import json
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
from verify_rm_ci_selective_allowlist import (ROOT, load_contract, audit, inspect_diff,
    tracked, dependency_fingerprint, review_matches, REGISTRY, build_plan)

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

    def test_transient_dependency_change_and_revert_denied(self):
        self.write("src/Main.java", "class Main { int transientValue; }")
        self.commit()
        self.write("src/Main.java", "class Main {}")
        self.commit()
        self.write(self.candidate, "changed note")
        self.assertFalse(inspect_diff(self.root, self.base, self.commit(), self.candidate))

    def test_linear_document_only_history_accepted(self):
        self.write(self.candidate, "first correction")
        self.commit()
        self.write(self.candidate, "second correction")
        self.assertTrue(inspect_diff(self.root, self.base, self.commit(), self.candidate))

    def test_merge_history_denied(self):
        self.git("checkout", "-b", "fixture-side")
        self.write(self.candidate, "side correction")
        self.commit()
        self.git("checkout", "--detach", self.base)
        self.git("merge", "--no-ff", "-m", "fixture merge", "fixture-side")
        self.assertFalse(inspect_diff(self.root, self.base, self.git("rev-parse", "HEAD"), self.candidate))

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

    def plan_fixture(self):
        count, digest = dependency_fingerprint(tracked(self.root, self.base), self.candidate)
        row = {"path": self.candidate, "reviewedSha": self.base,
               "reviewedDependencyCount": count, "reviewedDependencyFingerprint": digest}
        gates = [f"Gate {i:02d}" for i in range(12)]
        self.write(self.candidate, "changed note")
        head = self.commit()
        impact = {"schema": "RM-CI-R3-SHADOW/v1", "before": self.base, "head": head,
                  "shadowOnly": True, "admission": "UNCHANGED_FULL_REQUIRED",
                  "detail": "VERIFIED_ANCESTOR_DIFF", "paths": [self.candidate],
                  "requiredExecutedGates": gates}
        return head, row, gates, impact

    def plan(self, head, row, gates, impact, event="push", ref="refs/heads/base_dev", release=False):
        return build_plan(self.root, self.base, head, row, gates, impact, event, ref, release)

    def test_plan_is_deterministic_and_never_authorizes_execution(self):
        head, row, gates, impact = self.plan_fixture()
        observation = {"sha": head, "qualifiedForShadowPlanning": True, "executionAllowed": False}
        with patch("verify_rm_ci_selective_allowlist.audit", return_value=observation):
            a = self.plan(head, row, gates, impact)
            b = self.plan(head, row, list(reversed(gates)), impact)
        self.assertEqual(a, b)
        self.assertEqual("FAST_DOC_SHADOW", a["planMode"])
        self.assertEqual(gates, a["deferredCandidates"])
        self.assertEqual([], a["plannedGates"])
        self.assertEqual(gates, a["requiredExecutedGates"])
        self.assertEqual("FULL_REQUIRED", a["actualDecision"])
        self.assertFalse(a["executionAllowed"])
        self.assertEqual(64, len(a["planDigest"]))

    def test_plan_release_and_non_push_are_full(self):
        head, row, gates, impact = self.plan_fixture()
        for event, ref, release in (("push", "refs/heads/base_dev", True),
                                    ("workflow_dispatch", "refs/heads/base_dev", False),
                                    ("push", "refs/heads/main", False)):
            with self.subTest(event=event, ref=ref, release=release):
                a = self.plan(head, row, gates, impact, event, ref, release)
                self.assertEqual("FULL_REQUIRED", a["planMode"])
                self.assertEqual(gates, a["plannedGates"])
                self.assertEqual([], a["deferredCandidates"])

    def test_plan_legacy_mismatch_is_full(self):
        head, row, gates, impact = self.plan_fixture()
        impact["head"] = "0"*40
        a = self.plan(head, row, gates, impact)
        self.assertEqual("LEGACY_IMPACT_MISMATCH", a["reason"])
        self.assertEqual(gates, a["plannedGates"])

    def test_plan_dependency_drift_and_audit_denial_are_full(self):
        head, row, gates, impact = self.plan_fixture()
        with patch("verify_rm_ci_selective_allowlist.audit", return_value={"sha":head,
                   "qualifiedForShadowPlanning": False, "executionAllowed": False}):
            self.assertEqual("ALLOWLIST_AUDIT_DENIED", self.plan(head, row, gates, impact)["reason"])
        row["reviewedDependencyFingerprint"] = "0"*64
        self.assertEqual("REVIEW_DEPENDENCY_DRIFT", self.plan(head, row, gates, impact)["reason"])

    def test_plan_invalid_range_and_mixed_change_are_full(self):
        head, row, gates, impact = self.plan_fixture()
        self.assertEqual("FULL_REQUIRED", self.plan("0"*40, row, gates, impact)["planMode"])
        self.write("src/Main.java", "class Main { int z; }")
        changed = self.commit()
        self.assertEqual("NOT_EXACT_SINGLE_DOCUMENT_CHANGE", self.plan(changed, row, gates, impact)["reason"])

    def test_overlay_accepts_exact_planning_blob_only(self):
        entries = {self.candidate: ("100644", "a"*40), "workflow": ("100644", "b"*40)}
        count, digest = dependency_fingerprint(entries, self.candidate)
        row = {"path": self.candidate, "reviewedDependencyCount": count,
               "reviewedDependencyFingerprint": digest, "planningOverlay": {"changes": [
                   {"path":"workflow", "reviewedBlob":"b"*40, "planningBlob":"c"*40}]}}
        entries["workflow"] = ("100644", "c"*40)
        self.assertTrue(review_matches(entries, row))
        entries["workflow"] = ("100644", "d"*40)
        self.assertFalse(review_matches(entries, row))
        entries["workflow"] = ("100755", "c"*40)
        self.assertFalse(review_matches(entries, row))

if __name__ == "__main__":
    unittest.main()
