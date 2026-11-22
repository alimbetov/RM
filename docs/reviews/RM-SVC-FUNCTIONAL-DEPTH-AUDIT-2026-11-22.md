# RM Platform — аудит глубины функциональности сервисов (SVC-FUNC-AUDIT-R1)

**Branch:** `base_dev`. **Исходный снимок:** `f26c13eebbd3f04289e33ac16d28e2cdf4f86db5`, 22.11.2026.
**Статус:** source-level review, **не** функциональная приёмка всех бизнес-сценариев.
**Правило:** отсутствие `TODO` или пустых Java-тел не означает работающий бизнес-процесс. `CI GREEN` подтверждает лишь проверенный тестовый объём, не наличие незаявленных функций. **Production HOLD**, `main` не менять, HTTP-контроллеры не добавлять во время Feature Freeze.

## 1. Объём и методика

Просмотрены актуальное дерево `src/main/java/kz/rm` (147 Java source файлов, 15 доменов), прикладные service facades, доменные сервисы CRM/SALES/INVENTORY/FILES/ORG/IAM/CATALOG/KMS/AUDIT, DDL Liquibase и 48 test classes. Для каждого существующего контура сравнивались: заявленное назначение, фактически вызываемые методы, SQL/JPA эффекты, tenant/permission admission, lifecycle, idempotency/audit, потребители опций, наличие полноценных service flows. Этот обзор **не** является автоматически сгенерированным полным доказательством отсутствия пустого тела у каждого метода всех 147 файлов; для этого нужен отдельный AST CI guard.

Проверенный на исходном SHA GitHub Actions:
- RM Domain Security and PostgreSQL Qualification: `44982786185` — SUCCESS;
- CRM R1.2 Populated PostgreSQL Upgrade Qualification: `44982781724` — SUCCESS;
- RustFS Real Infrastructure E2E: `44982781763` — SUCCESS.
Все GREEN относятся к указанному source HEAD и реализованным тестовым сценариям, а не к полноте бизнес-функций.

**Шкала зрелости (экспертная, не процент покрытия тестами):** 0 — тип/DDL без прикладного сервиса; 1 — отдельные операции; 2 — рабочий доменный участок, но разорван user journey; 3 — связанное тестируемое ядро; 4 — полный service E2E на принятом UX; 5 — отдельно подтверждённая production readiness. Значения относительные и не суммируются.

## 2. Матрица функциональной зрелости

