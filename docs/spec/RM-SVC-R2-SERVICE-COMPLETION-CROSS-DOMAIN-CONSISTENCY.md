# RM-SVC-R2 — Завершение бизнес-сервисов и междоменные инварианты

**Тип:** техническое задание / архитектурный контракт  
**Статус:** PROPOSED — к поэтапной реализации и приёмке, не объявляет функциональность реализованной  
**Репозиторий:** `alimbetov/RM` · **ветка:** `base_dev`  
**База обследования:** HEAD `d13dbdf165c019a737e7cfcb19b23b04e68bfae1`, 21.11.2026  
**Основание:** предоставленное ревью «RM Platform — техническое ревью сервисов и полноты CRUD», 21.11.2026. Приоритеты ниже уточнены проверкой сервисных контрактов на указанном HEAD.

**Предреализационное уточнение CRM/CATALOG:** [RM-SVC-R2B — Domain Decisions](RM-SVC-R2B-PREIMPLEMENTATION-DOMAIN-DECISIONS.md). При разночтениях по категориям, договорам, effective expiry и modelCode использовать R2B как уточнение к R2/R2A; это предложения к утверждению, не реализованные методы.

## 1. Решение и границы

RM сохраняет **модульный монолит**, существующие bounded contexts, PostgreSQL как источник истины для бизнес-состояния, Java/Spring Boot, JPA/JDBC по существующей архитектуре. В рамках RM-SVC-R2 **не создавать** новый микросервис FILES, общий generic CRUD engine, контроллеры, новый `SecurityConfig`, отдельный IAM, новую модель хранения объектов или массовый переписанный repository layer.

Цель — превратить доступные операции `create/read/transition` в полные **предметные жизненные циклы** `create → get/list → update → archive/deactivate → restore/reactivate` там, где операции допустимы; для SALES, INVENTORY, FINANCE и AUDIT сохраняются **команды и неизменяемая история**, а не универсальный SQL `DELETE`.

**Текущая политика FILES:** бинарные байты **без прикладного шифрования** по решению владельца продукта. Backend `LOCAL` по умолчанию, `RustFS` для новых загрузок по `RM_FILES_RUSTFS_ENABLED=true`; сохранённый provider/key определяет место чтения независимо от текущего флага. **Не изменять существующее шифрование CRM/IAM PII-полей.** Не объявлять возможным production-хранение конфиденциальных файлов без отдельного privacy/security допуска.

**Release gates:** `base_dev` only; `main` merge / production / новые HTTP endpoints — HOLD до отдельного решения. Применять принцип no controller before verified principal, реальные S2/security gates отдельно от технического GREEN.

## 2. Подтверждённый baseline и степень достоверности

| Контур | Подтверждено кодом | Пробел для R2 |
|---|---|---|
| IAM | `VerifiedActor`, `TenantAuthorization`, роли/членство, tenant permissions | Единый authenticated facade для всех новых команд; external OIDC/BFF qualification отдельно |
| CRM | `CrmCustomerService`: create PERSON/COMPANY, get, add/get contact, consent; `AuthenticatedCrmCommands` для части команд | Update, primary contact, archive/restore, revision/history |
| ORGANIZATION | `OrganizationLocationService.create`; `OrganizationLocationQueryService.get/search` | Изменение и state transitions локации, зависимости склада/заказов |
| FILES | staged → scanned → AVAILABLE, LOCAL/RustFS, `FileAttachmentService` для активного `CRM_CUSTOMER/COMPANY`, detach/list/read, reclaim | Полная медиатека, безопасное delete, versioned metadata, race-free quarantine; PERSON остается закрыт до объектной политики |
| SALES | Имеются предметные transitions и `SalesOrderQueryService.search` | Редактируемый draft, детальное get/history, частичное исполнение |
| INVENTORY | Движения, резервы, чтение остатков и настройки с `expectedVersion` | Transfer, stocktake, reconciliation, expiry; не допускать CRUD над balance |
| CATALOG / FINANCE / MARKETING / TASKS / SUBSCRIPTION | По исходному ревью преобладают схема/модель и инфраструктура | Предметные сервисы после завершения P0, с проверкой фактической схемы перед работами |

