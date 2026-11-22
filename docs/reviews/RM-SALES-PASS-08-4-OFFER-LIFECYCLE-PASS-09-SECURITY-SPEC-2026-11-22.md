# RM SALES PASS 08.4 — Seller-authorized storefront offer lifecycle; PASS 09/12 security gate

Date: 2026-11-22  
Branch: `base_dev`  
Status: **IMPLEMENTATION SPEC / CODE NOT YET QUALIFIED**  
Prerequisite: SALES PASS 08.1–08.3 exact-SHA PostgreSQL/H2 qualification `cb490556e56d74770ee86f839a849a354702673f`.

## 0. Scope and non-goals

Complete tenant-authorized seller operations on existing `crm.store_front` and `crm.store_offer`: publication, pause/resume, change commercial price, CAS, foreign-tenant denial; then perform PASS 09/12 IAM, RBAC and access boundary review. Maintain SALES→INVENTORY snapshot contract from 08.1–08.3.

No new REST controllers during feature freeze. No duplicate price ledger; `crm.cat_price` is catalogue price history, whereas `crm.store_offer.unit_price` is the authoritative *current* offer checkout price. Do not add another order engine, global hardcoded role taxonomy, payment posting, SERVICE inventory fulfillment or pub/sub architecture.

## 1. Data and actor invariants

1. `tenant_id` is resolved from trusted `VerifiedActor` membership, not accepted from a client-provided request parameter as an authorization source. Tenant identities in commands must match the verified actor.
2. All offer and storefront lookups/mutations are scoped by tenant. A foreign offer/storefront/SKU is indistinguishable from a nonexistent record in outward errors (`NOT_FOUND`), with no foreign IDs/counts leaked. `tenant_id` must never be reassigned.
3. No authorizing solely by `store_front.owner_user_id`, email, caller-supplied user_id, DTO or raw tenant ID. Require explicit tenant permission for *each* write. A user in tenants A and B must obtain a grant in the **same tenant as the target**, not combine A's seller and B's inventory grants.
4. Reuse current authenticated `VerifiedActor` abstraction and tenant-permission decision service. Use existing permission constants where semantically equivalent; add only minimal offer permissions if truly absent: `STOREFRONT_OFFER_MANAGE` (create/edit/pause) and `STOREFRONT_OFFER_PUBLISH` (publish/resume). Never infer write permission from read permission.
5. Maintain immutable accepted `sales_order_line` snapshots: offer ID, catalogue SKU snapshot, title, quantity, currency, unit price, totals. Changes to a live offer must **never** rewrite accepted order lines or rebind already PLACED orders.
6. Enforce tenant-composite FKs for offer→storefront and offer→catalogue item where not already present. Do not mutate previously applied changesets. Add a new forward-only migration, with populated PostgreSQL upgrade test.
7. Use monotonic `version BIGINT NOT NULL DEFAULT 0` on `crm.store_offer` for compare-and-set (new additive migration). Verify compatibility with existing schema/version conventions before migration. Increment exactly once for each *effective* mutation; unchanged command with same intended result can be idempotent only via a defined requestKey mechanism.
8. PRODUCT is the only type eligible for stock-backed publication in R1: tenant SKU exists and is PUBLISHED. SERVICE remains held until dedicated service-order fulfillment is implemented. Published offer must point to a tenant-owned published storefront and valid published PRODUCT SKU.
9. Treat offer-to-SKU assignment as **immutable after initial publication**. Reassigning a SKU requires creation of a new offer (new offer ID); do not reuse an existing offer for a different product. Protect this policy at the service boundary and, where viable, with a DB constraint/trigger.
10. Existing PLACED orders retain historical acceptance and inventory command snapshot semantics after pause/unpublish/price changes. Never silently switch SKU.

## 2. Minimal service contract (no controllers)

Existing domain service may expose equivalent functions; naming is indicative, not a mandate to add another layer:

