# RM-IMPLEMENTATION-BASELINE-R1 — согласованный технический базис перед следующей реализацией

**Статус:** PROPOSED FOR APPROVAL (сводная нормативная спецификация для следующей очереди разработки).
**Дата:** 2026-11-21. **Ветка:** `feat/rm-kms-r1-implementation`; main не изменять.
**Стек:** Spring Boot 4 / Java 21 / JPA+JDBC / PostgreSQL / Liquibase; H2 smoke — вспомогательный.
**Режим:** modular monolith, один PostgreSQL; не вводить новые микросервисы, Redis или общее партиционирование до измерений.
**Связанные документы:** [домены/таблицы](RM-MODULAR-DOMAIN-DB-R1.md), [матрица полей](RM-DOMAIN-FIELDS-PRIVACY-DELETE-R1.md), [локализация](RM-I18N-R1.md), [файлы](RM-FILES-R1.md), [партиционирование](RM-PARTITIONING-R1.md), [доменная карта](RM-DOMAIN-MAP-R1.md).

## 0. Результат аудита, приоритет требований и граница готовности

В существующих документах отмечены следующие несогласованности, которые **разрешены здесь**:

1. **Термины владельца:** `workspace_id` — только legacy/physical; для новых бизнес-таблиц использовать `tenant_id UUID`. `iam_workspace.id` физически существует и пока является идентичностью tenant. **Не** подменять UUID двумя цифрами КАТО, KMS AAD не переписывать.
2. **Файловые схемы:** `files.file_*` в FILES-R1 — концептуальный вариант, а `crm.file_*` — другой. **Выбран один R1 вариант**: `crm.file_object`, `crm.file_storage`, `crm.file_attachment`, собственник модуля FILES по префиксу `file_`. Отдельная schema `files` и отдельная БД не нужны на старте.
3. **Soft delete vs ARCHIVED:** `status=ARCHIVED` — бизнес-архив; `deleted_at` — техническое логическое удаление, если отдельно необходима операция удаления/восстановления. **По умолчанию только один механизм** на агрегат; не добавлять `deleted_at` везде.
4. **I18N:** текущие старые заголовки «3 языка» устарели. Поддерживаем `kk,ru,en,zh-Hans`; canonical DB code `zh-Hans`, `locale VARCHAR(12)`. Romanization КАТО EN не объявлять официальным переводом.
5. **География:** в GEO для организации допускаются только поддерживаемые активные `Level 2/3` КАТО; `region_zone_id` определяется по проверенной КАТО зоне, но не фиксирует физическую близость в километрах. Полный справочник ещё не загружен — нельзя объявлять текущие 100 записей национальной полнотой.
6. **Partitioning:** проектная готовность обязательна у крупных таблиц; физическое разделение всех таблиц в R1 **не обязательно и не допускается без PostgreSQL-квалификации**.
7. **KMS:** текущая `security.kms_tenant_key` и legacy `security.tenant_key` не переименовываются в следующем CRM/FILES срезе, rotation остаётся выключенной.
8. **Соответствие реализации:** текущие SQL `crm.customer`, `crm.contact`, `crm_pii.customer_secret` и др. созданы в feature branch и имеют CI GREEN на проверенном изменении; **не соответствуют целевым префиксам**. Следующая задача — отдельная новая Liquibase миграция и синхронное изменение SQL/JPA/тестов. Уже применённые changesets не переписывать.

**Порядок норм:** эта baseline спецификация определяет именование, порядок внедрения и разрешение конфликтов R1; подробные domain-specific правила остаются в связанных ТЗ при отсутствии противоречия.

## 1. Принципы модулей

