# COR-01.1–01.2 — Actual aggregate capability and archived DQR responsibility audit
2026-11-27 | SOURCE REVIEW | DESIGN ONLY | B4/API FREEZE | PRODUCTION HOLD
Runtime source a2fdfb088c36b1b082162aa31aa3b0f346176351 (tree matches 632f803914189be15c18eb2e80852ba44ece1e0a); archived DQR 712a55d8898a728d1f83e0ca54ec94d700acf4bd; design parent acfb360435e8ea4af1109b5a4c679e8c6651e4f9. GitHub targeted PostgreSQL 45922947533 PASS; 57 mandatory registry cases. Exact-SHA admission 45922947537 in progress at initial observation; no new runtime qualification performed here. Review covers changed-file inventory, inspected named runtime methods and documented test/role evidence, not exhaustive whole-repo call graph or measured workload. See RM-DEV-GOV-R1 and RM-IC-SINGLE-ENGINE-CONTRACT-R1. No runtime/DDL changes.

## 1. Existing ownership and exact route
| Existing component | Actual contract | Decision |
|---|---|---|
| AutoOrderService.checkout / checkoutAcceptedQuote | @Transactional order/lines/PLACED/appendLive/receipt; existing workspace lock; JVM Instant.now timestamps | Keep; do not add capture guard until source gap proved |
| CanonicalFactDeliveryService.openBackfill/backfillBatch/coverage | Run bounded to 31 days, batch 500; source/fact identity/time, receipt/digest/schema disposition checks | Reuse for present-source reconciliation; not historical owner completeness |
| L1ProjectionService.open/advance | Existing materialized daily buckets by metric and generation | Keep |
| L2ProjectionService.advance | Contributions, existing consumer/revision | Keep |
| L3RollupService.refresh/refreshIfStale | Period cells and exact order memberships, existing lifecycle | Keep |
| GenerationRebuildService.step | Bounded replay/rollup/proveOrReuse | Keep |
| L3DerivedSeriesService.readPeriods | Set-based requested periods; COUNT DISTINCT membership; line metrics L2 join; period stamp checked | Reuse for bulk closed periods, no duplicate aggregations |
| AuthorizedAnalyticalReadService.read/readWindow/readMetricWindows | Fresh ACL, outer RC + inner RR, <=12 anchors/<=3 metrics, current L3 else L0 owner fallback; Result binds generation/proof/revision/asOf; PARTIAL/MISSING history | Reuse; do not increase limits or change fallback without admission |
| MultidimensionalCoverageService.prove/proveOrReuse/statusInSnapshot | Stored proof with five revision dimensions, independent delivery/contribution/period/cell/membership oracle | Keep security; see critical finding |
| IndependentCanonicalDigestVerifier.mismatches | Whole-tenant independent digest oracle used by delivery proof | Keep; bounded slice candidate only if needed |
| BusinessConclusionService | Existing B5.5 evidence and abstention; decision BLOCKED | Keep |
| MetricQueryService.calculate | Owner metric path; historical PARTIAL, live position READY where present | Preserve existing semantics; not report-specific second engine |

## 2. Confirmed critical read-path issue predating DQR
AuthorizedAnalyticalReadService.readMetricWindows iterates per metric and anchor inside one RR snapshot, calling readSnapshot -> coverage.statusInSnapshot -> evaluate -> delivery/contributions/periods/cells/memberships. evaluate checks stored proof/revision vector then deliberately reruns all independent parity queries even for matching zero proof; delivery calls whole-tenant IndependentCanonicalDigestVerifier.mismatches and owner/fact/receipt SQL. proveOrReuse likewise calls evaluate. Thus an L3 result does not imply aggregate-only SQL. Up to 36 period/metric lookups per admitted window may invoke expensive checks if current closed L3 applies; not a measured query count or latency. readPeriods currently receives List.of(period) in that route; existing set-based implementation not reused across window. L0 fallback scans owner view by design. No claim that current code returns 24 monthly rows in one authorized call: limit 12. A 24-month report needs separately admitted bounded bulk contract.
Existing SQL role gate contains rm_dim_proof_gate NOLOGIN SELECT/INSERT and denies mutation; ordinary synthetic roles denied forgery. This is privilege-semantic evidence, NOT qualified deployed writer identity. evaluate explicitly protects against forged stored zero proof. Never simply remove oracle or trust a cached zero vector. B4 FREEZE: any proof-trust/read optimization is explicit change-control, not an incidental DQR refactor. First measure query plan/count and inspect actual identity, revision/invalidation coverage, proof writer/activation trust, fail-closed behavior; if cannot prove, retain old path and limit/report HOLD. Separate correctness of materialization from completeness of real history.

