# RM-SVC-R2 — Tenant isolation audit across search and filtering

**Ветка:** `base_dev`. **Baseline:** `617744d45da0ce999b892b3ddfa80862b1f2bfe0`.  
**Метод:** исходный код Spring/JDBC/JPA, Liquibase и тестовые сценарии. Это **статический аудит + проверка существующих CI-тестов**, не отдельный запущенный full cross-domain adversarial test.  
**Решение:** **CONDITIONAL PASS** для реализованных tenant-scoped query boundaries. **NOT YET QUALIFIED** для будущих фильтров и полного post-filter object access. Production и main остаются HOLD.

## 1. Зафиксированный принцип tenant isolation

Для каждой бизнес-операции `T` — выбранный **IAM workspace tenant UUID** (`public.iam_workspace.id`), относящийся к организации, а не `organization_id`, `user_id` или seller/buyer ID. Доменные таблицы `crm.*.tenant_id` и `public.org_location.workspace_id` должны ссылаться на этот один и тот же `workspace.id`.

**Tenant membership != tenant ownership == actor identity.** Пользователь может состоять в нескольких организациях; он вправе видеть только данные **явно выбранного и авторизованного** tenant, не объединённый список всех организаций без отдельной осознанной операции. UUID tenant никогда не выводится из произвольных фильтров, ownerId или «догадок» по customerId/itemId. `VerifiedActor.userId()` — отдельная ось идентичности пользователя.

Сервисная операция допускается, только если:
1. `VerifiedActor` валиден и не истёк, `TenantAuthorization` подтверждает active actor, active organization workspace, active membership и effective functional permission;
2. SQL/JPA на **каждой** таблице/подзапросе, способном ввести данные чужой организации, содержит `tenant_id=T` либо эквивалентное составное соединение `a.tenant_id=b.tenant_id=T`;
3. если операция требует object-level authorization, владелец сущности проверяет и этот доступ; `tenant_id` не заменяет `customerId/ownerId/locationId` permission;
4. DTO, count, total, nextCursor, order, history, file read и error messages не раскрывают записи другого tenant;
5. любой `customerId`, `fileId`, `itemId`, `locationId`, `orderId`, `ownerId` другого tenant приводит к пустой выборке/отказу, а не к переключению tenant context.

**Важно:** текущая защита — **application-layer tenant isolation**, не PostgreSQL RLS. Прямое подключение под прикладной DB-role с обходом сервисов не входит в доказанную гарантию. Не утверждать, что tenant_id уже делает невозможным доступ любой произвольной SQL-команде приложения.

## 2. Таблица результатов ревью

