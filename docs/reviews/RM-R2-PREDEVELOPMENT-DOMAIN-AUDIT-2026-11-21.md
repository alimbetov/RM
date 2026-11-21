# RM-R2-PREDEV — Domain Integrity and User Workflow Audit

**Date:** 2026-11-21
**Audited code:** `main` merge `126e312e82791a68d689cbcbef287163858e7859` (PR #2, original reviewed HEAD `02c426dc5503f3e928e3b130c8475dbd884924bc`).
**Method:** targeted GitHub source review and previous CI evidence; **no live populated PostgreSQL access or fresh local test execution in this audit**.
**Decision:** open R2 corrective work; public/production admission HOLD.

## Findings (source-based, distinguish observations and verification needs)

| ID | Severity | Evidence / impact | Required correction and acceptance |
|---|---|---|---|
| R2-P0-01 | P0 | `InventoryMovementService.adjust(tenant, location, item, key, delta, Reason)` is **public**, while five-argument overload is package-private. This is an internal authorization bypass: caller can mutate stock without `TenantAuthorization`. | Make all raw mutation overloads package-private; architecture test enumerates **every** overload and ensures only authorized application façade is cross-package callable. PG negative test. |
| R2-P0-02 | P0 before HTTP | `TenantRoleService` role edits lock tenant row, but IAM user/membership/tenant status writers are not demonstrated to use the same admission barrier. `TenantAuthorization.requireForCommand` reads status after tenant `FOR SHARE`. | Inventory all status-changing entrypoints; lock workspace `FOR UPDATE` before status mutation; for a global user suspension affecting multiple tenants define deterministic lock order, or another equivalent fenced protocol; two-connection PG tests for command↔suspend, membership revoke and workspace deactivate. Never assume RBAC role revoke tests cover these. |
| R2-P1-03 | P1 | `InventoryMovementService.adjust` locks stock and checks its existence **before** looking up the immutable `request_key` movement. A valid replay can fail after a stock position is removed or changed. | Look up immutable request with request-key serialization before requiring mutable stock row (preserve deadlock-safe lock ordering); test replay after stock changes and payload mismatch. |
| R2-P1-04 | P1 | `OidcActorResolver` verifies configured JWT and ACTIVE user, but there is no trusted HTTP/BFF adapter and multiple legacy service methods accept raw UUID actor IDs. | Introduce a single verified application boundary for user-facing flows, no user-provided actor UUID. Test forged actor, tenant substitution, expired tokens. Controller implementation only after approval. |
| R2-P1-05 | P1 | `StockBalanceQueryService` uses separate COUNT/page queries; inventory and orders are mutable. | Document transient pagination, use stable snapshot/reconciliation only for export and financial-grade reports; no generic pagination framework. |
| R2-P1-06 | P1 | Order lifecycle is a narrow vertical: PLACED→CONFIRMED→COMPLETED or CANCELLED, warehouse allocation single location; no completed shipment/delivery/payment semantics. | Design explicit application use cases before adding states: seller confirms/cancels, warehouse receives/corrects/reserves, dispatch proof, buyer status. Keep settlement, COGS and invoicing out of scope unless separately contracted. |
| R2-P1-07 | P1 | Historic Liquibase changesets and new additive migrations exist; clean H2/PG CI cannot prove migration on populated DB. | Run populated PostgreSQL upgrade with snapshot/backup, checksums, row count/hash checks, error injection and PITR rehearsal; never rewrite applied changesets or delete tenant/audit data. |
| R2-P2-08 | P2 | Active docs previously claimed missing already-implemented security protections; PR #3 updates current docs but historical specs/reviews still mention Draft. | Keep historical reviews as versioned evidence; link canonical current baseline; add domain use-case contracts and mark not-implemented separately. |

## Required PostgreSQL invariants and reproducible evidence

Use a **synthetically populated** PostgreSQL 17 snapshot with two tenants, at least two users per tenant, multiple roles, several SKUs/warehouses, concurrent orders, active/released/fulfilled reservations and immutable events. If an operator supplies a sanitized production-like backup, do not delete/alter it; restore to isolated test DB.

1. Tenant isolation: every order, location, stock, reservation, movement, CRM contact and permission assignment belongs to exactly its specified tenant; cross-tenant JOIN/ID substitution denied.
2. Inventory arithmetic: every stock row satisfies `on_hand >= reserved >= 0`. Active reservation quantities reconcile to reserved stock within tenant/location/SKU, accounting for any separately documented non-order reservations.
3. Order-state mapping: CONFIRMED requires full active reservations; CANCELLED leaves zero active; COMPLETED requires full fulfilled reservations and exactly one fulfillment movement per reservation. State-machine exceptions must be explicit, never inferred.
4. Idempotency: retries with same payload return same immutable evidence, changed payload denies; no duplicate movement, audit or stock decrement.
5. Concurrency: same-order commands, two orders competing for same SKU, two multi-line opposite-SKU orders; no oversell, bounded deadlocks/retries where defined.
6. Access revocation: both admission-before-revoke and revoke-before-admission orders, at least two independent database connections; prove operation linearization and denied stale decisions.
7. Migration: clean install and populated upgrade; record `DATABASECHANGELOG` checksums, counts and evidence hashes before/after; backup restoration. Do not assert PITR without real rehearsal.

## Functional completion — prioritized user workflows

| Stage | Actor / workflow | End-to-end definition of done |
|---|---|---|
| R2.0 | IAM/admin: activate tenant, invite/link member, assign roles, suspend membership/user, restore | Changes are tenant-safe, audited, transactional, reflected in the next command; concurrent revoke proofs |
| R2.1 | Inventory clerk: initialize location/SKU balance, receive, correct, inspect stock and movements | Full authorization, idempotency, reconciliation and provenance; no raw service bypass |
| R2.2 | Seller + warehouse: place/confirm order, reserve, cancel/release, fulfill/debit, see history | Exact status, line quantities, stock evidence and retry behavior; no buyer/tenant data leakage |
| R2.3 | CRM/operator: create/find customer/contact, consent, role-sensitive PII lookup | Masking, cross-tenant denial, immutable consent trail and validated status transitions |
| R2.4 | Usability adapters: command/query contracts and one real OIDC principal path | Trusted actor, bounded search/pagination, full browser/client E2E after separate API approval |

**Scope control:** no Redis, no policy microservice, no generalized CRUD/DTO framework, no new financial ledger during stabilization. Add migrations only when a verified functional gap requires them.

## Release gates

- **Internal code integration:** targeted P0 fixes, H2+PG exact-SHA GREEN, architecture negative tests and independent review.
- **Public access:** additional real OIDC HTTP integration, account/membership deactivation fencing, PII/least-privilege DB acceptance.
- **Production:** populated DB migration and recovery, load/latency, observability, external E2E. CI GREEN alone never implies production-ready.