**Примечание о статусе CI:** исходный документ отражал промежуточные FAIL 21.11.2026. На проверенном HEAD `d13dbdf...` оба workflow (`RM Domain Security and PostgreSQL Qualification`, `RustFS Real Infrastructure E2E`) завершились SUCCESS. Это **техническая квалификация существующего кода**, а не доказательство завершённого CRUD R2. Не переносить старые FAIL/HEAD в текущие утверждения без проверки SHA.

## 3. Единый стандарт сервисных контрактов

### 3.1 Входные параметры и доверие

- Каждый публичный **application facade** принимает `VerifiedActor actor`, `UUID tenantId` и типизированную команду/фильтр. Физический `actorId` нельзя получать из внешнего request DTO, query string или метаданных файла.
- Права проверяет владелец домена **перед раскрытием факта существования объекта и перед побочным действием**. Tenant ID обязательно участвует в каждом DB-lookup/update.
- Внутренние UUID-методы могут сохраниться как package-private после запрета прямого использования внешним транспортом; отдельный authenticated facade обязателен для расширяемых сервисов (в том числе Inventory Tenant Settings).
- Объектный доступ — **решение доменного владельца**, а не следствие одного общего tenant grant: CRM определяет возможность читать/изменять CRM customer, ORGANIZATION — location, SALES — order, CATALOG — item; FILES делегирует.
- `CRM_PII_READ` применяется там, где действительно раскрываются PII. Для PERSON/контактов сохраняется encrypted-field policy и fail-closed при отсутствии ключа.
- Возвращаемые DTO не содержат внутренние storage keys, ciphertext, secret envelope, credentials, неотфильтрованные поля, неподтверждённые персональные сведения.

### 3.2 Command semantics

- Все изменяемые агрегаты имеют `rowVersion`/`expectedVersion` либо эквивалентный PostgreSQL compare-and-swap; конфликт → явный `CONFLICT`, без потерянных изменений.
- Command DTO отделяет отсутствие поля от значения `null` (частичный PATCH только при явной семантике), валидирует размер, enum, нормализацию и запрещает смену tenant/ID/createdAt.
- Повторяемые бизнес-команды имеют `requestKey`, срок/состав idempotency и устойчивое возвращение того же результата; повтор **с другим payload** отклоняется.
- Событие об изменении фиксируется атомарно с изменением агрегата: через существующий audit/outbox подход; не использовать fire-and-forget между модулями.
- Изменения через жизненные состояния (`ACTIVE/ARCHIVED`, `ACTIVE/INACTIVE`, `DRAFT/CONFIRMED/...`) должны соответствовать матрице переходов; запрещены физическое удаление финансовой/складской/заказной истории и повторное подтверждение чужого состояния.
- Новая функциональность использует короткие PostgreSQL-транзакции, без DB lock во время S3 IO, ClamAV, файловых операций или внешнего вызова.

### 3.3 Read/search и пагинация

**Обязательная детализация:** [RM-SVC-R2A — Единый контракт фильтрации, сортировки и cursor/keyset pagination](RM-SVC-R2A-FILTERING-PAGINATION-CONTRACT.md). Этот документ является нормативным для новых списков и расширяет краткие требования ниже: bounded legacy OFFSET, per-domain typed filters, HMAC cursor, object-level visibility, лимиты и PostgreSQL EXPLAIN/конкурентную приёмку.



- `getById(tenant,id)`, `search(tenant,filter,limit,cursor)` либо уже используемая page/size схема (size 1..100). Сортировка детерминированна (`created_at,id` или явно разрешённые поля).
- Каждый список фильтруется tenant и объектными правами до возврата элементов. Удалённые/архивные записи не выдаются как активные; для исторического просмотра — отдельное разрешение.
- Все подробные read и history возвращают domain DTO, а не JPA entities или `Map<String,Object>` со случайными полями.

## 4. Матрица межсервисного владения и зависимостей

