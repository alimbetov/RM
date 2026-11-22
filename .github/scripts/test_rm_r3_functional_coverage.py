"""Self-tests for curated RM-R3 evidence coverage (synthetic, no network)."""
import json
from pathlib import Path
import tempfile
import unittest

import rm_r3_functional_coverage as coverage


class R3CoverageContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / coverage.CATALOG.parent).mkdir(parents=True)
        impl = "src/main/java/kz/rm/iam/VerifiedTenantRoleCommands.java"
        (self.root / impl).parent.mkdir(parents=True)
        (self.root / impl).write_text("class VerifiedTenantRoleCommands {}")
        self.catalog = {
            "schema": "RM-R3-FUNCTIONAL-COVERAGE/v1",
            "scope": "Synthetic contract self-test only",
            "domains": [{"id": key, "name": key, "requirements": [
                {
                    "id": key + "-01", "title": "Synthetic first-wave capability",
                    "delivery": "IMPLEMENTED" if idx == 0 else "PLANNED",
                    "source": impl if idx == 0 else None,
                    "proof": {"artifact": "kms-postgres-surefire", "suite": "IAMTest",
                              "test": "roleCanBeGranted"} if idx == 0 else None,
                }
            ]} for idx, key in enumerate(coverage.DOMAINS)]
        }
        self.save()

    def save(self):
        (self.root / coverage.CATALOG).write_text(json.dumps(self.catalog))

    def run_assess(self, cases=None, admitted=True):
        return coverage.assess(self.root, cases or {}, "a" * 40, admitted, write=False)

    def test_execution_and_full_sha_gate_required_to_verify(self):
        cases = {"kms-postgres-surefire": {("IAMTest", "roleCanBeGranted")}}
        result = self.run_assess(cases)
        self.assertEqual(result["summary"]["domains"], 16)
        self.assertEqual(result["summary"]["total"], 16)
        self.assertEqual(result["summary"]["implemented"], 1)
        self.assertEqual(result["summary"]["testExecuted"], 1)
        self.assertEqual(result["summary"]["verifiedInternal"], 1)
        held = self.run_assess(cases, admitted=False)
        self.assertEqual(held["summary"]["testExecuted"], 1)
        self.assertEqual(held["summary"]["verifiedInternal"], 0)
        self.assertEqual(held["domains"][0]["requirements"][0]["status"],
                         "EXECUTED_NOT_ADMITTED")

    def test_skipped_or_absent_case_never_becomes_verified(self):
        result = self.run_assess({"kms-postgres-surefire": {("IAMTest", "otherCase")}})
        self.assertEqual(result["summary"]["verifiedInternal"], 0)
        self.assertEqual(result["domains"][0]["requirements"][0]["status"],
                         "IMPLEMENTED_UNVERIFIED")

    def test_catalogue_cannot_drop_a_domain_or_duplicate_requirement(self):
        self.catalog["domains"].pop()
        self.save()
        with self.assertRaisesRegex(ValueError, "exactly 16"):
            self.run_assess()
        self.setUpCatalogReset()

    def setUpCatalogReset(self):
        # Restore only what is needed for this synthetic negative case.
        self.catalog["domains"].append({
            "id": coverage.DOMAINS[-1], "name": "SUBSCRIPTION",
            "requirements": [{"id": coverage.DOMAINS[0] + "-01",
                              "title": "Conflict", "delivery": "PLANNED",
                              "source": None, "proof": None}],
        })
        self.save()
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.run_assess()

    def test_missing_impl_and_fabricated_h2_proof_fail_closed(self):
        first = self.catalog["domains"][0]["requirements"][0]
        first["source"] = "src/main/java/kz/rm/iam/Imaginary.java"
        self.save()
        with self.assertRaisesRegex(ValueError, "source"):
            self.run_assess()
        first["source"] = "src/main/java/kz/rm/iam/VerifiedTenantRoleCommands.java"
        first["proof"]["artifact"] = "kms-h2-surefire"
        self.save()
        with self.assertRaisesRegex(ValueError, "Non-PostgreSQL"):
            self.run_assess()

    def test_planned_requirement_cannot_claim_existing_code(self):
        last = self.catalog["domains"][-1]["requirements"][0]
        last["source"] = "src/main/java/kz/rm/iam/VerifiedTenantRoleCommands.java"
        self.save()
        with self.assertRaisesRegex(ValueError, "Planned"):
            self.run_assess()

    def test_json_and_markdown_are_generated_for_all_domains(self):
        summary = coverage.assess(self.root, {}, "b" * 40, False, write=True)
        self.assertEqual(summary["summary"]["total"], 16)
        self.assertEqual(json.loads((self.root / coverage.JSON_OUT).read_text())["commitSha"],
                         "b" * 40)
        table = (self.root / coverage.MARKDOWN_OUT).read_text()
        self.assertIn("SUBSCRIPTION", table)
        self.assertIn("HOLD", table)


if __name__ == "__main__":
    unittest.main()
