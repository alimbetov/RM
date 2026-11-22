# RM SALES PASS 09/12 — IAM, tenant RBAC, order read, CRM PII and access boundaries

**Date:** 2026-11-22  
**Branch:** `base_dev`  
**Disposition:** SOURCE REVIEWED / TARGETED HARDENING IMPLEMENTED / EXACT-SHA QUALIFICATION PENDING  
**Release:** Production HOLD.

## 1. Verified source inventory

| Boundary | Actual implementation | Review finding |
|---|---|---|
| Authenticated entry | `VerifiedActor` + `OidcActorResolver`; `AuthenticatedSalesQueries`, `AuthenticatedBuyerOrders`, `AuthenticatedCrmQueries` | Trusted actor boundary exists; does not imply a production public HTTP adapter |
| Role and tenant admission | `TenantAuthorization.requireForCommand` | Takes actor row lock and workspace lock, fresh membership check, tenant-specific RBAC grant; deny on missing/inactive identity |
| Role revocation | `TenantRoleService.revoke` | Owner-authorized workspace lock fences admitted commands; concurrent revoke × seller command has a new PostgreSQL race test |
| Seller order read | `SalesOrderQueryService` → `SalesOrderRepository` | Authorization precedes page/count; every query includes tenant predicate; DTO only id, status, total, currency, createdAt, version |
| Buyer history | `AuthenticatedBuyerOrders` → package-private `AutoOrderService.buyerOrders` | Verified identity; checks verified current email and active global account; filters buyer_user_id; anonymous/public transport remains HOLD |
| CRM list | `CrmSearchService.customers` | Tenant-bounded paging/count, company labels only, no person plaintext |
| CRM contact PII | `CrmSearchService.contacts` and `CrmCustomerService.getContact` | `CRM_PII_READ` required; encrypted secrets decrypted using per-record tenant-associated crypto context |
| Offer mutation | `SellerOfferService` and separate `StorefrontOfferService` | Two command paths with different behaviors: retain as architectural P1 until consolidated or documented as a single authoritative port |
| Offer publication vs paused storefront | `StorefrontOfferService.transition` | **Confirmed functional access-control gap**: publication previously ignored storefront state; now rejects non-PUBLISHED storefront |
| Storefront/offer metadata read | `StorefrontManagementService.get`, `StorefrontOfferService.get` | Strengthened to `requireForCommand` in writable transaction for same lock/revoke fence as mutations |
| Log / API / outbox safety | no qualified public SALES HTTP controller in reviewed scope | Cannot certify HTTP status codes, transport rate limits, distributed structured logs or production PII masking from source-only tests |

## 2. Authorization matrix (required behavior)

| Principal / grants | Seller list A | Seller list B | CRM contact PII A | Offer mutate A |
|---|---|---|---|---|
| Anonymous | deny | deny | deny | deny |
| Verified A, SALES_ORDER_READ A | allow | deny | deny without CRM_PII_READ A | deny without offer permission |
| Verified A+B, SALES_ORDER_READ only A | allow | deny | deny without CRM_PII_READ A | deny without offer permission |
| Verified B only | deny | depends on explicit B grant | deny | deny |
| Global platform identity without A membership | deny | deny unless member | deny | deny |
| Blocked/revoked identity | deny | deny | deny | deny |
| Verified A, CRM_READ only | deny if tenant functional RBAC enabled | deny | deny | deny |

Note: when tenant RBAC is **not enabled**, `TenantAuthorization.permitted` intentionally grants `SALES_ORDER_READ` and `CRM_READ` to any active tenant member. This is a current policy, not a proof of least-privilege for seller-only order access. Before production, explicitly accept this fallback or require functional RBAC enforcement for sensitive tenants.

## 3. New audit qualifications in code

- `SalesOrderQueryQualificationTest.pass09RevokedSellerReadAndUnrelatedGlobalIdentityFailClosed`: read allowed under A grant; paginated/count B denied; revocation denies list and filtered queries; global-only principal without membership denied; seller projection has no buyer PII.
- `SalesOrderQueryQualificationTest.pass09SuspendedActorCannotReadOwnTenantOrders`: suspended identity denied.
- `StorefrontManagementQualificationTest.pass09PausedStorefrontCannotBeBypassedThroughSecondOfferService`: publish via alternative command service is denied while storefront paused.
- Existing `CrmR1aQualificationTest` contains `tcrud03MixedMembershipNeverCarriesCrmPiiOrWriteAcrossTenants`, `postgresCrmPiiReadFencesConcurrentPermissionRevocation`, `contactCiphertextSwapFailsClosedUnderPerRecordAad` and encrypted-person/contact tests.
- Existing `SalesOrderQueryQualificationTest.tcrud03MixedMembershipSalesReadIsTenantBoundAndCannotBorrowPermission` checks A/B asymmetrical grants and pagination.
- Existing `SellerOfferQualificationTest.postgresRevokeVersusSellerCommandHasOneAuthorizationOrder` qualifies racing permission revocation and seller CAS.

## 4. Open blockers and accepted boundaries

**P0 release gates:**
1. Obtain same exact-SHA full success for H2, real PostgreSQL, populated CRM/offer migrations, and seller revocation concurrency. No inherited green from older SHAs.
2. Run complete PASS 09 negative test matrix on that same SHA, including PII, global IAM admin, tenant A/B/AB, read pagination/count, blocked actor and race.
3. Public transport/anti-abuse and JWT admission remain independently unqualified. Do not claim real HTTP authorization or public checkout release.

**P1 design issues:**
1. Consolidate duplicate `SellerOfferService` and `StorefrontOfferService` behavior: inconsistent noop/CAS, title/currency constraints, state transitions, and audit. Prefer one existing authoritative implementation and preserve compatibility without introducing new architecture.
2. Explicitly decide whether `SALES_ORDER_READ` fallback for every active member while functional RBAC is disabled is acceptable to release; require `enforced=TRUE` otherwise.
3. Add transport-specific negative tests to demonstrate no foreign entity counts/IDs in HTTP failures, URLs, pagination and logs once trusted adapters exist.

## 5. Exact-SHA rules

No PASS 09 closure before CI evidence (run URL, exact SHA, completed result, failed/skipped tests, PostgreSQL report) is attached to this review. Status remains **SOURCE REVIEWED / IMPLEMENTED PARTIALLY / HOLD**.

This review records service-level findings, not a completed penetration test or verification of deployed traffic, databases, or live log systems.
