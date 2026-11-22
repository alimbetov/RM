"""UR-R3.0 mapping completeness and fail-closed semantics (no DB/network)."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_rm_user_role_crosswalk import (
    CATALOG, CROSSWALK, PERMISSION_ENUM,
    permission_names, validate, validate_documents,
)
import json


ROOT = Path(__file__).resolve().parents[2]


class UserRoleCrosswalkQualificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalogue = json.loads((ROOT / CATALOG).read_text(encoding="utf-8"))
        cls.crosswalk = json.loads((ROOT / CROSSWALK).read_text(encoding="utf-8"))
        cls.permissions = permission_names(
            (ROOT / PERMISSION_ENUM).read_text(encoding="utf-8")
        )

    def evaluate(self, mutate=None):
        crosswalk = copy.deepcopy(self.crosswalk)
        if mutate:
            mutate(crosswalk)
        return validate_documents(self.catalogue, crosswalk, self.permissions)

    def test_all_16_domains_75_requirements_and_5_proof_gaps_are_registered(self):
        report = validate(ROOT)
        self.assertEqual(report["domains"], 16)
        self.assertEqual(report["total"], 75)
        self.assertEqual(report["implemented"], 46)
        self.assertEqual(report["planned"], 29)
        self.assertEqual(report["declaredCanonicalProof"], 41)
        self.assertEqual(report["roleCasesPassed"], 0)
        self.assertEqual(report["gate"], "MAPPING_VALID_NOT_SERVICE_QUALIFIED")
        self.assertEqual(report["missingCanonicalProof"],
                         ["FIL-05", "INV-02", "KMS-01", "KMS-03", "ORG-04"])

    def test_missing_requirement_fails_instead_of_improving_coverage(self):
        with self.assertRaisesRegex(ValueError, "Missing R3 requirements"):
            self.evaluate(lambda j: j["requirements"].pop())

    def test_duplicate_id_and_unknown_domain_fail(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.evaluate(lambda j: j["requirements"].append(
                copy.deepcopy(j["requirements"][0])
            ))
        with self.assertRaisesRegex(ValueError, "Source/delivery/proof mismatch"):
            self.evaluate(lambda j: j["requirements"][0].update(
                domainId="UNKNOWN"
            ))

    def test_forged_proof_and_false_pass_claims_fail(self):
        with self.assertRaisesRegex(ValueError, "Source/delivery/proof mismatch"):
            self.evaluate(lambda j: j["requirements"][0].update(
                canonicalProof={"artifact": "invented", "suite": "No", "test": "Fake"}
            ))
        with self.assertRaisesRegex(ValueError, "claim qualification"):
            self.evaluate(lambda j: j["requirements"][0].update(
                qualified=True, userRoleCaseStatus="PASS"
            ))

    def test_unknown_permission_and_actor_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "DomainPermission"):
            self.evaluate(lambda j: j["requirements"][0].update(
                requiredPermissionsAllOf=["SUPERUSER_BYPASS"]
            ))
        with self.assertRaisesRegex(ValueError, "actor mapping"):
            self.evaluate(lambda j: j["requirements"][0].update(
                actorProfiles=["U99"]
            ))

    def test_planned_requirement_must_not_claim_java_contract_or_grant(self):
        def mutate(j):
            planned = next(x for x in j["requirements"]
                           if x["delivery"] == "PLANNED")
            planned["requiredPermissionsAllOf"] = ["SALES_ORDER_CONFIRM"]
        with self.assertRaisesRegex(ValueError, "Planned capability"):
            self.evaluate(mutate)

    def test_missing_proof_cannot_be_backfilled_by_assertion(self):
        def mutate(j):
            x = next(x for x in j["requirements"]
                     if x["requirementId"] == "INV-02")
            x["proofStatus"] = "DECLARED_NOT_EXECUTED_ON_THIS_SHA"
        with self.assertRaisesRegex(ValueError, "Missing proof misrepresented"):
            self.evaluate(mutate)

    def test_only_existing_domain_permission_members_are_allowed(self):
        self.assertIn("TENANT_RBAC_MANAGE", self.permissions)
        self.assertIn("SALES_ORDER_CONFIRM", self.permissions)
        self.assertIn("INVENTORY_RESERVE", self.permissions)
        self.assertNotIn("SUPERUSER_BYPASS", self.permissions)


if __name__ == "__main__":
    unittest.main()
