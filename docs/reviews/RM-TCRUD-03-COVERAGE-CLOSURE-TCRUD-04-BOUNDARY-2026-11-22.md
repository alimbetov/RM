# RM — TCRUD-03 Coverage Closure / TCRUD-04 Public Entrypoint Boundary

**Branch:** `base_dev`, Feature Freeze. **Source baseline:** `1d1c81bdd82c004b54fa5a90b33ba44b33957583`.
**Status:** targeted closure in progress; no claim of full platform qualification until every application entrypoint and external adapter is resolved. Exact final SHA and jobs to be reported separately. No controllers or production admission.

## 1. Qualification matrix, source → permission → tenant filter → negative test

Evidence applies to existing implementations; **TESTED** means named tests exist, **QUALIFIED** only after exact final SHA PostgreSQL evidence is recorded. Distinguish public authenticated entrypoints from package-private internal domain services.

| Entrypoint family | Permission or admission | Tenant/owner SQL/JPA boundary | Negative / race evidence | Gap / status |
|---|---|---|---|---|
| `AuthenticatedCrmCommands` Customer/Contact mutation | CRM_WRITE; verified actor | tenant, customer/contact version CAS; parent-first locks, tenant-scoped secrets | `CrmR1aQualificationTest.tcrud03MixedMembershipNeverCarriesCrmPiiOrWriteAcrossTenants`; R1.3/R1.4 lifecycle | Tested; full per-method negative matrix still open |
| `AuthenticatedCrmQueries` Customer/Contact read/search | CRM_READ; CRM_PII_READ for plaintext | tenant root + matched join; bounded page/count | `CrmR1aQualificationTest` tenant search/pagination and TCRUD-02 | Tested; customer status/consent/history permutations open |
| `CrmCategoryService`, `CrmContractService`, `CrmHistoryService` | VerifiedActor; CRM_READ / CRM_WRITE | JPA tenant predicates and compound association; immutable revisions | CRM R1.2/R1.3 test group, mixed membership | Tested; adversarial foreign UUID per transition remains open |
| `AuthenticatedOrganizationLocations` | ORGANIZATION_LOCATION_READ/WRITE | workspace scoped location search/GET; bounded pagination | `OrganizationLocationQueryQualificationTest.tcrud03SameGlobalMemberDifferentLocationGrantsAndForeignIdDenied`; PostgreSQL revoke race | Tested; lifecycle transitions not yet implemented |
| `AuthenticatedSalesQueries` | SALES_ORDER_READ | tenant scoped order list, status/time filter, count/page | `SalesOrderQueryQualificationTest.tcrud03MixedMembershipSalesReadIsTenantBoundAndCannotBorrowPermission`; concurrent paging | Tested; staff order detail/history feature remains out of scope |
| `AuthenticatedSalesCommands` | SALES_CONFIRM/CANCEL/FULFILL + INVENTORY_RESERVE/RELEASE/FULFILL | locked tenant order, reservations and stock; atomic event | `SalesInventoryRbacQualificationTest` 13 tests including cross-tenant and PG revoke | Tested; no claim all order states cross combined roles |
| `AuthenticatedInventoryQueries` | INVENTORY_STOCK_READ | tenant root and tenant/location/item filters; count/page | `StockBalanceQueryQualificationTest.tcrud03SharedMemberInventoryReadCannotBorrowOtherTenantGrants` | Tested; stocktake/transfer not implemented |
| `AuthenticatedInventoryCommands` | INVENTORY_RECEIVE, INVENTORY_ADJUST | admission token; location/item scoped balance and immutable movement | `InventoryMovementQualificationTest`, `SalesInventoryRbacQualificationTest` | Tested; exhaustive per-SKU cross-tenant cases open |
| `AuthenticatedFilesCommands/Queries` | FILES_UPLOAD / staged-file owner policy | tenant scoped metadata, idempotent intent | `FilesR10aQualificationTest`, `FilesR10bQualificationTest` | Tested; storage IO side effects remain infra gate |
| `AuthenticatedFilesAttachments/BinaryReads` | FILES_ATTACH/READ/DETACH and CRM owner permissions including PII | compound file+owner identity; foreign tenant rejected before storage stream | `FilesR10bQualificationTest.tcrud03BinaryReadDoesNotWriteAnyBytesForWrongOwnerOrMissingPiiPermission` and existing revoke cases; RustFS/ClamAV | Tested; typed owner variants beyond CRM_CUSTOMER not approved |
| IAM `VerifiedTenantRoleCommands`, `TenantAccessStatusService` | VerifiedActor + tenant owner/RBAC manage | locked actor then workspace, current membership, no wildcard | `TenantRbacQualificationTest`, `TenantRbacRevocationQualificationTest` (PG two-session) | Tested; legacy UUID administration methods remain publicly callable from Java |
| `AuthenticatedBuyerOrders.mine` | VerifiedActor own-user identity + verified email | buyer_user_id restricted to principal ID; not tenant-wide seller access | `AutoOrderR1QualificationTest.tcrud04BuyerOrdersCannotBeReadBySupplyingAnotherUsersUuid` | **New**: closes known public UUID buyerOrders bypass |
| `AutoOrderService.checkout/verifyEmail` | Anonymous checkout + separate proof token; not OIDC admission | published slug scopes seller; email proof controls buyer association | `AutoOrderR1QualificationTest` | NOT a transport security admission: public exposure prohibited during Feature Freeze |

