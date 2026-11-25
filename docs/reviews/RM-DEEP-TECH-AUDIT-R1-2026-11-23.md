# RM-DEEP-TECH-AUDIT-R1 — source-level functional, transaction, migration and QA review

**Scope:** RM Business + RM QR Commerce; source-based deep technical review for `RM-CORE-FUNCTIONAL-CLOSURE-R1` (reviewed design R1.2).  
**Date:** 2026-11-23; **repository/branch:** `alimbetov/RM` / `base_dev`.  
**Reviewed source SHA:** `3485e36f9c3f3ad279b16dfd1337aa9b7d28828e` (documentation-only baseline, code identical to preceding R1.1 documentation commits).  
**Report status:** **SOURCE-REVIEWED / CI-INSPECTED / SPECIFICATION EVIDENCE — NOT A NEW EXECUTED JAVA TEST RUN**.  
**Change policy:** no Java, SQL, workflow or controller code modified as part of this review; `main` untouched; production HOLD.

## 0. Executive technical verdict

- The core is genuinely implemented: IAM/ORG, CRM, CATALOG, STOREFRONT, AutoOrder, SALES/INVENTORY and QR workflows include transaction boundaries, typed ports, append-only receipts, migration constraints and mature targeted test classes. This is **not** an empty scaffold.
- No full-system release qualification can be granted at reviewed HEAD: main H2 suite, domain-security PG and exact-SHA admission runs FAIL; some populated upgrade and real-infra jobs succeed independently.
- Two **observed** CI failure classes are independent: H2 Liquibase syntax versus PostgreSQL over-connection during multi-JVM tests. Treating every downstream test as a separate application bug is unsound.
- **Highest semantic risk:** existing generic `AuthenticatedSalesCommands.fulfill` (delegating to `SalesInventoryCommandService.fulfill`) can complete a `CONFIRMED/PREPARING` order without QR pickup proof, while `SalesPickupHandoverService.complete` permits only `READY_FOR_PICKUP` + credential + warehouse scope + explicit confirmation + handover receipt. Since `crm.sales_order` has no durable fulfillment mode discriminator in the examined schema, a QR-initiated order's proof obligation cannot be assumed enforceable at *all* service entrypoints. This is an **observed policy/entrypoint bypass possibility**, not a claim that the general staff fulfillment is unauthenticated or that an exploit was run.
- **Second semantic risk:** generic `SalesInventoryCommandService.cancel` forbids READY but allows PREPARING while `sales_order_pick_confirmation` can already contain immutable picks; physical reverse/unpick semantics are not demonstrated. Needs explicit decision and two-path tests, not a speculative schema rewrite.
- **Authorization scope decision:** warehouse assignment is required in preparation/pickup/return paths, but generic seller confirm/fulfill uses paired tenant-level SALES/INVENTORY permissions without the same warehouse staff assignment. Determine SMB policy for multi-warehouse employees and prove it; no silent global object grant.
- **CRM functional gap:** `CrmInteractionService.record` has Kind NOTE but no meaningful note body or encrypted result data in the returned model/mutation. TASKS is intentionally deferred, yet CRM note semantics are a distinct product decision (P1 unless designated core).
- **Strong existing evidence:** `CommercialCoreE2EQualificationTest.skuToSellerOfferToCheckoutToFulfillmentToReconciliation` does exist. It uses synthetic identity and generic staff `sales.fulfill`, not one complete CRM-linked QR secure pickup/return journey. Do not state that there is no E2E at all.

## 1. Exact evidence provenance and latest CI for reviewed SHA

**HEAD:** `3485e36f9c3f3ad279b16dfd1337aa9b7d28828e`. GitHub Actions at this SHA:

| Run ID | Workflow | Conclusion observed | Boundary |
|---|---|---|---|
| `45060491949` | RM Module JUnit H2/PG | FAILURE | full suite not qualified |
| `45060491946` | RM Domain Security / PostgreSQL | FAILURE | security/PG integrated gate open |
| `45060486158` | RM Exact-SHA Qualification Admission | FAILURE | candidate cannot be admitted |
| `45060486113` | SALES 08.4 populated PG | SUCCESS | scoped migration only |
| `45060486076` | CRM 1.2 populated PG | SUCCESS | scoped migration only |
| `45060486127` | INVENTORY 0081 populated PG | SUCCESS | scoped migration only |
| `45060486074` | RustFS Real Infrastructure E2E | SUCCESS | scoped storage tests only |

**Root cause examined in prior same-code SHA logs** (`9feeb812...`, still present in code and later failed runs; rerun evidence must be read at final candidate SHA):

1. [run 45058719185](https://github.com/alimbetov/RM/actions/runs/45058719185), `module-h2` job `137319615781`: H2 raises `JdbcSQLSyntaxErrorException` for the partial unique index in Liquibase `0105-crm-r22b-lifecycle-dispute`, `CREATE UNIQUE INDEX ... WHERE status IN (...)`. Secondary `ApplicationContext failure threshold` messages reflect failed bootstrap, not 100 independent FILES defects.
2. [run 45058713523](https://github.com/alimbetov/RM/actions/runs/45058713523), `qr08-full-postgresql-regression` job `137319599291`: child-JVM QR-06B B31/B33/B35 startup rejected by PostgreSQL `FATAL: sorry, too many clients already` during Liquibase. Its `qr08-r0-postgresql` and `qr08-populated-postgresql-upgrade` subjobs were SUCCESS; full regression FAILURE.

**Do not fix CI by:** disabling Liquibase, modifying applied SQL without integrity review, skipping mandatory stress/race tests, changing `max_connections` blindly, or accepting a green focused job as full platform admission.

## 2. Technical stack / review scope

Java 21, Spring Boot 4.1.3, Spring MVC (HTTP conditionally present, development STOP), JPA/JDBC, PostgreSQL authoritative DB, H2 smoke/compatibility, Liquibase SQL migrations. One modular monolith, 15 bounded contexts enumerated by `DomainModule`; CRM Interaction counted separately for functional assessment. No need for new microservices or Redis.

Inspected source path families: `src/main/java/kz/rm/{iam,organization,crm,catalog,storefront,autoorder,sales,inventory,files}`, `src/main/resources/db/changelog/db.changelog-master.sql`, `src/test/java/kz/rm/{integration,sales,autoorder,crm,catalog,storefront,organization,iam}`, and current workflows/review docs. 71 Java test-source files exist in tree; this is **not** executed test count or proof that each test ran.

No code coverage instrumentation, new Java test execution, independent database inspection or benchmarking was performed by this documentation-only audit. Inferences must be checked by the exact-SHA implementation gate.

## 3. End-to-end source trace and data authority

| Step | Application/domain boundary (actual file/method) | Authoritative data/effects | Security/transaction proof needed |
|---|---|---|---|
| Staff tenant | `SelfServiceTenantOnboardingService.createFirstTenant/selectTenant`, `TenantMemberEnrollmentService.invite/accept/list`, `TenantRoleService`, `TenantAuthorization.requireForCommand` | IAM account, workspace, membership, grants | VerifiedActor + fresh locks, invite consent, explicit grant, revoke-first/command-first |
| Warehouse | `AuthenticatedOrganizationLocations.create/update/deactivate/reactivate/close/get/search`, `WarehouseStaffScopeService.assign/revoke/requireAssigned` | `public.org_location`, staff scope | location inventory blockers, owner permission, warehouse staff assignment and status |
| Customer | `AuthenticatedCrmCommands/Queries`, `CrmCustomerService`, `CrmContractService`, `CrmInteractionService` | customer/contact/contract/interactions/PII and events | CRM_WRITE/READ/PII_READ, AAD isolation, archive and idempotency |
| Verified buyer | `CrmBuyerLinkService.propose/confirm/revokeByBuyer/disputeByBuyer/adjudicateDispute/orderHistory` | tenant-local immutable identity proof | buyer ACTIVE and current verification; seller must pass CRM_READ + SALES_ORDER_READ |
| Catalogue | `CatalogItemService.createDraft/addPriceRevision/publish/archive` | `crm.cat_item`, `crm.cat_price`, `crm.cat_item_event` | CATALOG rights, version, inventory + offer archive blockers |
| Offer | `StorefrontManagementService`, `StorefrontOfferService.createDraft/updatePrice/publish/pause/hide/get/priceHistory` | `crm.store_front`, `crm.store_offer`, offer version/current price | seller permissions (currently SALES module code), CAS, PRODUCT/PUBLISHED |
| QR publication | `PublishedOfferQrService.issueOrGet/reprint/revoke/reissue` | stable `crm.store_offer_qr_ref` | published offer check, unique alias, owner, replay and revoke |
| Public read | `PublishedOfferCardQuery.findPublished`, `CatalogPublicProductMedia`, `RegionalPublicAvailabilityPort` | safe projection, approved media, advisory stock band | published ACTIVE, workspace status, no PII, FILES safety, stock not guarantee |
| List checkout | `PublishedQrCheckoutService.checkout` → `AutoOrderService.checkoutPublishedQr` | one `sales_order`, lines, immutable accepted price, receipt | price/version ack, QR active, seller scope, request fingerprint, no reserve at PLACED |
| Negotiation | `SalesPriceProposalService.submit/buyerRespond/sellerRespond` → `AutoOrderService.checkoutAcceptedQuote` | immutable proposal/accepted quote; one existing SALES order | alternating side, CAS, expiry, same checkout receipt namespace, consumption once |
| Reserve | `AuthenticatedSalesCommands.confirm` → `SalesInventoryCommandService.confirm` → `OrderInventoryPort.reserve` | one seller order, ACTIVE stock reservations | paired SALES_CONFIRM + INVENTORY_RESERVE, selected active warehouse |
| Pick | `SalesOrderPreparationService.startPreparing/confirmPicked/markReadyForPickup` | PREPARING, pick confirmations, READY | warehouse assignment, ordered qty, no debit |
| Secure pickup | `SalesPickupHandoverService.issue/preview/complete` → `OrderInventoryPort.fulfill` | single receipt, status COMPLETED, movement, credential consumption | buyer proof, staff scope, same transaction, two scans/crash/retry |
| Return | `SalesReturnRequestService.request`, `SalesReturnReviewService`, `SalesReturnReceptionProofService`, `SalesReturnPhysicalService.receive/inspect/post`, `SalesReturnQuarantineResolutionService.resolve` | RMA, reception, inspection, accepted/other disposition, posting | original handover FK, qty bound, warehouse, credential, exact-once inventory |
| Packaging | `PackagingServiceCatalogService`, `OrderPackagingService.offer/decide` | SALES order service line immutable commercial snapshot | QR08/R0 service-only, zero stock/finance; optional subqualification |

**Ownership:** IAM identities/access; ORG locations; CRM customers/consent/link provenance; CATALOG SKU and catalogue price revisions; STOREFRONT current sale offer visibility/price; SALES order/quote/status/handover/return and snapshot; INVENTORY quantities/reservations/movements; FILES media scan/bytes. No cross-owner direct writes in new implementation. `crm.sales_order` status `COMPLETED` indicates stock fulfillment, **not** payment/delivery/refund.

## 4. Source-proven defects and open safety decisions

Severity `P0-DEFECT` = observable failing CI; `P0-DECISION` = architecture/business policy missing, hazardous to proceed; `P1-GAP` = incomplete service behavior or evidence. Presence of a plausible bypass is not execution proof.

### A-01 P0-DEFECT: H2 DB migration hard-fails

Source: `db.changelog-master.sql` lines ~3338–3346, changeset 0105 lacks `dbms:postgresql` while using PostgreSQL partial index syntax on both customer and buyer columns. Current single changeset also drops prior uniqueness and changes checks, so a careless H2-skip would break H2 CRUD bootstrap and may lose meaningful uniqueness semantics.

**Owner:** IAM/CRM schema + QA.  
**Remedy decision required:** preserve already applied PostgreSQL changeset/checksum and one-to-one ACTIVE mapping semantics; choose controlled forward compatible test migration/DBMS-specific routing, not retroactive unreviewed SQL rewriting.  
**Oracle:** PostgreSQL forbids simultaneous PENDING/CONFIRMED/DISPUTED mappings per customer/buyer, allows historical REVOKED+new explicitly authorized mapping; H2 bootstrap and its meaningful test counterpart succeed; clean and populated PG remain sound.

### A-02 P0-DEFECT: full PostgreSQL regression exhausts connections

Source: QR-06B multi-JVM `QrWarehouseHandoverQualificationTest` B31/B33/B35 and `QrHandoverProcessWorker`; failure logs show child Spring Liquibase startup rejected by PostgreSQL max connections.

**Owner:** QA/CI.  
**Remedy:** audit Maven forks, Spring contexts, Hikari max-pools, child JVM count, lifecycle/close, PostgreSQL max_connections, test concurrency; set explicit connection budget, isolate heavyweight mandatory suites as needed. Keep workload and PG invariants; separate exact SHA qualification job is acceptable only if required by manifest.  
**Oracle:** zero refused connections in a mandatory run; B31/B33/B35 actually executed and passed, no unexpected skips; all services regress cleanly.

### A-03 P0-DECISION: untyped generic fulfill may bypass mandatory physical proof

**Code:** `AuthenticatedSalesCommands.fulfill` accepts VerifiedActor and calls `SalesInventoryCommandService.fulfill`; generic fulfillment admits `CONFIRMED` or `PREPARING`, checks paired permissions, creates `COMPLETED` + inventory debit without requiring `SalesPickupHandoverService.complete` or one-time pickup credential. `SalesPickupHandoverService.complete` instead enforces READY, staff warehouse scope, credential and receipt. `crm.sales_order` initial DDL lacks `fulfillment_mode` / pickup-mandatory channel; current check does not identify a QR-origin order. A conditional `SalesAuthenticatedCommandController` also already exposes generic fulfill when OIDC enabled (but public HTTP admission remains HOLD).

**Not claimed:** generic fulfillment is unauthenticated; it is guarded by tenant role and stock permissions.  
**Required decision:** explicit order/fulfillment policy that safely distinguishes when general staff fulfillment is legal versus when pickup proof is mandatory. If all selected QR orders must be picked up, a durable trusted discriminator or equivalent owner-owned state/evidence policy is needed; do not infer by storefront ref, email, QR alias existence or external DTO. Choose **one** state machine with typed fulfillment modes OR a single required handover route. No parallel `COMPLETED` writers for the same proof-required order.

**Oracle:** for pickup-required order, direct `AuthenticatedSalesCommands.fulfill` and currently existing HTTP facade **DENY** with zero status/events/movements; correct READY→credential→staff confirmation produces exactly one receipt/debit. For deliberately allowed non-pickup order, existing backward-compatible fulfillment continues only under explicitly approved contract. Tests for both entrypoints, two JVM, both IAM-revoke orders.

### A-04 P0-DECISION: PREPARING cancellation vs immutable picked goods

**Code:** `SalesInventoryCommandService.cancel` refuses READY_FOR_PICKUP but allows PREPARING; `SalesOrderPreparationService.confirmPicked` already writes immutable `sales_order_pick_confirmation`. Generic cancellation releases reservation and writes CANCELLED, but no pick reversal/physical return-to-shelf process was identified in this path.

**Required decision:** until qualified, either reject CANCEL once pick begins (allow cancellation of PREPARING only before first PICK), or provide auditable `UNPICK`/return-to-stock procedure with warehouse proof; no automatic silent deletion of pick history.  
**Oracle:** `cancel×pick` both commit orders, no physically picked orphan items, no stranded reserved quantity, immutable original pick events retained, idempotent retry/no negative stock. Existing prior cancellation policy and migrations must remain consistent.

### A-05 P1-GAP: warehouse object scope differs by operation

**Code:** `SalesOrderPreparationService`, `SalesPickupHandoverService` and physical return require `WarehouseStaffScopeService.requireAssigned`. Generic `SalesInventoryCommandService.confirm/fulfill` require tenant SALES/INVENTORY grants but do not invoke assignment.

**Decision:** define initial SMB policy: tenant-scoped order manager may select any eligible warehouse? or always explicit warehouse assignment? Do not silently impose new grants/deny on historical commands. Document who owns selected warehouse, effective membership and revoke fencing.
**Oracle:** assigned warehouse A employee cannot act at warehouse B for operations designated location-bound; tenant-level seller role can only do expressly approved global operations; concurrent assignment revoke×confirm/pick/post is serialized; denied commands have zero business side effects.

### A-06 P1-GAP: CRM interaction NOTE lacks business content

**Code:** `CrmInteractionService.Kind.NOTE` and `record` persist kind, contact/customer, timestamps, responsible and request key; no note text or meaningful outcome parameter or field. `CrmInteractionService.search` has offset pagination and independently sampled count/page queries.

**Decision:** TASKS remains STOP. Determine whether encrypted purpose-bound note/result is necessary for SMB P0 customer journey or a deferred P1; if needed, independently qualify PII classification, AEAD AAD, owner/CRM_PII_READ, populated migration, redaction, full event continuity. Avoid plaintext column/log or fake note content.

### A-07 P1-GAP: cross-domain E2E proof is narrower than target

**Code:** `CommercialCoreE2EQualificationTest` tests Catalogue→Storefront→AutoOrder→Generic fulfill→Inventory reconciliation, plus PG races for suspend/checkout/archive/receipt. But it sets up users/tenant/warehouse through fixtures and uses synthetic JwtDecoder; does not prove complete trusted self-onboarding+membership+CRM buyer-link+QR secure pickup+return from one business fixture. It does not establish independent OIDC/provider or UI readiness (intentionally out of scope).

**Remedy:** add a service integration **journey** that reuses real application commands for onboarding, CRM identity proof, offers, orders, pickup/returns. Keep fixture/bootstrap only for trusted immutable platform preconditions clearly labeled. Use real multi-domain rows and negative checks.

### A-08 P1-GAP: public card and checkout are separate read/admission moments

**Code:** `PublishedOfferCardQuery.findPublished` reads allowed public fields, FILES media and optional advisory regional stock in separate steps. `AutoOrderService.checkoutPublishedQr` correctly rechecks published QR, offer version/current price under locks, as well as seller status; `PLACED` does not reserve stock. This is **not** an error to paper over by promising guaranteed stock or single public read snapshot.

**Remedy:** document `PUBLIC_ADVISORY` contract, fallback media/region unknown, no exposure after revocation in new read; ensure price/version acknowledgement, failures and no buyer provisioning on stale price. Use two transaction orders `pause/revoke/changePrice × card/checkout`.

### A-09 P1-GAP: migration/test environment boundaries

**Code:** GitHub Actions and Liquibase PG-specific changesets/ H2 compatibility; current full regression failures block coverage claims. Some domain PG tests check `getDatabaseProductName` and skip PG-only branches on H2; H2 green never substitutes for PG execution.

**Required:** manifest of exact mandatory methods and skips per environment; normalized line-level test source→XML evidence, populated migration and two-worker real PG. Do not base acceptance on #tests declared or historical run statuses.

### A-10 P1-GAP: packaged services and broad finance should not block base SKU-to-pickup

**Code:** `PackagingServiceCatalogService`, `OrderPackagingService` already exist and 24 named test methods appear in `OrderPackagingQualificationTest` source; QR-08 R0 must have no stock movement or invoice/payment implication, R1 physical consumables not admitted.

**Decision:** QR-08 is separate additional deliverable; its regression still cannot be skipped in a candidate containing packaging code. Successful focused PG and populated PG alone do not equal whole-project SUCCESS.

## 5. Data integrity and locking invariant matrix

| Aggregate / boundary | Lock or version | Immutable evidence | Critical race | Fail invariant |
|---|---|---|---|---|
| IAM account + workspace + membership | transaction-held actor/workspace lock, freshly checked role | role/account events | suspend/revoke × any business mutation | new mutation after committed revoke denied |
| ORG location + warehouse assignment | expectedVersion + location/warehouse locks | lifecycle/staff evidence | deactivate/close × receipt/confirm/pick | no use of inactive warehouse; no lost stock |
| CRM customer/contact/buyer link | owner row/CAS, tenant composite FK, one active link uniqueness | link events, dispute decision | archive/propose/confirm/delete × checkout | false customer→buyer association forbidden |
| CATALOG item | row lock, CAS, archive usage port | cat_item_event, price revisions | archive × receive/offer-publish | no archived item with fresh valid stock sale |
| STOREFRONT offer | row_version + lock, server pricing | price history, order snapshot | price/pause × checkout/QR issue | accepted price/title/SKU remain coherent |
| SALES checkout | seller scoped stripe + request fingerprint | sales_checkout_receipt, order lines/status | same key different payload; concurrent accept | at most one order; no mixed/cross-mode replay |
| SALES confirmation/reserve | order row → order line → Inventory port | reservation/event | cancel×confirm; two SKU orders | all-or-nothing full-line reserves |
| SALES pick | order CAS, location scope | pick confirmation + prep receipt | pick×cancel, ready×revoke | no orphan pick or premature stock movement |
| SALES completion | generic fulfill and QR handover (policy requires decision) | movement and pickup receipt (QR) | competing fulfill paths × pickup proof | exactly one debit and required proof |
| RMA receive/inspect | return row CAS/lock, proof | physical receipt+inspection | duplicate scan/inspect and suspend | no sellable restock before inspection |
| RMA posting/quarantine | return row lock + Inventory port | stock movement, posting, resolution | post×post, resolve×resolve, partial failure | conservation + exactly-once posting |
| FILES public media | owner+scan+publish ACL | safe variant, content hash | file quarantine/revoke × card read | no private/original/infected bytes leaked |

**Cross-domain lock order:** use the actual reviewed lock DAG per service (checkout stripe, IAM account/workspace, storefront, offer/catalog, order, inventory stripe/location/balance, credential/return), compare both directions against each other. A global order must be derived from verified call graphs; do not invent a universal lock order that conflicts with working paths. Test deadlock detection (`40P01`), lock timeouts and retries using two real PG connections with barriers.

## 6. Numerical and state oracles: mandatory example

Given tenant T, published PRODUCT SKU S, warehouse W, initial `on_hand=10`, `reserved=0`, one order quantity 3 at accepted unit price 75 KZT:

| Action | Order state | Stock on_hand | Stock reserved | Sale total | Fulfillment movements |
|---|---|---:|---:|---:|---:|
| Buyer checkout | PLACED | 10 | 0 | 225 KZT | 0 |
| Seller confirm | CONFIRMED | 10 | 3 | 225 KZT | 0 |
| Prepare/pick/markReady | READY_FOR_PICKUP | 10 | 3 | 225 KZT | 0 |
| Valid explicit QR handover | COMPLETED | 7 | 0 | 225 KZT | 1 negative movement of 3 |
| Exact handover retry | COMPLETED | 7 | 0 | unchanged | still 1 |
| Authorized, inspected RESTOCK of 1 | original still COMPLETED | 8 | 0 | unchanged | 1 fulfillment movement plus 1 return movement |
| Inspected QUARANTINE of 1, before resolution | original still COMPLETED | 7 (in isolated fixture) | 0 | unchanged | no sellable positive restock |
| Rejected/stale/foreign attempt | previous authoritative state | unchanged | unchanged | unchanged | unchanged |

Each row requires **independent SQL queries of authoritative table values, receipts, immutable event counts and source references**, not just checking returned DTO. Scenarios with two returns must account for `fulfilled - accepted/posted return` before authorizing any quantity. The quarantine row is a distinct fixture from RESTOCK example.

## 7. Service-qualified business scenario catalog — prioritized clusters

Actual JUnit coverage **exists** and must be mapped, not recreated: `CommercialCoreE2EQualificationTest`, `SalesInventoryRbacQualificationTest`, `QrWarehouseHandoverQualificationTest`, `QrNegotiatedCheckoutQualificationTest`, `PublishedQrCheckoutQualificationTest`, `CrmBuyerLinkQualificationTest`, `StorefrontManagementQualificationTest`, `OrganizationLocationLifecycleQualificationTest`, `OrderPackagingQualificationTest` and others.

**CORE-P0** groups (must map to executed tests on release SHA):
- IAM: active verified user; confirmed email; tenant onboarding; employee invite/accept/role; tenant A/B denial; revoke/global suspend in both commit orders.
- CRM: customer/contact/contract; encrypted PII and consent; customer-buyer explicit proof and own order history; archive/identity delete/dispute; unwanted mutations denied.
- Catalog/offer: SKU/catalog price/source locale; offer publication and seller price; pause/resume; archive and live stock blockers; immutable sale price; separate publication and active QR.
- Sale/stock: quantity/warehouse eligibility, confirm all reserves atomic, insufficient-stock rollback, two orders same SKU, cancel/confirm race, correction/stocktake/transfer/reconciliation, receipt/source invariant, generic fulfillment policy.
- QR: stable unique alias; safe anonymous card and media; list checkout price ack; alternating negotiations/expiry/accepted quote; exactly-once quote consumption and receipt namespace.
- Pickup/return: full picks; READY validation; warehouse scope; issued/revoked/consumed credential; preview no effect; explicit handover once; double scan; crash-before/after-commit; RMA, warehouse inspection, quarantine, restock/dispose.
- Full E2E: one seller, real service staff grants, one buyer and linked CRM, stock, negotiated and list paths, RMA, exactly-once accounting of quantity, no PII leak.

**EXTENDED-P1** groups: pagination under concurrent insert, large catalog/search, deeper 99 CRM synthetic variants, optional packaging R0, media failure/restart, reports, multi-location operations, performance under fixed workload. P1 tests become P0 automatically if they defend an invariant used by P0.

Do not equate the proposed 120-row registry with 120 executed JUnit methods. Each required scenario needs an exact mapping `scenarioId→existingMethod/newTest→SHA→workflow→Surefire→verdict`. H2 smoke and PG-only branches must be separately accounted.

## 8. Exit gate and implementation priorities

1. **G0 CI integrity P0:** H2 0105 compatibility + PostgreSQL connection-budget deterministic full regression, exact-SHA manifest. No changes to business policy solely to make tests green.
2. **G1 business contract P0:** decide and implement/qualify generic fulfill versus pickup-specific handover; resolve PREPARING cancellation handling; warehouse permission scope decision; full customer→offer→order→inventory source consistency.
3. **G2 QR functional P0:** list and accepted quote flows on one authoritative order, secure pickup proof not bypassable, return line/receipt/inspection/warehouse posting exactly once, no claim of payment.
4. **G3 platform evidence P0:** common head H2/PG + populated migrations + security/cross-domain/fault suites; no unexpected skipped core scenario.
5. **G4 optional add-ons P1:** QR-08/R0 packaging independent and complete, operational CRM note content if approved, extended reports/media/performance.
6. **Only afterward:** TASKS in its own phase; later controllers/UI, real IdP/provider/scanners, pilot and production qualifications; KF-01..05 remain parked.

**FINAL VERDICT:** `SPEC TECHNICALLY ALIGNABLE — SERVICE FUNCTIONAL HOLD` at reviewed SHA. Architecture and code are sufficiently developed to close functional gaps by minimal targeted changes. There is no basis for a production PASS or any claim of tested throughput from the current full-suite failures. The most urgent uncovered business correctness obligation is the **two completion entrypoints / proof-required order boundary**; it must be resolved before calling RM QR Commerce functionally complete.
