#!/usr/bin/env python3
"""Validate advisory inventory; never authorizes skipping or evidence reuse."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ".github/qualification/rm-ci-workflow-dependencies.json"

def blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()

def verify(root, registry):
    errors = []
    if registry.get("schema") != "RM-CI-R3.6A/v1" or registry.get("admission") != "ADVISORY_ONLY" or registry.get("executionPolicy") != "UNCHANGED" or registry.get("reuse") != "FORBIDDEN" or registry.get("fallback") != "EXECUTE_REQUIRED":
        errors.append("Inventory may not authorize selective execution")
    rows = registry["workflows"]
    actual = {p.relative_to(root).as_posix() for p in (root / ".github/workflows").iterdir() if p.is_file() and p.suffix in (".yml", ".yaml")}
    declared = {r["path"] for r in rows}
    if actual != declared or len(declared) != len(rows):
        errors.append("Workflow inventory mismatch or duplicates")
    for r in rows:
        if r["decision"] != "EXECUTE_REQUIRED" or r["closure"] != "UNQUALIFIED":
            errors.append("Unqualified closure: " + r["path"])
        if r["path"] in actual and blob((root / r["path"]).read_bytes()) != r["reviewedBlob"]:
            errors.append("Workflow drift: " + r["path"])
    exact = (root / ".github/workflows/rm-exact-sha-admission.yml").read_text()
    expected = set(re.findall(r"\{name:'([^']+)',artifacts:", exact))
    required = [r.get("workflowName") for r in rows if r["mandatoryRelease"]]
    if len(expected) != 12 or len(required) != len(set(required)) or set(required) != expected:
        errors.append("Mandatory release gate mismatch")
    for r in rows:
        if r["mandatoryRelease"] and r["path"] in actual:
            first = (root / r["path"]).read_text().splitlines()[0]
            if first != "name: " + r["workflowName"]:
                errors.append("Mandatory workflow identity mismatch: " + r["path"])
    return errors

if __name__ == "__main__":
    registry = json.loads((ROOT / REGISTRY).read_text())
    errors = verify(ROOT, registry)
    print(json.dumps({"inventoryGate": "FAIL" if errors else "PASS", "admission": "ADVISORY_ONLY", "workflowCount": len(registry["workflows"]), "errors": errors}, indent=2))
    raise SystemExit(bool(errors))
