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


class QualificationEvidenceVerifierTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        directory = self.root / "src/main/resources/db/changelog"
        directory.mkdir(parents=True)
        (directory / "db.changelog-master.sql").write_text("-- baseline\n")
        self.manifest_path = self.root / "manifest.json"
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
                if artifact_name.startswith("rm-task-"):
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
                    ]
                    suite_cases={"TaskServiceQualificationTest":names}
                    if artifact_name.startswith("rm-task-h2-"):
                        skipped={("TaskServiceQualificationTest",n) for n in names if n.startswith(("t04","t05","t08","t14","t15","t21","t27","t28","t35","t36"))}
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


if __name__ == "__main__":
    unittest.main()
