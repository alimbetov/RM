# RM — ORGANIZATION: проход 04/12, Create, Update & Data Integrity

**Документ:** RM-ORG-FUNC-R1-P04. **Source HEAD до ревью:** `5d3eff7362ea895eb6af3b78a7fa478937569662`, ветка `base_dev` (22.11.2026). **Объём:** проверки исходников и тестов, уточнение ТЗ; **НЕ** реализация, **НЕ** новые GREEN tests. Feature Freeze: без HTTP-контроллеров, merge в main и production admission.

**Связи:** [основное ТЗ ORGANIZATION](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md), [проход 03 — lifecycle](RM-ORG-FUNCTIONAL-DEPTH-PASS03-2026-11-22.md).

## 1. Доказанная исходная точка

| Source | Фактически работает | Ограничение именно на текущем source |
|---|---|---|
| `AuthenticatedOrganizationLocations` | verified-actor `create(type,name,kato,address)`, `get`, `search` | Нет typed create DTO, update |
| `OrganizationLocationService.create` | `@Transactional`, `requireForCommand(actor,tenant,ORGANIZATION_LOCATION_WRITE)`, `KatoLocationPolicy`, `saveAndFlush` | Нет нормализации name/address, доменной проверки длины/управляющих символов, create audit |
| `OrganizationLocationEntity` | `@Entity @Table(org_location)`, immutable-like поля без setters, `@Version rowVersion`, `Instant.now()`, `status=ACTIVE` | Данные в конструкторе проверяются лишь на null/blank для name/KATO; нет изменения профиля |
| `LocationDto` / MapStruct `LocationMapper` | ID, workspaceId, type, name, kato, address, status, timestamps, rowVersion | Результат содержит нужную версию, новый read DTO не нужен |
| `OrganizationLocationRepository` | `findByIdAndWorkspaceId`, tenant-scoped search, JpaRepository | Не существует `findByIdAndWorkspaceIdForUpdate`/`@Lock(PESSIMISTIC_WRITE)`; наследованный JpaRepository.findById существует, не использовать для команд |
| `KatoLocationPolicy` | Исключает неизвестный/удалённый KATO, level>3 | Сейчас НЕ нормализует/не валидирует формат кода, не блокирует GEO row; level 1 формально проходит условие `<=3`, если встретится в snapshot |
| Liquibase `0009` | PK id, composite UNIQUE(workspace_id,id), FK workspace/KATO, enum CHECK, nullable address, name VARCHAR(200), kato VARCHAR(16), address VARCHAR(500), row_version BIGINT NOT NULL | Нет CHECK на blank name; нет ограничений NFC/контрольных символов; № версии сам по себе не делает CAS-сервис |
| `OrganizationLocationQueryQualificationTest` | A/B isolation, verified facade, rollback, PG revoke race, repeated equal names permitted | Нет update, concurrent edit, KATO history, no-op, create normalization, update audit |
| `KatoTerritoryQualificationTest` | create BRANCH/WAREHOUSE/POS, negative unknown/deleted/level>3/personal workspace | Не доказывает правил изменения существующей локации |

Смежное состояние: `InventoryMovementService` содержит первое RECEIPT и ledger; `StockEligibility` обеспечивает owner lock. В актуальном HEAD `InventoryTransferService` (INV-Q7) уже создаёт transfer-out/in movements и блокирует два склада в стабильном порядке. Поэтому смену КАТО при истории нужно запрещать **и после transfers**, не ссылаться на перенос как на отсутствующую функцию.

## 2. Решение DEC-ORG-04-A: update — полная замена редактируемого профиля

**В R1 не строить generic PATCH-трёхсостояний, JSON Merge Patch, reflection setter или новый form-engine.** Уточнить прежнюю формулировку «patch»: `update` означает **полную замену только редактируемых полей**; неизменяемые identity/status/version сохраняются.

~~~java
// kz.rm.organization.dto (проектный контракт, сейчас этих records нет)
public record LocationCreateRequest(
        OrganizationLocationEntity.Type type,
        String name,
        String katoCode,
        String addressLine
) {}

