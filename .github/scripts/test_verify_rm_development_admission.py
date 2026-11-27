import copy
import unittest
from verify_rm_development_admission import ROOT, load_contract, check_gate, evaluate

SHA = "a" * 40
ROW = {"path": ".github/workflows/test.yml", "name": "Test", "artifacts": ["test-{sha}"]}
RUN = {"id": 1, "head_sha": SHA, "head_branch": "base_dev", "event": "push",
       "path": ROW["path"], "name": ROW["name"], "status": "completed",
       "conclusion": "success", "run_attempt": 1, "repository": {"id": 7}, "head_repository": {"id": 7},
       "run_started_at": "2026-11-27T01:00:00Z", "updated_at": "2026-11-27T02:00:00Z"}
JOB = {"status": "completed", "conclusion": "success", "head_sha": SHA, "run_id": 1, "run_attempt": 1}
ART = {"id": 4, "name": "test-" + SHA, "expired": False, "size_in_bytes": 10,
       "digest": "sha256:" + "a"*64, "created_at": "2026-11-27T01:30:00Z",
       "updated_at": "2026-11-27T01:30:00Z",
       "workflow_run": {"id": 1, "head_sha": SHA, "head_branch": "base_dev",
                        "repository_id": 7, "head_repository_id": 7}}

class DevelopmentAdmissionTest(unittest.TestCase):
    def test_repository_contract(self):
        self.assertEqual(12, len(load_contract(ROOT)))

    def test_success(self):
        self.assertEqual([4], check_gate(ROW, RUN, [JOB], [ART], SHA)["artifactIds"])

    def test_wrong_run(self):
        for key, value in [("head_sha", "b"*40), ("head_branch", "main"), ("event", "workflow_dispatch"),
                           ("path", "wrong"), ("name", "wrong"), ("status", "in_progress"),
                           ("conclusion", "failure"), ("run_attempt", 0)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                check_gate(ROW, dict(RUN, **{key: value}), [JOB], [ART], SHA)

    def test_jobs(self):
        for jobs in ([], [dict(JOB, conclusion="skipped")], [dict(JOB, run_attempt=2)],
                     [dict(JOB, head_sha="b"*40)], [dict(JOB, run_id=2)]):
            with self.assertRaises(ValueError):
                check_gate(ROW, RUN, jobs, [ART], SHA)

    def test_artifacts(self):
        for artifacts in ([], [ART, copy.copy(ART)], [dict(ART, expired=True)], [dict(ART, size_in_bytes=0)],
                          [dict(ART, id=0)], [dict(ART, name="other")]):
            with self.assertRaises(ValueError):
                check_gate(ROW, RUN, [JOB], artifacts, SHA)

    def test_stale_branch(self):
        with self.assertRaises(ValueError):
            evaluate(lambda _: {"commit": {"sha": "b"*40}}, [ROW], SHA, "alimbetov/RM")

    def test_missing_run(self):
        def get(path):
            return {"commit": {"sha": SHA}} if "/branches/" in path else {"workflow_runs": []}
        with self.assertRaises(ValueError):
            evaluate(get, [ROW], SHA, "alimbetov/RM")

    def test_api_failure(self):
        def get(_):
            raise OSError("unavailable")
        with self.assertRaises(OSError):
            evaluate(get, [ROW], SHA, "alimbetov/RM")

    def test_rerun_rejected(self):
        with self.assertRaisesRegex(ValueError, "RERUN_PROVENANCE"):
            check_gate(ROW, dict(RUN, run_attempt=2), [dict(JOB, run_attempt=2)], [ART], SHA)

    def test_artifact_provenance(self):
        for change in ({"workflow_run": dict(ART["workflow_run"], head_sha="b"*40)},
                       {"workflow_run": dict(ART["workflow_run"], id=2)},
                       {"digest": "unknown"}, {"created_at": "2026-11-26T01:00:00Z"}):
            with self.assertRaises(ValueError):
                check_gate(ROW, RUN, [JOB], [dict(ART, **change)], SHA)

    def fixture(self, stale=False, rerun=False, replacement=False, artifact_change=False):
        calls = {"branch": 0, "list": 0, "artifacts": 0}
        def get(path):
            if "/branches/" in path:
                calls["branch"] += 1
                return {"commit": {"sha": "b"*40 if stale and calls["branch"] > 1 else SHA}}
            if "/workflows/" in path:
                calls["list"] += 1
                return {"workflow_runs": [dict(RUN, id=2) if replacement and calls["list"] > 1 else RUN]}
            if "/jobs?" in path:
                return {"jobs": [JOB]}
            if "/artifacts?" in path:
                calls["artifacts"] += 1
                return {"artifacts": [dict(ART, id=8) if artifact_change and calls["artifacts"] > 1 else ART]}
            return dict(RUN, run_attempt=2) if rerun else RUN
        return get

    def test_complete_api_observation(self):
        result = evaluate(self.fixture(), [ROW], SHA, "alimbetov/RM")
        self.assertEqual([4], result[0]["artifactIds"])

    def test_final_branch_fence(self):
        with self.assertRaisesRegex(ValueError, "STALE_SHA"):
            evaluate(self.fixture(stale=True), [ROW], SHA, "alimbetov/RM")

    def test_rerun_race(self):
        with self.assertRaisesRegex(ValueError, "RUN_CHANGED"):
            evaluate(self.fixture(rerun=True), [ROW], SHA, "alimbetov/RM")

    def test_new_run_race(self):
        with self.assertRaisesRegex(ValueError, "RUN_CHANGED"):
            evaluate(self.fixture(replacement=True), [ROW], SHA, "alimbetov/RM")

    def test_artifact_replacement_race(self):
        with self.assertRaisesRegex(ValueError, "ARTIFACT_CHANGED"):
            evaluate(self.fixture(artifact_change=True), [ROW], SHA, "alimbetov/RM")

if __name__ == "__main__":
    unittest.main()
