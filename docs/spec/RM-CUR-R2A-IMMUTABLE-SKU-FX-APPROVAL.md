# RM CUR-R2A — Immutable SKU FX Pricing Policy & Seller Approval Evidence

**Date:** 2026-11-23. **Branch:** `base_dev`. **Implementation:** service-only; **production:** HOLD.
**Primary owners:** STOREFRONT owns executable public KZT price; FX owns global immutable NBRK evidence and seller-facing recommendation; SALES owns accepted quote and order snapshot. `DEC-FUL-01` unchanged.

## 1. Domain contract and invariants

1. The seller stores the foreign-currency reference **anchor**, not a live public foreign price. A policy revision has tenant/offer, ISO currency (R2A: USD/EUR/CNY), anchor amount, buffer %, markup %, rounding step KZT, and verified approving actor. There is no AUTO mode in R2A.
2. A new SKU/offer FX policy edit always **inserts** a new immutable revision. Stale expected revision fails, never overwrites earlier values.
3. An immutable calculation captures exactly: policy UUID and revision, NBRK rate snapshot UUID and source revision/hash, official rate and nominal, original offer row version and KZT price, output candidate, formula version and content digest. No amount or currency is taken from a buyer.
4. Calculations do **not** modify `crm.store_offer`, `crm.store_offer_price_revision`, quotations, orders, inventory, or fulfillment.
5. Seller approval uses the *existing* `StorefrontOfferService.approveSuggestedKzt` and its row-version CAS; a successful approval links to **one** canonical immutable `crm.store_offer_price_revision` row.
6. A decision receipt is immutable, tenant- and actor-bound; exact request-key retry returns its original result, mismatched requests fail, and only one decision can exist per calculation.
7. Rejection writes evidence only. No automated price change, scheduler, calculation-based checkout repricing, or update of accepted quote/order snapshots.
8. An approval is denied when offer version is stale, the policy version is superseded, the referenced rate revision is superseded, or active negotiations/available accepted quotes exist for that offer. Denial rolls back the attempted price update and does not create a decision or additional price revision.
9. All NBRK rates are global read-only reference data; seller policy and calculation are tenant-bound. Composite SQL FKs prevent cross-offer, cross-policy-revision, and wrong-price-history links.
10. Public KZ price remains KZT; foreign currency is permissible only as seller-internal price anchor after explicit `SALES_INTERNAL` tenant allowlist admission. This allowance does not authorize foreign public/settlement currency.

## 2. New schema / reuse

- `crm.sku_fx_policy_version`: immutable policy configurations, unique `tenant, offer, revision`.
- `crm.sku_fx_calculation`: immutable source/policy/offer snapshot, unique semantic request SHA; NBRK snapshot FK and tenant-bound policy FK.
- `crm.sku_fx_seller_decision`: immutable seller choice, unique request key and unique calculation, optional FK to canonical offer price revision for APPROVED only.
- Changesets `0129` (tables), `0130` (PostgreSQL immutable triggers), `0131` (composite source-context constraints).
- Reused: `NbrkRateRevisionWriter`, `SkuFxCandidateCalculator`, `TenantCurrencyPolicyService.requireCurrencyForPurpose`, `StorefrontOfferService.approveSuggestedKzt`, existing KZT `crm.sales_order_line.unit_price_snapshot`.
- No second catalog of current seller prices, controllers, new branch, or microservice.

## 3. Transactional and lock considerations

Seller policy configuration: verified IAM/workspace admission -> tenant currency policy -> storefront lock -> read offer / policy revision -> immutable insert.

Calculation: verified IAM -> tenant currency -> storefront lock -> read offer and NBRK immutable snapshot -> pure BigDecimal calculation -> immutable insert.

Seller approval: actor's existing offer authorization -> canonical STOREFRONT price change (IAM -> currency -> storefront -> offer) -> validate latest policy/rate and active negotiations **while storefront lock remains held** -> insert linked immutable decision. Any validation failure aborts the same transaction and rolls back the price change. Concurrent checkout and negotiation also lock storefront before offer to provide a serial order.

Important unresolved concern: same-date NBRK correction may commit concurrently with approval. Approval checks the latest known revision during its transaction. Further linearization on the provider row or explicit captured-rate-as-of policy must be separately qualified before production.

## 4. Qualification

`SkuFxSellerApprovalQualificationTest` (10 scenarios):
- CUR-R2A-01 policy revision history and CAS;
- CUR-R2A-02 trusted immutable calculation and idempotent same-input replay;
- CUR-R2A-03 approval chain: policy/rate/calculation/decision/canonical offer price revision; exact retry;
- CUR-R2A-04 seller rejection with zero price changes;
- CUR-R2A-05 stale offer price CAS rollback;
- CUR-R2A-06 NBRK published correction invalidation;
- CUR-R2A-07 active negotiation prevents approval and rolls back all price changes;
- CUR-R2A-08 pre-existing placed order KZT snapshot survives seller FX price approval;
- CUR-R2A-09 tenant isolation and denied cross-tenant commands;
- CUR-R2A-10 PostgreSQL rejects mutation of evidence via UPDATE/DELETE.

Also qualify 9 pure `SkuFxCandidateCalculatorTest` scenarios for BigDecimal precision, nominal and rounding. Run H2 and real PostgreSQL 17 with exact checkout SHA, Surefire manifests and full RM exact-SHA release gate. **PASS may only be recorded after checking the final committed SHA and run conclusions.**

## 5. Unresolved prerequisites for CUR-R2B and production

- Bind every approval to an explicit price-confirmation UI and stronger operator intent receipt/actor; actual controller/UI remains STOP.
- Extend PostgreSQL race matrix to include both explicit orderings of `checkout × FX approve × policy change × negotiation`, consistent source revision as-of semantics, and non-zero real mixed-load concurrency.
- Confirm approval freshness threshold and legal holiday policy for NBRK, anomalies/percentage-change guardrails, and KZT min/max price bounds.
- Qualify existing tenant foreign historical offer migration and enable CUR-R1C commerce admission in UAT; current default remains `false`.
- Add seller permission for `FX_PRICING_MANAGE` only with an approved IAM change; for R2A use existing `STOREFRONT_OFFER_MANAGE` + existing tenant currency permission, no newly invented domain roles.
- No automatic FX repricing, invoice/payment execution, COGS or sale settlement conversions.

**Production admission:** HOLD, `main` untouched.