1. Одно бизнес-правило — один авторитетный модуль; никакой общей таблицы типа `entities` для всех доменов.
2. Java packages `kz.rm.iam`, `kz.rm.organization`, `kz.rm.geo`, `kz.rm.files`, `kz.rm.crm`, `kz.rm.catalog`, `kz.rm.sales`, `kz.rm.inventory`, `kz.rm.finance`, `kz.rm.marketing`, `kz.rm.tasks`, `kz.rm.audit`, `kz.rm.security.kms`. Пакеты не обязаны быть самостоятельными Maven-модулями.
3. Новая таблица всегда `<prefix>_<entity>` snake_case. Владение определяет префикс даже при общей PostgreSQL schema. Эталонные примеры: `crm.crm_customer`, `crm.cat_item`, `crm.sales_order`, `crm.file_object`.
4. Не переносить весь код на слои `domain/application/infrastructure/api` механически, если модуль мал; явные границы важнее формального количества папок. Запрет циклических зависимостей.
5. Организации и персональные контексты: один пользователь может иметь несколько memberships, данные всегда через стабильный `tenant_id`; платформенный аудитор не получает автоматического decrypt.
6. Internal service boundaries may pass typed identifiers, but REST adapter must derive actor/user from authenticated principal; never trust actor UUID supplied by client. Every lookup and action enforces tenant membership/permissions.

## 2. Registry таблиц и исходных полей (целевые новые имена)

В таблицах ниже подразумеваются `id` и даты только по необходимости. Все tenant-owned таблицы несут `tenant_id UUID` и `UNIQUE(tenant_id,id)` или `PRIMARY KEY(tenant_id,id)` там, где используется UUID root.

| Модуль | Таблица/агрегат | Основные бизнес-поля | PII / секреты | Удаление / история |
|---|---|---|---|---|
| IAM | `iam_user_account` | OIDC subject, `status`, `preferred_locale` | контактные персональные сведения отдельно, без plaintext | disable, retention policy |
| IAM | `iam_tenant` (позже rename `iam_workspace`) | kind, owner, status | нет | suspend/close |
| IAM | `iam_org_membership` (legacy retain R1) | organization_id,user_id,role,status | нет | revoke/leave |
| ORG | `org_organization` | legal name, BIN, kind,status | личные данные представителя отдельно | archive |
| ORG | `org_location` | tenant_id,kato_code,region_zone_id,type,address,status | домашний адрес только как PII | archive |
| GEO | `geo_kato_territory` (legacy `ref_kato_territory`) | code,parent_source_id,level,name_kk,name_ru,name_en,active | нет | deactivate obsolete |
| CRM | `crm.crm_customer` | tenant_id,type,status,region_zone_id,kato_code | имя физлица, ИИН, частные заметки — `crm_pii.crm_customer_secret` | archive; privacy erase отдельно |
| CRM | `crm.crm_contact` | tenant_id,customer_id,status,is_primary | ФИО,phone,email,частный адрес — `crm_pii.crm_contact_secret` | archive; erase отдельно |
| CRM | `crm.crm_email_consent_event` | tenant_id,contact_id,purpose,granted,policy_version,source,occurred_at | сырой IP/email не помещать в журнал | append-only, revocation event |
| CRM | `crm.crm_customer_event` (позже) | tenant_id,customer_id,event_type,occurred_at | санитизированные метаданные | immutable |
| FILES | `crm.file_object` | tenant_id,id,state,media_type,size_bytes,sha256,created_by | PII в original_name требует защиты; sensitive blob encrypt | staged→available→deleting→deleted |
| FILES | `crm.file_storage` | tenant_id,file_id,provider,storage_key,bucket,object_version | credentials отдельно в secret manager | migrate/cleanup |
| FILES | `crm.file_attachment` | tenant_id,file_id,owner_type,owner_id,purpose | не хранить PII в link labels | detach / restricted retention |
| CATALOG | `crm.cat_item`, `crm.cat_item_i18n` | tenant_id,sku,kind,status; locale,name,description | tenant commercial data access controlled; PII не разрешена | deactivate item, revisions preserve |
| SALES | `crm.sales_order`, `crm.sales_order_line` | tenant_id,customer_id,location_id,region_zone_id,currency,status,price_snapshot | адрес доставки физлица, персональные комментарии encrypt | draft delete; confirmed cancel/void |
| SALES | `crm.sales_estimate`, `crm.sales_estimate_revision` | tenant_id,customer_id,revision,currency,locale,snapshot | PII в контенте отделять | drafts archive, accepted revisions immutable |
| INVENTORY | `crm.inv_stock_balance`, `crm.inv_stock_movement` | tenant_id,location_id,sku_id,on_hand/reserved/quantity,occurred_at | нет обычно | movements immutable/correction |
| FINANCE | `crm.fin_invoice`, `crm.fin_payment`, `crm.fin_payment_allocation` | tenant_id,document_no,amount,currency,occurred_at,status | персональные платёжные реквизиты encrypt | issued/posted immutable/void |
| MARKETING | `crm.mkt_campaign`, `crm.mkt_template_i18n` | tenant_id,status,locale,revision,content | personalised body/recipient fields protected | draft archive; send events immutable |
| TASKS | `crm.task_item`, `crm.task_activity` | tenant_id,assignee,status,due_at,related_customer_id | sensitive free text encrypt | task archive, activity evidence retained |
| AUDIT | `crm.audit_event` | tenant_id,actor_id,entity_id,action_code,occurred_at | **не сохранять открытые PII, токены, DEK, dump** | append-only, retention |
| KMS | `security.kms_tenant_key` | tenant_id,generation,status,wrapped_dek | DEK wrapped, root вне БД | revoke/archive/destroy under gate |

