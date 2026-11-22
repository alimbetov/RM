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
| INVENTORY | **3** | Q1–Q9: first receipt, staff ledger provenance/history/write-off, atomic transfer, stocktake, SALES reserve/fulfill, working mandatory-reservation policy — PostgreSQL-qualified | Financial valuation/FEFO/lot/serial, transport, real-load qualification and full tenant-wide TCRUD remain HOLD |
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

**Дополнение от 2026-11-22, имеет приоритет над указанной выше исторической оценкой:** Q1–Q9 реализованы и PostgreSQL-квалифицированы на source SHA `534aa18008bc54f77a4b1f0277d0f041bfa12bae`; завершено 12/12 предметных проходов **только количественного inventory-R1**. Текущая оценка **3/5**, а не 2/5. Верификация: [INV-Q7/Q9 PG & performance closure](RM-INVENTORY-Q7-Q9-PG-PERFORMANCE-CLOSURE-2026-11-22.md); реализационный [INVENTORY functional completion spec](../spec/RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md); все четыре CI workflow на точном SHA SUCCESS: 44987925832, 44987931306, 44987925857, 44987925840. Тесты INVENTORY 9/9+7/7 и SALES RBAC 16/16; исторические записи сохранены. Production, valuation, FEFO, transport и масштабная нагрузка по-прежнему HOLD. Следующий домен SALES/AutoOrder, используя существующий 12-проходный ТЗ; первоначальные GAP-01/07/08 по INVENTORY в этой общей таблице закрыты соответствующим Q1–Q9 code scope, а не переписаны задним числом.



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


### SALES / AutoOrder: итерация 02/12 (method-to-SQL test trace)

- **Source SHA:** `75a1fc96a732b8dcacf3b63d5e2a9bbf36627bbd`, `base_dev`; **подробный отдельный evidence:** [RM-SALES-AUTOORDER-METHOD-TRACE-PASS-02](RM-SALES-AUTOORDER-METHOD-TRACE-PASS-02-2026-11-22.md). ТЗ обновлено в [RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES](../spec/RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md).
- **Выполнен source method trace:** `checkout`, `verifyEmail`, `buyerOrders`+`AuthenticatedBuyerOrders.mine`; `AuthenticatedSalesCommands.confirm/cancel/fulfill` и одноимённые внутренние методы; `AuthenticatedSalesQueries.search` → `SalesOrderQueryService` → `SalesOrderRepository`; SQL helpers и `OrderInventoryPort`+reservation state changes. Для каждого описаны аргументы, principal, SQL-предикаты, транзакции, наблюдаемый эффект, status и конкретные тестовые методы.
- **Подтверждённые дополнительные дефекты и ограничения:** `cancel(PLACED)` избыточно требует INVENTORY_RELEASE без резерва (least-privilege требует решения); checkout читает offers отдельными запросами без offer-lock; входные количества ограничены, но после merge дубликатов агрегированная quantity не ограничена; buyer history не имеет LIMIT; checkout не делает явного admission ACTIVE tenant; независимые page/count запросы не являются единственным консистентным снимком.
- **Важное уточнение безопасности:** `verifyEmail` намеренно связывает все ещё не привязанные заказы одной **глобально доказанной** email identity, в том числе у разных продавцов; само по себе это не tenant leak, потому что buyer `mine` фильтрует owner, а seller queries — tenant.
- **Не меняем прежнюю зрелость:** SALES / AutoOrder остаётся **2/5**, `PASS 01–02 SOURCE REVIEWED`, `PASS 03–12 OPEN`. Созданы/обновлены только audit/spec; ни Java, ни DDL, ни контроллеры не изменялись; CI старого SHA не доказывает новый функционал.
- **Следующий проход 03/12:** выполнить полный schema/nullability/FK/index/legacy-data audit `store_offer`, `sales_order_line`, `sales_checkout_receipt`, `inv_stock_reservation`, спроектировать populated-safe immutable SKU snapshot без фиктивного backfill и уничтожения старых заказов.


### SALES / AutoOrder: итерация 03/12 (PostgreSQL order schema and immutable SKU)

