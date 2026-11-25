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
from rm_mvp_service_evidence import assess as assess_mvp_service
from verify_ret04_global_artifact import verify_target_archive, verify_full_archive, WORKFLOW as RET04_WORKFLOW

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
    "RM TASK R32B T00 PostgreSQL Contract Qualification": ("rm-task-t00-postgresql-{sha}",),
    "RM CUR-R2C Policy Schema PostgreSQL Qualification": ("cur-r2c-policy-pg-{sha}", "cur-r2c-populated-pg-{sha}", "cur-r2c-anchor-populated-pg-{sha}", "cur-r2c-revoke-populated-pg-{sha}"),
    RET04_WORKFLOW: ("qr07-r0-r1-{sha}", "qr07-full-pg-{sha}"),
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
         "SalesOrderQueryQualificationTest", "UserRoleMatrixR31QualificationTest",
         "MvpServiceWarehouseAdmissionQualificationTest",
         "IamSellerAuthenticatedTenantAdmissionQualificationTest",
         "MvpServiceCurrencyStartupQualificationTest",
         "BusMvpServiceJourneyQualificationTest",
         "QrMvpServiceJourneyQualificationTest"),
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
    "RM TASK R32B T00 PostgreSQL Contract Qualification": ("CrmInteractionQualificationTest",),
    "RM CUR-R2C Policy Schema PostgreSQL Qualification": ("FxAutoPolicySchemaPgTest", "FxAutoRollingLimitEvaluatorTest", "FxAutoDryRunTest", "FxAutoOwnerAdmissionQualificationTest", "TenantCurrencyPolicyQualificationTest", "SkuFxSellerApprovalQualificationTest"),
    RET04_WORKFLOW: ("QrWarehouseHandoverQualificationTest",),
}