| Бизнес-объект / инвариант | Авторитетный владелец | Потребители | Правило согласованности |
|---|---|---|---|
| User/roles/tenant | IAM | Все | Только IAM валидирует VerifiedActor и effective permissions; глобальная блокировка действует на новые команды |
| Customer/Contact/PII/consent | CRM | FILES, SALES, MARKETING | CRM проверяет статус, тип, доступ; archived customer не принимает новые attachment/заказы; существующие исторические ссылки сохраняются |
| Organization Location | ORGANIZATION + GEO eligibility | INVENTORY, SALES | LOCATION нельзя deactivate при активных складах, открытых резервах и иных ссылках, пока не определена миграция/альтернатива |
| File Object и attachment relation | FILES | CRM, CATALOG, SALES | FILES хранит bytes/metadata; **владелец** разрешает attach/read/detach; FK-подобная проверка typed owner до фиксации |
| Catalog item / price revision | CATALOG | SALES, INVENTORY, FILES | Заказы сохраняют immutable snapshot идентификатора, цены, валюты, SKU/ревизии; archive запрещает новые заказы, но не ломает историю |
| Sales Order | SALES | INVENTORY, CRM, CATALOG | Продажа инициирует резерв/исполнение через существующий порт, с уникальным order/line/request id и транзакционным согласованием |
| Stock movement / reservation | INVENTORY | SALES, CATALOG | Остатки выводятся из квалифицированных движений/резервов; прямой UPDATE balance запрещён |
| File provider/key | FILES metadata | LOCAL/RustFS selector | Current flag влияет только на новые uploads, а существующий `provider/key` — на все read/delete/recovery; auto-migration отсутствует |
| Audit / retention | AUDIT | Все | Append-only evidence; физическая очистка не может опережать действующие ссылки и retention/legal hold |

**Запрет на междоменные “shortcut” INSERT/UPDATE:** сервис одного домена не изменяет внутренние таблицы другого напрямую, кроме существующего квалифицированного compatibility layer, который необходимо отдельно описать и постепенно заменить domain port. Не дублировать customer status, item status или inventory quantities в независимых таблицах без явного snapshot/version контракта.

## 5. Этап RM-SVC-R2.0 — Contract Alignment & Correctness (P0)

**Задача:** закрыть дефекты межсервисного согласования до массового CRUD.

### 5.1 FILES recovery quarantine race

Текущий `FileRecoveryService.reconcileOne` сначала читает `file_storage.storage_key`, выполняет IO проверку SHA вне транзакции, затем делает `UPDATE crm.file_object ... WHERE state='AVAILABLE' AND scan_status='CLEAN'`. В UPDATE **не закреплены** ранее проверенный storage pointer, его version и SHA, поэтому при конкурентном изменении ссылки можно ошибочно карантинировать уже исправленный файл.

**Требуется:**
1. Снять короткий DB snapshot: `tenantId,fileId,rowVersion,storageKey,sha256,sizeBytes,storageState/provider`.
2. После IO-верификации открыть новую короткую транзакцию и повторно проверить, что актуальные `file_object.row_version`, `file_storage.storage_key`, provider, hash, size, active state **точно равны snapshot**.
3. Только при совпадении делать CAS карантин и audit. При изменении pointer/version — `STALE_SNAPSHOT`/retry verification, **не карантин**.
4. Различать `404/missing`, `size/hash mismatch` и `timeout/503/auth issue`. Сетевые/авторизационные ошибки хранения **не являются доказательством порчи файла** и не должны менять AVAILABLE на REJECTED.
5. Не удалять orphan key при гонке со staging/promote/новым активным pointer; cleanup только после повторной DB-проверки и fencing.
6. PostgreSQL deterministic race test с барьерами: A проверил старый key, B заменил актуальный pointer, A пытается quarantine → файл B остаётся AVAILABLE; отдельные тесты реальных 404, 503, revoked actor и конкурирующих claim.

### 5.2 Service-level consistency review