- **Source SHA:** `1cc19715e82551ec22dd451427ea34b2a8b0c86d` (`base_dev`); **подробный PostgreSQL schema-review + план миграции:** [RM-SALES-AUTOORDER-POSTGRESQL-SCHEMA-PASS-03](RM-SALES-AUTOORDER-POSTGRESQL-SCHEMA-PASS-03-2026-11-22.md), доработано [ТЗ SALES](../spec/RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md).
- **Точная матрица из реальных Liquibase changesets:** `store_offer` с nullable `catalog_item_id`, `sales_order`, `sales_order_line` без SKU snapshot, `sales_checkout_receipt` с UNIQUE tenant/key, `sales_order_status_event` без CHECK event_type и без immutable trigger, `inv_stock_reservation` с UNIQUE reservation per order line, FK tenant+order+line и FK tenant+warehouse+SKU; проверены индексы rm:0019 и constraints rm:0025/0026.
- **Главный DB gap:** действующий составной FK reservation→order_line не включает SKU; нельзя считать его проверкой, что резерв относится к исходному предмету покупки. Текущий `store_offer.catalog_item_id` не является историческим snapshot. В `sales_order_line` price/title фиксируются на checkout, но не защищены DB immutable trigger от прямого UPDATE.
- **Безопасный предложенный алгоритм:** read-only статистика и pg_catalog клона заполненной PostgreSQL → additive nullable `sales_order_line.catalog_item_id_snapshot` и tenant FK → новые writers всегда заполняют snapshot → confirm/reserve используют его и fail-closed на NULL → не подставлять текущий SKU в старые строки → аудируемое доказательное разрешение legacy либо сохранение NULL history. Перед старым writer rollout требуется fencing. Миграция **пока не создана и не применена**.
- **DB vs Java:** ограничения уникальности, составные FK, stock balance CHECK и immutable ledger остаются в PostgreSQL; tenant grants/VerifiedActor, PRODUCT/PUBLISHED, ACTIVE WAREHOUSE, валюта и сумма, переходы состояний и порядок блокировок — транзакционный Java service. До реализации сначала квалифицировать SALE-DB03-01..10, populated migration и независимые негативные cases; ни одна новая функция не стала runtime GREEN.
- **Вердикт:** PASS 03/12 = **DDL SOURCE REVIEWED / MIGRATION SPEC PROPOSED**. Общая зрелость SALES/AutoOrder **2/5**. Далее только SALES pass **04/12** (state machine, команды и разрешения). Production HOLD.


### ORGANIZATION — проход 03/12: State Machine & Dependency Integrity

- **Подробное ревью:** [ORG 03/12 — State Machine / Dependency Integrity](RM-ORG-FUNCTIONAL-DEPTH-PASS03-2026-11-22.md); **согласованное ТЗ:** [RM-ORG-FUNC-R1](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md). **Снимок runtime:** `1cc19715e82551ec22dd451427ea34b2a8b0c86d`, ветка `base_dev`. Только документирование: ORG lifecycle НЕ IMPLEMENTED.
- **DEC-ORG-03-A:** `ACTIVE→INACTIVE` разрешён при `onHand>0`, когда `reserved=0` на всех SKU и нет ACTIVE reservations. Это временная пауза, а не списание товара. `INACTIVE→CLOSED` по-прежнему требует полного нуля `onHand/reserved` и отсутствия ACTIVE reservations; CLOSED терминален. Старое ограничение прохода 01/02 «склад с stock нельзя деактивировать» **заменено**.
- **Фактическая синхронизация со свежим INVENTORY:** после 02/12 реализованы и отдельно квалифицированы `INV-Q1..Q3` (source `d9819250089872ead3ddb869f821974041bd46a4`). `InventoryMovementService` автоматически создаёт нулевую позицию при первом разрешённом RECEIPT; `StockEligibility` проверяет ACTIVE WAREHOUSE/PRODUCT перед новым receive/reserve, old exact-payload replay происходит прежде повторной проверки. Поэтому историческая формулировка ORG-11 и часть ORG-02/03 из прохода 02 теперь **устарели**; не реализовывать ещё один `initializePosition`.
- **Оставшийся R1 GAP:** ORG state methods, location-row CAS, typed INVENTORY usage blockers, атомарные audit events и реальный параллельный `deactivate×receive/reserve/confirm` PostgreSQL E2E. `StockEligibility` пока использует `FOR UPDATE` по location, потенциально сериализуя независимые SKU на одном складе; менять на `FOR SHARE` только после доказательных race/lock-order/throughput tests. Fulfill на аномально неактивном legacy-складе требует узкой защиты без повреждения cleanup/replay.
- **KATO reactivation corner case:** исторический KATO, ставший deprecated при паузе, не должен безвозвратно запереть склад с остатками: повторная активация разрешает **сохранённое неизменённое** FK-значение, а любое новое значение обязано быть eligible по GEO. Физический переезд склада с запасом не реализуем под видом редактирования КАТО.
- **Приёмка:** проектная матрица `P03-Q01..Q14`, отдельные явные исходы/lock sequence/SQL EXISTS. **NOT RUN** для новых ORG сценариев; зрелость ORGANIZATION **1/5**, проход **03/12 CONTRACT SPECIFIED**, следующий **04/12** — изменение полей и CAS.

**Историческая пометка:** прежние пункты раздела «ORGANIZATION 01/12, 02/12» соответствуют собственным SHA, но не отменяют более позднюю фактическую реализацию INV-Q1..Q3 и принятое решение DEC-ORG-03-A. Читатель должен применять данный проход при расхождениях с прежним текстом.


### SALES / AutoOrder: итерация 04/12 — команды, state machine и резерв

