#!/usr/bin/env python3
"""Audit existing JSON without attesting official provenance."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_REGIONS = 20

def audit(raw):
    rows = json.loads(raw)
    if not isinstance(rows, list) or not rows:
        raise ValueError("KATO_EMPTY_OR_INVALID")
    ids, codes = {}, set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("KATO_ROW")
        i, code, level, parent = (row.get(k) for k in ("Id", "Code", "Level", "Parent"))
        if type(i) is not int or i <= 0 or type(code) is not int or not 100000000 <= code <= 999999999:
            raise ValueError("KATO_ID_OR_CODE")
        if type(level) is not int or not 2 <= level <= 32:
            raise ValueError("KATO_LEVEL")
        if parent not in ("", None) and (type(parent) is not int or parent <= 0):
            raise ValueError("KATO_PARENT")
        if type(row.get("IsMarkedToDelete")) is not bool or type(row.get("AreaType")) is not int:
            raise ValueError("KATO_TYPE")
        if any(not isinstance(row.get(k), str) or not row[k].strip() for k in ("NameKaz", "NameRus")):
            raise ValueError("KATO_LABEL")
        if i in ids or code in codes:
            raise ValueError("KATO_DUPLICATE")
        ids[i] = row
        codes.add(code)
    active = [r for r in rows if not r["IsMarkedToDelete"]]
    regions = sorted(r["Code"] for r in active if r["Level"] == 2)
    missing = sum(r["Parent"] not in ("", None) and r["Parent"] not in ids for r in rows)
    unresolved = []
    for r in active:
        n, seen = r, set()
        while n is not None:
            i = n["Id"]
            if i in seen or n["IsMarkedToDelete"]:
                break
            seen.add(i)
            if n["Level"] == 2:
                if n["Parent"] in ("", None):
                    n = None
                break
            parent = ids.get(n["Parent"])
            if parent is None or parent["Level"] >= n["Level"]:
                break
            n = parent
        if n is not None:
            unresolved.append(r["Code"])
    canonical = json.dumps(sorted(rows, key=lambda r: r["Code"]), ensure_ascii=False,
                           sort_keys=True, separators=(",", ":")).encode("utf-8")
    complete = len(regions) == EXPECTED_REGIONS and not unresolved
    return {"rows": len(rows), "activeRows": len(active), "deletedRows": len(rows)-len(active),
            "activeLevel2": regions, "expectedLevel2Count": EXPECTED_REGIONS,
            "missingParentLinksAllRows": missing, "unresolvedActiveCodes": sorted(unresolved),
            "rawSha256": hashlib.sha256(raw).hexdigest(),
            "normalizedSha256": hashlib.sha256(canonical).hexdigest(),
            "directoryCoverage": "STRUCTURALLY_COMPLETE" if complete else "HOLD",
            "officialProvenance": "NOT_ATTESTED", "activationAdmission": "HOLD"}

def audit_admin(raw, manifest):
    report = audit(raw)
    rows = json.loads(raw)
    by_code = {r["Code"]: r for r in rows}
    by_id = {r["Id"]: r for r in rows}
    codes = manifest["selectedCodes"]
    regions = manifest["regionCodes"]
    if (manifest["schema"] != "RM-KATO-ADMIN-RELEASE/v1"
            or manifest["selectedCount"] != 375 or len(codes) != 375
            or len(set(codes)) != 375 or len(set(regions)) != EXPECTED_REGIONS
            or sorted(regions) != report["activeLevel2"]):
        raise ValueError("KATO_ADMIN_SCOPE")
    scope = set(codes)
    for code in codes:
        row = by_code.get(code)
        if row is None or row["IsMarkedToDelete"] or code in report["unresolvedActiveCodes"]:
            raise ValueError("KATO_ADMIN_MISSING_OR_UNRESOLVED")
        if row["Level"] > 2 and by_id[row["Parent"]]["Code"] not in scope:
            raise ValueError("KATO_ADMIN_PARENT_OUTSIDE_SCOPE")
    if {r["Code"] for r in rows if r["Id"] >= 1_000_000_000} != scope - set(manifest["legacySelectedCodes"]):
        raise ValueError("KATO_ADMIN_NEW_SCOPE_DRIFT")
    types = manifest.get("territoryTypes", {})
    expected = {"OBLAST":17,"REPUBLICAN_CITY":3,"DISTRICT":170,"CITY":87,
                "CITY_DISTRICT":25,"CITY_ADMINISTRATION":71,"DISTRICT_ADMINISTRATION":2}
    if set(types) != set(expected) or any(len(types[k]) != n for k,n in expected.items()):
        raise ValueError("KATO_SEMANTIC_TYPES")
    flattened = [int(code) for codes in types.values() for code in codes]
    if len(flattened) != len(set(flattened)) or set(flattened) != scope:
        raise ValueError("KATO_SEMANTIC_SCOPE")
    if {int(c) for c in types["OBLAST"] + types["REPUBLICAN_CITY"]} != set(regions):
        raise ValueError("KATO_SEMANTIC_REGION")
    for code in scope:
        row = by_code[code]
        parent = by_id.get(row["Parent"])
        if parent is not None and str(parent["Code"])[:2] != str(code)[:2]:
            raise ValueError("KATO_SEMANTIC_FOREIGN_REGION")
    report["semanticTypes"] = expected
    report["administrativeCoverage"] = "STRUCTURALLY_COMPLETE"
    report["administrativeRows"] = len(codes)
    report["releaseDate"] = manifest["releaseDate"]
    report["sourceFileSha256"] = manifest["sourceSha256"]
    return report

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, default=ROOT / "src/main/resources/kato-data.json")
    parser.add_argument("--expected-sha256")
    parser.add_argument("--admin-manifest", type=Path)
    parser.add_argument("--require-admin-complete", action="store_true")
    parser.add_argument("--verify-semantic-migration", type=Path)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    try:
        raw = args.file.read_bytes()
        result = audit_admin(raw, json.loads(args.admin_manifest.read_text(encoding="utf-8"))) if args.admin_manifest else audit(raw)
        if args.verify_semantic_migration:
            if not args.admin_manifest:
                raise ValueError("KATO_SEMANTIC_MANIFEST_REQUIRED")
            import re
            manifest = json.loads(args.admin_manifest.read_text(encoding="utf-8"))
            sql = args.verify_semantic_migration.read_text(encoding="utf-8")
            block = sql.split("--changeset rm:0201-kato-semantic-administrative-types", 1)[1].split("--changeset",1)[0]
            parsed = {}
            for kind, values in re.findall(r"UPDATE public\.ref_kato_territory SET territory_type='([A-Z_]+)',semantic_release='2026-11-17'\s+WHERE code IN \(([^;]+)\);",block):
                if kind in parsed:
                    raise ValueError("KATO_SEMANTIC_SQL_DUPLICATE")
                parsed[kind] = sorted(re.findall(r"'([0-9]{9})'",values))
            if parsed != {k:sorted(v) for k,v in manifest["territoryTypes"].items()}:
                raise ValueError("KATO_SEMANTIC_SQL_DRIFT")
        if args.require_admin_complete and result.get("administrativeCoverage") != "STRUCTURALLY_COMPLETE":
            raise ValueError("KATO_ADMIN_REQUIRED")
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        print(json.dumps({"directoryCoverage":"INVALID","activationAdmission":"HOLD","error":str(error)}))
        return 1
    print(json.dumps(result, indent=2))
    if args.expected_sha256 is not None and result["rawSha256"] != args.expected_sha256:
        return 1
    if args.require_complete and (args.expected_sha256 is None or result["directoryCoverage"] != "STRUCTURALLY_COMPLETE"):
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