- `AuthenticatedCrmCommands`, facade ORGANIZATION, FILES, SALES и INVENTORY — согласовать одну trust boundary; не мигрировать всё сразу.
- Переопределить permission matrix: `CRM_READ/WRITE/PII_READ`, `FILES_UPLOAD/READ/ATTACH/DETACH/DELETE` (проверить, какие codes реально существуют, прежде чем миграция), `ORGANIZATION_LOCATION_READ/WRITE`, права CATALOG/SALES/INVENTORY.
- Ввести тестовую матрицу отказов: foreign tenant, missing permission, global block, archived owner, stale row version, replay, competing commands.
- Консолидировать FILES ТЗ: убрать устаревшие требования обязательного binary AES-GCM, **не меняя** защиту PII в IAM/CRM; зафиксировать plaintext-принцип, условия хранения в инфраструктуре и production HOLD.

**Приёмка R2.0:** exact-head H2 + PostgreSQL + populated PII + live RustFS/ClamAV regression GREEN; новые файлы в одном tenant не становятся доступными чужому tenant при любой гонке; документ контрактов и разрешений обновлён.

## 6. Этап RM-SVC-R2.1 — CRM & ORGANIZATION CRUD Completion (P0)

### 6.1 CRM Customer

Методы application facade:
- `getCustomer(actor,tenant,customerId)`, `searchCustomers(actor,tenant,filter,limit,cursor)` с **категорией клиента** (`categoryCode`) и **индикатором окончания договора** (`contractExpiry`, daysRemaining, expiry/status/date filters). Категория tenant-scoped и независима от PERSON/COMPANY; договор — самостоятельный aggregate/read projection (один клиент может иметь несколько договоров), не колонка `crm.customer.end_date`. Полный нормативный контракт — [RM-SVC-R2A §§6.1–6.2](RM-SVC-R2A-FILTERING-PAGINATION-CONTRACT.md).
- `updateCustomer(actor,tenant,customerId,patch,expectedVersion,requestKey)`: разрешённые имя, KATO/region и утверждённые editable атрибуты, **без** смены `customer_type`, id/tenant и шифрованной модели PERSON.
- `archiveCustomer(actor,tenant,customerId,expectedVersion,reason,requestKey)`: запрещает новые active-only операции CRM/FILES/SALES; не удаляет записи, существующие attachment и историю.
- `restoreCustomer(actor,tenant,customerId,expectedVersion,requestKey)`: повторная проверка уникальности, GEO eligibility, прав и политики KMS/PII; terminally blocked legal states не восстанавливаются автоматически.
- `getCustomerHistory(actor,tenant,customerId,limit,cursor)` — append-only actor/time/action/diff по безопасным полям, без plaintext PII в audit.

**Инварианты:** NAME у PERSON хранится как encrypted envelope по текущему `TenantCryptoPort`; обновление обновляет envelope в одной транзакции с customer `rowVersion`. COMPANY name изменяется в разрешённом поле. Не создавать тайную копию PII в audit/search. Для archival при активном SALES contract определить до исполнения policy: **default deny**, с явным pending-work conflict; не “отцеплять” существующие заказы.

### 6.2 CRM Contact

- `updateContact(actor,tenant,contactId,patch,expectedVersion,requestKey)` — encrypted name/email/phone с валидаторами, re-encrypt в текущем tenant key.
- `setPrimaryContact(actor,tenant,customerId,contactId,expectedCustomerVersion,requestKey)` — **не более одного** ACTIVE PRIMARY контакта на одного клиента; требуется PostgreSQL partial unique index и транзакционное serializing по customer row.
- `archiveContact(...)`; `listContacts(actor,tenant,customerId,filter,page)`; `getContactHistory(...)`.
- Активный primary contact при архивировании должен быть сброшен или заменён **одной транзакцией**, без состояния “архивный контакт — основной”.
- Согласие на рассылку — **отдельное append-only evidence**, не перезаписывается при updateContact и не возникает автоматически при добавлении клиента.

### 6.3 ORGANIZATION Location

