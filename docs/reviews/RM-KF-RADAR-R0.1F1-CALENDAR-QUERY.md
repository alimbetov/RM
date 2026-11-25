# RM-KF-RADAR-R0.1F1 — Calendar/list query service
Qualified baseline 2f50bfbb1d1c9c47eb15f4c2c5e913f73c26b76c: 105/105, TASK PG 53/53 H2 35+18 expected skips. [E](https://github.com/alimbetov/RM/actions/runs/45451472864), [C3](https://github.com/alimbetov/RM/actions/runs/45451473021). Parent a10badba8ae8a5275c6e875f48414b397f853f45 modifies RET04 tests/evidence only. RET04 job failure belongs to parallel stream, global release HOLD. New SHA requires fresh gates, no inherited PASS.

Internal TaskCalendarQueryService uses TaskTimingEngine.fullRelation; no duplicate classifier. Fresh signed actor/TASK_READ admission and existing transaction fence before business query; one Clock cutoff, one set-based SQL statement with scope, perspective, state/timeliness sets, DUE/COMPLETED date basis, half-open instant range, keyset and LIMIT+1. Default ALL_VISIBLE, DUE, all states except ARCHIVED, no timeliness filter; missing zone defaults UTC, explicit zone must be IANA ID. Absent range defaults current local month. Explicit undated DUE-only mode; 93 local-date cap; skipped historic local day may map to empty instant interval. Cursor bound to canonical applied filter (includes derived range, zone, sort via date basis/undated), tenant/actor/size/key. No count/offset/PII or client-side membership filter. Title code uses same single TaskTitlePolicy map as creation validation, legacy generic. No new DB table/index/dependency or TASK command changes. Current full relation aggregates events scoped by tenant, not a claim of calendar-scale performance.

12 named cases per database, 24 executions planned:
- f01CombinedSparseFiltersBeforeLimit
- f02PerspectiveAndDefaultArchivedExclusion
- f03DueVersusCompletedDateAndEvidence
- f04HalfOpenLeapYearAndMonthBoundaries
- f05DstSpringFallAndZoneDoesNotChangeTiming
- f06InvalidFilterLimitAndCursorBeforeBusinessSql
- f07StaticKeysetFullTraversalAndSingleSqlPerPage
- f08CursorBindsActorTenantZoneBasisFiltersAndLimit
- f09UndatedAndSafeTitleVocabulary
- f10FreshAdmissionAndCompositeTenantIsolation
- f11DefaultMonthAndFreshTimingOnLivePages
- f12CompletedDateIncludesValidEvidenceWithoutDue

Includes actual signed JWT, mutable Clock, positive-control-qualified test DataSource wrapper reused for statement counts/no DML. Initial trace CAL-12/13/14/15/16/17/18/20/21/23/24, E covers earlier timing cases. No full CAL family qualification yet. F2/D remaining: actual PG completion/archive/reschedule races and reassignment fences, independent multi-filter oracle, mixed-state history workload at 1k/10k/100k and plans, long/odd timing and broader trace completeness, no log leakage/expiry verification. G2 UI cases deferred, R0.2 separately authorized. Target 129/129 (105+24) plus TASK regression and existing C3 performance gate on candidate. Final qualification after F2/D, no REST/UI/main/production.
