import copy
import unittest
from verify_rm_development_admission import ROOT, load_contract, check_gate, evaluate

SHA = "a" * 40
ROW = {"path": ".github/workflows/test.yml", "name": "Test", "artifacts": ["test-{sha}"]}
RUN = {"id": 1, "head_sha": SHA, "head_branch": "base_dev", "event": "push",
       "path": ROW["path"], "name": ROW["name"], "status": "completed",
       "conclusion": "success", "run_attempt": 2}
JOB = {"status": "completed", "conclusion": "success", "head_sha": SHA, "run_id": 1, "run_attempt": 2}
ART = {"id": 4, "name": "test-" + SHA, "expired": False, "size_in_bytes": 10}

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
        for jobs in ([], [dict(JOB, conclusion="skipped")], [dict(JOB, run_attempt=1)],
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

if __name__ == "__main__":
    unittest.main()