## 3. Genuine historical gap and minimal target
Current source parity can show recorded rows equal facts/projections. It cannot establish continuity before onboarding, unimported data, or verified closed zero. L3 readPeriods emits MISSING/null for no matching cells even with valid period stamp; absence alone is not zero. HistoricalCoverageService/NativeBookedClosure archived logic distinguishes missing/open/pre-boundary/qualified shadow zero but never admits READY. Owner declaration/structural input match not authenticated source evidence.
Use existing metric BOOKED_ORDERS from L3 + membership; do not alias PLACED_ORDERS without population/time/scope/method proof. First scope tenant-wide SALES/ORDER_PLACED, occurred time, method 1, explicit calendar and half-open periods, SALES_ORDER_READ. BOOKED geography unsupported; group/SKU/currency/unit scope not inherited from tenant-wide proof without separate semantics. Existing source revision tenant-global (including other streams); targeted invalidation is not proved; preserve conservative stale behavior. Bind historical completeness evidence to tenant/metric/scope/calendar/period/source revision/generation/proof/asOf. This is a semantic requirement, not automatic approval for new tables. Independent source count/digest is an oracle only. If complete range, empty matched aggregate and proof, deliberate zero admission may be designed; positive/zero/missing/stale remain distinct. Report path should read authorized existing aggregate + trusted evidence; no report-time independent source audit after qualification, no hidden fallback scan for qualified history. Current behavior remains until admitted change. D06C intraday 3h existing L0 provisional, nightly closed L3; no change or ACTIVE mutation.

