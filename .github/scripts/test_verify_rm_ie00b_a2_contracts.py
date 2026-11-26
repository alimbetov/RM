import json
import unittest
from verify_rm_ie00b_a2_contracts import ROOT, PATH, verify

class A2ContractTest(unittest.TestCase):
    def setUp(self):
        self.m = json.loads((ROOT / PATH).read_text(encoding="utf-8"))

    def test_baseline(self):
        self.assertEqual(verify(self.m), [])

    def test_no_warehouse_bypass(self):
        self.m["metrics"][-1]["warehouseAssignment"] = False
        self.assertTrue(any("metric scope" in e for e in verify(self.m)))

    def test_no_default_zone(self):
        self.m["calendar"]["defaultZoneAllowed"] = True
        self.assertIn("calendar contract", verify(self.m))

    def test_no_false_zero(self):
        self.m["quality"]["unknownAsZero"] = True
        self.assertIn("quality contract", verify(self.m))

    def test_no_runtime_promotion(self):
        self.m["admission"] = "PASS"
        self.assertTrue(any("runtime admission" in e for e in verify(self.m)))

    def test_no_currency_mixing(self):
        self.m["metrics"][1]["unit"] = "COUNT"
        self.assertTrue(any("metric unit" in e for e in verify(self.m)))

    def test_no_worker_actor_forgery(self):
        self.m["security"]["workerMayMintActor"] = True
        self.assertIn("security fence", verify(self.m))