Типы: UUID; TIMESTAMPTZ; NUMERIC(19,4) with currency CHAR(3); `row_version BIGINT` for mutable aggregate; `locale VARCHAR(12)`; `kato_code VARCHAR(16)`; `region_zone_id VARCHAR(2)`. File checksum storage may require access controls and tenant scoping. Do not put file content in PostgreSQL `BYTEA`.

## 3. Классификация полей / KMS / поиск

- **S0 — technical:** IDs, FK, KATO code, region, stable status, SKU, currency. Не шифровать, но применять RBAC/tenant restrictions.
- **S1 — business restricted:** price, cost, commercial terms, business activities. By default non-PII in `crm`, tenant-protected; encryption only if classification/contract requires.
- **S2 — personal:** person name, IIN, personal phone/email/address, sensitive free text, personalized campaign contents. Tenant-scoped authenticated encryption via `TenantCryptoPort`; envelope stored in `crm_pii` or protected file blobs.
- **S3 — credentials/keys:** DEKs and access secrets: wrapped/secret manager, never ordinary CRM table or logs.

Stable CryptoContext: `(tenant UUID, logicalEntityType, entity UUID, logicalFieldName)`; physical table renames do not change AAD. KMS rotation is disabled until separate distributed gate; no automatic retry with fresh tenant key on failed decrypt.

Blind search indexes (email/phone/IIN) require a separate versioned tenant/purpose keyed HMAC and normalization strategy (avoid raw SHA, plaintext LIKE). Do not implement before threat model; start with lookup by IDs and scoped transactional indexes.

Files containing PII require encryption-at-rest **before production uploads**; local filesystem mode must reject sensitive uploads unless encryption/scan/retention controls implemented. Folders are private and must not contain user-supplied paths. Multi-pod workloads require shared persistent filesystem or RustFS earlier.

## 4. Soft delete, archiving, immutable evidence, legal erasure

**Default rule:** `status` is a business lifecycle state (ACTIVE, ARCHIVED, SUSPENDED, CANCELLED), not a universal deletion flag. Add `deleted_at/deleted_by` only to entity types with **distinct** explicit trash/recover UX requirements. Avoid a universal `is_deleted` or `deleted_at` on every table. Immutable events generally use `occurred_at` and retention policies, not soft delete.

