# DEC-FUL-01 — Durable execution-channel admission for RM Business and QR Commerce

**Date:** 2026-11-23. **Branch:** `base_dev`. **Status:** IMPLEMENTED FOR SERVICE UAT / FINAL EXACT-SHA AND BUSINESS ADMISSION HOLD.
**Scope:** existing SALES order + INVENTORY ports only. No new engine, controllers, UI or TASKS.

## Decision

The same `crm.sales_order` aggregate supports both RM Business and RM QR Commerce.
A stable, server-written `fulfillment_mode` determines the authorized *completion* path:

| Value | Source | Allowed completion |
|---|---|---|
| `STANDARD` | Existing normal Business checkout and historical orders (migration default) | Existing `AuthenticatedSalesCommands.fulfill` subject to current IAM and reservation rules |
| `PICKUP_PROOF_REQUIRED` | `AutoOrderService.checkoutPublishedQr` (public published QR) and `checkoutAcceptedQuote` (negotiated QR) | Only `SalesPickupHandoverService.complete` after `READY_FOR_PICKUP`, valid buyer-issued one-time proof, warehouse staff assignment, IAM permission, exact reservation and immutable handover receipt |

This is an **execution policy, not a second sales order model**. A public product QR or order reference is never a pickup credential.

`SalesInventoryCommandService.fulfill` rejects `PICKUP_PROOF_REQUIRED` **before** it performs a stock movement, including at `CONFIRMED`, `PREPARING`, and on an attempted generic replay after `COMPLETED`. The order row is locked before mode admission. Existing stock reservations remain under INVENTORY ownership.

`cancel(PREPARING)` rejects an order when any append-only `sales_order_pick_confirmation` exists. A reverse-pick workflow is **not** implemented or inferred. The rejection does not release reserved stock, alter status, or append business events.

## Database and migration requirements

- `0121-fc40-fulfillment-channel-policy`: non-null `fulfillment_mode`, default `STANDARD`, restrictive CHECK; historical rows remain visible and compatible.
- `0122-fc40-fulfillment-mode-immutable` (PostgreSQL): trigger prevents updating mode after order creation, including downgrades from pickup-proof-required to standard.
- Historical PostgreSQL changesets `0105` and `0120` retain their SQL bodies. H2-only logical equivalents `0105h2` and `0120h2` provide dialect compatibility; do not disable H2 tests or edit historical PostgreSQL indexes.
- Populated PostgreSQL upgrade must prove Liquibase checksum compatibility and preserve existing SALES/CRM/INVENTORY records. An ordinary green clean-schema test does **not** prove that upgrade.
- **Historical provenance caveat:** a pre-migration order may have originated from a QR checkout, but existing order rows do not always record a reliable distinction from standard checkout. Defaulting historical rows to `STANDARD` preserves compatibility but is **not** evidence of retrospectively secure QR classification. Before admitting a populated production migration, inventory all outstanding historical orders; classify or quarantine ambiguous unfinished QR-origin orders under a separately approved data migration. Do not infer QR ownership from the existence of `ORDER_REFERENCE`: standard checkout also creates one.

## Proof targets

1. Standard checkout persists `STANDARD`; published QR and negotiated quote persist `PICKUP_PROOF_REQUIRED` atomically with order and receipt.
2. Generic `fulfill` denied before pick for proof-required order; zero stock debit, receipt, order event or reservation release.
3. Generic `fulfill` denied after pick and for a QR order already completed through the secure handover.
4. PostgreSQL cannot update immutable fulfillment mode.
5. Existing secure pickup still enforces READY, fresh credential, tenant/warehouse membership, idempotence, exactly-once stock posting and immutable receipt.
6. `cancel(PREPARING)` after pick cannot silently release physical items; check both transaction orders for cancel × pick.
7. PostgreSQL clean and populated migration, module H2, domain-security PostgreSQL, selected QR routes, full regression and exact-SHA evidence manifest all PASS at the same candidate SHA.

## Remaining decisions and gate status

- `DEC-FUL-01`: code implemented; FINAL SERVICE PASS remains HOLD until the above evidence passes at a common exact SHA and historical QR migration classification is reviewed.
- `DEC-LOC-03`: operation-scoped warehouse/location authority for generic SALES confirm/fulfill remains OPEN. The secure pickup path already enforces warehouse assignment; this document does not grant broad tenant-wide staff privileges.
- `DEC-SAL-02`: conservative deny-after-pick implemented. Audited reverse-picking is deferred.
- External real OIDC, physical scanner, invoices, payment, shipping, QR-08/R1 packaging material consumption, UI and production admission remain separate STOP/HOLD gates.

Never mark these assumptions as production-accepted merely because the capability registry maps source methods to test files.
