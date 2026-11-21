# RM-PARTITIONING-R1 — максимальная готовность доменов к партиционированию PostgreSQL

**Статус:** PROPOSED — техническое ТЗ; физических партиций в текущей ветке не создано.
**Ветка:** `feat/rm-kms-r1-implementation`
**Доменный стандарт:** [RM-MODULAR-DOMAIN-DB-R1](RM-MODULAR-DOMAIN-DB-R1.md).
**Ключевая цель:** использовать партиционирование максимально широко **там, где оно повышает производительность, изоляцию обслуживания, масштабируемость и скорость удаления/архивации**, без лавинообразного роста партиций и разрушения уникальности / FK.

## 1. Нормативные правила

- Модульный префикс обязателен: `crm_*`, `sales_*`, `inv_*`, `fin_*`, `file_*`, `mkt_*`, `audit_*`, `kms_*`.
- `tenant_id UUID` — неизменный владелец данных и KMS boundary. `region_zone_id VARCHAR(2)` — региональная зона из проверенного КАТО. Эти поля **никогда не эквивалентны**.
- `occurred_at` или `created_at` — бизнес/системная дата, явно задокументированная в каждом домене. Для неизменяемых финансовых и складских событий использовать бизнес-время `occurred_at` плюс `recorded_at` при необходимости, не подменять его датой БД.
- **Максимальное партиционирование — по семействам нагруженных данных, а не по каждой таблице.** Малые lookup, IAM, GEO, планы подписок, каталоги категорий, мастер-данные и KMS не партиционировать по умолчанию. Справочники хорошо работают с уникальными индексами и кешем.
- На старте никаких партиций на одну организацию, филиал или код КАТО: это вызывает тысячи физических таблиц, тяжелые DDL, pg_catalog и maintenance. Вместо этого ограниченное число `HASH(tenant_id)` buckets.
- Рекомендовать физический тип партиционирования только после сравнения с оптимизированной обычной таблицей под реалистичной нагрузкой.

## 2. Матрица доменов и схем

| Домен / таблицы (целевые имена) | Приоритет | Первичный кандидат | Когда и для чего |
|---|---|---|---|
| IAM: `iam_user_account`, `iam_tenant`, `iam_tenant_membership` | Не партиционировать R1 | PK/unique/index | Учётные записи и членство; высокая связность |
| GEO: `geo_kato_territory`; ORG: `org_organization`, `org_location` | Не партиционировать R1 | PK + индекс `tenant_id,region_zone_id` для локаций | Малые справочники и организации |
| FILES: `file_object`, `file_storage` | По метрикам, после FILES-R1.0 | `HASH(tenant_id)` при крупном реестре | Файловые метаданные; **сами байты в Local/RustFS, не PG** |
| FILES: `file_access_event`, `file_migration_event` | Высокий при большом трафике | `RANGE(occurred_at)` → `HASH(tenant_id)` | События скачиваний и миграций, TTL |
| CRM: `crm_customer`, `crm_contact`, PII `crm_*_secret` | Сначала индексы; затем HASH при росте | `HASH(tenant_id)` | Контактные карточки, быстрый customer lookup |
| CRM: `crm_customer_event`, `crm_communication_event` | Высокий | `RANGE(occurred_at)` → `HASH(tenant_id)` | История клиентов, ротация/архив периодов |
| CATALOG: `cat_category`, `cat_item`, `cat_variant`, `cat_price` | Обычно не партиционировать R1 | `tenant_id` + SKU, category / effective date индексы | Уникальность SKU в организации и поиск цен |
| SALES: `sales_order`, `sales_order_line` | По объёму и характеру запросов | `HASH(tenant_id)`; для региональных фактов см. ниже | История заказов, быстрый tenant/customer lookup |
| SALES: `sales_order_event`, `sales_estimate_revision_event` | Высокий | `RANGE(occurred_at)` → `HASH(tenant_id)` | Большая история и аудит смены статусов |
| INVENTORY: `inv_stock_balance`, `inv_stock_reservation` | Сначала без partitioning | `(tenant_id,location_id,variant_id)` PK/unique | Строгое конкурентное резервирование |
| INVENTORY: `inv_stock_movement` | Высокий | `RANGE(occurred_at)` → `HASH(tenant_id)` | Большие потоки движения и архив |
| FINANCE: `fin_invoice`, `fin_payment`, `fin_payment_allocation` | Сначала без partitioning | Индексы по tenant, контрагенту, периоду | ACID расчёты, глобальная внутри tenant идемпотентность |
| FINANCE: `fin_ledger_event`, `fin_payment_event` | Высокий | `RANGE(occurred_at)` → `HASH(tenant_id)` | Записи бухгалтерского следа / history, retention |
| MARKETING: `mkt_campaign`, `mkt_segment` | Не партиционировать R1 | PK + tenant index | Конфигурация и сегменты |
| MARKETING: `mkt_recipient`, `mkt_delivery_event` | Высокий при массовых рассылках | `HASH(tenant_id)`, для событий — `RANGE(occurred_at)` → `HASH(tenant_id)` | Массовые отправки/доставка, TTL |
| TASKS: `task_item` | Сначала без partitioning | `tenant_id,assignee_id,due_at` | Оперативные задачи |
| AUDIT: `audit_event`, KMS журнал операций | Высокий | `RANGE(occurred_at)` → `HASH(tenant_id)` (если поле применимо) | Архив, retention, контроль доказательств |
| KMS: `kms_tenant_key`, `kms_tenant_key_archive` | **Не партиционировать R1** | существующие PK/constraints | Сохранение механизма KMS/fencing |

