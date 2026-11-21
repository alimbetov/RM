# RM-MODULAR-DOMAIN-DB-R1 — ТЗ по модульным доменам и префиксам таблиц

**Статус:** PROPOSED — на утверждение. Требование пользователя: домены изолируются по модулям, **каждая новая бизнес-таблица имеет префикс своего модуля**.
**Ветка:** `feat/rm-kms-r1-implementation`. Миграции существующих физических таблиц этим ТЗ **не выполняются**.
**Дата:** 2026-11-21
**Технологии:** Java 21, Spring Boot 4, PostgreSQL + Liquibase, JPA/Hibernate, единый modular monolith. H2 только dev/smoke.

## 1. Цель и границы

- Каждый модуль владеет моделями, таблицами, миграциями и бизнес-операциями. Один бизнес-инвариант — один авторитетный модуль.
- Новые таблицы названы `<module_prefix>_<entity_name>` даже при использовании PostgreSQL schemas; схема — отдельная граница доступа, **префикс — обязательный однозначный маркер домена**.
- Начальная реализация: **одна база PostgreSQL**, без микросервисов, межсервисных распределённых транзакций и партиционирования всех таблиц. Нельзя создавать отдельную БД на домен без отдельного обоснования.
- **Факт vs цель:** в существующей ветке часть таблиц не соответствует предложенной системе (например `crm.customer`, `security.tenant_key`, `ref_kato_territory`). Они остаются legacy/current до отдельной совместимой миграции; новые изменения не переписывают уже исполненные Liquibase changesets.

## 2. Каталог модулей и префиксов

| Модуль (код) | Java base package | Префикс таблиц | Базовые агрегаты и пример новых таблиц | Этап |
|---|---|---|---|---|
| IAM | `kz.rm.iam` | `iam_` | `iam_user_account`, `iam_tenant`, `iam_tenant_membership`, `iam_global_role_assignment` | IAM-R1 |
| ORG | `kz.rm.organization` | `org_` | `org_organization`, `org_location`, `org_unit` | IAM/GEO-R1 |
| GEO | `kz.rm.geo` | `geo_` | `geo_kato_territory`, `geo_kato_revision` | GEO-R1 |
| FILES | `kz.rm.files` | `file_` | `file_object`, `file_storage`, `file_attachment` | FILES-R1.0 |
| CRM | `kz.rm.crm` | `crm_` | `crm_customer`, `crm_contact`, `crm_email_consent_event`, `crm_customer_event` | CRM-R1A |
| CATALOG | `kz.rm.catalog` | `cat_` | `cat_category`, `cat_item`, `cat_variant`, `cat_price_list`, `cat_price` | CRM-R1A.5 |
| SALES | `kz.rm.sales` | `sales_` | `sales_order`, `sales_order_line`, `sales_estimate`, `sales_estimate_revision` | CRM-R1B/C |
| INVENTORY | `kz.rm.inventory` | `inv_` | `inv_stock_balance`, `inv_stock_movement`, `inv_stock_reservation` | CRM-R1B+ |
| FINANCE | `kz.rm.finance` | `fin_` | `fin_invoice`, `fin_payment`, `fin_payment_allocation` | CRM-R1D |
| MARKETING | `kz.rm.marketing` | `mkt_` | `mkt_segment`, `mkt_template`, `mkt_campaign`, `mkt_delivery_event` | CRM-R1E |
| TASKS | `kz.rm.tasks` | `task_` | `task_item`, `task_communication` | CRM-R1F |
| AUDIT | `kz.rm.audit` | `audit_` | `audit_event` (append-only) | IAM/CRM-R1 |
| KMS | `kz.rm.security.kms` | `kms_` | `security.kms_tenant_key`, `security.kms_tenant_key_archive` | Существующий KMS |
| SUBSCRIPTION | `kz.rm.subscription` | `sub_` | `sub_plan`, `sub_subscription`, `sub_usage_counter` | После IAM |

**Правила:** уникальные, не перекрывающиеся короткие префиксы; нижний регистр и `snake_case`; только латинские буквы/цифры/подчёркивания; рекомендуемая полная длина имени таблицы/индекса < 63 байт PostgreSQL; не называть таблицы SQL reserved words; префикс описывает **владельца** таблицы, не тип ссылки. Например, `sales_order` содержит `customer_id`, но не переименовывается в `crm_sales_order`.

## 3. Схемы PostgreSQL: без дублирования смысла

**Рекомендация R1:** не плодить отдельную schema на каждый модуль. Уже есть историческая `public` для IAM/ORG/GEO, `security` для KMS, `crm` для бизнес-данных, `crm_pii` для зашифрованных клиентских полей. Эти схемы можно сохранять в первом релизе: принадлежность модулю однозначно задаётся префиксом.

