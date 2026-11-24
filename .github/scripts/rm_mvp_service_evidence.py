#!/usr/bin/env python3
"""RM MVP W6: fail-closed exact-SHA execution evidence for 54 CORE-P0 slots.

The mapping is a reviewed SERVICE-only business crosswalk, not a claim about
real OIDC, HTTP, web, payments, production or live customer operations.
"""
from verify_ret04_evidence import METHODS as RET04_METHODS
import hashlib
import json
from pathlib import Path

SOURCE = Path("docs/strategy/RM-DUAL-PRODUCT-CAPABILITIES-R1.json")
MAPPING = Path("docs/spec/RM-MVP-CORE-P0-SERVICE-EVIDENCE-v1.json")
REPORT = Path("rm-mvp-service-coverage.json")
ARTIFACT = "kms-postgres-surefire"


def require(value, message):
    if not value:
        raise ValueError(message)


def read(root, name):
    data = (root / name).read_bytes()
    return json.loads(data), hashlib.sha256(data).hexdigest()


def assess(root, passed_cases, sha, admission_pass, write=True):
    canonical, canonical_hash = read(root, SOURCE)
    registry, mapping_hash = read(root, MAPPING)
    expected = [
        ident for capability in canonical["capabilities"]
        for ident in capability.get("scenarioIds", [])
    ]
    require(len(expected) == 54 and len(set(expected)) == 54,
            "W6 canonical 54 IDs changed or duplicated")
    require(registry.get("sourceRegistry") == SOURCE.as_posix() and
            registry.get("evidenceArtifact") == ARTIFACT,
            "W6 source or PostgreSQL evidence artifact changed")
    cases = registry.get("scenarios")
    require(isinstance(cases, list) and len(cases) == 54 and
            len({c.get("id") for c in cases}) == 54 and
            {c.get("id") for c in cases} == set(expected),
            "W6 missing, duplicated or fabricated CORE-P0 IDs")
    addendum = registry.get("taskAddendum")
    require(isinstance(addendum, list) and
            {x.get("id") for x in addendum} == {"BUS-TASK-01", "BUS-TASK-02"} and
            len(addendum) == 2, "W6 mandatory TASKS addendum incomplete")
    require(isinstance(sha, str) and len(sha) == 40 and
            all(x in "0123456789abcdef" for x in sha),
            "W6 invalid commit SHA")

    pg_cases = passed_cases.get(ARTIFACT, set())
    require(isinstance(pg_cases, set), "W6 PostgreSQL execution set invalid")
    checks = []
    missing = []
    methods = set()
    mapping_gaps = []
    for entry in [*cases, *addendum]:
        ident = entry.get("id")
        require(isinstance(entry.get("behavior"), str) and
                len(entry["behavior"].strip()) > 24,
                "W6 behavior must be explicitly described: " + str(ident))
        proofs = entry.get("requiredProofs")
        verdict = entry.get("mappingVerdict")
        require(verdict in {"EXACT", "SUPPORTING_PARTIAL", "GAP"},
                "W6 semantic mapping verdict absent or invalid: " + str(ident))
        require(isinstance(proofs, list) and (verdict != "EXACT" or len(proofs) > 0),
                "W6 executable proof absent: " + str(ident))
        if verdict != "EXACT":
            require(isinstance(entry.get("openRequirement"), str) and entry["openRequirement"].strip(),
                    "W6 semantic gap reason absent: " + str(ident))
            mapping_gaps.append({"id": ident, "verdict": verdict, "reason": entry["openRequirement"]})
        if ident == "CORE-RET-04":
            require(entry.get("evidenceProfile") == "RET04-D/v1" and
                    verdict == "SUPPORTING_PARTIAL" and
                    {(p.get("suite"), p.get("test")) for p in proofs} ==
                    {("QrWarehouseHandoverQualificationTest", m) for m in RET04_METHODS},
                    "RET04-D requires all 24 methods and partial verdict until global artifact integration")
        seen = set()
        bound = []
        for proof in proofs:
            suite, method = proof.get("suite"), proof.get("test")
            require(isinstance(suite, str) and suite.endswith("Test") and
                    isinstance(method, str) and len(method) > 5,
                    "W6 invalid JUnit method: " + str(ident))
            pair = (suite, method)
            require(pair not in seen, "W6 duplicate same-ID proof: " + ident)
            seen.add(pair)
            methods.add(pair)
            found = pair in pg_cases
            bound.append({"suite": suite, "method": method,
                          "artifact": ARTIFACT, "executedPostgresql": found})
            if not found:
                missing.append({"id": ident, "suite": suite, "method": method})
        checks.append({"id": ident, "behavior": entry["behavior"],
                       "mappingVerdict": verdict,
                       "qualification": "VERIFIED_INTERNAL" if verdict == "EXACT" and bool(bound) and all(
                           p["executedPostgresql"] for p in bound)
                       else "HOLD", "evidence": bound})
    gate = "CORE_SERVICE_EVIDENCE_QUALIFIED" if not missing and not mapping_gaps and admission_pass else "HOLD"
    report = {
        "schema": "RM-MVP-SERVICE-EVIDENCE/v1",
        "exactSha": sha,
        "scope": "SERVICE_ONLY; NOT live OIDC/HTTP/production admission",
        "gate": gate,
        "productionAdmission": "HOLD",
        "canonicalSha256": canonical_hash,
        "mappingSha256": mapping_hash,
        "totalCoreP0": 54,
        "verifiedCoreP0": sum(x["qualification"] == "VERIFIED_INTERNAL"
                              for x in checks[:54]),
        "taskAddendumTotal": 2,
        "verifiedTaskAddenda": sum(x["qualification"] == "VERIFIED_INTERNAL"
                                    for x in checks[54:]),
        "uniqueExecutedMethodsMapped": len(methods & pg_cases),
        "totalUniqueMappedMethods": len(methods),
        "missingExecution": missing,
        "semanticGaps": mapping_gaps,
        "scenarios": checks,
    }
    if write:
        (root / REPORT).write_text(
            json.dumps(report, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8")
    return report