**Пояснение:** `RANGE → HASH` — потенциально две ступени, **не три по умолчанию**. Дополнительная `LIST(region_zone_id)` допустима только на регионально-ориентированных таблицах с существенными региональными запросами, при оправданной сложности.

## 3. Региональное партиционирование и поиск клиента

На операции фиксируются `tenant_id`, `region_zone_id`, `kato_code`, `location_id`, `customer_id`, `occurred_at` при наличии реальной территориальной привязки. `region_zone_id` — **снимок региона места проведения операции**, а не обязательно адрес клиента или регистрации организации.

### 3.1. Альтернативы для `sales_order` / `inv_stock_movement`

- **A (базовая):** обычная таблица и индексы `(tenant_id,customer_id,occurred_at DESC)`, `(tenant_id,region_zone_id,occurred_at DESC)`.
- **B:** `HASH(tenant_id)` (например 8/16 bucket **как тестовая конфигурация**, не утвержденное число).
- **C:** `LIST(region_zone_id)` → `HASH(tenant_id)`, если запросы почти всегда ограничены регионом и известен стабильный справочник регионов.
- **D:** `RANGE(occurred_at)` → `HASH(tenant_id)`, если преобладают история, retention и временные интервалы.
- **E:** `RANGE(occurred_at)` → `LIST(region_zone_id)` → `HASH(tenant_id)` только для очень крупных event/fact таблиц после доказательного преимущества и контроля числа физических партиций.

Региональные партиции **не означают поиск по расстоянию**. Для реального радиуса поиска требуются координаты локаций и пространственный индекс PostGIS. Для клиента, действующего в нескольких регионах, поиск полной истории без region должен оставаться быстрым — обязательно сравнить с A/B.

### 3.2. Управление регионами

- Выделить специальное состояние `UNASSIGNED`/DEFAULT или nullable region для реально негеографических операций. Нельзя произвольно назначать регион клиенту.
- Коды областей сверяются по полной официальной версии КАТО; первые две цифры — только предложенная модель зон, с валидацией и сохранением исходного полного `kato_code`.
- Изменение региона локации **не переносит** историческое событие между партициями: event фиксирует исторический region snapshot. Новые операции используют актуальное размещение.
- Логику региональных таблиц не переносить на KMS или identity.

## 4. PostgreSQL DDL/integrity — обязательные ограничения

