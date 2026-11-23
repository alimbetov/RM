#!/usr/bin/env python3
"""RM-QA-01B: fail-closed, exact-SHA CI evidence verifier (stdlib only)."""
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request
import urllib.parse
import zipfile
from io import BytesIO
import xml.etree.ElementTree as ET

from rm_r3_functional_coverage import assess
from rm_user_role_qualification import assess_role_cases

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path("rm-exact-sha-manifest.json")
REQUIRED = {
    "RM Domain Security and PostgreSQL Qualification": ("kms-h2-surefire", "kms-postgres-surefire", "iam-pii-populated-pg-surefire"),
    "CRM R1.2 Populated PostgreSQL Upgrade Qualification": ("crm-r12-populated-upgrade-surefire",),
    "INVENTORY 0081 Populated PostgreSQL Upgrade Qualification": ("inventory-0081-populated-postgresql",),
    "SALES 08.4 Populated PostgreSQL Upgrade Qualification": ("sales-084-populated-postgresql",),
    "RustFS Real Infrastructure E2E": ("rustfs-real-infra-e2e-surefire",),
    "RM TASK R2 Service Qualification": ("rm-task-h2-{sha}", "rm-task-postgresql-{sha}"),
    "RM TASK R3.2A Populated PostgreSQL Upgrade Qualification": ("rm-task-r32a-populated-pg-{sha}",),
}
# Required suite names are checked against actual test XML, never workflow labels alone.
SUITES = {
    "RM Domain Security and PostgreSQL Qualification":
        ("CommercialCoreE2EQualificationTest", "AutoOrderR1QualificationTest",
         "TenantRbacQualificationTest", "TenantRbacRevocationQualificationTest",
         "CrmR1aQualificationTest", "CrmInteractionQualificationTest",
         "CatalogQualificationTest", "SellerOfferQualificationTest",
         "StorefrontManagementQualificationTest", "InventoryMovementQualificationTest",
         "InventoryTransferStocktakeQualificationTest", "FilesR10bQualificationTest",
         "OrganizationLocationLifecycleQualificationTest", "KatoTerritoryQualificationTest",
         "KmsFencedJobQualificationTest", "AuditRetentionQualificationTest",
         "SalesOrderQueryQualificationTest", "UserRoleMatrixR31QualificationTest"),
    "INVENTORY 0081 Populated PostgreSQL Upgrade Qualification":
        ("InventoryMovementQualificationTest",),
    "SALES 08.4 Populated PostgreSQL Upgrade Qualification":
        ("SellerOfferQualificationTest",),
    "RustFS Real Infrastructure E2E":
        ("RustFsLiveInfrastructureE2ETest", "RustFsRealClamAvE2ETest"),
    "CRM R1.2 Populated PostgreSQL Upgrade Qualification":
        ("CrmR1aQualificationTest",),
    "RM TASK R2 Service Qualification": ("TaskServiceQualificationTest",),
    "RM TASK R3.2A Populated PostgreSQL Upgrade Qualification": ("TaskServiceQualificationTest",),
}

