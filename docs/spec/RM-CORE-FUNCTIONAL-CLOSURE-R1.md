# RM-CORE-FUNCTIONAL-CLOSURE-R1 — завершение функциональности RM Business и RM QR Commerce

**Тип:** комплексное техническое задание / dependency-aware execution plan / service-only qualification contract.  
**Дата:** 2026-11-23. **Репозиторий:** `alimbetov/RM`. **Ветка:** `base_dev`.  
**Source baseline на момент подготовки:** `9feeb812e2b56d877e223c42bde0d55c09d4057b`.  
**Статус:** **R1.1 REVIEWED / ALIGNED WITH CURRENT RM / IMPLEMENTATION VIA SEPARATE APPROVED TASKS**. Создание этого документа не доказывает выполнение кодовых задач или прохождение CI.  
**Delivery boundary:** Java/Spring application/domain services + JDBC/JPA + PostgreSQL/Liquibase + H2 smoke + JUnit/Testcontainers; новые REST controllers/BFF/UI — **STOP**, public HTTP/OIDC admission — **HOLD**, production — **HOLD**, merge to `main` — **HOLD**.

## 0. Продуктовое решение и исключения

Активные целевые направления **только два**:

1. **RM Business (SMB)**: IAM/сотрудники → ORGANIZATION → CRM/клиенты → CATALOG/товары → STOREFRONT/предложения → SALES/заказы → INVENTORY/остатки и резервирование → исполнение, доступная история и аудит.
2. **RM QR Commerce**: опубликованный товар/витрина → стабильный QR → безопасная публичная карточка → купить по цене либо согласовать цену → единственный SALES checkout → резерв после подтверждения продавца → подготовка → одноразовая выдача → возврат/инспекция → корректное складское движение.

**RM Network** исключён из активных коммерческих целей; cross-legal-entity accounting, corporate consolidation и KF-05 Business Network не разрабатывать. **Не удалять** существующие tenant isolation, multi-location, transfer, RBAC и организационные сервисы, необходимые обычному малому бизнесу. Базовый продукт: одна юридическая/торговая организация с возможностью нескольких точек и складов в пределах ранее утверждённых моделей.

**TASKS, полноценный task lifecycle и CRM→TASKS integration — AFTER P0/P1**, вне данного release gate; не создавать временную task-подсистему. CRM Interaction `nextAction` и существующие статусы сохранять в действующем owner. **FINANCE полноценный ledger/COGS/payment reconciliation, MARKETING, SUBSCRIPTION, KF-01..05, ML и универсальный ERP — вне scope**. Не приписывать `SALES.COMPLETED` оплату, доставку или возмещение.

Новый документ является **актуальным исполняемым планом** для двух направлений совместно с `docs/strategy/RM-PRODUCT-STRATEGY-R2-SMB-QR.md` (заменяет только сегментный выбор R1, оставляя прежний файл историческим); не заменяет нормативный стандарт, доменные ТЗ, источники истины и ранее утверждённые технические контракты. Противоречие с R2 стандартом → `DECISION REQUIRED`, без молчаливой смены поведения.

## 1. Приоритет документов / основание

1. Норматив: `docs/architecture/RM-PLATFORM-STANDARD-R2.md`. Продуктовое решение: `docs/strategy/RM-PRODUCT-STRATEGY-R2-SMB-QR.md`.
2. Предметные ТЗ: `docs/spec/RM-TENANT-SERVICES-COMPLETION-R1.md`, `docs/spec/RM-SVC-MATURITY-COMPLETION-R2.md`, `docs/spec/RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md`, `docs/spec/RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md`.
3. QR: `docs/spec/RM-QR-R1.4-CANONICAL-FUNCTIONAL-TECHNICAL-CONTRACT.md`, `docs/spec/RM-QR-08-PACKAGING-SERVICE-FIRST-R0-R1-2026-11-22.md`; QR-07/08 review evidence.
4. CRM: `docs/strategy/RM-CRM-SERVICE-JOURNEY-REGISTRY-v1.json`, `docs/reviews/RM-CRM-R22D-IAM-DELETION-EVIDENCE-2026-11-22.md`, `docs/architecture/IAM-CRM-R22F-OIDC-REVOCATION-OUTBOX.md`.
5. Coverage: `docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json`; strategy: `docs/strategy/RM-STRATEGY-R4-GAP-PRIORITY-v1.json`; these are historical/scoped registries and MUST NOT be silently rewritten from this plan.
6. Competitive backlog `docs/spec/RM-COMPETE-R1-POST-STABILIZATION-KILLER-FEATURES.md` remains `PROPOSED/PARKED`; no authorization for additional features.

