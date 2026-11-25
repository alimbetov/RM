import copy
import json
from pathlib import Path
import tempfile
import unittest

from verify_rm_ie00b_source_inventory import ROOT, MANIFEST, git_blob, verify

class Ie00bInventoryTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((ROOT / MANIFEST).read_text(encoding="utf-8"))
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for row in self.manifest["sourceInventory"]:
            if row["baselineBlob"] is not None:
                src = ROOT / row["path"]
                dst = self.root / row["path"]
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
                # Fixture pins current bytes; independent command checks baseline.
                row["baselineBlob"] = git_blob(dst.read_bytes())
                row["status"] = "UNCHANGED" if row["baselineBlob"] == row["designBlob"] else "DRIFT"

    def test_valid_inventory(self):
        self.assertEqual(verify(self.root, self.manifest), [])

    def test_drift_is_detected(self):
        row = next(r for r in self.manifest["sourceInventory"] if r["baselineBlob"])
        (self.root / row["path"]).write_bytes(b"changed")
        self.assertTrue(any("Source drift" in e for e in verify(self.root, self.manifest)))

    def test_no_runtime_promotion(self):
        self.manifest["admission"] = "PASS"
        self.assertTrue(any("runtime admission" in e for e in verify(self.root, self.manifest)))

    def test_duplicate_scenario_rejected(self):
        self.manifest["scenarioIds"][0] = self.manifest["scenarioIds"][1]
        self.assertTrue(any("scenarioIds" in e for e in verify(self.root, self.manifest)))