| Домен | Уровень / 5 | Реально исполняемые сценарии | Что НЕ является завершённым |
|---|---:|---|---|
| IAM / RBAC | **3** | VerifiedActor/OIDC resolver, tenant roles/grants/revocation, membership, owner transfer/recovery, email proof + outbox, protected global profile | First OWNER business grants, внешний email delivery/transport, полный TCRUD-03/04/production |
| CRM Core | **3** | Customer/Contact CRUD и PII, category/contract lifecycle, consent, as-of expiry list, immutable histories | Полный CRM operational funnel, индивидуальный customer portfolio ACL, все фильтры/отчёты |
| CRM Interaction | **2** | Record, assign, next-action date, complete/cancel, search, idempotent event | Kind NOTE без текста, содержательные результаты коммуникаций, Tasks linkage |
| CATALOG | **2** | Draft SKU/i18n/modelCode, price revisions, publish/archive, tenant queries/history | Публикация **не передаёт** оффер в storefront/checkout, отсутствуют коммерческие price list/offer flows |
| SALES / AutoOrder | **2** | Public-engine checkout + email proof, status events, seller confirm/cancel/fulfill and stock reserve | Staff draft/detail/line editing/history; CRM Customer mapping; actual storefront governance; delivery vs payment |
| INVENTORY | **2** | Quantity receive/correct, reservations/release/fulfill, balances, stock ledger, settings persistence | Transfer, stocktake, valuation execution; настройки Picking/autoReserve не управляют подтверждением заказа |
| FILES | **3** | Staged upload, Local/RustFS, ClamAV adapter, immutable metadata, attachment, guarded binary read, recovery | Typed owners beyond CRM COMPANY, media search, actual AV infrastructure required at runtime |
| ORGANIZATION | **1** | Create/get/search location, KATO validation | Update/deactivate/reactivate, archive constraints with active stock/orders |
| GEO | **2** | KATO directory, region eligibility for org location | Полнота/актуальность nationwide production reference и обновление источника не квалифицированы этим обзором |
| KMS | **2** | Tenant DEK provision/read/decrypt + crypto/fencing | rotate() **explicitly unsupported**, rollout/migration not qualified |
| AUDIT | **1** | Audit retention policy, bounded retention worker, opt-in scheduler/legal hold | Единого tenant audit query и доказанного приложения-поставщика canonical cross-domain audit_event нет |
| STOREFRONT | **1** | StorefrontSettings value object, DDL + published slug lookup for AutoOrder | Tenant-owned management operations, publish/pause/offer synchronization |
| TASKS | **0** | `TaskState` enum; `task_item` table declared | Нет `TaskService`, назначения, истории и фактических команд |
| FINANCE | **0** | `Money` record; `fin_invoice` table declared | Нет Invoice/Payment/Allocation service и учёта оплаты |
| MARKETING | **0** | `CampaignState` enum; `mkt_campaign` table declared | Нет Campaign/segmentation/consent-gated dispatch |
| SUBSCRIPTION | **0** | `PlanEntitlement` record; `sub_plan/sub_subscription` tables | Нет назначения тарифа, limit enforcement/usage и billing policy |

**Вывод:** функционально развито ядро 6–7 доменов; остальное — реальные предметные задачи или декларации, а не «CRUD completed». Наличие SQL-таблицы не равняется реализованному сервису.

## 3. Пустые, stub и «ложно рабочие» операции

