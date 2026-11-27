import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from verify_rm_ci_selective_allowlist import (ROOT, load_contract, audit, inspect_diff,
    tracked, dependency_fingerprint, review_matches, REGISTRY)

class SelectiveAllowlistTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "CI")
        self.git("config", "user.email", "ci@example.invalid")
        self.candidate = "docs/reviews/note.md"
        self.write(self.candidate, "historical note")
        self.write("src/Main.java", "class Main {}")
        self.base = self.commit()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.PIPE).decode().strip()

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def test_contract_and_actual_dependency_audit(self):
        row = load_contract(ROOT)
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        report = audit(ROOT, head, row["path"])
        self.assertFalse(report["executionAllowed"])
        self.assertEqual("FULL_REQUIRED", report["decision"])
        self.assertEqual("SHADOW_PLANNING_ONLY", row["scope"])
        self.assertEqual(report["reviewFingerprintMatches"] and not report["documentationReferences"]
                         and not any(row["path"] in line or Path(row["path"]).name in line
                                     for line in report["machineReferences"]),
                         report["qualifiedForShadowPlanning"])
        print("RM-CI-R3.6C.1 AUDIT " + json.dumps(report, ensure_ascii=True), flush=True)

    def test_only_existing_regular_document_candidate(self):
        self.write(self.candidate, "corrected historical note")
        self.assertTrue(inspect_diff(self.root, self.base, self.commit(), self.candidate))

    def test_mixed_change_denied(self):
        self.write(self.candidate, "corrected")
        self.write("src/Main.java", "class Main { int x; }")
        self.assertFalse(inspect_diff(self.root, self.base, self.commit(), self.candidate))

    def test_delete_denied(self):
        (self.root / self.candidate).unlink()
        self.assertFalse(inspect_diff(self.root, self.base, self.commit(), self.candidate))

    def test_rename_denied(self):
        (self.root / self.candidate).rename(self.root / "docs/reviews/new.md")
        self.assertFalse(inspect_diff(self.root, self.base, self.commit(), self.candidate))

    def test_mode_change_denied(self):
        self.git("update-index", "--chmod=+x", self.candidate)
        self.git("commit", "-qm", "mode")
        self.assertFalse(inspect_diff(self.root, self.base, self.git("rev-parse", "HEAD"), self.candidate))

    def test_invalid_range_denied(self):
        with self.assertRaises(ValueError):
            inspect_diff(self.root, self.base, self.base, self.candidate)

    def test_non_ancestor_denied(self):
        self.write(self.candidate, "first")
        other = self.commit()
        self.git("checkout", "--detach", self.base)
        self.write(self.candidate, "second")
        with self.assertRaises(subprocess.CalledProcessError):
            inspect_diff(self.root, other, self.commit(), self.candidate)

    def test_review_fingerprint_changes_on_dependency_drift(self):
        initial = tracked(self.root, self.base)
        count, digest = dependency_fingerprint(initial, self.candidate)
        row = {"path": self.candidate, "reviewedDependencyCount": count,
               "reviewedDependencyFingerprint": digest}
        self.write(self.candidate, "changed document")
        doc_head = self.commit()
        self.assertTrue(review_matches(tracked(self.root, doc_head), row))
        self.write("src/Main.java", "class Main { int y; }")
        self.assertFalse(review_matches(tracked(self.root, self.commit()), row))

    def test_control_change_never_eligible(self):
        self.write(self.candidate, "changed note")
        self.write(REGISTRY, "{}")
        self.assertFalse(inspect_diff(self.root, self.base, self.commit(), self.candidate))

if __name__ == "__main__":
    unittest.main()