- `updateLocation(actor,tenant,locationId,patch,expectedVersion)`
- `deactivateLocation(actor,tenant,locationId,expectedVersion,reason)`
- `reactivateLocation(actor,tenant,locationId,expectedVersion)`
- `getLocationHistory(actor,tenant,locationId,...)`
- Перед деактивацией: доменные проверки использования складами, активными резервациями, draft/active заказами и fulfillment. **Если хотя бы один dependency adapter отсутствует — deny**, без произвольного прямого UPDATE ссылок.
- GEO KATO policy применяется при изменении и реактивации. Исторические заказы продолжают указывать на исходную локацию; deleteLocation физически запрещён.
- Обязательный `rowVersion`, tenant isolation, история и deterministic competing update.

**Приёмка R2.1:** полный positive lifecycle каждого типа customer и contact, alias/PII confidentiality, архив/восстановление, уникальность primary contact в PostgreSQL при двух потоках, valid/invalid KATO и связанная локация со складом/заказом, rollback при ошибке.

## 7. Этап RM-SVC-R2.2 — FILES Media Completion (P0)

### 7.1 Сервисные операции

- `getFileMetadata(actor,tenant,fileId)`: безопасный публичный DTO metadata без provider key.
- `listMedia(actor,tenant,ownerType,ownerId,filter,limit,cursor)`: owner authorization, MIME/class, state, sort order, pagination, captions.
- `updateFileMetadata(actor,tenant,fileId,displayName,description,expectedVersion)`: только metadata; `storageKey`, `sha256`, uploadedBy и content_class не меняются через patch.
- `attach(actor,tenant,fileId,ownerRef,purpose,requestKey)`, `detach(actor,tenant,attachmentId,requestKey)`: typed owner policy and idempotency. Сначала только существующий `CRM_CUSTOMER/COMPANY`; PERSON остаётся **HOLD** до отдельного разрешения object policy и PII gate.
- `requestDelete(actor,tenant,fileId,expectedVersion,reason,requestKey)`: логическая tombstone `DELETE_REQUESTED`, запрет на новые связи и чтения, проверка no active attachments, retention/legal hold/ongoing scan. Физическое удаление bytes — **отложенная идемпотентная операция** через существующий recovery/storage port, после durable gate; не удалять blob раньше PostgreSQL tombstone.
- `listAttachmentHistory(...)`; `reorderMedia(...)` с expected owner/media collection version, deterministic order; превью — отдельное безопасное производное содержимое, не выдавать произвольный PDF как картинку.
- Исходное имя файла: серверная нормализация Unicode, удаление path-separators, ограничение длины, запрет control chars и filename-based path construction. Только display metadata, **не источник storage key**.

### 7.2 Хранение и согласованность

- `FileStoragePort` / selector единственные знают LOCAL vs RustFS. Feature flag переключает **только новые** uploads; persisted provider/key определяет read/cleanup старых.
- `AVAILABLE` требует ровно один ACTIVE storage pointer, `CLEAN`, правильный SHA/size и актуальные owner permission на выдачу.
- Сканирование реальным ClamAV имеет fail-closed semantics; ERROR не маркирует `AVAILABLE`. Последние R1.3 real-infra E2E — regressions для R2.2.
- Person owner, Catalog Item, Sales Order link включаются **только после** квалификации их собственных owner adapters, а не просто путём добавления строки `owner_type`.
- Объекты не имеют публичных URL и не содержат application-level file encryption по текущему решению.
- Возможное расширение `file_attachment` metadata и file_object metadata — **минимальная additive Liquibase миграция**, без второго файлового реестра и без переноса blobs.
- При отказе RustFS 503 не путать unavailable и corrupt. Сложные lifecycle операции должны сохранять старые файлы и возвращать retriable error.

**Приёмка R2.2:** get/list/attach/detach/read/archive/retention lifecycle; двухпоточная гонка delete vs attach, delete vs read, verify vs pointer change, retry после crash, dual-provider LOCAL/RustFS, EICAR clean/infested/error, байтовая целостность после восстановления и строгие tenant/object-denials.

## 8. Этап RM-SVC-R2.3 — CATALOG Core + SALES/INVENTORY Agreement (P1)

### 8.1 CATALOG