```java
Offer createDraft(VerifiedActor actor, UUID storefrontId, UUID catalogItemId,
                  String title, BigDecimal unitPrice, String currency,
                  String requestKey);
Offer publish(VerifiedActor actor, UUID offerId, long expectedVersion);
Offer pause(VerifiedActor actor, UUID offerId, long expectedVersion);
Offer resume(VerifiedActor actor, UUID offerId, long expectedVersion);
Offer changePrice(VerifiedActor actor, UUID offerId, long expectedVersion,
                  BigDecimal newPrice, String currency);
```

Commands must run within their own transaction, authorize before mutation, and apply CAS by `UPDATE ... WHERE tenant_id=? AND id=? AND version=?` or equivalent row-locked version check. CAS conflict yields a stable `OFFER_VERSION_CONFLICT` and no changes. Price > 0, finite representable amount, expected scale/currency precision, ISO currency code and valid tenant state. No client-provided arbitrary state changes.

State machine (adapt if existing persisted enum differs; no breaking data changes):

| Current | Command | Next | Additional admission |
|---|---|---|---|
| DRAFT | publish | PUBLISHED | storefront PUBLISHED; PRODUCT SKU PUBLISHED; publication permission |
| PUBLISHED | pause | PAUSED | manage permission |
| PAUSED | resume | PUBLISHED | repeat publication admission |
| DRAFT/PAUSED/PUBLISHED | changePrice | same | manage permission, CAS, price/currency constraints |
| any | same command with stale expectedVersion | unchanged | version conflict |
| ARCHIVED | publish/resume/changePrice | denied | terminal/explicit policy |
| PUBLISHED | SKU remap | denied | create new offer instead |

If offer currency is immutable in the existing contract, disallow currency change rather than inventing multicurrency order recalculation. Storefront publication/pause must follow the same trusted-tenant and CAS discipline, and checkout should reject offers under paused/nonpublished storefronts.

## 3. Checkout concurrency boundary

`AutoOrderService.checkout` from 08.2 already locks published offer + SKU with `FOR UPDATE`. Preserve consistent lock acquisition order between seller commands and checkout (storefront→offer→catalogue item, or the existing qualified order). A simultaneous pause / price edit / checkout must serialize as one of two valid outcomes:

- checkout acquired lock first → order reflects old published price and SKU, seller edit succeeds only after accepted order commits;
- seller mutation acquired lock first → checkout sees updated price or rejects PAUSED offer; never creates half an order or identity/email outbox side effects on rejected checkout.

Avoid locking another set of rows in inverse order. Add PostgreSQL race tests with bounded waiting/lock timeouts and no deadlock. H2 passing alone is not proof of PostgreSQL concurrency.

## 4. Error taxonomy / observable result

| Condition | Service error | No side effect |
|---|---|---|
| foreign tenant or nonexistent offer | `OFFER_NOT_FOUND` | yes |
| verified user lacks tenant permission | `ACCESS_DENIED` | yes |
| expected version stale | `OFFER_VERSION_CONFLICT` | yes |
| transition disallowed | `OFFER_INVALID_STATE` | yes |
| SKU not published, missing, or SERVICE | `OFFER_SKU_NOT_SELLABLE` | yes |
| storefront unavailable | `STOREFRONT_UNAVAILABLE` | yes |
| invalid price/currency | `OFFER_INVALID_PRICE` | yes |

Do not leak whether a foreign tenant's offer exists. Authenticated actor lacking permission for an in-tenant offer should receive the established authorization failure type.

## 5. PASS 08.4 mandatory automated proof