## 4. Archived DQR disposition
31 changed files relative to last pre-S1 baseline, 22 commits. File-level inventory (no irreversible deletion; all history accessible). Runtime restore as-is NOT recommended. Pure rules and oracle tests may be selected after concrete gap; no separate declaration ledger/capture lifecycle, source transaction fences or Hikari semaphore until necessary and approved.
| File | Diff | Lines | Proposed disposition |
|---|---|---|---|
| `.github/qualification/rm-intelligence-targeted.json` | modified | +148/-0 | Archive; select only for admitted behavior |
| `.github/workflows/rm-intelligence-targeted-pg.yml` | modified | +9/-1 | Archive; select only for admitted behavior |
| `docs/architecture/RM-IC-R2-S1-HISTORICAL-COVERAGE.md` | added | +89/-0 | Archive; select only for admitted behavior |
| `docs/operations/RM-FINAL-SEC-GATE-DB-ROLE-QUALIFICATION.sql` | modified | +202/-0 | Archive; select only for admitted behavior |
| `docs/reviews/RM-IE-00B-R2-SOURCE-INVENTORY.json` | modified | +10/-6 | Archive; select only for admitted behavior |
| `src/main/java/kz/rm/autoorder/AutoOrderService.java` | modified | +10/-4 | HOLD owner mutation/DDL/lock review and database compatibility |
| `src/main/java/kz/rm/intelligence/BookedHistoryComposition.java` | added | +77/-0 | Reuse pure interval/zero rules selectively; not report value |
| `src/main/java/kz/rm/intelligence/BookedMonthlyAuthorityAssessment.java` | added | +95/-0 | Reuse pure interval/zero rules selectively; not report value |
| `src/main/java/kz/rm/intelligence/BookedOwnerAuthorityContract.java` | added | +74/-0 | Defer authority persistence/adapter; structural declaration not trusted proof |
| `src/main/java/kz/rm/intelligence/BookedOwnerDeclarationService.java` | added | +160/-0 | Defer authority persistence/adapter; structural declaration not trusted proof |
| `src/main/java/kz/rm/intelligence/BookedSourceAuthorityInputs.java` | added | +73/-0 | Defer authority persistence/adapter; structural declaration not trusted proof |
| `src/main/java/kz/rm/intelligence/BookedSourceReconciliationService.java` | added | +362/-0 | Bounded independent oracle candidate; reuse existing delivery/coverage; no runtime restore as-is |
| `src/main/java/kz/rm/intelligence/HistoricalCoverageService.java` | added | +239/-0 | Reuse pure interval/zero rules selectively; not report value |
| `src/main/java/kz/rm/intelligence/IndependentCanonicalDigestVerifier.java` | modified | +87/-0 | Bounded independent oracle candidate; reuse existing delivery/coverage; no runtime restore as-is |
| `src/main/java/kz/rm/intelligence/NativeBookedCaptureService.java` | added | +47/-0 | HOLD owner mutation/DDL/lock review and database compatibility |
| `src/main/java/kz/rm/intelligence/NativeBookedClosure.java` | added | +45/-0 | Reuse pure interval/zero rules selectively; not report value |
| `src/main/resources/db/changelog/db.changelog-master.sql` | modified | +196/-0 | HOLD owner mutation/DDL/lock review and database compatibility |
| `src/test/java/kz/rm/iam/BookedConcurrencyEvidence.java` | added | +112/-0 | Keep archived regression evidence; requalify only necessary lock/operational behavior |
| `src/test/java/kz/rm/iam/BookedDeclarationLifecycleEvidence.java` | added | +109/-0 | Defer authority persistence/adapter; structural declaration not trusted proof |
| `src/test/java/kz/rm/iam/BookedMonthlyJourneyEvidence.java` | added | +59/-0 | Archive; select only for admitted behavior |
| `src/test/java/kz/rm/iam/BusMvpServiceJourneyQualificationTest.java` | modified | +59/-2 | Archive; select only for admitted behavior |
| `src/test/java/kz/rm/iam/CanonicalDeliveryJourneyEvidence.java` | modified | +168/-1 | Archive; select only for admitted behavior |
| `src/test/java/kz/rm/iam/NativeBookedCaptureJourneyEvidence.java` | added | +265/-0 | HOLD owner mutation/DDL/lock review and database compatibility |
| `src/test/java/kz/rm/intelligence/BookedDigestPgEvidence.java` | added | +131/-0 | Bounded independent oracle candidate; reuse existing delivery/coverage; no runtime restore as-is |
| `src/test/java/kz/rm/intelligence/BookedHistoryCompositionQualificationTest.java` | added | +69/-0 | Reuse pure interval/zero rules selectively; not report value |
| `src/test/java/kz/rm/intelligence/BookedMonthlyAuthorityQualificationTest.java` | added | +66/-0 | Archive; select only for admitted behavior |
| `src/test/java/kz/rm/intelligence/BookedOwnerAuthorityQualificationTest.java` | added | +138/-0 | Defer authority persistence/adapter; structural declaration not trusted proof |
| `src/test/java/kz/rm/intelligence/BookedSourceReconciliationQualificationTest.java` | added | +51/-0 | Bounded independent oracle candidate; reuse existing delivery/coverage; no runtime restore as-is |
| `src/test/java/kz/rm/intelligence/HistoricalCoverageQualificationTest.java` | added | +101/-0 | Archive; select only for admitted behavior |
| `src/test/java/kz/rm/intelligence/NativeBookedClosureQualificationTest.java` | added | +42/-0 | Reuse pure interval/zero rules selectively; not report value |
| `src/test/java/kz/rm/sales/QrNegotiatedCheckoutQualificationTest.java` | modified | +299/-4 | Keep archived regression evidence; requalify only necessary lock/operational behavior |

## 5. Decision proposal for COR-01.3–01.4 (no implementation approval)
A. Review actual proof writer trust boundary and whole-tenant/per-period query cost. Reuse current proof table/revisions; prefer existing path with safe once-per-snapshot reuse or qualified persisted proof read, but neither is approved until forged proof, stale/revoked access, concurrent activation, late events and production identity are addressed. Do not build new cache/engine by default.
B. Review owner capture continuity: compare existing transactional checkout, immutable source events/facts/receipts, retention, source revision and clock/commit boundary. Independent parity != completeness. If missing, propose smallest source evidence contract, then compare archived native capture guards. Do not restore entire 0222/0223 bundle or mutate applied changesets. Actual deployment DB migration inventory remains unknown; source revert not DB rollback. External import authority separate, deferred.
C. Define minimal authorized bulk historical result and exact zero contract before implementing. Reuse set-based readPeriods, existing active generation and proof, B5 evidence. Measure and set workload bounds before changing limits. Preserve L0 provisional route where approved; qualify no primary-table scans in ordinary admitted historical read. No general report engine or S2–S6 expansion.
Acceptance before owner review: method/SQL and identity/lock inventory, gap/alternatives, concrete minimal diff and independent source oracle, zero/duplicate/missing/stale/foreign cases, query-count and PG role proofs; no weakened existing tests or READY claim. Existing S1–S6/DAG unchanged; Q4.2/Q5 HOLD. COR-01.1–01.2 source/file responsibility review recorded; exhaustive call-graph/production schema and runtime proof remain OPEN in COR-01.3. Owner approval required for COR-01.6.
