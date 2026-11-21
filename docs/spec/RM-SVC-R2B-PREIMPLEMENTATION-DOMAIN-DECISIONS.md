# RM-SVC-R2B — Предреализационное уточнение CRM/CATALOG/SEARCH

**Статус:** REVIEWED DESIGN / предлагается к утверждению владельцем продукта до Java/DDL; **код и миграции не реализованы**.  
**Ветка:** `base_dev`. **Проверенный baseline:** `91b6418404d12621a47079a6f922a7b6ed255dd2`.  
**Связанные ТЗ:** [RM-SVC-R2](RM-SVC-R2-SERVICE-COMPLETION-CROSS-DOMAIN-CONSISTENCY.md), [RM-SVC-R2A](RM-SVC-R2A-FILTERING-PAGINATION-CONTRACT.md).  
**Область:** CRM Customer categories, Customer Contracts + expiry indicator, CATALOG modelCode, поиск/пагинация, security, миграции и тесты. Ни контроллеров, ни новых сервисов/микросервисов, ни merge в main.

## A. Подтверждённые факты и архитектурные расхождения

1. `crm.customer` имеет `customer_type PERSON/COMPANY`, `status`, `region_zone_id`, `kato_code`, `row_version`, **не имеет** категории, действующего договора или конечной даты договора.
2. `crm.cat_item` имеет tenant, id, уникальный для tenant `sku`, `kind PRODUCT/SERVICE`, state, source_locale и row_version, но **не имеет** modelCode; есть `crm.cat_item_i18n` и `crm.cat_price`.
3. `CrmSearchService.customers` пока использует `Page`, OFFSET, count, статусы и company name; PERSON name зашифрован отдельно в `crm_pii`.
4. `crm.sales_order` в текущем baseline связан с buyer IAM (`buyer_user_id`) и `pending_email_identity_id`, **а не прямым customer_id**. Новые CRM contracts/customerId нельзя автоматически считать linked к SALES; нужен отдельный формальный mapping decision, не входящий в текущую миграцию.
5. Существующая категория catalog item не подтверждена таблицей `crm.cat_item`; существующий `kind PRODUCT/SERVICE` не является бизнес-категорией каталога.

## B. Решения к реализации (рекомендованы)

### B1. Категории CRM — ровно одна primary category

**Принять как первую версию:** категории управляются в tenant-области, не hardcoded в Java и не IAM Roles.

Схема для additive Liquibase миграции:
- `crm.customer_category(tenant_id,code VARCHAR(48),name VARCHAR(120),active BOOLEAN,sort_order INT,row_version BIGINT)`, `PRIMARY KEY(tenant_id,code)`, FK tenant.
- `crm.customer.category_code VARCHAR(48) NULL`, составной FK `(tenant_id,category_code) → crm.customer_category(tenant_id,code)`.
- `NULL` допустим для ранее созданных клиентов; миграция не назначает всем `STANDARD` молча.
- Primary category одна на клиента. Более сложная сегментация/теги и несколько категорий — **отложить**, пока не появится реальная бизнес-потребность.
- `code` tenant-unique, immutable после создания; название можно редактировать, не влияя на код. `inactive` категория не назначается новым клиентам, но исторические назначения остаются.
- Customer category update требует existing CRM_WRITE и CAS `customer.row_version` + audit. New permissions для этого этапа не вводятся.

Минимальные операции: `createCategory`, `renameCategory`, `deactivateCategory`, `listCategories`, `assignCustomerCategory`. Все через VerifiedActor facade, tenant checks и существующие CRM permissions. Не раскрывать имена физических лиц в списке категорий.

### B2. Договоры CRM — отдельный агрегат и явная дата окончания

**Принять минимальный CRM customer contract**, не помещать `contract_end_at` в `crm.customer`: клиент может иметь несколько документов/пролонгаций.

