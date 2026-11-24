# RM-KF-RADAR R0.1 + R0.2 — Joint Technical Review
**Date:** 2026-11-24 · **Source SHA:** e188c517c0eb6b59ef47e011c4d6618cbc771ffc · base_dev.
**Verdict:** DESIGN CORRECTIONS APPLIED IN DOCUMENTATION / IMPLEMENTATION & RUNTIME QUALIFICATION PENDING / PRODUCTION HOLD.
Reviewed R0.1 R1.1, R0.2 R1.0, TaskService, TaskState, TaskTitlePolicy, TenantAuthorization, VerifiedActor, Liquibase master, TenantRoleService, GlobalAccountStatusService, GlobalAccountDeletionService and TASK qualification workflow at source SHA. No Radar Java files at baseline. No Maven/PG/CI execution or independent security sign-off.

| ID | Finding / consequence | Correction in shared contract | Status |
|---|---|---|---|
| REV-01 | SQL predicate vs Java classification may disagree | single SQL authority, shared CASE and exact dialect adapter; test-only oracle | DESIGN FIX |
| REV-02 | R0.2 subtracts timestamps again | direction + magnitude from shared snapshot | DESIGN FIX |
| REV-03 | local countdown can contradict color | pending boundary indicator and atomic server refresh | DESIGN FIX |
| REV-04 | completion/history evidence vague | versioned transition, unique event, archive link, AFTER_CUTOFF separate | DESIGN FIX |
| REV-05 | extended due can conceal initial lateness | original proof, tri-state, separate warning | DESIGN FIX |
| REV-06 | optional filter/empty set/date range ambiguous | closed typed filters, normalized range, no optional original filters yet | DESIGN FIX |
| REV-07 | moving cutoff/keyset results may be incomplete | explicit live semantics, scoped cursor, no false complete count | DESIGN FIX |
| REV-08 | titles need detail N+1 or expose legacy text | same query approved titleCode / generic; no raw title | DESIGN FIX |
| REV-09 | concurrent completion may follow sampled cutoff | single statement, AFTER_CUTOFF and retry, no commit-time claim | DESIGN FIX |
| REV-10 | out-of-order refresh can expose prior tenant | coalescing, generation check, clear on denial/switch | DESIGN FIX |
| REV-11 | duplicated assertions/case counts | traceability without double-counting, preserve CI counts | DESIGN FIX |
| REV-12 | R0.2 parked, scope not silently expanded | design-only R0.2 until explicit authorization | GOVERNANCE RETAINED |

## Targeted acceptance refinements
These 12 check families refine existing 84 families, not automatically 12 additional executed tests. Bind exact assertions at implementation; use separate tests if coverage differs.
| ID | Required check | Trace |
|---|---|---|
| JTR-01 | SQL classification/filter and independent math agree before LIMIT on sparse data; no Java runtime classifier | RAD-35,CAL-12,CAL-22 |
| JTR-02 | signed microseconds, overflow limits, exact 50%, 1us overdue with zero seconds, H2/PG parity | RAD-08,CAL-01..04,REM-15 |
| JTR-03 | duplicate/malformed completion and archive version; due null but valid completion date | CAL-05,06,13 |
| JTR-04 | complete event version chain, initial null, broken chain, original/effective mismatch | CAL-07,08,REM-13 |
| JTR-05 | legitimate committed completion after sampled cutoff -> AFTER_CUTOFF, UNKNOWN; retry valid | CAL-05,19 |
| JTR-06 | titleCode from existing closed vocabulary, legacy sensitive/null -> generic, no N+1/raw title | RAD-34,CAL-24 |
| JTR-07 | default versus explicit empty filters; half-open DST range; cursor mismatch | CAL-12,14..18 |
| JTR-08 | live page movement and explicit incomplete marker | RAD-31,CAL-23 |
| JTR-09 | scope/expiry and actor/workspace fences on actual writer paths | RAD-15..21,CAL-20 |
| JTR-10 | descriptor consumes magnitude only; 24h/due boundary never contradictory confirmed label/color | REM-01..10,21 |
| JTR-11 | stale/out-of-order response, tenant switch, deny clears all visible data (G2) | REM-19,21..24 |
| JTR-12 | same statement count, no DML, exact-SHA artifacts and mandatory old CI preserved | RAD-35,36,CAL-22,REM-24 |

## Open implementation blockers and decisions
- Exact PG/H2 numeric primitive and query plan must be demonstrated; no performance claim from indexes existing.
- Full IAM writer lock protocol, stale entity behavior and role/account/member/workspace races require actual transactions; inspected three writers and fence, not all paths independently qualified.
- Source event history quality for legacy tasks unknown; no data repair under Radar.
- Proposed safe titleCode, 60-second refresh, compact floor, thresholds and enum names are design defaults, not externally approved SLA. Review UI/locales at G2.
- R0.2 implementation requires owner scope exception; prior R0.2 contract-expiration idea remains PARKED.
- Current frozen-candidate CI status not verified; documentation commit is not a release candidate qualification. Before code verify actual HEAD, active candidate and mandatory BSR work. No main/production.

Authoritative resolution: [Shared Task Timing Contract R1](../spec/RM-KF-RADAR-TASK-TIMING-CONTRACT-R1.md). R0.1 R1.2 and R0.2 R1.1 explicitly defer conflicting technical wording to this contract. Preserve original matrices and constraints.