**Verification hierarchy:** actual source/DDL + exact-SHA executed Surefire XML + successful associated Actions jobs > past source reviews > plans. Never equate `TEST SOURCE PRESENT` with `EXECUTED PASS`.

## 2. Baseline: what exists and what is not proven

Source tree at `9feeb812...` contains IAM/Organization role and membership services; CRM customer/contact/contract/interaction and buyer link; CATALOG item/price; STOREFRONT management, offer, public card and QR; AutoOrder; SALES order/negotiation/preparation/handover/returns/packaging; INVENTORY receiving/stocktake/transfer/ledger/reservation/reconciliation. Existing task package contains `TaskState`, not an accepted task lifecycle.

**CI snapshot (not a persistent status assertion):** at examined HEAD `9feeb812...`, runs `45058713536` (INVENTORY populated PG), `45058713678` (CRM populated PG), `45058713608` (SALES populated PG), `45058713546` (RustFS real infra) reported SUCCESS. Run `45058719185` (RM Module JUnit) reported FAILURE in `module-h2` and skipped module PostgreSQL; run `45058713523` (QR08) had targeted PG and populated PG SUCCESS, **full PG regression FAILURE**; run `45058713533` exact-SHA admission was IN_PROGRESS at inspection. These statuses are momentary; fetch fresh jobs/artifacts on execution SHA. **NO INTEGRATED PASS** may be inferred.

Already existing conditional controllers are legacy/in-tree and not a reason to expand HTTP. No new controller is authorized.

### Evidence status taxonomy

`EXISTS` = actual class/table/function observed; `QUALIFIED@SHA` = explicitly successful job/test XML at exactly that SHA; `PARTIAL` = implemented subset or incomplete business flow; `SPECIFIED` = contract only; `BLOCKED` = mandatory qualification absent. Every task must separately track `CODE`, `PG`, `INTEGRATED`, `BUSINESS JOURNEY`.

## 3. Milestones and dependency DAG

`FC-00 CI restore → FC-10 IAM/ORG foundations → FC-20 CRM identity/customer contract → FC-30 CATALOG/STOREFRONT offer → FC-40 SALES/INVENTORY order lifecycle → FC-50 QR full journey → FC-60 cross-domain QA/fault recovery → SERVICE FUNCTIONAL PASS`.

FC-10/20/30 source analysis may run in parallel after FC-00 diagnosis; mutations require matching critical security gates. FC-40 and FC-50 **reuse** common catalog, checkout, warehouse and IAM boundaries. A failure of a required P0 gate blocks full admission even if other tracks are green.

## 4. Seven work packages

### FC-00 — P0: CI/PostgreSQL: repair integrated evidence first

**Task:** diagnose and fix two *observed* categories from runs `45058719185` and `45058713523`: (1) H2 Liquibase failure in changeset `0105-crm-r22b-lifecycle-dispute`: `CREATE UNIQUE INDEX ... WHERE status IN (...)` is a PostgreSQL partial index syntax rejected by H2; (2) full PostgreSQL QR regression: multiple QR06B distributed worker JVM startups failed with `FATAL: sorry, too many clients already` during Liquibase initialization; the pressure/parallelism source must be confirmed in tests/connection pools. See section 10, `FC-00A/B`. Inspect Surefire XML, stack traces, all failed test names and resource limits. Do not weaken gate or skip tests to make green. Check related changes and GitHub Actions concurrency/cancellation semantics.

**Implementation:** minimal corrective code/migration/test change in `base_dev`; no feature scope expansion. Ensure targeted + full H2/PG suites, populated upgrade and test evidence manifest are compared to *one final candidate SHA*. Reports should reject missing XML, unexecuted mandatory names, unexpected skips and mismatched head SHA.

**DoD:** the mandatory module-H2, module-PostgreSQL, domain-security-PostgreSQL, QR08-targeted and QR08-full-PostgreSQL jobs PASS on same HEAD (when a workflow is required for that candidate); populated migration and real-infra gates PASS where changed code depends on them; verified test counts/errors/failures/skips recorded; SHA, links, artifact names and report check stored. No claim without re-fetch.

### FC-10 — P0: IAM / ORGANIZATION / employees / permissions

**Business stories:** (A) verified owner creates or obtains tenant via the approved onboarding path; (B) invites active employee, employee accepts, receives explicit limited permissions; (C) staff works in selected tenant, gets denied in other tenant and after revoke/suspend; (D) owner updates/deactivates/reactivates/closes location under stock/order blockers.

**Method audit:** `OidcActorResolver`, `SelfServiceTenantOnboardingService`, `TenantMemberEnrollmentService`, `TenantRoleService`, `TenantAuthorization`, `GlobalAccountStatusService`, organization location lifecycle. Identify capability actually implemented versus source-review-only; only fill missing behavior.