public record LocationUpdateRequest(
        String name,
        String katoCode,
        String addressLine,
        long expectedVersion
) {}

// Публичный доверенный Java-фасад; существующий create-overload оставить совместимым.
LocationDto create(VerifiedActor actor, UUID tenant, LocationCreateRequest request);
LocationDto update(VerifiedActor actor, UUID tenant, UUID id, LocationUpdateRequest request);
~~~

Для update оба `name` и `katoCode` обязательны и передаются как **актуальные значения**, даже если не меняются. `addressLine=null` или blank (`""`/пробелы) означает **очистку адреса**. Отсутствующего `name`/`katoCode` нет как понятия «оставить без изменений». Это позволяет UI делать обычный edit-form с полным snapshot, исключая implicit clearing и конфликт между `null` и omit. Версию берём из последнего `LocationDto.rowVersion()`. Доменная модель и полный `LocationDto` остаются как есть.

Для `create` не принимать user-supplied `id/workspaceId/rowVersion/status`. Type обязателен только при создании; образуется `ACTIVE`, `id=UUID.randomUUID()`, `rowVersion=0`. Существующий старый фасад `create(actor,tenant,type,name,kato,address)` делегирует в один и тот же validate/create path, чтобы не ломать тесты.

**Повтор CREATE:** текущее серверное UUID-генерирование **не гарантирует идемпотентности** при сетевом повторе без отдельного стабильного request key. Два корректных create с одинаковым name разрешены (это уже отражено тестами), так что не вводить произвольный unique(name) или «автоматический поиск похожего». Для R1 считать create неидемпотентным; предусмотреть single submit в будущем UI и осознанно оценить необходимость request-id в отдельном production-transport контракте. Не заявлять exactly-once.

## 3. Валидация полей (один нормализатор для create и update)

| Field | При create | При update | Строгое правило R1 |
|---|---|---|---|
| type | REQUIRED enum BRANCH/WAREHOUSE/POINT_OF_SALE | Не входит в request | immutable location identity; изменение типа = новая локация |
| name | REQUIRED | REQUIRED | `Normalizer.normalize(input.strip(),NFC)`; не blank; 1..200 Unicode code points; запрещён `Character.isISOControl`, невалидные суррогаты и управляющие bidi override; не схлопывать внутренние пробелы |
| katoCode | REQUIRED | REQUIRED | Точный строковый технический код `[0-9]{2,16}`, не преобразовывать в число (ведущие нули). Значение сверить с `KatoLocationPolicy` **только при создании или фактическом изменении KATO** |
| addressLine | nullable | nullable (очистить) | normalize `strip()+NFC`; null/blank → NULL; non-null максимум 500 Unicode code points; отказ за управляющие символы/невалидные суррогаты; не требовать обязательного адреса для филиала, склада или POS в R1 |
| expectedVersion | N/A | REQUIRED >=0 | Без default 0 как скрытого fallback; сравнивать с locked `entity.rowVersion` до изменения |
| id | Серверный UUID | Путь/аргумент метода, REQUIRED | В request DTO не дублировать, не редактировать |
| tenant/workspace_id | Доверенно выбранный workspace | Доверенно выбранный workspace | Нельзя заменить UUID из body; `requireForCommand` до поиска/справочника |
| status | Создаётся как ACTIVE | Не входит в request | Переходы только `deactivate/reactivate/close` из прохода 03 |
| createdAt/updatedAt/rowVersion | Системные | Не входят кроме expectedVersion | CreatedAt неизменен, updatedAt только при реальном изменении; @Version управляет новым rowVersion |

**Unicode:** PostgreSQL VARCHAR(n) считает символы, Java `String.length()` — UTF-16 code units; определить границу через `codePointCount(0,length)` после NFC. Символы supplementary допустимы при корректных парных surrogate. Unicode-specific tests обязательны. Для `addressLine` и `name` достаточно общих domain helpers в package ORGANIZATION; новая validation library не требуется.

