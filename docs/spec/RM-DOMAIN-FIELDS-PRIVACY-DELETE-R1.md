# RM-DOMAIN-FIELDS-PRIVACY-DELETE-R1 — поля сущностей, шифрование, soft delete

**Статус:** PROPOSED, архитектурное заключение и ТЗ; физическая реализация не включена.
**Дата:** 2026-11-21.
**Связанные ТЗ:** [модули и префиксы](RM-MODULAR-DOMAIN-DB-R1.md), [FILES](RM-FILES-R1.md), [4 языка](RM-I18N-R1.md), [партиционирование](RM-PARTITIONING-R1.md).
**Принцип:** каждому модулю принадлежит его модель и таблицы с префиксом. Не делать единый универсальный набор полей, который механически добавляется во все таблицы.

## 1. Словарь столбцов

| Поле | Тип / назначение | Правило |
|---|---|---|
| `id` | UUID / стабильная идентичность | у aggregate root; у composite/business-key rows допустим иной PK |
| `tenant_id` | UUID / владелец и KMS boundary | обязательно в tenant-owned таблицах; не в общегосударственном GEO и глобальной IAM-идентичности |
| `region_zone_id` | VARCHAR(2) / регион КАТО | только для географически обоснованных сущностей; исторический snapshot для событий |
| `kato_code` | VARCHAR(16) / полный код | reference GEO; не идентичность владельца |
| `location_id` | UUID / филиал, склад, точка | при локальном размещении |
| `created_at` / `created_by` | TIMESTAMPTZ / UUID | запись происхождения, когда автор есть |
| `updated_at` / `updated_by` | TIMESTAMPTZ / UUID | только для изменяемых агрегатов |
| `row_version` | BIGINT | для конкурентно редактируемых агрегатов |
| `status` | VARCHAR+CHECK / state machine | бизнес-состояние, не код языка |
| `deleted_at` / `deleted_by` | TIMESTAMPTZ / UUID | только где soft delete допустим |
| `retention_until` | TIMESTAMPTZ | только при определённом retention/business/legal gate |
| `source_locale`, `locale` | VARCHAR(12) | allowlist `kk`, `ru`, `en`, `zh-Hans` |
| `occurred_at` / `recorded_at` | TIMESTAMPTZ | immutable business events; двусторонние даты только если бизнес обоснован |
| `currency` / `amount` | CHAR(3) / NUMERIC(19,4) | финансы, без строковой локализации |
| `pii_*_envelope` | BYTEA / authenticated ciphertext | изолированная `crm_pii` схема, owner scoped через `tenant_id` |
| `*_blind_index` | BYTEA | keyed HMAC отдельным поисковым ключом, никогда plain SHA |

Для tenant-owned tables минимум `PRIMARY KEY(tenant_id,id)` либо уникальность `(tenant_id,id)`, tenant-scoped composite FK. При partitioned parent все partition keys входят в unique/PK. Default-фильтры `deleted_at IS NULL` не должны использоваться для архивных/финансовых отчётов без явной бизнес-семантики.

## 2. Матрица полей доменов и чувствительности

