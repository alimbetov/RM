import tempfile
import unittest
from pathlib import Path
from verify_rm_intelligence_targeted import verify

class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.required = [{"classname":"A","name":"a"}]
    def tearDown(self):
        self.tmp.cleanup()
    def put(self, body):
        (self.root/"TEST-A.xml").write_text("<testsuite>"+body+"</testsuite>")
    def test_exact(self):
        self.put('<testcase classname="A" name="a"/>')
        self.assertEqual(verify(self.root,self.required),1)
    def test_no_reports(self):
        with self.assertRaises(ValueError): verify(self.root,self.required)
    def test_wrong_class(self):
        self.put('<testcase classname="B" name="a"/>')
        with self.assertRaises(ValueError): verify(self.root,self.required)
    def test_duplicate(self):
        self.put('<testcase classname="A" name="a"/>'*2)
        with self.assertRaises(ValueError): verify(self.root,self.required)
    def test_failure_skip_error(self):
        for tag in ("failure","skipped","error"):
            self.put(f'<testcase classname="A" name="a"><{tag}/></testcase>')
            with self.assertRaises(ValueError): verify(self.root,self.required)
    def test_unexpected(self):
        self.put('<testcase classname="A" name="a"/><testcase classname="A" name="b"/>')
        with self.assertRaises(ValueError): verify(self.root,self.required)
    def test_empty_registry(self):
        with self.assertRaises(ValueError): verify(self.root,[])
