"""Focused fail-closed checks for RET04-D evidence parser."""
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from verify_ret04_evidence import verify, snapshot, METHODS, TABLES, SUITE

SHA = "a"*40

class Ret04EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        (self.dir / "exact-sha.txt").write_text(SHA)
        self.xml = self.dir / "suite.xml"
        self.root = ET.Element("testsuite", name=SUITE, tests="24", errors="0", failures="0", skipped="0")
        for name in METHODS:
            ET.SubElement(self.root, "testcase", name=name, classname=SUITE)
        ET.ElementTree(self.root).write(self.xml)

    def test_wrong_sha(self):
        with self.assertRaisesRegex(ValueError, "SHA mismatch"):
            verify(self.xml, self.dir, "b"*40)

    def test_missing_method(self):
        self.root.remove(self.root[-1])
        self.root.set("tests", "23")
        ET.ElementTree(self.root).write(self.xml)
        with self.assertRaisesRegex(ValueError, "missing, duplicate"):
            verify(self.xml, self.dir, SHA)

    def test_duplicate_method(self):
        self.root[-1].set("name", METHODS[0])
        ET.ElementTree(self.root).write(self.xml)
        with self.assertRaisesRegex(ValueError, "missing, duplicate"):
            verify(self.xml, self.dir, SHA)

    def test_skipped_suite(self):
        self.root.set("skipped", "1")
        ET.ElementTree(self.root).write(self.xml)
        with self.assertRaisesRegex(ValueError, "suite not green"):
            verify(self.xml, self.dir, SHA)

    def test_missing_snapshots(self):
        (self.dir / "sql").mkdir()
        with self.assertRaisesRegex(ValueError, "snapshot method set"):
            verify(self.xml, self.dir, SHA)

    def test_snapshot_provenance_and_counts(self):
        path = self.dir / "sample.tsv"
        lines = ["sha\t"+SHA, "method\t"+METHODS[0], "postgres\t170000",
                 "tenant\t00000000-0000-0000-0000-000000000001"]
        lines += ["table\t"+t+"\t0\t"+"0"*64 for t in sorted(TABLES)]
        path.write_text("\n".join(lines))
        meta, tables, rows = snapshot(path, SHA, METHODS[0])
        self.assertEqual(set(tables), TABLES)
        with self.assertRaisesRegex(ValueError, "provenance"):
            snapshot(path, "b"*40, METHODS[0])
        path.write_text("\n".join(lines[:-1]))
        with self.assertRaisesRegex(ValueError, "missing SQL projection"):
            snapshot(path, SHA, METHODS[0])

if __name__ == "__main__":
    unittest.main()
