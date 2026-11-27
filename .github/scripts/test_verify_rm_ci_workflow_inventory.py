import copy
import json
import unittest
from verify_rm_ci_workflow_inventory import ROOT, REGISTRY, verify

class WorkflowInventoryTest(unittest.TestCase):
    def setUp(self):
        self.registry = json.loads((ROOT / REGISTRY).read_text())

    def test_current_inventory(self):
        self.assertEqual([], verify(ROOT, self.registry))

    def test_missing_workflow_rejected(self):
        self.registry["workflows"].pop()
        self.assertTrue(verify(ROOT, self.registry))

    def test_duplicate_rejected(self):
        self.registry["workflows"].append(copy.deepcopy(self.registry["workflows"][0]))
        self.assertTrue(verify(ROOT, self.registry))

    def test_stale_blob_rejected(self):
        self.registry["workflows"][0]["reviewedBlob"] = "0" * 40
        self.assertTrue(verify(ROOT, self.registry))

    def test_skip_rejected(self):
        self.registry["workflows"][0]["decision"] = "NOT_REQUIRED"
        self.assertTrue(verify(ROOT, self.registry))

    def test_release_gate_removal_rejected(self):
        next(r for r in self.registry["workflows"] if r["mandatoryRelease"])["mandatoryRelease"] = False
        self.assertTrue(verify(ROOT, self.registry))

    def test_admission_escalation_rejected(self):
        self.registry["admission"] = "PASS"
        self.assertTrue(verify(ROOT, self.registry))
