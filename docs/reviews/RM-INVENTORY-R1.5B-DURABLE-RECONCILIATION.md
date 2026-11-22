# INV-R1.5B — Durable tenant-wide reconciliation

**Branch:** `base_dev`  
**Scope:** quantity-only inventory diagnostics; production admission **HOLD**.  
**Qualification:** pending exact-SHA H2/PostgreSQL/populated-DB CI.

## Contract

- `AuthenticatedInventoryReconciliationRuns.start(VerifiedActor,tenant)` creates a tenant-local run in `RUNNING`.
- `advance(actor,tenant,runId,pageSize)`: `1..20` unioned (balance, reservation, movement) SKU/location keys per transaction; deterministic keyset cursor. Persist findings and checkpoint atomically.
- `get`, `findings(page,size<=100)`, `fail` require an IAM-fenced `INVENTORY_STOCK_READ` permission; run and finding lookups always include tenant.
- `RUNNING -> COMPLETED` when no next SKU/location key; `RUNNING -> FAILED` on explicit operator abort. Unknown run is not found. Repeated `advance` after a terminal state does not mutate evidence.
- Every position's movement chain is read in ascending `recorded_at,id` sequence with JDBC fetch size 256 and *no movement count limit*. Verify `after-before=delta`, adjacent `before/after`, and current `on_hand` equals the last recorded balance. Independently compare `reserved` with sum of ACTIVE reservation quantities.
- Codes: `MISSING_BALANCE`, `MOVEMENT_CHAIN_BREAK`, `RESERVED_MISMATCH`, `MOVEMENT_BALANCE_MISMATCH`, `NO_MOVEMENT_BASELINE`. Currently only the highest-priority code is stored per SKU/location/run; there is no auto-repair.
- Durable metadata tables only: `crm.inv_reconciliation_run`, `crm.inv_reconciliation_finding`. Zero writes to balance, reservation, movement, order, or organizational location state.

## Correctness boundaries and remaining gates

1. **Cross-page consistency:** `COMPLETED` proves the cursor reached the end of the observed keyset, not an atomic tenant-wide as-of snapshot. Concurrent inserts/updates between pages may change balances, create keys before the cursor, or produce false or missed findings. An independent snapshot/watermark protocol is required for production-grade certified reconciliation under concurrent trading.
2. **Single-key streaming:** Java heap is bounded by the JDBC result traversal, but transactions and SQL execution time are not bounded for very long SKU histories; benchmark and consider persistent movement-level progress before production.
3. **Ordering:** Historic ledger does not have a per-position monotonic sequence number. The order `recorded_at,id` is deterministic, but UUID tie-breaks do not prove actual ordering when timestamps collide. A chain-break finding on tied timestamps may need adjudication.
4. **Historical baseline:** Older imported balance rows can have no movement evidence. `NO_MOVEMENT_BASELINE` is unresolved, not proof of corruption. First-event opening balance need not be zero.
5. **Error handling:** Exceptions roll back the current batch and leave the run `RUNNING`; the operator can retry or explicitly mark it `FAILED`. Crash recovery/lease expiry and periodic scheduling are not implemented.
6. **Evidence tests:** checkpoint replay, rollback, foreign-tenant denial, findings pagination, 502-event synthetic chain, and CI on a single SHA. Do not call this production GREEN before workflows complete.

## Explicit exclusions

No auto-fix, cost valuation, stock write commands, controller, new microservice, scheduler, or merge to `main`. The new tables store diagnostic evidence, never another source of truth for quantities.