- Customer/contact, location/catalog/task: archive by business status; restore allowed after authorization and uniqueness checks. If UX later needs trash separate from archival, introduce `deleted_at/deleted_by` via change request and SQL migration.
- File: `STAGED→AVAILABLE→DELETING→DELETED`, clean storage after retention/legal hold and confirm deletion; `file_attachment` may be detached while preserving historical provenance.
- Orders/estimates: only drafts may be soft-deleted; confirmed orders and accepted revisions use cancel/void/correction records without rewriting signed commercial snapshots.
- Payment/invoice/inventory movement/consent/audit: append-only or explicitly corrective event; never silently delete after posting.
- Privacy erasure is separate from archive/soft delete: legal basis and retention assessment, removal/irreversible de-identification of contact ciphertext/search indices/files, controlled backup policy. Never destroy a tenant-wide DEK to erase one customer.
- Uniqueness policy per entity: `UNIQUE(tenant_id,sku)` if SKU never reused; partial unique with status/deleted requires explicit tenant contract and PostgreSQL tests. Invoice number and idempotency keys never reused just because an item is archived.

## 5. Four languages in every module

- Supported: `kk`, `ru`, `en`, `zh-Hans`; canonical code as BCP-47, `locale VARCHAR(12)`. Java resources `messages_kk.properties`, `messages_ru.properties`, `messages_en.properties`, `messages_zh_Hans.properties`.
- UI string resources separate from tenant-editable `cat_item_i18n`, `cat_category_i18n`, `mkt_template_i18n`. Translation rows `PRIMARY KEY(tenant_id,entity_id,locale)`; no language-specific partitions or cloned base tables.
- Language is preference (explicit request → saved user preference → Accept-Language → default ru), **not** inferred from region or tenant.
- For GEO retain `name_kk`, `name_ru` and Romanized `name_en` (NOT an officially translated EN name). Verified Chinese may be added later; fallback must report resolved locale.
- Legal contracts, consent, financial documents and marketing messages require approved language version; no hidden fallback to an unapproved translation.
- Locale never changes quantity, price, currency, order ID, tenant ID or KMS context; switching languages cannot grant decrypt permission.

## 6. Region and partitions: readiness vs actual deployment

- `region_zone_id`: first two characters of *validated* KATO, stored as snapshot for geographically assigned operations. Not present on global user, tenant, KMS or generic file metadata.
- Company has multiple locations in different regions; searching customer history without region must still return all authorized activity.
- **R1 baseline**: tenant/customer/date and tenant/region/date composite indexes in high traffic tables where there are corresponding query patterns.
- Partition candidate **master tables**: `HASH(tenant_id)` at scale (customer, contacts, file metadata only if needed); **event streams**: `RANGE(occurred_at)→HASH(tenant_id)` when retention/time queries dominate; region `LIST(region_zone_id)` as tested alternative for high-volume geographic facts.
- Not all combinations at once: `RANGE→LIST→HASH` only with demonstrated p95/p99 and manageable partition count. IAM, GEO, KMS, small catalogs and highly constrained balances do not require physical partitions in R1.
- On a partitioned parent all partition columns must occur in parent `PRIMARY KEY`/`UNIQUE`; this impacts FK and global identifiers. Design canonical stable IDs and validated registry if globally unique business number is needed.
- PostgreSQL native `EXPLAIN(ANALYZE,BUFFERS)`, pruning under JDBC parameters, FK integrity, maintenance, archive, 1m/5m/20m stress tests are gates. H2 cannot prove native partition semantics.

## 7. FILES domain and future RustFS