Схема (имена итоговых Liquibase changeset выбрать при реализации после инспекции текущего конца файла):
- `crm.customer_contract(tenant_id UUID, id UUID, customer_id UUID, contract_no VARCHAR(80), starts_on DATE, ends_on DATE NULL, state VARCHAR(16), created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, row_version BIGINT, ...)`.
- `PRIMARY KEY(tenant_id,id)`; `FOREIGN KEY(tenant_id,customer_id) REFERENCES crm.customer(tenant_id,id)`; `UNIQUE(tenant_id,contract_no)`; `CHECK(ends_on IS NULL OR ends_on>=starts_on)`; `CHECK state IN ('DRAFT','ACTIVE','TERMINATED')`.
- `EXPIRED` — **не stored state**; это вычисляемый effective indicator при `ends_on < asOfDate`. Не вводить cron job, ежедневно переписывающий state/row_version. В частности, `DRAFT` и `TERMINATED` остаются юридическими состояниями.
- `starts_on` и `ends_on` — `LocalDate`, а не timestamp. Договор считается действующим **по дату `ends_on` включительно**, поэтому `daysRemaining=0` означает последний день.
- `ACTIVE` без `ends_on` — бессрочный; `ACTIVE` до `starts_on` — `NOT_STARTED`, после `ends_on` — `EXPIRED`.
- `contract_no` **не выводить в массовый CustomerSummary**; если номер содержит PII/чувствительный текст, политики маскирования и хранения нужно утвердить отдельно.
- Для первого этапа договор — CRM record/policy; **не пытаться синхронизировать его автоматически** с существующими IAM-buyer `sales_order`.

Минимальные операции: `createDraftContract`, `updateDraftContract`, `activateContract`, `terminateContract`, `getContract`, `listCustomerContracts`. После активации даты/номер нельзя свободно менять; продление оформлять новым договором или явной amendment/revision (второе — отдельная задача). Физический delete активного/терминированного договора запрещён.

### B3. Правило «сколько осталось» при нескольких договорах

**Клиентский агрегатный индикатор = ближайший к окончанию НЕЗАВЕРШЁННЫЙ ДЕЙСТВУЮЩИЙ срочный договор.** Для детального просмотра доступны все договоры.

Вычисление из `asOfDate` в `tenant ZoneId`, без daily persistence:
- Для каждого договора state=ACTIVE и starts_on<=asOfDate:
  - ends_on IS NULL → OPEN_ENDED;
  - ends_on<asOfDate → EXPIRED;
  - `remainingDays = ChronoUnit.DAYS.between(asOfDate,ends_on)`; 0..criticalDays → CRITICAL; (criticalDays,warningDays] → EXPIRING_SOON; >warningDays → ACTIVE.
- **Выбор клиентского индикатора:** сначала ближайший по `ends_on` действующий срочный, `ends_on>=asOfDate`. Если нет такого, но есть бессрочный ACTIVE → OPEN_ENDED. Если только истёкшие ACTIVE → EXPIRED с `daysRemaining<0` (выбрать самый недавно истекший договор). Если только будущие ACTIVE → NOT_STARTED. Если только TERMINATED/DRAFT → NO_ACTIVE_CONTRACT. Если записей нет → NO_CONTRACT.
- Если одновременно есть просроченный ACTIVE и ещё действующий ACTIVE, summary выбирает действующий и **отдельно** возвращает `expiredContractCount`; не скрывать риски.
- Для нескольких одинаковых `ends_on` tie-breaker `contract_id ASC` (детерминизм).
- `warningDays=30`, `criticalDays=7` — tenant settings (значения можно реализовать как конфигурацию существующего tenant setting, **не создавать новую общую платформу настроек**), 1..365 и 1..warningDays.
- В summary обязательно `asOfDate`, `contractId`, `endsOn`, `remainingDays`, `indicator`, `activeContractCount`, `expiredContractCount`. Отсутствие даты = `remainingDays=null`; не записывать 0.
- Уточнение по словам UI: `CRITICAL` — 0..7 дней; `EXPIRING_SOON` — 8..30; `ACTIVE` — 31+, `EXPIRED` — отрицательное; интервал включительно.

**Принципиальная граница:** «срок договора» — **не** дата архивирования CRM customer. Истечение договора не архивирует клиента и не блокирует продажу автоматически без отдельного бизнес-правила.

### B4. Код модели в CATALOG

**Принимаем отдельное `model_code` на `crm.cat_item`, не подменять `sku`.**