- **Reviewed code SHA:** `fe50589b88d104843623ed598024e0de962bbcc2` (`base_dev`); методный подробный документ: [RM-SALES-AUTOORDER-STATE-MACHINE-PASS-04](RM-SALES-AUTOORDER-STATE-MACHINE-PASS-04-2026-11-22.md); [обновлённое 12-проходное ТЗ](../spec/RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md).
- **Реальный R1 workflow:** `checkout→PLACED`; `confirm→CONFIRMED` с резервом всех позиций; `cancel→CANCELLED` с release при наличии резервов; `fulfill→COMPLETED` с однократным stock movement; повторы фиксируются по уже достигнутым состояниям. `REJECTED`, `READY_FOR_PICKUP`, `OUT_FOR_DELIVERY` — только enum/DDL без сервисных команд; `PREPARING` поддержан как исходный статус cancel/fulfill, но нет публичной команды его установить.
- **Коррекция прежнего P0-вывода:** текущий `StockEligibility.requireReservable` уже проверяет `ACTIVE WAREHOUSE` и `PUBLISHED PRODUCT` с блокировкой при новом резерве. Не создавать дублирующий ORG guard в SALES; необходимы тесты согласованности переходов и конкуренции с деактивацией. Historical replays не должны требовать повторной публикации каталога.
- **Новые требования:** `cancel(PLACED)` с нулём резервов должен требовать только `SALES_ORDER_CANCEL` (согласовать); `cancel(CONFIRMED/PREPARING)` оставляет +`INVENTORY_RELEASE`; `reject(PLACED,reasonCode,expectedVersion)` как минимальная команда с атомарным событием; явный allowlist состояний вместо cancel denylist; запрет перевода заказа в COMPLETED без полного line-by-line подтверждения резерва и корректного исполнения.
- **Целостность:** текущие idempotent replay CONFIRMED и COMPLETED проверяют агрегированные counts, а не равенство SKU/quantity/location по каждой строке; эта детализация должна использовать proposed immutable SKU snapshot из pass 03. Existing `OrderInventoryPort` продолжает требовать пару IAM решений одного actor+tenant в текущей транзакции.
- **QA:** существующие PostgreSQL тесты покрывают cancel-vs-fulfill, rollback-after-inventory, grant revocation, mandatory-on-confirm и StockEligibility; новый `reject`, permission split PLACED, confirmed replay mismatch и статусные границы ещё не реализованы/не тестировались. Требования `SALE-SM04-01..16` — будущая qualification, не GREEN.
- **Status:** PASS 04/12 **SOURCE VERIFIED / SPEC PROPOSED**, не runtime implementation. SALES остаётся **2/5**. Следующий 05/12 — detail/lines/history, scoped pagination/PII; production HOLD.


### ORGANIZATION — проход 04/12: Create, Update & Data Integrity

- **Source HEAD:** `5d3eff7362ea895eb6af3b78a7fa478937569662`, `base_dev`. **Подробное доказательное ревью:** [RM-ORG-FUNC-R1-P04](RM-ORG-FUNCTIONAL-DEPTH-PASS04-2026-11-22.md); [консолидированное ТЗ 01–04/12](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md). **Результат:** 04/12 SPECIFIED, NOT IMPLEMENTED; общая зрелость ORGANIZATION 1/5.
- **Фактически:** `AuthenticatedOrganizationLocations` предоставляет create/get/search, `OrganizationLocationService.create` проверяет IAM WRITE, GEO KATO, затем JPA `saveAndFlush`. В `OrganizationLocationEntity` есть `@Version rowVersion`, но нет update, CAS admission, нормализации имени/адреса или audit CREATE. `OrganizationLocationRepository` имеет scoped get, **не** locked edit query.
- **DEC-ORG-04-A:** update — **полная замена только редактируемых полей**, а не JSON PATCH. Два typed records `LocationCreateRequest(Type,name,kato,address)` и `LocationUpdateRequest(name,kato,address,expectedVersion)`; `address=null/blank` — очистка, `name/kato` обязательны, status/type/tenant не редактируются. Оставить совместимый legacy create overload.
- **Нормализация/валидация:** `strip+NFC`, не пустое name 1..200 Unicode code points, address <=500/nullable, reject controls/invalid Unicode; KATO точный строковый `[0-9]{2,16}` с ведущими нулями. GEO eligibility требуется только при CREATE/changed-KATO, не при корректировке имени исторической локации со старым deprecated KATO.
- **Географическая целостность:** changed-KATO допускается при отсутствии live stock, **любой** истории `inv_stock_movement` и `inv_stock_reservation`. На текущем HEAD `InventoryTransferService` уже пишет две movement legs для INV-Q7, поэтому смена КАТО после transfer также блокируется; name/address correction остаётся допустима без переноса товара.
- **CAS:** tenant+ID scoped `@Lock(PESSIMISTIC_WRITE)`, `expectedVersion==rowVersion` перед mutation, JPA `@Version`; update no-op на текущей версии не создаёт события/версии, stale retry даже при одинаковом payload → `STALE_VERSION`. При фактической редакции — ровно один version increment, update timestamp и `ORG_LOCATION_UPDATE` audit внутри TX. Hibernate update SQL может быть по `id+version` без tenant в WHERE — изоляция обеспечивается **предыдущим** scoped locked load и глобальным PK, а не вымышленным tenant predicate в UPDATE.
- **CREATE нюанс:** существующие тесты допускают повторяющиеся имена в одном tenant; серверный UUID создаётся для каждого вызова, поэтому без request-id CREATE **не идемпотентен**. Нельзя обещать exactly-once или вводить случайный unique-name constraint.
- **План квалификации:** `P04-Q01…Q20`, включая Unicode, KATO deleted/changed-history, A/B RBAC, no-op/stale, двухконнектные update-vs-update/update-vs-deactivate/receive, audit rollback. **Новые P04 PostgreSQL тесты не запускались.** Следующий 05/12 — операционные зависимости INVENTORY/transfer и конкурентность локаций; до 12/12 другой домен не переключать.


