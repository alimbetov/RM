#!/usr/bin/env python3
"""Executable DESIGN examples, stdlib only; no RM/SQL/pod qualification.

Run from repository root:
  python3 docs/design/intelligent-commerce/qualification/rm_ars_r1_reference_oracles.py
Assertions are mandatory; do not use python -O. Fixtures are synthetic.
The finite model assumes each guarded transition is atomic. Real database locks,
FKs, permissions, crashes and network/file behavior need independent runtime tests.
"""
import hashlib
import json
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
from fractions import Fraction
from itertools import permutations
from zoneinfo import ZoneInfo


def reference_vectors():
    result = []

    def record(identifier, expected):
        result.append({"id": identifier, "status": "REFERENCE_PASS", "expected": expected})

    groups = [{"o1", "o2"}, {"o1", "o3"}]
    assert len(set.union(*groups)) == 3 and sum(map(len, groups)) == 4
    record("M01", {"union_orders": 3, "incorrect_sum": 4})

    amounts, counts = [100, 900], [1, 3]
    ratio = Fraction(sum(amounts), sum(counts))
    assert ratio == 250 and sum(Fraction(a, n) for a, n in zip(amounts, counts)) / 2 == 200
    record("M02", {"ratio_of_sums": "250", "incorrect_mean_of_ratios": "200"})

    weighted = sum(Fraction(v * w) for v, w in [(10, 1), (20, 3)]) / 4
    assert weighted == Fraction(35, 2)
    record("M03", {"weighted_mean": "17.5"})

    values = [2, 4, 4, 4, 5, 5, 7, 9]
    mean = Fraction(sum(values), len(values))
    ss = sum((v - mean) ** 2 for v in values)
    assert mean == 5 and ss / len(values) == 4 and ss / (len(values) - 1) == Fraction(32, 7)
    assert Fraction(2, 1) / mean == Fraction(2, 5)
    record("M04", {"mean": "5", "variance_pop": "4", "stddev_pop": "2", "cv": "0.4", "variance_sample": "32/7"})

    cumulative = [4, 7, 6]
    assert cumulative[-1] == 6 and sum(cumulative) == 17 and cumulative[-1] - cumulative[-2] == -1
    record("M05", {"latest_cumulative": 6, "incorrect_sum": 17, "correction_delta": -1})

    a = datetime(2026, 11, 27, 8, tzinfo=timezone.utc)
    b = datetime(2026, 11, 28, 10, tzinfo=timezone.utc)
    threshold = max(a, b) + timedelta(hours=24)
    assert threshold == datetime(2026, 11, 29, 10, tzinfo=timezone.utc)
    assert threshold - timedelta(microseconds=1) < threshold
    record("M06", {"earliest_time_if_all_other_guards_pass": threshold.isoformat()})

    # A three-valued semantic fixture: no proof != observed/qualified zero.
    missing, unqualified, qualified = (None, "MISSING"), (0, "PARTIAL"), (0, "READY")
    assert len({missing, unqualified, qualified}) == 3
    record("M07", {"distinct_cases": [[None, "MISSING"], [0, "PARTIAL"], [0, "READY"]]})

    rows = [Decimal("0.004"), Decimal("0.004")]
    rounded_total = sum(rows).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    rounded_rows = sum(v.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP) for v in rows)
    assert sum(rows) == Decimal("0.008") and rounded_total == Decimal("0.01") and rounded_rows == 0
    record("M08", {"raw_sum": "0.008", "displayed_total": "0.01", "sum_of_displayed_rows": "0.00", "rounding": "HALF_UP_for_this_fixture_only"})

    zone = ZoneInfo("America/New_York")
    def hours(year, month, day, next_day):
        start = datetime(year, month, day, tzinfo=zone).astimezone(timezone.utc)
        end = datetime(year, month, next_day, tzinfo=zone).astimezone(timezone.utc)
        return (end - start).total_seconds() / 3600
    assert hours(2026, 3, 8, 9) == 23 and hours(2026, 11, 1, 2) == 25
    instant = datetime(2026, 11, 27, 21, tzinfo=timezone.utc)
    assert instant.astimezone(ZoneInfo("Asia/Almaty")).date().isoformat() == "2026-11-28"
    assert instant.date().isoformat() == "2026-11-27"
    record("M09", {"civil_hours": [23, 25], "utc_staging_date": "2026-11-27", "almaty_date": "2026-11-28"})

    old, new = {"revision": 7, "generation": "G1"}, {"revision": 7, "generation": "G2"}
    assert old["revision"] == new["revision"] and old != new
    record("M10", {"same_scalar_revision_proves_same_manifest": False})

    # Unit and denominator preconditions, not a new production metric method.
    def scoped_ratio(n, d, numerator_scope, denominator_scope):
        if numerator_scope != denominator_scope:
            return None, "SCOPE_MISMATCH"
        if d == 0:
            return None, "UNDEFINED_DENOMINATOR"
        return Fraction(n, d), "DEFINED"
    assert scoped_ratio(10, 0, "KZT", "KZT")[1] == "UNDEFINED_DENOMINATOR"
    assert scoped_ratio(10, 2, "KZT", "USD")[1] == "SCOPE_MISMATCH"
    record("M11", {"zero_denominator": "UNDEFINED_DENOMINATOR", "mixed_scope": "SCOPE_MISMATCH"})

    # Cross-language golden bytes for the proposed typed staging format.
    def decimal_text(value):
        if value is None:
            return None
        n = Decimal(value)
        if not n.is_finite():
            raise ValueError("Non-finite number")
        if n == 0:
            return "0"
        plain = format(n, "f")
        return plain.rstrip("0").rstrip(".") if "." in plain else plain

    def frame(data):
        return str(len(data)).encode("ascii") + b":" + data

    def canonical(value):
        return json.dumps(value, sort_keys=True, separators=(",", ":"),
                          ensure_ascii=False, allow_nan=False).encode("utf-8")

    header = {"schema": "RM-ARS-DATASET-1", "tenant": "00000000-0000-0000-0000-000000000001",
              "dataset": "00000000-0000-0000-0000-000000000002",
              "scope_sha256": "0" * 64, "source_manifest_sha256": "1" * 64}
    row = ["1", "BOOKED_AMOUNT", "1", "2026-10-01T00:00:00.000000Z",
           "2026-11-01T00:00:00.000000Z", [], "CURRENCY", "KZT", decimal_text("10.5000"),
           "PARTIAL", "HISTORICAL_COVERAGE_UNQUALIFIED"]
    encoded = b"RM-ARS-DATASET-1\n" + frame(canonical(header)) + frame(canonical(row)) + frame(b"1")
    digest = hashlib.sha256(encoded).hexdigest()
    assert len(encoded) == 484 and digest == "f54eae406408e614c77b222932179052d01819dbaa7757d2e0f2470601293995"
    assert decimal_text("-0.000") == "0" and decimal_text("1000") == "1000"
    assert decimal_text(None) is None
    altered = row.copy()
    altered[8] = None
    assert canonical(altered) != canonical(row)
    assert frame(b"ab") + frame(b"c") != frame(b"a") + frame(b"bc")
    foreign = dict(header, tenant="00000000-0000-0000-0000-000000000003")
    assert canonical(foreign) != canonical(header)
    assert decimal_text("10.5000") == decimal_text("10.5")
    record("M12", {"canonical_bytes": len(encoded), "sha256": digest})
    return result


