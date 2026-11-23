# RM CUR-R2B — Seller FX Pricing Guardrails & Lifecycle Hardening

**Date:** 2026-11-23. **Base:** `base_dev` at `67f6c5981f8ccb6ea2001ccb19bb626ed03fd9a0`. **Implementation scope:** service + DB + PostgreSQL qualification only. **Production:** HOLD.

## 1. Baseline and ownership
CUR-R2A exact-SHA, specialized CUR-R2A, security, module, SALES, INVENTORY, CRM and RustFS workflow runs all concluded success on baseline SHA. Only STOREFRONT may publish a KZT offer price via canonical `store_offer_price_revision`; FX snapshots/policies/calculations/decisions remain immutable. No controllers, auto repricing, changes to DEC-FUL-01 or checkout FX conversion.

## 2. Review findings (must address)
- **CUR-R2B-P0-01:** `SkuFxSellerApprovalService.calculate` uses SELECT-then-INSERT for semantic SHA idempotency **after acquiring the tenant `store_front FOR UPDATE` lock**. This may already serialize identical calls correctly. Verify in two actual PostgreSQL transactions (and audit all other calculation writers); **do not change to ON CONFLICT unless a failing race proves the locking contract insufficient**. Exact replay must return the same persisted calculation ID and no duplicate evidence. Retain existing unique constraint as defense in depth.
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
1. Write PG race characterization tests for P0-01 and failing guardrail tests for P0-02 through P0-04.
2. Preserve existing lock-based calculation idempotency if proven; fix only demonstrated defects. Implement configurable immutable guardrails with additive Liquibase migration after 0131; avoid editing earlier migrations.
3. Add deterministic injected-clock checks and business-calendar coverage.
4. Implement seller decision receipt/retry semantics without changing public price owner.
5. Execute targeted PG qualification, full H2/PG regression, security, SALES/INVENTORY, then full exact-SHA CI. Record final SHA/run IDs in this document only after confirmed.
6. Keep CUR-R1C activation default OFF; require separately approved scoped UAT tenant rollout with rollback and monitoring.

**Acceptance:** all R2B PG scenarios and exact-SHA workflow success on same final commit; no controllers, automatic FX repricing, main merge, DEC-FUL-01 mutation. Production admission remains HOLD until distinct decision.

## 8. CUR-R2B Calendar/Clock/Inverse-Race technical review (2026-11-23)

**Official NBRK evidence:** NBRK describes USD/KZT as a next-business-day official rate from KASE fixing and other currencies as cross-rates. Official **valid_date**, source revision and source SHA are data fields from the trusted NBRK importer, never derived from an assumed holiday calendar. Primary references:
- https://nationalbank.kz/ru/news/suraqtar/17842
- https://nationalbank.kz/ru/exchangerates/ezhednevnye-oficialnye-rynochnye-kursy-valyut

**Current implemented admission (R2B scope):**
- Evaluate `valid_date <= today(Asia/Almaty)` using injected UTC `Clock`.
- Apply seller-configured maximum **calendar days** between official valid date and Almaty local evaluation date. This is a configurable freshness SLA; it is *not* a verified KASE/NBRK holiday schedule. Sunday-to-Friday allowance of 2 days can be configured; Monday may exceed the same allowance, with no synthetic rate interpolation.
- Calculation TTL and immutable timestamps use the same injectable Clock; no sleep-based timing test.
- Published correction of a rate for its valid date invalidates older revisions under shared NBRK source lock.
- **Production gap:** dedicated authoritative holiday/trading calendar source and publication-delay/late-arrival exceptions have not been implemented. Manual override must not invent official rates. This gap blocks claimed complete NBRK calendar admission.
- Inverse PostgreSQL tests exercise FX approval committing before checkout, and before new negotiation, with canonical price-history and order snapshot assertions. They complement existing negotiation-first tests; source/IAM/checkout interleavings need full qualification before general rollout.

**Audit-only constraints:** migrations 0129–0131 and DEC-FUL-01 unchanged; public KZT price remains exclusively STOREFRONT-owned. No new FX controller or automatic FX repricing.

**CI:** final test results must be separately confirmed by exact-SHA action runs. This section does not assert admission.
