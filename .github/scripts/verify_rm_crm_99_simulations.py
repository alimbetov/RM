#!/usr/bin/env python3
"""Audit 99 CRM design simulations; NEVER represent them as 99 run E2E tests."""
import json
import re
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
REG=Path("docs/strategy/RM-CRM-99-BUSINESS-SIMULATIONS-v1.json")
CAT=Path("docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json")
TEST_PATHS={
"R4FirstTenantOnboardingQualificationTest":"src/test/java/kz/rm/iam/R4FirstTenantOnboardingQualificationTest.java",
"CrmR1aQualificationTest":"src/test/java/kz/rm/crm/CrmR1aQualificationTest.java",
"CrmInteractionQualificationTest":"src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java",
"CommercialCoreE2EQualificationTest":"src/test/java/kz/rm/integration/CommercialCoreE2EQualificationTest.java",
"FilesR10bQualificationTest":"src/test/java/kz/rm/files/FilesR10bQualificationTest.java",
}
LEVELS={"COMPONENT_TESTED","PARTIAL","NEW_PG_TEST","EXTERNAL_HOLD","MISSING_FEATURE","POLICY_GAP","BLOCKED_PROVENANCE"}
PG_METHODS={"crm99ArchivedContactAndParentNeverRemainCommunicationEligible",
"crm99ForeignAndSuspendedAssigneeProduceZeroInteractionSideEffects"}

def validate(root=ROOT):
    catalog=json.loads((root/CAT).read_text(encoding="utf-8"))
    doc=json.loads((root/REG).read_text(encoding="utf-8"))
    req=[r for d in catalog["domains"] for r in d["requirements"]]
    if len(req)!=75 or len(catalog["domains"])!=16:
        raise ValueError("Changed R3 canonical denominator")
    if doc.get("schema")!="RM-CRM-99-BUSINESS-SIMULATIONS/v1" or "NOT 99 real PostgreSQL E2E" not in doc.get("methodology",""):
        raise ValueError("CRM-99 synthetic review wrongly presented as executed E2E")
    controls=doc.get("controls",{})
    if any(controls.get(x)!="STOP" for x in ("newControllers","rest","frontend")) or controls.get("production")!="HOLD" or controls.get("noInferredCustomerSalesLink") is not True:
        raise ValueError("Service-only production/identity policy violated")
    groups=doc.get("groups",[])
    cases=doc.get("cases",[])
    if len(groups)!=11 or len(cases)!=99 or [c.get("id") for c in cases]!=[f"CRM-SIM-{x:03}" for x in range(1,100)]:
        raise ValueError("Expected exactly 99 ordered and unique CRM simulations in 11 groups")
    group_ids=[g["id"] for g in groups]
    if len(set(group_ids))!=11 or len(set(c["businessEvent"] for c in cases))!=99:
        raise ValueError("Duplicated CRM simulation or group")
    domain_ids={d["id"] for d in catalog["domains"]}
    for g in groups:
        if g.get("count")!=9 or sum(c["group"]==g["id"] for c in cases)!=9:
            raise ValueError("Each group requires precisely nine simulations")
    counts={}
    for c in cases:
        status=c.get("assessment")
        if status not in LEVELS or c.get("group") not in group_ids or c.get("domain") not in domain_ids or c.get("priority") not in ("P0","P1","P2"):
            raise ValueError("Invalid scenario classification: "+c["id"])
        if not c.get("expectedBehavior") or not c.get("proposedAction") or c.get("controllerUiScope")!="STOP" or c.get("productionAdmission")!="HOLD" or c.get("reviewMethod")!="SOURCE_AND_EXISTING_JUNIT_TRACE_OR_DESIGN_GAP":
            raise ValueError("Unsupported simulated guarantee: "+c["id"])
        evidence=c.get("supportingEvidence",[])
        if status in ("BLOCKED_PROVENANCE","MISSING_FEATURE","POLICY_GAP") and evidence:
            raise ValueError("Unimplemented capability cannot claim evidence: "+c["id"])
        if status=="COMPONENT_TESTED" and c.get("simulatedOutcome")!="EXISTING_COMPONENT_PROOF_NOT_COMPLETE_END_TO_END":
            raise ValueError("Component test misrepresented as full E2E")
        if status=="NEW_PG_TEST" and c.get("simulatedOutcome")!="NEW_POSTGRESQL_JUNIT_REQUIRED":
            raise ValueError("New test must remain candidate pending exact SHA")
        for ref in evidence:
            if not re.fullmatch(r"[A-Za-z]\w*\.[A-Za-z]\w*",ref):
                raise ValueError("Invalid test ref: "+ref)
            suite,method=ref.split(".")
            path=TEST_PATHS.get(suite)
            if not path or not re.search(r"\bvoid\s+"+re.escape(method)+r"\s*\(",(root/path).read_text(encoding="utf-8")):
                raise ValueError("Missing referenced Java JUnit: "+ref)
        counts[status]=counts.get(status,0)+1
    if doc["summary"].get("total")!=99 or doc["summary"].get("assessmentCounts")!=counts:
        raise ValueError("Simulation summary counts do not match records")
    java=(root/"src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java").read_text()
    qa=(root/".github/scripts/verify_rm_qa_01b.py").read_text()
    for method in PG_METHODS:
        if not re.search(r"\bvoid\s+"+method+r"\s*\(",java):
            raise ValueError("Missing new PG test: "+method)
        if '("CrmInteractionQualificationTest", "'+method+'")' not in qa:
            raise ValueError("Missing mandatory exact-SHA PG admission: "+method)
    return {"schema":"RM-CRM-99-DESIGN-VALIDATION/v1","gate":"99_DESIGN_CASES_AUDITED_NOT_99_PG_RUNS","simulations":99,"groups":11,"assessmentCounts":counts,"mandatoryNewPgMethods":2,"production":"HOLD"}

if __name__=="__main__":
    print(json.dumps(validate(),indent=2))
