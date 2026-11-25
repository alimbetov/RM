# CRM-R2.2B — Link Lifecycle & Dispute Resolution PostgreSQL Evidence

**Date:** 2026-11-22. **Repository/branch:** `alimbetov/RM` / `base_dev`.
**Qualified exact source SHA:** `a7d4ac44482f22cba6ba2f52451ef6d4ff773e75`.
**GitHub Actions:** https://github.com/alimbetov/RM/actions/runs/45049503215 — completed **SUCCESS**.
**Job:** `137291279671` (`buyer-link-postgresql`).
**Surefire artifact:** `crm-r22-buyer-postgresql-45049503215` (artifact ID `14764584315`).
**Test system:** PostgreSQL 17, Java 21, service layer; controllers and UI unchanged.

## Executed qualification

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| CrmR1aQualificationTest | 37 | 0 | 0 | 0 |
| CrmBuyerLinkQualificationTest | 12 | 0 | 0 | 0 |
| CrmInteractionQualificationTest | 19 | 0 | 0 | 0 |
| **Total** | **68** | **0** | **0** | **0** |

## Business contract

- States: `PENDING → CONFIRMED → DISPUTED → REVOKED`, plus `PENDING/CONFIRMED → REVOKED`. Terminal `REVOKED` is never restored.
- `revokeByBuyer`: only verified target buyer can terminate their link.
- `releaseBySeller`: requires `CRM_WRITE`, does not approve consent or transfer the link to another buyer.
- `disputeByBuyer`: only the verified buyer of a **CONFIRMED** link; suspends cross-domain order-history projection immediately.
- `closeDispute`: seller-only fail-closed resolution to `REVOKED`. Re-approval must use a separate new buyer-consented link.
- `propose`: tenant + request-key idempotent. Old revisions are retained, but active one-to-one link uniqueness blocks conflicting mappings.
- `linkHistory`: `CRM_READ`-authorized ordered append-only event history across all link generations.
- `orderHistory`: requires `CRM_READ` + `SALES_ORDER_READ`; only `CONFIRMED` link and active customer yields seller-tenant orders. Neither revoked nor disputed link reveals orders.
- No replication of IAM identity or mutation of SALES order buyer ownership.

## Database

`0105-crm-r22b-lifecycle-dispute` extends states and event action codes, drops unconditional per-tenant unique customer and buyer constraints, and replaces them with PostgreSQL partial uniqueness on **active** link states. Uniqueness of `(tenant_id, request_key)` remains unconditional. Event table remains append-only with the existing PostgreSQL immutable-history trigger; all rows from prior generations remain available for audit.

## PostgreSQL concurrency qualification

The 12 buyer link tests cover seven previously qualified R2.2 tests and five additional lifecycle tests. One new test explicitly orchestrates both `confirm × revoke` transaction orderings with independent PostgreSQL transactions, deterministic commit barriers, CAS version checks, terminal state expectations, and event counts. Existing two-thread tests also exercise duplicate proposals and duplicate buyer confirmation.

## Evidence boundaries / unresolved work

**CRM-R2.2B initial service-and-concurrency slice: GREEN for the exact SHA above.**
This scope does not establish global platform production admission.

**Open contracts:** independent adjudication of disputed identity claims (e.g., binding evidence to a reviewer, appeals or arbitration), policy on historical orders before linkage confirmation, dedicated cross-tenant multi-org authorization stress, buyer account suspension/deletion interplay, and migration against already-populated production-like link tables. In the current fail-closed implementation, seller can only close a dispute by **revoking** the mapping; there is no disputed-link restore or automatic matching.

**Controller/UI:** STOP. **Production:** HOLD. **No merge to main.**