### Remaining test obligations

Test `crm_history` across multiple permission combinations; add foreign SKU/location to actual write commands (not only stock reads); qualify staff order state changes with mixed membership and foreign IDs; lock down IAM raw UUID method exposure; re-evaluate EMAIL/FILE recovery and external adapters. No method may be marked QUALIFIED by this documentation alone.

## 2. TCRUD-04 — verified-principal public interface gate

The added `Tcrud04VerifiedEntrypointQualificationTest` reflects over 20 named application boundaries (CRM, SALES, INVENTORY, ORGANIZATION, FILES, IAM, buyer orders) and rejects any newly public instance method that does not accept `VerifiedActor` as first argument. Existing domain services' internal UUID methods remain encapsulated.

**Confirmed defect and fix:** `AutoOrderService.buyerOrders(UUID)` previously allowed a caller knowing a verified buyer's UUID to read that buyer's order receipts if an unsafe adapter called it. Method is now package-private. Only `AuthenticatedBuyerOrders.mine(VerifiedActor)` provides a cross-package entrypoint; tests simulate two OIDC identities and prove receipt separation and null principal rejection. No change to checkout/verification rules or SQL schema.

**Remaining P0 boundary debt:** `TenantRoleService` still exposes older public Java methods accepting caller-supplied actor UUID, although each method performs owner checks and audit. `Tcrud04VerifiedEntrypointQualificationTest.legacyTenantRoleUuidExposureCannotGrowWithoutExplicitReview` freezes the currently allowed method name set (not a security approval). Convert cross-package callers to `VerifiedTenantRoleCommands` then make the old methods package-private in a separate compatibility-reviewed task. Likewise `EmailIdentityService` public internal-only functions and other IAM bootstrap/recovery entrypoints require an explicit trusted integration boundary review.

A bean being `@Service` does not make it a public HTTP endpoint, but it can still be injected into an unsafe future adapter. Thus **TCRUD-04 stays PARTIAL** until all public raw identity operations have a deliberate boundary or are encapsulated.

## 3. CI gates

Require H2 smoke, PostgreSQL integration (including all 11 non-skipped `TenantRbacRevocationQualificationTest` tests), populated 200-account IAM, populated CRM Liquibase, and real RustFS/ClamAV on the **same final commit**. Fail on compile, status mismatch, revoked actor, cross-tenant response, nonempty denied sink or new public raw UUID method. Never reuse GREEN of a previous SHA.

**Production:** HOLD; no new controllers, transport adapters, merge to `main`, or changes to role policy.
