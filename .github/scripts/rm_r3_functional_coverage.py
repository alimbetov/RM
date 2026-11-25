#!/usr/bin/env python3
"""Evidence-derived R3 capability coverage; deliberately scoped to curated catalogue v1."""
import hashlib
import json
from pathlib import Path

CATALOG = Path("docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json")
JSON_OUT = Path("rm-r3-functional-coverage.json")
MARKDOWN_OUT = Path("rm-r3-functional-coverage.md")
DOMAINS = (
    "IAM_RBAC", "CRM_CORE", "CRM_INTERACTION", "CATALOG", "STOREFRONT",
    "SALES_AUTOORDER", "INVENTORY", "FILES", "ORGANIZATION", "GEO", "KMS",
    "AUDIT", "TASKS", "FINANCE", "MARKETING", "SUBSCRIPTION",
)
POSTGRES_ARTIFACTS = {
    "kms-postgres-surefire",
    "iam-pii-populated-pg-surefire",
    "crm-r12-populated-upgrade-surefire",
    "inventory-0081-populated-postgresql",
    "sales-084-populated-postgresql",
    "rustfs-real-infra-e2e-surefire",
}

def percentage(n, d):
    return round(100 * n / d, 2) if d else 0.0

def assess(root, passed_cases, sha, admission_pass, write=True):
    """passed_cases is {artifact: {(suite, method), ...}} from validated Surefire XML.

    An admitted percentage requires the entire exact-SHA QA-01B gate to pass.
    Presence of a test class in Git never counts as executed evidence.
    """
    raw = (root / CATALOG).read_bytes()
    catalog = json.loads(raw)
    if catalog.get("schema") != "RM-R3-FUNCTIONAL-COVERAGE/v1":
        raise ValueError("Invalid RM R3 catalogue schema")
    domains = catalog.get("domains")
    if not isinstance(domains, list) or [d.get("id") for d in domains] != list(DOMAINS):
        raise ValueError("Coverage catalogue must enumerate exactly 16 canonical domains")
    ids, proofs, result_domains = set(), set(), []
    for domain in domains:
        requirements = domain.get("requirements")
        if not isinstance(requirements, list) or not requirements:
            raise ValueError("Domain has zero requirements: " + domain["id"])
        evaluated = []
        for req in requirements:
            rid, delivery, source, proof = (req.get(k) for k in
                                            ("id", "delivery", "source", "proof"))
            if not isinstance(rid, str) or not rid or rid in ids:
                raise ValueError("Duplicate or invalid requirement ID " + str(rid))
            ids.add(rid)
            if not isinstance(req.get("title"), str) or not req["title"].strip():
                raise ValueError("Missing title for " + rid)
            matched = False
            if delivery == "PLANNED":
                if source is not None or proof is not None:
                    raise ValueError("Planned requirement claims implementation: " + rid)
                status = "NOT_IMPLEMENTED"
            elif delivery == "IMPLEMENTED":
                if (not isinstance(source, str) or not source.startswith("src/main/java/")
                        or not source.endswith(".java") or ".." in Path(source).parts
                        or not (root / source).is_file()):
                    raise ValueError("Invalid/missing source for " + rid + ": " + str(source))
                if proof is not None:
                    if (not isinstance(proof, dict)
                            or set(proof) != {"artifact", "suite", "test"}
                            or not all(isinstance(x, str) and x for x in proof.values())):
                        raise ValueError("Invalid exact test proof for " + rid)
                    artifact = proof["artifact"]
                    if artifact not in POSTGRES_ARTIFACTS:
                        raise ValueError("Non-PostgreSQL proof for " + rid)
                    key = (artifact, proof["suite"], proof["test"])
                    if key in proofs:
                        raise ValueError("Same test claimed for two requirements: " + str(key))
                    proofs.add(key)
                    matched = (proof["suite"], proof["test"]) in passed_cases.get(artifact, set())
                status = ("VERIFIED_INTERNAL" if admission_pass else "EXECUTED_NOT_ADMITTED") \
                    if matched else "IMPLEMENTED_UNVERIFIED"
            else:
                raise ValueError("Unknown delivery " + str(delivery) + " for " + rid)
            evaluated.append({"id": rid, "title": req["title"], "delivery": delivery,
                              "source": source, "proof": proof, "testExecuted": matched,
                              "status": status})
        count = len(evaluated)
        implemented = sum(x["delivery"] == "IMPLEMENTED" for x in evaluated)
        executed = sum(x["testExecuted"] for x in evaluated)
        verified = executed if admission_pass else 0
        result_domains.append({
            "id": domain["id"], "name": domain["name"], "total": count,
            "implemented": implemented, "notImplemented": count - implemented,
            "testExecuted": executed, "verifiedInternal": verified,
            "implementedPct": percentage(implemented, count),
            "testEvidencePct": percentage(executed, count),
            "verifiedPct": percentage(verified, count),
            "requirements": evaluated,
        })
    total = sum(d["total"] for d in result_domains)
    implemented = sum(d["implemented"] for d in result_domains)
    tested = sum(d["testExecuted"] for d in result_domains)
    qualified = tested if admission_pass else 0
    result = {
        "schema": "RM-R3-COVERAGE-RESULT/v1",
        "commitSha": sha,
        "catalogueSha256": hashlib.sha256(raw).hexdigest(),
        "qualificationGate": "PASS" if admission_pass else "HOLD",
        "productionAdmission": "HOLD",
        "scope": catalog["scope"],
        "denominator": "Curated R3-v1 requirements only, not all RM product functions",
        "summary": {
            "domains": len(result_domains), "total": total, "implemented": implemented,
            "notImplemented": total - implemented, "testExecuted": tested,
            "verifiedInternal": qualified, "implementedPct": percentage(implemented, total),
            "testEvidencePct": percentage(tested, total),
            "verifiedPct": percentage(qualified, total),
        },
        "domains": result_domains,
    }
    if write:
        (root / JSON_OUT).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                                      encoding="utf-8")
        md = [
            "# RM-R3: Functional Coverage v1", "",
            f"Exact SHA: \`{sha}\`. RM-QA-01B: **{result['qualificationGate']}**. Production: **HOLD**.",
            "", "Percentages cover only the **" + str(total) + " explicitly catalogued R3-v1 capabilities**,",
            "not all possible CRM/ERP requirements. No test can be qualified without the full evidence gate.",
            "", "| Domain | Implemented | Executed PostgreSQL proof | Qualified internal | Missing |",
            "|---|---:|---:|---:|---:|",
        ]
        for d in result_domains:
            md.append(f"| {d['name']} | {d['implemented']}/{d['total']} ({d['implementedPct']}%) "
                      f"| {d['testExecuted']}/{d['total']} ({d['testEvidencePct']}%) "
                      f"| {d['verifiedInternal']}/{d['total']} ({d['verifiedPct']}%) "
                      f"| {d['notImplemented']} |")
        md += ["", "## Per-capability evidence", "",
               "| ID | Domain | Requirement | Delivery | Proof status |",
               "|---|---|---|---|---|"]
        for d in result_domains:
            for item in d["requirements"]:
                md.append(f"| {item['id']} | {d['name']} | {item['title']} "
                          f"| {item['delivery']} | {item['status']} |")
        (root / MARKDOWN_OUT).write_text("\n".join(md) + "\n", encoding="utf-8")
    return result
