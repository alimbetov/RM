# RM CUR-R2B — Seller FX Pricing Guardrails & Lifecycle Hardening

**Date:** 2026-11-23. **Base:** `base_dev` at `67f6c5981f8ccb6ea2001ccb19bb626ed03fd9a0`. **Implementation scope:** service + DB + PostgreSQL qualification only. **Production:** HOLD.

## 1. Baseline and ownership
CUR-R2A exact-SHA, specialized CUR-R2A, security, module, SALES, INVENTORY, CRM and RustFS workflow runs all concluded success on baseline SHA. Only STOREFRONT may publish a KZT offer price via canonical `store_offer_price_revision`; FX snapshots/policies/calculations/decisions remain immutable. No controllers, auto repricing, changes to DEC-FUL-01 or checkout FX conversion.

## 2. Review findings (must address)
- **CUR-R2B-P0-01:** `SkuFxSellerApprovalService.calculate` uses SELECT-then-INSERT for semantic SHA idempotency. In parallel identical calculations, the DB unique key prevents duplicate evidence but may return a unique-constraint exception to one caller. Implement PostgreSQL atomic insert/conflict-read, or a well-defined transaction-retry boundary; ensure identical calls return the same persisted calculation ID, conflicting semantics never collide, and no rollback-only transaction is reused. Demonstrate with two independent PostgreSQL transactions.
- **CUR-R2B-P0-02:** No quote/calculation freshness TTL is enforced in `decide`; stale-but-latest historical rate dates can be approved. Introduce explicit tenant policy and evaluation timestamp from an injected `Clock`, with an official NBRK business-day/holiday treatment. Never assume a universally fixed 24-hour rate TTL.
- **CUR-R2B-P0-03:** No explicit max candidate KZT price delta versus captured offer price at approval. Add explicit guardrail configuration (per-tenant and/or per-policy immutable version) and fail closed when absent in admitted UAT tenants. Check both upward and downward deviations using BigDecimal ratio, rounded candidate and absolute price bounds.
- **CUR-R2B-P0-04:** `decide` rechecks active negotiation states only after invoking STOREFRONT mutation; same-transaction rollback works but ensure **every** quote/proposal writer follows the same storefront serialization point. Prove with both lock orderings rather than relying on test scheduling.
- **CUR-R2B-P1-05:** For rejected proposals, capture explicit reason/intent evidence and actor authorization. Expired or denied approvals must leave an auditable terminal result *without* contaminating canonical price history; distinguish decision REJECTED from system refusal EXPIRED/STALE/BLOCKED.
- **CUR-R2B-P1-06:** `configure` uses `MAX(revision)` under tenant storefront lock; verify no independent policy writer bypasses the lock. Policy bounds are not the same as commercially approved price-delta bounds.

## 3. State and contract
Do not mutate an immutable R2A calculation. Add an optional lifecycle projection of **derived** state (PENDING, EXPIRED, SUPERSEDED, APPROVED, REJECTED, BLOCKED) from immutable events/decision + clock, not mutable evidence rows. A failed validation is a deterministic typed result or business exception; it must not create a seller approval decision, canonical price revision, or order changes. Approval request must include stable idempotency key, actor and reviewed calculation ID and price; sender supplied rate/amount is never trusted. Approval is single-use with current offer row-version CAS. Repeated committed command with the same actor/key/payload returns the same receipt even if subsequent rate/policy changed; different payload or key conflicts. Expose service-side candidate preview and guardrail reasons for later UI, without controllers.

## 4. Proposed guardrail model
Immutable tenant/offer scope policy revision with: max upward/downward delta pct; min/max KZT; rate effective-date validity rule; calculation TTL; optional manual override prohibition. Require explicit configuration; no silent default allowing an unlimited price change. Define source-date resolution based on official NBRK business-calendar publication dates, not wall-clock date alone. Validate against policy revision and against current offer price under the serialized lock. Use DB-compatible UTC Instants for audit and injected Clock in service. Pending calculations may become non-approvable but never deleted/updated. A *new* calculation after rate correction references new source snapshot.

## 5. Transaction and concurrency requirements
Enforce source provider FOR SHARE/UPDATE, organization currency policy admission, storefront lock, offer CAS, negotiation/accepted quote checks and decision insert in one logical serial order. Review IAM lock-order DAG to avoid deadlock. All approval failures roll back any tentative STOREFRONT mutation. For source rate correction and checkout, insist on two isolated transactions and post-commit assertions. No external API calls within pricing transaction.

## 6. PostgreSQL qualification matrix
- R2B-01 identical concurrent `calculate × calculate` -> one persisted ID, both succeed
- R2B-02 conflicting idempotency key/payload -> deterministic conflict
- R2B-03 rate too old, including weekend/holiday -> no approval/price change
- R2B-04 calculation expires between preview and decision -> no approval
- R2B-05 excessive upward and downward price changes -> refusal, no price revision
- R2B-06 boundary min/max and 0%-delta, non-zero prices, rounding precision
- R2B-07 policy change before decision -> stale policy refusal
- R2B-08 correction before approval and approval before correction -> consistent order
- R2B-09 checkout before/after FX approval -> immutable order unit price snapshot
- R2B-10 negotiation before/after FX approval -> consistent negotiation price contract
- R2B-11 accepted quote before/after FX approval -> no retroactive repricing
- R2B-12 duplicate seller decision under concurrent sessions -> one approval + one canonical revision; exact replay
- R2B-13 permissions revoked and tenant suspended during approval -> fail closed
- R2B-14 transaction deadlock/timeout/crash retry -> no orphan decision/revision
- R2B-15 cross-tenant/ineligible currency attempt -> blocked
- R2B-16 DB composite FK/immutable trigger abuse -> rejected
All tests run against actual PostgreSQL and assert row counts, actual amounts, statuses, locks and snapshots, not just thrown exceptions. H2 is supplementary.

## 7. Delivery and gates
1. Write failing PG tests for P0-01 through P0-04.
2. Implement atomic idempotent calculation and configurable immutable guardrails with additive Liquibase migration after 0131; avoid editing earlier migrations.
3. Add deterministic injected-clock checks and business-calendar coverage.
4. Implement seller decision receipt/retry semantics without changing public price owner.
5. Execute targeted PG qualification, full H2/PG regression, security, SALES/INVENTORY, then full exact-SHA CI. Record final SHA/run IDs in this document only after confirmed.
6. Keep CUR-R1C activation default OFF; require separately approved scoped UAT tenant rollout with rollback and monitoring.

**Acceptance:** all R2B PG scenarios and exact-SHA workflow success on same final commit; no controllers, automatic FX repricing, main merge, DEC-FUL-01 mutation. Production admission remains HOLD until distinct decision.
