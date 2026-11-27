#!/usr/bin/env python3
"""R3.6C.1: bounded dependency audit. Does not grant selective execution."""
import json
import hashlib
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
                         "reviewedSha", "reviewedDependencyFingerprint", "reviewedDependencyCount", "evidence"}
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

def review_matches(entries, row):
    count, digest = dependency_fingerprint(entries, row["path"])
    return count == row["reviewedDependencyCount"] and digest == row["reviewedDependencyFingerprint"]

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
    return True

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
                reviewFingerprintMatches=fingerprint_matches,
                qualifiedForShadowPlanning=planning,
                executionAllowed=False, decision="FULL_REQUIRED", blockers=blockers)

def main():
    row = load_contract(ROOT)
    print(json.dumps(audit(ROOT, sha(ROOT, "HEAD"), row["path"]), indent=2))

if __name__ == "__main__":
    main()