# These PostgreSQL interleavings must execute, not merely appear as skipped classes.
REQUIRED_CASES = {
    "kms-postgres-surefire": {
        # MVP six-pack gates: require named cases from real PostgreSQL XML.
        # SELLER-EDIT-R0 preparation: identity, tenant, revoke and catalog CAS.
        # Warehouse-scope denial and both commit-order races are P0 blockers
        # for any future tenant employee stock upload; these are SERVICE cases.
        ("SellerWarehouseScopeR0QualificationTest",
         "sellerR0ReceiptAndCorrectionRequireAssignedWarehouseDespiteTenantGrants"),
        ("SellerWarehouseScopeR0QualificationTest",
         "sellerR0WarehouseScopeAndRoleRevocationBothBlockNewInventoryWrites"),
        ("SellerWarehouseScopeR0QualificationTest",
         "sellerR0AllInventoryMutationsRespectWarehouseAssignment"),
        ("SellerWarehouseScopeR0QualificationTest",
         "sellerR0ForeignTenantWarehouseAndSkuCannotBeUsedWithOwnGrants"),
        ("SellerWarehouseScopeR0QualificationTest",
         "sellerR0PostgresAdmittedReceiptCommitsBeforeWarehouseScopeRevocation"),
        ("SellerWarehouseScopeR0QualificationTest",
         "sellerR0PostgresScopeRevocationCommitsBeforeReceiptWithZeroLedger"),
        ("SellerWarehouseScopeR0QualificationTest",
         "sellerR0PostgresRoleRevocationCommitsBeforeReceiptWithZeroLedger"),
        ("StorefrontManagementQualificationTest",
         "sellerR0PgPriceUpdateCommitsBeforeRoleRevokeKeepsOneRevision"),
        ("StorefrontManagementQualificationTest",
         "sellerR0PgRoleRevokeCommitsBeforePriceUpdateWithoutHistory"),
        # Cryptographic access-token purpose -> verified RM user -> tenant RBAC
        # -> actual CATALOG write; these cases must run on real PostgreSQL.
        ("IamSellerAuthenticatedTenantAdmissionQualificationTest",
         "iamSellerR0RealBearerSubCannotBeOverriddenByActorOrTenantClaims"),
        ("IamSellerAuthenticatedTenantAdmissionQualificationTest",
         "iamSellerR0SignedIdRefreshWrongPurposeAndBadJwtNeverMintActor"),
        ("IamSellerAuthenticatedTenantAdmissionQualificationTest",
         "iamSellerR0RoleRevokedAfterSignedTokenAndTenantSelectionDeniesWrite"),
        ("IamSellerAuthenticatedTenantAdmissionQualificationTest",
         "iamSellerR0MembershipSuspendedAfterBearerBlocksSelectAndMutation"),
        ("IamSellerAuthenticatedTenantAdmissionQualificationTest",
         "iamSellerR0SuspendedAccountRejectsExistingAndFreshBearer"),
        ("IamSellerAuthenticatedTenantAdmissionQualificationTest",
         "iamSellerR0TenantSuspendedAfterAccessTokenDeniesCurrentSelectionAndWrite"),
        # Keep preparatory coverage mandatory without enabling XLSX imports.
        ("CatalogQualificationTest",
         "sellerEditR0SameSkuAcrossTenantsUsesEmployeeSpecificGrants"),
        ("CatalogQualificationTest",
         "sellerEditR0RevokedPriceGrantCannotAppendAnotherRevision"),
        ("CatalogQualificationTest",
         "sellerEditR0PostgresTwoEditorsOneCatalogPriceRevisionWins"),
        ("CatalogQualificationTest",
         "sellerEditR1SplitEmployeeGrantsPreserveCatalogVersionsAndImmutablePriceHistory"),
        ("StorefrontManagementQualificationTest",
         "sellerEditR1EmployeePriceAndPublisherRolesCannotRewriteCommittedOrders"),
        ("StorefrontManagementQualificationTest",
         "sellerEditR1CatalogPriceAndSellerOfferPriceHaveIndependentHistories"),
        ("PublishedQrCheckoutQualificationTest",
         "qrMvpVerifiedListCheckoutCreatesOneBuyerOwnedOrder"),
        ("PublishedQrCheckoutQualificationTest",
         "qrMvpAnonymousAndUnverifiedEmailCannotCreateNewOrder"),
        ("PublishedQrCheckoutQualificationTest",
         "qrMvpSuspendOrDeleteVersusCheckoutBothCommitOrders"),
        ("CommerceCurrencyAdmissionQualificationTest",
         "curc01MissingPolicyRejectsCheckoutWithoutSideEffects"),
        ("CommerceCurrencyAdmissionQualificationTest",
         "curc03UsdPublishedLegacyOfferRejectedWithoutOrder"),
        ("CommerceCurrencyAdmissionQualificationTest",
         "curc06PostgresPolicyChangeVsCheckoutLinearizes"),
        ("CommerceCurrencyAdmissionQualificationTest",
         "curc10PostgresThreeWayCheckoutPriceAndPolicyRaceIsAtomic"),
        ("MvpServiceCurrencyStartupQualificationTest",
         "mvpW2StrictServiceModeCannotStartWithPublicCurrencyAdmissionDisabled"),
        ("MvpServiceWarehouseAdmissionQualificationTest",
         "mvpW3StrictWarehouseAssignmentRequiredForConfirmAndFulfill"),
        ("MvpServiceWarehouseAdmissionQualificationTest",
         "mvpW3ReleaseCannotUseTenantGrantAfterWarehouseScopeRevocation"),
        ("BusMvpServiceJourneyQualificationTest",
         "busMvpJ1OrganizationEmployeeCustomerTaskSkuOrderStockHistoryPg"),
        ("QrMvpServiceJourneyQualificationTest",
         "qrMvpJ1VerifiedListPriceToHandoverAndRestockPg"),
        ("QrMvpServiceJourneyQualificationTest",
         "qrMvpJ1NegotiatedPriceToHandoverAndQuarantinePg"),
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
    full_passes = {}
    full_skips = []
    class_identities = {}
    ret04_artifacts = {}
    manifest["ret04DurableQualification"] = {"status": "HOLD", "sha": sha,
        "mappingVerdict": "SUPPORTING_PARTIAL", "productionAdmission": "HOLD"}
    seen_artifact_ids = set()
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
                if artifact["id"] in seen_artifact_ids:
                    raise ValueError("Duplicate artifact ID across mandatory gates")
                seen_artifact_ids.add(artifact["id"])
                data = api(f"https://api.github.com/repos/{repo}/actions/artifacts/{artifact['id']}/zip", token)
                archive = zipfile.ZipFile(BytesIO(data))
                xmls = [n for n in archive.namelist() if n.endswith(".xml") and
                        Path(n).name.startswith("TEST-")]
                if not xmls:
                    raise ValueError("No Surefire TEST-*.xml files")
                # Independently re-run SQL verifiers on raw QR evidence. The
                # source artifact PASS and test labels alone are insufficient.
                ret04_verified = None
                if name == RET04_WORKFLOW:
                    if artifact_name == "qr07-r0-r1-" + sha:
                        ret04_verified = verify_target_archive(archive, sha)
                    elif artifact_name == "qr07-full-pg-" + sha:
                        ret04_verified = verify_full_archive(archive, sha)
                    else:
                        raise ValueError("Unknown RET04 artifact")
                counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
                skipped_cases = []
                executed_cases = set()
                qualified_cases = set()
                qualified_skips = []
                artifact_classes = {}
                for member in xmls:
                    suite_root = ET.fromstring(archive.read(member))
                    if suite_root.tag != "testsuite":
                        raise ValueError(f"Unexpected XML root in {member}")
                    full_suite = suite_root.get("name", "")
                    suite = full_suite.split(".")[-1]
                    artifact_classes.setdefault(suite, set()).add(full_suite)
                    if not suite:
                        raise ValueError(f"Unnamed test suite in {member}")
                    values = {}
                    for key in counts:
                        value = int(suite_root.attrib.get(key, "-1"))
                        if value < 0:
                            raise ValueError(f"Missing or invalid {key} in {member}")
                        values[key] = value
                        counts[key] += value
                    cases = suite_root.findall("testcase")
                    actual = {"tests": len(cases), "failures": 0, "errors": 0, "skipped": 0}
                    seen_case_names = set()
                    for case in cases:
                        case_name = case.get("name")
                        if not case_name or case_name in seen_case_names:
                            raise ValueError(f"Unnamed or duplicate testcase in {member}: {case_name}")
                        seen_case_names.add(case_name)
                        outcomes = [kind for kind in ("failure", "error", "skipped")
                                    if case.find(kind) is not None]
                        if len(outcomes) > 1:
                            raise ValueError(f"Conflicting testcase outcomes in {member}: {case_name}")
                        if outcomes:
                            actual[{"failure": "failures", "error": "errors", "skipped": "skipped"}[outcomes[0]]] += 1
                        else:
                            executed_cases.add((suite, case_name))
                            qualified_cases.add((full_suite, case_name))
                        skip = case.find("skipped")
                        if skip is not None:
                            qualified_skips.append((full_suite, case_name, skip.get("message") or skip.text or "UNSPECIFIED"))
                            skipped_cases.append({
                                "suite": suite, "test": case.get("name"),
                                "reason": skip.get("message") or skip.text or "UNSPECIFIED"
                            })
                    if actual != values:
                        raise ValueError(f"Surefire testcase counters mismatch in {member}: declared {values}, actual {actual}")
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
                        ("TaskServiceQualificationTest", "t53PostgresqlManagerRevocationRacesWithSnapshotCapture"),
                    }
                    if counts["tests"] != 53 or counts["skipped"] != 0:
                        problems.append(f"{artifact_name}: expected 53 executed cases without skips")
                if artifact_name.startswith("rm-task-h2-") and (counts["tests"] != 53 or counts["skipped"] != 18):
                    problems.append(f"{artifact_name}: expected 53 discovered cases with 18 PG-only skips")
                if artifact_name.startswith("rm-task-r32a-populated-pg-"):
                    if counts["tests"] != 53 or counts["skipped"] != 0:
                        problems.append(f"{artifact_name}: populated migration must execute 53 PostgreSQL cases without skips")
                    required_cases = {
                        ("TaskServiceQualificationTest", "t46PostgresqlSnapshotManifestAndEntriesAreImmutable"),
                        ("TaskServiceQualificationTest", "t49CommittedSnapshotReplaysAfterStatusChangeAndLostResponse"),
                        ("TaskServiceQualificationTest", "t50SnapshotIntegrityVerifiesPersistedRowsAndDeniesForeignActor"),
                        ("TaskServiceQualificationTest", "t51PostgresqlExactlyTenThousandTasksCaptureAndPageWithoutLoss"),
                        ("TaskServiceQualificationTest", "t52PostgresqlCrmStatusRaceSerializesWithSnapshotCapture"),
                        ("TaskServiceQualificationTest", "t53PostgresqlManagerRevocationRacesWithSnapshotCapture"),
                    }
                if artifact_name.startswith("rm-task-t00-postgresql-"):
                    required_cases = {
                        ("CrmInteractionQualificationTest", name) for name in (
                            "t00CancellationEventHasCanonicalIdentityAndSingleRevision",
                            "t00OuterTransactionRollbackRemovesCancellationAndSourceEvent",
                            "t00CancellationIntentPortCannotRunOutsideCrmTransaction",
                            "t00PgConcurrentCancellationCreatesExactlyOneCanonicalEvent",
                            "t00PgActorSuspensionCommitsBeforeCancellationDeniesWithoutEvent",
                            "t00PgCancellationCommitsBeforeActorSuspensionPreservesOneEvent",
                            "t00PgCanonicalEventIdentityAndPrecisionAreStoredExactly",
                            "t00PgTransactionPortReceivesPersistedCanonicalEvent",
                            "t00PgPortFailureRollsBackCancellationAndEvent",
                        )
                    }
                    if counts["tests"] != 9 or counts["skipped"] != 0:
                        problems.append(f"{artifact_name}: nine T00 PostgreSQL cases must execute without skips")
                if artifact_name == "cur-r2c-policy-pg-" + sha:
                    expected = {"FxAutoPolicySchemaPgTest": 8, "FxAutoRollingLimitEvaluatorTest": 8,
                                "FxAutoDryRunTest": 6, "FxAutoOwnerAdmissionQualificationTest": 35,
                                "TenantCurrencyPolicyQualificationTest": 14, "SkuFxSellerApprovalQualificationTest": 35}
                    actual = {suite: sum(1 for s, _ in executed_cases if s == suite) for suite in expected}
                    if actual != expected or counts != {"tests": 106, "failures": 0, "errors": 0, "skipped": 0}:
                        problems.append(f"{artifact_name}: expected 106 exact executed R2C cases; actual {actual}, {counts}")
                elif artifact_name == "cur-r2c-populated-pg-" + sha:
                    if counts != {"tests": 8, "failures": 0, "errors": 0, "skipped": 0} or sum(s == "FxAutoPolicySchemaPgTest" for s, _ in executed_cases) != 8:
                        problems.append(f"{artifact_name}: expected eight executed schema cases")
                elif artifact_name == "cur-r2c-anchor-populated-pg-" + sha:
                    if counts != {"tests": 49, "failures": 0, "errors": 0, "skipped": 0} or sum(s == "TenantCurrencyPolicyQualificationTest" for s, _ in executed_cases) != 14 or sum(s == "SkuFxSellerApprovalQualificationTest" for s, _ in executed_cases) != 35:
                        problems.append(f"{artifact_name}: expected 49 executed currency and legacy FX cases")
                elif artifact_name == "cur-r2c-revoke-populated-pg-" + sha:
                    if counts != {"tests": 35, "failures": 0, "errors": 0, "skipped": 0} or sum(s == "FxAutoOwnerAdmissionQualificationTest" for s, _ in executed_cases) != 35:
                        problems.append(f"{artifact_name}: expected 35 executed lifecycle cases")
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
                    for short, identities in artifact_classes.items():
                        class_identities.setdefault(short, set()).update(identities)
                    for key in qualified_cases:
                        full_passes.setdefault(key, set()).add(artifact_name)
                    full_skips.extend((artifact_name, cls, method, reason)
                                      for cls, method, reason in qualified_skips)
                    if ret04_verified is not None:
                        ret04_artifacts[artifact_name] = dict(ret04_verified,
                            artifactId=artifact["id"], artifactSha256=hashlib.sha256(data).hexdigest())
                gate["verifiedArtifacts"].append({
                    "name": artifact_name, "id": artifact["id"], "sha256": hashlib.sha256(data).hexdigest(),
                    "surefire": counts, "skippedTests": skipped_cases,
                })
                for key in total:
                    total[key] += counts[key]
            except (OSError, ValueError, KeyError, TypeError, ArithmeticError, ET.ParseError, urllib.error.URLError, zipfile.BadZipFile) as exc:
                problems.append(f"{name}: {artifact_name} cannot verify evidence: {exc}")
        for suite in SUITES.get(name, ()):
            group = discovered_suites.get(suite)
            if group is None or group["tests"] <= group["skipped"]:
                problems.append(f"{name}: mandatory suite {suite} missing or entirely skipped")
        gate["suites"] = sorted(discovered_suites)
    manifest["testCounts"] = total
    collisions = {short: sorted(names) for short, names in class_identities.items()
                  if len(names) > 1}
    if collisions:
        problems.append("Ambiguous short suite identifiers: " + str(collisions))

    def evidence_environment(name):
        if "h2" in name:
            return "H2"
        if name.startswith("rustfs-real-infra"):
            return "REAL_INFRA"
        if "postgres" in name or "-pg-" in name or name.startswith("qr07-"):
            return "POSTGRESQL"
        return "UNCLASSIFIED"

    # A skip is not approved by a generic reason or an arbitrary alternate pass.
    # Exact TASK H2 omissions are explicitly mandated by the existing gate.
    task_pg_only_prefixes = ("t04", "t05", "t08", "t14", "t15", "t21", "t27",
                             "t28", "t35", "t36", "t38", "t42", "t46", "t47",
                             "t48", "t51", "t52", "t53")
    skip_inventory = []
    for artifact_name, cls, method, reason in sorted(full_skips):
        environment = evidence_environment(artifact_name)
        alternatives = sorted(full_passes.get((cls, method), set()) - {artifact_name})
        same_environment = [a for a in alternatives
                            if evidence_environment(a) == environment and environment != "UNCLASSIFIED"]
        status = ("PASS_ELSEWHERE_SAME_ENV" if same_environment else
                  "PASS_ELSEWHERE_OTHER_ENV" if alternatives else "NO_ALTERNATIVE_PASS")
        policy = "REVIEW_REQUIRED"
        if (artifact_name.startswith("rm-task-h2-") and
                cls.rsplit(".", 1)[-1] == "TaskServiceQualificationTest" and
                method.startswith(task_pg_only_prefixes) and
                "rm-task-postgresql-" + sha in alternatives):
            policy = "EXPECTED_SKIP_CONFIRMED"
        skip_inventory.append({"artifact": artifact_name, "class": cls, "method": method,
                               "reason": reason, "environment": environment,
                               "status": status, "alternativePasses": alternatives,
                               "sameEnvironmentPasses": same_environment,
                               "policyVerdict": policy})
    manifest["skipAudit"] = {
        "schema": "RM-QA-02B/v1", "sha": sha,
        "scope": "verified mandatory artifacts only; alternative execution is not skip approval",
        "classIdentityCollisions": collisions,
        "uniquePassingMethods": len(full_passes),
        "inventoryComplete": len(skip_inventory) == total["skipped"],
        "counts": {status: sum(row["status"] == status for row in skip_inventory)
                   for status in ("PASS_ELSEWHERE_SAME_ENV", "PASS_ELSEWHERE_OTHER_ENV", "NO_ALTERNATIVE_PASS")},
        "policyCounts": {status: sum(row["policyVerdict"] == status for row in skip_inventory)
                         for status in ("EXPECTED_SKIP_CONFIRMED", "REVIEW_REQUIRED")},
        "executions": skip_inventory,
    }
    target = ret04_artifacts.get("qr07-r0-r1-" + sha)
    full = ret04_artifacts.get("qr07-full-pg-" + sha)
    if target is not None and full is not None:
        manifest["ret04DurableQualification"].update({
            "status": "PG_EVIDENCE_REVERIFIED", "cases": 56,
            "target": target, "fullRegression": full,
            "scope": "SERVICE_ONLY; semantic review and production admission remain HOLD"})
    else:
        problems.append("CORE-RET-04: missing independently reverified exact-SHA target/full evidence")
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
    # W6 is independent of earlier R3 coverage denominators. A scenario is
    # admitted only when its explicitly mapped JUnit method EXECUTED in the
    # verified PostgreSQL XML on this exact SHA, never from source names alone.
    try:
        mvp = assess_mvp_service(ROOT, passed_cases, sha, admission_pass=not problems)
        manifest["mvpServiceQualification"] = {
            "json": "rm-mvp-service-coverage.json",
            "gate": mvp["gate"],
            "totalCoreP0": mvp["totalCoreP0"],
            "verifiedCoreP0": mvp["verifiedCoreP0"],
            "taskAddendumTotal": mvp["taskAddendumTotal"],
            "verifiedTaskAddenda": mvp["verifiedTaskAddenda"],
            "mappingSha256": mvp["mappingSha256"],
            "uniqueExecutedMethodsMapped": mvp["uniqueExecutedMethodsMapped"],
            "ret04DurableEvidence": manifest["ret04DurableQualification"]["status"]
        }
        if mvp["gate"] != "CORE_SERVICE_EVIDENCE_QUALIFIED":
            problems.append("RM-MVP W6 missing exact-SHA PostgreSQL evidence: "
                            + str(mvp["missingExecution"])
                            + "; semantic gaps: " + str(mvp["semanticGaps"]))
    except (ValueError, KeyError, OSError, TypeError) as exc:
        problems.append("RM-MVP W6 evidence mapping invalid: "
                        + type(exc).__name__ + ": " + str(exc))
        manifest["mvpServiceQualification"] = {"gate": "HOLD"}
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