| ID | Код / доказательство | Классификация | Решение |
|---|---|---|---|
| STUB-01 | `TenantKeyService.rotate(UUID)` выбрасывает `UnsupportedOperationException` | **Intentionally disabled**, fail closed, не рабочая capability | Не реализовывать небезопасную ротацию ради удаления заглушки; убрать из доступных бизнес-возможностей до distributed fencing/restore E2E |
| STUB-02 | `UnavailableFileScanner.scan()` возвращает `Verdict.ERROR` | **Safe fail-closed adapter**, не AV сканер | Не считать CLEAN; запуск загрузки зависит от реального ClamAV; проверить production wiring/failure alerts |
| STUB-03 | `StorefrontSettings.emailRequired()` и `autoProvisionBuyer()` возвращают `true` | Зафиксированная security policy, **не** имитация алгоритма | Сохранять invariant; отделить от реально изменяемых бизнес-настроек |
| STUB-04 | Повторные `TenantRoleService.grant`, checkout receipts и terminal-status переходы возвращают прежний результат | Допустимая **идемпотентность** | Не заменять исключением/записью только ради «непустого» метода |
| GAP-01 | `InventoryTenantSettingsService.updatePicking` сохраняет `auto_reserve_on_confirm`, `picking_strategy`; `SalesInventoryCommandService.confirm` **всегда** выполняет reserve и не читает настройки | **P0 functional mismatch**: изменяемый параметр без эффекта в фактическом процессе | Согласовать всегда-резервировать или optional reserve; затем либо выполнить flow flag+strategy, либо удалить/заморозить изменяемую опцию |
| GAP-02 | `InventoryTenantSettingsService.approveCostPolicy` создаёт версию метода FIFO/MWA/SPECIFIC, но `InventoryMovementService` записывает только quantity и **не** valuation | P1 «policy metadata ≠ costing engine» | Отдельный cost-ledger/valuation qualification; не отображать valuation как рассчитанную |
| GAP-03 | `CatalogItemService.publish` меняет `cat_item.state`, но `AutoOrderService.checkout` читает независимые `crm.store_offer(title,unit_price,currency)` | **P1 disconnected publish**; два управляемых источника цен для разных шагов | Контракт CATALOG published immutable projection → STOREFRONT offer; immutable SALES snapshots; E2E сценарий |
| GAP-04 | `CrmInteractionService.Kind.NOTE` возможен, но `record` не принимает и не хранит note body/result | P1 «type marker without note content», intentional PII exclusion | Добавить encrypted purpose-scoped notes с AAD + `CRM_PII_READ` либо временно не представлять NOTE как полноценную заметку |
| GAP-05 | `EmailIdentityService.queueChallenge` записывает `iam_email_delivery_outbox`; в дереве Java production не найден executor/SMTP delivery worker | P0 external integration gap; мог существовать внешний вне репозитория | Qualified outbox dispatcher + provider, retries, template/anti-abuse + proof E2E до public registration/checkout |
| GAP-06 | `InventoryTenantSettingsService` — публичный Java-сервис с raw `UUID authenticatedActorId`, хотя внутри вызывает `TenantAuthorization` | TCRUD-04 boundary debt, **не доказанная exploit vulnerability** | Новый `AuthenticatedInventorySettings` с VerifiedActor, старый UUID порт закрыть package-private и исправить тестовых потребителей |
| GAP-07 | `OrganizationLocationService` имеет create, `AuthenticatedOrganizationLocations` — create/get/search, отсутствуют update/deactivate/reactivate | P1 missing lifecycle | Добавить CAS update и отказ закрыть склад с активными остатками/резервами |
| GAP-08 | FILES `CrmFileOwnerAccess` допускает только `customer_type='COMPANY'` и CRM_PII_READ; PERSON/CATALOG/SALES owners ещё не приняты | P1 deliberate owner-policy limitation | Typed owner adapters per domain, tenant/PII policy + forbidden-byte E2E |
| GAP-09 | `TaskState`, `Money`, `CampaignState`, `PlanEntitlement`, `StorefrontSettings` существуют, но соответствующие CRUD/lifecycle services отсутствуют | **Declared domains, not placeholder methods** | Реализовать конкретные сервисы по плану; не «заполнять» enum новыми пустыми классами |
| GAP-10 | Основной REST controller только `HealthController`, остальные entrypoints service-only | **Feature Freeze by design**, не дефект Java service | Не заявлять готовность пользовательской CRM системы; HTTP/anti-abuse/OIDC transport — отдельный post-freeze gate |
| GAP-11 | `TCRUD-00/03` содержит 103 named operations с `EVIDENCE TRACE OPEN`; IAM/system operations частично за пределами матрицы | P0 qualification incomplete | Метод→permission→SQL/JPA→test exact SHA, foreign UUID, mixed role, revoke race, pagination, storage |

## 4. Доработки с точными Definition of Done

### P0 — убрать «кажется работает» до расширения продукта

1. **FUNC-01: Inventory settings semantics.** Решить и задокументировать контракт auto-reserve. Тест: установил FALSE → confirm не делает скрытый reserve (если policy допускает FALSE), установил TRUE → ровно один резерв на line; FIFO/FEFO/MANUAL либо имеют реального потребителя, либо не предлагаются как работающая настройка. Не нарушить dual SALES+INVENTORY permissions.
2. **FUNC-02: Real email delivery gate.** Challenge/outbox `PENDING` → worker claim/fencing → external provider → retry/poison state → proof verified; нельзя «выполнить» тест за счёт чтения encrypted token напрямую в production.
3. **FUNC-03: TCRUD-00/03/04.** Проверить полноту всех существующих прикладных портов, включая IAM/KMS/AUDIT/system, raw UUID, actor A/B/AB, and revoked permissions на PostgreSQL. Точные test IDs и SHA. Не объявлять после одного smoke.
4. **FUNC-04: OWNER bootstrap policy.** Утвердить конечную начальную функциональную роль или независимый путь self-service без OWNER wildcard и `CRM_PII_READ` по умолчанию. До решения existing self-assignment запрещён.