Вводить **минимальный** CatalogItem aggregate с `sku`, **`modelCode`**, `kind` (`PRODUCT/SERVICE`), `tenant`, `displayName`, `status`, `rowVersion`; **`modelCode` не равен SKU, и одна модель может иметь несколько SKU**. Фильтровать по modelCode equality/prefix и виду товара или услуги; параметры из уже имеющейся схемы использовать повторно. Команды:
- `createItem`, `getItem`, `searchItems`, `updateItem`, `publishItem`, `archiveItem`
- `createPriceRevision`, `activatePriceRevision` — неизменяемые ценовые ревизии с ISO currency, effective date, active status.
- `attachItemMedia` только после typed CATALOG owner policy, без обхода FILES.
- `publishItem` требует формальную valid pricing/visibility eligibility. `archiveItem` запрещает новые заказы, но сохраняет ссылки и snapshots исторических sales lines.

### 8.2 SALES

- `createDraftOrder`, `updateDraftLines`, `getOrder(id)`, `searchOrders`, `getOrderHistory`.
- `confirm/cancel/fulfill` — только имеющиеся state transitions, с versioned draft and idempotency. Удаление подтверждённого заказа запрещено.
- Каждая строка хранит согласованный snapshot `catalogItemId/sku/revision/quantity/unitPrice/currency/tenant`. Изменение цены после подтверждения не меняет заказ задним числом.
- Draft commit валидирует активный customer/location/item и согласованность валюты, количества и SKU.

### 8.3 INVENTORY

- `transferStock`: неизменяемое парное движение source/destination (same tenant), запрет отрицательного остатка, идемпотентность.
- `performStocktake`, `approveStocktakeAdjustment`, `reconcileBalance` — фактическая инвентаризация и расхождения, не прямой UPDATE `stock_balance`.
- `expireReservations`: batch с fencing/lease и version protection, не освобождает уже подтверждённые отгрузки.
- Частичная отгрузка: операция идемпотентна по `fulfillmentId/lineId`, quantity conserved, no double decrement; SALES и INVENTORY согласованы через существующий port.
- `InventoryTenantSettingsService` должен быть доступен через authenticated `VerifiedActor` фасад для будущего транспорта; текущие внутренние UUID методы остаются закрытыми.

**Приёмка R2.3:** end-to-end `active customer + active location → catalog published item & price revision → draft order → confirm/reserve → partial fulfill → cancel/release`, consistency invariants SKU/price/currency/quantity and historical snapshots; no double reservation/deduction under concurrent retries.

## 9. Следующие домены (P2, не начинать параллельно с R2.1)

| Контур | Minimum viable operations | Ограничение |
|---|---|---|
| TASKS | create, assign, changeStatus, archive, list | Не позволять перескакивать состояния и менять чужой tenant |
| FINANCE | draft/approve/cancel invoice, record payment, reconciliation | Ни payment, ни financial audit не delete |
| MARKETING | draft campaign, template version, approval, send eligibility | Отдельное проверенное consent и block/unsubscribe |
| SUBSCRIPTION | get entitlements, assign plan, change effective plan, audit usage | Не менять разрешения IAM вместо entitlements |
| IAM | update role display metadata, member list/role summaries, enrollment UX | Не ослаблять global block / revocation |
| GEO / KMS / AUDIT | только обслуживание и выявленные дефекты | Никакого механического CRUD над справочником, ключами или журналами |
| STOREFRONT | item availability, publication, verified checkout linkage | Не создавать второй каталог и второй order writer |

Перед каждой P2 вертикалью нужен краткий inspection существующих схем, permissions и ownership; операции выше — **предлагаемые**, не заявлены как имеющиеся.

## 10. Тестовая матрица и качественные gates

Для **каждой** новой команды обязательны: positive, invalid input, unauthorized actor, wrong tenant, revoked/global blocked, archived/deactivated owner, stale expectedVersion, duplicate requestKey same/different payload, concurrent writes (two threads), SQL rollback, PII confidentiality, historical immutability where relevant.

