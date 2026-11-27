#!/usr/bin/env python3
"""R3.6C.1: bounded dependency audit. Does not grant selective execution."""
import json
import hashlib
import argparse
import re
import subprocess
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ".github/qualification/rm-ci-selective-allowlist.json"
POLICY = {"schema": "RM-CI-R3.6C.1/v1", "executionMode": "SHADOW_ONLY",
          "defaultDecision": "FULL_REQUIRED", "releasePolicy": "FULL_EXACT_SHA",
          "evidenceReuse": "FORBIDDEN"}

def require(ok, reason):
    if not ok:
        raise ValueError(reason)

def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root, stderr=subprocess.PIPE)

def sha(root, ref):
    value = git(root, "rev-parse", "--verify", ref + "^{commit}").decode().strip()
    require(re.fullmatch(r"[0-9a-f]{40}", value), "INVALID_SHA")
    return value

def load_contract(root):
    data = json.loads((root / REGISTRY).read_text(encoding="utf-8"))
    require(set(data) == set(POLICY) | {"qualifiedAllowlist", "candidateAllowlist", "rejected"}
            and all(data[k] == v for k, v in POLICY.items()), "POLICY_DRIFT")
    require(data["candidateAllowlist"] == [], "PILOT_SCOPE")
    qualified = data["qualifiedAllowlist"]
    require(len(qualified) == 1, "PILOT_SCOPE")
    row = qualified[0]
    require(set(row) == {"path", "mode", "scope", "dependencyClosure", "executionAllowed",
                         "reviewedSha", "reviewedDependencyFingerprint", "reviewedDependencyCount", "evidence", "planningOverlay"}
            and row["path"] == "docs/reviews/RM-B2-1R-CI-RECOVERY.md"
            and row["mode"] == "FAST_DOC" and row["scope"] == "SHADOW_PLANNING_ONLY"
            and row["dependencyClosure"] == "BOUNDED_STATIC_REVIEW"
            and row["executionAllowed"] is False, "PILOT_CONTRACT")
    require(re.fullmatch(r"[0-9a-f]{40}", row["reviewedSha"])
            and re.fullmatch(r"[0-9a-f]{64}", row["reviewedDependencyFingerprint"])
            and type(row["reviewedDependencyCount"]) is int and row["reviewedDependencyCount"] > 0,
            "BASELINE_CONTRACT")
    evidence = row["evidence"]
    require(set(evidence) == {"dependencyAuditRun", "dependencyAuditJob", "dependencyAuditArtifact",
                              "fullExactShaRun", "testsPassed", "mandatoryWorkflowReviewCount", "limitations"}
            and evidence["testsPassed"] == 8 and evidence["mandatoryWorkflowReviewCount"] == 12
            and all(type(evidence[k]) is int and evidence[k] > 0 for k in
                    ("dependencyAuditRun", "dependencyAuditJob", "dependencyAuditArtifact", "fullExactShaRun"))
            and set(evidence["limitations"]) == {"DYNAMIC_TRANSITIVE_READS_NOT_PROVEN",
                    "EXTERNAL_ENVIRONMENT_UNQUALIFIED", "BRANCH_PROTECTION_UNAVAILABLE"}, "EVIDENCE_CONTRACT")
    overlay = row["planningOverlay"]
    require(set(overlay) == {"schema", "parentSha", "changes"}
            and overlay["schema"] == "RM-CI-R3.6C.2/overlay-v1"
            and re.fullmatch(r"[0-9a-f]{40}", overlay["parentSha"]), "OVERLAY_CONTRACT")
    changes = overlay["changes"]
    require(len(changes) == 2 and {x["path"] for x in changes} == {
            ".github/workflows/rm-ci-impact-shadow.yml",
            ".github/qualification/rm-ci-workflow-dependencies.json"}, "OVERLAY_SCOPE")
    require(all(set(x) == {"path", "reviewedBlob", "planningBlob"}
                and all(re.fullmatch(r"[0-9a-f]{40}", x[k]) for k in ("reviewedBlob", "planningBlob"))
                and x["reviewedBlob"] != x["planningBlob"] for x in changes), "OVERLAY_BLOBS")
    require(all(x["path"] != row["path"] for x in data["rejected"]), "REJECTED_CANDIDATE")
    return row

