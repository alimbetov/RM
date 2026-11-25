"""Archive boundary and manifest recomputation tests; no production evidence is minted."""
import io
from contextlib import contextmanager
import json
import unittest
from unittest.mock import patch
import zipfile
import xml.etree.ElementTree as ET
import hashlib
import verify_ret04_global_artifact as g

SHA="a"*40

class GlobalRet04ArtifactTest(unittest.TestCase):
    def setUp(self):
        root=ET.Element("testsuite",name=g.SUITE,tests="81",errors="0",failures="0",skipped="0")
        for name in sorted(g.CORE)+["other"+str(i) for i in range(25)]:
            ET.SubElement(root,"testcase",name=name,classname=g.SUITE)
        self.raw=ET.tostring(root)
        self.files={g.XML:self.raw,g.TARGET+"exact-sha.txt":SHA.encode()}
        self.reports=[]
        for (name,_,count,schema),methods in zip(g.MANIFESTS,(g.d.METHODS,g.a.METHODS,g.b.METHODS)):
            report={"schema":schema,"sha":SHA,"status":"PASS","cases":count,
                    "surefireSha256":hashlib.sha256(self.raw).hexdigest(),
                    "evidence":[{"method":m} for m in methods]}
            self.files[g.TARGET+name]=json.dumps(report).encode()
            self.reports.append((name,report,count,schema))
        self.lineage={"schema":g.c.SCHEMA,"sha":SHA,"status":"PASS","cases":56,
                      "surefireSha256":hashlib.sha256(self.raw).hexdigest(),
                      "evidence":[{"method":m} for m in sorted(g.CORE)]}
        self.files[g.TARGET+"ret04-e6c-manifest.json"]=json.dumps(self.lineage).encode()
    def archive(self,files=None):
        data=io.BytesIO()
        with zipfile.ZipFile(data,"w") as z:
            for p,v in (self.files if files is None else files).items():
                z.writestr(p,v)
        return zipfile.ZipFile(data)
    @contextmanager
    def mock_verifiers(self):
        # Test the archive composition boundary only. Real D/A/B SQL
        # verifiers have their own tests and execute against real CI artifacts.
        entries=tuple((n,lambda *args, report=r:report,c,s) for n,r,c,s in self.reports)
        with patch.object(g,"MANIFESTS",entries),patch.object(g.c,"verify",return_value=self.lineage):
            yield
    def test_three_independently_recomputed_manifests(self):
        with self.mock_verifiers(),self.archive() as z:
            result=g.verify_target_archive(z,SHA)
        self.assertEqual(result["cases"],56)
        self.assertEqual(len(result["manifests"]),3)
        self.assertEqual(result["checkoutLineage"]["cases"],56)
    def test_checkout_lineage_tamper(self):
        self.files[g.TARGET+"ret04-e6c-manifest.json"]=b'{"status":"PASS"}'
        with self.mock_verifiers(),self.archive() as z,self.assertRaisesRegex(ValueError,"lineage manifest differs"):
            g.verify_target_archive(z,SHA)
    def test_checkout_lineage_missing(self):
        del self.files[g.TARGET+"ret04-e6c-manifest.json"]
        with self.mock_verifiers(),self.archive() as z,self.assertRaisesRegex(ValueError,"missing RET04 checkout lineage"):
            g.verify_target_archive(z,SHA)
    def test_wrong_sha(self):
        with self.archive() as z,self.assertRaisesRegex(ValueError,"SHA mismatch"):
            g.verify_target_archive(z,"b"*40)
    def test_manifest_tamper(self):
        self.files[g.TARGET+g.MANIFESTS[0][0]]=b'{"status":"PASS"}'
        with self.mock_verifiers(),self.archive() as z,self.assertRaisesRegex(ValueError,"differs"):
            g.verify_target_archive(z,SHA)
    def test_missing_manifest(self):
        del self.files[g.TARGET+g.MANIFESTS[-1][0]]
        with self.mock_verifiers(),self.archive() as z,self.assertRaisesRegex(ValueError,"missing RET04 manifest"):
            g.verify_target_archive(z,SHA)
    def test_real_verifier_rejects_missing_snapshots_even_with_claimed_pass(self):
        with self.archive() as z,self.assertRaisesRegex(ValueError,"snapshot method set"):
            g.verify_target_archive(z,SHA)
    def test_path_traversal(self):
        self.files[g.TARGET+"../../escaped"]=b"x"
        with self.archive() as z,self.assertRaisesRegex(ValueError,"unsafe ZIP path"):
            g.verify_target_archive(z,SHA)
    def test_duplicate_zip_entry(self):
        import warnings
        data=io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore",UserWarning)
            with zipfile.ZipFile(data,"w") as z:
                z.writestr(g.XML,self.raw)
                z.writestr(g.XML,self.raw)
        with zipfile.ZipFile(data) as z,self.assertRaisesRegex(ValueError,"duplicate ZIP"):
            g.verify_target_archive(z,SHA)
    def test_full_regression_sha_and_named_cases(self):
        files={g.XML:self.raw,g.FULL+"exact-sha.txt":SHA.encode()}
        with self.archive(files) as z:
            self.assertEqual(g.verify_full_archive(z,SHA)["coreCases"],56)
        del files[g.FULL+"exact-sha.txt"]
        with self.archive(files) as z,self.assertRaisesRegex(ValueError,"SHA mismatch"):
            g.verify_full_archive(z,SHA)
    def test_skipped_core_rejected(self):
        root=ET.fromstring(self.raw)
        root.set("skipped","1")
        ET.SubElement(root[0],"skipped")
        with self.assertRaisesRegex(ValueError,"nonclean core"):
            g.verify_suite(ET.tostring(root),False)

if __name__=="__main__":
    unittest.main()
