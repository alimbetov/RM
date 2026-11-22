"""Unit tests for the R4 static commercial dependency backlog."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from verify_rm_r4_priority import verify, CATALOG, BACKLOG

REPO = Path(__file__).resolve().parents[2]


class R4PriorityContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for file in (CATALOG, BACKLOG):
            target = self.root / file
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((REPO / file).read_bytes())
        self.backlog = json.loads((self.root / BACKLOG).read_text())

    def save(self):
        (self.root / BACKLOG).write_text(json.dumps(self.backlog), encoding="utf-8")

    def test_all_29_missing_capabilities_and_16_domains(self):
        result = verify(self.root)
        self.assertEqual(result["requirements"], 29)
        self.assertEqual(result["domains"], 16)
        self.assertEqual(result["waves"], {"W0":5,"W1":5,"W2":8,"W3":5,"W4":6})
        self.assertEqual(result["gate"], "PRIORITY_GRAPH_VALID_NOT_IMPLEMENTED")

    def test_missing_or_duplicated_capability_fails(self):
        self.backlog["requirements"].pop()
        self.save()
        with self.assertRaisesRegex(ValueError, "29 unique"):
            verify(self.root)

    def test_future_dependency_or_cycle_fails(self):
        self.backlog["requirements"][0]["dependsOnPlanned"] = ["SAL-06"]
        self.save()
        with self.assertRaisesRegex(ValueError, "Forward, unknown or cyclic"):
            verify(self.root)

    def test_invented_implementation_or_invalid_score_fails(self):
        self.backlog["requirements"][0]["readiness"] = "IMPLEMENTED"
        self.save()
        with self.assertRaisesRegex(ValueError, "Unjustified implementation"):
            verify(self.root)
        self.backlog["requirements"][0]["readiness"] = "NOT_STARTED"
        self.backlog["requirements"][0]["provisionalScore"] += 1
        self.save()
        with self.assertRaisesRegex(ValueError, "Invalid computed score"):
            verify(self.root)

    def test_renaming_source_requirement_fails(self):
        self.backlog["requirements"][0]["title"] = "Invented capability"
        self.save()
        with self.assertRaisesRegex(ValueError, "Source catalogue mismatch"):
            verify(self.root)


if __name__ == "__main__":
    unittest.main()