**Single R1 physical destination** `crm.file_object`, `crm.file_storage`, `crm.file_attachment`; files owned by FILES domain despite `crm` schema. No generic direct URL in other tables; `file_id` is stable UUID.
Binary storage currently proposed LOCAL (durable server mount) via `FileObjectStore`; future RUSTFS through adapter without changing links or API.
Owner authorization includes tenant and owning business object permission. Metadata state `STAGED/AVAILABLE/REJECTED/DELETING/DELETED`, streaming checksum, size/type checks, optional virus gate before availability, safe paths, fsync+atomic rename when possible, reconciliation on DB/FS failure, KMS encryption for sensitive payloads.
Migrate per-object with checksum verification at destination, atomic storage pointer switch with fencing/version, old bytes held through rollback window then purged. No claim of atomicity across PostgreSQL and disk.

## 8. Liquibase and staged compatibility

**Do not edit existing changesets** (`0001`–`0013` already present). Physical compatibility stage:
1. Inventory tables, explicit SQL, JPA annotations, grants, indexes, unique/FKs, test fixtures, backups.
2. Add **new** changeset for prefix rename of CRM/PII (if accepted), update JDBC/JPA references and test data **in same commit**; ensure source schema and table names qualified. No customer PII plaintext migration.
3. Keep `iam_workspace`, `iam_org_membership`, `ref_kato_territory`, and KMS physical tables stable in first slice; plan tenant/geo renames as independent changes, avoid bundling.
4. New FILES tables follow agreed `crm.file_*` convention. For FILES module catalog/migrations stay separately owned even though schema is shared.
5. Later expand-backfill-switch-contract migration for `workspace_id→tenant_id` where applicable. Never replace tenant UUID and never alter logical CryptoContext because of column names.
6. Qualify H2 + PostgreSQL schema/FK/tenant negative tests, decrypt roundtrip after migration/restart; migration must be repeatable across a clean DB and one with historical data.

## 9. Implementation roadmap with acceptance gates

| Order | Work package | Deliverable | Mandatory gate |
|---|---|---|---|
| 0 | BASELINE-R1 | This spec, conflict decisions, domain field inventory | Product architecture approval, no unresolved naming |
| 1 | MOD-R1.1 | CRM/PII table prefix migration only | Liquibase H2/PG and regression CI green, encrypted historical decrypt |
| 2 | IAM-AUTH-R1 | Authenticated principal → active member and permission checks for services | Tenant A/B negative, inactive member, viewer writes, auditor decrypt denial |
| 3 | FILES-R1.0 | `crm.file_*` metadata + LOCAL adapter, upload/download, file-state recovery | Restart+restore, traversal, oversized/malicious content denial, multi-tenant boundary |
| 4 | CRM-R1A | Customer/contact/consent lifecycle and domain fields, explicit archive, protected file attachment | Encrypted fields, KMS roundtrip, consent revoke, customer/doc access |
| 5 | I18N-R1.1 | Resources and stable errorCode + locale preference; CATALOG `cat_*_i18n` | Four locales incl. CJK, fallback tests |
| 6 | CATALOG-R1A.5 | SKU/category/price and product media, snapshot contract | Tenant uniqueness, effective pricing, file links |
| 7 | SALES-R1B/C | Orders/estimates revision snapshot, regional event model | Historical immutability, currency and tenant/location FK |
| 8 | FINANCE/INVENTORY/MARKETING/TASKS | Domain operations and event histories | Ledger/stock corrections, payment reconciliation, marketing consent gate |
| 9 | PERF-PART-R2 + FILES-R1.2 | Qualified partitions and RustFS migration | PG 1m/5m/20m, p95/p99, multi-pod backup/restore and blob integrity |

**No implementation of steps 1–9 is claimed by this document.** Previously committed experimental CRM-R1A classes/tests are a foundation to reconcile, not evidence of completion of the new field/lifecycle model.

## 10. Exit checklist before coding next slice