| Модуль/агрегат | Основные бизнес-поля | Шифровать (при наличии PII или секретов) | Удаление |
|---|---|---|---|
| IAM / UserAccount | `id,oidc_subject,status,preferred_locale` | email/phone/MFA secrets/recovery tokens, если управляются RM; пароли — только проверенные password hashes, не обратимое шифрование | disable/anonymize по политике; не удалять глобальную идентичность, пока есть ссылки |
| IAM / TenantMembership | `tenant_id,user_id,role,status,valid_from,revoked_at` | обычно нет | revoke/close, сохранять audit |
| ORG / Organization | `id,legal_name,bin,registration_country,status` | персональные данные представителей, доверенные документы, конфиденциальные сведения по классификации; БИН юридического лица не обязательно PII | archive, без потери связей |
| ORG / Location | `tenant_id,id,region_zone_id,kato_code,address_line,status` | частный домашний адрес/точные персональные указания; публичный адрес филиала не обязательно PII | archive, а операции сохраняют snapshot |
| GEO / KatoTerritory | `code,parent_source_id,level_no,name_kk,name_ru,name_en` | нет | deprecate, never purge referenced code |
| FILES / FileObject | `tenant_id,id,original_name,content_type,size_bytes,sha256,state` | личные сведения в имени, приватный SHA/классифицированные метаданные; сами PII bytes — KMS-backed encryption at rest | state `DELETING`→wipe→`DELETED` после retention/legal hold |
| FILES / Storage | `tenant_id,file_id,provider,storage_key,object_version` | storage access credentials — только secret manager; приватный storage key не публичен | очистка provider после безопасного удаления |
| CRM / Customer | `tenant_id,id,type,region_zone_id,status` | ФИО физлица, ИИН, персональный адрес, заметки, персональные финансовые сведения | archive, отдельный privacy erase |
| CRM / Contact | `tenant_id,id,customer_id,status,is_primary` | ФИО, телефон, email, личный адрес, заметки | archive, revocation/erasure отдельно |
| CRM / ConsentEvent | `tenant_id,id,contact_id,purpose,granted,policy_version,occurred_at` | не хранить plaintext PII в source/evidence; защищать при необходимости ссылки на доказательства | append-only; retention/minimization, отзыв = новое событие |
| CATALOG / Item & Price | `tenant_id,id,sku,category_id,unit,status,amount,currency,effective_at` | обычно нет; непубличные коммерческие условия — access control, при явной классификации поле encrypt | deactivate; цены/ревизии snapshot не переписывать |
| SALES / Order & Estimate | `tenant_id,id,customer_id,region_zone_id,status,amount,currency,revision` | персональные сведения покупателя, адрес доставки физлица, приватные комментарии и вложения | draft soft delete; confirmed = cancel/void transition, keep historical snapshot |
| INVENTORY / Balance & Movement | `tenant_id,location_id,sku_id,quantity,occurred_at` | обычно нет; при PII в акте — секрет отдельно | balance correct by event; movement immutable, correction event |
| FINANCE / Invoice & Payment | `tenant_id,id,order_id,amount,currency,status,occurred_at` | ИИН/реквизиты физлица, адрес плательщика, чувствительные назначения платежей | drafts cancel; issued/posted: void/refund/adjustment with trace, retention |
| MARKETING / Campaign & Delivery | `tenant_id,id,template_revision,locale,status,occurred_at` | email/phone recipient, personalized message body, personal tracking tokens | drafts archive; send/unsubscribe events append-only and retained minimally |
| TASKS / Task & Activity | `tenant_id,id,assignee_id,customer_id,status,due_at` | свободный текст, приватные примечания/вложения, если содержат PII | task archive; activity immutable where audit-worthy |
| AUDIT / Event | `tenant_id,actor_id,action_code,entity_id,occurred_at,correlation_id` | нельзя сохранять plaintext PII, secrets, ciphertext dumps или auth tokens | append-only + controlled retention; redact metadata by design |
| KMS / TenantKey | `tenant_id,generation,status,wrapped_dek,created_at` | DEK always wrapped; root key never in DB/source | revoke/archive/destroy only after explicit recovery/legal check |

**Не шифровать по умолчанию:** UUID, регион, КАТО, технический статус, SKU, валюты, суммы, структурные foreign keys. Это не означает публичного доступа: остаются GRANT, IAM, tenant-isolation и аудирование.

**PII в свободном тексте:** классифицировать по источнику. Не копировать случайно ФИО, ИИН или email в `*_i18n`, логи, audit, outbox, отчётные агрегаты. Публичные названия организаций и адреса публичных магазинов не шифруются автоматически, но частная информация физлица — да.

## 3. Два способа хранения персональных данных

**A. В таблице метаданных:** `crm.crm_customer(tenant_id,id,type,status,region_zone_id,...)`.

**B. В защищённой таблице:** `crm_pii.crm_customer_secret(tenant_id,customer_id,name_envelope,iin_envelope,...)`, `crm_pii.crm_contact_secret(...)`; composite FK `(tenant_id,customer_id)`, отдельные DB GRANT и строго разрешённый decrypt через `TenantCryptoPort`.

Стабильный `CryptoContext(tenant_id,logicalEntityType,entityId,logicalFieldName)` должен совпадать при чтении и после миграции таблицы. Поля хранения и названия schema **не** являются уникальным KMS scope. Удаление PII не должно разрушать способность расшифровать остающиеся законные документы иных клиентов.

**Поиск:** email/phone/ИИН — отдельные `*_blind_index` с HMAC и tenant- scoped purpose-specific search key (отдельный от DEK), версия алгоритма/index key, канонизация телефона/E.164 или email по согласованной политике, сравнения constant-time при необходимости, rotation с dual-index или backfill; HMAC сам по себе раскрывает равенство и требует доступа/риск-анализа. Нельзя сохранять ИИН в обычном уникальном текстовом индексе. Частичный поиск телефонов/подстрок не открывать без отдельной схемы privacy search.

## 4. Soft delete: явная политика

### 4.1. Когда допустим

Mutable master/config/draft: CRM clients, contacts, locations, catalog items, tasks, unsent campaigns, drafts. `deleted_at NULL` значит активен в read model; `deleted_at NOT NULL` значит исключён из стандартных текущих выборок. Пара `deleted_at/deleted_by` предпочтительнее общего `is_deleted` — содержит время и субъект действия. Если у домена уже есть `status=ARCHIVED`, не добавлять второй равнозначный статус: **архивирование** и **логическое удаление** должны иметь разные правила, либо оставить один поддерживаемый механизм.

### 4.2. Когда недопустим

История согласий, принятые сметы, подтверждённые заказы, issued invoices, posted payments, складские движения, audit events, KMS generation — append-only/correction/void, не `deleted_at` вместо предметной операции. Активные ссылки на архивированного клиента могут оставаться допустимыми для истории; новая продажа архивному клиенту — явная бизнес-проверка.

### 4.3. Физическое удаление и privacy erasure