**Required rules:** VerifiedActor and fresh account/membership/tenant grants, deny-by-default, explicit tenant scope; no user-supplied actor UUID as authority; no OWNER wildcard/automatic business grant; location with nonterminal stock/order/reservation cannot be silently closed; no cross-tenant entity relationships.

**DoD:** full employee invite→accept→grant→perform→revoke and tenant A/B roles; two commit orders for `revoke/suspend × sales/warehouse/location command`; location lifecycle under active reservation/stock and migration of populated locations; disallowed commands leave zero business/audit/outbox deltas. External live OIDC/provider remains a separate release gate, **not** required for service-only closure.

### FC-20 — P0: CRM / customer / contact / contracts / verified order linkage

**Business stories:** create/update/search customer and contacts; category/contract lifecycle and expiry; interactions and follow-up fields **without TaskService**; verified buyer identity linkage to existing SALES order; revoke/dispute/deletion must hide unauthorized history while retaining immutable order evidence.

**Method audit:** authenticated CRM commands/queries, customer/contact/category/contract/search/interaction/history, `CrmBuyerLinkService`, IAM deletion/revocation integrations. Preserve previously qualified R2.2C/D/F behavior; check new exact HEAD.

**Required rules:** CRM owns customer and linkage provenance; SALES owns orders/lines/status/payment-neutral snapshots; never join identities by matching name or unverified email; consent and encrypted PII remain purpose-scoped; archived customer/contract guarding and history preservation. No unauthorized CRM access via sales projection. Do not reopen feature work on TASKS.

**DoD:** CRM complete customer→contact→interaction→contract→history and customer↔buyer↔order; role/tenant/PII negatives; dispute and deletion/revocation races; consistent search/pagination and populated PostgreSQL migration; exact-SHA scoped and integrated evidence.

### FC-30 — P0: CATALOG / STOREFRONT / commercial offer / price authority

**Business stories:** create PRODUCT draft and localized text; add priced revision; publish item; create/publish/pause offer; public card resolves **only** published eligible offer; update price does not rewrite placed orders; attempt archive while active offer or inventory in use must follow approved blockers.

**Method audit:** `CatalogItemService`, `CatalogQueryService`, `StorefrontManagementService`, `StorefrontOfferService`, `PublishedOfferCardQuery`, CATALOG inventory/offer usage ports; resolve `CATALOG price history` versus authoritative seller offer current checkout price explicitly.

**Required rules:** no implicit Catalog publish → automatic offer publish; tenant-qualified FK; price/currency/SKU immutable at checkout; monotonic CAS; draft/pause/archived/SERVICE SKU disallowed in PRODUCT stock flow; no stale price accepted silently. Archive/stock/offer race cannot strand active sellable objects without explicit policy.

**DoD:** one qualified `catalog→storefront→offer→checkout` test across 2 tenants; price drift, pause vs checkout, revoke vs publication (both orders), SKU archive vs stock/offer, duplicate create/replay, populated migration, read-after-restart and audited history. Search/filter/order validation where core operator depends on it.

### FC-40 — P0: SALES / INVENTORY / authoritative order lifecycle

**Business stories:** buyer submits order; authorized seller sees detail/history and confirms order for a specific eligible warehouse; full atomic reservation; seller prepares/marks ready; verified handover posts exactly once; cancel releases reservation when permitted; movements/stocktake/transfer and adjustments remain independently auditable.

**Method audit:** `AutoOrderService`, `AuthenticatedSalesCommands/Queries`, `SalesInventoryCommandService`, `SalesOrderPreparationService`, `OrderInventoryPort`, `InventoryReservationService`, `InventoryMovementService`, `InventoryTransferService`, `InventoryStocktakeService`, reconciliation services.

**Required rules:** one order→one seller, tenant+warehouse scope, permitted state transitions, immutable accepted line snapshots, reserve only after authorized CONFIRMED, no stock debit in PREPARING/READY, exactly-once fulfillment, no negative sellable balance, no stray ledger on denied or rolled-back operation; stockowner INVENTORY only. Do not equate COMPLETED with PAID or DELIVERED.

**DoD:** transition table positive/negative, warehouse mismatch, partial availability failure with rollback, cancel×confirm, fulfill×revoke, duplicate confirm/fulfill, warehouse stocktake×movement, transfer/reconciliation concurrency, reboot/retry, durable receipts/history and populated PG test. Independent source/result oracle checks onHand/reserved/movements/order state.

### FC-50 — P0: QR Commerce / negotiation / pickup / returns

**Business stories:** seller publishes stable QR of published PRODUCT offer and can reprint/revoke; buyer views restricted public card, chooses list-price checkout or verifies buyer identity and negotiates; latest accepted negotiated terms create exactly one SALES order; seller confirms, reserves, prepares; verified buyer receives one-time pickup secret; authorized employee scans/previews and explicitly executes physical handover; buyer returns eligible quantity, warehouse receives/inspects and posts RESTOCK/QUARANTINE/WRITE_OFF safely.

