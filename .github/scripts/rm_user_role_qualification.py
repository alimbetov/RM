#!/usr/bin/env python3
"""Evidence-only service role case report, never inferred from source-file presence."""
import hashlib
import json
from pathlib import Path

REGISTRY = Path("docs/spec/RM-UR-R3.1-JUNIT-CASES-v1.json")
REGISTRY_R32 = Path("docs/spec/RM-UR-R3.2-JUNIT-CASES-v1.json")
REGISTRY_R33 = Path("docs/spec/RM-UR-R3.3-JUNIT-CASES-v1.json")
CROSSWALK = Path("docs/spec/RM-R3-USER-ROLE-CROSSWALK-v1.json")
RESULT = Path("rm-user-role-scenario-results.json")


def assess_role_cases(root, passed_cases, sha, admission_pass, write=True):
    raw = (root / REGISTRY).read_bytes()
    registry = json.loads(raw)
    if registry.get("schema") != "RM-UR-R3.1-JUNIT-CASES/v1":
        raise ValueError("Unknown R3.1 role test case catalogue")
    raw32 = (root / REGISTRY_R32).read_bytes()
    next_registry = json.loads(raw32)
    if next_registry.get("schema") != "RM-UR-R3.2-JUNIT-CASES/v1":
        raise ValueError("Unknown R3.2 role test case catalogue")
    raw33 = (root / REGISTRY_R33).read_bytes()
    third_registry = json.loads(raw33)
    if third_registry.get("schema") != "RM-UR-R3.3-JUNIT-CASES/v1":
        raise ValueError("Unknown R3.3 role case catalogue")
    crosswalk = json.loads((root / CROSSWALK).read_text(encoding="utf-8"))
    entries = {x["requirementId"]: x for x in crosswalk["requirements"]}
    known = set()
    rows = []
    for case in registry["cases"] + next_registry["cases"] + third_registry["cases"]:
        cid, suite, method = (case.get(k) for k in
                              ("caseId", "junitSuite", "junitMethod"))
        if not cid or cid in known:
            raise ValueError("Duplicate or invalid role case ID")
        known.add(cid)
        artifact = case.get("evidenceArtifact")
        if not artifact or not suite or not method:
            raise ValueError("Missing exact Surefire proof key: " + cid)
        if (case.get("mandatory") is not True or
                case.get("evidenceStatus") != "DECLARED_NOT_EXECUTED_ON_CURRENT_SHA"):
            raise ValueError("Role case falsely declared executable: " + cid)
        for rid in case.get("requirementIds", []):
            if rid not in entries or cid not in entries[rid]["userRoleCaseIds"]:
                raise ValueError("Role case absent from R3 crosswalk: " + cid)
        executed = (suite, method) in passed_cases.get(artifact, set())
        rows.append({
            "caseId": cid, "requirementIds": case["requirementIds"],
            "actorProfiles": case["actorProfiles"],
            "dimensions": case["dimensions"],
            "proof": {"artifact": artifact, "suite": suite, "test": method},
            "executed": executed,
            "status": ("VERIFIED_INTERNAL" if admission_pass else "EXECUTED_NOT_ADMITTED")
                      if executed else "NOT_RUN",
        })
    total = len(rows)
    passing = sum(x["executed"] for x in rows)
    qualified = total > 0 and passing == total and admission_pass
    result = {
        "schema": "RM-UR-R3-RESULT/v2",
        "commitSha": sha,
        "catalogueSha256": hashlib.sha256(raw+raw32+raw33).hexdigest(),
        "r31Cases": len(registry["cases"]), "r32Cases": len(next_registry["cases"]), "r33Cases": len(third_registry["cases"]),
        "scope": "JAVA_SERVICE_JUNIT_POSTGRESQL",
        "productionAdmission": "HOLD",
        "mandatory": total,
        "executedPostgresql": passing,
        "verifiedInternal": passing if admission_pass else 0,
        "gate": "USER_ROLE_SERVICE_QUALIFIED" if qualified else "HOLD",
        "cases": rows
    }
    if write:
        (root / RESULT).write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n",
                                   encoding="utf-8")
    return result
