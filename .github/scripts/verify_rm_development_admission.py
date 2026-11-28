#!/usr/bin/env python3
"""Development-only execution evidence. No selective execution or release authorization."""
import json
from datetime import datetime
import os
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from verify_rm_ci_workflow_inventory import verify as verify_inventory

ROOT = Path(__file__).resolve().parents[2]
POLICY = {"schema": "RM-CI-R3.6B/v1", "purpose": "DEVELOPMENT_ONLY",
          "executionPolicy": "UNCHANGED", "fallback": "FULL_REQUIRED",
          "reuse": "FORBIDDEN", "releaseAdmission": "DENIED"}

def require(condition, reason):
    if not condition:
        raise ValueError(reason)

def load_contract(root):
    c = json.loads((root / ".github/qualification/rm-development-admission.json").read_text())
    inventory = json.loads((root / ".github/qualification/rm-ci-workflow-dependencies.json").read_text())
    require(not verify_inventory(root, inventory), "INVENTORY_INVALID")
    require(set(c) == set(POLICY) | {"required"} and all(c[k] == v for k, v in POLICY.items()), "POLICY_INVALID")
    expected = {(r["path"], r["workflowName"]) for r in inventory["workflows"] if r["mandatoryRelease"]}
    required = c["required"]
    require(len(required) == 12 and {(r["path"], r["name"]) for r in required} == expected, "GATE_SET_INVALID")
    exact = (root / ".github/workflows/rm-exact-sha-admission.yml").read_text()
    artifacts = {name: [x.strip().replace("'", "").replace("+sha", "{sha}") for x in values.split(",")]
                 for name, values in re.findall(r"\{name:'([^']+)',artifacts:\[([^\]]+)\]\}", exact)}
    for row in required:
        require(set(row) == {"path", "name", "artifacts"} and row["artifacts"] == artifacts.get(row["name"]) and bool(row["artifacts"]), "ARTIFACT_CONTRACT_INVALID")
    return required

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