# These PostgreSQL interleavings must execute, not merely appear as skipped classes.
REQUIRED_CASES = {
    "kms-postgres-surefire": {
        ("InventoryReservationQualificationTest", "reservationReleaseAndIdempotentReplay"),
        ("OrganizationLocationQueryQualificationTest", "paginationAndFiltersUseTenantBoundary"),
        ("KmsPersistedRoundTripTest", "persistedEnvelopeRestoresUsingFreshCryptoInstance"),
        ("KmsDeploymentGuardTest", "urR34DeploymentConfigurationAcceptsOnlySafeImmutableKeyModes"),
        ("SalesInventoryRbacQualificationTest", "urR32SalesConfirmDenyPreservesOrderReservationAndStatusHistory"),
        ("SalesInventoryRbacQualificationTest", "urR32InventoryGrantRevokeAndForeignActorPreserveLedger"),
        ("CrmR1aQualificationTest", "urR32CrmWriterRevocationAndForeignTenantLeaveCustomerLedgerUnchanged"),
        ("CrmInteractionQualificationTest", "crmSvc01CustomerContactInteractionAndContractJourneyPersistsAcrossReads"),
        ("CrmInteractionQualificationTest", "crmSvc03ReadonlyEmployeeCannotCompleteOrReadAfterPermissionRevoke"),
        ("CrmInteractionQualificationTest", "crmSvc04ConsentLifecycleRevocationOverridesGrantWithoutDuplicateEvents"),
        ("CrmInteractionQualificationTest", "crmSvc05CustomerArchiveContractGuardAndRestorePreserveTimeline"),
        ("CrmInteractionQualificationTest", "crm99ArchivedContactAndParentNeverRemainCommunicationEligible"),
        ("CrmInteractionQualificationTest", "crm99ForeignAndSuspendedAssigneeProduceZeroInteractionSideEffects"),
        ("FilesR10bQualificationTest", "urR32FilesTenantAndRevocationNeverPromoteUnapprovedBytes"),
        ("OrganizationLocationLifecycleQualificationTest", "urR32OrganizationLocationGrantRevocationAndForeignTenantHaveZeroEffects"),
        ("TenantRbacRevocationQualificationTest", "activeCommandCompletesBeforeConcurrentRevocationCommits"),
        ("TenantRbacRevocationQualificationTest", "commandCannotProceedUntilEarlierRevocationCommits"),
        ("CommercialCoreE2EQualificationTest", "postgresSec09SuspendCommitsBeforeCheckoutMustFailWithoutOrderEffects"),
        ("CommercialCoreE2EQualificationTest", "postgresSec09CheckoutCommitsBeforeSuspendKeepsOneAcceptedSnapshot"),
        ("InventoryTransferStocktakeQualificationTest", "urR33PostgresTransferCommitsBeforeReconciliationSnapshot"),
        ("InventoryTransferStocktakeQualificationTest", "urR33PostgresReconciliationSnapshotCommitsBeforeTransferVisibility"),
        ("InventoryTransferStocktakeQualificationTest", "durableReconciliationRollbackDoesNotAdvanceCursorOrPersistFindings"),
        ("InventoryTransferStocktakeQualificationTest", "transferWritesExactlyTwoLegsAndReplaysWithoutDuplicateBalances"),
        ("R4FirstTenantOnboardingQualificationTest", "verifiedEmailFirstTenantIsAtomicAndExactRetryIsIdempotent"),
        ("R4FirstTenantOnboardingQualificationTest", "unverifiedEmailSuspensionAndForeignTenantRejectWithoutWrites"),
        ("R4FirstTenantOnboardingQualificationTest", "concurrentFirstTenantRequestsProduceOneOwnerAndNoOrphanRows"),
        ("R4LocalJwksIntegrationQualificationTest", "localJwksFetchVerifiesSignatureIssuerAudienceExpirySubjectAndRejectsForeignKey"),
        ("UserRoleMatrixR31QualificationTest", "urR31AllFiveMembershipRolesHaveFallbackReadButNoImplicitWrite"),
        ("UserRoleMatrixR31QualificationTest", "urR31ExplicitGrantsAuthorizeEachRoleAndRevocationFencesNextCommand"),
        ("UserRoleMatrixR31QualificationTest", "urR31TenantAAndBEnforceIndependentGrantsAndNoForeignWrites"),
        ("UserRoleMatrixR31QualificationTest", "urR31DualTenantMemberHasDifferentPermissionsAndRevocationIsLocal"),
        ("UserRoleMatrixR31QualificationTest", "urR31SuspendedActorAndMembershipCannotWriteDespiteExistingGrant"),
        ("UserRoleMatrixR31QualificationTest", "urR31UnassignedStaffAndMissingActorAreDeniedWithoutSideEffects"),
        ("CommercialCoreE2EQualificationTest",
         "postgresSec09SuspendCommitsBeforeCheckoutMustFailWithoutOrderEffects"),
        ("CommercialCoreE2EQualificationTest",
         "postgresSec09CheckoutCommitsBeforeSuspendKeepsOneAcceptedSnapshot"),
        ("InventoryTransferStocktakeQualificationTest",
         "postgresInv16ConcurrentAdvanceCannotDoubleCommitCursorOrFindings"),
        ("InventoryTransferStocktakeQualificationTest",
         "postgresInv16DurableAdvanceAndConcurrentReceivingHaveNoFalseDrift"),
        ("InventoryTransferStocktakeQualificationTest",
         "durableReconciliationRollbackDoesNotAdvanceCursorOrPersistFindings"),
        ("InventoryTransferStocktakeQualificationTest",
         "durableReconciliationStreamsEntireLedgerBeyondFiveHundredMovements"),
    },
    "rustfs-real-infra-e2e-surefire": {
        ("RustFsRealClamAvE2ETest",
         "realClamdRespondsCleanInfectedAndFailClosedForUnavailableEndpoint"),
    },
}

