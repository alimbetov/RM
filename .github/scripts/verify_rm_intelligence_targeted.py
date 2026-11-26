#!/usr/bin/env python3
"""Strict targeted evidence; never grants full admission."""
import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import Counter

def verify(root, required):
    expected = [(r["classname"], r["name"]) for r in required]
    if not expected or len(expected) != len(set(expected)):
        raise ValueError("INVALID_REQUIRED_CASE_REGISTRY")
    paths = sorted(root.glob("TEST-*.xml"))
    if not paths:
        raise ValueError("MISSING_SUREFIRE_XML")
    counts = Counter()
    errors = []
    for path in paths:
        for case in ET.parse(path).getroot().iter("testcase"):
            key = (case.get("classname"), case.get("name"))
            counts[key] += 1
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                errors.append(f"FAILED_OR_SKIPPED: {key}")
    for key in expected:
        if counts[key] != 1:
            errors.append(f"REQUIRED_CASE_COUNT: {key}: {counts[key]}")
    for key in counts:
        if key not in expected:
            errors.append(f"UNEXPECTED_CASE: {key}")
    if errors:
        raise ValueError("; ".join(errors))
    return sum(counts.values())

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--reports", type=Path, required=True)
    p.add_argument("--registry", type=Path, required=True)
    p.add_argument("--sha", required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.sha):
        raise ValueError("INVALID_SHA")
    data = json.loads(args.registry.read_text())
    count = verify(args.reports, data["requiredCases"])
    args.output.write_text(json.dumps({"sha":args.sha,"executed":count,"junit":"PASS","admission":"DIAGNOSTIC_ONLY","securityGate":"SEPARATE_WORKFLOW_STEP"}, indent=2)+"\n")
if __name__ == "__main__":
    main()