### P1 — завершить цельные бизнес-процессы

5. **FUNC-05: CATALOG → STOREFRONT → SALES.** Один published SKU/locale/effective price → qualified offer → immutable order snapshot; изменённая цена не меняет старый заказ, concurrent checkout/unpublish безопасен. Реальные границы: `CatalogItemService`, новый `StorefrontManagementService`, `AutoOrderService`.
6. **FUNC-06: CRM Interaction → TASKS.** Действительное взаимодействие с клиентом (тип, итог, responsible, next action), encrypted text only after privacy design; Task создаётся/поручается одному tenant staff, сроки/статус/history; Customer/Contact owner same tenant.
7. **FUNC-07: ORGANIZATION + INVENTORY.** Edit/deactivate location; move/stocktake, immutable pair movements, no cross-tenant relocation, lock ordering; only quantity ledger until separate valuation.
8. **FUNC-08: SALES staff workflow.** Draft/add/remove/update lines, submit/confirm/cancel/fulfill, detail/history; buyerId != CRM customerId; payment separate from fulfillment.
9. **FUNC-09: FILES typed ACL.** CRM PERSON, CATALOG ITEM, SALES ORDER owner-specific policy after accepted privacy contracts; list/media query, denied bytes before storage IO.

### P2 — реальный функционал контрактных доменов

10. **FUNC-10:** invoice/payment/allocation + reconciliation with immutable SALES order and single-currency constraints.
11. **FUNC-11:** marketing campaign segment + send consent re-check + worker, no dispatch before qualified real mail transport.
12. **FUNC-12:** subscription plan/limit/tenant entitlements, feature entitlement AND RBAC, audited platform governance.
13. **FUNC-13:** tenant audit query/projection; read sanitized evidence, retention governed separately.

## 5. Capability acceptance matrix для всех новых и изменённых методов

Не принимать сервис по критерию «`@Service` есть / метод компилируется». На каждый заявленный use case документировать:

- **Behavior:** команды/queries дают наблюдаемый эффект, подтверждённый независимым чтением; изменяемая настройка реально изменяет поведение своего consumer.
- **Domain:** legal transitions, referential integrity, state and CAS/version, repeat-key conflict, concurrency and rollback; complete happy+negative flows.
- **Security:** VerifiedActor vs trusted system, selected tenant and permission, owner/PII policy, foreign UUID, user AB mixed roles, revoke and inactive workspace.
- **Persistence:** additive Liquibase on existing & populated PG, composite FK, stable ordering and page/count consistency; immutable audit/effective price.
- **External:** delivery/storage/AV qualified integration (if used), failure & recovery; no unit test mock as proof of production transport.
- **Evidence:** exact SHA, H2/PG/infrastructure workflow, test name for each operation and connected journey.

**Acceptance states:** DECLARED → IMPLEMENTED → TESTED → QUALIFIED → ADMITTED. `QUALIFIED` only for the particular tested scope; `ADMITTED` requires separate production boundary approvals.

## 6. Рекомендация по разработке

Следующий полезный шаг — **не создавать ещё 8 сервисов в одном PR**. Сначала закрыть `FUNC-01` (ложная настройка inventory) и `FUNC-05` (каталог опубликован, но storefront не обновлён), затем `FUNC-06` (CRM Interaction → Task) и `FUNC-07` (складской lifecycle). Параллельно P0 security/owner/email gates. Реализация в той же `base_dev`, без новых контроллеров и без ослабления no-wildcard и PII правил.


## 7. INVENTORY — узкое 12-проходное ревью и отдельное детальное ТЗ