Граничные сквозные тесты:
1. Архивировать CRM customer с активным FILES attachment; новые attach/read блокируются, история и bytes не удаляются.
2. Одновременно установить два primary contacts → PostgreSQL unique consistency, максимум один ACTIVE PRIMARY.
3. Попытка deactivate location с резервом/заказом → отказ без частичного update.
4. FILES verify старого key vs смена актуального key → no false quarantine; 503 vs 404.
5. Одновременно attach vs requestDelete → в базе не появляется активная связь с удалённым файлом.
6. RustFS enabled → upload; flag LOCAL → новые LOCAL, старые RustFS читаются; отсутствие RustFS endpoint → fail closed.
7. SALES confirmed order references immutable price revision; later price change does not rewrite order.
8. INVENTORY transfer and partial fulfill conserve quantities and remain idempotent.

**CI для каждого этапа:** H2 smoke, PostgreSQL integration, populated PostgreSQL PII, R1.3 real RustFS/ClamAV where FILES paths are touched, targeted E2E. Считать GREEN **только** когда все применимые jobs SUCCESS на **том же exact HEAD**. Обязательные артефакты: scenario table, JUnit report, SQL migration history, README/spec delta, unresolved risk register.

## 11. Приоритеты, результаты, нецели и решения

| Release | Priority | Deliverable | Entry/exit |
|---|---|---|---|
| `R2.0A` | P0 | FILES quarantine CAS + error taxonomy + race regression | Exact-head PG negative tests GREEN |
| `R2.0B` | P0 | VerifiedActor/owner policy/permissions alignment | No domain permission bypass and complete denied matrix |
| `R2.1A` | P0 | CRM Customer Update/Archive/Restore | PERSON/COMPANY + encrypted PII regression GREEN |
| `R2.1B` | P0 | CRM Contact Update/Primary/Archive | PG partial unique + concurrency GREEN |
| `R2.1C` | P0 | ORGANIZATION Location Update/Deactivate/Reactivate | Dependency denial + GEO/tenant regression GREEN |
| `R2.2A` | P0 | FILES metadata/get/list/delete planning | No active link leaks, retention/fencing GREEN |
| `R2.2B` | P0 | FILES owner-bound attachment lifecycle/media order | Single source of truth / LOCAL+RustFS tests GREEN |
| `R2.3A` | P1 | CATALOG lifecycle/pricing | Published item + immutable price revision GREEN |
| `R2.3B` | P1 | SALES draft/read/history | Immutable committed snapshots + workflow GREEN |
| `R2.3C` | P1 | INVENTORY transfer/stocktake/expiry/partial fulfillment | Conservation + idempotency + E2E GREEN |
| `R2.4+` | P2 | TASKS/FINANCE/MARKETING/SUBSCRIPTION | Only after P0/P1 acceptance |

**Mandatory process:** один PR/review section для вертикали; additive migrations only; не создавать вторые таблицы “для удобства”; изменение схем согласовать с доменным владельцем; attach links не подменяют бизнес-объект.

**Открытые до начала соответствующего этапа решения:** (1) permission code FILES_DELETE; (2) правила archive CRM с уже подтверждёнными SALES, (3) owner policy для CRM PERSON и предметных media links, (4) archival Location при historical-only references, (5) уровень детализации и retention audit, (6) доступный механизм глобального object-level ownership assignment. Эти решения нельзя молча предполагать “уже реализованными”.

## 12. Критерий закрытия всей программы

RM-SVC-R2 считается выполненным не по числу таблиц или CRUD методов, а по доказуемым сценариям:
- Пользователь с VerifiedActor создаёт клиента, редактирует данные/контакты, выбирает primary contact, архивирует и восстановливает клиента **без утечки PII**.
- Организация управляет локациями с проверкой влияния на склады/заказы.
- CRM файл корректно загружается, сканируется, прикрепляется, читается, отсоединяется и архивируется по owner policy; recovery не теряет актуальные файлы при гонке.
- Каталог публикует item и неизменяемую цену; SALES и INVENTORY используют согласованные snapshots, резервы и отгрузки.
- Все critical-negative / concurrent / fault-recovery E2E и exact-head CI GREEN; production/security gates рассматриваются отдельно.

**Рекомендация к старту:** реализовать `R2.0A FILES quarantine CAS` первой — это маленький, но критический корректностный дефект, который затрагивает последующую медиатеку и безопасное удаление.