- **OFFER-01:** tenant A publisher can create draft and publish its offer on its published storefront with published PRODUCT SKU.
- **OFFER-02:** tenant A without offer-manage / offer-publish cannot mutate; no DB/outbox side effects.
- **OFFER-03:** actor with grants only in tenant B cannot edit A; identical externally observable response for nonexistent and foreign A IDs.
- **OFFER-04:** actor member of A+B with grant in A only cannot operate on B; no cross-tenant permission union.
- **OFFER-05:** DRAFT→PUBLISHED→PAUSED→PUBLISHED; invalid transitions denied.
- **OFFER-06:** price edit CAS expectedVersion N succeeds once; second concurrent/stale N fails; version monotonic.
- **OFFER-07:** checkout→price edit preserves accepted order currency/price/total and catalog SKU snapshot.
- **OFFER-08:** PostgreSQL concurrent checkout × pause and checkout × price edit serialize consistently; zero partial orders/outbox.
- **OFFER-09:** attempt to attach tenant B catalogue SKU/storefront under tenant A fails (DB + service).
- **OFFER-10:** unpublished/archived PRODUCT and SERVICE cannot publish/resume stock-backed offer; no stock reservation.
- **OFFER-11:** remap of published offer SKU is rejected, even if a PLACED order exists.
- **OFFER-12:** seller offer write vs permission revocation: define linearization against authoritative grant state, ensure post-revocation new commands fail, verify TOCTOU policy and forced rollback if the revocation wins.
- **OFFER-13:** populated PostgreSQL migration, H2 migration + preexisting order fixtures unaffected; no old changeset checksum rewrites.
- **OFFER-14:** tenant storefront pause blocks new checkout but does not mutate previously accepted lines/reservations.
- **OFFER-15:** same checkout idempotency key returns the original accepted order after price change; mismatched request parameters conflict.

## 6. PASS 09/12 — IAM, tenant RBAC and security boundary

Security audit runs after 08.4 suite on the **same exact commit SHA**. Document the complete role→permission→service command matrix for SALES, STOREFRONT, CATALOG and INVENTORY; include access to CRM buyer/contact PII.

Mandatory matrix dimensions:
- principals: anonymous, verified buyer, seller read-only, seller manager, seller publisher, inventory clerk, tenant admin, globally privileged IAM administrator without tenant membership, blocked/revoked user;
- tenancy: A only, B only, A+B with asymmetrical grants, foreign entity IDs;
- operations: storefront create/update/publish/pause, offer create/publish/pause/resume/price, checkout, order read, buyer history, confirm/cancel/fulfill, reserve/release/consume, catalogue publication;
- races: permission revoke × publish, revoke × confirm/reserve, pause × checkout, actor block × command; same-tenant grants required for paired SALES+INVENTORY operations;
- exposure: no cross-tenant leaks in list, filtering, pagination counts, projections, error bodies, logs or outbox payloads; buyer history uses verified identity and tenant scope; no PII leakage to unauthorized seller;
- admission: revoked memberships, blocked principals and invalid `VerifiedActor` fail closed; unauthenticated email verification does not imply an authenticated buyer session.

Evidence should record SQL fixtures, exact test method, H2 run, real PostgreSQL run, pass/fail/skip and CI URL and commit SHA. No substituted mocks for principal-revocation or concurrency proofs.

## 7. Definition of Done / release gate

**08.4 DONE** only when production code, additive migrations, tests and actual CI on an exact SHA are present; PostgreSQL concurrency, tenant isolation, CAS and accepted immutable price all GREEN, with no skipped mandatory cases.

**09/12 DONE** only when full matrix is green and evidence includes negative authorization, tenant boundary, revoke race and read-model/PII access checks. Any unresolved finding is a numbered P0/P1 blocker.

Passing 08.4 or 09 alone **does not** release SALES. PASS 10–12, public transport anti-abuse, SERVICE fulfillment and remaining release qualification continue as separate gates. **Production HOLD** remains.

## 8. Implementation order

1. Inspect live `store_offer`/storefront schema, current state enums, actor permission model, checkout locking, and existing seller services. Reuse exact symbols rather than naming new duplicates.
2. Add only missing additive version/FK/constraints migration(s).
3. Implement seller commands and state/tenant permission checks with explicit CAS.
4. Add and run OFFER-01..15 in H2 and real PostgreSQL; fix race ordering.
5. Execute PASS 09 audit/negative tests on same SHA.
6. Publish exact-SHA CI evidence, findings and remaining HOLDs in existing review documents; do not merge into `main` without explicit approval.