class SafeArtifactRedirect(urllib.request.HTTPRedirectHandler):
    """Do not forward the GitHub token to an artifact storage redirect host."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and (
                urllib.parse.urlsplit(req.full_url).netloc
                != urllib.parse.urlsplit(newurl).netloc):
            redirected.remove_header("Authorization")
        return redirected

def api(url, token):
    req = urllib.request.Request(
        url, headers={"Authorization": "Bearer " + token,
                      "Accept": "application/vnd.github+json",
                      "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.build_opener(SafeArtifactRedirect()).open(req, timeout=60) as response:
        return response.read()

def verify():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    sha = os.environ["GITHUB_SHA"]
    repo = os.environ["GITHUB_REPOSITORY"]
    token = os.environ["GH_TOKEN"]
    problems = []
    if manifest.get("sha") != sha or manifest.get("repository") != repo:
        problems.append("SHA/repository mismatch")
    gates = manifest.get("required", [])
    if len(gates) != len(REQUIRED) or {g.get("name") for g in gates} != set(REQUIRED):
        problems.append("Missing/duplicate mandatory workflows")
    digest = hashlib.sha256()
    migrations = sorted((ROOT / "src/main/resources/db/changelog").rglob("*.sql"))
    if not migrations:
        problems.append("No Liquibase SQL migration files")
    for f in migrations:
        digest.update(f.relative_to(ROOT).as_posix().encode() + b"\0")
        digest.update(hashlib.sha256(f.read_bytes()).digest())
    manifest["migrationFingerprint"] = {
        "algorithm": "sha256:path-and-content",
        "sha256": digest.hexdigest(), "fileCount": len(migrations)}
    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    passed_cases = {}  # strictly validated PostgreSQL artifact cases
    for gate in gates:
        name = gate.get("name")
        if gate.get("sha") != sha or gate.get("status") != "completed" or gate.get("conclusion") != "success":
            problems.append(f"{name}: not successful on same SHA")
            continue
        jobs = gate.get("jobs", [])
        if not jobs or any(j.get("conclusion") != "success" for j in jobs):
            problems.append(f"{name}: missing/failed jobs")
        expected_artifact_templates = REQUIRED.get(name)
        expected_artifacts = tuple(a.replace("{sha}", sha) for a in expected_artifact_templates) if expected_artifact_templates else ()
        if not expected_artifacts:
            problems.append(f"{name}: missing expected artifact configuration")
            continue
        artifacts = gate.get("artifacts", [])
        if len(artifacts) != len(expected_artifacts) or {
                item.get("name") for item in artifacts} != set(expected_artifacts):
            problems.append(f"{name}: missing/duplicate artifact declarations")
        gate["verifiedArtifacts"] = []
        discovered_suites = {}
        for artifact_name in expected_artifacts:
            artifact = next((item for item in artifacts
                             if item.get("name") == artifact_name), None)
            if not artifact or not isinstance(artifact.get("id"), int):
                problems.append(f"{name}: {artifact_name} unavailable")
                continue
            try:
                data = api(f"https://api.github.com/repos/{repo}/actions/artifacts/{artifact['id']}/zip", token)
                archive = zipfile.ZipFile(BytesIO(data))
                xmls = [n for n in archive.namelist() if n.endswith(".xml") and
                        Path(n).name.startswith("TEST-")]
                if not xmls:
                    raise ValueError("No Surefire TEST-*.xml files")
                counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
                skipped_cases = []
                executed_cases = set()
                for member in xmls:
                    suite_root = ET.fromstring(archive.read(member))
                    if suite_root.tag != "testsuite":
                        raise ValueError(f"Unexpected XML root in {member}")
                    suite = suite_root.get("name", "").split(".")[-1]
                    if not suite:
                        raise ValueError(f"Unnamed test suite in {member}")
                    values = {}
                    for key in counts:
                        value = int(suite_root.attrib.get(key, "-1"))
                        if value < 0:
                            raise ValueError(f"Missing or invalid {key} in {member}")
                        values[key] = value
                        counts[key] += value
                    for case in suite_root.findall("testcase"):
                        if case.find("skipped") is None:
                            executed_cases.add((suite, case.get("name")))
                        skip = case.find("skipped")
                        if skip is not None:
                            skipped_cases.append({
                                "suite": suite, "test": case.get("name"),
                                "reason": skip.get("message") or skip.text or "UNSPECIFIED"
                            })
                    group = discovered_suites.setdefault(suite, {"tests": 0, "skipped": 0})
                    group["tests"] += values["tests"]
                    group["skipped"] += values["skipped"]
                required_cases = REQUIRED_CASES.get(artifact_name, set())
                if artifact_name.startswith("rm-task-postgresql-"):
                    required_cases = {
                        ("TaskServiceQualificationTest", "t04PostgresqlConcurrentStartAllowsExactlyOneTransition"),
                        ("TaskServiceQualificationTest", "t05PostgresqlImmutableTaskHistoryRejectsTampering"),
                        ("TaskServiceQualificationTest", "t08PostgresqlSuspensionWaitsUntilAdmittedCreationCommits"),
                        ("TaskServiceQualificationTest", "t09LegacyNullAssigneeCanBeAssignedWithoutLosingAudit"),
                        ("TaskServiceQualificationTest", "t11LostResponseRetryReturnsSameTaskWithoutDuplicateTransition"),
                        ("TaskServiceQualificationTest", "t12EarlierTaskPermissionRevocationDeniesExecutionWithoutWrites"),
                        ("TaskServiceQualificationTest", "t13EarlierMembershipRevocationDeniesAssigneeCreation"),
                        ("TaskServiceQualificationTest", "t14PostgresqlRoleRevocationWaitsForAdmittedExecutionCommit"),
                        ("TaskServiceQualificationTest", "t15PostgresqlMembershipRevocationWaitsForTaskAssignmentCommit"),
                        ("TaskServiceQualificationTest", "t16LostResponseAssignmentRetryUsesOneImmutableEvent"),
                        ("TaskServiceQualificationTest", "t17ArchiveRetryAndCrossTaskKeyCollisionFailClosed"),
                        ("TaskServiceQualificationTest", "t18CrmFollowupCreationRetryAndScopedLinkRead"),
                        ("TaskServiceQualificationTest", "t19CrmFollowupRejectsForeignAndClosedInteractionWithNoTasks"),
                        ("TaskServiceQualificationTest", "t20CrmFollowupCannotReuseTaskRequestForAnotherInteraction"),
                        ("TaskServiceQualificationTest", "t21PostgresqlCrmLinkIsImmutableAndCompositeTenantFkIsEnforced"),
                        ("TaskServiceQualificationTest", "t22CrmFollowupRetryAfterSourceClosureReturnsOriginalTask"),
                        ("TaskServiceQualificationTest", "t23ManagerReschedulePreservesDueEvidenceAndKeyedReplay"),
                        ("TaskServiceQualificationTest", "t24TerminalTaskCannotRescheduleAndReplayedOldDueReceiptIsSuperseded"),
                        ("TaskServiceQualificationTest", "t25ClosedCrmSourcePreservesLinkedTaskAndHistoryButRejectsNewLink"),
                        ("TaskServiceQualificationTest", "t26RollbackBeforeFollowupCommitAllowsExactRequestRetry"),
                        ("TaskServiceQualificationTest", "t27PostgresqlConcurrentRescheduleAndReassignmentHaveSingleWinner"),
                        ("TaskServiceQualificationTest", "t28PostgresqlTwoManagersConcurrentReassignmentExactlyOneWins"),
                        ("TaskServiceQualificationTest", "t29ManagerCancellationIsIdempotentAndNotArchival"),
                        ("TaskServiceQualificationTest", "t30InProgressCancellationForbidsCompletionAndPreservesEvidence"),
                        ("TaskServiceQualificationTest", "t31RequeueRemovesAssignmentAndManagerMayClaimForNewStaff"),
                        ("TaskServiceQualificationTest", "t32RequeueRequiresStoppedWorkAndCannotBeStartedUnassigned"),
                        ("TaskServiceQualificationTest", "t33CancelledCrmInteractionRequiresExplicitScopedReconciliation"),
                        ("TaskServiceQualificationTest", "t34ReconciliationAndQueueRollbackLeaveNoPartialEvents"),
                        ("TaskServiceQualificationTest", "t35PostgresqlCancelRacesWithEmployeeCompletionExactlyOneWins"),
                        ("TaskServiceQualificationTest", "t36PostgresqlRequeueRacesWithReassignmentExactlyOneWins"),
                        ("TaskServiceQualificationTest", "t37ReconciliationRejectsUnrelatedSourceAndForeignTenantWithoutTaskMutation"),
                        ("TaskServiceQualificationTest", "t38PostgresqlSourceCancellationSerializesAgainstFollowupCreation"),
                        ("TaskServiceQualificationTest", "t39ManagerQueuePaginatesAndHidesUnassignedTasksFromEmployee"),
                        ("TaskServiceQualificationTest", "t40ManagerQueueCannotExposeAnotherTenantOrUnauthorizedRole"),
                        ("TaskServiceQualificationTest", "t41CancelledCrmReconciliationPageAdvancesWithoutRepeatingTasks"),
                        ("TaskServiceQualificationTest", "t42PostgresqlQueuePageDoesNotResurrectConcurrentlyClaimedTask"),
                        ("TaskServiceQualificationTest", "t43FrozenReconciliationTraversalSurvivesConcurrentTaskCancellation"),
                        ("TaskServiceQualificationTest", "t44SnapshotTenantAndActorPrivacyRequestReplayAndInvalidSource"),
                        ("TaskServiceQualificationTest", "t45SnapshotRollbackBeforeCommitKeepsNoManifestAndRetryWorks"),
                        ("TaskServiceQualificationTest", "t46PostgresqlSnapshotManifestAndEntriesAreImmutable"),
                        ("TaskServiceQualificationTest", "t47PostgresqlConcurrentSnapshotSameRequestCommitsSingleSealedManifest"),
                        ("TaskServiceQualificationTest", "t48PostgresqlSnapshotOverCapacityRejectsWithoutPartialManifest"),
                        ("TaskServiceQualificationTest", "t49CommittedSnapshotReplaysAfterStatusChangeAndLostResponse"),
                        ("TaskServiceQualificationTest", "t50SnapshotIntegrityVerifiesPersistedRowsAndDeniesForeignActor"),
                        ("TaskServiceQualificationTest", "t51PostgresqlExactlyTenThousandTasksCaptureAndPageWithoutLoss"),
                        ("TaskServiceQualificationTest", "t52PostgresqlCrmStatusRaceSerializesWithSnapshotCapture"),
                    }
                    if counts["tests"] != 52 or counts["skipped"] != 0:
                        problems.append(f"{artifact_name}: expected 52 executed cases without skips")
                if artifact_name.startswith("rm-task-h2-") and (counts["tests"] != 52 or counts["skipped"] != 17):
                    problems.append(f"{artifact_name}: expected 52 discovered cases with 17 PG-only skips")
                missing_cases = required_cases - executed_cases
                if missing_cases:
                    problems.append(f"{artifact_name}: required PostgreSQL tests missing/skipped: {sorted(missing_cases)}")
                if counts["tests"] <= 0 or counts["failures"] or counts["errors"]:
                    problems.append(f"{name}: {artifact_name} empty/failing tests")
                if counts["skipped"] > counts["tests"]:
                    problems.append(f"{name}: {artifact_name} invalid skip count")
                if not missing_cases and counts["tests"] > 0 and not (
                        counts["failures"] or counts["errors"]) and counts["skipped"] <= counts["tests"]:
                    passed_cases[artifact_name] = executed_cases
                gate["verifiedArtifacts"].append({
                    "name": artifact_name, "id": artifact["id"], "sha256": hashlib.sha256(data).hexdigest(),
                    "surefire": counts, "skippedTests": skipped_cases,
                })
                for key in total:
                    total[key] += counts[key]
            except (OSError, ValueError, KeyError, ET.ParseError, urllib.error.URLError, zipfile.BadZipFile) as exc:
                problems.append(f"{name}: {artifact_name} cannot verify evidence: {exc}")
        for suite in SUITES.get(name, ()):
            group = discovered_suites.get(suite)
            if group is None or group["tests"] <= group["skipped"]:
                problems.append(f"{name}: mandatory suite {suite} missing or entirely skipped")
        gate["suites"] = sorted(discovered_suites)
    manifest["testCounts"] = total
    try:
        result = assess(ROOT, passed_cases, sha, admission_pass=not problems)
        manifest["functionalCoverage"] = {
            "json": "rm-r3-functional-coverage.json",
            "markdown": "rm-r3-functional-coverage.md",
            "catalogueSha256": result["catalogueSha256"],
            "summary": result["summary"],
        }
    except (ValueError, KeyError, OSError, TypeError) as exc:
        problems.append(f"RM-R3 catalogue/evidence qualification failed: {type(exc).__name__}: {exc}")
        manifest["functionalCoverage"] = {"status": "FAILED"}
    try:
        role_result = assess_role_cases(ROOT, passed_cases, sha,
                                        admission_pass=not problems)
        manifest["userRoleQualification"] = {
            "json": "rm-user-role-scenario-results.json",
            "gate": role_result["gate"],
            "mandatory": role_result["mandatory"],
            "executedPostgresql": role_result["executedPostgresql"],
            "verifiedInternal": role_result["verifiedInternal"],
            "catalogueSha256": role_result["catalogueSha256"]
        }
        if role_result["gate"] != "USER_ROLE_SERVICE_QUALIFIED":
            problems.append("UR-R3.1 mandatory service role matrix incomplete or not admitted")
    except (ValueError, KeyError, OSError, TypeError) as exc:
        problems.append(f"UR-R3.1 role evidence failed: {type(exc).__name__}: {exc}")
        manifest["userRoleQualification"] = {"gate": "HOLD"}
    manifest["evidenceErrors"] = problems
    manifest["qa01bGate"] = "PASS" if not problems else "FAIL"
    # A passing QA evidence gate is still NOT public security/production admission.
    manifest["admission"] = "HOLD"
    MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"qa01bGate": manifest["qa01bGate"],
                      "tests": total, "errors": problems}, indent=2))
    return 0 if not problems else 1

if __name__ == "__main__":
    try:
        sys.exit(verify())
    except Exception as exc:
        print(f"RM-QA-01B FAIL CLOSED: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