1. PostgreSQL UNIQUE/PRIMARY KEY на partitioned parent должен включать **все столбцы ключа партиционирования**, в том числе вложенные partition keys. Например при `HASH(tenant_id)` — `PRIMARY KEY (tenant_id,id)`; при `RANGE(occurred_at)` → `HASH(tenant_id)` — `PRIMARY KEY (occurred_at,tenant_id,id)`.
2. Это осложняет FK и идентичность сущностей: для событий использовать ссылку по `(tenant_id,occurred_at,id)` или отдельный **unpartitioned identity registry**, если нужен стабильный lookup только по `id`. Не объявлять `UUID id UNIQUE` на родительской partitioned таблице без нужных partition keys.
3. При `LIST(region_zone_id)` все необходимые уникальные ограничения включают регион. Если нужна уникальность SKU/номера счёта **в пределах tenant независимо от региона**, держать её в отдельной **непартиционированной таблице-регистре** или не партиционировать соответствующий агрегат по региону.
4. Композитные FK должны включать `tenant_id` и, если необходимо, `region_zone_id`/`occurred_at`. Запрет cross-tenant link обязателен на уровне БД для FK, поддерживающих tenant identity.
5. Для `NULL` partition keys предусмотреть `DEFAULT` partition или явный sentinel, не выводить регион из случайной строки.
6. PostgreSQL partition pruning проверять через `EXPLAIN (ANALYZE, BUFFERS)`, включая prepared statements и реальные JDBC bind parameters. `ORDER BY` и агрегации по всем регионам должны замеряться отдельно.
7. Для каждой parent и child миграции Liquibase должны быть детерминированы и повторно применимы. PostgreSQL-specific partition DDL не считать прошедшим приёмку только по H2 smoke; H2 использует непартиционированный тестовый эквивалент.
8. Индексы и `VACUUM`/ANALYZE, `autovacuum`, retention, backup/restore, attach/detach, lock behavior и tenant isolation проверяются на стенде, включая конкуренцию нескольких pods.
9. Нельзя автоматически создавать новую физическую партицию из транзакции запроса. Динамический `CREATE TABLE` per tenant/region запрещён; child partitions управляются миграциями/административными задачами с проверками.

### Пример базовой partitioned event-таблицы (проектная иллюстрация, НЕ миграция)

```sql
CREATE TABLE crm.crm_customer_event (
  tenant_id UUID NOT NULL,
  id UUID NOT NULL,
  customer_id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  region_zone_id VARCHAR(2),
  event_type VARCHAR(40) NOT NULL,
  PRIMARY KEY (tenant_id, occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE crm.crm_customer_event_2027_01
PARTITION OF crm.crm_customer_event
FOR VALUES FROM ('2027-01-01') TO ('2027-02-01')
PARTITION BY HASH (tenant_id);

CREATE TABLE crm.crm_customer_event_2027_01_h00
PARTITION OF crm.crm_customer_event_2027_01
FOR VALUES WITH (MODULUS 8, REMAINDER 0);
-- Остальные 7 hash children создаются заранее версионированной миграцией.
```

Пример показывает, что `PRIMARY KEY` включает оба partition key (`occurred_at` и `tenant_id`); на родительской таблице нет глобального `UNIQUE(id)`. Реальный период и число buckets выбираются по нагрузочному тесту.

## 5. Защищённый контур CRM_PII и файловый домен

- `crm_pii.crm_contact_secret`, `crm_pii.crm_customer_secret` используют `tenant_id UUID` как KMS/AAD контекст. Шифрованные envelopes и логическая идентичность не меняются при перемещении строки между партициями. `region_zone_id` не используется в криптографических ключах и часто не применим для PII.
- На первом этапе достаточно обычной таблицы с `PRIMARY KEY(tenant_id,contact_id)`; будущая `HASH(tenant_id)` — только с сохранением FK и blob retrieval E2E.
- `file_object` и `file_storage` содержат метаданные/индекс, **содержимое лежит в серверном storage**, позже RustFS. Не пытаться ускорять скачивание бинарных файлов PostgreSQL-партициями. Ускорять file lookup индексом по `(tenant_id,id)`.
- Любой локальный disk-vs-PostgreSQL inconsistency должен иметь reconciler; на многоподовой конфигурации нужен общий persistent volume или более ранний RustFS.

## 6. План квалификации `RM-PART-QUAL-R1`