### SALES / AutoOrder: итерация 05/12 — seller/buyer Read Model, order detail/history и PII

- **Source review SHA:** `23f3d2048ea5335a2b463ed86b53b68eff229fab` (`base_dev`). [Подробный PASS 05 evidence](RM-SALES-AUTOORDER-READ-MODEL-PASS-05-2026-11-22.md); [обновлённое SALES ТЗ](../spec/RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md).
- **Работающие queries:** `AuthenticatedSalesQueries.search` требует VerifiedActor/SALES_ORDER_READ, SQL `tenant_id=?` и фильтры status/half-open createdAt, page size 1..100, отдельные count/page; DTO без PII. `AuthenticatedBuyerOrders.mine` требует VerifiedActor, ACTIVE user+CURRENT verified email и возвращает собственные orders across sellers, но без LIMIT и только orderId/status. INVENTORY имеет самостоятельные защищённые stock and reservation history queries — не дублировать.
- **Functional missing:** seller `get` со frozen line snapshots и связанными reserve status/location; `history` по immutable status event; bounded buyer `mine(page,size)` и `ownDetail`. Actor/reason в sales events нет; legacy events нельзя обогащать выдуманными данными. `READ COMMITTED` нескольких header/line/reserve SELECT не гарантирует единый snapshot, concurrency проверить отдельно.
- **Важная поправка к ранее предложенному контракту:** `sales_order` PK — `(tenant_id,id)`, глобальный `UNIQUE(id)` отсутствует; поэтому buyer detail должен принимать `(sellerTenantId,orderId)` и проверять `buyer_user_id=actor.userId()`. `sellerTenantId` не является правом доступа, а только частью ключа.
- **Permission/PII:** `SALES_ORDER_READ` дает сведения о выбранном заказе, его frozen lines и связанных резервах, **не** глобальный stock balance/movements, buyer email/phone/identity или CRM Customer. Общие складские истории остаются за `INVENTORY_STOCK_READ`; buyer видит только собственные заказы у разных продавцов. Глобальный администратор без tenant grant не наследует implicit SALES access.
- **Приёмка:** `SALE-RD05-01..14` для seller get/history, line totals, PII, A/B/AB tenant, foreign composite keys, pagination 101+ buyer orders, legacy NULL SKU, inventory grant separation, transactional consistency. Это план квалификации, **не запущенные новые тесты**.
- **Итог:** PASS 05/12 **SOURCE REVIEWED / SPEC PROPOSED**, зрелость SALES 2/5, без Java/DDL/REST изменений, production HOLD. Следующий PASS 06/12: idempotency, write/read races, rollback и восстановление.


### ORGANIZATION — проход 05/12: INVENTORY Integration & Warehouse Integrity

