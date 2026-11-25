#!/usr/bin/env python3
"""IE-00B source inventory integrity check; never grants runtime admission."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = "docs/reviews/RM-IE-00B-R2-SOURCE-INVENTORY.json"

def git_blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()

def verify(root, manifest):
    errors = []
    for key, count in (("sourceGroups", 22), ("metrics", 8),
                       ("scenarioIds", 40), ("acceptanceFamilyIds", 88)):
        rows = manifest[key]
        ids = [row["id"] if isinstance(row, dict) else row for row in rows]
        if len(ids) != count or len(set(ids)) != count:
            errors.append(f"{key}: invalid inventory cardinality")
    if manifest["admission"] != "HOLD" or manifest["runtimeQualification"] != "NOT_EXECUTED":
        errors.append("Source inventory cannot grant runtime admission")
    rows = manifest["sourceInventory"]
    if not rows or len({row["path"] for row in rows}) != len(rows):
        errors.append("Missing or duplicate source inventory paths")
    for row in rows:
        path = (root / row["path"]).resolve()
        if not path.is_relative_to(root.resolve()):
            errors.append("Invalid source path")
            continue
        actual = git_blob(path.read_bytes()) if path.is_file() else None
        if actual != row["baselineBlob"]:
            errors.append(f"Source drift: {row['path']}")
        expected = ("MISSING" if row["baselineBlob"] is None else
                    "UNCHANGED" if row["baselineBlob"] == row["designBlob"] else "DRIFT")
        if row["status"] != expected:
            errors.append(f"Invalid source status: {row['path']}")
    return errors

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    manifest = json.loads((args.root / MANIFEST).read_text(encoding="utf-8"))
    errors = verify(args.root, manifest)
    print(json.dumps({"sourceInventoryGate": "FAIL" if errors else "PASS",
                      "runtimeAdmission": "HOLD", "errors": errors}, indent=2))
    return int(bool(errors))

if __name__ == "__main__":
    raise SystemExit(main())