Soft delete **не равно удалению персональных данных** и не удовлетворяет сам по себе запрос на erasure. Использовать отдельный `privacy_erase` workflow с основаниями, сроками, legal hold, retention policy и audit outcome без PII. После истечения основания хранения: purging ciphertext/search indexes/file bytes/backups по документированной политике; crypto-erasure — только если ключ не разделяется с данными, которые необходимо сохранить. **Нельзя просто удалить общий tenant DEK ради одного клиента.**

Файловый домен `STAGED→AVAILABLE→DELETING→DELETED` и reconciliation физического хранилища. Для RustFS удаление учитывает versioning/object-lock/retention, чтобы не заявлять `DELETED` до фактической гарантии удаления.

### 4.4. Данные и индексы

- SQL по текущим сущностям должен последовательно фильтровать deleted/archived согласно use case; историческая отчётность использует snapshot и контролируемое чтение архивных ссылок.
- `UNIQUE(tenant_id,sku)` vs `UNIQUE(tenant_id,sku) WHERE deleted_at IS NULL`: осознанно выбрать бизнес-правило повторного использования кода. Для финансовых номеров, ИИН/идемпотентных ключей и immutable business IDs **никогда** не разрешать повторное использование просто из-за soft delete.
- PostgreSQL partial index `WHERE deleted_at IS NULL` применим только при соответствующей модели; на partitioned parent уникальность/partitions ограничивают дизайн. H2 smoke не заменяет PostgreSQL qualification.
- FK на soft-deleted записи физически остаются валидными; проверять бизнес-состояние в app service на новом назначении ссылок.
- Query cache, bulk update, JPA filters и native JDBC запросы должны использовать одинаковую visibility policy; использование `@SQLDelete` не обеспечивает безопасность всех запросов и не заменяет tenant authorization.
- Любое восстановление `restore()` транзакционно проверяет uniqueness и право актёра.

## 5. Четыре языка

UI `kk/ru/en/zh-Hans` переводит **представление**, а не ключи и PII. Справочники КАТО используют `name_kk/name_ru/name_en`; китайский только проверенный перевод либо явно маркированный fallback. Переводимые tenant-данные в `<module>_<entity>_i18n` с `locale VARCHAR(12)` и `PRIMARY KEY(tenant_id,entity_id,locale)`; не шифровать весь каталог автоматически. Юридические документы/согласия и клиентские файлы не переводить без утверждённой версии. Snapshots коммерческих документов включают фактический язык.

## 6. Приёмочные критерии

1. Contract test таблиц каждого модуля: корректные `tenant_id`, composite FK, обязательные nullable условия, префиксы.
2. Межтенантский UUID не позволяет читать/изменять/восстанавливать soft-deleted запись, PII или файл.
3. Person name/email/phone/IIN и приватные адреса не присутствуют plaintext в обычных таблицах, логах, audit, export и cache.
4. Authorized encrypted round-trip после restart/KMS reload/табличной миграции сохраняет AAD и tenant scope; чужой context не дешифрует.
5. CRM soft delete скрывает запись из активных списков, но не повреждает исторические заказы/сметы; restore проверяет права и уникальность.
6. Confirmed orders/posted payments/audit/consent history имеют корректирующие/void events, а не физическое или silent soft deletion.
7. Privacy erasure удаляет/анонимизирует PII с учётом retention/legal hold, включая search indexes и файлы; shared tenant DEK сохраняет доступ к остальным данным.
8. File lifecycle не оставляет доступных blob после гарантированного удаления; LOCAL/RustFS миграция не изменяет `file_id`.
9. `kk/ru/en/zh-Hans` не меняет сущность, ciphertext, tenant/region; переводы и Unicode проходят QA.
10. PostgreSQL partition/FK/partial unique/EXPLAIN/2-pod gates закрыты реальными интеграционными тестами, не одними H2 smoke.

## 7. Последовательность

**FIELDS-R1.0:** утвердить матрицу доменов и classification.
**FIELDS-R1.1:** выполнить Liquibase миграции существующих имен/новых полей после проверки текущего CRM CI, затем backend тесты.
**PRIVACY-R1.0:** KMS envelope для CRM/FILES, отдельные права к `crm_pii`, минимальное consent flow.
**DELETE-R1.0:** soft-delete для CRM/CATALOG/FILES/TASKS + запреты на immutable события; lifecycle tests.
**SEARCH-R1.0:** blind indexes только после threat model и тестов на нормализацию/утечки; baseline tenant/region indexes.
**QUAL-R1:** PostgreSQL/H2, negative cross-tenant, recovery/restore, rotation safety и load p95/p99.

Это **архитектурное ТЗ**, а не утверждение, что все описанные функции уже закодированы.


## 8. Уточнение R1 перед реализацией

Каноническая трактовка **soft delete** зафиксирована в [RM-IMPLEMENTATION-BASELINE-R1](RM-IMPLEMENTATION-BASELINE-R1.md): `ARCHIVED` — бизнес-состояние, `deleted_at` — только когда реально требуется отдельная корзина/восстановление. Для Customer/Contact/Location/Catalog/Task на R1 достаточно явной архивации и проверки видимости; не добавлять оба механизма без описанной разницы. Физические FILES таблицы целевого R1: `crm.file_object`, `crm.file_storage`, `crm.file_attachment`.