- **Исходный source HEAD:** `1185d5554ae4bd07aa83109d12237ee0eec34846`, `base_dev`. **Отдельное техническое ревью:** [RM-ORG-FUNC-R1-P05](RM-ORG-FUNCTIONAL-DEPTH-PASS05-2026-11-22.md); [основное 12-проходное ТЗ обновлено](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md). **Статус:** SOURCE VERIFIED / CONTRACT SPECIFIED, не новая реализация и не E2E GREEN.
- **Подтверждено в коде INVENTORY:** `AuthenticatedInventoryCommands.receive/correct/writeOff/transfer`; `StockEligibility` с `org_location FOR UPDATE` и PRODUCT check; `InventoryMovementService` с first-receipt zero-init, idempotent replay, locked balance и immutable movement. Q7 `InventoryTransferService` записывает один transfer receipt и две immutable ledger legs; Q8 `InventoryStocktakeService` содержит OPEN/POSTED/CANCELLED, версионный snapshot, post/cancel. Новые записи на INACTIVE/CLOSED запрещаются в inventory owner admission; `ORG.deactivate/close` пока отсутствуют.
- **P05-01 / P0 — источник потенциального PostgreSQL deadlock:** Q7 transfer берёт `location A→PRODUCT X→location B` из-за двух вызовов `StockEligibility.requireReceivable`, в то время как параллельный receive/transfer в B может удерживать `location B` и ждать `PRODUCT X`. Требуется исправить последовательность **все location locks по UUID → PRODUCT один раз → stock locks по UUID**. Existing inverse-transfer test не доказывает безопасность смешанного расписания. Repro на PG с разными request stripes; *не* утверждать, что deadlock уже воспроизведён.
- **P05-02 / P1:** для `InventoryReservationService.fulfill` сейчас отсутствует отдельный ACTIVE warehouse guard; при legacy inconsistent ACTIVE reservation+INACTIVE warehouse возможна новая отрицательная проводка. Добавить guard для **новой** fulfillment до stock FOR UPDATE, сохраняя cleanup `release/cancel`, immutable historical replay и paired IAM permits. Проверять порядок совместно с SALES в проходе 06.
- **DEC-ORG-05-B:** появившаяся `crm.inv_stocktake` со статусом OPEN — дополнительная живая зависимость; `deactivate/close` запрещать при OPEN stocktake, пока её не POST/CANCEL; POSTED/CANCELLED history сохраняется и не блокирует. **DEC-ORG-03-A не отменён:** положительный свободный onHand по-прежнему допускается при временном INACTIVE, когда reserved=0, нет ACTIVE reservation и OPEN stocktake.
- **Typed dependency port для ORG:** `hasOnHand/hasReservedBalance/hasActiveReservation/hasOpenStocktake` по tenant+location из INVENTORY; read-only EXISTS под уже удерживаемым location lock. Не делать обратных `stock/order/stocktake_header FOR UPDATE` внутри ORG lifecycle. Следующая нагрузочная оценка `FOR SHARE` возможна только после PG квалификации race/lock order, без фиктивных p95/p99.
- **Приёмка:** `P05-Q01…Q20` — запланированные, **NOT RUN**: concurrent deactivate/close vs receipt/writeoff/transfer/confirm/reserve/start/post stocktake, Q7 mixed deadlock repro, exact replay after CLOSED, foreign tenant, rollback and immutable history. Зрелость ORGANIZATION остаётся **1/5**. Следующий ORGANIZATION 06/12: SALES operations and order-state coupling.


### SALES / AutoOrder: итерация 06/12 — транзакции, replay, IAM fencing, rollback

- **Reviewed source SHA:** `51d410865c90cfb92e5b28df5f273f7d9c355103` (`base_dev`), [подробный TX/source audit](RM-SALES-AUTOORDER-TRANSACTIONS-PASS-06-2026-11-22.md), [дополненное 12-проходное ТЗ](../spec/RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md). Ветка менялась параллельно: статусы относятся к исходному SHA.
- **Подтверждено:** checkout транзакционно фиксирует identity/заказ/строки/receipt/PLACED event/email outbox, сериализует один tenant/key с 256 bucket locks и SHA нормализованного payload; seller confirm/cancel/fulfill держит IAM transaction-scoped decisions + order FOR UPDATE + INVENTORY MANDATORY, balance and reservation locks, unique per-line reservation и per-reservation movement.
- **Семантика повтора:** same key checkout возвращает исходный orderId, но **текущий** статус; если storefront PAUSED, повтор не доходит до receipt (PUBLISHED проверяется первым). Email proof одноразовый, повторный challenge-token не является idempotent success. CONFIRMED/COMPLETED replay проверяет count, но не полные item/qty/location/movement соответствия строк. Это надо исправлять адресно, без переписывания движения склада.
- **Новые P0/P1:** unlocked multi-offer snapshot может смешать изменения офферов; отсутствует immutable SKU в line (PASS 03); нет failure-injection на весь checkout/IAM/outbox; PLACED cancel требует ненужную stock grant; buyer list unbounded, seller COUNT+page разные READ COMMITTED SELECT. Checkout retry при PAUSED — product policy pending, не уже подтверждённая security exploit.
- **Квалификация:** существующие тесты проверяют same-key concurrent checkout, second-line rollback confirm, revoke-vs-confirm, cancel-vs-fulfill, rollback after nested SALES operations, no oversell и actor/source attribution. Дополнительно предложена матрица **SALE-TX06-01..16** для snapshot price/SKU, checkout failpoints, paused replay, revoke cancel/fulfill, read/write isolation и PG load. Новые тесты **не писались и не запускались**.
- **Вердикт:** PASS 06 **SOURCE REVIEWED / SPEC PROPOSED**, SALES 2/5; нет изменений Java/DDL, нет нового PostgreSQL GREEN, production HOLD. Следующий **07/12** — какие настройки и feature flags реально изменяют поведение.


### ORGANIZATION — проход 06/12: SALES Integration & Order Lifecycle