**Method audit:** `PublishedOfferQrService`, `PublishedOfferCardQuery`, `PublishedQrCheckoutService`, `SalesPriceProposalService`, `SalesOrderQrReferenceService`, `SalesPickupHandoverService`, return request/review/physical/reception-proof/quarantine services; packaging service catalog/order line R0 as separate sub-gate.

**Required rules:** PRODUCT_OFFER, ORDER_REFERENCE, PICKUP_CREDENTIAL, RETURN_REFERENCE and PACKAGE_LABEL are different powers; public ref != proof of order ownership; seller quote counter cannot accept own revision; accepted quote is consumed once; buyer must be verified before private read/pickup; pickup raw secret never stored/logged; scan preview performs zero stock effect; double scan causes at most one completion/stock movement; return cannot exceed original handed-over quantity less previously received, original COMPLETED remains. Damaged/quarantined item never increases sellable balance; QR-08 R0 **never** posts packaging materials, finance or delivery; R1 materials debit remains STOP until separately approved and qualified.

**DoD (QR core):** full list-price and negotiated paths on SAME underlying checkout/order inventory contracts; concurrency `accept×counter/reject/expire`, `confirm×stock change`, `pickup×pickup`, `return×return`, IAM suspend/revoke×each sensitive operation, recovery and price drift; PostgreSQL ledger proof. **Separate QR-08/R0 PACKAGING gate:** focused + populated + full PG PASS exact SHA and XML before claiming QR-08 R0 PASS. QR-08 packaging is not a prerequisite for the base `PRODUCT_OFFER→checkout→pickup→return` journey, but any code included in candidate HEAD must still pass full regression; no skipping failing package tests. External scanner/browser/payment are separate gates.

### FC-60 — P1: cross-domain qualification / business admission

**Goal:** verify domain joining, not repeat each unit test. Build **scenario registry** with inputs, actor/tenant, order/business state, expected domain rows, history, zero side effects on deny, test method, environment, SHA, job/artifact and result.

**Minimum independently described target matrix (proposal; not 120 tests claimed):**
- 15 IAM/tenant/employee admission, permissions, revoke;
- 20 CRM customer/contract/linkage/PII/consent;
- 15 CATALOG/storefront/price/history;
- 25 SALES/inventory/order/stock movement;
- 15 QR/public/negotiation/checkout;
- 20 pickup/return/quarantine/packaging R0;
- 10 cross-domain crash/retry/migration/races.
**Total: 120 planned scenario IDs as a full regression registry target**, not 120 new JUnit methods and not a blanket numerical release blocker. Mark each scenario `CORE-P0` or `EXTENDED-P1` by actual business dependency, then de-duplicate against existing executed tests by user-behavior equivalence; never inflate counts with trivial assertions. ALL identified `CORE-P0` scenarios must be verified for service functional PASS; remaining `EXTENDED-P1` scenarios may follow in the same program unless they protect a P0 invariant. Mapping can identify historical existing passing tests but must confirm them at target HEAD.

**Mandatory scenario examples:**
- owner onboarding→seller grant→catalog→offer→order→reserve→handover→CRM history;
- seller permission removed while confirming or handing over, both commit orders;
- offered price changes during QR checkout (single coherent snapshot);
- same buyer repeats quote checkout using identical key and conflicting payload;
- two staff scan one pickup credential;
- cancellation and reservation/release competition;
- return reception crash between receipt and stock posting, idempotent retry;
- tenant A actor attempts tenant B catalog/stock/CRM/QR order; no leakage;
- populated Liquibase upgrade preserves existing order, buyer link, ledger and immutable audit;
- failed File media / quarantine cannot leak private original via public card;
- packaging service accepted without any stock/finance movement (R0).

**DoD:** exact-SHA manifests for mandatory suites and all `CORE-P0` scenario IDs, zero unexpected skips, no unqualified gaps in either product's critical user journey; complete the 120-entry registry without pretending all 120 have passed. Any `NOT RUN`, `BLOCKED` or failed critical scenario means `SERVICE FUNCTIONAL HOLD`. Distinguish independent external OIDC/HTTP, real scanner, UX, legal and production gates.

## 5. Shared architecture, transactions and data ownership

