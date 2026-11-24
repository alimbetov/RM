"""Unit tests for E.3A negative business-state evidence."""
import copy
import unittest
from verify_ret04_identity_e3a import check_tenant, TABLES

class E3AEvidenceTest(unittest.TestCase):
    def baseline(self):
        return ({"tenant":"a","staff":"s"},
                {t:(0,"digest") for t in TABLES},
                {"sales_return_request":[{"state":"QUARANTINED","row_version":6}]})
    def test_unchanged_deny(self):
        first=self.baseline()
        check_tenant([first,copy.deepcopy(first)],None)
    def test_denied_mutation_rejected(self):
        first=self.baseline()
        last=copy.deepcopy(first)
        last[1]["inv_stock_balance"]=(1,"changed")
        with self.assertRaisesRegex(ValueError,"denied business mutation"):
            check_tenant([first,last],None)
    def test_identity_change_rejected(self):
        first=self.baseline()
        last=copy.deepcopy(first)
        last[0]["staff"]="different"
        with self.assertRaisesRegex(ValueError,"identity metadata changed"):
            check_tenant([first,last],None)
    def test_missing_final_rejected(self):
        with self.assertRaisesRegex(ValueError,"baseline/final"):
            check_tenant([self.baseline()],None)

if __name__=="__main__":
    unittest.main()
