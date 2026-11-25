# RM-KF-RADAR-R0.1A — Exact Timing Qualification

Baseline: 112d6b11991395936838ba973eb4a44c743a5edb (base_dev).
Status: TEST PACKAGE / DATABASE EXECUTION PENDING / RELEASE HOLD.

## Owner decision — independent engineering admission (2026-11-24)
Owner explicitly approved R0.1A integration independently of ongoing global CI. This supersedes the previous requirement to await all active global runs before this development commit only. CR-MVP-KF-RADAR-01/01A remain the only implementation scope; R0.2 and G2 remain parked. Existing mandatory CI, W6, BSR, exact-SHA release admission, main and production HOLD are unchanged. New base_dev SHA reruns global checks; older evidence is not current-SHA proof. No acceptance of known engineering defects. No protected-branch change. R0.1B requires both engine jobs 12/12, zero failure/error/skip, named methods, same SHA, no unresolved relevant regression. Before release, require existing admission gate and full Radar qualification on exact candidate SHA.

## Test-only candidate
No application context, migration, schema, dependencies, new service or business DML. Independent Java Duration/BigInteger oracle; SQL candidates:
- PostgreSQL 17: CAST(EXTRACT(EPOCH FROM (b-a))*1000000 AS NUMERIC(30,6)).
- H2: CAST(DATEDIFF('MICROSECOND', a AT TIME ZONE 'UTC', b AT TIME ZONE 'UTC') AS NUMERIC(30,6)).
- Integration refinement from prepared patch: keep H2 operands zoned in UTC instead of casting to zone-less timestamp (avoid possible session-zone conversion); explicit named aliases instead of expression text replacement. Both remain unqualified candidates until actual database tests pass.
- Integral signed microseconds checked by BigDecimal.toBigIntegerExact. Finite candidate range years 0001–9999; no infinity claim. Timestamp and CASE probes do not qualify production event evidence, authorization, filtering, or performance.

| Prefix | Proof target | Trace |
|---|---|---|
| q01 | signed subsecond, zero, microseconds | RAD-07/08,JTR-02 |
| q02 | epoch/second crossing | JTR-02 |
| q03 | long range both directions | CAL-03,JTR-02 |
| q04 | offset equivalence, spring DST both directions | RAD-10,CAL-15 |
| q05 | fall DST, 23/25h elapsed days | CAL-15 |
| q06 | NULL propagation | RAD-06,CAL-04 |
| q07 | cutoff and JDBC round trip | RAD-11 |
| q08 | exact 50%, adjacent microseconds, odd plan | CAL-01,JTR-02 |
| q09 | equality, late, invalid plan/NULL | CAL-02/04 |
| q10 | 24/72h boundaries, zero | RAD-07/09,CAL-10 |
| q11 | seeded 256 pairs both directions | JTR-02 |
| q12 | long-plan threshold | CAL-03 |

## Evidence
Workflow RM Radar R0.1A Exact Timing Qualification checks exact checkout SHA and actual DB product (PG major 17); no fallback or skips. Two separate exact-SHA XML artifacts; names rm-radar-r01a-h2-<sha> and rm-radar-r01a-postgresql-<sha>. Each must contain all twelve exact named methods and zero failures/errors/skips. Existing TASK R2 53-case CI unchanged. Integration of full Radar evidence into global release gate is required at R0.1 closure; no waiver. Test results pending, not a PASS claim.
