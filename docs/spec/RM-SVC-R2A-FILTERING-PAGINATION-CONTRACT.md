# RM-SVC-R2A — Единый контракт фильтрации, сортировки и пагинации

**Статус:** PROPOSED / техническое задание; сервисное исполнение ещё не произведено.  
**Репозиторий:** `alimbetov/RM`, ветка `base_dev`. **База анализа:** `a2bf2e676c431e1897e6b8901e1ed537fec43f5f`, 21.11.2026.  
**Зависимости:** `RM-SVC-R2` (service completion); сначала контракт и тесты, затем точечные миграции.  
**Границы:** Java/Spring Boot + PostgreSQL; никаких новых микросервисов, HTTP controllers, универсального динамического SQL framework, Elasticsearch или Redis ради пагинации. Production и merge в main — HOLD.

## 1. Проблема подтверждённого кода

| Контур / исходный метод | Как работает сейчас | Проблема / решение |
|---|---|---|
| `CrmSearchService.customers` | `CustomerSearch(type,status,regionZoneId,companyNameContains)`; параметры SQL, `LOWER(company)` с escaped `LIKE`, `count(*)`, `ORDER BY created_at DESC,id DESC LIMIT/OFFSET` | Безопасная параметризация, но `OFFSET` деградирует и смещает результаты при вставках; contains-поиск может потребовать `pg_trgm` и индекса. По PERSON имени не искать без отдельного разрешения/механизма. |
| `CrmSearchService.contacts` | `ContactSearch(customerId,status,primaryOnly)`, decrypt selected contacts, `count(*)`, LIMIT/OFFSET | Добавить обязательную область customerId для object-level policy или отдельное разрешение tenant-wide; не расшифровывать записи, которые могут быть отклонены авторизацией. |
| `OrganizationLocationQueryService.search` | type/status/name, Spring `Page`, ORDER createdAt DESC/id ASC | Неодинаковая tie-break direction относительно CRM и SALES; `LIKE` содержит неэкранированные `%` и `_`, которые могут выступать wildcard при пользовательском вводе. |
| `SalesOrderRepository.search` | status, fromInclusive/toExclusive, `count(*)`, createdAt DESC/id DESC, LIMIT/OFFSET | Нет cursor для больших исторических наборов; total count не всегда нужен; расширение фильтров требует строгого white-list. |
| `StockBalanceQueryService.search` | typed `StockSearch`, Page/PageRequest; tenant+permission | Доменная политика нужна для склада и SKU, без возможности прочитать запасы недоступной локации. |
| `FileAttachmentService.listCustomer` | order createdAt DESC/id DESC, limit 1..100; без cursor; owner проверяется в CRM | Использовать keyset после owner check и стабильный page token; отфильтровывать detached, не-CLEAN, не-AVAILABLE. |

Отдельно: текущий `PageRequest.of(page,size)` и вычисление `(long)page*size` не обеспечивают надёжной защиты от дорогих глубоких запросов; offset следует ограничить на старом API, а новые списки переводить на keyset.

## 2. Принципиальное решение

1. **Для больших, постоянно изменяющихся списков: keyset/cursor pagination по умолчанию.** Это CRM Customers/Contacts, SALES Orders/History, FILES Media, INVENTORY Movements и AUDIT History.
2. **Для коротких административных справочников допускается page/size** (например, ограниченный перечень LOCATION), если есть разумная верхняя граница глубины и подтверждённый EXPLAIN.
3. Запросы статически/типизированно формируются из **допустимых полей** конкретного домена. Нельзя принимать SQL-фрагменты, названия колонок и sort expressions от клиента и подставлять без allowlist.
4. **По умолчанию не вычислять `totalElements` на каждой cursor-странице.** Только `hasNext`, `nextCursor`, `items`. Отдельный `count` допускается при явном разрешении, бюджетировании нагрузки и зафиксированной семантике.
5. Авторизация и условия tenant/owner являются частью **самого SQL-предиката** для допускающих построчную область видимости; проверка IAM до запроса, никогда не применять `LIMIT` до проверки объектных прав и не “подфильтровывать” уже ограниченные строки в Java.
6. Cursor всегда связан с конкретными `tenantId`, областью owner, *каноническим filter hash*, сортировкой, размером страницы и версией контракта; смена фильтра → новый cursor.
7. Использовать короткие DB-транзакции для чтения, не удерживать snapshot и locks между запросами страниц. Keyset обеспечивает устойчивость к сдвигам OFFSET, но **не гарантирует единый snapshot при новых/изменённых данных**. Для юридически/финансово воспроизводимой выгрузки нужен отдельный frozen snapshot/asOf или export job, не выдавать обычный cursor за точную историческую выгрузку.

## 3. Обязательные универсальные DTO

