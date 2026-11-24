"""W6 source-aware 54 CORE-P0 fail-closed self-tests (no network/DB)."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from rm_mvp_service_evidence import assess, SOURCE, MAPPING, REPORT, ARTIFACT


class MvpServiceEvidenceContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        project = HERE.parents[1]
        for path in (SOURCE, MAPPING):
            output = self.root / path
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes((project / path).read_bytes())
        self.registry = json.loads((self.root / MAPPING).read_text())
        self.passed = {ARTIFACT: {
            (p["suite"], p["test"])
            for row in (*self.registry["scenarios"], *self.registry["taskAddendum"])
            for p in row["requiredProofs"]
        }}
        self.sha = "a" * 40

    def test_exactly_54_core_and_2_tasks_are_bound_to_executed_pg_methods(self):
        report = assess(self.root, self.passed, self.sha, True)
        self.assertEqual(report["gate"], "HOLD")
        self.assertEqual(report["totalCoreP0"], 54)
        self.assertEqual(report["verifiedCoreP0"], 0)
        self.assertEqual(len(report["semanticGaps"]), 56)
        self.assertEqual(report["verifiedTaskAddenda"], 0)
        self.assertEqual(report["productionAdmission"], "HOLD")
        self.assertTrue((self.root / REPORT).is_file())

    def test_missing_pg_case_cannot_be_satisfied_by_source_code_or_h2(self):
        entry = self.registry["scenarios"][0]
        p = entry["requiredProofs"][0]
        self.passed[ARTIFACT].remove((p["suite"], p["test"]))
        report = assess(self.root, self.passed, self.sha, True, write=False)
        self.assertEqual(report["gate"], "HOLD")
        self.assertEqual(report["verifiedCoreP0"], 0)
        self.assertEqual(report["missingExecution"][0]["id"], entry["id"])

    def test_wrong_behavior_bindings_are_explicit_gaps(self):
        gaps = [x for x in self.registry["scenarios"] if x["mappingVerdict"] == "GAP"]
        self.assertEqual(len(gaps), 11)
        self.assertTrue(all(not x["requiredProofs"] and x["legacyProofs"] for x in gaps))
        report = assess(self.root, self.passed, self.sha, True, write=False)
        self.assertEqual(report["gate"], "HOLD")

    def test_ret04_mapping_cannot_drop_method_or_claim_exact(self):
        row = next(x for x in self.registry["scenarios"] if x["id"] == "CORE-RET-04")
        original = copy.deepcopy(row)
        for mutation in ("missing", "exact"):
            row.clear()
            row.update(copy.deepcopy(original))
            if mutation == "missing":
                row["requiredProofs"].pop()
            else:
                row["mappingVerdict"] = "EXACT"
            (self.root / MAPPING).write_text(json.dumps(self.registry))
            with self.assertRaisesRegex(ValueError, "RET04-D"):
                assess(self.root, self.passed, self.sha, True, write=False)

    def test_exact_empty_mapping_fails_closed(self):
        row = next(x for x in self.registry["scenarios"] if x["mappingVerdict"] == "GAP")
        row["mappingVerdict"] = "EXACT"
        (self.root / MAPPING).write_text(json.dumps(self.registry))
        with self.assertRaisesRegex(ValueError, "executable proof absent"):
            assess(self.root, self.passed, self.sha, True, write=False)

    def test_missing_canonical_id_fails_closed(self):
        self.registry["scenarios"].pop()
        (self.root / MAPPING).write_text(json.dumps(self.registry))
        with self.assertRaisesRegex(ValueError, "CORE-P0"):
            assess(self.root, self.passed, self.sha, True, write=False)

    def test_failed_parent_admission_never_grants_service_coverage(self):
        report = assess(self.root, self.passed, self.sha, False, write=False)
        self.assertEqual(report["gate"], "HOLD")
        self.assertEqual(report["productionAdmission"], "HOLD")


if __name__ == "__main__":
    unittest.main()
