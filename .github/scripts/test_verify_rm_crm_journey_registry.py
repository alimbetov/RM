"""Negative contract tests for the service-only CRM journey registry."""
import copy
import json
import tempfile
import unittest
from pathlib import Path

from verify_rm_crm_journey_registry import CATALOG, JOURNEYS, MAPPED, validate

ROOT=Path(__file__).resolve().parents[2]

class CrmJourneyRegistryTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        for path in [CATALOG,JOURNEYS,*map(Path,MAPPED.values())]:
            target=self.root/path
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes((ROOT/path).read_bytes())
        self.data=json.loads((self.root/JOURNEYS).read_text())
    def save(self):
        (self.root/JOURNEYS).write_text(json.dumps(self.data))
    def test_baseline_is_complete_without_falsely_claiming_qualification(self):
        result=validate(self.root)
        self.assertEqual(result["journeys"],12)
        self.assertEqual(result["partial"],7)
        self.assertEqual(result["gaps"],4)
        self.assertEqual(result["blocked"],1)
        self.assertEqual(result["mandatoryMethods"],4)
        self.assertEqual(result["production"],"HOLD")
    def test_invented_qualified_status_rejected(self):
        self.data["journeys"][0]["qualification"]="SERVICE_QUALIFIED"
        self.save()
        with self.assertRaisesRegex(ValueError,"Overstated"):
            validate(self.root)
    def test_omitted_journey_rejected(self):
        self.data["journeys"].pop()
        self.save()
        with self.assertRaisesRegex(ValueError,"must be complete"):
            validate(self.root)
    def test_missing_java_method_rejected(self):
        self.data["journeys"][1]["existingJUnitEvidence"]=[
            "CrmInteractionQualificationTest.imaginedTestPass"]
        self.save()
        with self.assertRaisesRegex(ValueError,"Missing JUnit method"):
            validate(self.root)
    def test_customer_sales_association_must_not_be_inferred(self):
        self.data["policy"]["noInferredCustomerOrderAssociation"]=False
        self.save()
        with self.assertRaisesRegex(ValueError,"HOLD policy"):
            validate(self.root)

if __name__=="__main__":
    unittest.main()