| Этап | Что проверяется | Критерий |
|---|---|---|
| P0 domain inventory | Все таблицы размечены `UNPARTITIONED / HASH / RANGE / REGION` | Нет таблиц без архитектурного решения; KMS/IAM без разрушительных миграций |
| P1 baseline | Native PostgreSQL индексы, `EXPLAIN`, планы JDBC, p50/p95/p99 | Измерен контрольный эталон без partitions |
| P2 HASH | `HASH(tenant_id)` на staging-копии крупных таблиц | Нет потерь, FK/unique корректны, p95/p99 сравнимы |
| P3 RANGE | Месячные/квартальные `RANGE` для immutable events | Retention, сканирование интервала, archive/recovery проверены |
| P4 REGION | `LIST(region_zone_id)`, при необходимости HASH внутри | Региональный pruning и полная история multi-region клиента |
| P5 multimodal | Совместный `RANGE→HASH` / региональные альтернативы | Выбран победитель по workload, а не максимальному количеству child tables |
| P6 failure | Конкурентные insert/update, crashed pod, duplicate idempotency keys, restore | ACID, tenant isolation, accuracy, idempotence unchanged |
| P7 acceptance | Review статей, индексов, storage cost, сложность ops | Partitioned решение получает явное разрешение перед production |

**Нагрузочные наборы:** 1 млн / 5 млн / 20 млн операций плюс перекос tenants (один tenant с 50% строк), multi-region клиенты, регион без событий, несколько сотен tenants, 1/2/5 pods. Сравнить объём индексов, диск, скорость миграций, p95/p99, aggregate by period, tenant-customer history, regional search, cross-region history, inserts, deletes, VACUUM.

**Порог принятия:** решение о партициях принимается при устойчивой и воспроизводимой пользе на приоритетных запросах и приемлемых затратах эксплуатации; произвольных обещаний «в N раз быстрее» нет. Формальный production gate блокирован до PostgreSQL-результатов.

## 7. Этапность без переусложнения

- **Сейчас:** проектировать большие таблицы с правильно названными `tenant_id`, `region_zone_id`, `occurred_at` и ключами; в R1 использовать обычные индексы и функциональные тесты.
- **После базового CRUD CRM/CATALOG/SALES:** проверить крупные event-таблицы в staging, включая REGION vs HASH для customer history.
- **Позже:** создавать физические партиции **только** для победивших кандидатов и управлять ими Liquibase/DBA. Не партиционировать все домены ради самого факта партиционирования.

**Важно:** слова «максимальное партиционирование» интерпретируются как **максимальная практическая масштабируемость с предсказуемой сложностью**, а не физическая разбивка любой таблицы.


## 8. Базовый порядок внедрения

[RM-IMPLEMENTATION-BASELINE-R1](RM-IMPLEMENTATION-BASELINE-R1.md) — единый порядок реализации до принятия решения о физическом партиционировании. В R1 проектируем partition-safe идентификаторы и временные/географические поля, используем индексы; нативные партиции запускаем только после PostgreSQL квалификации. Для FILES на R1 целевые таблицы `crm.file_*`, отдельная PostgreSQL схема `files` не создаётся.


## 9. Implemented qualification candidate: PostgreSQL audit RANGE → HASH

**Updated 2026-11-21.** Previous sections describe selection and constraints; the earlier statement that no physical partitions exist is now **outdated for the feature branch**. PostgreSQL-specific Liquibase changeset `rm:0018-audit-range-hash-postgresql` converts `crm.audit_event` into annual `RANGE(occurred_at)` parents for 2025, 2026, 2027 and DEFAULT, each with four `HASH(tenant_id)` leaf partitions. H2 remains unpartitioned. `rm:0019-partition-aware-query-indexes` supplies composite indexes to nonpartitioned domains without changing order, invoice, SKU or KMS uniqueness.

The existing audit key becomes `PRIMARY KEY(tenant_id,occurred_at,id)`; existing references by `(tenant_id,id)` alone **must not be introduced**. This is a branch-level physical prototype with automated PostgreSQL integration tests, **not** a production partitioning approval. Before deploying to an existing tenant database, prove there are no inbound audit foreign keys, verify production-scale migration time, counts, backups, PITR and partition-pruning benefit. A new date range overlapping DEFAULT requires an explicit data maintenance plan.

See [RM-LIQUIBASE-PARTITIONING-R1](../implementation/RM-LIQUIBASE-PARTITIONING-R1.md) for the actual SQL migration, test matrix and release HOLD.