- Existing Java 21 / Spring Boot modular monolith, PostgreSQL/Liquibase; no new DB cluster, external queue, Redis or universal workflow engine required.
- IAM owns account/workspace/grants, CRM owns customer and consent, CATALOG owns stable SKU and price revisions, STOREFRONT owns published sellable offer price/visibility, SALES owns orders/snapshots/accepted quotes/fulfillment receipts, INVENTORY owns balance/reservation/movement, FILES owns bytes/scan/access; AUDIT records domain evidence. Use typed ports only.
- All external identity assertions originate at validated trusted boundary; internal service testing may use synthetic signed principals. Never trust caller actor UUID or inferred email. Current HTTP controllers may remain untouched; no new transports.
- Reuse `OrderInventoryPort`, current checkout idempotency namespace and current order aggregate; no parallel QR order/stock engine or SQL writes across owner boundary.
- PostgreSQL is concurrency source of truth: composite tenant FKs, unique/idempotency constraints, row-lock DAG, CAS, transaction scope, immutable append-only event/receipt data. Every command must specify linearization point and retry semantics.
- Cross-domain operations inside same database use atomic transaction when valid; durable outbox only when an external side effect truly requires it. On failure, zero partial balance/order changes and sanitized audit.
- Keep structured request/trace correlation without sensitive data; no raw pickup, verification or encryption secrets in history/logs.

## 6. Acceptance format and execution gates

Per ticket mandatory record:
`ID | existing method & source | gap classification | approved owner & invariant | business scenario IDs | migration impact | test names | actor/tenant cases | concurrency orders | rollback/recovery | exact commit SHA | Actions run & job | Surefire XML tests/errors/failures/skips | verdict`.

**Gate G0:** determine failure root causes; repair H2 + full PG, exact-SHA manifest.  
**Gate G1 (Business Core):** FC-10/20/30/40 service E2E, all P0 business scenarios, immutable history, clean+populated PG and adverse races.  
**Gate G2 (QR Core):** FC-50 primary QR product flows, exact-SHA regression, no double stock effects. QR-08 R0 receives a **separate** qualification verdict and is not a prerequisite for the QR PRODUCT core when not selected for release; existing package code still participates in mandatory whole-project regression.  
**Gate G3 (Cross-domain):** FC-60 registry mapped to source and tests; all critical `CORE-P0` scenarios executed with fault/retry, source-of-truth, rollback and security proof. 120-row inventory is the coverage plan, not evidence of 120 executed cases.  
**Verdict:** `RM BUSINESS SERVICE FUNCTIONAL PASS` + `RM QR SERVICE FUNCTIONAL PASS` each awarded independently; **integrated CORE PASS** only after G0–G3 on a single qualifying release SHA. External real OIDC/transport/scanner/UX and production are separate HOLD until explicit later approval.

Failure policy: a required CI failure, absent artifact, unexpected skip, unapproved policy change or unresolved P0 race → `HOLD`. Historical PASS never upgrades new HEAD automatically. Re-run exact-H2/full-PG after each integration change.

## 7. Explicit decisions to record before implementation

| Decision | Default until product authority approves |
|---|---|
| BIZ-01: SMB customer/legal entity/warehouse limits | Preserve existing tenant/warehouse schema; no artificial one-warehouse restriction |
| BIZ-02: first-tenant OWNER grants | Explicit allowlisted grant workflow, no wildcard/automatic business superuser |
| BIZ-03: CRM customer and verified buyer | Explicit proof-bound link, no name/email matching |
| BIZ-04: price authority | STOREFRONT offer is checkout authority, CATALOG keeps catalog revision history |
| BIZ-05: customer archive vs open order | Preserve immutable orders, reject unsafe new operations; final policy by domain owner |
| BIZ-06: inventory vs sales and return | INVENTORY authoritative; receipt and inspection precede sellable restock |
| BIZ-07: no-show/partial fulfillment/refund | Separate workflow decision; do not infer payment/delivery/refund |
| BIZ-08: QR limits/negotiation TTL | Existing UAT policy only, not hardcoded commercial promise |
| BIZ-09: reports/minimal finance | Operational order/stock reports first; accounting/payment ledger out of current scope |
| BIZ-10: TASKS | Explicitly after P0/P1, no TaskService now |

## 8. Implementation sequence for Codex

1. Read exact HEAD, normative docs, relevant Java service methods, Liquibase and failing CI XML. Produce **code-to-scenario coverage map** and source-informed gap list with `EXISTS/PARTIAL/MISSING/QUALIFIED@SHA`.
2. Fix FC-00 regression blockers **before** feature expansion. Preserve/verify existing green domains.
3. Execute FC-10 / FC-20 / FC-30 in safe order with minimal change sets. For existing code: improve missing contracts/tests, do not reimplement.
4. Execute FC-40 and FC-50 through same authoritative order, checkout and inventory services. No controller/UI work.
5. Run FC-60 with fresh PostgreSQL and populated-schema migrations; publish verifiable evidence per scenario and per release candidate.
6. Update existing R3/CRM coverage status **only through their canonical validators** and with exact-SHA proof. Do not revise historic metrics by assumption.
7. Keep one active `base_dev` branch and no merge into `main` without a separately authorized review.

