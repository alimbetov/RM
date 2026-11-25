#!/usr/bin/env python3
"""Fail closed on drift in literal production source-journal SQL insert sites."""
import collections
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "docs/reviews/RM-B2-1R-PRODUCER-INVENTORY.json"
PATTERN = re.compile(r"\binsert\s+into\s+(?:crm\s*\.\s*)?(sales_order_status_event|inv_stock_movement)\b", re.I)


def main():
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    expected = {(r["path"], r["table"]): r["sites"] for r in data["producers"]}
    if len(expected) != len(data["producers"]):
        raise SystemExit("Duplicate producer manifest entry")
    actual = collections.Counter()
    for path in sorted((ROOT / "src/main/java").rglob("*.java")):
        relative = path.relative_to(ROOT).as_posix()
        for match in PATTERN.finditer(path.read_text(encoding="utf-8")):
            actual[(relative, match.group(1).lower())] += 1
    for key in sorted(set(expected) | set(actual)):
        print(f"{key[0]} :: {key[1]} expected={expected.get(key, 0)} actual={actual.get(key, 0)}")
    if actual != expected:
        raise SystemExit("CANONICAL_PRODUCER_INVENTORY_DRIFT")
    # Conservative secondary inventory: ORM table mappings and SQL resource
    # writes may bypass the literal Java INSERT contract. No approved alternate
    # producers currently exist. Flag them for explicit owner review.
    names = r"(?:sales_order_status_event|inv_stock_movement)"
    alternate = re.compile(r"\b(?:merge\s+into|copy)\s+(?:crm\s*\.\s*)?" + names + r"\b", re.I)
    mapping = re.compile(r"@(?:[\w.]+\.)?(?:Table|SecondaryTable|CollectionTable|JoinTable)\s*\([^)]*\b" + names + r"\b", re.I | re.S)
    quoted_insert = re.compile(r'\binsert\s+into\s+(?:"?crm"?\s*\.\s*)?"' + names + r'"', re.I)
    unexpected = []
    for path in sorted((ROOT / "src/main").rglob("*")):
        if path.suffix not in {".java", ".sql", ".xml"} or not path.is_file():
            continue
        source = path.read_text(encoding="utf-8")
        if alternate.search(source) or mapping.search(source) or quoted_insert.search(source) or (
                path.suffix != ".java" and PATTERN.search(source)):
            unexpected.append(path.relative_to(ROOT).as_posix())
    if unexpected:
        raise SystemExit("CANONICAL_ALTERNATE_PRODUCER_REVIEW_REQUIRED: " + ", ".join(unexpected))
    print("CANONICAL_LITERAL_PRODUCER_INVENTORY_PASS")


if __name__ == "__main__":
    main()
