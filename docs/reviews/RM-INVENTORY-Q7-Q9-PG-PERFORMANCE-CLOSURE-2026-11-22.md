# RM INVENTORY Q7–Q9 — PostgreSQL qualification and performance review

**Date:** 2026-11-22. **Branch:** `base_dev`. **Scope:** quantity inventory only, authenticated service layer; production **HOLD**.
**Baseline exact SHA:** `2ede6576b2907e7a419dd85d05364c8d4f0300c0`.
**PostgreSQL shared-owner-lock optimization + first-position race fix SHA:** `534aa18008bc54f77a4b1f0277d0f041bfa12bae` (later code/test updates must be reviewed at their own SHA).
**States:** Baseline Q1–Q9 `POSTGRESQL QUALIFIED` in proven scope; optimization `IMPLEMENTED / CI PENDING` until all exact-head gates pass. Neither means production admission or full economic costing.

## 1. Exact baseline evidence, not assumptions

| Required check | Action / concrete evidence | Result |
|---|---|---|
| Domain security and PostgreSQL | [run 44987004180](https://github.com/alimbetov/RM/actions/runs/44987004180) — 3 jobs `h2-smoke`, `postgres-integration`, `postgres-pii-populated` | SUCCESS |
| Second domain security / PostgreSQL | [run 44987000110](https://github.com/alimbetov/RM/actions/runs/44987000110) | SUCCESS |
| Populated CRM upgrade / migration | [run 44987000100](https://github.com/alimbetov/RM/actions/runs/44987000100) — changesets 0070–0075 applied successfully to populated PostgreSQL | SUCCESS |
| RustFS real infrastructure | [run 44987000146](https://github.com/alimbetov/RM/actions/runs/44987000146) | SUCCESS (unrelated infrastructure regression) |
| PostgreSQL domain suite | Job 137099653338: `299 tests, 0 failures, 0 errors, 18 skipped`. The three named domain classes below have **zero** skipped | SUCCESS |
| `InventoryTransferStocktakeQualificationTest` | Q7 atomic two-leg transfer/ledger conservation, idempotent retry, opposite-direction and destination-receipt races, Q8 count/post/recount/terminal immutable | 7 tests, 0 failures/errors/skipped |
| `InventoryQ123QualificationTest` | First receipt, tenant/role, negative stock, Q4/Q6 history, bounded PostgreSQL lock probe | 7 tests, 0 failures/errors/skipped |
| `SalesInventoryRbacQualificationTest` | Coupled SALES confirm/reserve/cancel/fulfill and Q9 source/actor ledger | 16 tests, 0 failures/errors/skipped |
| H2 smoke | Job 137099653128 `299 tests, 0 failures, 0 errors, 57 skipped` (PostgreSQL-only tests skipped by design) | SUCCESS |

No claim that all 299 tests exercised identical environments or that all unrelated skipped tests are resolved.

## 2. Observed synthetic PostgreSQL performance

Baseline `INVENTORY_PG_SYNTHETIC_LOCK_PROBE`: **8 concurrent workers, 24 receipts, 1 tenant, 1 warehouse, 1 SKU**. Exact job 137099653338 reported **p95=46 ms**, **p99=53 ms**. Source prints elapsed wall time per Java receive operation including transaction-bound RBAC and PostgreSQL row locking. These are one CI run's latency samples, not throughput/production percentiles or agreed SLA.

**Bottlenecks in baseline source:**

1. `StockEligibility.requireWarehouse` and `requireProduct` used `SELECT ... FOR UPDATE` on owner rows during every new physical stock operation. This serializes all SKU work on the same warehouse even if balance positions differ; catalog SKU owner locks also exclude readers.
2. `InventoryMovementService.adjust` and `InventoryTransferService.transfer` use a **global 64-row request-stripe table**, not per-tenant stripes. Different tenants/keys can collide in a stripe; lock stripe first must remain deterministic to prevent duplicate request-key effects.
3. `inv_stock_balance` row `FOR UPDATE` is necessary to enforce `on_hand>=reserved` and exactly-once physical movement; same-SKU contention **cannot** be eliminated safely simply by dropping it.
4. `InventoryStocktakeService.start/post` holds locks through up to 100 SKU rows, so long count postings increase lock duration; post is deliberately atomic and bounded.
5. `InventoryHistoryQueryService.searchMovements` issues count + offset page and has relevant `(tenant_id,item_id,recorded_at,id)` index, but arbitrary tenant-wide and location history sort requires EXPLAIN against populated data. Deep OFFSET may degrade and warrants cursor pagination only after observed requirement.
6. `TenantAuthorization.requireForCommand` **already** uses PostgreSQL `FOR SHARE` for actor/workspace and exclusive test-only H2 lock. Reuse the *same proven dialect pattern* for inventory owner admission, not a novel lock subsystem.

**Minimal optimization implemented (CI PENDING):** `StockEligibility` selects PostgreSQL owner rows `FOR SHARE` to allow concurrent admission for independent stock rows while still blocking warehouse status UPDATE and SKU archival/publish UPDATE. H2 keeps `FOR UPDATE`. `requireTransferWarehouses` still reads locations in deterministic order. With compatible shared owner locks, initial empty position creation is made conflict-safe by PostgreSQL `INSERT ... ON CONFLICT (tenant_id,location_id,item_id) DO NOTHING` in `StockEligibility.ensureZeroPosition`; H2 uses previous conditional insertion under exclusive owner lock. Both `InventoryMovementService` first receipts and transfer destination initialization now call the shared helper. No database schema or controller added.

**Mandatory NEW evidence:** `InventoryQ123QualificationTest.postgresSharedOwnerAdmissionAllowsParallelChecksAndStillFencesClosure` proves two simultaneous owner admissions do **not** block each other, but an owner status UPDATE cannot commit until both release; `postgresSyntheticFanoutAdmissionProbeAcrossEightSkus` records **8 workers, 24 receipts, 8 SKU, 1 tenant warehouse** with p95/p99 and all 24 ledger rows; existing `concurrentFirstReceiptsDoNotDoubleCreateOrLoseQuantity` + transfer/stocktake suites must remain GREEN.

**Do not infer:** 8-SKU p95 improvement from the one-SKU baseline; to compare same SKU across versions run same CI scenario under identical hosts and repetitions. Lack of H2 PostgreSQL shared locking tests is compensated by PostgreSQL-only owner compatibility test, not a claim of identical lock modes.

## 3. 12-pass INVENTORY closure map (functional quantity R1)

| Pass | Source-based evidence | Functional status |
|---:|---|---|
| 01 | Verified clerk receive, stock read, order fulfillment, movement history; ORG/CATALOG bootstrap still in other domains | Completed service journey |
| 02 | Public VerifiedActor facades, owner admission, ledger, stocktake and transfer; no empty command methods found | Implemented & tested in scope |
| 03 | Composite tenant FKs, row version, stock invariants, additive changesets 0070–0075, immutable PG triggers | PG qualified baseline |
| 04 | Receive/correct/writeOff/transfer/startCount/count/post/cancel typed writes | Qualified baseline |
| 05 | getPosition/searchMovements/reservationHistory/stocktake detail with tenant predicate | Qualified baseline, deep-page performance open |
| 06 | Reservation exactly once, immutable 2-leg transfer, CAS physical count | Qualified baseline |
| 07 | Mandatory reserve reflected in operational settings; false FEFO/picking switches not exposed | Q1–Q3 qualified |
| 08 | ORG active warehouse, CATALOG product/published SKU, SALES paired authorization and immutable provenance | Qualified quantity integration, cross-domain offer projection still external |
| 09 | VerifiedActor + tenant RBAC, foreign refs, negative result, revoke fencing | Qualified named test scope, full TCRUD-00/03 remains open |
| 10 | PostgreSQL race tests (same item, reverse transfers, receipt-versus-transfer, stale stocktake), p95/p99 diagnostic | Correctness qualified baseline; production load OPEN |
| 11 | Cost policy `POLICY_ONLY`; no financial valuation, FEFO/lot, payment, COGS | Explicit separate HOLD |
| 12 | Four baseline CI workflows exact SHA, named domain tests fully executed, implementation/documentation admission | Baseline qualified / optimization pending |

**Quantity R1 outcome:** All 12 review *questions* assessed and named service-domain gaps documented. **This does not close** production admission, valuation, multi-location split picking, per-warehouse ACL, checkout SKU snapshot and independent tenant-wide security qualification. Service-focused quantity maturity should be treated as **3/5**, not 5/5.

## 4. Decisions, no over-engineering

- **KEEP:** one inventory balance aggregate, immutable movement journal, dual SALES/INVENTORY transaction-bound permissions, request-key and deterministic stock lock protocols, bounded physical stocktake.
- **FIX NOW:** only source-proven locking contention and first-receipt uniqueness race; require the new PG compatibility tests and populated migration regression.
- **MEASURE NEXT:** repeat 1-SKU vs 8-SKU 8-worker probes, 2+ warehouses and multiple actors, lookup lock waits `pg_stat_activity`/`pg_locks`, `EXPLAIN (ANALYZE, BUFFERS)` for movement queries on large synthetic ledger, database pool exhaustion; compare median/p95/p99 across at least several controlled runs.
- **DEFER:** PostgreSQL-specific advisory locks, tenant-scoped request stripe migration, new external queue/event bus, partitioning inventory ledgers, new pricing/valuation engine or throughput target without a business-defined SLA.

## 5. Handoff to SALES / AutoOrder

The separate [SALES 12-pass spec](../spec/RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md) already covers source-review passes 01–07. Do not create a competing duplicated TЗ. After optimized INVENTORY exact-SHA PostgreSQL qualification, reuse the same cross-domain Q9 contracts to resume SALES-specific pass 08 (provider/consumer and immutable SKU/price snapshot), then 09–12 security, throughput and release gates. This document does not assert completion of SALES.