### 3.1 Ввод

```java
record PageWindow(int limit, String cursor) {}
record SortSpec(String field, Direction direction) {}
record FilteredQuery<F>(F filters, PageWindow window, SortSpec sort) {}
```

- По умолчанию `limit=25`; допустимый диапазон `1..100`, для PII/read-heavy endpoints может быть ниже (например 50). `cursor=null` означает первую страницу.
- Для новых сервисов предпочтителен один утверждённый default sort; `SortSpec` не становится произвольной SQL-сортировкой.
- Все поля фильтра имеют явный тип: `UUID`, `enum`, `Instant`, `BigDecimal`, `Boolean`; обычная строка только для утверждённого текста/кодов.
- `null` означает «фильтр не задан», **кроме** заданных явно tri-state-полей, где различаются `ANY / IS_NULL / NOT_NULL / EQUALS`. `false` не эквивалентно `null`.
- Временные диапазоны: `fromInclusive <= createdAt < toExclusive`, UTC `Instant`; ограничить экстремальные периоды либо делать query budget. Для даты клиента отдельно явное преобразование его timezone → UTC.
- Списки `IN` (статусы, IDs) ограничить, например `1..20`, дедуплицировать, отклонять неизвестные enum.
- Строковые contains: trim, Unicode normalization NFC, max 100 chars, lowercase `Locale.ROOT`, escape `% _ \` + `ESCAPE '\'`; для тяжёлых contains запретить слишком короткую строку (например менее 3 Unicode codepoints), прежде чем использовать trigram index.
- Не позволять незаявленный сортировочный/фильтровый параметр: ошибка `INVALID_FILTER`, а не молчаливое игнорирование.

### 3.2 Ответ

```java
record SliceResult<T>(
    List<T> items,
    boolean hasNext,
    String nextCursor,
    int limit,
    String appliedSort,
    String filterFingerprint
) {}
```

- Первая страница и все последующие вызываются с заново проверенным `VerifiedActor` и effective IAM/owner access.
- Размер `items` не превышает `limit`. Из БД читается `limit+1` строка для `hasNext`, лишняя строка не возвращается.
- При `hasNext=false`, `nextCursor=null`. При `hasNext=true`, cursor строится **по последнему возвращённому элементу**, а не по `limit+1` (иначе один элемент потеряется).
- Никаких `storageKey`, encrypted values, внутренних токенов/ключей, SQL-запросов и ошибок расшифровки в ответах.

## 4. Cursor: протокол и безопасность

Использовать непрозрачный, версионированный, аутентифицированный token. На первом этапе допустим `base64url(payload).base64url(HMAC-SHA256)`, где HMAC-ключ берётся из отдельного server-side secret. **Не использовать** KMS data-encryption key CRM и не хранить tenant secrets в токене. Альтернатива — opaque server-side cursor storage, если уже есть готовый механизм; **не создавать Redis исключительно ради cursor**.

Полезная нагрузка **минимальная**:
- `v` версия cursor, `exp` срок годности (например 15 минут), `tenant` UUID,
- `scope` — идентификатор домена/owner access scope + id владельца (если применимо),
- `filtersSha256` канонический digest фильтра, `sortId`, `limit`,
- `lastCreatedAt` (точность PostgreSQL microseconds, UTC), `lastId` UUID.
  
Cursor нельзя считать авторизацией. Не следует помещать PII (имена, email, phone) в plaintext payload; при необходимости канонический fingerprint вычисляется на сервере. HMAC проверять constant-time (`MessageDigest.isEqual`). Max token length (например 2048 chars), строгая версия и expiry, reject malformed/signature mismatch/different tenant/scope/filter/sort/limit. Ротация HMAC-ключей — через key id и ограниченный overlap; истёкший token → `CURSOR_EXPIRED`, не fallback к page=0.

Для индивидуального пользователя можно дополнительно bind token к `actorId` / effective access epoch. Это не заменяет новую проверку прав на каждом запросе; при смене ролей token должен быть недействителен либо актуальная SQL policy должна строго исключить отозванные строки.

## 5. SQL keyset и сортировка

### 5.1 Канонический сортировочный контракт

**Default для событийных/созданных записей:** `created_at DESC, id DESC`. Все колонки с tie-breaker; `created_at NOT NULL`. Для других сортировок (`name`, `amount`, `updatedAt`) нужен отдельный явный cursor shape, индекс и правила для `NULLS FIRST/LAST` и collation. Без этого альтернативная сортировка отключена.

Пример CRM Customer (условия owner/role добавляются доменом):

