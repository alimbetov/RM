#!/usr/bin/env python3
"""Fail closed on drift in literal production source-journal SQL insert sites."""
import collections
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "docs/reviews/RM-B2-1R-PRODUCER-INVENTORY.json"
PATTERN = re.compile(r"\binsert\s+into\s+(?:crm\s*\.\s*)?(sales_order_status_event|inv_stock_movement)\b", re.I)


# Conservative checks for additional producer syntax; arbitrary dynamic SQL
# and out-of-repository database writers are outside this static proof.
IDENTIFIER = r'(?:sales_order_status_event|inv_stock_movement)'
SQL_NAME = r'(?:"' + IDENTIFIER + r'"|' + IDENTIFIER + r'\b)'
SCHEMA = r'(?:(?:"crm"|crm)\s*\.\s*)?'
ALTERNATE = re.compile(r'\b(?:merge\s+into|copy)\s+' + SCHEMA + SQL_NAME, re.I)
MAPPING = re.compile(r'@(?:[\w.]+\.)?(?:Table|SecondaryTable|CollectionTable|JoinTable)\s*\([^)]*\b' + IDENTIFIER + r'\b', re.I | re.S)
QUOTED_INSERT = re.compile(r'\binsert\s+into\s+(?:"crm"\s*\.\s*' + SQL_NAME + r'|(?:crm\s*\.\s*)?"' + IDENTIFIER + r'")', re.I)
ADJACENT = re.compile(r'"\s*\+\s*"')


def alternate_producer(source, suffix):
    # Recognize adjacent Java string literals and escaped SQL identifier quotes.
    # Do not count those as admitted literal sites: require explicit review.
    normalized = ADJACENT.sub("", source).replace(r'\"', '"') if suffix == ".java" else source
    if ALTERNATE.search(normalized) or MAPPING.search(normalized) or QUOTED_INSERT.search(normalized):
        return True
    if suffix != ".java":
        return bool(PATTERN.search(normalized))
    return collections.Counter(x.lower() for x in PATTERN.findall(normalized)) != collections.Counter(
        x.lower() for x in PATTERN.findall(source))


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
    unexpected = []
    for path in sorted((ROOT / "src/main").rglob("*")):
        if path.suffix not in {".java", ".sql", ".xml"} or not path.is_file():
            continue
        source = path.read_text(encoding="utf-8")
        if alternate_producer(source, path.suffix):
            unexpected.append(path.relative_to(ROOT).as_posix())
    if unexpected:
        raise SystemExit("CANONICAL_ALTERNATE_PRODUCER_REVIEW_REQUIRED: " + ", ".join(unexpected))
    print("CANONICAL_LITERAL_PRODUCER_INVENTORY_PASS")


if __name__ == "__main__":
    main()