def consumer_schedules():
    """All 720 orderings of six atomic operations on one live consumer.

    'advance' moves logical time 48 hours; abandonment grace is 24h ONLY in
    this fixture. ACK is separate from publish. No row-lock implementation
    is modeled, and this enumeration is not a proof of all concurrent programs.
    """
    totals = {"schedules": 0, "dropped": 0, "published": 0, "abandoned": 0}
    for order in permutations(("publish", "publish_retry", "abandon", "ack", "advance", "drop")):
        state, now, stamp, receipt, dropped = "ACTIVE", 0, None, None, False
        for op in order:
            if op == "advance":
                now = 48
            elif op in ("publish", "publish_retry") and state == "ACTIVE" and not dropped:
                state, stamp, receipt = "PUBLISHED_PENDING_ACK", now, "immutable-receipt"
            elif op == "abandon" and state == "ACTIVE" and not dropped:
                state, stamp = "ABANDONED", now
            elif op == "ack" and state == "PUBLISHED_PENDING_ACK":
                state = "PUBLISHED_ACK"
            elif op == "drop" and state in ("PUBLISHED_ACK", "ABANDONED") and now >= stamp + 24:
                dropped = True
            assert not (receipt and state == "ABANDONED")
            assert not dropped or (state in ("PUBLISHED_ACK", "ABANDONED") and now >= stamp + 24)
            assert not dropped or not receipt or state == "PUBLISHED_ACK"
        totals["schedules"] += 1
        totals["dropped"] += int(dropped)
        totals["published"] += int(receipt is not None)
        totals["abandoned"] += int(state == "ABANDONED")
    assert totals["schedules"] == 720 and totals["dropped"] > 0
    assert totals["published"] > 0 and totals["abandoned"] > 0
    return totals


if __name__ == "__main__":
    if not __debug__:
        raise SystemExit("Assertions must be enabled; do not run python -O")
    print(json.dumps({"scope": "DESIGN_REFERENCE_ONLY", "runtime_qualified": False,
                      "vectors": reference_vectors(), "atomic_consumer_model": consumer_schedules()}, indent=2))
