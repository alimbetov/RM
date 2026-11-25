# RM — QR-07 / QR-08: Returns & Services, Service-First R1

**Date:** 2026-11-22  
**Branch:** `base_dev`  
**Status:** implementation-ready design proposal; **NOT IMPLEMENTED, NOT ADMITTED**.  
**Prerequisite:** complete QR-06B B3.5 sustainable-capacity qualification, external identity/network/device admission where required, and maintain production HOLD until separately approved.

## Confirmed boundaries in current code

- `SalesPickupHandoverService.complete` is the source of truth for physical handover; order becomes `COMPLETED` only after authorized fulfillment, stock movement, pickup credential consumption, immutable proof/receipt and event in one PostgreSQL transaction.
- `SalesInventoryCommandService.cancel` explicitly prohibits cancellation of `COMPLETED`, `READY_FOR_PICKUP`, `OUT_FOR_DELIVERY`, `REJECTED`. **A return must NOT be implemented as cancel() or by changing completed sales history back to a pre-handover state.**
- Inventory `receive`, `correct`, `writeOff` and `transfer` are authorized warehouse movements; **a return is not an arbitrary stock correction**. Returns need their own provenance and conservation requirements.
- One order belongs to one seller/tenant. Retain tenant-qualified references, immutable line/SKU snapshots and exact originating pickup receipt for each return or post-sale service.

## QR-07 — Returns & Claims

### Contract and states

Implement a separate `ReturnRequest` aggregate with `ReturnLine` and append-only `ReturnDecisionEvent`. Proposed states:

`REQUESTED → UNDER_REVIEW → APPROVED | REJECTED`,  
`APPROVED → AWAITING_RECEIPT → RECEIVED → INSPECTED → RESTOCKED | DISPOSED`,  
or `APPROVED → CANCELLED_BY_BUYER` only if nothing has been received.

Do **not** restore inventory at `REQUESTED`, `APPROVED` or `AWAITING_RECEIPT`. Physical receipt and inspection are separately authorized events. Partial returns per order line are allowed; every return quantity must remain bounded by `original fulfilled quantity − already accepted/processed return quantity` under concurrent requests.

### Minimal service operations

1. `ReturnRequestService.request`: verified buyer and exact original completed order/line snapshot, reason code, evidence attachment references, idempotency key, return quantity and policy version.
2. `ReturnDecisionService.review/approve/reject`: seller-scoped actor, configurable eligibility policy, append-only events, and protected buyer/seller role separation.
3. `ReturnReceptionService.receive`: warehouse-scoped staff + warehouse match, one-time receipt evidence, check `APPROVED` and quantity, record immutable physical receipt, **no stock increase until inspection confirms disposition**.
4. `ReturnInspectionService.inspect`: disposition `RESTOCK`, `QUARANTINE`, `WRITE_OFF`; attach signed decision and provenance.
5. `ReturnStockPostingService.post`: precisely-once inventory movement from inspected return into sellable stock or designated quarantine location; preserve original inventory movement history and immutable return-to-pickup linkage.
6. `ReturnCaseQueryService`: tenant-authorized views by buyer, seller, warehouse and auditor, without leaking another tenant's order or PII.

### QR-07 P0 test matrix

- Exact fulfilled quantity, partial return, double request, competing partial returns, mixed SKU/warehouse, and over-return attack.
- Received-before-approved, restock-before-inspection, duplicate physical scan, retry after SQL rollback, cross-JVM duplicate posting.
- Revoked/suspended IAM account and warehouse scope during receive/inspection, both commit orders.
- Cross-tenant reference poisoning, immutable source receipt tampering, legacy orders without receipt.
- 100/250 mixed return + fulfillment + transfer concurrent PostgreSQL E2E, with stock conservation and no credit/refund double-posting.

Financial refund/exchange must be a **separate independently qualified integration contract**; do not create fictitious payment posting or imply a payment provider exists.

## QR-08 — Delivery, Packaging and Add-on Services

Keep **service offers/fulfillment** separate from physical inventory and from CRM contract identity. Introduce tenant-scoped `OrderServiceLine` snapshot and `ServiceFulfillment` ledger; service SKUs must not create or consume warehouse stock unless explicitly backed by a physical catalog item.

### Service families

- **Packaging:** packaging type, size, material, quantity, price snapshot, consumable SKU only when actual stock tracking is enabled, preparation checklist and immutable completion evidence.
- **Delivery:** address snapshot and consent, route/recipient restrictions, delivery assignment, handoff proof distinct from `SalesPickupHandoverService`, loss/failure/return-to-sender transitions and optional provider reference; no invented courier integration.
- **Other add-ons:** installation, assembly, warranty, service appointments. Time-slot reservation, responsibility, seller-assigned staff, cancellation policy and completion evidence; no implicit financial settlement.

### Contracts and state machines

`OFFERED → ACCEPTED → SCHEDULED → IN_PROGRESS → COMPLETED`, or allowed `CANCELLED/FAILED` transitions. Store historical price/tax/currency and method-version snapshots per service line. For delivery, define dispatch and recipient acceptance as **two separate facts**, and never mark customer receipt based only on dispatch.

### QR-08 P0 test matrix

- Multi-line order with optional packaging+delivery; per-line opt-in/out; confirmed historical price does not drift.
- Package availability versus physical consumables; out-of-stock rollback; single consumption.
- Delivery reassignment, failed attempt, reschedule, proof replay, duplicate delivery confirmation.
- Cross-tenant courier/staff, lost response after COMMIT, customer cancellation versus warehouse shipment concurrency.
- Failed installation/service, partial work, no double charging, immutable event history.
- Real provider integration, mobile scanner and HTTPS remain separate admission gates.

## Implementation sequence and definition of done

| Stage | Deliverable | Required gate |
|---|---|---|
| QR-07/R0 | ADR, schema and policy/state contracts, test fixtures | Java service-only; tenant-qualified FK; migration test |
| QR-07/R1 | Request/review/approve/reject | 20+ behavioral service tests; PG RLS/IAM negative |
| QR-07/R2 | Receive/inspect/disposition/stock posting | PG transactional, idempotent, crash/retry and 2-JVM gate |
| QR-07/R3 | Refund/exchange integration boundary and audit | No imaginary settlement; separately authorized contracts |
| QR-08/R0 | Service catalog, immutable `OrderServiceLine`, scope/policy | Tenant/price/SKU snapshot tests |
| QR-08/R1 | Packaging and preparation | Physical consumable inventory invariant tests |
| QR-08/R2 | Delivery lifecycle | Real provider/scanner admission separate from synthetic tests |
| QR-08/R3 | Installation, appointment, warranty | Scheduling/authorization/E2E matrix |

**Architecture rules:** no new controller before service acceptance; do not duplicate orders, product catalog, inventory balances, IAM global users, or fulfillment receipts. Use existing namespaces and ownership boundaries. Liquibase migrations and PostgreSQL integration tests must be part of each executable release candidate. R1 design is not a claim of completed implementation.

## Release policy

QR-06B B3.5 is the immediate engineering gate; closing it does **not** automatically approve QR-07/08 or production. QR-07/08 service design can proceed while external OIDC and network/device gates remain unresolved, but no production promises or backend controller expansion should be made until their own service correctness admission.