## 9. Final Definition of Done

- Two product flows fully executable **without HTTP** via authenticated application services.
- No critical orphan business process, direct foreign aggregate write, double checkout, double inventory debit, leaked PII or incorrect stock conservation in qualified cases.
- Every P0 capability has independently verifiable positive/negative/race/recovery service tests, a method-level scenario matrix and executed PostgreSQL evidence at exact SHA.
- Required workflow/test XML manifest authentic, complete, and consistent with source SHA; no skipped mandatory scenarios.
- No TASKS implementation, no new controllers/UI, no RM Network product scope, no premature activation of KF-01..05.
- `PRODUCTION HOLD` maintained until separate real provider/browser/operational/legal gates.

**Concluding decision:** target **functional completeness and proof of correct user behavior** of RM Business and RM QR Commerce before transport, UI, TASKS, new killer features or expansion to enterprise network.

## 10. R1.1 — матрица согласования с фактическим проектом (2026-11-23)

Это не новый бизнес-домен и не повторная реализация. Приоритет: проверенное поведение исходного Java-кода, действующие additive Liquibase и exact-SHA execution evidence. Каждый `CODE PRESENT` ниже перепроверить на HEAD задачи.

| Решение | Проверенная опора в проекте | Изменение / практический результат |
|---|---|---|
| PRD-R2 | `docs/strategy/RM-PRODUCT-STRATEGY-R2-SMB-QR.md`; R1 раньше включал Network | Только RM Business и QR Commerce; Network не имеет активного implementation backlog |
| CRM-CORE | `CrmCustomerService`, `CrmContactRepository`, `CrmContractService`, `CrmInteractionService`, `CrmBuyerLinkService`; R2.2C/D/F evidence | Выполнять gap-specific тесты и корректировки, не дублировать CRM и buyer-link |
| IAM-ORG | `TenantAuthorization`, `TenantMemberEnrollmentService`, `OrganizationLocationService` и authenticated facade; ORGANIZATION R1.1 history | Lifecycle location **уже реализован частично**; квалифицировать оставшиеся контракты, не создавать снова create/update/close |
| CATALOG | `CatalogItemService`, `CatalogQueryService`, `CatalogInventoryUsagePort` | Item/price/history код существует; закрывать только published SKU↔offer↔orders/archive races |
| STORE | `StorefrontManagementService`, `StorefrontOfferService`, `PublishedOfferCardQuery` | Storefront/offer lifecycle, CAS и price history присутствуют: проверять поведение и exact-SHA, а не проектировать вторую витрину |
| SALES | `AutoOrderService`, `SalesInventoryCommandService`, `SalesOrderPreparationService`, authenticated queries | Не создавать второй order aggregate; доказать seller read/status/prepare/fulfill на existing flow |
| INVENTORY | `InventoryMovementService`, `OrderInventoryPort`, `InventoryReservationService`, transfer, stocktake, reconciliation | Сохранить quantity ledger/locks и исторические qualification; отказ и восстановление проверять на новых SHA |
| QR | `PublishedOfferQrService`, `PublishedQrCheckoutService`, `SalesPriceProposalService`, `SalesPickupHandoverService`, Return* | Сервисы физической выдачи/возврата есть; приоритет сквозной contract proof, не запуск нового QR engine |
| QR-08 | `PackagingServiceCatalogService`, `OrderPackagingService` | R0 присутствует как кандидат, но его локальный PASS не заменяет full PG; он необязателен для товарного QR core |
| TASKS | package `kz.rm.tasks.TaskState`, schema `task_item` | Отсутствие `TaskService` сознательно принято; никакого временного lifecycle до завершения P0/P1 |

### 10.1 FC-00A: H2 migration compatibility — подтверждённый дефект

