#!/usr/bin/env python3
"""RM-QA-01B: fail-closed, exact-SHA CI evidence verifier (stdlib only)."""
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request
import zipfile
from io import BytesIO
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path("rm-exact-sha-manifest.json")
REQUIRED = {
    "RM Domain Security and PostgreSQL Qualification": "kms-postgres-surefire",
    "CRM R1.2 Populated PostgreSQL Upgrade Qualification": "crm-r12-populated-upgrade-surefire",
    "INVENTORY 0081 Populated PostgreSQL Upgrade Qualification": "inventory-0081-populated-postgresql",
    "SALES 08.4 Populated PostgreSQL Upgrade Qualification": "sales-084-populated-postgresql",
    "RustFS Real Infrastructure E2E": "rustfs-real-infra-e2e-surefire",
}
# Required suite names are checked against actual test XML, never workflow labels alone.
SUITES = {
    "INVENTORY 0081 Populated PostgreSQL Upgrade Qualification":
        ("InventoryMovementQualificationTest",),
    "SALES 08.4 Populated PostgreSQL Upgrade Qualification":
        ("SellerOfferQualificationTest",),
    "RustFS Real Infrastructure E2E":
        ("RustFsLiveInfrastructureE2ETest", "RustFsRealClamAvE2ETest"),
    "CRM R1.2 Populated PostgreSQL Upgrade Qualification":
        ("CrmR1aQualificationTest",),
}

def api(url, token):
    req = urllib.request.Request(
        url, headers={"Authorization": "Bearer " + token,
                      "Accept": "application/vnd.github+json",
                      "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.urlopen(req, timeout=60) as response:
        return response.read()

def verify():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    sha = os.environ["GITHUB_SHA"]
    repo = os.environ["GITHUB_REPOSITORY"]
    token = os.environ["GH_TOKEN"]
    problems = []
    if manifest.get("sha") != sha or manifest.get("repository") != repo:
        problems.append("SHA/repository mismatch")
    gates = manifest.get("required", [])
    if len(gates) != len(REQUIRED) or {g.get("name") for g in gates} != set(REQUIRED):
        problems.append("Missing/duplicate mandatory workflows")
    digest = hashlib.sha256()
    migrations = sorted((ROOT / "src/main/resources/db/changelog").rglob("*.sql"))
    if not migrations:
        problems.append("No Liquibase SQL migration files")
    for f in migrations:
        digest.update(f.relative_to(ROOT).as_posix().encode() + b"\\0")
        digest.update(hashlib.sha256(f.read_bytes()).digest())
    manifest["migrationFingerprint"] = {
        "algorithm": "sha256:path-and-content",
        "sha256": digest.hexdigest(), "fileCount": len(migrations)}
    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for gate in gates:
        name = gate.get("name")
        if gate.get("sha") != sha or gate.get("status") != "completed" or gate.get("conclusion") != "success":
            problems.append(f"{name}: not successful on same SHA")
            continue
        jobs = gate.get("jobs", [])
        if not jobs or any(j.get("conclusion") != "success" for j in jobs):
            problems.append(f"{name}: missing/failed jobs")
        artifact_name = REQUIRED.get(name)
        if not artifact_name:
            # Never admit missing required XML artifacts.
            problems.append(f"{name}: Surefire evidence artifact not configured")
            continue
        artifact = gate.get("artifact")
        if not artifact or artifact.get("name") != artifact_name:
            problems.append(f"{name}: required artifact unavailable")
            continue
        try:
            data = api(f"https://api.github.com/repos/{repo}/actions/artifacts/{artifact['id']}/zip", token)
            archive = zipfile.ZipFile(BytesIO(data))
            xmls = [n for n in archive.namelist() if n.endswith(".xml") and
                    Path(n).name.startswith("TEST-")]
            if not xmls:
                raise ValueError("No Surefire TEST-*.xml files")
            reported = set()
            suite_counts = {}
            skipped_cases = []
            counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
            for member in xmls:
                root = ET.fromstring(archive.read(member))
                if root.tag != "testsuite":
                    continue
                suite = root.get("name", "").split(".")[-1]
                reported.add(suite)
                suite_counts.setdefault(suite, {"tests": 0, "skipped": 0})
                suite_counts[suite]["tests"] += int(root.get("tests", "0"))
                suite_counts[suite]["skipped"] += int(root.get("skipped", "0"))
                for case in root.findall("testcase"):
                    skipped = case.find("skipped")
                    if skipped is not None:
                        skipped_cases.append({
                            "suite": suite, "test": case.get("name"),
                            "reason": skipped.get("message") or skipped.text or "UNSPECIFIED"
                        })
                for key in counts:
                    counts[key] += int(root.attrib.get(key, "0"))
            if counts["tests"] == 0 or counts["failures"] or counts["errors"]:
                problems.append(f"{name}: empty or failing Surefire report")
            # No required suite may be missing or fully skipped.
            for suite in SUITES.get(name, ()):
                if suite not in reported:
                    problems.append(f"{name}: required suite {suite} missing")
                elif suite_counts[suite]["tests"] <= suite_counts[suite]["skipped"]:
                    problems.append(f"{name}: required suite {suite} entirely skipped")
            gate["surefire"] = counts
            gate["suites"] = sorted(reported)
            gate["skippedTests"] = skipped_cases
            gate["artifactSha256"] = hashlib.sha256(data).hexdigest()
            for key in total:
                total[key] += counts[key]
        except (OSError, ValueError, KeyError, ET.ParseError, urllib.error.URLError, zipfile.BadZipFile) as exc:
            problems.append(f"{name}: cannot verify evidence: {exc}")
    manifest["testCounts"] = total
    manifest["evidenceErrors"] = problems
    manifest["qa01bGate"] = "PASS" if not problems else "FAIL"
    # A passing QA evidence gate is still NOT public security/production admission.
    manifest["admission"] = "HOLD"
    MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\\n", encoding="utf-8")
    print(json.dumps({"qa01bGate": manifest["qa01bGate"],
                      "tests": total, "errors": problems}, indent=2))
    return 0 if not problems else 1

if __name__ == "__main__":
    try:
        sys.exit(verify())
    except Exception as exc:
        print(f"RM-QA-01B FAIL CLOSED: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