**Source для INV-кросс-ревью:** `base_dev` / `c776baec5c82e0dfd4c7930aa6a68629202fa5fd` (тот же SHA, что основная матрица). Проверены `AuthenticatedInventoryCommands/Queries`, `InventoryAuthorizedCommandService`, `InventoryMovementService`, `InventoryReservationService`, `InventoryTenantSettingsService`, `StockBalanceRepository/QueryService`, `OrderInventoryPort`, тесты INVENTORY/SALES/ORG и Liquibase 0022–0030, а также нормативная R2 политика.

**См. обязательный артефакт:** [RM-INVENTORY-FUNCTIONAL-COMPLETION-R1](../spec/RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md). Для остальных сервисов утверждён [12-проходный протокол](../spec/RM-SERVICE-12-PASS-REVIEW-PROTOCOL-R1.md). Пока проработан **только INVENTORY**, не остальные 15 доменов.

**Точечные поправки к первоначальному общему выводу:**
- Inventory movement/reserve/fulfill — настоящие транзакционные операции; «нет пустых методов» верно для проверенного ядра, но нового SKU нельзя оприходовать через service-only boundary без предварительного ручного `inv_stock_balance`. Нужен first-position admission.
- `auto_reserve_on_confirm=FALSE` в настройках не влияет на безусловный резерв SALES; **рекомендуемая единая R1 семантика — всегда резервировать при confirm**, не пытаться поддержать оба режима без нового order state machine. `FIFO/FEFO/MANUAL` сейчас лишь metadata без выбора партий.
- Composite FK к ORG/CATALOG гарантирует тот же tenant, но не `ACTIVE WAREHOUSE` и не `PRODUCT`; новые складские команды должны учитывать status/kind и гонки деактивации.
- Ledger и reservation events пока не содержат actor; stock movement history не предоставлена кладовщику через verified facade; public `InventoryTenantSettingsService` с raw UUID actor требует TCRUD-04 hardening.
- Стоимостная политика immutable, но cost ledger отсутствует; **мaturity quantity-R1** не означает FIFO valuation, FEFO или COGS.
- Transfer и stocktake нужно добавлять на существующем balance/immutable ledger с минимальным transfer receipt и stocktake snapshot, а не создавать параллельный stock engine.

**Переоценка:** INVENTORY остаётся **2/5** до закрытия INV-Q0–Q9. Документ расширяет диагностику и ТЗ, Java не меняет; текущий статус **REVIEWED / SPEC PROPOSED**, не IMPLEMENTED. Указанные бизнес-решения требуют согласования до реализации. Новый CI документации не является предметной приёмкой.


---

## 7. Углублённые сервисные ревью — 12 проходов на домен

**Правило развития аудита R1.1:** после общего обзора каждый домен проходит 12 последовательных ревью с отдельным подробным ТЗ, подтверждёнными кодовыми доказательствами, бизнес-сценариями, смежными владельцами данных, негативными/конкурентными тестами и точным SHA. Смена домена — только после закрытия предыдущего по принятому объёму; документы не равны runtime qualification. Контроллеры и production admission по-прежнему вне Feature Freeze.

### ORGANIZATION: итерация 01/12 (cross-domain depth audit)