- Additive `crm.cat_item.model_code VARCHAR(80) NULL`; backfill старых строк только при известных данных; не копировать SKU автоматически как код модели.
- `PRODUCT` — модель товара; `SERVICE` — код типа услуги/модели услуги, одна модель может иметь несколько SKU (тариф, длительность, комплектация).
- `model_code` не уникален на уровне `crm.cat_item`; `sku` остаётся tenant-unique. Указание model_code само по себе **не создаёт новый CatalogModel aggregate**.
- Canonical normalization: trim + Unicode NFC + `Locale.ROOT` uppercase; консервативный набор `[A-Z0-9][A-Z0-9._/-]{0,79}`; запрет arbitrary SQL wildcard в equality/prefix.
- `model_code` nullable у DRAFT и у legacy PUBLISHED после additive миграции. **Не включать жёсткое правило NOT NULL для публикации до принятия и исполнения data-backfill plan**. Для новых publish можно требовать непустой код через версионированную domain validation после квалификации.
- Index `(tenant_id,kind,model_code,id)` обсуждать только после `EXPLAIN (ANALYZE, BUFFERS)` и проверки существующих индексов. `ILIKE '%...%'` по модели в первой версии не вводить.
- `model_code` и `sku` попадают в неизменяемый snapshot SALES line **только после отдельного совместимого изменения SALES schema/contract**; текущая sales_order структура не гарантирует такой записи.

### B5. Фильтры и пагинация — без N+1 и неоднозначного cursor

- `CustomerFilter`: `type,status,regionZoneId,categoryCode,companyNameContains,createdFrom,createdTo,contractIndicator,expiresWithinDays,contractEndsFrom,contractEndsTo`.
- `CatalogFilter`: `kind,status,sku,modelCode,modelCodePrefix,categoryCode` (последнее только когда реально введён каталог категорий), `name` по разрешённым публичным локалям.
- `expiresWithinDays=N` означает **действующий срочный договор, заканчивающийся от asOfDate до asOfDate+N включительно**; `EXPIRED` в этот фильтр не попадает. `contractEndsTo` явно exclusive.
- `contractIndicator` фильтруется по агрегатной выбранной CRM contract projection, *не* просто `EXISTS` по любому договору, иначе строка и индикатор могут противоречить друг другу.
- Для клиентов без договора `contractIndicator=NO_CONTRACT` возможен; `expiresWithinDays=0` — только заканчивается сегодня, не «без срока».
- Контрактные фильтры и метрики считаются **в PostgreSQL до LIMIT+1**, одним scope-safe SQL/query plan. Допустим `LEFT JOIN LATERAL`/CTE/агрегат по договору; не загружать/расшифровывать каждого клиента для фильтрации, не допускать N+1.
- Default sort `created_at DESC,id DESC`; optional expiry sort `ends_on ASC,id ASC` (nulls last), **другой тип и содержимое cursor**. Для фильтров, зависящих от `asOfDate`, cursor обязан фиксировать `asOfDate` и интерпретировать фильтры на тот же день при следующих страницах; не создавать разницу на границе полуночи.
- Для поиска по modelCode вместе с несколькими SKU — `ORDER BY model_code ASC,id ASC` при model sort, на одной модели не должно быть ни пропусков, ни дубликатов.
- Не подменять `COUNT(*)` дешёвой магией — offset Page оставлять совместимым, cursor использует `LIMIT+1`; фильтры/индексы с `EXPLAIN` и фактическими синтетическими объёмами. Total count отдельная опция только после измерений.

## C. Сервисные контракты и ownership

### C1. CRM

```java
record CustomerFilter(String type, String status, String regionZoneId,
                      String categoryCode, String companyNameContains,
                      Instant createdFrom, Instant createdTo,
                      String contractIndicator, Integer expiresWithinDays,
                      LocalDate contractEndsFrom, LocalDate contractEndsTo) {}

record ContractExpirySummary(UUID contractId, LocalDate endsOn,
                             Long daysRemaining, String indicator,
                             int activeContractCount, int expiredContractCount,
                             LocalDate asOfDate) {}
```

Конкретные Java enum/record могут отличаться именами без изменения семантики. Все CRUD команды принимают VerifiedActor, tenant, expectedVersion и requestKey где применимо, через существующий authenticated facade; tenant scope + CRM_READ и CRM_PII_READ отдельно. `get/list contracts` требует CRM owner authorization и явной contract permission policy; не раскрывать договорные номера/метаданные FILES непривилегированным пользователям. При отсутствии договорного owner ACL — временно deny, не открывать tenant-wide чтение «автоматически».

