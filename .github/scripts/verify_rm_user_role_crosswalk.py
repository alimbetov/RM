#!/usr/bin/env python3
"""UR-R3.0 structural crosswalk validation. Never manufactures JUnit evidence."""
import argparse
import hashlib
import json
import re
from pathlib import Path

CATALOG = Path("docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json")
CROSSWALK = Path("docs/spec/RM-R3-USER-ROLE-CROSSWALK-v1.json")
ROLE_CASES = Path("docs/spec/RM-UR-R3.1-JUNIT-CASES-v1.json")
ROLE_CASES_R32 = Path("docs/spec/RM-UR-R3.2-JUNIT-CASES-v1.json")
ROLE_CASES_R33 = Path("docs/spec/RM-UR-R3.3-JUNIT-CASES-v1.json")
PERMISSION_ENUM = Path("src/main/java/kz/rm/iam/DomainPermission.java")
EXPECTED_DOMAINS = (
    "IAM_RBAC", "CRM_CORE", "CRM_INTERACTION", "CATALOG", "STOREFRONT",
    "SALES_AUTOORDER", "INVENTORY", "FILES", "ORGANIZATION", "GEO",
    "KMS", "AUDIT", "TASKS", "FINANCE", "MARKETING", "SUBSCRIPTION",
)
ACTORS = {"U" + str(i) for i in range(13)}
STORIES = {"BS-" + f"{i:02d}" for i in range(1, 16)}
R34_REVIEWED_PROOFS = {
    "INV-02": ("kms-postgres-surefire", "InventoryReservationQualificationTest", "reservationReleaseAndIdempotentReplay"),
    "FIL-05": ("rustfs-real-infra-e2e-surefire", "RustFsRealClamAvE2ETest", "realClamdRespondsCleanInfectedAndFailClosedForUnavailableEndpoint"),
    "ORG-04": ("kms-postgres-surefire", "OrganizationLocationQueryQualificationTest", "paginationAndFiltersUseTenantBoundary"),
    "KMS-01": ("kms-postgres-surefire", "KmsPersistedRoundTripTest", "persistedEnvelopeRestoresUsingFreshCryptoInstance"),
    "KMS-03": ("kms-postgres-surefire", "KmsDeploymentGuardTest", "urR34DeploymentConfigurationAcceptsOnlySafeImmutableKeyModes"),
}
POLICIES = {
    "TENANT_RBAC", "IDENTITY_LIFECYCLE", "SYSTEM_MIGRATION",
    "PUBLIC_CHECKOUT_POLICY", "BUYER_IDENTITY_OWNERSHIP",
    "INFRA_DEPENDENCY", "REFERENCE_DATA", "DB_CONSTRAINT",
    "INTERNAL_SYSTEM_CRYPTO", "INTERNAL_WORKER", "STARTUP_CONFIG",
    "SYSTEM_RETENTION",
}


def permission_names(java_source):
    """Extract enum constants from the declaration, excluding comments and later methods."""
    match = re.search(
        r"public enum DomainPermission\s*\{(.*?)\s*;",
        java_source, re.DOTALL
    )
    if not match:
        raise ValueError("DomainPermission enum declaration not found")
    return set(re.findall(r"\b([A-Z][A-Z0-9_]+)\s*\(", match.group(1)))


