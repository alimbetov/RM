#!/usr/bin/env python3
import json
import unittest
from verify_rm_kato_directory import audit

def row(i, code, level=2, parent="", deleted=False):
    return dict(Id=i, Code=code, Level=level, Parent=parent, IsMarkedToDelete=deleted,
                AreaType=0, NameKaz="KZ", NameRus="RU")

def run(rows):
    return audit(json.dumps(rows).encode())

class KatoAuditTest(unittest.TestCase):
    def test_incomplete_never_admits(self):
        r=run([row(1,110000000),row(2,111000000,3,9)])
        self.assertEqual(r["directoryCoverage"],"HOLD")
        self.assertEqual(r["missingParentLinksAllRows"],1)
        self.assertEqual(r["unresolvedActiveCodes"],[111000000])
        self.assertEqual(r["activationAdmission"],"HOLD")
    def test_duplicate_fails(self):
        with self.assertRaises(ValueError):
            run([row(1,110000000),row(2,110000000)])
    def test_deleted_parent_and_cycle_fail_coverage(self):
        r=run([row(1,110000000,deleted=True),row(2,111000000,3,1),row(3,112000000,4,3)])
        self.assertEqual(r["unresolvedActiveCodes"],[111000000,112000000])
    def test_structural_not_official(self):
        r=run([row(i+1,(10+i)*10000000) for i in range(20)])
        self.assertEqual(r["directoryCoverage"],"STRUCTURALLY_COMPLETE")
        self.assertEqual(r["officialProvenance"],"NOT_ATTESTED")
        self.assertEqual(r["activationAdmission"],"HOLD")
    def test_normalized_hash_order_independent(self):
        rows=[row(1,110000000),row(2,430000000)]
        self.assertEqual(run(rows)["normalizedSha256"],run(rows[::-1])["normalizedSha256"])

if __name__ == "__main__":
    unittest.main()