- **Source HEAD:** `cf0ae73c22d881d703f072afcacd7c5de8f5deff` (base_dev, 22.11.2026). **Ревью метода/SQL/TX/permissions и итоговая матрица:** [RM-ORG-FUNC-R1-P06](RM-ORG-FUNCTIONAL-DEPTH-PASS06-2026-11-22.md). **Консолидированное ТЗ:** [RM-ORG-FUNC-R1 01–06](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md). **Статус:** P06 SPECIFIED, новые ORG/SALES PG tests NOT RUN, ORG lifecycle NOT IMPLEMENTED, зрелость ORGANIZATION 1/5.
- **Текущее SALES ядро:** `AuthenticatedSalesCommands.confirm/cancel/fulfill` → `SalesInventoryCommandService`; transactional seller order row FOR UPDATE; `OrderInventoryPort` с transaction-bound paired SALES+INVENTORY IAM decisions. `confirm PLACED→CONFIRMED` резервирует **все** строки, `cancel` освобождает резервы, `fulfill CONFIRMED/PREPARING→COMPLETED` проводит по одному отрицательному movement на reservation. Единство коммита подтверждается существующими qualification tests, но не новыми гонками с future ORG.deactivate.
- **DEC-ORG-06-A:** склад INACTIVE/CLOSED запрещает **новый** confirm/reserve и NEW fulfill, но не переписывает существующие orders, immutable history, read-only exact terminal replay и authorized cancel/release cleanup на legacy inconsistent location. `PLACED` ещё **не связан с конкретной location**: `crm.sales_order` не содержит `location_id`, привязка возникает только через `crm.inv_stock_reservation` после confirm. Checkout не следует запрещать из-за статуса произвольного склада.
- **P0 cross-domain contract:** `confirm(CONFIRMED)` и `fulfill(COMPLETED)` сейчас сверяют aggregate reservation count, но **не каждую line/SKU/quantity/location/movement**; `SalesInventoryCommandService.lines` читает изменяемую `store_offer.catalog_item_id`, а immutable `sales_order_line.catalog_item_id_snapshot` остаётся проектом SALES pass 03. Требуется per-line доказательство без COALESCE с live offer; legacy NULL snapshot — честный unknown, без угадывания.
- **P1 fulfillment gap:** `InventoryReservationService.transition` не проверяет ACTIVE warehouse перед **новой** fulfilled movement и держит stock lock даже перед возвратом terminal replay. Исправление точечное: order row lock → check exact terminal outcome → location status/type row lock **before** stock → stock/reservation → ledger; **не** требовать PUBLISHED PRODUCT для исполнения прежнего резерва, чтобы архив товара не блокировал законную отгрузку. `release` сохраняет cleanup без warehouse admission.
- **P1 least-privilege:** `cancel(PLACED)` сейчас требует `INVENTORY_RELEASE` даже при отсутствии резервов. Цель — `SALES_ORDER_CANCEL` при locked PLACED + доказанный zero reservations, paired release для CONFIRMED/PREPARING; учесть IAM lock order и stale state. Не ослаблять `OrderInventoryPort`.
- **Важное исправление после прохода 05:** потенциальный lock-order дефект Q7 transfer `location A→SKU→location B` **уже исправлен** методом `StockEligibility.requireTransferWarehouses` (оба location locks до PRODUCT), появился `receiptAtTransferDestinationDoesNotCycleWithWarehouseOwnerLocks`. Статус `P05-01` = code fix present / additional ORG races NOT QUALIFIED. Не описывать это как действующий source P0 на HEAD P06.
- **Приёмка:** 20 новых planned PostgreSQL `ORG-P06-Q01…Q20`: confirm/warehouse admission, exact reservation identity, cancel cleanup, legacy inactive fulfill negative, two-connection confirm/cancel/fulfill vs deactivate, rollback multi-SKU, A/B IAM/permission revocation, immutable replay. Тесты **NOT RUN**, нет текущего ORG.deactivate/close.
- **Изменение маршрута по требованию пользователя:** после **07/12 ORGANIZATION** оформить промежуточное закрытие и при желании перейти к другому сервису. Проходы **08–12 — DEFERRED / NOT DONE**; это не доказательство production readiness. Старое правило «не переключаться до 12/12» отменено более поздним указанием. Следующий проход 07/12 — IAM/RBAC/VerifiedActor/tenant boundaries.


### SALES / AutoOrder: итерация 07/12 — Settings & Feature Flags

