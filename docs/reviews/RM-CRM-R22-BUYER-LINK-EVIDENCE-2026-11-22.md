# CRM-R2.2 — Buyer-confirmed Customer Link, PostgreSQL Qualification

**Date:** 2026-11-22. **Repository:** alimbetov/RM, branch `base_dev`.
**Exact qualification SHA:** `3cdbf4b25472dedb2203d3f37f869f9438384e86`.
**GitHub Actions:** [run #45048659928](https://github.com/alimbetov/RM/actions/runs/45048659928) — **SUCCESS**.
**Job:** `137288724042`, `buyer-link-postgresql`.
**Surefire artifact:** `crm-r22-buyer-postgresql-45048659928`, artifact ID `14764088565` (full CRM XML, SHA/run provenance, counts).
**Environment:** PostgreSQL 17, Java 21, Spring test profile.

## Test results

| Suite | Tests run | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| CrmR1aQualificationTest | 37 | 0 | 0 | 0 |
| CrmBuyerLinkQualificationTest | 7 | 0 | 0 | 0 |
| CrmInteractionQualificationTest | 19 | 0 | 0 | 0 |
| **Total** | **63** | **0** | **0** | **0** |

GitHub Actions run, JUnit assertions, fail-closed Surefire presence/names/skipped verification, and evidence upload all succeeded.

## Implementation

- `src/main/java/kz/rm/crm/CrmBuyerLinkService.java`: `propose`, buyer-only `confirm`, CRM+SALES-authorized `orderHistory`, tenant-scoped `linkForCustomer`.
- Liquibase changesets `0103` and `0104` in `src/main/resources/db/changelog/db.changelog-master.sql`: separate link and immutable event schema, tenant-composite FK, unique customer/buyer/request keys, PostgreSQL immutable audit trigger.
- `src/test/java/kz/rm/crm/CrmBuyerLinkQualificationTest.java`: 7 positive/negative/concurrency tests.
- `.github/workflows/crm-r22-buyer-link-pg.yml`: independently qualified PostgreSQL CI without branch-wide concurrency cancellation.

## Ownership and security invariants

- **IAM identity:** buyer is `public.iam_user_account.id`; CRM never creates a duplicate buyer identity.
- **SALES authority:** `crm.sales_order.buyer_user_id` remains the order owner. The link does not rewrite or duplicate SALES facts.
- **CRM tenant:** `crm.customer(tenant_id,id)` is the seller-owned customer record.
- **Explicit buyer approval:** seller creates `PENDING`; only the verified buyer of the proposed link can confirm; requires an actual seller-owned order assigned to that global buyer ID, not merely a pending email.
- **No history disclosure before confirmation:** `orderHistory` also requires seller's `CRM_READ` and `SALES_ORDER_READ` permissions and an active CRM customer.
- **Uniqueness:** one CRM customer per buyer and one buyer per CRM customer within the same seller tenant; distinct tenants may independently link the same global buyer.
- **Concurrency & audit:** proposal races admit one winner; double confirmation writes exactly one `CONFIRMED` event. PostgreSQL update/delete of link events rejected. Failures keep state/revision/events unchanged.

## Remaining limitations — HOLD outside this slice

- Buyer link **revocation, unlink/relink, disputed link, customer merge, account deletion** are not implemented; the UNIQUE constraints deliberately fail closed rather than silently changing mappings.
- Business policy for **backfilled historical orders** after confirmation is not independently approved; current read projection includes prior orders for that buyer in the same seller tenant.
- Robustness of repeated multi-run racing and adversarial global-suspension-versus-confirmation still needs a separate stress/security gate.
- No controllers/UI; no external OIDC E2E, no full platform RM-QA-01B admission, no general 99/99 CRM simulation qualification. **Production HOLD.**

**CRM-R2.2 initial service qualification: GREEN on the exact SHA above.** This does not represent production release approval.