- **Подробное ТЗ:** [RM-ORG-FUNC-R1 — 12 итераций, lifecycle и интеграция](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md).
- **Исходный HEAD ревью:** `c776baec5c82e0dfd4c7930aa6a68629202fa5fd`, ветка `base_dev`. **Статус:** SPECIFIED; ни один новый runtime метод ещё не реализован и не квалифицирован.
- **Точная граница:** IAM владеет `org_organization`, `iam_workspace`, ролями и VerifiedActor; ORGANIZATION владеет только `org_location`; GEO — KATO, INVENTORY — stock/reservations, SALES — orders. Не создавать повторный юридический профиль организации в ORG.
- **Реально существующий surface:** `AuthenticatedOrganizationLocations.create/get/search`; внутренние `OrganizationLocationService.create`, `OrganizationLocationQueryService.get/search`. Уже есть tenant-scoped JPA, permission admission, `@Version`, статусы `ACTIVE/INACTIVE/CLOSED`; **нет** update/deactivate/reactivate/close.
- **P0 междоменные пробелы:** текущий INVENTORY receive/correct и SALES confirm не читают `org_location.status/type` перед изменением склада/заказа. Tenant-composite FK в `inv_stock_balance` проверяет идентичность, но не операционную пригодность склада. Требуется транзакционная typed-проверка ACTIVE WAREHOUSE с блокировкой для предотвращения гонки с deactivate; закрытие также должно проверять зависимости.
- **P1:** CAS на `rowVersion` для будущих изменений, атомарный event в существующий `crm.audit_event`, нормализация и KATO revalidation, литеральный поиск c экранированием `%/_`, ограниченный OFFSET и согласованный sort `createdAt DESC,id DESC`. Текущий sort `createdAt DESC,id ASC` детерминирован, но не совпадает с проектным SEARCH-R2.
- **Важная оговорка:** LIKE использует bind-параметр, поэтому поиск с `%/_` — не доказанная SQL injection. Статус недостатков — source-level GAP; отсутствуют runtime подтверждения и реальные PostgreSQL тесты для новых состояний.
- **Приёмка:** ORG-Q01..Q12 из подробного ТЗ; сначала R1.1A–D, затем 02/12…12/12 с фиксацией результатов. Текущие тесты покрывают создание/чтение/tenant и отдельную гонку revocation, но не закрытие склада и полномасштабные lifecycle flows.

**Обновление матрицы §2:** оценка ORGANIZATION остаётся **1/5**, пока не появится подтверждённое сквозное управление локациями. После документационного этапа нельзя менять её на 3/5 только из-за написанного ТЗ.


### SALES / AutoOrder: итерация 01/12 (предметный и перекрёстный source audit)

- **Отдельное подробное ТЗ:** [RM-SALES-AUTO-FUNC-R1 — 12 проходов и спецификация](../spec/RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md).
- **Исходный source HEAD ревью:** `169e4e2a0d641463984837ad76cea8aa70d9f265`. **Статус:** PASS 01/12 SOURCE REVIEWED / SPEC PROPOSED. Не объявлять 02–12 закрытыми, пока не представлены отдельные доказательства; этот документ **не** изменяет Java runtime.
- **Реальная бизнес-цепочка:** `AutoOrderService.checkout` фиксирует заказ, цену/title snapshot, receipt и status event; IAM email proof связывает покупателя; `AuthenticatedSalesCommands.confirm/cancel/fulfill` вызывает транзакционные INVENTORY reserve/release/fulfill при двойном permission; seller `AuthenticatedSalesQueries.search` выдаёт tenant-scoped страницу и count. Это не пустые методы — транзакционный ядро работает в пределах тестов.
- **Главный P0 cross-domain gap:** seller confirm и `InventoryReservationService.reserve` читают `catalog_item_id` из текущего `crm.store_offer`, а заказная строка сохраняет только title, quantity и unit price. Неизменяемый SKU snapshot отсутствует, поэтому необходимо зафиксировать исходный предмет исполнения на checkout и сохранить корректность старых заказов через populated migration.
- **Другие P0:** checkout может принять оффер, который позднее нельзя зарезервировать по SKU; confirm не использует ORGANIZATION `ACTIVE WAREHOUSE` guard; `auto_reserve_on_confirm` в INVENTORY на поведение SALES не влияет. Рекомендовано **always reserve** в R1 и запрет ложного изменяемого toggle, без параллельной state machine.
- **P1 functional:** нет seller `get/lines/history`, buyer read не пагинируется; отсутствует полноценный tenant-owned storefront offer lifecycle, CATALOG `publish` не синхронизирован с storefront; статусы доставки и `REJECTED` объявлены в схеме, но не имеют полноценных бизнес-команд. `COMPLETED` пока только складское исполнение, не оплата/доставка.
- **Аутентификация:** email proof не является OIDC; seller commands уже требуют VerifiedActor и две операции admission, но реальная доставка email/transport anti-abuse остаётся отдельной зависимостью, не должна открываться ради «закрытия CRUD».
- **Границы:** CRM Customer не создаётся автоматически по checkout-email, CATALOG владеет SKU/ценой, STOREFRONT оффером, SALES заказом, INVENTORY резервом, ORG статусом склада, IAM глобальной идентичностью. Payment/Finance, logistics, multiwarehouse, offer bargaining — не часть текущего R1.
- **Приёмка:** SALE-Q01..Q16 в ТЗ, slices SALE-R1.A–E после согласования D-01..D-08 и повторного code-to-test trace. **Maturity остаётся 2/5**, текущий gate — source review, не реализация и не PostgreSQL qualification новых функций.