def validate_documents(catalogue, crosswalk, permissions, role_cases=None):
    if role_cases is None:
        role_cases = {'schema': 'RM-UR-R3.1-JUNIT-CASES/v1', 'cases': []}
    if role_cases.get('schema') not in {'RM-UR-R3.1-JUNIT-CASES/v1','RM-UR-R3-JUNIT-COMBINED/v1'}:
        raise ValueError('Invalid role-case catalogue schema')
    by_id = {}
    proof_keys = set()
    for role_case in role_cases.get('cases', []):
        cid = role_case.get('caseId')
        if not isinstance(cid, str) or not (cid.startswith('UR-R31-') or cid.startswith('UR-R32-') or cid.startswith('UR-R33-')) or cid in by_id:
            raise ValueError('Duplicate or invalid role case ID: ' + str(cid))
        if role_case.get('evidenceStatus') != 'DECLARED_NOT_EXECUTED_ON_CURRENT_SHA' or role_case.get('mandatory') is not True:
            raise ValueError('Role case falsely qualified or nonmandatory: ' + cid)
        key = (role_case.get('evidenceArtifact'),role_case.get('junitSuite'),role_case.get('junitMethod'))
        allowed_suites = {'UserRoleMatrixR31QualificationTest', 'SalesInventoryRbacQualificationTest',
                          'CrmR1aQualificationTest', 'FilesR10bQualificationTest',
                          'OrganizationLocationLifecycleQualificationTest', 'TenantRbacRevocationQualificationTest',
                          'CommercialCoreE2EQualificationTest', 'InventoryTransferStocktakeQualificationTest'}
        if key in proof_keys or key[0] != 'kms-postgres-surefire' or key[1] not in allowed_suites:
            raise ValueError('Duplicate, non-PG or unknown role test key: ' + cid)
        if not isinstance(key[2], str) or (cid.startswith('UR-R31-') and not key[2].startswith('urR31')) or (cid.startswith('UR-R32-') and not key[2].startswith('urR32')):

            raise ValueError('Invalid JUnit method: ' + cid)
        proof_keys.add(key)
        if not isinstance(role_case.get('requirementIds'), list) or not role_case['requirementIds']:
            raise ValueError('Role case requires R3 links: ' + cid)
        if not set(role_case.get('actorProfiles', [])) <= ACTORS:
            raise ValueError('Invalid actor references in role case: ' + cid)
        by_id[cid] = role_case
    if catalogue.get("schema") != "RM-R3-FUNCTIONAL-COVERAGE/v1":
        raise ValueError("Unexpected source catalogue schema")
    if crosswalk.get("schema") != "RM-QUAL-USER-R3-CROSSWALK/v1":
        raise ValueError("Unexpected crosswalk schema")
    if crosswalk.get("strategySchema") != catalogue["schema"]:
        raise ValueError("Crosswalk source schema mismatch")
    if crosswalk.get("qualificationMode") != "JAVA_SERVICE_JUNIT_ONLY":
        raise ValueError("Crosswalk must remain service/JUnit only")
    if crosswalk.get("actorProfileIds") != ["U" + str(i) for i in range(13)]:
        raise ValueError("Missing/duplicate actor profiles")
    if set(crosswalk.get("storyIds", [])) != STORIES or len(crosswalk["storyIds"]) != 15:
        raise ValueError("Missing/duplicate business stories")
    domains = catalogue.get("domains")
    if not isinstance(domains, list) or [x.get("id") for x in domains] != list(EXPECTED_DOMAINS):
        raise ValueError("Expected 16 canonical R3 domains")
    references = {}
    for domain in domains:
        for requirement in domain["requirements"]:
            rid = requirement["id"]
            if rid in references:
                raise ValueError("Duplicate source requirement: " + rid)
            references[rid] = (domain["id"], requirement)
    mapped = crosswalk.get("requirements")
    if not isinstance(mapped, list):
        raise ValueError("Crosswalk requirements must be a list")
    seen = set()
    counters = {"total": 0, "implemented": 0, "planned": 0,
                "declaredCanonicalProof": 0, "roleCasesPassed": 0}
    proof_gaps = set()
    for item in mapped:
        rid = item.get("requirementId")
        if rid not in references or rid in seen:
            raise ValueError("Unknown or duplicate crosswalk requirement: " + str(rid))
        seen.add(rid)
        domain, original = references[rid]
        if (item.get("domainId") != domain
                or item.get("title") != original["title"]
                or item.get("delivery") != original["delivery"]
                or item.get("implementationSource") != original["source"]
                or item.get("canonicalProof") != original["proof"]):
            raise ValueError("Source/delivery/proof mismatch for " + rid)
        if not isinstance(item.get("operationId"), str) or not item["operationId"].startswith("OP:"):
            raise ValueError("Missing canonical operation ID for " + rid)
        stories = item.get("storyIds")
        if not isinstance(stories, list) or not stories or len(stories) != len(set(stories)) or not set(stories) <= STORIES:
            raise ValueError("Invalid business story mapping for " + rid)
        actors = item.get("actorProfiles")
        if not isinstance(actors, list) or len(actors) != len(set(actors)) or not set(actors) <= ACTORS:
            raise ValueError("Invalid actor mapping for " + rid)
        grants = item.get("requiredPermissionsAllOf")
        if not isinstance(grants, list) or len(grants) != len(set(grants)) or not set(grants) <= permissions:
            raise ValueError("Unknown/duplicate DomainPermission for " + rid)
        policy = item.get("permissionPolicy")
        if policy not in POLICIES and policy != "NOT_DEFINED":
            raise ValueError("Unknown permission policy for " + rid)
        if item.get("qualified") is not False or item.get("userRoleCaseStatus") not in {"NOT_RUN", "NOT_IMPLEMENTED", "DECLARED_NOT_EXECUTED_ON_THIS_SHA"}:
            raise ValueError("Unexecuted role cases claim qualification: " + rid)
        linked = item.get("userRoleCaseIds")
        expected_links = [case_id for case_id, case in by_id.items()
                          if rid in case["requirementIds"]]
        if linked != expected_links:
            raise ValueError("Role case crosswalk mismatch: " + rid)
        if linked and item.get("userRoleCaseStatus") != "DECLARED_NOT_EXECUTED_ON_THIS_SHA":
            raise ValueError("Linked role cases lack declared execution status: " + rid)
        if not linked and item.get("userRoleCaseStatus") not in {"NOT_RUN", "NOT_IMPLEMENTED"}:
            raise ValueError("Unlinked role cases falsely reported: " + rid)
        counters["total"] += 1
        if original["delivery"] == "PLANNED":
            counters["planned"] += 1
            if (grants or actors or item.get("canonicalProof") is not None
                    or item.get("permissionMappingStatus") != "NOT_APPLICABLE_PLANNED"
                    or item.get("userRoleCaseStatus") != "NOT_IMPLEMENTED"
                    or item.get("serviceContractStatus") != "NOT_IMPLEMENTED"):
                raise ValueError("Planned capability claims active behavior: " + rid)
        elif original["delivery"] == "IMPLEMENTED":
            counters["implemented"] += 1
            if item.get("permissionMappingStatus") not in {"REVIEW_REQUIRED", "NON_TENANT_PERMISSION_REVIEW"}:
                raise ValueError("Permission mapping prematurely approved: " + rid)
            if item.get("serviceContractStatus") != "REQUIRES_METHOD_REVIEW":
                raise ValueError("Service method declared qualified without audit: " + rid)
            if not isinstance(item.get("negativeDimensions"), list) or not item["negativeDimensions"]:
                raise ValueError("No role/tenant negatives required for " + rid)
            if original["proof"]:
                counters["declaredCanonicalProof"] += 1
                if item.get("proofStatus") != "DECLARED_NOT_EXECUTED_ON_THIS_SHA":
                    raise ValueError("Declared proof misrepresented as execution: " + rid)
            else:
                proof_gaps.add(rid)
                if item.get("proofStatus") != "MISSING_PROOF":
                    raise ValueError("Missing proof misrepresented: " + rid)
        else:
            raise ValueError("Unknown delivery status: " + rid)
    missing = set(references) - seen
    if missing:
        raise ValueError("Missing R3 requirements: " + ",".join(sorted(missing)))
    for rid, expected in R34_REVIEWED_PROOFS.items():
        proof = references[rid][1]["proof"]
        if not isinstance(proof, dict) or tuple(proof.get(key) for key in
                ("artifact","suite","test")) != expected:
            raise ValueError("UR-R3.4 reviewed proof missing or changed: " + rid)
    if counters != {"total": 75, "implemented": 46, "planned": 29,
                    "declaredCanonicalProof": 46, "roleCasesPassed": 0}:
        raise ValueError("Unexpected R3-v1 baseline counts: " + str(counters))
    for cid, case in by_id.items():
        if not set(case["requirementIds"]) <= seen:
            raise ValueError("Role case references unknown requirement: " + cid)
    if proof_gaps:
        raise ValueError("Unresolved R3-v1 proof gaps: " + str(proof_gaps))
    return {"schema": "RM-QUAL-USER-R3-CROSSWALK-VALIDATION/v1",
            "scope": "STRUCTURAL_MAPPING_ONLY",
            "gate": "MAPPING_VALID_NOT_SERVICE_QUALIFIED",
            "productionAdmission": "HOLD",
            "domains": len(EXPECTED_DOMAINS), **counters,
            "missingCanonicalProof": sorted(proof_gaps),
            "roleTestEvidence": "DECLARED_NOT_EXECUTED",
            "registeredRoleCases": len(by_id),
            "notes": "46 declared canonical proofs; five new UR-R3.4 proofs require exact-SHA execution, not inferred role-case or production admission."}