| Домен | Текущий фильтр / SQL-предикат | Проверка IAM | Аудиторский вывод |
|---|---|---|---|
| CRM Customer | `CrmSearchService.customers`: `WHERE tenant_id=?`; type/status/region/company name; count и row SQL используют тот же predicate | `CRM_READ`, `TenantAuthorization.requireForCommand` | **PASS для tenant scope**. Новые categoryCode, contract expiry, период ещё не реализованы и требуют tenant-safe JOIN и новых тестов |
| CRM Contact | `CrmSearchService.contacts`: `WHERE c.tenant_id=?`, contact_secret JOIN по tenant+contact | `CRM_PII_READ` | **PASS для tenant scope**. `customerId` сейчас опционален: любой разрешённый CRM_PII_READ сотрудник видит tenant-wide contacts. Не claim per-customer portfolio isolation без дополнительной политики |
| ORGANIZATION | `OrganizationLocationRepository.search`: `l.workspaceId=:tenant`, type/status/name | `ORGANIZATION_LOCATION_READ` | **PASS для workspace scope**. KATO filter не реализован. `name` LIKE поддерживает wildcard, если не экранировать, — проблема точности фильтра, не смена tenant |
| FILES | `FileAttachmentService.listCustomer`: `a.tenant_id=?`, owner type/id, joined object by `f.tenant_id=a.tenant_id AND f.id=a.file_id`, scanned ACTIVE object | `FILES_READ` + `CrmFileOwnerAccess.requireCustomerRead` checks CRM_READ+CRM_PII_READ+ACTIVE COMPANY | **PASS для текущего COMPANY owner**; PERSON, CATALOG, SALES owner types не допущены. Новый generic `listMedia(ownerType,ownerId)` пока **NOT IMPLEMENTED** |
| SALES | `SalesOrderRepository.search`: `WHERE tenant_id=?`, status/date; count и rows tenant scoped | `SALES_ORDER_READ` | **PASS для seller workspace**, buyer identity скрыт из DTO. Фильтра `customerId` нет; `sales_order` связан с IAM buyer, не с CRM customer; no implied customerId join |
| INVENTORY | `StockBalanceRepository.search`: `WHERE tenant_id=?`, locationId/itemId/availableOnly | `INVENTORY_STOCK_READ` | **PASS для tenant scope**, FK `(tenant_id,location_id)` и `(tenant_id,item_id)` в balance обеспечивают tenant-local ссылочную целостность; SKU join, movementType filter и owner-specific warehouse portfolios не реализованы |
| CATALOG | `crm.cat_item`: PK `(tenant_id,id)`, unique `(tenant_id,sku)`, FK tenant; `cat_item_i18n` и `cat_price` composite FK по tenant+item | Нет готового `CATALOG_READ` в `DomainPermission`, полноценный каталоговый query service не найден | **SCHEMA PASS / QUERY NOT IMPLEMENTED**. Seller/price/category/modelCode filters проектные, необходимы ACL и tenant-safe pricing join |

`CRM customer` PERSON names remain encrypted in `crm_pii`; cannot introduce plaintext name index or cross-tenant decryption. FILES binary read checks separate `FILES_READ` and CRM owner permissions and validates storage-key scope.

## 3. P0 contract for ALL cross-domain search

1. All new application facades: `search(VerifiedActor actor, UUID tenantId, TypedFilter filters, PageWindow page)`; no trusted actor ID or tenant from SQL text, URL/DTO/ownerId.
2. Determine exactly one active tenant per request and authorize **before** any query/counter/owner lookup. If user is member of A and B, `search(T=A)` must not return B.
3. For every join, `JOIN joined j ON j.tenant_id=root.tenant_id AND j.id=root.id`, additionally `root.tenant_id=:tenant`; never `JOIN ... ON j.id=?` alone. Tenant-wide/global IAM or GEO references may use their own explicit scope model, never pretend they are tenant rows.
4. Each supplied reference filter (customerId/locationId/itemId/ownerId/orderId) is interpreted **inside current T only**. Foreign ID yields zero rows for read, deny for mutation, and generic absence response; do not return object data through validation messages.
5. The selected tenant and owner scope must be signed/bound in cursor. HMAC validation does **not** replace fresh IAM/owner authorization on each page.
6. Count/hasNext and sorting operate on **authorized set only**, not global count or rows that are filtered after LIMIT by Java.
7. For mutable states (archive, suspension, membership revoke), read must re-check current visibility. No global cached list that omits `tenantId` in cache key.
8. Tenant membership is not itself enough for per-customer/per-warehouse ownership partition: if business requires an employee to see only *assigned* accounts, define an explicit portfolio assignment model, permissions and SQL predicate, then qualify them. No implicit claim of this ability in current RM.
9. Avoid new generic search service. Shared `PageWindow` and typed filter conventions only; SQL stays in domain repository and reflects actual owners.
10. Tenant-keyed uniqueness constraints and parent/child composite FK must be reviewed with every new schema: CRM category, contract, catalog model, pricing, order lines, file attachments.

## 4. Concrete acceptance matrix, two tenant A/B

Seed **two active organization workspaces** A/B with owners, managers, users and category/sku collisions. Populate both with same category codes, company names, region, KATO, modelCode, SKU, status, date and multiple rows having identical createdAt. Record known UUIDs of B.