def tracked(root, commit):
    result = {}
    for record in git(root, "ls-tree", "-r", "-z", commit).split(b"\0"):
        if not record:
            continue
        header, name = record.split(b"\t", 1)
        mode, kind, oid = header.decode("ascii").split()
        path = name.decode("utf-8", errors="strict")
        require(path not in result, "DUPLICATE_PATH")
        require(kind == "blob" and mode in ("100644", "100755")
                and re.fullmatch(r"[0-9a-f]{40}", oid), "UNSUPPORTED_TREE_ENTRY")
        result[path] = (mode, oid)
    require(bool(result), "EMPTY_TREE")
    return result

# The three C.1 audit/control files are excluded only from the historical
# review fingerprint so this acceptance commit can record the review.
# inspect_diff excludes NONE of them: changes to any control file require FULL.
REVIEW_CONTROL = {REGISTRY, ".github/scripts/verify_rm_ci_selective_allowlist.py",
                  ".github/scripts/test_verify_rm_ci_selective_allowlist.py"}

def dependency_fingerprint(entries, candidate):
    rows = [[p, mode, oid] for p, (mode, oid) in sorted(entries.items())
            if p != candidate and p not in REVIEW_CONTROL]
    payload = json.dumps(rows, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    return len(rows), hashlib.sha256(payload).hexdigest()

def reviewed_fingerprint(entries, row):
    normalized = dict(entries)
    # Only the exact reviewed C.2 integration blobs may normalize to C.1.
    # A third version, missing file or mode drift invalidates the review.
    for change in row.get("planningOverlay", {}).get("changes", []):
        p = change["path"]
        require(normalized.get(p) == ("100644", change["planningBlob"]), "OVERLAY_DRIFT")
        normalized[p] = ("100644", change["reviewedBlob"])
    return dependency_fingerprint(normalized, row["path"])

def review_matches(entries, row):
    try:
        count, digest = reviewed_fingerprint(entries, row)
        return count == row["reviewedDependencyCount"] and digest == row["reviewedDependencyFingerprint"]
    except ValueError:
        return False

def inspect_diff(root, before, head, candidate):
    """Candidate only when both committed trees differ in one regular existing Markdown blob."""
    require(all(re.fullmatch(r"[0-9a-f]{40}", x) for x in (before, head))
            and before != head and before != "0"*40, "INVALID_RANGE")
    require(sha(root, "HEAD") == head and sha(root, before) == before, "CHECKOUT_MISMATCH")
    git(root, "merge-base", "--is-ancestor", before, head)
    old, new = tracked(root, before), tracked(root, head)
    changes = sorted(p for p in old.keys() | new.keys() if old.get(p) != new.get(p))
    if changes != [candidate] or candidate not in old or candidate not in new:
        return False
    if old[candidate][0] != "100644" or new[candidate][0] != "100644":
        return False
    for commit in (before, head):
        content = git(root, "show", commit + ":" + candidate)
        require(b"\0" not in content, "BINARY_DOCUMENT")
        content.decode("utf-8", errors="strict")
    # The endpoint tree comparison alone hides transient changes that were
    # reverted within the push. Every commit must be a single-parent edit of
    # the same existing regular document, with no other changed tree entry.
    # Limit the work and fail closed for merges or unexpectedly long ranges.
    history = git(root, "rev-list", "--reverse", "--parents", "--max-count=129",
                  before + ".." + head).decode("ascii").splitlines()
    if not history or len(history) > 128:
        return False
    parent = before
    parent_tree = old
    for line in history:
        parts = line.split()
        if len(parts) != 2 or parts[1] != parent:
            return False
        commit = parts[0]
        current = tracked(root, commit)
        changed = {p for p in parent_tree.keys() | current.keys()
                   if parent_tree.get(p) != current.get(p)}
        if (changed != {candidate} or candidate not in current
                or current[candidate][0] != "100644"):
            return False
        body = git(root, "show", commit + ":" + candidate)
        require(b"\0" not in body, "BINARY_DOCUMENT")
        body.decode("utf-8", errors="strict")
        parent, parent_tree = commit, current
    return parent == head

def grep(root, commit, patterns, paths, fixed=True):
    args = ["git", "grep", "-n", "-I", "-F" if fixed else "-E"]
    for p in patterns:
        args += ["-e", p]
    result = subprocess.run(args + [commit, "--", *paths], cwd=root, capture_output=True)
    require(result.returncode in (0, 1), "GREP_FAILED")
    return result.stdout.decode("utf-8", errors="strict").splitlines()

def audit(root, commit, candidate):
    require(sha(root, "HEAD") == commit, "CHECKOUT_MISMATCH")
    entries = tracked(root, commit)
    require(candidate in entries and entries[candidate][0] == "100644", "CANDIDATE_NOT_REGULAR")
    body = git(root, "show", commit + ":" + candidate)
    require(b"\0" not in body, "BINARY_DOCUMENT")
    body.decode("utf-8", errors="strict")
    path = PurePosixPath(candidate)
    # Scan all tracked machine-readable text, not merely CI paths.
    # Markdown references are separated: governance still requires human review.
    machine = grep(root, commit, [candidate, path.name, str(path.parent)],
                   [".", ":(exclude,glob)**/*.md"])
    # The audit itself and its registry mention the candidate intentionally.
    self_paths = {REGISTRY, ".github/scripts/verify_rm_ci_selective_allowlist.py",
                  ".github/scripts/test_verify_rm_ci_selective_allowlist.py"}
    def external(line):
        location = line.split(":", 2)[1]
        return location not in self_paths
    machine = [line for line in machine if external(line)]
    docs = grep(root, commit, [candidate, path.name], [":(glob)**/*.md"])
    # Broad readers / package commands must be reviewed; grep is not a proof of semantic closure.
    broad = grep(root, commit,
                 [r"docs/\*|docs/\*\*|glob\(|rglob\(|Files\.walk|git ls-files|git archive|find[[:space:]]+docs|readFileSync\("],
                 [".github", "scripts", "src", "pom.xml"], fixed=False)
    broad = [line for line in broad if external(line)]
    qualification = load_contract(root)
    require(qualification["path"] == candidate, "CANDIDATE_MISMATCH")
    count, digest = dependency_fingerprint(entries, candidate)
    fingerprint_matches = review_matches(entries, qualification)
    normalized_digest = reviewed_fingerprint(entries, qualification)[1] if fingerprint_matches else None
    direct = [line for line in machine if candidate in line or path.name in line]
    planning = fingerprint_matches and not direct and not docs
    blockers = list(qualification["evidence"]["limitations"])
    if not fingerprint_matches:
        blockers.append("REVIEW_DEPENDENCY_DRIFT")
    if direct or docs:
        blockers.append("NEW_DIRECT_REFERENCE")
    return dict(POLICY, sha=commit, candidate=candidate, trackedFiles=len(entries),
                machineReferences=machine, documentationReferences=docs,
                broadReaderCandidates=broad,
                dependencyClosure="BOUNDED_STATIC_REVIEW" if planning else "UNQUALIFIED",
                reviewedSha=qualification["reviewedSha"],
                reviewedDependencyFingerprint=qualification["reviewedDependencyFingerprint"],
                actualDependencyFingerprint=digest, actualDependencyCount=count,
                normalizedReviewedFingerprint=normalized_digest,
                reviewFingerprintMatches=fingerprint_matches,
                qualifiedForShadowPlanning=planning,
                executionAllowed=False, decision="FULL_REQUIRED", blockers=blockers)

def build_plan(root, before, head, row, gates, impact, event, ref, release=False):
    """Deterministic advisory result. No trigger, release or evidence-reuse authority."""
    gates = sorted(gates)
    require(len(gates) == 12 and len(set(gates)) == 12, "MANDATORY_GATE_SET")
    result = {"schema": "RM-CI-R3.6C.2/v1", "before": before, "head": head,
              "event": event, "ref": ref, "releaseRequested": release,
              "shadowOnly": True, "executionAllowed": False,
              "actualDecision": "FULL_REQUIRED", "releasePolicy": "FULL_EXACT_SHA",
              "releaseAdmission": "DENIED", "evidenceReuse": "FORBIDDEN",
              "otherWorkflowPolicy": "EXISTING_TRIGGERS_UNCHANGED",
              "planMode": "FULL_REQUIRED", "reason": "UNVERIFIED_PLAN_INPUT",
              "plannedGates": gates, "deferredCandidates": [],
              "requiredExecutedGates": gates, "candidate": row["path"],
              "reviewedDependencyFingerprint": row["reviewedDependencyFingerprint"]}
    try:
        if release or event != "push" or ref != "refs/heads/base_dev":
            result["reason"] = "NON_DEVELOPMENT_PUSH_OR_RELEASE"
        elif not inspect_diff(root, before, head, row["path"]):
            result["reason"] = "NOT_EXACT_SINGLE_DOCUMENT_CHANGE"
        elif (impact.get("schema") != "RM-CI-R3-SHADOW/v1"
              or impact.get("before") != before or impact.get("head") != head
              or impact.get("shadowOnly") is not True
              or impact.get("admission") != "UNCHANGED_FULL_REQUIRED"
              or impact.get("detail") != "VERIFIED_ANCESTOR_DIFF"
              or impact.get("paths") != [row["path"]]
              or sorted(impact.get("requiredExecutedGates", [])) != gates):
            result["reason"] = "LEGACY_IMPACT_MISMATCH"
        else:
            anchor = row.get("planningOverlay", {}).get("parentSha", row["reviewedSha"])
            git(root, "merge-base", "--is-ancestor", anchor, before)
            require(not git(root, "status", "--porcelain", "--untracked-files=no").strip(), "DIRTY_CHECKOUT")
            if not all(review_matches(tracked(root, c), row) for c in (before, head)):
                result["reason"] = "REVIEW_DEPENDENCY_DRIFT"
            else:
                observation = audit(root, head, row["path"])
                if (observation.get("qualifiedForShadowPlanning") is True
                    and observation.get("executionAllowed") is False
                    and observation.get("sha") == head):
                    result.update(planMode="FAST_DOC_SHADOW", reason="BOUNDED_DOCUMENT_PILOT",
                                  plannedGates=[], deferredCandidates=gates)
                else:
                    result["reason"] = "ALLOWLIST_AUDIT_DENIED"
    except (ValueError, subprocess.CalledProcessError, OSError, KeyError, TypeError, AttributeError):
        result["reason"] = "UNVERIFIED_PLAN_INPUT"
    # This digest covers the entire plan including the always-full execution policy.
    result["planDigest"] = hashlib.sha256(json.dumps(result, sort_keys=True,
        ensure_ascii=True, separators=(",", ":")).encode("ascii")).hexdigest()
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", nargs=2, metavar=("BEFORE", "HEAD"))
    parser.add_argument("--impact", type=Path)
    parser.add_argument("--event", default="")
    parser.add_argument("--ref", default="")
    parser.add_argument("--release", action="store_true")
    args = parser.parse_args()
    row = load_contract(ROOT)
    if not args.plan:
        print(json.dumps(audit(ROOT, sha(ROOT, "HEAD"), row["path"]), indent=2))
        return
    # Existing exact gate contract validates inventory identities and artifact sets.
    # Invalid gate inventory aborts the job rather than publishing an incomplete plan.
    from verify_rm_development_admission import load_contract as load_gates
    gates = [r["name"] for r in load_gates(ROOT)]
    try:
        impact = json.loads(args.impact.read_text(encoding="utf-8")) if args.impact else {}
    except (OSError, ValueError):
        impact = {}
    print(json.dumps(build_plan(ROOT, *args.plan, row, gates, impact,
                                args.event, args.ref, args.release), indent=2, sort_keys=True))

if __name__ == "__main__":
    main()
