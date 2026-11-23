# RM CUR-R1C — Commerce Currency Admission & Lock Hardening

**Date:** 2026-11-23  
**Branch:** `base_dev` only. **Status:** implementation staged; activation/production HOLD.  
**Authority:** RM-PLATFORM-STANDARD-R2, RM-CURRENCY-R1, FX-R1A.3, DEC-FUL-01.

## 1. Scope and default safety posture

A tenant's currency *configuration* must be checked on every **new public KZ commerce command**, independently of a foreign-currency PURCHASE or SALES_INTERNAL allowance. The first admitted market is KZ with **public KZT prices only**. No implicit conversion, no recalculation of agreed offers/quotes/orders, and no change to inventory or fulfillment.

The implementation is deployed as an opt-in switch, **`rm.currency.commerce-admission.enabled=false` by default**, pending historical-tenant migration, preflight, race qualification and legal review. Never declare this default-off code an enforced production market policy. Enable in a controlled UAT PostgreSQL environment with fully initialized tenant policy and backfilled historical classifications.

## 2. Service integration

- `CommerceCurrencyAdmission.lockKzMarket(tenant)`: transaction mandatory, locks `public.tenant_currency_policy FOR UPDATE`, requires `KZ:KZT:ACTIVE` and ENABLED KZT `PUBLIC_PRICE` allowlist row.
- `CommerceCurrencyAdmission.assertKzt(currency)`: rejects any non-KZT public value while enabled.
- `CommerceCurrencyAdmission.assertDraftCurrency(tenant,currency)`: foreign internal draft requires explicit ENABLED `SALES_INTERNAL`; this is *not* foreign public pricing consent.
- `CommerceCurrencyAdmission.assertPublicRead(tenant,currency)`: read-only anonymous card admission; does not acquire write locks.
- `StorefrontOfferService`: lock currency prior to storefront row; KZT on publish/public price update; foreign draft only when internally allowed.
- `PublishedOfferQrService`: currency lock prior to new ISSUE/REISSUE and storefront row; reprint/revoke still function under REVIEW_REQUIRED. An immutable receipt may be replayed without creating a new active QR.
- `PublishedOfferCardQuery`: checks market policy/KZT before allowing public image/card return (nonlocking read check).
- `AutoOrderService`: after checkout stripe and IAM workspace, lock currency **before storefront and offers**; validate accepted currency from locked authoritative offer or negotiated quote. Replayed immutable checkout receipts are unaffected.
- `SalesPriceProposalService`: currency lock after workspace and before storefront and offer; new proposal/response validates public KZT. Immutable prior receipts are readable even after market HOLD.
- `StorefrontOfferService.approveSuggestedKzt`: seller-initiated explicit approval of a KZT candidate, using the canonical `updatePrice` row-version CAS + `crm.store_offer_price_revision`; it does not itself bind an FX calculation or publish automatically.

## 3. Required PostgreSQL lock order

1. **New buyer checkout:** checkout idempotency stripe -> verified buyer lock (accepted quote only) -> IAM workspace -> tenant currency policy -> storefront -> offer -> catalogue -> quote / dependent rows.
2. **Seller price mutation:** IAM authorization/workspace fence -> tenant currency policy -> storefront -> offer -> catalogue when applicable.
3. **New QR issue/reissue:** IAM/workspace -> tenant currency policy -> storefront -> offer -> catalogue. Receipt replay returns immutable proof before new admission.
4. **QR revoke/reprint:** IAM/workspace -> storefront -> offer -> QR, intentionally no currency lock, so disabling policy never prevents security revocation.
5. **Negotiation:** buyer/seller IAM identity -> workspace -> tenant currency policy -> storefront -> offer -> proposal. Existing receipt reads can return before new admission.
6. **Policy mutation:** IAM workspace fence -> tenant currency policy. It never locks storefront or offer rows.

**Forbidden:** take storefront/offer lock and then acquire a new currency policy lock in the same transaction. Double calls to the same lock after already obtaining it are permitted only under an established outer lock before storefront.

## 4. Invariants and regression obligations

- Foreign public currency is rejected on new KZ operations, **not** silently rewritten to KZT.
- All accepted order and quote monetary amounts remain immutable, including after seller changes price.
- A MARKET policy HOLD blocks new checkout, publication, QR issue and negotiation while never preventing existing receipt replay, administrative QR revoke or normal fulfillment of historically valid orders.
- A denied operation must not produce sales-order, order-line, checkout receipt, price revision or stock side effects.
- Row-version CAS prevents lost seller price updates and publishes at most one canonical immutable price revision.
- The approval method is a manual business action. Full CUR-R2 evidence binding (seller pricing policy ID + rate snapshot ID + immutable calculation ID + explicit decision receipt) is not yet implemented.

## 5. Tests and observed scope

- `CommerceCurrencyAdmissionQualificationTest`: strict-on scenarios for missing policy, KZT checkout, foreign/REVIEW_REQUIRED rejection, QR card denial/admission, PG policy-vs-checkout concurrency, foreign drafts, PG three-way checkout/price/policy race, QR revoke and immutable receipt replay under HOLD.
- `StorefrontManagementQualificationTest`: seller-approved KZT canonical price revision, stale approval rejection, historical order price immutability, and PostgreSQL checkout-vs-price two-way race.
- Dedicated H2 + PostgreSQL workflow: `.github/workflows/rm-cur-r1c-commerce-admission.yml`. Exact SHA + Surefire cases and skip counts; full repository regression still separately required.
- Previous FX-R1A.3 and RM-QA-01B exact-SHA completed successfully at `894d2f25` before CUR-R1C work; that is **not** evidence for this new code.

## 6. Gates to activate or graduate

1. Preflight existing tenant policies, NULLs and published foreign offers, accepted proposals/quotes, QR references and orders. Classify legacy foreign publications as `REVIEW_REQUIRED`; never mutate historical financial records.
2. Demonstrate clean and populated PostgreSQL migrations, backfill strategy, tenant A/B isolation, KZT compliance, deadlock-free lock DAG and representative mixed-workload races with exact SHA evidence.
3. Run the strict-on test suite and end-to-end seller/QR/negotiation/checkout suite. Include both outcomes of policy-vs-checkout and seller-price-vs-checkout races; randomized triple race alone is supplementary and does not prove every ordering.
4. Upgrade feature flag only after independent legal/security and operational approval; production remains HOLD. For rollout, reject attempts to enable it while a tenant lacks fully initialized policy.
5. CUR-R2 next: immutable seller FX policy, provenance-bound calculated candidate, seller approval receipt and no-auto-publish default. Only call existing STOREFRONT price update path; never update accepted order snapshots or DEC-FUL-01 fulfillment.

## 7. Residual findings

- Default-off flag is **not** public compliance enforcement: an already-active legacy non-KZT public offer may remain available until rollout. A read-only preflight and controlled admission enablement are P0.
- Anonymous public card checks are read-only and cannot provide the same linearizable contract as a price/checkout write; explicitly qualify state visibility and cache invalidation.
- Missing policy and foreign historic data must be classified before flag enablement.
- Expanded genuine source-signed NBRK provenance and legal business-day policy are separate FX-R1A.3 operational gates; offline tests alone are not a production live-feed approval.
- Historical purchase FX and inventory valuation do not imply lawful foreign-currency KZ retail pricing.