**Следующее действие внутри этого же сервиса:** проход **02/12 — полный method → SQL/JDBC → permission → state → consumer → test trace**, затем 03/12 по aggregate/schema и populated migration. К другому домену не переходить по факту написания ТЗ.


### ORGANIZATION — проход 02/12: фактические бизнес-пути склада/филиала/POS

- **Детальное ревью:** [RM-ORG-FUNC-R1-P02](RM-ORG-FUNCTIONAL-DEPTH-PASS02-2026-11-22.md); **главное ТЗ обновлено:** [RM-ORG-FUNC-R1](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md). **Снимок source:** `2b4afbbabc6be0193def86a933d7947163f964ae`; runtime классы не изменены относительно первого аудита.
- **BJ-01 WAREHOUSE:** `create/get/search` работают, но новая SKU-позиция отсутствует в прикладном API. `InventoryMovementService.adjust` требует ранее созданный `inv_stock_balance`; существующие тесты делают SQL INSERT в arrange. Новый **ORG-11 / P0** — INVENTORY `initializePosition` и сервисный E2E «создать склад → инициализировать SKU → приёмка», без stock INSERT из ORG.
- **BJ-02 BRANCH:** полнота R1 определяется редактированием адреса/названия/КАТО и переходами ACTIVE/INACTIVE/CLOSED; не требуется новая IAM роль филиала или необоснованная иерархия.
- **BJ-03 POINT_OF_SALE:** `org_location.POINT_OF_SALE` не является действующим POS checkout: `crm.store_front` и `crm.store_offer` не имеют location_id. **ORG-12 / P2** — отдельно зафиксировать будущую связку, не реализовывать в ORG-R1.
- **IAM предусловие ORG-13 / P0:** `PlatformTenantBootstrapService.provision` создаёт новый tenant с enforced RBAC и без default operational grants; `TenantRoleService` запрещает self-assignment. Для реального сценария первый сотрудник должен законно получить ORG_READ/WRITE через IAM до создания склада.
- **ORG-14 / P1:** минимальный scoped usage-blocker по stock/reservations на границе INVENTORY; запрещать закрытие склада с положительным остатком/резервом, но не раскрывать SKU/количество без INVENTORY_STOCK_READ.
- **Уточнение lifecycle:** новый reserve/receipt/confirm — только ACTIVE WAREHOUSE; исторический idempotent replay без новых mutations и release/cancel cleanup не должны беспричинно блокироваться после смены статуса. При реализации учесть request-key stripe и order/stock locks, проверить deadlock на PG.
- **Приёмка:** сценарии ORG-BJ01..ORG-BJ12 — **NOT RUN**; старые тесты подтверждают только отдельные методы и scripted SQL setup. Этап **02/12 SPECIFIED**, не GREEN; ORG всё ещё **1/5**. Следующий этап 03/12 — lifecycle-инварианты и доказанные блокировки.