- **Reviewed source SHA:** `7d8189bff795956a56b0f14608b82c2e9bd0d113`, `base_dev`; полный [PASS 07 — setting→writer→storage→consumer→effect audit](RM-SALES-AUTOORDER-SETTINGS-FEATURE-FLAGS-PASS-07-2026-11-22.md); [обновлённое 12-проходное ТЗ](../spec/RM-SALES-AUTOORDER-FUNCTIONAL-DEPTH-R1-12-PASSES.md).
- **Реально действуют:** `store_front.state` PUBLISHED допускает checkout, DRAFT/PAUSED блокирует; `store_offer.state` PUBLISHED допускает checkout, HIDDEN блокирует. Но trusted tenant-scoped commands для publish/pause/hide не подтверждены, state в БД управляется только fixture/внешней командой в обследованном R1. Нужен малый lifecycle и CAS, не новый config engine.
- **Фиктивные/декларативные параметры:** `StorefrontSettings.acceptOrders` и `defaultLocale` — Java record attributes без persisted storefront columns и без consumer в `AutoOrderService.checkout`; `canCheckout()` не управляет фактическим checkout. Не рекламировать самостоятельный toggle `acceptOrders` и работающую локализацию.
- **Уже исправлена операционная проекция INVENTORY:** `AuthenticatedInventorySettings` возвращает fixed `MANDATORY_ON_CONFIRM`, `NOT_IMPLEMENTED` picking, `POLICY_ONLY` valuation. Legacy columns `auto_reserve_on_confirm` и `picking_strategy` всё ещё записываются через deprecated internal `updatePicking`, но **не влияют** на обязательный reserve SALES и не запускают FIFO/FEFO picking. Утверждённая immutable cost policy реально хранится с CAS/revisions, но сама по себе не вычисляет себестоимость или COGS. `allow_negative_stock=FALSE` закреплено CHECK и не должно быть переключателем.
- **CATALOG:** `publish/archive/addPriceRevision` имеют business effect на catalog и admission нового reserve, однако не синхронизируют `store_offer.title/unit_price` с checkout. Explicit seller offer publication and price snapshot — будущие узкие контракты, не автоматическое объявление витрины готовой.
- **Предлагаемая реализация:** SALE-R1.7A truthful settings facade/deprecated flag isolation → R1.7B storefront/offer writer → R1.7C explicit catalog bridge → R1.7D synthetic PostgreSQL set→effect matrix `SALE-SET07-01..14`. Новых тестов/DDL/Java в этом проходе **нет**; ни один settings enum не равен runtime capability без реального потребителя.
- **Вердикт:** PASS 07/12 **SOURCE REVIEWED / SPEC PROPOSED**, SALES maturity **2/5**, production HOLD. Следующий **08/12** — сквозные междоменные связи и владельцы данных.


### ORGANIZATION — проход 07/12: IAM, Tenant RBAC & Security Boundary; промежуточное закрытие

- **Source HEAD:** `1f24bba79b228dff7a8b4df75f0f80fd700766e0` (`base_dev`). **Подробный pass:** [RM-ORG-FUNC-R1-P07](RM-ORG-FUNCTIONAL-DEPTH-PASS07-2026-11-22.md). **Промежуточный closure report:** [RM-ORG-INTERIM-R1-07](RM-ORG-INTERIM-CLOSURE-07-2026-11-22.md). **Основное ТЗ 01–07:** [RM-ORG-FUNC-R1](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md).
- **Trusted entrypoint:** `VerifiedActor` из `OidcActorResolver`, signature/issuer/audience/expiry в условно включаемом `OidcJwtConfiguration`; `OrganizationLocationService.create` и `OrganizationLocationQueryService.get/search` через `TenantAuthorization.requireForCommand`. Actor row `FOR SHARE` → workspace `FOR SHARE` → свежая membership/permission в одной TX; role-revoke/admin mutates tenant `FOR UPDATE`. `OrderInventoryPort` проверяет paired SALES/INV transaction-bound IAM Decisions. Не заявлять, что это уже HTTP OIDC production gateway.
- **Tenant A/B/AB:** существующие `OrganizationLocationQueryQualificationTest.tcrud03SameGlobalMemberDifferentLocationGrantsAndForeignIdDenied` и `IamUserOnboardingProcessQualificationTest.oneLoginListsOnlyOwnTenantsAndDifferentFunctionalRoles` проверяют одного пользователя с A WRITE, B READ и другую комбинацию permissions; чужой location UUID в scoped get denied. Existing `TenantRbacRevocationQualificationTest` проверяет оба порядка commit-vs-revoke и suspended actor/member/tenant. Это **проверенное наличие кода и tests**, не новые PG test results этого прохода.
- **Граница нового tenant:** `PlatformTenantBootstrapService.provision` создаёт RBAC enforced=TRUE, без функциональных grants; OWNER-only роль управляет grants, но `TenantRoleService.assign` запрещает self-assignment. Поддержанный путь: owner → role/grant → invite OIDC-linked employee → employee accept → assign → service action. **P07-F01 P1 продуктовый GAP:** solo-owner не может самостоятельно использовать ORG_WRITE после bootstrap. Решение предлагается как *явное auditable owner-only self-assignment уже созданной functional role*, без wildcard/TENANT_RBAC_MANAGE delegation, только после отдельного approval, или фиксированный staff-only путь.
- **Legacy fallback:** когда `iam_tenant_rbac_config.enforced=FALSE`, `TenantAuthorization.require` допускает baseline membership-role permissions; новые tenants provisioned true. Для строгого platform-wide RBAC потребуются inventory legacy tenants и populated-safe migration/enable, не принудительный auto-switch.
- **Legacy helper:** `TenantAuthorization.requireOrganizationWriter` public shortcut вызывает нефенсированный `require`, но **не используется текущим ORGANIZATION create**, который правильно вызывает requireForCommand. Запрет на новые write-call-sites через helper, future deprecation после audit callers.
- **ORG lifecycle всё ещё NOT IMPLEMENTED:** update/deactivate/reactivate/close, audit/state, typed blocker, SQL lock integration. Новые `ORG-P07-Q01…Q24` NOT RUN; при этом старые орган-задачи от P03–P06 остаются тем же P0/P1 backlog.
- **Итог по изменённому пользователем плану:** 07 из 12 проходов **SPECIFIED**, ORGANIZATION **1/5** без runtime green и **production HOLD**. **08/12–12/12 сознательно DEFERRED / NOT DONE**; сменить предметный аудит на следующий сервис допускается после этого промежуточного заключения, это **не** закрытие технической приёмки. Из междоменных зависимостей следующий кандидат **CATALOG** (PRODUCT lifecycle, offer SKU, publish/archive), не обозначать как уже проверенный.