- **CRM Customer:** A actor search type/status/region/company/category/contract status+expiry → A only. B UUID `getCustomer` or used as customerId filter → deny/empty. Existing list does not yet support category/contract filters.
- **CRM Contact:** A actor with `CRM_PII_READ` filters B customerId → empty; no decrypted B PII. A actor without PII read → SecurityException even when no rows. If assignment policy is not introduced, tenant-wide contacts are allowed for permitted roles (explicit documented business decision).
- **ORGANIZATION:** A actor searches same name/type/status/KATO that exist in B → only A. B locationId GET → not found/deny; no code+name collision cross-tenancy.
- **FILES:** A actor lists/reads B ownerId, fileId or attachmentId → deny/empty before storage I/O; file metadata/storage key never disclosed. BINARY read must deny even if a user copies exact B object key. Existing owner access COMPANY only; future typed owner adapters qualified individually.
- **SALES:** A actor searches same status/date as B → only A. B orderId detail/history deny. B customerId filter cannot be implemented before a qualified CRM customer → sales order link; no SQL join by IAM buyerId as surrogate CRM customerId.
- **INVENTORY:** A actor location B UUID + item B UUID → empty. Same SKU in A/B should resolve via `(tenant_id,sku)`. Negative tests for stock availableOnly, movement filters and cross-tenant location+item FKs; no direct balance CRUD.
- **CATALOG:** A tenant same SKU/modelCode as B tenant allowed, B itemId search cannot leak; catalog i18n/price subqueries joins by tenant+item; pricing filter must use effective price revision and no cross-tenant MIN/MAX. Query service and permission must be implemented first.
- **Cursor:** Create A page token and attempt to use under B/changed user or filter → reject; revocation of actor role between page1/page2 → reject; page1/page2 still only A.
- **Concurrency:** Suspend workspace A while query starts; assert proper fail-close/admission for affected queries; no mixed tenant transactions.
- **Database:** Check composite FK `tenant_id,parent_id` for new relations and uniqueness scoped per tenant; prohibit accidental globally-unique `sku` or category code.

**Important:** these are **new required tests**, not a claim of current passing E2E. Existing tests include `CrmR1aQualificationTest.organizationIsolationBlocksOtherTenantsEvenWithKnownIds` and `...customerAndContactSearchAreBoundedTenantScopedAndPrivacyMinimized`; `OrganizationLocationQueryQualificationTest.paginationAndFiltersUseTenantBoundary`; `SalesOrderQueryQualificationTest.verifiedReadFacadeEnforcesActorTenantAndPagination`; `StockBalanceQueryQualificationTest.verifiedStockSearchPaginatesFiltersAndDeniesCrossTenant`; `FilesR10bQualificationTest.wrongTenantAndRevokedPermissionNeverReadBinaryStream`.

## 5. Findings and backlog

| ID | Priority | Finding | Required action |
|---|---|---|---|
| TENANT-P0-01 | P0 | Cross-domain filters in R2/R2A not implemented; blanket tenant isolation claim overstates evidence | Mandatory tenant boundary test for **every added filter, join, sort, cursor, count** |
| TENANT-P0-02 | P0 | No CATALOG search permission/query; catalog source schema is tenant-safe but functionality incomplete | Add `CATALOG_READ` as separate explicit permission and authenticated query facade; SQL tenant+kind/item filter |
| TENANT-P0-03 | P0 | SALES customerId filter cannot rely on IAM buyer ID as CRM customer key | Define real CRM-to-SALES authoritative association before shipping customer filter |
| TENANT-P0-04 | P0 | FILES generic owner list not qualified | OwnerType-specific domain access policy; no generic unchecked ownerId query |
| TENANT-P0-05 | P0 | CRM Contact list currently tenant-wide when `CRM_PII_READ`; not per-assignee | Decide if tenant-wide permission is intended. If not, add explicit customer-portfolio ownership policy before expanding |
| TENANT-P1-06 | P1 | Stock location filters tenant-safe but no per-warehouse assignment restriction | Decide internal visibility: all tenant stock vs assigned warehouses; implement warehouse scope if required |
| TENANT-P1-07 | P1 | Organization name search interprets SQL LIKE wildcards | Escape user literal and qualify pagination; security boundary remains tenant-safe |
| TENANT-P1-08 | P1 | No RLS as defense-in-depth against direct SQL programming error | Assess DB RLS as separate ADR if risk/operations require it; not blocker for correct service-layer P0 isolation if verified tests/DB role constraints |
| TENANT-P1-09 | P1 | Current offset/COUNT patterns not protected for large datasets | Use R2A cursor/keyset, bounded OFFSET and no global COUNT leakage |
| TENANT-P1-10 | P1 | Missing tenant-aware filters in category, customer contracts, catalog pricing | Composite FK/joins + two-tenant negative regression on additive migrations |