```sql
SELECT c.id, c.customer_type, c.organization_label, c.region_zone_id,
       c.status, c.created_at, c.row_version
FROM crm.customer c
WHERE c.tenant_id = :tenant_id
  AND (:status IS NULL OR c.status = :status)
  AND (:type IS NULL OR c.customer_type = :type)
  AND (:region IS NULL OR c.region_zone_id = :region)
  AND (
    :cursor_created_at IS NULL
    OR (c.created_at, c.id) < (:cursor_created_at, :cursor_id)
  )
ORDER BY c.created_at DESC, c.id DESC
LIMIT :limit_plus_one;
```

Пример соответствует **PostgreSQL keyset semantics**; динамическое составление WHERE допустимо из статических проверенных фрагментов, чтобы не мешать индексам конструкцией `param IS NULL OR ...`. Служебные аргументы в JDBC PreparedStatement. Лексикографическое сравнение `(created_at,id) < (...)` корректно только для согласованной двухколоночной сортировки DESC/DESC и одинакового типа/точности курсора. Не округлять timestamp до миллисекунд при хранении token.

Индексы утверждать **после** сравнения `EXPLAIN (ANALYZE, BUFFERS)`:
- `crm.customer (tenant_id, created_at DESC, id DESC)`
- `crm.customer (tenant_id, status, created_at DESC, id DESC)` при реальной селективности status
- `crm.contact (tenant_id, customer_id, created_at DESC, id DESC)`
- `crm.sales_order (tenant_id, created_at DESC, id DESC)`
- `crm.file_attachment (tenant_id, owner_type, owner_id, created_at DESC, id DESC)` **с частичным `WHERE detached_at IS NULL`**.
- Для `LOWER(company_name) LIKE '%text%'` в PostgreSQL возможен `pg_trgm` + GIN/GIN expression index; сначала функциональное обоснование и контролируемое extension admission, без PII-колонок.

Индексы делать additive в существующих Liquibase changelog, не дублировать присутствующие индексы; `CREATE INDEX CONCURRENTLY` только с корректной Liquibase transaction policy и PostgreSQL smoke.

### 5.2 OFFSET compatibility

Не ломать старые `Page<T>` API на первом шаге. Ввести отдельный cursor-based метод, старый оставить с bounded `page*size <= 10_000` (значение настраивается/обосновывается по нагрузке). Вычислять offset как `long` с `Math.multiplyExact`, не преобразовывать в `int` без проверки. Для старых запросов с `COUNT` оценивать стоимость и не выполнять его для новых cursor methods. Для `page/size` обеспечить `Sort.by(DESC,"createdAt").and(DESC,"id")`, чтобы тот же tie-breaker соответствовал новым страницам, если домен не утвердит другой порядок.

## 6. Доменные матрицы допустимых фильтров

| Домен / метод | Допустимые фильтры P0/P1 | Сортировка | Security / ограничения |
|---|---|---|---|
| CRM Customer | type (PERSON/COMPANY), status, regionZoneId, companyNameContains, created range | created DESC/id DESC | PERSON name is encrypted; **никакого SQL LIKE/decrypt-and-filter** для PERSON; status ANY only with explicit history scope |
| CRM Contact | **customerId required by default**, status, primaryOnly, created range | created DESC/id DESC | CRM_PII_READ **и** owner read; не раскрывать contact от чужого клиента; tenant-wide contact lists только отдельным разрешённым сценарием |
| ORGANIZATION Location | type, status, nameContains, KATO code, activeOnly | created DESC/id DESC | escape LIKE, account for dependent inventory/fulfillment, no access beyond workspace |
| FILES Media/Attachment | ownerType, **ownerId required**, purpose, MIME/category, state, created range | created DESC/id DESC; gallery position separately | CRM/CATALOG/SALES owner port must admit; no detached, infected or unavailable bytes |
| SALES Order | status set, created [from,to), seller workspace, customerId where modeled | created DESC/id DESC | tenant + domain object scope; no PII columns or free-text across encrypted data; history past cutoff separately |
| INVENTORY Stock | SKU/itemId, locationId, availableOnly, status where modeled | qualified stable sort (sku/location/id) | stock state mutable, cursor not a historical valuation snapshot; enforce inventory-location access |
| INVENTORY Movements/Reservations | SKU, location, movementType/status, created [from,to) | created DESC/id DESC | append-only movements, no unbounded global scans |
| CATALOG Items | status, category, seller, SKU, prefix/contains name where stored publicly, price range only if pricing index supports it | created DESC/id DESC | published vs draft visibility; snapshot revision for price in SALES |
| TASKS (P2) | assignee, creator, status, due date range, priority | dueAt/id when explicitly qualified; otherwise created/id | assignment scope and tenant membership |
| FINANCE (P2) | status, accounting period, currency, counterparty, due date | created/id or postingDate/id per report | financial audit and published revision, no PII leaks |
| MARKETING (P2) | campaign status, owner, created [from,to) | created/id | consent alone is not campaign authorization |
| SUBSCRIPTION (P2) | plan/status/effective period | created/id | no tenant entitlement leaks |

