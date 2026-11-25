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
        # Synthetic fixture: fill generic W6 gaps for execution tests, but
        # preserve the mandatory RET04-D partial-verdict guard. A fully
        # qualified W6 outcome is exercised only at the assessor boundary in
        # the positive composition test; never promote the checked-in registry.
        self.real_mvp_mapping = (self.root / MVP_MAPPING).read_bytes()
        mvp_mapping = json.loads(self.real_mvp_mapping)
        for item in (*mvp_mapping["scenarios"], *mvp_mapping["taskAddendum"]):
            if item["id"] == "CORE-RET-04":
                continue
            item["mappingVerdict"] = "EXACT"
            if not item["requiredProofs"]:
                item["requiredProofs"] = [{
                    "suite": "SyntheticW6QualificationTest",
                    "test": "synthetic_" + item["id"].replace("-", "_"),
                }]
        (self.root / MVP_MAPPING).write_text(json.dumps(mvp_mapping))
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
            # Synthetic composition fixtures do not contain genuine SQL
            # evidence. Mock only the dedicated archive verification boundary;
            # its path, manifest and raw SQL rejection tests live separately.
            mock.patch.object(gate, "verify_target_archive", return_value={
                "status":"PASS","sha":"f"*40,"cases":56,"javaTests":81}),
            mock.patch.object(gate, "verify_full_archive", return_value={
                "status":"PASS","sha":"f"*40,"coreCases":56,"suiteTests":81}),
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
        # This is a composition test, not proof that W6 is complete. The
        # real assessor must still report RET04-D as HOLD. Simulate a future
        # admitted W6 outcome at this boundary only, after checking all
        # synthetic mapped PostgreSQL executions and other prerequisite gates.
        real_assessor = gate.assess_mvp_service

        def qualified_fixture(root, passed_cases, sha, admission_pass):
            self.assertTrue(admission_pass)
            report = real_assessor(root, passed_cases, sha, admission_pass)
            self.assertEqual(report["missingExecution"], [])
            self.assertEqual(report["gate"], "HOLD")
            self.assertEqual(
                [(x["id"], x["verdict"]) for x in report["semanticGaps"]],
                [("CORE-RET-04", "SUPPORTING_PARTIAL")],
            )
            return dict(report, gate="CORE_SERVICE_EVIDENCE_QUALIFIED",
                        verifiedCoreP0=report["totalCoreP0"],
                        verifiedTaskAddenda=report["taskAddendumTotal"],
                        semanticGaps=[])

        with mock.patch.object(gate, "assess_mvp_service",
                               side_effect=qualified_fixture) as assessor:
            self.assertEqual(gate.verify(), 0)
            assessor.assert_called_once()
        result = json.loads(self.manifest_path.read_text())
        self.assertEqual(result["qa01bGate"], "PASS")
        self.assertEqual(result["admission"], "HOLD")
        self.assertTrue(result["migrationFingerprint"]["sha256"])
        self.assertGreater(result["testCounts"]["tests"], 0)

    def test_real_semantic_gaps_hold_even_with_all_mapped_pg_methods(self):
        # Restore the real, unmodified registry in the temporary workspace.
        # The generated artifacts already contain all its mapped methods.
        (self.root / MVP_MAPPING).write_bytes(self.real_mvp_mapping)
        real = json.loads(self.real_mvp_mapping)
        incomplete = [row for row in (*real["scenarios"], *real["taskAddendum"])
                      if row["mappingVerdict"] != "EXACT"]
        if not incomplete:
            # Keep a permanent negative control after real release closure.
            real["scenarios"][0]["mappingVerdict"] = "SUPPORTING_PARTIAL"
            real["scenarios"][0]["openRequirement"] = "Synthetic missing business variant"
            (self.root / MVP_MAPPING).write_text(json.dumps(real))
        self.assertEqual(gate.verify(), 1)
        result = json.loads(self.manifest_path.read_text())
        self.assertEqual(result["qa01bGate"], "FAIL")
        self.assertEqual(result["mvpServiceQualification"]["gate"], "HOLD")
        self.assertEqual(result["admission"], "HOLD")
        self.assertTrue(any("semantic gaps:" in e for e in result["evidenceErrors"]))
        report = json.loads((self.root / "rm-mvp-service-coverage.json").read_text())
        self.assertEqual(report["missingExecution"], [])
        self.assertTrue(report["semanticGaps"])

    def test_ret04_partial_verdict_is_not_promoted_by_synthetic_execution(self):
        # All fixture methods execute, but the explicit RET04-D guard remains.
        self.assertEqual(gate.verify(), 1)
        result = json.loads(self.manifest_path.read_text())
        self.assertEqual(result["qa01bGate"], "FAIL")
        self.assertEqual(result["mvpServiceQualification"]["gate"], "HOLD")
        self.assertEqual(result["admission"], "HOLD")
        report = json.loads((self.root / "rm-mvp-service-coverage.json").read_text())
        self.assertEqual(report["missingExecution"], [])
        self.assertEqual(
            [(x["id"], x["verdict"]) for x in report["semanticGaps"]],
            [("CORE-RET-04", "SUPPORTING_PARTIAL")],
        )

    def test_ret04_missing_raw_evidence_blocks_global_gate(self):
        with mock.patch.object(gate,"verify_target_archive",side_effect=ValueError("missing RET04 snapshots")):
            self.assertEqual(gate.verify(),1)
        result=json.loads(self.manifest_path.read_text())
        self.assertEqual(result["ret04DurableQualification"]["status"],"HOLD")
        self.assertTrue(any("missing RET04 snapshots" in e for e in result["evidenceErrors"]))

    def test_ret04_reverified_evidence_does_not_promote_semantic_verdict(self):
        self.assertEqual(gate.verify(),1)
        result=json.loads(self.manifest_path.read_text())
        self.assertEqual(result["ret04DurableQualification"]["status"],"PG_EVIDENCE_REVERIFIED")
        self.assertEqual(result["ret04DurableQualification"]["cases"],56)
        self.assertEqual(result["ret04DurableQualification"]["mappingVerdict"],"SUPPORTING_PARTIAL")
        self.assertEqual(result["admission"],"HOLD")

    def test_surefire_malformed_case_evidence_fails_closed(self):
        artifact_id = next(a["id"] for a in self.manifest["required"][0]["artifacts"]
                           if a["name"] == "kms-postgres-surefire")
        original = self.artifact_bytes[artifact_id]
        for mutation, expected in (
                ("counter", "Surefire testcase counters mismatch"),
                ("hidden_failure", "Surefire testcase counters mismatch"),
                ("hidden_error", "Surefire testcase counters mismatch"),
                ("duplicate", "Unnamed or duplicate testcase"),
                ("unnamed", "Unnamed or duplicate testcase"),
                ("conflicting", "Conflicting testcase outcomes")):
            with self.subTest(mutation=mutation):
                output = io.BytesIO()
                with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(output, "w") as target:
                    for index, member in enumerate(source.namelist()):
                        data = source.read(member)
                        if index == 0:
                            root = ET.fromstring(data)
                            case = root.find("testcase")
                            if mutation == "counter":
                                root.set("tests", str(int(root.get("tests")) + 1))
                            elif mutation in ("hidden_failure", "hidden_error"):
                                ET.SubElement(case, mutation.removeprefix("hidden_"))
                            elif mutation == "duplicate":
                                root.append(ET.fromstring(ET.tostring(case)))
                            elif mutation == "unnamed":
                                case.attrib.pop("name")
                            else:
                                ET.SubElement(case, "failure")
                                ET.SubElement(case, "skipped")
                            data = ET.tostring(root)
                        target.writestr(member, data)
                self.artifact_bytes[artifact_id] = output.getvalue()
                self.assertEqual(gate.verify(), 1)
                result = json.loads(self.manifest_path.read_text())
                self.assertTrue(any(expected in error for error in result["evidenceErrors"]),
                                result["evidenceErrors"])
        self.artifact_bytes[artifact_id] = original

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
