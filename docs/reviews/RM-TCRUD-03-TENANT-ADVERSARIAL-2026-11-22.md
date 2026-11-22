# TCRUD-03 — tenant isolation adversarial qualification

**Branch:** `base_dev`. **Evidence status:** active, **NOT fully qualified**. **Target:** PostgreSQL two-tenant isolation; preserve Feature Freeze / no controllers. Baseline reviewed: `f8cdf56a9259eb654a4190115e0eb8afc792f820`.

## Security invariants

1. The request explicitly selects `tenant_id=iam_workspace.id`; a global user’s membership in two workspaces does not transfer grants across them.
2. Queries, filtered counts and every page only return rows within the authorized tenant; known cross-tenant UUID must return not-found or fail closed, never report foreign object details.
3. PII reads require `CRM_PII_READ` in the selected tenant, not merely `CRM_READ`, `CRM_WRITE`, or PII grant in another tenant.
4. State transitions, audit history and nested links must be scoped to tenant and checked against object state; immutable SQL history is only writable through approved append commands.
5. No direct storage access or image/file stream before FILES permission **and** typed owner policy.
6. Revocation vs admitted command requires real two-connection PostgreSQL interleavings, not only sequential mocks.

## Evidence matrix and gaps

| Surface | Existing evidence / new case | Status | Remaining negative qualification |
|---|---|---|---|
| CRM customer & contact | `CrmR1aQualificationTest` existing R1/R1.4 + `tcrud03MixedMembershipNeverCarriesCrmPiiOrWriteAcrossTenants` | CODED, CI PENDING | All operation/filter permutations, multi-page counts, explicit archived parent and different-role restore |
| CRM category & contract | Existing R1.2/R1.3 qualification plus mixed-membership category/contract read & denied cross-tenant writes | CODED, CI PENDING | Foreign UUID for each mutation, expiry/search page variation, baseline change rollback |
| CRM audit | `CrmHistoryService` tenant-scoped read + known foreign UUID history absence | CODED, CI PENDING | All history types with mixed membership; direct UPDATE/DELETE restricted to PostgreSQL |
| IAM permission and revocation | `TenantRbacRevocationQualificationTest` uses PostgreSQL two-session latches for permission revoke, membership and workspace suspension | EXISTING / RECHECK | Cross-domain admission-specific object side effect and revocation |
| SALES | `SalesOrderQueryQualificationTest`, `SalesInventoryRbacQualificationTest` | EXISTING / TRACE | Mixed membership, known order UUID, every page/count, business state change |
| INVENTORY | `InventoryMovementQualificationTest`, `InventoryReservationQualificationTest`, `StockBalanceQueryQualificationTest` | EXISTING / TRACE | Known warehouse/location UUID, stock movement transfer FK, all mutation paths |
| ORGANIZATION | `OrganizationLocationQueryQualificationTest` | EXISTING / TRACE | Mixed membership, explicit foreign location IDs and archived owner |
| FILES | `FilesR10aQualificationTest`, `FilesR10bQualificationTest`, live RustFS E2E | EXISTING / TRACE | VerifiedActor facade layer, foreign owner ID at stream boundary, byte-exposure order |

`EXISTING / TRACE` is **not** a claim of full qualification. Missing dimensions remain backlog and must not be reported GREEN as a whole.

## Wave 0.1 change and acceptance

Add a test in existing `CrmR1aQualificationTest` (reuse existing PostgreSQL/H2 fixtures and qualified user/role provisioning rather than inventing another service) that:

- creates two organizations with same customer labels, category code and contract code;
- assigns a single global member two distinct tenant roles, full CRM in A and CRM_READ-only in B, with RBAC enabled in both;
- confirms tenant-switch searches return only each tenant’s records;
- confirms no PII read, CRM writes or cross-tenant mutations in B;
- confirms a known foreign contact ID cannot yield history or status in A.

