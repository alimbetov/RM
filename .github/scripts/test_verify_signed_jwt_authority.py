"""Fail-closed self-tests for E.1A, with generated Surefire fixtures."""
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from verify_signed_jwt_authority import verify, REQUIRED, PACKAGE

SHA = "a" * 40
SUITE = PACKAGE + "SignedJwtTestAuthorityTest"


class AuthorityEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.reports = Path(self.temp.name)
        for short, methods in REQUIRED.items():
            suite = PACKAGE + short
            root = ET.Element("testsuite", name=suite, tests=str(len(methods)),
                              failures="0", errors="0", skipped="0")
            for method in sorted(methods):
                ET.SubElement(root, "testcase", name=method, classname=suite)
            ET.ElementTree(root).write(self.reports / ("TEST-" + suite + ".xml"))
        self.path = self.reports / ("TEST-" + SUITE + ".xml")

    def mutate(self, mutation):
        root = ET.parse(self.path).getroot()
        mutation(root)
        ET.ElementTree(root).write(self.path)

    def test_all_named_methods_pass(self):
        report = verify(self.reports, SHA, SHA)
        self.assertEqual(report["total"], 12)
        self.assertEqual(report["authorityCases"], 4)
        self.assertEqual(report["productionAdmission"], "HOLD")

    def test_sha_mismatch(self):
        with self.assertRaisesRegex(ValueError, "SHA_MISMATCH"):
            verify(self.reports, SHA, "b" * 40)

    def test_missing_suite(self):
        self.path.unlink()
        with self.assertRaisesRegex(ValueError, "SUITE_SET_MISMATCH"):
            verify(self.reports, SHA, SHA)

    def test_duplicate_method(self):
        self.mutate(lambda root: root[-1].set("name", root[0].get("name")))
        with self.assertRaisesRegex(ValueError, "METHOD_SET_MISMATCH"):
            verify(self.reports, SHA, SHA)

    def test_wrong_case_class(self):
        self.mutate(lambda root: root[0].set("classname", "unrelated.Test"))
        with self.assertRaisesRegex(ValueError, "CASE_CLASS_MISMATCH"):
            verify(self.reports, SHA, SHA)

    def test_skipped_case_even_with_forged_green_aggregate(self):
        self.mutate(lambda root: ET.SubElement(root[0], "skipped"))
        with self.assertRaisesRegex(ValueError, "CASE_NOT_CLEAN"):
            verify(self.reports, SHA, SHA)

    def test_flaky_case_not_accepted(self):
        self.mutate(lambda root: ET.SubElement(root[0], "flakyFailure"))
        with self.assertRaisesRegex(ValueError, "CASE_NOT_CLEAN"):
            verify(self.reports, SHA, SHA)

    def test_nonzero_aggregate(self):
        self.mutate(lambda root: root.set("errors", "1"))
        with self.assertRaisesRegex(ValueError, "SUITE_NOT_GREEN"):
            verify(self.reports, SHA, SHA)


if __name__ == "__main__":
    unittest.main()