**Важное ограничение GEO:** фактический `KatoLocationPolicy` допускает `!deleted && level<=3`; DDL требует `level_no>0`. Не объявлять в 04/12 реализованным `level IN (2,3)` или автоматическую проверку «район/город» по цифрам КАТО: это отдельная предметная настройка GEO, если реальный справочник обнаружит неоднозначность. Строго сохранять точный existing eligible контракт до согласования; не расширять область выбора ради работы update.

## 4. Создание: окончательная последовательность эффектов

1. Проверить `VerifiedActor` при входе доверенного фасада, извлечь `userId` через `actor.userId()`, НЕ принимать `actorId` из DTO.
2. Открыть Spring `@Transactional` в `OrganizationLocationService.create`; выполнить `TenantAuthorization.requireForCommand(actorId,tenant,ORGANIZATION_LOCATION_WRITE)` (IAM authorizes transaction and locks actor/workspace).
3. Нормализовать/проверить имя, адрес, type, технический формат KATO; через GEO `KatoLocationPolicy.requireOrganizationLocationEligible` проверить новый KATO.
4. Создать `OrganizationLocationEntity(UUID.randomUUID(), tenant, type, normalizedName,kato,normalizedAddress)`, status ACTIVE и оба timestamps.
5. `saveAndFlush` в `public.org_location`. FK на `iam_workspace` и `ref_kato_territory` даёт DB integrity, но не заменяет IAM/GEO.
6. Добавить `crm.audit_event(tenant_id,id,actor_user_id,entity_id,action_code='ORG_LOCATION_CREATE',occurred_at)` в **той же** транзакции, после успешной проверки и до commit. Откат ошибки audit откатывает insert локации.
7. Вернуть через `LocationMapper.toDto` persisted ID/rowVersion. **На 04/12 это ТЗ, не исполненный runtime путь.**

Нельзя при create автоматически инициализировать остатки, создавать store_front, переносить пользователей/tenant grants, удалять или перепривязывать чужие локации.

## 5. Update: блокировка, expectedVersion, no-op, audit

### 5.1 SQL/JPA boundary

Добавить ровно один scoped JPA repository method:

~~~java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT l FROM OrganizationLocationEntity l " +
       "WHERE l.workspaceId = :tenant AND l.id = :id")
Optional<OrganizationLocationEntity> lockByWorkspaceIdAndId(
    @Param("tenant") UUID tenant,
    @Param("id") UUID id
);
~~~

Транзакция: `IAM requireForCommand` → `ORG row FOR UPDATE` → `expectedVersion check` → `status != CLOSED` → `normalize/validate` → при changed KATO — проверка history → GEO eligibility → `entity.applyProfile(...)` (только 3 поля+updatedAt) → JPA `@Version` flush → atomic audit UPDATE → commit. Для full-profile update **не** брать stock balance `FOR UPDATE`/sales order locks: history checks — только `EXISTS` после location lock; INVENTORY admission уже блокирует ту же location row.

**CAS и PESSIMISTIC_WRITE сочетаются:** row lock сериализует операции ORG/INVENTORY, а expectedVersion защищает от потерянного обновления устаревшей карточки. Проверка версии перед и после ORM не требует второй самостоятельной таблицы версий. При поздней `OptimisticLockException`/`StaleObjectStateException` — rollback всей транзакции; наружу стабильный `STALE_VERSION`. Тестировать реальный Hibernate SQL и @Version increment, а не только объект in-memory.

**Тенантность generated JPA UPDATE:** Hibernate обычно обновляет по глобальному `org_location.id` плюс `row_version`, не обязательно включает `workspace_id` в самом UPDATE. Это допустимо в выбранном R1 **только** потому что:
- загруженная для изменения entity найдена исключительно по `(workspace_id,id)` и заблокирована;
- `id` — глобальный PRIMARY KEY, `workspaceId` immutable;
- `expectedVersion` сравнивается с заблокированной версией, ORM update — versioned.
Не заявлять, что SQL от Hibernate сам всегда содержит tenant predicate; запретить прямой `findById`+`save` update без scoped load. Если поздний security standard требует буквально tenant predicate в DML, менять *только метод persistence* на проверенный scoped SQL-CAS, не смешивая JDBC update с уже managed JPA entity в одной транзакции. Решение прохода 04: **scoped-lock + JPA @Version**, без нового persistence-engine.

