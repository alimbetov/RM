# RM QR-08/R0–R1 — Packaging Foundation

**Branch:** `base_dev`. **Status:** service-first implementation specification, **not a claim of implemented/qualified code**. QR-07/R2.5 evidence and full PG regression are prerequisites for code admission.

## Domain boundaries

Packaging is a seller service attached to **one existing** `crm.sales_order`. Never create another order/checkout/stock copy, never treat a service as sellable catalog stock. Preserve immutable tenant/order/SKU/price/currency/tax/policy snapshots on accepted service request.

Two separate concepts:

- `OrderServiceLine` (accepted packaging service): tenant, order, service catalog code/version, nominal price/currency/tax, customer choice, warehouse, selected material SKUs and quantities.
- `PackagingExecution` (warehouse work): PENDING → IN_PROGRESS → COMPLETED or CANCELLED with evidence, employee, timestamp, revisions and unique idempotency keys.

## Inventory material consumption

Physical packaging materials are **catalog items with stock**, but the packaging service itself is not a stock SKU. Only once packaging is actually completed, atomically debit precisely its approved material quantities in the original fulfillment warehouse with an immutable inventory movement linked to `PackagingExecution`. Do not reserve/debit before customer accept; reserve consumables separately if needed to guarantee availability. Never silently use `InventoryMovementService.correct` for consumption. Introduce a dedicated INVENTORY usage port and movement reason, validated at the source and following canonical inventory lock ordering.

Before recording completion:

- tenant-scoped sales package approval and exact order state admitted;
- active staff membership, warehouse scope, packaging-specific permission and inventory permission;
- all material SKUs active and physical quantities validated, `on_hand-reserved>=required`;
- idempotent request-key and unique movement references per service+material line;
- joint transaction: inventory debit(s), job completion, receipt and event;
- failed debit or IAM revocation must roll back all changes, with no partial kit consumption.

## R0 milestones

1. Liquibase: `sales_packaging_service_catalog` (tenant-scoped snapshots) and `sales_order_service_line`, with single-order composite FK, price/currency checks, accepted revision and sealed records.
2. Java `PackagingServiceContract` and state enum; no controller.
3. R0 service API: offer, accept, reject/cancel (before work), read authorized package status. Do not couple to QR-05 transition handler.
4. PostgreSQL and H2 migration fixtures plus 20+ positive/negative service tests covering tenant isolation, stale versions, repeated acceptance, price drift and invalid material combinations.

## R1 milestones

1. Explicit packaging-material requirements and warehouse job assignment; no hidden stock debits.
2. Service-only start/complete/cancel operations and append-only evidence.
3. Atomic inventory consumable posting using original inventory ledger, with SKU/location/qty/request key and source relationship.
4. PostgreSQL rollback, retry after COMMIT, two-JVM race, mixed stock correction / transfer, warehouse closure and concurrent IAM revoke; assert exactly-once debit and zero nonphysical SKU movements.
5. Per-phase performance and throughput with high-contention package preparation, separate from QR-06B pickup throughput.

## Commercial and operational admission

Price, tax, service catalog, returned packaging waste, environmental recycling rules and cancellation/refund method need separate policy approvals. Actual scanner/proof, external OIDC, HTTPS and real staff devices remain production HOLD. Packaging service can be implemented with service-layer synthetic actors before those external integrations, but no shipping or financial posting should be inferred.

**Precedence:** finish QR-07/R2.5 correctness and full PG results before announcing QR-08/R0 code PASS.