**Acceptance:** pass all existing test groups on exact HEAD with H2, PostgreSQL, populated CRM upgrade and existing RustFS E2E, while noting TCRUD-03 as **PARTIAL** until remaining domains' matrix is fully traced and exercised.

## Follow-on priority

1. Expand to mixed-member SALES and ORGANIZATION, including list totals and foreign IDs.
2. Expand INVENTORY and FILES binary reads with denied stream pre-I/O tests.
3. Add exact-SHA evidence and per-facade negative coverage matrix before claiming TCRUD-03 QUALIFIED.


## Wave 0.2 — cross-domain adversarial qualification

**Source baseline:** `154acf8cda2f989d2f51c14b668d887b44aae4ef` (earlier CRM-only GREEN). This section records *new* tests on the next `base_dev` commit; **no exact-HEAD CI status is asserted before jobs finish**.

| Domain | New scenario / exact test method | Adversarial input | Expected security outcome |
|---|---|---|---|
| ORGANIZATION | `OrganizationLocationQueryQualificationTest.tcrud03SameGlobalMemberDifferentLocationGrantsAndForeignIdDenied` | Global member in tenant A and B; writer A / reader-only B; same names; page 0/1; foreign location UUID | Per-tenant count, isolation on each page, foreign GET not found, write in B denied |
| SALES | `SalesOrderQueryQualificationTest.tcrud03MixedMembershipSalesReadIsTenantBoundAndCannotBorrowPermission` | Global member A/B; SALES_ORDER_READ in A but not B; filtered/offset list | A pages/count only A orders; B read denied despite membership; null actor denied |
| INVENTORY | `StockBalanceQueryQualificationTest.tcrud03SharedMemberInventoryReadCannotBorrowOtherTenantGrants` | Global member A/B; INVENTORY_STOCK_READ only A; foreign location/item UUID predicates; paging | B denied, foreign filters return zero; stock rows A-only |
| FILES | `FilesR10bQualificationTest.tcrud03BinaryReadDoesNotWriteAnyBytesForWrongOwnerOrMissingPiiPermission` | Existing confidential CRM-owned clean file; foreign tenant + attachment UUID; null actor; CRM_PII_READ revoked | No bytes written to any output stream before authorization |

### Existing tests re-used, not copied

- `TenantRbacRevocationQualificationTest` — active command vs later revoke; earlier revoke vs command; global suspension; membership/tenant suspension; stale JPA membership/workspace.
- `OrganizationLocationQueryQualificationTest.postgresLocationCreationFencesConcurrentPermissionRevocation`.
- `SalesInventoryRbacQualificationTest.postgresSalesConfirmationFencesConcurrentPermissionRevocation`, `concurrentOrdersCannotOversellAndCrossTenantCannotCommand`, `finalSecGateRejectsMixedActorTenantAndMissingInventoryPermits`.
- `InventoryReservationQualificationTest.tenantIsolationAndReservationEventImmutability`; `InventoryMovementQualificationTest.cannotReduceBelowReservedOrCrossTenantReference`, `concurrentSameKeyPostsExactlyOneMovement`.
- `FilesR10bQualificationTest.wrongTenantAndRevokedPermissionNeverReadBinaryStream`, `authorizedPlaintextCustomerReadIsScopedAndImmediatelyRevoked`; live RustFS/ClamAV workflow.

**Qualification distinction:** Existing cases cover selected command paths, not every possible foreign UUID in every method. Do not report all of TCRUD-03 as QUALIFIED until all entrypoint/SQL/test mappings are complete and the unmapped cells are closed.

### Completion gate

- H2 smoke; full isolated PostgreSQL integration; 200-account populated IAM; populated CRM migration; RustFS/ClamAV real infra on the **same exact commit**.
- Verify all four new adversarial cases pass on both test DB profiles where supported, and PostgreSQL-only race tests are actually executed, not skipped.
- If any workflow fails, report HOLD and repair in `base_dev`; previous commit GREEN is not inherited.