**Evidence:** [run 45058719185](https://github.com/alimbetov/RM/actions/runs/45058719185), job `module-h2` `137319615781`. В startup `FilesR10bQualificationTest` общий Spring `ApplicationContext` падает на Liquibase changeset `0105-crm-r22b-lifecycle-dispute`: H2 отвергает PostgreSQL-specific `CREATE UNIQUE INDEX uk_crm_buyer_active_customer ... WHERE status IN ('PENDING','CONFIRMED','DISPUTED')`. Много последующих `ApplicationContext failure threshold exceeded` — следствие первой ошибки, а не доказательство многочисленных независимых дефектов FILES.

**Требуется:** source trace dbms-conditional миграции и тестового bootstrap; сравнить семантику действующего PostgreSQL partial index с H2 test-only equivalent. **Запрещено** ретроспективно редактировать применённый production changeset / ослаблять PostgreSQL uniqueness, отключать Liquibase либо помечать тесты skip. Если forward-only PG changeset уже применён, использовать безопасный отдельный совместимый путь/корректирующую миграцию или тестовый schema adapter по нормативному порядку. Механизм выбрать после inspection; каждый backend должен реально проверять соответствующий business invariant.

**Exit:** H2 clean bootstrap и обязательные service тесты PASS, PostgreSQL clean+populated upgrade PASS, прежняя PostgreSQL уникальность/конкурентная защита сохранена, artifacts и exact SHA записаны.

### 10.2 FC-00B: PostgreSQL connection budget — подтверждённый отказ запуска процессов

**Evidence:** [run 45058713523](https://github.com/alimbetov/RM/actions/runs/45058713523), job `qr08-full-postgresql-regression` `137319599291`: `QrWarehouseHandoverQualificationTest` (включая distributed 2-JVM B31/B33/B35) сообщает `FATAL: sorry, too many clients already` при старте worker JVM и Liquibase. Это **не** доказательство ошибки самой QR08 packaging business logic. Специальный targeted PG и populated PG QR08 на том же SHA в этом запуске были SUCCESS, но общий gate FAIL.

**Требуется:** посчитать максимальное количество подключений с учётом Maven forks, JUnit workers, Spring/Hikari pools, двух JVM, job-level parallelism и PostgreSQL `max_connections` / reserved slots; вывести числа и фактические активные соединения до и после. Обеспечить адекватный resource budget посредством изоляции тяжёлых тестов, управляемой параллельности, освобождения ресурсов или сознательной квалификационной конфигурации БД. **Нельзя** скрывать распределённые тесты через skips, уменьшать предметную нагрузку и считать текущую suite GREEN без дополнительного доказательства. При выносе стресс-сценариев в отдельный mandatory job это должен быть **тот же exact SHA** с явным manifest.

**Exit:** полный mandatory PostgreSQL regression PASS на том же source SHA, child JVM bootstrap стабилен; B31/B33/B35 и остальные критические distributed scenarios продолжают выполняться в обязательном квалификационном gate; отчёт о подключениях и исходных причинах сохранён.

### 10.3 Критические контрактные уточнения

1. **Верная транзакционная граница IAM/ORG.** Некоторые низкоуровневые сервисы всё ещё принимают internal `actorId`, тогда как публичный trusted application facade обязан принимать `VerifiedActor` и свежие tenant permissions. Не объявлять все низкоуровневые методы автоматически безопасным внешним API; тестировать принятые authenticated facade entrypoints и убрать лишь реально доступные обходы. Исправлять не массовой заменой UUID во всех внутренних методах, а сохранением trust boundary и контрактов.
2. **Location lifecycle уже существует** (`OrganizationLocationService.update/deactivate/reactivate/close`) и не должен быть повторно запланирован как новый функционал. Закрывать фактические PG/race/populated/warehouse-blocker gaps. Один tenant не должен автоматически означать один физический склад.
3. **Catalog price != current seller offer price:** `cat_price` — история цены каталога, `store_offer` — текущая коммерческая цена оформления. У `StorefrontOfferService` присутствует `priceHistory`; нельзя описывать её как отсутствующий метод без проверки настоящих source/CI. Immutable snapshots в SALES остаются единственным доказательством цены принятого заказа.
4. **QR checkout:** `PublishedQrCheckoutService` использует существующий `AutoOrderService`; negotiated checkout должен использовать согласованный accepted quote контракт без второго receipt namespace и без переиспользования ключа с иной семантикой. `PLACED` не создаёт складской резерв, `CONFIRMED` создаёт.
5. **Подготовка заказа:** `SalesOrderPreparationService` содержит `startPreparing`, `confirmPicked`, `markReadyForPickup`; выдача и списание отделены в `SalesPickupHandoverService`. `READY_FOR_PICKUP` не равен списанию или завершению.
6. **Возвраты:** source-implemented `SalesReturnPhysicalService.receive/inspect/post` и соседние return services требуют единого state-machine и exactly-once posting проверок; не писать второй сервис возврата. `RESTOCK` увеличивает sellable stock только после утверждённой инспекции.
7. **QR-08 packaging:** R0 сохраняет `zero inventory movement, zero financial posting`. Это опциональная функция относительно base QR Commerce, с **собственной** qualification. Входящие в HEAD R0 тесты остаются частью общей регрессии; optional не означает `skip`.
8. **External IAM:** модуль содержит кандидата `OidcRevocationOutboxWorker` и disabled-by-default Keycloak gateway; на старых exact SHA есть scoped PG PASS, но live Keycloak provider/session invalidation, подписанная retention policy и реальный OIDC/E2E остаются отдельными HOLD. FC-10/20 не должны переизобретать R2.2F worker.
9. **Документная иерархия:** `docs/strategy/RM-PRODUCT-STRATEGY-R2-SMB-QR.md` решает **продуктовые приоритеты**; `RM-PLATFORM-STANDARD-R2` остаётся высшим по техническому/security контракту; QR-R1.4 определяет QR invariant, domain specs определяют методики. Старые R3/R4 файлы — исторические capability baseline; статусы обновлять только доказательствами.
10. **MVP ценность и сервисный допуск:** отсутствующий TASKS, полноценная FINANCE/бухгалтерия, маркетинговые кампании и enterprise network не блокируют *этот* service-functionality gate. Однако не заявлять техническую возможность задачи, оплаты или консолидированного учёта в пользовательском MVP до отдельной квалификации.

### 10.4 Точная приёмка двух продуктов независимо

**Business Core P0 route** — trusted actor (синтетически удостоверенный в service test) → membership + explicit grants → CRM customer/contact/contract → PRODUCT draft/price publication → published storefront and seller offer → buyer checkout → seller confirm selected warehouse → atomic full reserve → preparation/ready → secure handover → immutable order+stock history → CRM customer↔buyer/order read только с proven linkage. Тесты проверяют разрешённые и отказные пути, но не имитируют физическое списание для отказов.

**QR Core P0 route** — current published PRODUCT offer → stable QR reprint → restricted public card and safe media → [list-price OR proposal/counter/accepted quote] → existing checkout, same seller/order idempotency semantics → confirm/reserve → authenticated pickup issue → explicit employee physical handover → return request/approval/reception/inspection/post → invariants for original order and stock. Публичный ref никогда не является выдачным credential.

**Admission split:** (1) `BUSINESS SERVICE FUNCTIONAL PASS`; (2) `QR SERVICE FUNCTIONAL PASS`; (3) `QR08 PACKAGING R0 PASS` независимо; (4) `INTEGRATED CORE PASS` только при общей платформенной exact-SHA регрессии. Не смешивать с `PILOT` или `PRODUCTION`.

### 10.5 Реестр сценариев и test evidence

120 описанных в FC-60 сценариев — **план полной матрицы**, не число уже выполненных тестов. До запуска каждого implementation slice:

| Поле | Требуемая запись |
|---|---|
| `scenario_id` | Уникальный business behavior, без дубликатов |
| `tier` | `CORE-P0` / `EXTENDED-P1` с обоснованием зависимости от продукта |
| `owner` | IAM / ORG / CRM / CATALOG / STORE / SALES / INVENTORY / FILES |
| `code_anchor` | Реальный Java method + migration/aggregate, `EXISTS/PARTIAL/MISSING` |
| `test_anchor` | JUnit class.method либо `NOT IMPLEMENTED` |
| `evidence` | environment, source SHA, workflow/job, XML tests/errors/failures/skips |
| `race_oracle` | Оба порядка коммита, final DB rows, history, zero-denied-side-effects |
| `verdict` | `PASS@SHA`, `FAIL`, `NOT RUN`, `BLOCKED`, `NOT APPLICABLE` (с объяснением) |

**Gate:** все CORE-P0 должны иметь `PASS@SAME_RELEASE_SHA` и platform mandatory suites GREEN; расширенная матрица 120 должна быть полной как реестр, но неприоритетные P1 не объявлять выполненными без тестов. После согласования и исполнения следует создать отдельный machine-readable `docs/strategy/RM-CORE-FUNCTIONAL-SCENARIOS-v1.json` со schema/validator/self-tests **только если текущие реестры нельзя расширить без нарушения их identity/ownership**. Документ сам по себе не санкционирует создание нового кода и подсистемы тестов.

### 10.6 Decision and change control

- Source baseline для этого R1.1 appendix: `9feeb812e2b56d877e223c42bde0d55c09d4057b`. Документальный коммит R1.1 меняет HEAD, поэтому qualification на документальном SHA **отсутствует**, если workflow не завершён успешно и не зафиксирован.
- Implementation допускается только отдельной задачей с точным HEAD, scope, acceptance cases и явными unresolved decisions. Не считать `SPEC ALIGNED` эквивалентом выполненных сервисных операций.
- Не создавать новые branches, не менять `main`, не добавлять controllers/REST/BFF/UI, не запускать KF-01..05 и TASKS.
- Для миграций не править применённые changesets; новые forward-only изменения + clean/populated DB доказательства, особенно PostgreSQL partial indices и тестовая H2 совместимость.
- Повторная квалификация не должна переписывать исторические `DONE-QUALIFIED@OLD-SHA`: они сохраняются как historical evidence до fresh release SHA.
