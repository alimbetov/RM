"""Synthetic fail-closed tests for CRM-99 documentation and evidence map."""
import json
from pathlib import Path
import tempfile
import unittest

from verify_rm_crm_99_simulations import CAT, REG, ROOT, TEST_PATHS, validate

class Crm99EvidenceContractTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)
        paths=[CAT, REG, Path(".github/scripts/verify_rm_qa_01b.py"),
               Path("src/test/java/kz/rm/crm/CrmInteractionQualificationTest.java")]
        paths.extend(Path(x) for x in TEST_PATHS.values())
        for p in set(paths):
            dst=self.root/p
            dst.parent.mkdir(parents=True,exist_ok=True)
            dst.write_bytes((ROOT/p).read_bytes())
        self.doc=json.loads((self.root/REG).read_text())
    def save(self):
        (self.root/REG).write_text(json.dumps(self.doc),encoding="utf-8")
    def test_all_ninety_nine_cases_have_distinct_codes_and_are_not_passed_off_as_e2e(self):
        result=validate(self.root)
        self.assertEqual(result["simulations"],99)
        self.assertEqual(result["groups"],11)
        self.assertEqual(result["mandatoryNewPgMethods"],2)
        self.assertEqual(result["production"],"HOLD")
    def test_missing_case_must_fail(self):
        self.doc["cases"].pop()
        self.save()
        with self.assertRaisesRegex(ValueError,"99 ordered"):
            validate(self.root)
    def test_fabricated_test_method_must_fail(self):
        self.doc["cases"][0]["supportingEvidence"]=["CrmR1aQualificationTest.fakedJUnitPass"]
        self.save()
        with self.assertRaisesRegex(ValueError,"Missing referenced Java JUnit"):
            validate(self.root)
    def test_missing_feature_cannot_claim_component_proof(self):
        gap=next(c for c in self.doc["cases"] if c["assessment"]=="MISSING_FEATURE")
        gap["supportingEvidence"]=["CrmR1aQualificationTest.consentIsOptInAndRevocationWins"]
        self.save()
        with self.assertRaisesRegex(ValueError,"Unimplemented capability"):
            validate(self.root)
    def test_prod_and_transport_stay_stopped(self):
        self.doc["controls"]["frontend"]="GO"
        self.save()
        with self.assertRaisesRegex(ValueError,"Service-only"):
            validate(self.root)
    def test_pg_method_is_required_on_exact_sha(self):
        path=self.root/".github/scripts/verify_rm_qa_01b.py"
        text=path.read_text()
        text=text.replace('("CrmInteractionQualificationTest", "crm99ArchivedContactAndParentNeverRemainCommunicationEligible"),','')
        path.write_text(text)
        with self.assertRaisesRegex(ValueError,"Missing mandatory exact-SHA"):
            validate(self.root)

if __name__=="__main__":
    unittest.main()