### CATALOG — проход 01/12: PRODUCT/SERVICE, SKU and inventory/sales lifecycle

- **Следующий выбранный пользователем домен после ORGANIZATION interim 07/12:** CATALOG. **Reviewed source HEAD:** `77dffdf43eb179e6b355764204ce79b775f7e586`, `base_dev`. **Доказательный pass 01:** [CAT-P01](RM-CATALOG-FUNCTIONAL-DEPTH-PASS01-2026-11-22.md). **Нормативное ТЗ:** [RM-CAT-FUNC-R1-12](../spec/RM-CATALOG-FUNCTIONAL-DEPTH-R1-12-PASSES.md).
- **Реально реализовано:** `CatalogItemService.createDraft/updateModelCode/upsertTranslation/addPriceRevision/publish/archive` с `VerifiedActor + TenantAuthorization.requireForCommand`, expectedVersion/`crm.cat_item FOR UPDATE`, `CATALOG_WRITE/PRICE_MANAGE/PUBLISH`. `CatalogQueryService.get/search/priceHistory/history` требует `CATALOG_READ`. SKU локально уникален `(tenant_id,sku)`, kind `PRODUCT/SERVICE`, state `DRAFT/PUBLISHED/ARCHIVED`; цены и аудит append-only, PG immutability triggers. `CatalogQualificationTest` содержит 3 source-verified тестовых метода, новых тестов в pass 01 не запускали.
- **CAT-G01 P0 — unsafe archive:** `archive` прямо переключает state без inventory usage или active-offer guard. `StockEligibility` запрещает все новые `receive/correct/writeOff/transfer` для ARCHIVED SKU. При ненулевом свободном остатке товар становится невозможным к штатному перемещению/списанию после архивации. Требуется item-row lock → inventory-owned **read-only EXISTS** blockers for onHand/reserved/ACTIVE reservation/OPEN stocktake + storefront-owner PUBLISHED offer blocker; не брать обратный stock/location FOR UPDATE под item lock.
- **CAT-G02 P0 — checkout admission gap:** `AutoOrderService.checkout` проверяет PUBLISHED `store_offer` и копирует `title/unit_price/currency`, **не** проверяет `catalog_item_id`, `cat_item.kind/state` или CATALOG price. Поэтому SERVICE, ARCHIVED/DRAFT item либо NULL-mapped PUBLISHED offer может создать `PLACED`, который текущий stock-only confirm не зарезервирует. Нужен явный PRODUCT/PUBLISHED admission для stock-backed offer, а не выдуманное SERVICE-резервирование; текущая price sale source — **store_offer**, не cat_price.
- **CAT-G03 P0 — historical SKU substitution:** `sales_order_line` фиксирует title/price/qty/offer_id, но не `catalog_item_id_snapshot`; `SalesInventoryCommandService.lines` и `InventoryReservationService.reserve` позже читают **живой** `store_offer.catalog_item_id`. Требование SALES pass 03 о nullable additive immutable SKU snapshot обязательно повторно используется при CATALOG integration, не отдельная новая сущность. Исторический NULL fail-closed для нового confirm, без backfill из текущего оффера.
- **Other P1:** catalog `cat_price` latest effective query does not partition currency, app-only `valid_from` collision validation without DB unique; no offer price propagation or seller-authorized offer lifecycle; same-value draft updates bump rowVersion/events; description exists DB but no writer; no locale/title search. Avoid speculative dimensions and extra services.
- **Proposed DEC-CAT-01-A…G:** immutable SKU/kind/tenant; source card publication separate from offer publication and warehouse stock; guarded archival; SERVICE catalog without stock reservation; immutable order snapshots; explicit seller offer pricing; separate tenant permissions. Preliminary CATALOG maturity **2/5**, production HOLD.
- **Planned tests:** `CAT-P01-Q01…Q18`, all **NOT RUN**. Master 12-pass structure established; practical intermediate checkpoint after pass 07 may be used as in ORGANIZATION, with 08–12 as deferred release gates, not as completed evidence. **Next pass 02/12:** real parameter/DTO/SQL/CAS/validation trace of create/edit/translation/price/publish/archive.
