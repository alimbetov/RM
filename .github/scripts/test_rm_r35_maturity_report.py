"""UR-R3.5 synthetic evidence-contract tests (no GitHub API or database)."""
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import rm_r35_maturity_report as maturity
from rm_r3_functional_coverage import assess as assess_functional
from rm_user_role_qualification import assess_role_cases
from verify_rm_user_role_crosswalk import validate as validate_crosswalk

ROOT = HERE.parents[1]
SHA = "a" * 40


class MaturityReportContractTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / maturity.CROSSWALK.parent).mkdir(parents=True)
        self.crosswalk = json.loads((ROOT / maturity.CROSSWALK).read_text())
        (self.root / maturity.CROSSWALK).write_bytes((ROOT / maturity.CROSSWALK).read_bytes())
        catalogue = json.loads((ROOT / "docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json").read_text())
        passed = {}
        for domain in catalogue["domains"]:
            for req in domain["requirements"]:
                if req.get("proof"):
                    p = req["proof"]
                    passed.setdefault(p["artifact"], set()).add((p["suite"], p["test"]))
        for filename in ("RM-UR-R3.1-JUNIT-CASES-v1.json", "RM-UR-R3.2-JUNIT-CASES-v1.json",
                         "RM-UR-R3.3-JUNIT-CASES-v1.json"):
            for case in json.loads((ROOT / "docs/spec" / filename).read_text())["cases"]:
                passed.setdefault(case["evidenceArtifact"], set()).add(
                    (case["junitSuite"], case["junitMethod"]))
        self.functional = assess_functional(ROOT, passed, SHA, True, write=False)
        self.roles = assess_role_cases(ROOT, passed, SHA, True, write=False)
        self.structure = validate_crosswalk(ROOT)
        self.manifest = {
            "sha": SHA, "branch": "base_dev",
            "qa01bGate": "PASS", "workflowGate": "PASS",
            "admission": "HOLD", "evidenceErrors": [],
            "migrationFingerprint": {"sha256": "b" * 64, "fileCount": 70},
            "functionalCoverage": {
                "catalogueSha256": self.functional["catalogueSha256"],
            },
            "userRoleQualification": {
                "catalogueSha256": self.roles["catalogueSha256"],
                "gate": self.roles["gate"],
            },
            "testCounts": {"tests": 886, "failures": 0, "errors": 0, "skipped": 116},
            "required": [{
                "name": "synthetic-required-" + str(i),
                "sha": SHA, "status": "completed", "conclusion": "success",
                "verifiedArtifacts": [{"sha256": "c" * 64}]
            } for i in range(5)]
        }
        self.save_inputs()

    def save(self, path, data):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")

    def save_inputs(self):
        self.save(maturity.FUNCTIONAL, self.functional)
        self.save(maturity.ROLES, self.roles)
        self.save(maturity.MANIFEST, self.manifest)
        self.save(maturity.STRUCTURE, self.structure)

    def test_successful_report_has_all_16_domains_and_distinct_denominators(self):
        report = maturity.generate(self.root)
        self.assertEqual(len(report["domains"]), 16)
        self.assertEqual(report["summary"]["totalRequirements"], 75)
        self.assertEqual(report["summary"]["implemented"], 46)
        self.assertEqual(report["summary"]["verifiedCapabilities"], 46)
        self.assertEqual(report["summary"]["planned"], 29)
        self.assertEqual(report["summary"]["verifiedRoleCases"], 19)
        self.assertEqual(report["summary"]["verifiedConcurrencyCases"], 8)
        self.assertEqual(report["releaseReadiness"], "NOT_ADMITTED")
        self.assertEqual(report["productionAdmission"], "HOLD")
        self.assertTrue(any(x["roleCaseCoverage"]["status"] == "NOT_ASSESSED"
                            for x in report["domains"]))
        self.assertIn("NOT_ASSESSED", (self.root / maturity.MD_REPORT).read_text())

    def test_wrong_sha_and_failed_qa_never_generate_admitted_maturity(self):
        self.roles["commitSha"] = "b" * 40
        self.save_inputs()
        with self.assertRaisesRegex(ValueError, "Git SHA mismatch"):
            maturity.render(self.root)
        self.roles["commitSha"] = SHA
        self.manifest["qa01bGate"] = "FAIL"
        self.save_inputs()
        with self.assertRaisesRegex(ValueError, "not admitted"):
            maturity.render(self.root)

    def test_unverified_role_case_and_mismatched_role_count_fail(self):
        self.roles["cases"][0]["status"] = "NOT_RUN"
        self.save_inputs()
        with self.assertRaisesRegex(ValueError, "Unverified JUnit case"):
            maturity.render(self.root)
        self.roles["cases"][0]["status"] = "VERIFIED_INTERNAL"
        self.roles["mandatory"] = 18
        self.save_inputs()
        with self.assertRaisesRegex(ValueError, "Missing or duplicate"):
            maturity.render(self.root)

    def test_source_hash_fingerprint_and_artifact_provenance_fail_closed(self):
        self.structure["crosswalkSha256"] = "0" * 64
        self.save_inputs()
        with self.assertRaisesRegex(ValueError, "Crosswalk checksum"):
            maturity.render(self.root)
        self.structure = validate_crosswalk(ROOT)
        self.manifest["required"][0]["verifiedArtifacts"] = []
        self.save_inputs()
        with self.assertRaisesRegex(ValueError, "verified artifact"):
            maturity.render(self.root)
        self.manifest["required"][0]["verifiedArtifacts"] = [{"sha256": "c" * 64}]
        self.manifest["migrationFingerprint"]["sha256"] = "invalid"
        self.save_inputs()
        with self.assertRaisesRegex(ValueError, "Liquibase"):
            maturity.render(self.root)

    def test_changed_catalogue_denominator_or_missing_requirement_is_rejected(self):
        self.functional["summary"]["total"] = 74
        self.save_inputs()
        with self.assertRaisesRegex(ValueError, "Changed R3 denominator"):
            maturity.render(self.root)
        self.functional["summary"]["total"] = 75
        self.crosswalk["requirements"].pop()
        self.save(maturity.CROSSWALK, self.crosswalk)
        self.save_inputs()
        with self.assertRaisesRegex(ValueError, "Crosswalk checksum"):
            maturity.render(self.root)


if __name__ == "__main__":
    unittest.main()