### 5.2 Успех и no-op

Если normalized тройка (`name,katoCode,addressLine`) в точности совпала с текущей:
- `expectedVersion` всё равно обязателен и должен совпасть;
- не вызывать setters, не менять `updatedAt`, не увеличивать `rowVersion`, не писать audit;
- возвращать текущий `LocationDto`.
Если хотя бы одно поле изменилось, mutation делает **ровно +1** `rowVersion`, один `updatedAt` и один `ORG_LOCATION_UPDATE` audit event. Нет `save` raw DTO и нет возможности обновить `status/type/workspaceId` методом profile update.

### 5.3 Изменение КАТО и inventory history

Смена `katoCode` — это **изменение географической идентичности**, не просто label-edit. Оно допускается только когда не существует:

~~~sql
-- Already holding FOR UPDATE on this exact org_location
SELECT
 EXISTS(SELECT 1 FROM crm.inv_stock_balance b
        WHERE b.tenant_id = :tenant AND b.location_id = :location
          AND (b.on_hand <> 0 OR b.reserved <> 0)) AS has_live_stock,
 EXISTS(SELECT 1 FROM crm.inv_stock_movement m
        WHERE m.tenant_id = :tenant AND m.location_id = :location)
        AS has_movement_history,
 EXISTS(SELECT 1 FROM crm.inv_stock_reservation r
        WHERE r.tenant_id = :tenant AND r.location_id = :location)
        AS has_reservation_history;
~~~

Если хотя бы один TRUE, вернуть `KATO_CHANGE_BLOCKED_BY_HISTORY`, оставить entity без изменений. Последняя проверка именно **существования любой** reservation, не только ACTIVE: сохранённые cancelled/fulfilled orders ссылаются на старую географию. Поскольку Q7 transfer оставляет в ledger обе стороны, смена КАТО после любого transfer также запрещена. Нулевая техническая stock row без движения/резерва сама по себе не запрещает исправить ошибочный KATO: не блокировать из-за одной пустой строки.

Если history отсутствует, вызвать `KatoLocationPolicy` на **новом** коде после проверки формата, обновить KATO внутри row-locked TX и audit. При сохранённом **старом неизменённом** КАТО, который позже GEO пометил deleted, *пропустить* eligibility recheck: это позволяет исправить имя/адрес и соответствует проходу 03 (reactivate grandfathered code). Новое значение deleted KATO всегда запрещено.

**Изменение name/addressLine:** разрешено и при наличии stock/reservation, потому что это коррекция справочной карточки, без изменения ID и immutable inventory/order history. Не использовать изменение адреса/КАТО вместо реальной операции физического перемещения товара; UI/операционный процесс должен трактовать address edit как исправление реквизитов, а физический move как INVENTORY transfer. `addressLine` не несёт гарантии точной геолокации.

## 6. Политика ошибок (без HTTP controllers)

| Internal reason | Условие | Последствие |
|---|---|---|
| `ACCESS_DENIED` | Нет VerifiedActor, inactive workspace/member, нет WRITE permission | 0 insert/update/audit, no cross-tenant information |
| `INVALID_CREATE_REQUEST` | Null type, blank/long/unsafe name, invalid address, malformed KATO | 0 |
| `INVALID_UPDATE_REQUEST` | Null ID/request/name/KATO, negative expectedVersion | 0 |
| `LOCATION_NOT_FOUND` | Scoped id absent, including foreign tenant ID | 0 и отсутствие подсказки о существовании чужой строки |
| `LOCATION_CLOSED` | Изменение профиля CLOSED | 0 |
| `STALE_VERSION` | expectedVersion != locked rowVersion; в т.ч. stale no-op/retry | 0, transaction rollback |
| `INVALID_KATO` | KATO new, unknown/deleted/level>3/malformed | 0 |
| `KATO_CHANGE_BLOCKED_BY_HISTORY` | Новый KATO при live stock или любой inventory ledger/reservation history | 0 |
| `LOCATION_PERSISTENCE_CONFLICT` | SQL integrity/unique/foreign-key failure при реальной гонке/внешней миграции | Вся TX rollback, не преобразовывать в «успех» |
| `LOCATION_UNCHANGED` | Тройка совпала и версия актуальна | **Не ошибка**: DTO без version/time/audit delta |

