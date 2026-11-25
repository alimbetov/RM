#!/usr/bin/env python3
"""Service-only CRM U01..U12 contract validator. Never upgrade evidence implicitly."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = Path("docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json")
JOURNEYS = Path("docs/strategy/RM-CRM-SERVICE-JOURNEY-REGISTRY-v1.json")
ALLOWED = {"PARTIAL", "GAP", "BLOCKED_PROVENANCE"}
MAPPED = {
    "CrmInteractionQualificationTest": "src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java",
    "R4FirstTenantOnboardingQualificationTest": "src/test/java/kz/rm/iam/R4FirstTenantOnboardingQualificationTest.java",
    "IamUserOnboardingProcessQualificationTest": "src/test/java/kz/rm/iam/IamUserOnboardingProcessQualificationTest.java",
    "CommercialCoreE2EQualificationTest": "src/test/java/kz/rm/integration/CommercialCoreE2EQualificationTest.java",
    "FilesR10bQualificationTest": "src/test/java/kz/rm/files/FilesR10bQualificationTest.java",
}
NEW_MANDATORY = {
    "crmSvc01CustomerContactInteractionAndContractJourneyPersistsAcrossReads",
    "crmSvc03ReadonlyEmployeeCannotCompleteOrReadAfterPermissionRevoke",
    "crmSvc04ConsentLifecycleRevocationOverridesGrantWithoutDuplicateEvents",
    "crmSvc05CustomerArchiveContractGuardAndRestorePreserveTimeline",
}


def validate(root=ROOT):
    catalogue=json.loads((root/CATALOG).read_text(encoding="utf-8"))
    reg=json.loads((root/JOURNEYS).read_text(encoding="utf-8"))
    ids={r["id"] for d in catalogue["domains"] for r in d["requirements"]}
    planned={r["id"] for d in catalogue["domains"] for r in d["requirements"]
             if r["delivery"]=="PLANNED"}
    if len(ids)!=75 or len(planned)!=29 or len(catalogue["domains"])!=16:
        raise ValueError("Changed R3 requirements baseline")
    if reg.get("schema")!="RM-CRM-SERVICE-JOURNEY-REGISTRY/v1":
        raise ValueError("Unrecognized CRM service registry")
    if reg.get("baseline")!={"domains":16,"requirements":75,"implemented":46,"planned":29}:
        raise ValueError("CRM baseline no longer matches R3")
    policy=reg.get("policy",{})
    if not (policy.get("controllers")=="STOP" and policy.get("restApi")=="STOP" and
            policy.get("ui")=="STOP" and policy.get("production")=="HOLD" and
            policy.get("noInferredCustomerOrderAssociation") is True):
        raise ValueError("CRM service-only HOLD policy violated")
    journeys=reg.get("journeys",[])
    if len(journeys)!=12 or [j.get("id") for j in journeys] != [
        f"CRM-U{n:02}" for n in range(1,13)]:
        raise ValueError("CRM-U01..U12 must be complete, distinct and ordered")
    all_methods=set()
    for j in journeys:
        if (j.get("assessment") not in ALLOWED or
            j.get("qualification")!="NOT_FULLY_QUALIFIED" or
            j.get("controllerUiScope")!="STOP" or
            j.get("evidenceSemantic")!="SUPPORTING_PARTIAL" or
            not j.get("remainingAcceptance")):
            raise ValueError("Overstated or incomplete CRM user journey: "+j["id"])
        reqs=j.get("requirementIds",[])
        if not reqs or any(req not in ids for req in reqs):
            raise ValueError("Invalid canonical R3 requirement: "+j["id"])
        tests=j.get("existingJUnitEvidence",[])
        if j["assessment"]=="GAP" and tests:
            raise ValueError("Unproven GAP cannot list executable journey coverage")
        if j["assessment"]=="BLOCKED_PROVENANCE" and j["id"]!="CRM-U08":
            raise ValueError("Only CRM-U08 has unresolved buyer/customer provenance")
        for entry in tests:
            if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*\.[A-Za-z][A-Za-z0-9_]*",entry):
                raise ValueError("Malformed JUnit case")
            suite,method=entry.split(".",1)
            path=MAPPED.get(suite)
            if path is None:
                raise ValueError("JUnit suite outside reviewed evidence map: "+suite)
            if not re.search(r"\bvoid\s+"+re.escape(method)+r"\s*\(",(root/path).read_text()):
                raise ValueError("Missing JUnit method: "+entry)
            all_methods.add(method)
    if not NEW_MANDATORY.issubset(all_methods):
        raise ValueError("Required new CRM service user journeys missing")
    if len({j["id"] for j in journeys}) !=12:
        raise ValueError("Duplicate CRM journeys")
    return {"schema":"RM-CRM-SERVICE-JOURNEY-VALIDATION/v1",
            "gate":"STRUCTURE_VALID_NOT_FULL_JOURNEY_QUALIFICATION",
            "sourceRequirements":75,"journeys":12,
            "partial":sum(j["assessment"]=="PARTIAL" for j in journeys),
            "gaps":sum(j["assessment"]=="GAP" for j in journeys),
            "blocked":sum(j["assessment"]=="BLOCKED_PROVENANCE" for j in journeys),
            "mandatoryMethods":len(NEW_MANDATORY),"production":"HOLD"}


if __name__=="__main__":
    print(json.dumps(validate(),indent=2))