### C2. CATALOG

```java
record CatalogFilter(String kind, String status, String sku,
                     String modelCode, String modelCodePrefix,
                     String categoryCode) {}
```

CATALOG является authoritative source для modelCode. INVENTORY продолжает ссылаться на `item_id` и SKU, не начинает хранить дублирующую master-копию модели. SALES сохраняет согласованный snapshot лишь при утверждённой интеграции; FILES привязки к каталогу — через отдельный owner port.

## D. Минимальные миграции и порядок раскатки

1. `R2B.0` — зафиксировать единую семантику договоров/категорий/modelCode, описанную выше; проверить актуальность authority schema и пермишенов. **No DDL until accepted**.
2. `R2B.1` — additive DDL: `customer_category`, nullable `customer.category_code`, `customer_contract`, nullable `cat_item.model_code` + constraints/FKs. Проверить на populated PostgreSQL, без опасного NOT NULL.
3. `R2B.2` — сервисы categories + contracts (createDraft/activate/terminate/list) с CAS, idempotency, tenant owner ACL, audit; вычисляемый индикатор и projection.
4. `R2B.3` — typed CRM query + keyset, фильтры категорий/индикатора; оставить legacy Page API. Счётчики/расчёты в PostgreSQL до LIMIT.
5. `R2B.4` — CATALOG modelCode lifecycle + equality/prefix search, совместимые DRAFT/PUBLISHED, доп. индекс только после EXPLAIN.
6. `R2B.5` — согласование SALES/INVENTORY snapshot, FILES attachments только после завершения соответствующего domain-owner port; не смешивать в одном PR с миграцией каталога.

## E. Обязательная приёмка (Definition of Done)

| Область | Минимальная негативная/конкурентная проверка |
|---|---|
| Categories | Категория чужого tenant, inactive category, CAS конфликт, удаление категории с назначенными клиентами запрещено |
| Contracts | Перекрывающиеся интервалы **допустимы**, если бизнес не ввёл уникальность; 0/7/8/30/31 дней, бессрочный, просроченный, будущий, terminated, несколько договоров, равная дата; нет N+1 |
| Effective status | Истечение даты не запускает массовый UPDATE или архив CRM; вычисления при смене tenant timezone/полуночи корректны |
| CRM search | status/category/contract combined filter + keyset без дублей при одинаковом createdAt; asOfDate привязан к cursor; foreign tenant, revoked role, hidden PII |
| Catalog model | Одинаковая модель у нескольких SKU допустима; уникальность SKU неизменна; legacy PUBLISHED без modelCode читается; model prefix, category availability, stable cursor |
| SQL/Load | EXPLAIN BUFFERS на 10k/100k synthetic rows + max limit, no `COUNT(*)` per keyset page, no N+1; SLA только после утверждения профиля |
| Migration | Empty DB + populated PostgreSQL + rollback strategy; H2 smoke, IAM/CRM PII regression; RustFS real-infra unchanged |

**Release decision:** ТЗ **READY FOR DESIGN APPROVAL**, но **NOT IMPLEMENTED** и **NOT PRODUCTION-ADMITTED**. Доступность реального search/contract/modelCode наступает лишь после Java/DDL реализации и CI на точном SHA.

## F. Вопросы, требующие продуктового утверждения

1. **Категория клиента:** допускаем одну primary category, не набор тегов? Рекомендация: **да, одна** в R1.
2. **Договоры:** CRM должен вести собственный договор или это read projection от внешнего контрактного модуля? **По проверенной схеме RM собственного договора нет; рекомендуем CRM aggregate**, до появления отдельного authority.
3. **Индикатор нескольких договоров:** ближайший действующий срочный + счётчик просроченных? Рекомендация: **да**.
4. **Модель товара/услуги:** один modelCode для множества SKU и nullable для существующих published записей? Рекомендация: **да**.
5. **Порог уведомлений:** 30 дней предупреждение, 7 дней критично, tenant-local дата? Рекомендация: **да**.

Если решения подтверждены владельцем проекта, можно применять их как зафиксированные требования без дополнительного раунда абстрактного архитектурного согласования.
