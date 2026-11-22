# RM QR-08/R0 — Packaging Service Catalog: implementation admission

**Date:** 2026-11-22. **Branch:** `base_dev`. **Status:** R0 design/QA admission, executable implementation NOT YET QUALIFIED.
**Scope:** service layer + PostgreSQL/H2 migrations + tests. No controllers, UI, shipping, stock debit or production admission.

## Upstream QR-07 evidence

- Exact SHA: `6a06f8119f0d7959b1f49e371a719dec352b42e7`.
- Run: https://github.com/alimbetov/RM/actions/runs/45049455205
- Both `qr07-r0-postgresql` and `qr07-full-postgresql-regression` completed SUCCESS at the same SHA.
- This is **QR-07 SHA-scoped PostgreSQL technical acceptance**, not integrated branch-HEAD or production approval.
- Additional CRM commits on `base_dev` require qualification as part of the later integrated gate.

## Boundaries and source of truth

- Existing `crm.sales_order` owns the only order. No packaging-order copy.
- Service catalogue is SALES-owned, tenant-scoped, append-only **published version**. A mutable current-version pointer is permissible, but published rows remain immutable.
- Accepted packaging requests are separate SALES `OrderServiceLine` records referencing an existing `crm.sales_order` by composite `(tenant_id,order_id)` foreign key.
- Commercial snapshots hold immutable catalogue code/version, unit price, currency, tax representation, policy version and accepted quantity. Snapshot must not be reconstructed from latest catalogue prices.
- `PackagingExecution` and physical consumables belong to R1 and MUST NOT be posted by R0.
- Use only trusted `VerifiedActor` (never arbitrary client-supplied actor IDs), `TenantAuthorization.requireForCommand`, buyer-ownership checks through the existing `SalesOrderQrReferenceService`, and row locks following IAM → SALES order → order service line.
- Service choices must be tied to a real order lifecycle: allow offer/accept only for explicitly admitted nonterminal states, and reject once physical execution/preparation makes the choice unsafe. Do not silently revise completed or cancelled orders.

## R0 implementation work packages

1. **R0A — catalogue migration and service:** tenant + code + strictly increasing version, immutable published snapshots, valid monetary precision and currency, permissioned publish/deactivate, replay keys, concurrency-safe version publication.
2. **R0B — order service line:** seller offer + buyer accept/reject and pre-execution cancellation, order-owner verification, state/revision fencing, single-use immutable command receipt, no double acceptance, SQL FK enforcement.
3. **R0C — qualification:** PostgreSQL migration on clean and populated database, H2 compatibility, adversarial tenant/buyer/IAM cases, concurrent offers/acceptance, catalogue price drift, replay conflicts, rollback and crash/retry.
4. **R0D — exact-SHA gate:** targeted PostgreSQL service suite and whole-project PostgreSQL regression must both pass on the same source SHA; publish evidence manifest before R0 PASS.

## Minimum R0 invariants

- One order owner and one tenant scope for all R0 operations; foreign tenant/order combinations fail closed.
- Accepted quote snapshot is immutable even after price, tax, policy or catalogue head changes.
- Persistent request-key replay returns the original immutable command result; same key with different actor/payload is rejected.
- Exactly one winner in `accept × reject`, `accept × cancel`, concurrent duplicate offer, and stale revision races.
- IAM suspension or membership revocation cannot cross an admitted command transaction.
- R0 creates **zero** `crm.inv_stock_movement` records and never changes stock balances/reservations.
- No implicit payment, refund, delivery or external financial posting.
- Catalogue publication, order-line transitions and receipts have PostgreSQL uniqueness/FK/check constraints, not only Java validation.

## Required test families

| ID | Scenario | Required evidence |
| --- | --- | --- |
| PKG-R0-01 | valid catalogue publish and new version | old version remains immutable |
| PKG-R0-02 | invalid code, currency, negative price or incorrect precision | no rows committed |
| PKG-R0-03 | price/tax/policy drift after buyer acceptance | accepted snapshot unchanged |
| PKG-R0-04 | original buyer accepts own order | exactly one accepted line |
| PKG-R0-05 | foreign buyer/tenant attempts read or accept | denied; no information leak |
| PKG-R0-06 | stale revision and `accept × reject` competition | one terminal decision |
| PKG-R0-07 | request-key exact replay and conflicting replay | one receipt/event; conflict denied |
| PKG-R0-08 | catalog publish × deactivate/republish | valid ordering and immutable history |
| PKG-R0-09 | seller IAM revoke × offer | serialized decision with no unauthorized side effect |
| PKG-R0-10 | order cancellation/preparation × accept | no late acceptance |
| PKG-R0-11 | failure between snapshot and receipt | atomic rollback |
| PKG-R0-12 | full PostgreSQL regression at exact SHA | all required suites green |

Expand these into at least 20 independent positive/negative service tests before qualifying R0.

## Admission decision

QR-07/R2.5 focused + full PostgreSQL qualification **PASS at its exact SHA**. QR-08/R0 **IMPLEMENTATION HOLD** until executable R0A–R0C are committed and R0D evidence passes. QR-08/R1 (materials debit) remains STOP. Production HOLD.