def validate(root):
    raw_catalogue = (root / CATALOG).read_bytes()
    raw_crosswalk = (root / CROSSWALK).read_bytes()
    catalogue = json.loads(raw_catalogue)
    crosswalk = json.loads(raw_crosswalk)
    permissions = permission_names((root / PERMISSION_ENUM).read_text(encoding="utf-8"))
    role_cases = json.loads((root / ROLE_CASES).read_text(encoding="utf-8"))
    extra = json.loads((root / ROLE_CASES_R32).read_text(encoding="utf-8"))
    if extra.get("schema") != "RM-UR-R3.2-JUNIT-CASES/v1":
        raise ValueError("Invalid R3.2 registry schema")
    additional = json.loads((root / ROLE_CASES_R33).read_text(encoding="utf-8"))
    if additional.get("schema") != "RM-UR-R3.3-JUNIT-CASES/v1":
        raise ValueError("Invalid R3.3 registry schema")
    combined = {"schema":"RM-UR-R3-JUNIT-COMBINED/v1",
                "cases":role_cases["cases"] + extra["cases"] + additional["cases"]}
    result = validate_documents(catalogue, crosswalk, permissions, combined)
    result["roleCaseCatalogueSha256"] = hashlib.sha256((root / ROLE_CASES).read_bytes()).hexdigest()
    result["roleCaseR32Sha256"] = hashlib.sha256((root / ROLE_CASES_R32).read_bytes()).hexdigest()
    result["roleCaseR33Sha256"] = hashlib.sha256((root / ROLE_CASES_R33).read_bytes()).hexdigest()
    result["catalogueSha256"] = hashlib.sha256(raw_catalogue).hexdigest()
    result["crosswalkSha256"] = hashlib.sha256(raw_crosswalk).hexdigest()
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = validate(args.root)
    payload = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    print(payload, end="")
