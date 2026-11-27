import tempfile
import json
import os
import subprocess
import textwrap
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

class IsolatedScopeTest(unittest.TestCase):
    """Exercise the actual inline workflow guard against real Git histories."""
    source = "src/main/java/kz/rm/intelligence/Example.java"
    iam = "src/main/java/kz/rm/iam/Example.java"
    test = "src/test/java/kz/rm/intelligence/AnalyticalJobFoundationQualificationTest.java"
    registry = ".github/qualification/rm-intelligence-targeted.json"
    workflow = ".github/workflows/rm-intelligence-targeted-pg.yml"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "CI")
        self.git("config", "user.email", "ci@example.invalid")
        for path in (self.source, self.iam, self.test, self.workflow, "pom.xml"):
            self.write(path, "initial")
        self.case = {"classname": "kz.rm.intelligence.AnalyticalJobFoundationQualificationTest",
                     "name": "case1"}
        self.data = {"admission": "DIAGNOSTIC_ONLY", "requiredCases": [self.case]}
        self.write(self.registry, json.dumps(self.data))
        self.base = self.commit()
        self.git("update-ref", "refs/remotes/origin/base_dev", self.base)
        root = Path(__file__).resolve().parents[2]
        source = (root / self.workflow).read_text(encoding="utf-8")
        marker = "      - name: Validate isolated Intelligence development scope"
        step = source.split(marker, 1)[1].split("      - uses:", 1)[0]
        self.guard = textwrap.dedent(step.split("python3 - <<'PY'\n", 1)[1].rsplit("\n          PY", 1)[0])

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root,
                                       stderr=subprocess.PIPE, text=True).strip()

    def write(self, path, content):
        f = self.root / path
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(content, encoding="utf-8")

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def check(self, accepted, reason=None):
        result = subprocess.run(["python3", "-c", self.guard], cwd=self.root,
                                env={**os.environ, "GITHUB_SHA": self.git("rev-parse", "HEAD")},
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(accepted, result.returncode == 0, result.stdout + result.stderr)
        if accepted:
            report = json.loads(result.stdout)
            self.assertEqual("INTELLIGENCE_DIAGNOSTIC_ONLY", report["scope"])
            self.assertEqual("NOT_GRANTED", report["developmentAdmission"])
            self.assertEqual("DENIED", report["releaseAdmission"])
        elif reason:
            self.assertIn(reason, result.stderr)

    def test_source_and_additive_registry(self):
        self.write(self.source, "changed")
        self.commit()
        self.write(self.test, "changed")
        self.write(self.registry, json.dumps({**self.data, "requiredCases": [
            self.case, {**self.case, "name": "case2"}]}))
        self.commit()
        self.check(True)

    def test_transient_iam_revert_denied(self):
        self.write(self.iam, "changed")
        self.commit()
        self.write(self.iam, "initial")
        self.commit()
        self.write(self.source, "changed")
        self.commit()
        self.check(False, "OUT_OF_SCOPE")

    def test_transient_registry_removal_denied(self):
        self.write(self.registry, json.dumps({**self.data, "requiredCases": []}))
        self.commit()
        self.write(self.registry, json.dumps(self.data))
        self.commit()
        self.write(self.source, "changed")
        self.commit()
        self.check(False, "CASE_REMOVAL")

    def test_merge_denied(self):
        self.git("checkout", "-b", "fixture-side")
        self.write(self.source, "changed")
        self.commit()
        self.git("checkout", "--detach", self.base)
        self.git("merge", "--no-ff", "-m", "fixture merge", "fixture-side")
        self.check(False, "NON_LINEAR_HISTORY")

    def test_workflow_change_denied(self):
        self.write(self.workflow, "changed")
        self.commit()
        self.check(False, "OUT_OF_SCOPE")

    def test_no_diff_denied(self):
        self.check(False, "UNSUPPORTED_HISTORY_LENGTH")
