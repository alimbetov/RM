# RM SALES-INV-R1.2 — RBAC protected seller order + warehouse commands

**Date:** 2026-11-21. **Branch:** feat/sec-r1-unified-domain-authorization. **PR:** #2.
**Status:** internal application services, H2/PostgreSQL qualification required. No public OIDC endpoint, payment/financial COGS, or production admission.

## 1. Architecture

- `SalesInventoryCommandService`: seller confirmation, cancellation and one-shot fulfillment. Order status change + inventory reservation or stock debit + immutable order/reservation/movement history commit in **one database transaction**. One order = one tenant; R1.2 uses one warehouse for all order lines.
- `InventoryAuthorizedCommandService`: RBAC entry point for quantity-only receiving and stock correction. It deliberately does **not** expose order-linked reserve/release/fulfill; those require the atomic SALES orchestrator. Existing `InventoryReservationService` and `InventoryMovementService` are low-level **internal persistence orchestration**. They must never be exposed as HTTP routes or worker API without the checked facade.
- `TenantAuthorization`: requires active user, workspace and membership, plus per-tenant functional permission. Existing unconfigured tenants may read existing data but new write operations deny until RBAC is enabled and granted.
- Two independent checks protect crossing SALES/INVENTORY boundaries: confirming requires `SALES_ORDER_CONFIRM` and `INVENTORY_RESERVE`; cancelling requires `SALES_ORDER_CANCEL` and `INVENTORY_RELEASE`; fulfilling requires `SALES_ORDER_FULFILL` and `INVENTORY_FULFILL`.
- Standalone `receive` / `correct` require `INVENTORY_RECEIVE` / `INVENTORY_ADJUST`. Reservation, release and fulfillment require **both** domain permissions through the SALES application service. These permissions are explicit enum entries; no wildcard `MANAGER` access.
- **No new SQL tables** in this slice: existing `sales_order_status_event`, `inv_stock_reservation`, `inv_reservation_event`, `inv_stock_movement` are reused.

## 2. Order state transitions (internal)

| Operation | Allowed original state | New state | Inventory side-effect |
|---|---|---|---|
| confirm | PLACED | CONFIRMED | reserve **all** order lines (offer must have catalog SKU, enough stock); rollback all on failure |
| confirm retry | CONFIRMED | unchanged | verify complete active reserves and same warehouse, no extra event |
| cancel | PLACED/CONFIRMED/PREPARING | CANCELLED | release all ACTIVE reserves, reject if any fulfilled |
| cancel retry | CANCELLED | unchanged | no extra event |
| fulfill | CONFIRMED/PREPARING | COMPLETED | fulfill all ACTIVE reservations; one stock debit and movement per line |
| fulfill retry | COMPLETED | unchanged | no extra event |

No cancellation after shipment, completion, or rejection. Idempotent retries of CANCELLED/COMPLETED additionally verify that active/fulfilled reservation counts match the terminal state. Completed order is a **minimal vertical milestone** only: do not interpret it as financial settlement, tax issuance, delivery evidence, or confirmed cost of sales. Real transport/delivery progression requires separate states and controlled transitions.

## 3. Concurrency

All mutating reservation methods use unified lock order **sales order row → inventory request stripe → stock balance row → reservation row**; the SALES orchestrator starts from the same order row. PostgreSQL row locks serialize competing updates and prevent over-reserving stock. Unique `(tenant_id,order_line_id)` allows at most one reservation per line. Unique `(tenant_id,reservation_id)` movement index makes fulfillment write-once.

`row_version` increments on state changes. Returns are transactional: a failed reservation rolls back order transition, reservation updates and all history events.

H2/PG E2E must test concurrent orders on same SKU, repeated commands, failure/rollback, unauthorized actor, tenant isolation, partial grant, and immutable evidence.

## 4. Limitations and next gates

- No OIDC/BFF principal binding yet. All `actorId` values must originate in a trusted adapter, **never** HTTP user input. No public endpoints.
- Existing low-level stock service methods still permit internal callers without actor context. Before production, expose only permission-checked ports, implement module boundary tests and restrict bean usage/entry point visibility.
- Inventory receipt is quantity-only, not cost accounting. No FIFO/moving-average layers, FX or COGS ledger; remain FINANCE production HOLD.
- A confirmation cannot currently split one order line across warehouses, and seller cannot partially fulfill an order; add in later R2 with explicit allocation.
- Cancellation is seller-initiated; buyer cancellation, outbox, fulfilment/recovery worker, delivery proof and settlement remain separate release gates.
- Revocation of a permission after first auth check and before transaction commit is not transactionally fenced yet. SEC-R1.2 must add revocation-aware transaction admission and multi-process E2E.
- Do not merge PR #2 or enable production until test results and security review are complete.