Стабильные reason codes — логический контракт Java-domain, **не** новые REST response mappings; не создавать иерархию из десятков exception classes. IAM authorization всегда раньше чтения KATO или чужой локации. Для KATO сервиса не раскрывать внутренние справочные записи пользователю без права.

## 7. Проверяемые SQL/DDL следствия

- Новая миграция для Create/Update R1 **не требуется**: `org_location` уже имеет нужные столбцы, FK и `row_version`; `crm.audit_event` уже существует. Не добавлять второй master `location_profile`, JSONB patch body, event sourcing или eTag store.
- Существующие DB limits: name 200, address 500, kato 16; `NOT NULL` name/KATO/type/status/tenant/timestamps/version; FK KATO/workspace; enum CHECK. **Нет CHECK `btrim(name) <> ''`**, поэтому raw SQL теоретически может создавать невалидный пробельный name. При реализации предусмотреть **данные заполненной БД/SQL preflight** и, только после согласования миграции в проходе 09, additive database CHECK. В 04 source review не заявлять такую защиту существующей.
- **Не удалять старые связки, не исправлять без evidence старые строчки, не менять changeset 0009.** Новые ограничения — additive changeSet после populated upgrade qualification, если понадобятся.
- ORM SQL expected (схематично; Hibernate может менять порядок/набор колонок):
  - SELECT scoped `WHERE workspace_id=? AND id=? FOR UPDATE`;
  - UPDATE versioned `SET name=?,kato_code=?,address_line=?,updated_at=?,row_version=? WHERE id=? AND row_version=?`;
  - INSERT audit `(tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)`;
  - rollback при audit failure или stale version.
- Не ставить уникальность по name: текущий `OrganizationLocationQueryQualificationTest` намеренно создаёт несколько локаций с именем `Repeated Warehouse` внутри одного tenant; имена — не business key.

## 8. Сценарии приёмки ORG-P04-Q01…Q20

| ID | Проверка | Expected evidence |
|---|---|---|
| P04-Q01 | CREATE BRANCH/WAREHOUSE/POINT_OF_SALE с валидными КАТО и правом WRITE | ACTIVE, correct immutable UUID/type/tenant, version=0, одно CREATE audit |
| P04-Q02 | CREATE с Unicode, пробелами вокруг name/address | trim+NFC сохранено, get/search возвращают нормализованное |
| P04-Q03 | CREATE с null/blank name, control, malformed surrogate, >200 codepoints | Reject, zero location/audit rows |
| P04-Q04 | CREATE с address null/blank/500/501 codepoints | NULL или valid exact boundary, reject >500 |
| P04-Q05 | CREATE null type, KATO wrong format, missing/deleted/level>3 | Reject prior to insert and audit; exact scope/format preserved |
| P04-Q06 | CREATE same name twice within same tenant | Two distinct records, no hidden unique name and no assertion exactly-once |
| P04-Q07 | CREATE with missing/expired principal, no WRITE, foreign/personal tenant | Deny before probing KATO, zero location/audit |
| P04-Q08 | UPDATE full profile ACTIVE: name+address, KATO same | New fields, one version increment, updatedAt changed, audit one, ID/tenant/type unchanged |
| P04-Q09 | UPDATE addressLine null/blank, name/KATO supplied unchanged | Address NULL, no accidental clear of name/KATO |
| P04-Q10 | UPDATE all same normalized fields with fresh version | Same DTO, unchanged version/time, no audit |
| P04-Q11 | UPDATE same values but stale version | STALE_VERSION, not silent success |
| P04-Q12 | UPDATE with negative version, missing name/KATO/ID, controls, overlimit | Reject, no partial persistence |
| P04-Q13 | UPDATE valid new KATO without inventory movements/reservations/stock | New eligible KATO, version+1, one audit |
| P04-Q14 | UPDATE new KATO with onHand>0 / old ledger / fulfilled-or-released reservation / INV-Q7 transfer history | Reject even when current onHand=0, no false relocation |
| P04-Q15 | UPDATE name/address while unchanged historical KATO became deleted | Allowed; KATO not revalidated if unchanged |
| P04-Q16 | UPDATE during INACTIVE; UPDATE during CLOSED | Allowed first, denied second, consistent with pass03 |
| P04-Q17 | Same verified user with A WRITE/B READ; try updating B location via A tenant or B tenant | Scoped NOT_FOUND / ACCESS_DENIED, no cross-tenant read/mutation |
| P04-Q18 | Concurrent UPDATE-vs-UPDATE with same expectedVersion, 2 PG sessions | Exactly one successful mutation + audit; second STALE_VERSION after lock wait; no lost update |
| P04-Q19 | Concurrent UPDATE-vs-DEACTIVATE and UPDATE KATO-vs-new RECEIPT / transfer, 2 PG sessions | Deterministic lock order, no time-of-check bypass; order respects version/history (requires lifecycle implementation) |
| P04-Q20 | Transaction rollback after update or CREATE audit failure; concurrent IAM permission revocation | No partial location/audit changes; revocation fenced, denied future writes |

