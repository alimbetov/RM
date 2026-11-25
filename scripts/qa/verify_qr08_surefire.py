#!/usr/bin/env python3
"""QR-08/R0 exact-SHA Surefire evidence gate (Python stdlib only)."""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("focused", "full", "populated"), required=True)
    parser.add_argument("--output", required=True)
    options = parser.parse_args()
    sha = os.environ.get("GITHUB_SHA", "")
    local_sha = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], text=True
    ).strip()
    if len(sha) != 40 or sha != local_sha:
        raise SystemExit(f"QR08_SHA_MISMATCH: expected {sha}, checkout {local_sha}")
    paths = sorted(pathlib.Path("target/surefire-reports").glob("TEST-*.xml"))
    if not paths:
        raise SystemExit("QR08_MISSING_SUREFIRE_XML")
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = {}
    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            raise SystemExit(f"QR08_INVALID_SUREFIRE_XML {path}: {exc}") from exc
        if root.tag != "testsuite":
            raise SystemExit(f"QR08_UNEXPECTED_ROOT {path}")
        name = root.get("name") or path.name
        testcases = root.findall("testcase")
        number = len(testcases)
        failures = sum(1 for tc in testcases if tc.find("failure") is not None)
        errors = sum(1 for tc in testcases if tc.find("error") is not None)
        skipped = sum(1 for tc in testcases if tc.find("skipped") is not None)
        counts = dict(tests=number, failures=failures, errors=errors, skipped=skipped)
        for key, value in counts.items():
            totals[key] += value
        suites[name] = counts
    required = {
        "kz.rm.sales.PackagingServiceCatalogQualificationTest": 4,
        "kz.rm.sales.OrderPackagingQualificationTest": 24,
    }
    for suffix, minimum in required.items():
        matches = [v for name, v in suites.items() if name.endswith(suffix)]
        if len(matches) != 1 or matches[0]["tests"] < minimum:
            raise SystemExit(f"QR08_REQUIRED_SUITE_MISSING_OR_INCOMPLETE {suffix}: {matches}")
        if any(matches[0][x] for x in ("failures", "errors", "skipped")):
            raise SystemExit(f"QR08_REQUIRED_SUITE_NOT_GREEN {suffix}: {matches[0]}")
    if totals["failures"] or totals["errors"]:
        raise SystemExit(f"QR08_SUREFIRE_FAILURE {totals}")
    if options.mode == "focused" and totals["skipped"]:
        raise SystemExit(f"QR08_FOCUSED_SKIPS {totals}")
    if totals["tests"] < 28:
        raise SystemExit(f"QR08_TOO_FEW_TESTS {totals}")
    evidence = {
        "gate": "QR08-R0D",
        "mode": options.mode,
        "sha": sha,
        "total": totals,
        "suites": suites,
        "required_suite_minima": required,
        "status": "PASS",
    }
    dest = pathlib.Path(options.output)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("QR08 exact-SHA Surefire gate PASS:", json.dumps(totals))


if __name__ == "__main__":
    main()
