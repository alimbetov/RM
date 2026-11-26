#!/usr/bin/env python3
import json
import unittest
from verify_rm_kato_directory import audit, audit_admin
from pathlib import Path

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

    def test_actual_admin_release_and_legacy_gaps_are_separate(self):
        root = Path(__file__).resolve().parents[2]
        raw = (root / "src/main/resources/kato-data.json").read_bytes()
        manifest = json.loads((root / "src/main/resources/kato-admin-release.json").read_text(encoding="utf-8"))
        result = audit_admin(raw, manifest)
        self.assertEqual(result["administrativeRows"], 375)
        self.assertEqual(result["semanticTypes"]["CITY"], 87)
        tampered = json.loads(json.dumps(manifest))
        tampered["territoryTypes"]["CITY"][0] = tampered["territoryTypes"]["DISTRICT"][0]
        with self.assertRaises(ValueError):
            audit_admin(raw, tampered)
        self.assertEqual(result["administrativeCoverage"], "STRUCTURALLY_COMPLETE")
        self.assertEqual(result["directoryCoverage"], "HOLD")
        self.assertEqual(result["activationAdmission"], "HOLD")
        self.assertEqual(result["rows"], 465)
        self.assertEqual(result["missingParentLinksAllRows"], 10)
        broken = [r for r in json.loads(raw) if r["Code"] != 190000000]
        with self.assertRaises(ValueError):
            audit_admin(json.dumps(broken).encode(), manifest)

if __name__ == "__main__":
    unittest.main()