- [ ] Naming: owners/prefixes/target schemas approved; no ambiguous FILES schema or `workspace_id` substitution.
- [ ] Field schemas: tenant/locale/region/currency/PII types and nullability reviewed for every new entity.
- [ ] Security: IAM principal derivation, tenant membership, KMS AAD stability, PII grant and sensitive file encryption gate specified.
- [ ] Deletion: archive vs soft delete vs erasure documented by aggregate, historical safety ensured.
- [ ] Migration: existing changelog checksums preserved; new Liquibase changesets, backup/rollback and cross-version read tests designed.
- [ ] Four-language messages/documents and GEO Chinese fallback explicitly covered.
- [ ] Partition candidate matrix and key constraints understood; no unqualified native partitions.
- [ ] Acceptance tests defined: H2, PostgreSQL, integration, malicious upload, legal/consent, inter-tenant negative, deterministic CI.


## Публичная витрина, автозаказ и покупательский кабинет

**Нормативное дополнительное ТЗ:** [RM-STOREFRONT-AUTOORDER-R1](RM-STOREFRONT-AUTOORDER-R1.md). Новый модуль STOREFRONT с префиксом `store_` и целевыми таблицами `crm.store_front`, `crm.store_setting`, `crm.store_front_i18n`. Автозаказ **всегда** требует email, при отсутствии аккаунта IAM автоматически создаёт `PENDING_VERIFICATION` пользователя. При существующем аккаунте нельзя делать login или связывать доступ к чужим заказам на основе одного введённого email: требуется подтверждение владения адресом и аутентификация. `tenant_id` всегда продавца, `buyer_user_id` — глобальная IAM identity; покупатель не получает `ORG_MEMBER` продавца. Заказы остаются в SALES, связь покупателя с seller CRM — `crm.crm_buyer_link`; новый код не создавался. Четыре языка, FILES, tenant security, consent и исторические статусы обязательны.


## Domain foundation coverage (2026-11-21)

All 15 named contexts now have a canonical module registry, Java source package and initial typed contract or pre-existing implementation. **This is not a claim that all tables/controllers/business operations are implemented.** Current coverage, prefix mappings and follow-on vertical-slice gates are maintained in [RM-DOMAIN-IMPLEMENTATION-PLAN-R1](RM-DOMAIN-IMPLEMENTATION-PLAN-R1.md). The `kz.rm.architecture.DomainModule` registry validates prefixes and package uniqueness. Existing IAM, KMS, GEO, CRM and preliminary AUTOORDER persistence are preserved; missing domain persistence is introduced in independent Liquibase changesets with PostgreSQL integration tests.


## QUAL-RM-DOMAIN-R1.2 — единая IAM email-идентичность

Зафиксирована кодовая граница: глобальные `EmailIdentityCrypto`, `EmailIdentityService` (email find-or-provision, one-time verification, durable outbox) принадлежат IAM, а `AutoOrderService` координирует seller-owned order и buyer claim, **не является владельцем email account**. Существующие legacy plaintext email, полноценный OIDC и SMTP worker ещё блокируют публичный admission. Состояние реализации, migration gates и acceptance: [QUAL-RM-DOMAIN-R1.2-IAM-IDENTITY-STANDARD](../../docs/implementation/QUAL-RM-DOMAIN-R1.2-IAM-IDENTITY-STANDARD.md). Изменение не создаёт публичные endpoints и не влияет на optional CRM-card rule.


## All-domain standardization / R1 persistence baseline

[RM-ALL-DOMAINS-STANDARDIZATION-R1](../implementation/RM-ALL-DOMAINS-STANDARDIZATION-R1.md) фиксирует внедрение общей политики `DomainStandards`, списка локалей `SupportedLocale` и изменений Liquibase **0017** с базовыми таблицами восьми ранее пустых доменов. При наличии domain contracts + базовых таблиц **нельзя** маркировать бизнес-модуль production-ready: отдельные service/API/authorization/retention/PII E2E и 2-pod qualification обязательны. Legacy changesets 0001–0016 не переписывались, `main` остаётся закрытой до последующих gates.