## 5A. TENANT-ISO-R1 implemented qualification increment (2026-11-22)

**Scope of this increment (new tests):**
- `CrmR1aQualificationTest.tenantIsoR1CustomersContactsJPAFiltersCountsAndAllPagesRemainScoped`: separate A/B tenant fixtures with same company labels and identical createdAt timestamps; paged count/order, known foreign UUID filters; BOTH JPA repositories scoped by tenant; PII contact lookup remains tenant-aware.
- `OrganizationLocationQueryQualificationTest.paginationAndFiltersUseTenantBoundary`: identical searchable warehouse names in different tenants, filter and list isolation.
- `StockBalanceQueryQualificationTest.verifiedStockSearchPaginatesFiltersAndDeniesCrossTenant`: foreign locationId/itemId filters return zero and both pages contain only requested tenant.
- `SalesOrderQueryQualificationTest.orderListPaginatesFiltersAndNeverReturnsBuyerIdentity`: foreign order absent from normal/status-filtered results and returned count; other tenant with no matching status sees no results.

**Existing independent FILES gate retained:** `FilesR10bQualificationTest` covers foreign tenant / revoked binary read and owner-bound attachment policies, alongside real RustFS/ClamAV CI. It is not represented as a newly added test in this increment.

**Deferred/unimplemented (explicitly not claimed qualified):**
- CRM customer categories/contracts and expiry cursor; they require additive DDL/services.
- CATALOG catalog query permission and typed model/category/price filter.
- SALES CRM customerId filter; current order relates to IAM buyer, not CRM customer.
- INVENTORY movement-type/SKU joins and cursor; current `StockSearch` accepts item UUID / warehouse / availability.
- FILES generic media owner-type pagination; existing COMPANY owner access only.
- Signed cursor pagination / keyset; current core services use bounded `page/size` and `OFFSET`.
- All-scope portfolio and warehouse-per-assignee authorization, which require a separately approved product rule.

This is a **two-tenant regression gate for EXISTING filters and repositories**, not certification of absent new features. Its final status must come from exact-HEAD CI output.

## 6. Release gate

**Suggested named gate: `TENANT-ISO-R1`**: integration tests in real PostgreSQL with TWO tenant fixtures across CRM/CONTACT/ORG/FILES/SALES/INVENTORY and, once implemented, CATALOG, plus future category/contracts/modelCode/owner filters. Test target is `0` unauthorized records, files, counters, cursors and bytes under any combination.

Must include H2 compatibility, PostgreSQL integration, populated PII and live RustFS/ClamAV for FILES paths. Only call exact-head green after runs on same SHA. No new controllers or merge into main.

**Conclusion:** Current implemented search queries generally include `tenant_id` / workspaceId and IAM permission; no demonstrated cross-tenant data leak in reviewed paths. **Cannot yet claim full per-tenant functional coverage of all requested filters**, because several are absent and per-object scopes (customer assignments/warehouse access) require business decisions. The remediation is service-level SQL and domain-policy qualification, not a new microservice.