**Проверять на PostgreSQL, не только H2**: row locks, exact-tenant isolation, CAS/hibernate emitted SQL, FK/trigger behavior, Unicode. По каждому кейсу: test method, source HEAD, pre/post DB snapshots, audit count, number of rows, no skip, proper transaction boundary. Существующие тесты `OrganizationLocationQueryQualificationTest`, `KatoTerritoryQualificationTest`, `InventoryQ123QualificationTest` и `InventoryReservationQualificationTest` запускать в регрессии; они не покрывают новый UPDATE автоматически.

## 9. Минимальная карта реализации (после завершения проектных проходов)

| Место | Изменение | Запрет |
|---|---|---|
| `dto/LocationCreateRequest`, `dto/LocationUpdateRequest` | Ровно два typed records | Generic PATCH library, новые JSON DTO-контроллеры |
| `AuthenticatedOrganizationLocations` | Два overload: typed create и update; legacy create delegate | Передача caller-provided actorId |
| `OrganizationLocationService` | Общий normalize/validate create+update, tenant IAM, scoped lock, version gate, KATO history, audit | `findById` без tenant, прямой update stock |
| `OrganizationLocationRepository` | `lockByWorkspaceIdAndId` с `@Lock(PESSIMISTIC_WRITE)` | Новый global repository/list-all mutations |
| `OrganizationLocationEntity` | Package-domain `applyProfile`, private fields, @Version | Public setters для tenant/type/status/createdAt |
| `KatoLocationPolicy` | Повторно использовать current GEO admission при **новом** KATO | Парсинг KATO как числа или собственная копия GEO каталога |
| `InventoryLocationUsagePort` (согласованный 03) | Дополнить или использовать компактные history flags для changed KATO | Второй складской движок/дубликат ledger |
| `crm.audit_event` | Create/Update action-code в общей TX | Новый необоснованный audit event store |

**Решение 04:** полный profile replacement; tenant-scoped locked JPA + @Version CAS; изменение KATO только до первого исторического складского события; name/address correction разрешена при stock; no-op без audit/version delta; create без обещания exactly-once. Зрелость всего ORGANIZATION **1/5**, проход 04/12 **SPECIFIED / NOT IMPLEMENTED**.

**Следующий проход 05/12:** INVENTORY-интеграция; проверка `StockEligibility`, Q7 transfer и складских запретов при INACTIVE вместе с ORG lifecycle, с PG concurrency qualification и внутренними API без контроллеров.