Примеры: `crm.crm_customer`, `crm.crm_contact`, `crm_pii.crm_customer_secret`, `crm_pii.crm_contact_secret`, `crm.cat_item`, `crm.sales_order`, `crm.fin_invoice`. Файловый домен: `crm.file_object`, `crm.file_storage`, `crm.file_attachment` **либо** `files.file_object` в отдельной schema `files` (если SQL grants и ответственность за отдельную schema действительно нужны). **Предпочтение:** `crm.file_*` для простоты раннего релиза, при этом байты файлов не хранятся в БД.

Для security-зон `crm_pii` отдельные права DB role обязательны для реальной изоляции, а не только название схемы. **Schema + prefix не защищают от чужих запросов без IAM/RBAC, tenant scoping и grant.** KMS всегда использует `tenant_id` UUID.

## 4. Единые имена полей

| Поле | Тип | Обязательность |
|---|---|---|
| `id` | UUID | Почти все агрегатные корни |
| `tenant_id` | UUID | Все tenant-owned бизнес-строки, включая файлы, заказы и PII |
| `region_zone_id` | VARCHAR(2) | Только реально гео-привязанные локации/операции, при наличии известной территории |
| `kato_code` | VARCHAR(16) | Ссылка на справочник КАТО; не ключ шифрования |
| `location_id` | UUID | Если операция привязана к филиалу/складу/торговой точке |
| `created_at`, `updated_at` | TIMESTAMPTZ | При необходимости для mutable-агрегатов |
| `row_version` | BIGINT | Optimistic locking для изменяемых агрегатов |

**Важное:** `tenant_id != region_zone_id`. Не пытаться вычислять `tenant_id` из КАТО; одна организация может работать в нескольких областях. Код зоны (два символа КАТО) валидируется по полноценной версии справочника, не используется для аутентификации/криптографии.

На PostgreSQL критические tenant-owned таблицы имеют `UNIQUE(tenant_id,id)` или `PRIMARY KEY(tenant_id,id)`. Любые cross-domain связи проверяются по обеим колонкам в СУБД, когда целевые таблицы допускают tenant-scoped FK; отсутствие FK компенсировать только документированным инвариантом и транзакционным тестом. Не разрешать FK по одному `id`, если это допускает cross-tenant reference.

## 5. Минимальный внутренний модульный контракт

Структура кодовой базы на один модуль:

```text
kz.rm.crm/
  domain/          Customer, Contact, Consent, policies, invariants
  application/     use cases, transactional orchestration, ports
  infrastructure/  Jpa/Jdbc repositories, crypto/file adapters
  api/             REST DTO/controllers (после согласования доменов)
```

Для маленьких модулей допускается не дробить пакеты по слоям до роста сложности; **важна граница модуля и владение таблицами**. Модуль не получает доступ к чужому `JpaRepository` напрямую: через узкий сервис/порт, API или domain event. Для первого релиза обычные синхронные Java вызовы в одной транзакции предпочтительнее событийной шины, если не требуется eventual consistency. Cyclic dependencies запрещены.

### Владелец данных / потоки

- IAM определяет `tenant_id`, membership и права.
- GEO владеет КАТО/территориями; ORG — филиалами, складами и торговыми точками как организациями-площадками; Inventory — количественными остатками в них.
- CRM владеет клиентом/контактом/согласием, а не ключом шифрования; хранение персональных полей через KMS.
- FILES владеет `file_id`, storage provider и приватным storage key. Другие модули хранят только `file_id`, не серверный путь и не RustFS URL. LOCAL → RustFS без изменения связей.
- CATALOG владеет актуальными товарами/ценами; SALES копирует price/item snapshot при подтверждении заказа или сметы.
- FINANCE владеет выставленными счетами/оплаченными суммами, не изменяет задним числом цену заказа.
- MARKETING перечитывает действующее `consent_email` перед каждой отправкой; создание контакта не означает согласие.
- AUDIT записывает только безопасные метаданные, не PII plaintext и не DEK.

## 6. Миграции Liquibase и правила переименования

- **Один логический модуль — свой каталог changelog**, например `src/main/resources/db/changelog/modules/crm/`, `modules/files/`, `modules/iam/`; общий master подключает их в определённом порядке: IAM/GEO → FILES/CRM → CATALOG → SALES/FINANCE → MARKETING/TASKS. Переход с существующего **единого formatted SQL master** выполнять отдельной задачей, не переносить/не править уже применённые changesets.
- Новая миграция: `<module>-NNNN-<description>`; автор и id changeset уникальны, rollback содержит обратные изменения где это безопасно. Добавление схемы/таблицы, FK/индексов — независимые изменения при потенциальных online-lock рисках.
- Переименование действующих таблиц — **отдельная техническая миграция**, выполняемая после анализа PostgreSQL зависимостей, JPA `@Table`, явных JDBC SQL, history, индексов, представлений, grantee, тестов, backup/rollback и миграции потребителей.
- Для больших таблиц рекомендуется **expand → migrate/backfill → switch readers/writers → contract**, а не разом `ALTER TABLE RENAME` с downtime без оценки зависимостей.
- Не менять UUID, tenant references, encrypted envelope AAD и KMS context при изменении физического имени таблицы. Физическое имя не должно попадать в AAD, если используем стабильный логический `entityType` и поле.
- Нельзя физически менять колонку `workspace_id` на `region_zone_id`. Если будет выбран `tenant_id` вместо `workspace_id`, UUID остаётся прежним; миграция отдельно квалифицируется.