def api(path, token):
    req = urllib.request.Request("https://api.github.com" + path,
                                 headers={"Authorization": "Bearer " + token,
                                          "Accept": "application/vnd.github+json",
                                          "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.build_opener(NoRedirect()).open(req, timeout=30) as response:
        return json.load(response)

def pages(get, path, key):
    result = []
    for page in range(1, 101):
        data = get(path + ("&" if "?" in path else "?") + "per_page=100&page=" + str(page))
        rows = data[key]
        require(isinstance(rows, list), "INVALID_API_PAGE")
        result.extend(rows)
        if len(rows) < 100:
            return result
    raise ValueError("PAGINATION_LIMIT")

def check_gate(row, run, jobs, artifacts, sha):
    require(run["head_sha"] == sha and run["head_branch"] == "base_dev" and run["event"] == "push"
            and run["path"] == row["path"] and run["name"] == row["name"], "RUN_IDENTITY")
    require(run["status"] == "completed" and run["conclusion"] == "success"
            and type(run["run_attempt"]) is int and run["run_attempt"] > 0, "RUN_NOT_SUCCESS")
    # GitHub artifacts are run-scoped, not attempt-scoped. Never infer rerun provenance.
    require(run["run_attempt"] == 1, "RERUN_PROVENANCE_UNAVAILABLE")
    require(run["repository"]["id"] == run["head_repository"]["id"], "FOREIGN_HEAD_REPOSITORY")
    # Identity violations remain terminal even when a job is not settled.
    # An empty list or incomplete status can be a lagging API view; never
    # admit it. The bounded outer poll must obtain complete fresh evidence.
    require(all(j["head_sha"] == sha and j["run_id"] == run["id"]
                and j["run_attempt"] == run["run_attempt"] for j in jobs), "JOB_IDENTITY")
    require(bool(jobs), "JOB_PENDING")
    require(all(j["conclusion"] in (None, "success") for j in jobs), "JOB_NOT_SUCCESS")
    require(all(j["status"] == "completed" and j["conclusion"] is not None
                for j in jobs), "JOB_PENDING")
    ids = set()
    for template in row["artifacts"]:
        matches = [a for a in artifacts if a["name"] == template.format(sha=sha)]
        require(len(matches) == 1, "ARTIFACT_MISSING_OR_AMBIGUOUS")
        a = matches[0]
        require(a["expired"] is False and type(a["id"]) is int and a["id"] > 0
                and a["size_in_bytes"] > 0 and a["id"] not in ids, "ARTIFACT_INVALID")
        provenance = a["workflow_run"]
        require(provenance["id"] == run["id"] and provenance["head_sha"] == sha
                and provenance["head_branch"] == "base_dev"
                and provenance["repository_id"] == run["repository"]["id"]
                and provenance["head_repository_id"] == run["head_repository"]["id"], "ARTIFACT_PROVENANCE")
        require(re.fullmatch(r"sha256:[0-9a-f]{64}", a["digest"]), "ARTIFACT_DIGEST_MISSING")
        def instant(value):
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            require(parsed.tzinfo is not None, "INVALID_TIMESTAMP")
            return parsed
        require(instant(run["run_started_at"]) <= instant(a["created_at"])
                <= instant(a["updated_at"]) <= instant(run["updated_at"]), "ARTIFACT_TIME_SCOPE")
        ids.add(a["id"])
    return {"path": row["path"], "runId": run["id"], "attempt": run["run_attempt"], "artifactIds": sorted(ids)}

def evaluate(get, required, sha, repo):
    require(re.fullmatch(r"[0-9a-f]{40}", sha) and re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo), "INVALID_CONTEXT")
    prefix = "/repos/" + repo
    branch = prefix + "/branches/base_dev"
    require(get(branch)["commit"]["sha"] == sha, "STALE_SHA")
    evidence = []
    observed = []
    require(bool(required) and len({r["path"] for r in required}) == len(required), "INVALID_REQUIRED_SET")
    for row in required:
        workflow = urllib.parse.quote(row["path"].rsplit("/", 1)[1], safe="")
        runs = pages(get, prefix + "/actions/workflows/" + workflow + "/runs?branch=base_dev&event=push&head_sha=" + sha, "workflow_runs")
        require(bool(runs), "RUN_MISSING")
        run = max(runs, key=lambda r: r["id"])
        require(run["status"] == "completed", "RUN_PENDING")
        require(run["conclusion"] == "success", "RUN_NOT_SUCCESS")
        require(run["run_attempt"] == 1, "RERUN_PROVENANCE_UNAVAILABLE")
        run_path = prefix + "/actions/runs/" + str(run["id"])
        jobs = pages(get, run_path + "/attempts/" + str(run["run_attempt"]) + "/jobs", "jobs")
        artifacts = pages(get, run_path + "/artifacts", "artifacts")
        try:
            evidence.append(check_gate(row, run, jobs, artifacts, sha))
        except ValueError as exc:
            exc.workflow_name = row["name"]
            exc.workflow_run_id = run["id"]
            raise
        observed.append((row, run, run_path, workflow))
    # Revisit ALL gates after collection, not only immediately after each gate.
    # This detects observed changes but is not an atomic GitHub snapshot.
    for row, run, run_path, workflow in observed:
        latest = pages(get, prefix + "/actions/workflows/" + workflow + "/runs?branch=base_dev&event=push&head_sha=" + sha, "workflow_runs")
        require(bool(latest) and max(latest, key=lambda r: r["id"])["id"] == run["id"], "RUN_CHANGED")
        fresh = get(run_path)
        require(all(fresh[k] == run[k] for k in ("id", "run_attempt", "status", "conclusion", "head_sha", "updated_at")), "RUN_CHANGED")
        artifacts = pages(get, run_path + "/artifacts", "artifacts")
        prior = next(e for e in evidence if e["path"] == row["path"])
        names = {x.format(sha=sha) for x in row["artifacts"]}
        require(sorted(a["id"] for a in artifacts if a["name"] in names and a["expired"] is False) == prior["artifactIds"], "ARTIFACT_CHANGED")
    require(get(branch)["commit"]["sha"] == sha, "STALE_SHA")
    return evidence

def retryable(exc):
    # Only observation failures, never terminal provenance/qualification
    # failures. The workflow already has a finite 160-poll deadline.
    return ((isinstance(exc, ValueError) and str(exc) in
             {"RUN_MISSING", "RUN_PENDING", "JOB_PENDING"})
            or (isinstance(exc, urllib.error.HTTPError) and exc.code in
                {500, 502, 503, 504}))


def main():
    result = dict(POLICY, developmentAdmission="FAIL", evidenceScope="EXECUTION_METADATA_ONLY",
                  selectiveExecution="DENIED", evidence=[])
    try:
        sha = os.environ["GITHUB_SHA"]
        repo = os.environ["GITHUB_REPOSITORY"]
        token = os.environ["GH_TOKEN"]
        require(bool(token), "TOKEN_MISSING")
        require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() == sha, "CHECKOUT_MISMATCH")
        require(not subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=ROOT, text=True).strip(), "DIRTY_CHECKOUT")
        result.update(sha=sha, repository=repo)
        result["evidence"] = evaluate(lambda p: api(p, token), load_contract(ROOT), sha, repo)
        result["developmentAdmission"] = "PASS"
    except Exception as exc:
        result["reason"] = type(exc).__name__ + ": " + str(exc)
        result["retryable"] = retryable(exc)
        if hasattr(exc, "workflow_name"):
            result["failedWorkflow"] = exc.workflow_name
            result["failedRunId"] = exc.workflow_run_id
    print(json.dumps(result, indent=2))
    return int(result["developmentAdmission"] != "PASS")

if __name__ == "__main__":
    raise SystemExit(main())
