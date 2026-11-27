#!/usr/bin/env python3
"""Development-only execution evidence. No selective execution or release authorization."""
import json
import os
import re
import subprocess
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
    require(bool(jobs) and all(j["status"] == "completed" and j["conclusion"] == "success"
                               and j["head_sha"] == sha and j["run_id"] == run["id"]
                               and j["run_attempt"] == run["run_attempt"] for j in jobs), "JOB_NOT_SUCCESS")
    ids = set()
    for template in row["artifacts"]:
        matches = [a for a in artifacts if a["name"] == template.format(sha=sha)]
        require(len(matches) == 1, "ARTIFACT_MISSING_OR_AMBIGUOUS")
        a = matches[0]
        require(a["expired"] is False and type(a["id"]) is int and a["id"] > 0
                and a["size_in_bytes"] > 0 and a["id"] not in ids, "ARTIFACT_INVALID")
        ids.add(a["id"])
    return {"path": row["path"], "runId": run["id"], "attempt": run["run_attempt"], "artifactIds": sorted(ids)}

def evaluate(get, required, sha, repo):
    require(re.fullmatch(r"[0-9a-f]{40}", sha) and re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo), "INVALID_CONTEXT")
    prefix = "/repos/" + repo
    branch = prefix + "/branches/base_dev"
    require(get(branch)["commit"]["sha"] == sha, "STALE_SHA")
    evidence = []
    for row in required:
        workflow = urllib.parse.quote(row["path"].rsplit("/", 1)[1], safe="")
        runs = pages(get, prefix + "/actions/workflows/" + workflow + "/runs?branch=base_dev&event=push&head_sha=" + sha, "workflow_runs")
        require(bool(runs), "RUN_MISSING")
        run = max(runs, key=lambda r: r["id"])
        run_path = prefix + "/actions/runs/" + str(run["id"])
        jobs = pages(get, run_path + "/attempts/" + str(run["run_attempt"]) + "/jobs", "jobs")
        artifacts = pages(get, run_path + "/artifacts", "artifacts")
        evidence.append(check_gate(row, run, jobs, artifacts, sha))
        fresh = get(run_path)
        require(all(fresh[k] == run[k] for k in ("id", "run_attempt", "status", "conclusion", "head_sha")), "RUN_CHANGED")
    require(get(branch)["commit"]["sha"] == sha, "STALE_SHA")
    return evidence

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
    print(json.dumps(result, indent=2))
    return int(result["developmentAdmission"] != "PASS")

if __name__ == "__main__":
    raise SystemExit(main())