### Registry текущих исключений (проверено в ветке)

| Существующее имя | Целевое (после отдельной миграции) | Примечание |
|---|---|---|
| `iam_user_account` | без изменений | уже соответствует IAM |
| `iam_workspace` | `iam_tenant` (проектное имя) | существующий workspace id = будущий tenant id |
| `iam_org_membership` | `iam_tenant_membership` или оставить | выбрать одну схему ролей перед миграцией |
| `org_organization`, `org_location` | без изменений | соответствует ORG |
| `ref_kato_territory` | `geo_kato_territory` | GEO-раздел |
| `crm.customer` | `crm.crm_customer` | новая бизнес-таблица в существующей schema |
| `crm.contact` | `crm.crm_contact` | customer/tenant FK |
| `crm.email_consent_event` | `crm.crm_email_consent_event` | журнал подтверждения/отзыва |
| `crm_pii.customer_secret` | `crm_pii.crm_customer_secret` | зашифрованное содержимое |
| `crm_pii.contact_secret` | `crm_pii.crm_contact_secret` | зашифрованное содержимое |
| `security.tenant_key` | оставить LEGACY | не вмешиваться в legacy KMS до отдельной квалификации |
| `security.kms_tenant_key` | без изменений | ключевой KMS-контур |
| `security.kms_tenant_key_archive` | без изменений | KMS archive |
| `files.file_object` и др. | не существует; проектные `crm.file_*` | файловый домен на стадии ТЗ |

**Миграционный приоритет:** сначала завершить текущую CI-квалификацию CRM-R1A, затем безопасное переименование/уточнение границ доменов; не превращать новую конвенцию в пакет ломающих изменений в текущем PR.

## 7. Правила ограничений / индексов / SQL

- `pk_<prefix><short_entity>`; `fk_<child>_<parent>`; `uk_<table>_<columns>`; `idx_<table>_<filter_columns>`; именовать в пределах PostgreSQL 63 bytes.
- Для tenant queries индексы начинаются с `tenant_id`, когда это соответствует селективности и доступу. Для геозапросов рассматривается `(tenant_id,region_zone_id,occurred_at)`; для истории клиента `(tenant_id,customer_id,occurred_at)`.
- Не делать обязательные HASH/LIST partitions на R1. Проверить `EXPLAIN (ANALYZE, BUFFERS)` на данных нужного объёма; при partitioning `PRIMARY KEY` и `UNIQUE` должны включать ключи partition.
- Всё шифрование PII — через tenant-scoped KMS, **не** `hash(Code)` и не `region_zone_id`.
- Подлинность и целостность файлов: метаданные в FILES, байты на сервере, позднее RustFS, потоковый SHA-256 и проверка владельца.

## 8. Приёмка ТЗ

1. Для каждой сущности существует **один** владеющий модуль, префикс соответствует реестру.
2. Новые DDL/JPA mapping names проходят H2 + PostgreSQL `ddl-auto=validate`, включая tenant composite FK и негативные межтенантские сценарии.
3. Domain boundaries проверяются правилами импорта (например, ArchUnit) и отсутствием циклических зависимостей.
4. Переименования legacy-таблиц не изменяют существующих ID, KMS decrypt/reload и внешние REST контракты без адаптера.
5. Персональные файлы и PII недоступны посторонним tenant/membership; создание и чтение с разрешённой стороны проходит end-to-end.
6. CRM/FILES/CATALOG/SALES/FINANCE проходят отдельные приёмочные сценарии и не требуют партиционирования для корректности.
7. Финансовые денежные суммы — `BigDecimal` + `currency`; пересчёт валют только по явной методике.

## 9. Приоритеты реализации

1. **RM-MOD-R1.0:** согласовать реестр модулей/префиксов, проектные имена и исключения.
2. **RM-MOD-R1.1:** после зелёной CRM CI — спланировать безопасную миграцию текущих таблиц, не менять старые changesets; интеграционные тесты и резервная копия.
3. **FILES-R1.0:** модуль `file_*` (metadata + local filesystem), чтобы CRM-R1A имела документы и CATALOG — фотографии.
4. **CRM-R1A:** `crm_*` + `crm_pii` encrypted, затем CATALOG `cat_*`, SALES `sales_*`, FINANCE `fin_*`, MARKETING/TASKS/AUDIT.
5. **RM-MOD-R1.2:** применить архитектурные тесты границ, измерить производительность индексов; партиции только после доказательной нагрузки.

**Решение пользователя для подтверждения:** единый реестр префиксов является стандартом для всех *новых* таблиц; существующие таблицы приводятся к нему отдельно, безопасной миграцией.