Новые поля возможны только через конкретное доменное ADR, тест селективности и инварианты owner access. Фильтры каталога/финансов/тасков пока **проектные**, не утверждение о существующих полях в БД.

## 7. Semantics при изменении данных между страницами

- При новых вставках в начало списка keyset не повторяет уже просмотренные ключи из-за сдвига offset; новые записи выше первой страницы в последующие страницы обычно не попадут — это намеренная семантика просмотра.
- `created_at` и `id` immutable. Изменение **фильтрового поля** (например status) в процессе листинга может вызвать пропуск/появление строки между запросами; cursor не является snapshot.
- Для консистентного отчёта/экспорта вводится `asOf` / snapshot generation и отдельная event-backed модель или материализация результата с retention, когда продукт действительно потребует. Не держать долгие PostgreSQL snapshot transactions через HTTP/gRPC запросы.
- Архивация во время листинга: запись исчезает из active-only последующих страниц; no stale PII disclosure. При revocation: немедленный permission deny независимо от валидного token.

## 8. Приёмка и измеримая квалификация

### 8.1 Functional/property tests

Минимум:
1. 201+ строк с одинаковым `created_at` → последовательный обход cursor без пропусков/дубликатов по `id`.
2. Страницы `limit=1`, `25`, `100`, `last page`, `empty`; проверять `limit+1` и last-returned cursor.
3. Записи добавляются между запросами; keyset не дублирует уже отданные записи; описание новых элементов и filter state documented.
4. Изменение status между страницами; тест закрепляет eventual-current policy, не обещает snapshot.
5. Different tenant, actor access revoked, owner archived, foreign owner, SQL injection-like strings, escaped `% _ \`, Unicode NFC, unsupported filter/sort, PII permission revoked.
6. Cursor: tampered HMAC, wrong scope, wrong tenant, changed filter, expired, oversized token, key rotation, page-size change.
7. Boundary: `page=Integer.MAX_VALUE`, `size=100`, overflow, deep offset limit → bounded reject (не дорогой запрос).
8. Empty/nullable status tri-state, date UTC exclusive upper bound, false/true primaryOnly, no decrypted-person-name search.
9. Concurrent attach/detach FILES, archive CUSTOMER, update LOCATION, confirm SALES, transfer INVENTORY — отсутствие утечек между областями видимости.
10. `EXPLAIN (ANALYZE, BUFFERS)` на PostgreSQL с синтетическими 10k и 100k строками; проверить индексную траекторию и стабильность p95/p99 под заявленной нагрузкой, записать CPU/DB buffers/rows returned. **Не назначать выдуманный SLA**, до старта квалификации утвердить профиль нагрузки и пороги.

### 8.2 CI gates и внедрение

- `SEARCH-R0`: инвентаризация текущих фильтров/индексов + контракт cursor token + error codes, без runtime переключения.
- `SEARCH-R1`: CRM Customers/Contacts cursor + owner-scoped permission + deterministic PostgreSQL tests.
- `SEARCH-R2`: ORGANIZATION/Sales order list cursor, bounded legacy OFFSET, EXPLAIN и security.
- `SEARCH-R3`: FILES Media/Attachments, Inventory movement/stock cursor, tie-breaker, no PII leak.
- `SEARCH-R4`: CATALOG after functional CATALOG core; other P2 domains after release gate.
- Каждый этап: exact-head H2 smoke + real PostgreSQL tests + populated PII + RustFS/ClamAV real-infra regression при затрагивании FILES. Никаких контроллеров и merge в main до отдельного допуска.

## 9. Definition of Done

Домен считается квалифицированным по фильтрации, если:
- есть типизированный filter DTO, allowlist сортировок, owner/tenant SQL predicates и документированная default visibility;
- cursor связан с tenant/scope/filter/sort/limit, проверен HMAC, expired/revoked tokens fail closed;
- нет N+1 и неиспользуемых COUNT для cursor, нет глубокого OFFSET без ограничения;
- PostgreSQL доказал отсутствие дубликатов/пропусков при одинаковых timestamp, точный порядок и соблюдение прав;
- метрики SQL и индексный план записаны в ревью для утверждённой нагрузки;
- существующие `Page` методы не сломаны до миграции вызывающих клиентов;
- обе применимые CI линейки PASS на **точном HEAD**.

**Рекомендованный порядок:** сначала `RM-SVC-R2.0A` (quarantine CAS), параллельно документационный `SEARCH-R0`; затем `SEARCH-R1 CRM` как первая реализованная cursor-вертикаль до массового CRUD. Не создавать второй search сервис.
