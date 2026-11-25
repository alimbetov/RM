# RM-QR-05/06 — Verified Preparation, One-Time Pickup and Exactly-Once Inventory Fulfillment

**Source:** RM-QR-R1.4 canonical UC-QR-06 and QR-05/06.
**Branch:** base_dev. **Status:** implementation candidate pending exact-SHA PostgreSQL admission.
**Transport freeze:** no controllers, public URLs, device scanner, QR graphics, offline completion or branch main modifications.

## QR-05: line-verified preparation

Existing SALES confirmation via SalesInventoryCommandService.confirm remains the only trusted way to move PLACED to CONFIRMED and fully reserve lines on one active tenant warehouse. No shadow inventory tables.

SalesOrderPreparationService adds three commands:
- startPreparing(staff, tenant, orderId, warehouseId, expectedRowVersion, requestKey): CONFIRMED → PREPARING only if exactly one full ACTIVE reservation for each immutable sales order line at that specific warehouse.
- confirmPicked(staff, tenant, orderId, warehouseId, lineId, fullQty, expectedRowVersion, requestKey): PREPARING only, records one append-only, immutable proof of the FULL quantity for that sales line and matching SKU/location; increments order rowVersion for CAS. No partial/split picking in R1.
- markReadyForPickup(...): PREPARING → READY_FOR_PICKUP only after EVERY line has one matching pick confirmation at the reserved warehouse. No stock debit until QR-06.

A durable seller-tenant preparation receipt stores actor, warehouse/order/line/action, expected order version, fingerprint and operation-time result. Exact requestKey retries return historical result and mismatch is rejected. Status transitions append existing SALES status events. Stock on_hand/reserved remains unchanged on all QR-05 operations.

IAM: fresh SELLER/tenant SALES_ORDER_CONFIRM and INVENTORY_STOCK_READ permits PLUS explicit current warehouse assignment. A revoked grant blocks new commands and replays. Data source: exact tenant/line SKU snapshot, inventory reservations, org_location WAREHOUSE ACTIVE. No invented stock availability.

## Explicit warehouse scope

public.iam_warehouse_staff_scope is a non-commercial IAM authorization scope, not stock data or a second staff identity. It binds an ACTIVE organization member to one concrete warehouse with composite tenant FK. WarehouseStaffScopeService.assign/revoke requires non-delegable TENANT_RBAC_MANAGE (organization OWNER), with audit and row lock fencing. Every QR-05 and QR-06 staff command requires the scope row and ACTIVE org_location row under transaction locks. Tenant RBAC alone is not enough to handle a different warehouse.

## QR-06: one-time credential

- ONLY global verified buyer of the exact SALES order can call issue(buyer, ownedOrderRef, requestKey) while order is READY_FOR_PICKUP and full picked/reserved. Buyer ownership comes from existing SalesOrderQrReferenceService.readOwn, not the copied order reference alone.
- A new 256-bit random base64url raw proof is returned ONCE; persisted only as a purpose/tenant/warehouse-bound SHA-256 digest. Raw proof and buyer PII never enter the database, audit or logs.
- UAT policy TTL is 10 minutes, not production-approved; issuance of a new generation revokes prior unconsumed ISSUED proof under order FOR UPDATE. Same issuance requestKey cannot recover raw secret: PICKUP_PROOF_RECOVERY_REQUIRED; use a fresh requestKey to revoke and reissue.
- preview(staff,tenant,warehouse,rawProof) validates current IAM SALES_ORDER_READ + INVENTORY_STOCK_READ, specific warehouse assignment, current order READY, credential digest/state/expiry and full picking. It is read-only with regard to order/stock/credential; preview is NOT an issue or a transfer of goods.
- complete(staff,tenant,warehouse,rawProof,explicitConfirm,expectedOrderVersion,requestKey) needs fresh SALES_ORDER_FULFILL+INVENTORY_FULFILL paired decisions and warehouse scope. Without affirmative staff confirmation it rejects. A current proof is checked by PostgreSQL clock_timestamp() (not transaction-start or previous scan time).
- In ONE transaction: lock SALES order -> check current warehouse scope -> lock credential -> assert READY/full ACTIVE reservations and every pick proof -> post EACH reservation via authoritative OrderInventoryPort.fulfill -> update SALES COMPLETED/version -> append single status event -> insert immutable handover receipt and one proof per fulfillment movement -> mark token CONSUMED -> audit.
- Every exception rolls back ALL preceding movements, balances, reservations, order status, receipt, credential state. Complete replays with identical key/actor/token/orderVersion return SAME receipt from DB, with no new inventory movement. Different request keys against consumed proof cannot create another order or stock movement. There is NO network exactly-once promise.
- Ledger remains inventory-owned, unique by reservation and request_key. Explicit handover receipt is not inferred retrospectively for pre-QR COMPLETED orders.

## DB / migrations

0092: IAM warehouse scope, append-only per-order line pick proof and durable preparation operation receipt.
0093: time-limited single-active-per-order pickup proof, hashed digest with one generation history; immutable full-order handover and line→reservation→inventory movement references, unique order and request key.
0094: PostgreSQL triggers deny UPDATE and DELETE of pick evidence, preparation receipts, handover and handover lines.

## Required evidence and failure injection

- CONFIRMED requires full ACTIVE reserve on chosen warehouse; another warehouse, missing or incomplete reservations or inaccurate picked qty deny; no debit before handover.
- READY requires one immutable pick proof per order line; same requestKey returns exact result, changed intent conflicts; no status event duplication.
- Only verified order owner can issue proof; no plaintext token persistence; token lifetime, revoked generation, foreign warehouse and suspended/revoked staff fail closed.
- Preview leaves token ISSUED, order READY and full reserved balance untouched.
- Two simultaneous scanners with different keys → ONE receipt/COMPLETED event, one FULFILLED movement per reservation, on_hand - qty and reserved zero.
- Lost response / exact duplicate after successful COMMIT → original receipt returned, zero new stock movement.
- Real PostgreSQL injected failure in final reservation movement → previous successful inventory fulfill calls all roll back, no credential consumption, no handover, order still READY. Authorized retry after fault removal succeeds.
- Real PostgreSQL immutable-receipt tampering attempts fail. Run H2 migration, PG real tests, existing SALES/INVENTORY IAM regression, populated migration and exact-SHA manifest.
- Runtime p95/p99, real OIDC/JWKS, signed canonical URL/domain, QR center circular badge phone scans, cross-pod lock failure injection and real staff floor acceptance stay HOLD.

**Commercial gates:** ten-minute TTL and full-line confirmation semantics are provisional UAT parameters. No show/no-show cancellation and return (RMA) until separate qualification. Staff authority is explicitly scoped by warehouse but no additional bin/aisle/granular physical-zone enforcement is claimed.
