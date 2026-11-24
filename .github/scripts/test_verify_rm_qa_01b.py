"""Negative/positive self-tests for RM-QA-01B (no external services)."""
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile
import xml.etree.ElementTree as ET

import verify_rm_qa_01b as gate
from rm_mvp_service_evidence import SOURCE as MVP_SOURCE, MAPPING as MVP_MAPPING


class QualificationEvidenceVerifierTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        directory = self.root / "src/main/resources/db/changelog"
        directory.mkdir(parents=True)
        (directory / "db.changelog-master.sql").write_text("-- baseline\n")
        self.manifest_path = self.root / "manifest.json"
        # W6 assessor must see the real canonical registry in its isolated
        # synthetic workspace. Otherwise the verifier self-test fails due to
        # absent fixture files, not due to missing release evidence.
        project_root = Path(__file__).resolve().parents[2]
        for source in (MVP_SOURCE, MVP_MAPPING):
            target = self.root / source
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((project_root / source).read_bytes())
        mvp_mapping = json.loads((self.root / MVP_MAPPING).read_text())
        mvp_required = {
            (p["suite"], p["test"])
            for item in (*mvp_mapping["scenarios"], *mvp_mapping["taskAddendum"])
            for p in item["requiredProofs"]
        }
        self.artifact_bytes = {}
        gates = []
        artifact_id = 100
        for workflow, artifacts in gate.REQUIRED.items():
            group = {
                "name": workflow, "sha": "f" * 40,
                "status": "completed", "conclusion": "success",
                "jobs": [{"name": "required-job", "conclusion": "success"}],
                "artifacts": [],
            }
            for artifact in artifacts:
                artifact_name=artifact.replace("{sha}","f"*40)
                suite_cases = {"SmokeQualificationTest": ["smokePasses"]}
                skipped = set()
                if artifact_name.startswith("rm-task-t00-postgresql-"):
                    suite_cases={"CrmInteractionQualificationTest": [
                        "t00CancellationEventHasCanonicalIdentityAndSingleRevision",
                        "t00OuterTransactionRollbackRemovesCancellationAndSourceEvent",
                        "t00CancellationIntentPortCannotRunOutsideCrmTransaction",
                        "t00PgConcurrentCancellationCreatesExactlyOneCanonicalEvent",
                        "t00PgActorSuspensionCommitsBeforeCancellationDeniesWithoutEvent",
                        "t00PgCancellationCommitsBeforeActorSuspensionPreservesOneEvent",
                        "t00PgCanonicalEventIdentityAndPrecisionAreStoredExactly",
                        "t00PgTransactionPortReceivesPersistedCanonicalEvent",
                        "t00PgPortFailureRollsBackCancellationAndEvent",
                    ]}
                elif artifact_name.startswith("rm-task-"):
                    names = [
                        "t01ManagerCreatesAssignedTaskAndEmployeeCompletesHistory",
                        "t02TenantIsolationAndEmployeeCannotManageOrArchive",
                        "t03StaleVersionAndInvalidTransitionLeaveNoEvent",
                        "t04PostgresqlConcurrentStartAllowsExactlyOneTransition",
                        "t05PostgresqlImmutableTaskHistoryRejectsTampering",
                        "t06ReassignmentHistoryCapturesBothEmployeesAndRevokesOldVisibility",
                        "t07SuspendedAssigneeCannotReceiveNewTaskOrReassignment",
                        "t08PostgresqlSuspensionWaitsUntilAdmittedCreationCommits",
                        "t09LegacyNullAssigneeCanBeAssignedWithoutLosingAudit",
                        "t10TitleVocabularyRejectsSensitiveFreeTextWithoutWrites",
                        "t11LostResponseRetryReturnsSameTaskWithoutDuplicateTransition",
                        "t12EarlierTaskPermissionRevocationDeniesExecutionWithoutWrites",
                        "t13EarlierMembershipRevocationDeniesAssigneeCreation",
                        "t14PostgresqlRoleRevocationWaitsForAdmittedExecutionCommit",
                        "t15PostgresqlMembershipRevocationWaitsForTaskAssignmentCommit",
                        "t16LostResponseAssignmentRetryUsesOneImmutableEvent",
                        "t17ArchiveRetryAndCrossTaskKeyCollisionFailClosed",
                        "t18CrmFollowupCreationRetryAndScopedLinkRead",
                        "t19CrmFollowupRejectsForeignAndClosedInteractionWithNoTasks",
                        "t20CrmFollowupCannotReuseTaskRequestForAnotherInteraction",
                        "t21PostgresqlCrmLinkIsImmutableAndCompositeTenantFkIsEnforced",
                        "t22CrmFollowupRetryAfterSourceClosureReturnsOriginalTask",
                        "t23ManagerReschedulePreservesDueEvidenceAndKeyedReplay",
                        "t24TerminalTaskCannotRescheduleAndReplayedOldDueReceiptIsSuperseded",
                        "t25ClosedCrmSourcePreservesLinkedTaskAndHistoryButRejectsNewLink",
                        "t26RollbackBeforeFollowupCommitAllowsExactRequestRetry",
                        "t27PostgresqlConcurrentRescheduleAndReassignmentHaveSingleWinner",
                        "t28PostgresqlTwoManagersConcurrentReassignmentExactlyOneWins",
                        "t29ManagerCancellationIsIdempotentAndNotArchival",
                        "t30InProgressCancellationForbidsCompletionAndPreservesEvidence",
                        "t31RequeueRemovesAssignmentAndManagerMayClaimForNewStaff",
                        "t32RequeueRequiresStoppedWorkAndCannotBeStartedUnassigned",
                        "t33CancelledCrmInteractionRequiresExplicitScopedReconciliation",
                        "t34ReconciliationAndQueueRollbackLeaveNoPartialEvents",
                        "t35PostgresqlCancelRacesWithEmployeeCompletionExactlyOneWins",
                        "t36PostgresqlRequeueRacesWithReassignmentExactlyOneWins",
                        "t37ReconciliationRejectsUnrelatedSourceAndForeignTenantWithoutTaskMutation",
                        "t38PostgresqlSourceCancellationSerializesAgainstFollowupCreation",
                        "t39ManagerQueuePaginatesAndHidesUnassignedTasksFromEmployee",
                        "t40ManagerQueueCannotExposeAnotherTenantOrUnauthorizedRole",
                        "t41CancelledCrmReconciliationPageAdvancesWithoutRepeatingTasks",
                        "t42PostgresqlQueuePageDoesNotResurrectConcurrentlyClaimedTask",
                        "t43FrozenReconciliationTraversalSurvivesConcurrentTaskCancellation",
                        "t44SnapshotTenantAndActorPrivacyRequestReplayAndInvalidSource",
                        "t45SnapshotRollbackBeforeCommitKeepsNoManifestAndRetryWorks",
                        "t46PostgresqlSnapshotManifestAndEntriesAreImmutable",
                        "t47PostgresqlConcurrentSnapshotSameRequestCommitsSingleSealedManifest",
                        "t48PostgresqlSnapshotOverCapacityRejectsWithoutPartialManifest",
                        "t49CommittedSnapshotReplaysAfterStatusChangeAndLostResponse",
                        "t50SnapshotIntegrityVerifiesPersistedRowsAndDeniesForeignActor",
                        "t51PostgresqlExactlyTenThousandTasksCaptureAndPageWithoutLoss",
                        "t52PostgresqlCrmStatusRaceSerializesWithSnapshotCapture",
                        "t53PostgresqlManagerRevocationRacesWithSnapshotCapture",
                    ]
                    suite_cases={"TaskServiceQualificationTest":names}
                    if artifact_name.startswith("rm-task-h2-"):
                        skipped={("TaskServiceQualificationTest",n) for n in names if n.startswith(("t04","t05","t08","t14","t15","t21","t27","t28","t35","t36","t38","t42","t46","t47","t48","t51","t52","t53"))}
                if artifact == "kms-postgres-surefire":
                    suite_cases = {
                        "CommercialCoreE2EQualificationTest": [
                            "postgresSec09SuspendCommitsBeforeCheckoutMustFailWithoutOrderEffects",
                            "postgresSec09CheckoutCommitsBeforeSuspendKeepsOneAcceptedSnapshot",
                        ],
                        "AutoOrderR1QualificationTest": ["retryIsIdempotentAndChangesAreRejected"],
                        "InventoryTransferStocktakeQualificationTest": [
                            "postgresInv16ConcurrentAdvanceCannotDoubleCommitCursorOrFindings",
                            "postgresInv16DurableAdvanceAndConcurrentReceivingHaveNoFalseDrift",
                            "durableReconciliationRollbackDoesNotAdvanceCursorOrPersistFindings",
                            "durableReconciliationStreamsEntireLedgerBeyondFiveHundredMovements",
                        ],
                    }
                if artifact == "kms-postgres-surefire":
                    for suite, method in mvp_required:
                        suite_cases.setdefault(suite, [])
                        if method not in suite_cases[suite]:
                            suite_cases[suite].append(method)
                elif artifact != "kms-postgres-surefire":
                    for suite in gate.SUITES.get(workflow, ()):
                        suite_cases.setdefault(suite, ["qualified"])
                for suite in gate.SUITES.get(workflow, ()):
                    suite_cases.setdefault(suite, ["qualified"])
                # Positive fixtures must include every named mandatory case.
                # Negative tests below can deliberately skip one to prove HOLD.
                for required_suite, required_method in gate.REQUIRED_CASES.get(artifact, set()):
                    suite_cases.setdefault(required_suite, [])
                    if required_method not in suite_cases[required_suite]:
                        suite_cases[required_suite].append(required_method)
                # R2C artifacts have distinct strict per-suite contracts.
                # Replace generic smoke fixtures rather than appending suites.
                r2c_counts = {
                    "cur-r2c-policy-pg-": {
                        "FxAutoPolicySchemaPgTest": 8,
                        "FxAutoRollingLimitEvaluatorTest": 8,
                        "FxAutoDryRunTest": 6,
                        "FxAutoOwnerAdmissionQualificationTest": 35,
                        "TenantCurrencyPolicyQualificationTest": 14,
                        "SkuFxSellerApprovalQualificationTest": 35,
                    },
                    "cur-r2c-populated-pg-": {"FxAutoPolicySchemaPgTest": 8},
                    "cur-r2c-revoke-populated-pg-": {"FxAutoOwnerAdmissionQualificationTest": 35},
                    "cur-r2c-anchor-populated-pg-": {
                        "TenantCurrencyPolicyQualificationTest": 14,
                        "SkuFxSellerApprovalQualificationTest": 35,
                    },
                }
                for prefix, expected in r2c_counts.items():
                    if artifact_name.startswith(prefix):
                        suite_cases = {suite: [f"syntheticCase{i}" for i in range(count)]
                                       for suite, count in expected.items()}
                self.artifact_bytes[artifact_id] = self.make_zip(suite_cases, skipped)
                group["artifacts"].append({"name": artifact_name, "id": artifact_id})
                artifact_id += 1
            gates.append(group)
        self.manifest = {
            "repository": "alimbetov/RM", "sha": "f" * 40, "required": gates,
        }
        self.save()
        self.patches = [
            mock.patch.object(gate, "ROOT", self.root),
            mock.patch.object(gate, "MANIFEST", self.manifest_path),
            mock.patch.object(gate, "assess", return_value={
                "catalogueSha256": "self-test", "summary": {"domains": 16}}),
            mock.patch.object(gate, "assess_role_cases", return_value={
                "catalogueSha256": "synthetic-role-proof",
                "gate": "USER_ROLE_SERVICE_QUALIFIED",
                "mandatory": 6,
                "executedPostgresql": 6,
                "verifiedInternal": 6}),
            mock.patch.object(gate, "api", side_effect=self.fake_api),
            mock.patch.dict(os.environ, {
                "GITHUB_SHA": "f" * 40, "GITHUB_REPOSITORY": "alimbetov/RM",
                "GH_TOKEN": "self-test-only",
            }),
        ]
        for patch in self.patches:
            patch.start()
            self.addCleanup(patch.stop)

    @staticmethod
    def make_zip(suites, skipped=None):
        memory = io.BytesIO()
        with zipfile.ZipFile(memory, "w") as archive:
            for suite, cases in suites.items():
                root = ET.Element("testsuite", {
                    "name": "kz.rm." + suite, "tests": str(len(cases)),
                    "failures": "0", "errors": "0",
                    "skipped": str(sum((suite, case) in (skipped or set()) for case in cases)),
                })
                for case in cases:
                    child = ET.SubElement(root, "testcase", {"name": case})
                    if (suite, case) in (skipped or set()):
                        ET.SubElement(child, "skipped", {"message": "required test skipped"})
                archive.writestr("TEST-" + suite + ".xml", ET.tostring(root))
        return memory.getvalue()

    def fake_api(self, url, token):
        artifact_id = int(url.split("/artifacts/")[1].split("/")[0])
        return self.artifact_bytes[artifact_id]

    def save(self):
        self.manifest_path.write_text(json.dumps(self.manifest))

    def test_all_required_evidence_passes_without_granting_production_admission(self):
        self.assertEqual(gate.verify(), 0)
        result = json.loads(self.manifest_path.read_text())
        self.assertEqual(result["qa01bGate"], "PASS")
        self.assertEqual(result["admission"], "HOLD")
        self.assertTrue(result["migrationFingerprint"]["sha256"])
        self.assertGreater(result["testCounts"]["tests"], 0)

    def test_wrong_commit_sha_fails_closed(self):
        self.manifest["sha"] = "bad"
        self.save()
        self.assertEqual(gate.verify(), 1)
        self.assertEqual(json.loads(self.manifest_path.read_text())["qa01bGate"], "FAIL")

    def test_missing_h2_artifact_fails_closed(self):
        self.manifest["required"][0]["artifacts"] = self.manifest["required"][0]["artifacts"][1:]
        self.save()
        self.assertEqual(gate.verify(), 1)

    def test_required_postgresql_case_skipped_fails_closed(self):
        artifact_id = next(a["id"] for a in self.manifest["required"][0]["artifacts"]
                           if a["name"] == "kms-postgres-surefire")
        cases = {
            "CommercialCoreE2EQualificationTest": [
                "postgresSec09SuspendCommitsBeforeCheckoutMustFailWithoutOrderEffects",
                "postgresSec09CheckoutCommitsBeforeSuspendKeepsOneAcceptedSnapshot",
            ],
            "AutoOrderR1QualificationTest": ["retryIsIdempotentAndChangesAreRejected"],
            "InventoryTransferStocktakeQualificationTest": [
                "postgresInv16ConcurrentAdvanceCannotDoubleCommitCursorOrFindings",
                "postgresInv16DurableAdvanceAndConcurrentReceivingHaveNoFalseDrift",
                "durableReconciliationRollbackDoesNotAdvanceCursorOrPersistFindings",
                "durableReconciliationStreamsEntireLedgerBeyondFiveHundredMovements",
            ],
        }
        missing = {("InventoryTransferStocktakeQualificationTest",
                    "postgresInv16ConcurrentAdvanceCannotDoubleCommitCursorOrFindings")}
        self.artifact_bytes[artifact_id] = self.make_zip(cases, missing)
        self.assertEqual(gate.verify(), 1)
        self.assertIn("missing/skipped", json.loads(self.manifest_path.read_text())["evidenceErrors"][0])

    def test_r2c_missing_artifact_fails_closed(self):
        group = next(g for g in self.manifest["required"] if g["name"] ==
                     "RM CUR-R2C Policy Schema PostgreSQL Qualification")
        group["artifacts"] = group["artifacts"][:-1]
        self.save()
        self.assertEqual(gate.verify(), 1)
        self.assertTrue(any("missing/duplicate artifact declarations" in e for e in
                            json.loads(self.manifest_path.read_text())["evidenceErrors"]))

    def test_r2c_incomplete_or_skipped_evidence_fails_closed(self):
        group = next(g for g in self.manifest["required"] if g["name"] ==
                     "RM CUR-R2C Policy Schema PostgreSQL Qualification")
        for prefix in ("cur-r2c-policy-pg-", "cur-r2c-populated-pg-", "cur-r2c-anchor-populated-pg-"):
            artifact_id = next(a["id"] for a in group["artifacts"] if a["name"].startswith(prefix))
            original = self.artifact_bytes[artifact_id]
            with self.subTest(artifact=prefix):
                try:
                    self.artifact_bytes[artifact_id] = self.make_zip(
                        {"FxAutoPolicySchemaPgTest": ["onlyOne"]})
                    self.save()
                    self.assertEqual(gate.verify(), 1)
                    self.assertTrue(any(e.startswith(prefix) for e in
                                        json.loads(self.manifest_path.read_text())["evidenceErrors"]))
                    with zipfile.ZipFile(io.BytesIO(original)) as archive:
                        suites = {}
                        for member in archive.namelist():
                            root = ET.fromstring(archive.read(member))
                            suites[root.attrib["name"].split(".")[-1]] = [
                                case.attrib["name"] for case in root.findall("testcase")]
                    suite = next(iter(suites))
                    self.artifact_bytes[artifact_id] = self.make_zip(
                        suites, {(suite, suites[suite][0])})
                    self.save()
                    self.assertEqual(gate.verify(), 1)
                    self.assertTrue(any(e.startswith(prefix) for e in
                                        json.loads(self.manifest_path.read_text())["evidenceErrors"]))
                finally:
                    self.artifact_bytes[artifact_id] = original


if __name__ == "__main__":
    unittest.main()
