# RM-INVENTORY-FUNCTIONAL-COMPLETION-R1 — углублённое ревью и ТЗ развития INVENTORY

**Тип:** специализированное детальное ТЗ + 12-проходный перекрёстный аудит одного домена.  
**Дата / ветка / source SHA:** 2026-11-22 · `base_dev` · `c776baec5c82e0dfd4c7930aa6a68629202fa5fd`.  
**Статус:** INV-Q1–Q3 `POSTGRESQL QUALIFIED` (§15); INV-Q4–Q9 `IMPLEMENTED / CI QUALIFICATION PENDING` (§16); INV-VAL остаётся `HOLD`. Любой production admission — HOLD.  
**Цель:** зрелый *количественный* складской сервис с завершённым сценарием кладовщика и безопасной интеграцией SALES, CATALOG, ORG, IAM — без общего CRUD-framework, новых микросервисов, HTTP-контроллеров и самодельного workflow-engine.  
**Смежные документы:** `RM-PLATFORM-STANDARD-R2.md` (нормативный), `RM-SVC-FUNCTIONAL-DEPTH-AUDIT-2026-11-22.md` (обобщённый аудит), `RM-TENANT-SERVICES-COMPLETION-R1.md`, `RM-INVENTORY-VALUATION-R1.md`, `RM-SERVICE-FIRST-SALES-INVENTORY-R1.2.md`, `RM-SVC-R2-SERVICE-COMPLETION-CROSS-DOMAIN-CONSISTENCY.md`. Новый документ **не отменяет** иммутабельные согласованные механизмы reserve/fulfill, DDL 0022–0030 или dual-permission SALES+INVENTORY. При противоречии — нормативный стандарт R2 и принятое доменное решение выше проекта.

## 1. 12 проходов — от частной операции к общей системе

| № | Независимая грань проверки | Конкретный вывод по HEAD | Состояние |
|---:|---|---|---|
| 01 | Рабочий сценарий кладовщика | Есть receive/correct/stock search; перед первым поступлением приходится создавать stock row напрямую SQL. Нет write-off через trusted facade, movement history, transfer/stocktake | **GAP** |
| 02 | Список классов/методов и «пустые функции» | В 12 inventory Java-файлах реализованы SQL, domain logic либо DTO; массовых пустых бизнес-методов не обнаружено. `StockPosition.available()` — содержательная формула, фасады — тонкая осмысленная граница. Однако существуют *ложно работающие настройки*, а не пустые тела | **PARTIAL** |
| 03 | Quantity model / DDL / FK | `crm.inv_stock_balance` с PK `(tenant_id,location_id,item_id)`, `on_hand>=reserved>=0`; composite FK к ORG/CATALOG. Нет бизнес-проверки *ACTIVE / WAREHOUSE / PRODUCT* только из факта FK | **GAP** |
| 04 | Получение/коррекция/списание | `receive/correct` реальны, требуют permissions и reason, пишут balance+immutable ledger; корректировка может быть ±. `WRITE_OFF` есть во внутреннем enum, но нет отдельной безопасной staff-команды | **PARTIAL** |
| 05 | Поиск/остаток/история | `StockBalanceRepository.search` tenant WHERE, location/item/available filter, stable order, bounded pages. Нет get-position, movement history и reconciliation projection | **PARTIAL** |
| 06 | Reserve/release/fulfill | Полная строка заказа — ровно один резерв, atomic dual-role SALES+INVENTORY; неизменяемые events и `FULFILLMENT` ledger; нет split/multi-location, возвратов и складского picking lifecycle | **R1 CORE VERIFIED** |
| 07 | Настройки и фактическое применение | `updatePicking` пишет `picking_strategy`/`auto_reserve_on_confirm`; `SalesInventoryCommandService.confirm` не читает ни одно поле и **всегда** делает reserve. `approveCostPolicy` лишь утверждает metadata | **P0 MISMATCH** |
| 08 | Междоменные зависимости | STOCK fk на ORG location / CATALOG item, но состояние/тип не проверяется перед receipt/reserve; SALES offer→SKU может быть NULL для старых офферов, checkout не гарантирует stockable offer | **GAP** |
| 09 | Tenant/RBAC/доверенная граница | `AuthenticatedInventoryCommands/Queries` получают VerifiedActor, writes и read проходят IAM; `InventoryTenantSettingsService` остаётся public c raw UUID actor, `get()` использует `require` внутри `readOnly` без revocation fence | **P0 BOUNDARY** |
| 10 | Конкурентность/идемпотентность/восстановление | Request-key stripes, stock-row locks, no oversell, immutable movement, forced rollback and races у SALES есть; не проверены будущие transfer×reserve, stocktake×receipt, настройка×confirm | **QUALIFIED CORE / GAPS NEW** |
| 11 | Себестоимость/учёт/дата | FIFO/MWA/SPECIFIC *политика* существует, cost layers/valuation events отсутствуют; с новым receipt после approved policy всё ещё не появляется COGS. FEFO требует отсутствующего lot/expiry | **OUT OF R1 QUANTITY** |
| 12 | Тесты/admission/evolution | 4 inventory test classes + SALES/ORG negative and concurrency suites, PostgreSQL triggers и Green CI на SHA. Нужны E2E реального user journey, API boundary и негативные тесты новых операций; production HOLD | **PARTIAL** |

**Принцип закрытия каждого из 12 проходов:** evidence = исходный метод/SQL + конкретный тест + exact SHA + решение в данном ТЗ. «Прочитано» не равно `IMPLEMENTED`; новый цикл после реализации будет подтверждать статус каждой грани. Не создавать 12 независимых архитектурных реализаций.

## 2. Существующий код: не заменять и не переписывать

| Элемент | Реальный контракт / след исполнения | Решение |
|---|---|---|
| `AuthenticatedInventoryCommands.receive/correct` | Trusted principal → `InventoryAuthorizedCommandService` → permission `INVENTORY_RECEIVE/ADJUST` → immutable `crm.inv_stock_movement` | **KEEP**, добавить явную валидацию склада/SKU, возможность первого приёма |
| `InventoryMovementService.adjust(...)` | Request stripe → stock `FOR UPDATE` → balance mutation + movement (1 DB transaction); mismatch idempotency key → отказ | **KEEP**, расширить provenance и причины без общего CRUD |
| `AuthenticatedInventoryQueries.search` / `StockBalanceRepository.search` | Tenant predicate, location/item/availableOnly, count+bounded page | **KEEP**, добавить точечные `getPosition`/ledger queries |
| `InventoryReservationService.reserve/release/fulfill` | full order line + same SKU, stock row lock, one reservation/line, event and fulfillment movement | **KEEP**; расширение только through paired `OrderInventoryPort` |
| `OrderInventoryPort` | Two distinct transaction-bound IAM decisions SALES+INVENTORY, same actor+tenant | **KEEP**, никакого публичного `reserve(UUID, UUID)` |
| `InventoryTenantSettingsService` | Quantity-unrelated picking preference, deferred autoReserve flag, immutable valuation policy revisions | **RESTRICT** misrepresented settings, boundary hardening, do not advertise FIFO costing |
| `InventoryTenantSettingsQualificationTest` | Persistence/ver/version/immutable policy tested, **не** consumer behavior | **EXTEND** with cross-domain actual effect tests |
| `SalesInventoryRbacQualificationTest` | Confirm/cancel/fulfill atomicity, adverse permissions, revoked-role race, cancel-vs-fulfill and rollback | **PRESERVE**; add new constraint/setting cases |
| `OrganizationLocationService` | Location `create`, status ACTIVE, org tenant ownership; lifecycle not complete | **DEPENDENCY:** ORG-01, not a second warehouse master |
| `CatalogItemService` / `AutoOrderService` | PRODUCT/SERVICE kind & DRAFT/PUBLISHED/ARCHIVED, published `store_offer` still separate; `catalog_item_id` nullable for legacy offers | **DEPENDENCY:** CATALOG/STOREFRONT/SALES mapping admission |

### Почему нельзя просто «закрыть CRUD»

Остаток — **производная позиция**, изменяется только через проводимые движения и резерв. `deleteStock`, `updateStock(onHand=...)`, универсальный `save(entity)` и общий `InventoryCrudService` **запрещены**. Для коррекции существует проводка с причиной, автором и request key.

## 3. Идентифицированные дефекты: severity, влияние, решение

| ID | Priority | Root cause / проверяемое место | Последствие | Минимальное исправление |
|---|---|---|---|---|
| INV-F01 | **P0** | `InventoryMovementService.adjust` выбрасывает `"Stock position not initialized"`; в тестах `inv_stock_balance` создают raw SQL | Новый SKU невозможно оприходовать из прикладного сценария | Idempotent create-zero position **в receive**, проверив ORG/CATALOG и бизнес-статус; correction не создаёт |
| INV-F02 | **P0** | `InventoryTenantSettingsService.updatePicking` сохраняет `auto_reserve_on_confirm`, но SALES confirm всегда резервирует full lines; default FALSE | Настройка лжёт пользователю; ошибочное управление заказами | **DEC-INV-01:** в R1 always reserve, сделать поле legacy/deprecated read-only, миграция/согласование UI; не делать optional reservation в R1 |
| INV-F03 | **P0** | `picking_strategy` допускает `FEFO`/`MANUAL`, хотя нет lot/expiry и picker workflow | FEFO/FIFO физически не выбирает партии | Снять с активного UI и из изменяемого R1 command; хранить legacy значение, маркировать `NOT_APPLICABLE` до lot R2 |
| INV-F04 | **P0** | INVENTORY использует FK `(tenant, location/item)` без CHECK по `org_location.status/location_type` и `cat_item.kind/state` | Движение возможно на закрытой локации или SERVICE, если balance уже существует | Shared internal `StockEligibility` adapter; запись только по валидным owner rows, row lock/fence, exact typed errors |
| INV-F05 | **P0** | `InventoryTenantSettingsService` public methods `UUID authenticatedActorId`, `get()` — `authorization.require` в `@Transactional(readOnly=true)` | Неправильная доверенная точка входа/отличается revoke semantics | `AuthenticatedInventorySettings(VerifiedActor,tenant)`, raw settings service package-private, fenced read per established R2; архитектурный тест |
| INV-F06 | **P1** | `inv_stock_movement` / `inv_reservation_event` не содержат `actor_user_id`; причины `RECEIPT/CORRECTION` без инициатора | Не восстановить, кто провёл приход/списание; аудит неполон | Additive `actor_user_id` nullable для исторических записей; обязателен для новых staff writes; SALES principal переносится through paired port; legacy unknown не переименовывать |
| INV-F07 | **P1** | `Reason.WRITE_OFF` есть внутри, наружу доступны только receive/correct | Нельзя различить ответственное списание и безликую отрицательную коррекцию | Отдельная `writeOff` команда с обоснованием-кодом, `INVENTORY_ADJUST` в R1, event + actor |
| INV-F08 | **P1** | Нет transfer, stocktake service / DDL; один request key на movement | Склады не могут перемещать и пересчитывать товар в приложении | Две предметные транзакционные команды с малой evidence-моделью; reuse balance/ledger |
| INV-F09 | **P1** | Нет `getPosition`, `searchMovements`, `reservationHistory` authorised query | Ledger физически существует, но кладовщик не может отследить движение | Bounded tenant-scoped queries с `INVENTORY_STOCK_READ`, reasons/source IDs, audit projection |
| INV-F10 | **P1** | Approved `inv_cost_policy_version` metadata без получения `unit_cost`, cost layers, COGS | «Метод оценки утверждён» ошибочно воспринимается как выполнение стоимости | Возвращать readiness `POLICY_ONLY`, до INV-VAL-R1.2 запрет финансовых claims/exports; стоимостные события отдельно |
| INV-F11 | **P1** | `fulfill-<reservationId>` служебный movement request key и пользовательский requestKey делят unique `(tenant_id,request_key)` | Возможен конфликт имен служебного и пользовательского movement key | Зарезервировать служебные prefix и проверить существующие данные; внешний input не принимает internal prefixes |
| INV-F12 | **P1** | `org_location` нельзя deactive/reactivate; CATALOG offer→PRODUCT mapping не гарантирован на checkout | ORG может нарушить складской lifecycle; заказ может быть не резервируем | Owner-port admission + отдельные ORG/CATALOG companion tasks, без прямых cross-domain updates |
| INV-F13 | **P2** | Нет cost layers/lot expiry и automatic dispatch/warehouse picker | Нельзя давать обещание FEFO или FIFO себестоимости | Отдельная финансово-складская фаза `INV-VAL-R1.2`, не блокирует quantity-R1 при честной маркировке |

**Severity не означает эксплуатируемую уязвимость.** P0-F05 — нарушение стандарта будущего транспортного фасада, а не доказанный внешний обход, поскольку бизнес HTTP пока не открыт. P0-F02/F03 — business correctness mismatch, не повреждение текущих резервов.

## 4. Консолидированные бизнес-правила INVENTORY-R1Q

### 4.1 Основная модель

- \`tenantId = iam_workspace.id\`; склад = \`org_location.id\` в том же tenant; SKU = \`cat_item.id\` в том же tenant; ни склад, ни регион, ни salesperson не новый tenant.
- Для каждой позиции \`onHand >= reserved >= 0\`, \`available = onHand - reserved\`; \`NUMERIC(19,4)\`, \`BigDecimal\`, quantity strictly positive for receive/transfer/write-off/stocktake counts; корректировка signed \`delta != 0\`.
- Первоначальная позиция создаётся **незаметно для пользователя, но не без следа**: \`receive\` проверяет actor, location, item, затем \`INSERT ... ON CONFLICT DO NOTHING\` нулевой позиции, затем вызывает прежний ledger \`adjust\`. Нет фиктивного движения нулевого количества; первый реальный приход создаёт движение.
- На запись допустим \`org_location.status=ACTIVE\` и \`location_type=WAREHOUSE\` для R1Q. **DEC-INV-02:** разрешать ли \`POINT_OF_SALE\` как stock location; безопасный default — нет, пока не принят процесс POS. \`BRANCH\` без складской функции — не склад. Проверить current code и backwards populated data прежде enforcement.
- На приёмку разрешён \`cat_item.kind=PRODUCT\` при \`state=DRAFT|PUBLISHED\` (поставку можно принять до публикации). \`SERVICE\` и \`ARCHIVED\` — запрет новых приёмок; для архивных остатков историческое чтение разрешено. Reservation требует published offer, SKU mapping и \`PRODUCT\`. Отдельная политика для перемещения архива/утилизации — DEC-INV-03.
- До финальной миграции прояснить legacy stock balances с non-WAREHOUSE, SERVICE или ARCHIVED; **не** auto-delete и не переписать immutable ledger.

### 4.2 Настройки: выбрать работающий, а не красивый вариант

**Рекомендуемое DEC-INV-01: R1 = MANDATORY_RESERVE_ON_CONFIRM.**
- \`SalesInventoryCommandService.confirm\` **всегда** атомарно создаёт full-line reservation. Именно это уже работает, покрыто гонками и является обязательным условием \`fulfill\`.
- \`auto_reserve_on_confirm\` с default FALSE в \`inv_tenant_setting\` — **deprecated nonfunctional legacy field**. Прекратить принимать изменение через business API; UI показывает \`reservationMode=MANDATORY\`, не FALSE. Физическое поле удалить/мигрировать только в отдельной additive/backfill фазе, сохранив совместимость.
- \`picking_strategy=FIFO|FEFO|MANUAL\` сейчас **не влияет** на реальный выбор партий. В quantity-R1 не предлагать изменить настройку как реально действующую. \`FEFO\` нельзя реализовать без lot/expiry/batch; не строить lot engine в этом цикле. Поле может остаться исторической конфигурацией до отдельного R2.
- \`allow_negative_stock\` остаётся FALSE, editable toggle **не создавать**.
- \`approveCostPolicy\` — хранение утверждённой policy revision, а не запуск расчёта. Отдельный status \`valuationReadiness=POLICY_ONLY\`, если нет qualified ledger. Никогда не создавать COGS из \`COMPLETED\` или одной цены SALES.
- Если бизнес утвердит опциональный резерв, это **другая вертикаль**: order lifecycle, release/fulfill semantics, доступность и retry state должны быть перепроектированы и заново квалифицированы; в текущем ТЗ не включать.

### 4.3 Движения

- \`receive(actor, tenant, location, item, requestKey, qty)\`: only ACTIVE warehouse + PRODUCT + sufficient precision; create zero balance if missing; post positive \`RECEIPT\`; duplicate same payload -> same receipt; key with different tenant/payload -> appropriate scope/conflict, no mutation.
- \`correct(..., delta, reasonCode, requestKey)\`: only existing balance, signed nonzero, reasonCode from bounded allowed vocabulary, no onHand below reserved. Исправление +3 или -3 фиксируется как отдельная запись, старые события не редактировать.
- \`writeOff(..., qty, reasonCode, requestKey)\`: positive input; create negative ledger \`WRITE_OFF\`, no indirect order changes; require \`INVENTORY_ADJUST\` in R1; reason for damage/loss/expiry with domain-code, no PII free text.
- \`getPosition\`, \`searchMovements\`, \`reservationHistory\`: typed DTO, hidden/foreign IDs yield not-found/empty without leaking count, finite page 1..100 and stable \`recorded_at DESC,id DESC\`; no direct repository transport.
- Each new movement has \`actorUserId\` (or well-defined service identity), \`sourceType/sourceId\`, \`reasonCode\` (typed, not arbitrary SQL), and immutable after committed. For old movements \`actorUserId=null\` means **unknown**; do not backfill with synthetic owner.

### 4.4 SALES reservation and fulfillment

- Keep full-line one-warehouse reservation in R1, no partial picking and no mixed seller tenants.
- Validate selected warehouse ACTIVE+eligible and each SKU stockable before changing order status. Existing order/line locks and deterministic SKU order remain; authorizations SALES_ORDER_CONFIRM+INVENTORY_RESERVE must both be transaction-bound.
- Retry confirm after commit checks exactly same reservations/location; user cannot resubmit with different warehouse. Terminal release/fulfill idempotent, conflicting terminal transitions fail.
- Fulfill reduces \`onHand\` and \`reserved\` once, emits one \`FULFILLMENT\` movement; cancel releases reserves; **quantity fulfillment != payment/financial posting**.
- \`AutoOrderService.checkout\` consumer risk: provisional \`store_offer.catalog_item_id\` may be null (legacy schema permits). This can create PLACED order not fulfillable from stock. Fix acceptance at CATALOG/STOREFRONT published-offer boundary, and for legacy orders return explicit \`UNMAPPED_OFFER\` at confirm, never silently choose another item.

## 5. ТЗ на новые и доработанные внутренние сервисные контракты

Синтаксис ниже — **проект Java interface**, не готовые Java declarations. Существующие методы сохраняются, если их изменение нарушит квалифицированный R1 flow.

| Контур / предложенный метод | Permission, граница | Критическое поведение |
|---|---|---|
| \`AuthenticatedInventoryCommands.receive(actor,tenant,location,item,key,qty)\` | \`INVENTORY_RECEIVE\` | Validate eligibility, create-zero position on first receive, one receipt + audit, stock proof |
| \`AuthenticatedInventoryCommands.correct(actor,tenant,location,item,key,delta,reason)\` | \`INVENTORY_ADJUST\` | Require existing row, CAS/stock row lock, signed delta, immutable event |
| \`AuthenticatedInventoryCommands.writeOff(actor,tenant,location,item,key,qty,reason)\` | \`INVENTORY_ADJUST\` | Separate explicit negative posting, no hidden balance set |
| \`AuthenticatedInventoryCommands.transfer(actor,tenant,src,dst,item,qty,key)\` | \`INVENTORY_ADJUST\` (R1) | Same-tenant two-location atomic transfer, provenance, deterministic lock order |
| \`AuthenticatedInventoryCommands.startStocktake(actor,tenant,location,scope,key)\` | \`INVENTORY_ADJUST\` | Snapshot expected versions/quantities, no automatic stock lock for full counting session |
| \`AuthenticatedInventoryCommands.recordCount(actor,tenant,stocktake,item,qty,expected)\` | \`INVENTORY_ADJUST\` | Recording physical count only, no immediate balance mutation |
| \`AuthenticatedInventoryCommands.postStocktake(actor,tenant,stocktake,key)\` | \`INVENTORY_ADJUST\` | In one bounded transaction, compare stock rowVersion, post deltas with \`STOCKTAKE\` ledger |
| \`AuthenticatedInventoryQueries.getPosition(actor,tenant,location,item)\` | \`INVENTORY_STOCK_READ\` | onHand/reserved/available, rowVersion; no fake valuation |
| \`AuthenticatedInventoryQueries.searchMovements(actor,tenant,filter,page,size)\` | \`INVENTORY_STOCK_READ\` | Location, SKU, time, reason, actor, source, stable paging |
| \`AuthenticatedInventoryQueries.reservationHistory(actor,tenant,order/line,filter)\` | \`INVENTORY_STOCK_READ\` **DEC-INV-04** | Historical metadata only; SALES may need \`SALES_ORDER_READ\` via typed owner port |
| **New** \`AuthenticatedInventorySettings.get/initialize(...)\` | \`INVENTORY_SETTINGS_MANAGE\` | Trusted \`VerifiedActor\`; fenced read; only actual supported R1 settings |
| **Internal** \`StockEligibility.require(...)\` | Already authorized caller, no direct transport | Lock/check ORG location + CATALOG PRODUCT + business status + same tenant |
| **Internal** \`InventoryMovementService\` | Only authenticated command caller | Reuse existing locked balance and movement core, internal ledger/admission |

Do **not** add generic \`InventoryService.save()\`, \`delete()\`, repository exposure, permissions per warehouse as separate hardcoded roles, or a universal \`InventoryWorkflowEngine\`.

## 6. Transfer — минимальная, но полноценная реализация

**Model:** \`crm.inv_stock_transfer(tenant_id,id,request_key,request_hash,source_location_id,target_location_id,item_id,qty,posted_at,posted_by)\` с composite warehouse/item FKs (точную FK topology проверять в migration). Два неизменяемых движения \`TRANSFER_OUT\` и \`TRANSFER_IN\` в существующем \`crm.inv_stock_movement\` с \`transfer_id\` и парой \`sourceType/sourceId\`. Не заводить третий актуальный остаток.

**Invariants:**
- \`source != target\`, оба same tenant, ACTIVE WAREHOUSE; \`qty>0\`, существующая исходная позиция, исходный \`available>=qty\`; target zero position may be lazily created.
- Lock idempotency/request stripe **before** stock rows; обе stock-позиции lock в детерминированном порядке \`(tenant,location,item)\`. Если existing requestKey совпадает fingerprint — вернуть тот же transfer receipt; другой payload — \`IDEMPOTENCY_CONFLICT\`.
- \`source.onHand -= qty\`, \`target.onHand += qty\`; \`reserved\` на обеих не меняется. Atomic two-ledger entries in one DB transaction; \`sum(delta_quantity)=0\` для пары.
- Существующий \`uk_inv_stock_movement_request(tenant_id,request_key)\` не позволяет обеим ногам один ключ: внутренние keys генерировать детерминированно из \`transfer.id + /out,/in\`, уникальными и ограниченными до 100 символов, с зарезервированным системным namespace. User requestKey хранится только в transfer receipt; при rollback обе ноги исчезают.
- Existing \`movement_kind\` check допускает лишь \`ADJUSTMENT/FULFILLMENT\`. В R1 можно использовать \`ADJUSTMENT\` + явные \`TRANSFER_OUT/TRANSFER_IN\` reason и \`transfer_id\` с constraint parity; *либо* additive расширить enum CHECK до \`TRANSFER\` с тестами PG upgrade. Не переписывать changeset 0025.
- Нет внешнего движения между tenants, нет скрытой стоимости/FX/COGS. Для valuation позже сохранить cost provenance, не выдумывать экономическую стоимость из продажной цены.

## 7. Stocktake — минимальная физическая инвентаризация

**Model:** \`crm.inv_stocktake(tenant,id,location,status,started_by,started_at,posted_at,request_key)\` и \`crm.inv_stocktake_line(tenant,stocktake,item,expected_version,book_on_hand,book_reserved,counted_on_hand,delta,status)\`. Никакого отдельного ledger; изменения только через существующий movement writer с новым reason \`STOCKTAKE_ADJUSTMENT\`.

Flow:
1. \`startStocktake\` на ACTIVE склад и отобранные позиции; snapshot \`onHand,reserved,rowVersion\`, состояние \`OPEN\`. Не держать транзакцию на весь период подсчёта.
2. \`recordCount\`: физическое количество >=0; изменяет только entry подсчёта, \`stock balance\` не трогает.
3. \`postStocktake\`: весь **ограниченный batch** в одной транзакции, lock location+SKU balance в stable order; проверить rowVersion = captured version. Если другой приход/резерв/выдача успел изменить позицию — \`CONFLICT / RECOUNT_REQUIRED\`, никаких поправок по старому snapshot.
4. \`delta=countedOnHand - bookOnHand\`; \`delta=0\` = \`NO_CHANGE\` (событие закрытия строки без нулевого stock movement). Nonzero → журнал с причиной \`STOCKTAKE_ADJUSTMENT\`. Если \`countedOnHand < reserved\`, отказ, не затирать резерв.
5. Повтор \`postStocktake\` с тем же requestKey возвращает тот же receipt, иной payload — конфликт; snapshot старый, а изменения совершены — запрашивать новый подсчёт, не мигрировать историю.
6. \`cancelStocktake\` до POSTED с audit. Если количество SKU велико — лимит batch, затем позже partial posting с отдельным предметным контрактом, не «универсальный» batch engine.

**Не делать:** auto stock freeze на весь tenant, RFID/serial/lot, инвентаризация в денежных единицах, произвольный UPDATE on_hand.

## 8. Междоменные контракты, кто владеет решением

| Provider → consumer | Текущая связь | Требуемое поведение | Кто меняет код |
|---|---|---|---|
| **IAM → INVENTORY** | Shared \`TenantAuthorization.requireForCommand\`, DomainPermission, VerifiedActor через 2 facades | Settings тоже через trusted facade; revoked account/role fence на writes/read; actor A/B/AB | INVENTORY facade; IAM unchanged |
| **ORG → INVENTORY** | \`inv_stock_balance.(tenant_id,location_id)\` FK → \`org_location.(workspace_id,id)\` | ACTIVE eligible stock location before initial receive/reserve/transfer; deactivate blocked при >0 stock/active reserve. No duplicate warehouse table | ORG validates lifecycle; INVENTORY validates its operations |
| **CATALOG → INVENTORY** | composite SKU FK to cat_item | PRODUCT only for stock, SERVICE reject; DRAFT may receive, PUBLISHED+mapped offer for reserve; archive does not delete historical ledger | CATALOG owns item state; INVENTORY checks typed owner |
| **STOREFRONT → SALES → INVENTORY** | offer ID → optional catalog_item_id; SALES confirm uses paired OrderInventoryPort | published price snapshot remains SALES-owned, one seller/warehouse full-line reservation, no double commit, unmapped legacy order fails typed error | Storefront/SALES own offer/order; INVENTORY owns quantity changes |
| **INVENTORY → FINANCE** | No finance invoice/payment service for inventory economics | Only qualified immutable quantity facts; no COGS/cost export until INV-VAL-R1.2. Financial invoice amount ≠ inventory cost | FINANCE later via typed receipt |
| **GEO → ORG → INVENTORY** | KATO validates location creation | No direct KATO ownership or geography-based tenant policy, status/type taken from ORG | ORG |
| **AUDIT → INVENTORY** | Ledger/reservation events immutable | actor/source metadata for all staff writes; bounded sanitized history, no retention purge of stock ledger | INVENTORY owns ledger; AUDIT retention unchanged |

**Cross-domain database writes запрещены**, кроме существующей одобренной SALES/INVENTORY orchestration в одной транзакции. Не заводить `WarehouseEntity` как копию `OrganizationLocationEntity`.

## 9. Безопасность и права — строго используем существующую модель

| Operation | Existing permissions | Extra object guard |
|---|---|---|
| balance get/search, movement read | \`INVENTORY_STOCK_READ\` | tenant filter, no hidden cross-tenant count |
| receive | \`INVENTORY_RECEIVE\` | ACTIVE WAREHOUSE, PRODUCT, same tenant |
| correct / writeOff / transfer / stocktake | \`INVENTORY_ADJUST\` | existing stock, valid cause, version, physical constraints |
| owner settings | \`INVENTORY_SETTINGS_MANAGE\` | VerifiedActor + tenant context; no UUID actor external |
| SALES confirm/cancel/fulfill | respective SALES and INVENTORY paired permissions | current transaction permit + same actor/tenant/order/stock |
| reservation history as INVENTORY-only | \`INVENTORY_STOCK_READ\` (DEC-INV-04) | order linkage is valid same tenant; if read through SALES require SALES_ORDER_READ |
| per-warehouse assignment | **DEC-INV-05 (not implemented)** | default tenant-wide for grant holder; do not claim portfolio ACL exists |

No new \`INVENTORY_TRANSFER\`/\`STOCKTAKE\` permission in R1 without real privilege separation; tenant can assemble configurable clerk roles from existing grants. \`TENANT_RBAC_MANAGE\` still OWNER-only; no `OWNER=ALL`.

**Explicit trust rule:** public application methods accept \`VerifiedActor actor\`; internal \`UUID actorId\` methods are package-private and never transport handlers. Settings \`get\` must match revocation safety of \`AuthenticatedInventoryQueries.search\`; PostgreSQL read-only locks require tested transaction semantics, not simply renaming an annotation. \`@Transactional\` at verified facade must enclose admissions and writes.

## 10. PostgreSQL / Liquibase migration design

1. New changesets only, named and ordered after current HEAD; never edit 0022–0030, PK or triggered immutable historical values.
2. Metadata additions to \`inv_stock_movement\`, \`inv_reservation_event\`: optional historical \`actor_user_id\`, typed source reference for future events, indexes by tenant/location/item/time; after migration, **new** service writes must provide actor/source. Zero historical actor = unknown.
3. For transfer, one header receipt with tenant-local unique requestKey+payload fingerprint and FK to both ORG locations and item, plus \`transfer_id\` linkage on two ledger rows; CHECK source != target, qty >0 and exactly-one-pair proof at service/qualification level. No global unique SKU.
4. For stocktake, two small tenant-owned tables with status and line version; only necessary indexes \`(tenant_id,location_id,status,id)\`; one-time data migration not required for preexisting positions.
5. Optional index for \`crm.inv_stock_movement(tenant_id,item_id,recorded_at DESC,id DESC)\` **only after EXPLAIN** of new queries on populated PG.
6. For existing rows with non-stockable locations/service items, generate read-only **preflight discrepancy report**; do not blindly fail upgrades or delete stock. Block new invalid operations after approved migration and reconciliation.
7. SETTINGS legacy columns \`auto_reserve_on_confirm\`/picking remain physically until compatibility mapping is qualified; cannot rewrite old Liquibase checksum. Cost policy remains immutable; disable deceptive activation/valuation claim, not history.
8. Confirm technical DB role read/write permissions, trigger immutability, concurrent upgrade/backfill and restore on populated PostgreSQL.

## 11. Реализация по малым инкрементам

| ID | Prior. | Narrow outcome | Dependencies | Exit evidence |
|---|---|---|---|---|
| **INV-Q0** | P0 | Утверждённые DEC-INV-01/02/04, exact-head inventory method map and explicit R1 quantity boundary | R2 standard, TCRUD-00/03 | Normative decision + 12-pass issue states |
| **INV-Q1** | P0 | `AuthenticatedInventorySettings`; raw UUID boundary closed; consistent revocation reads | IAM/TCRUD-04 patterns | reflection + 2-tenant role-revoke PG |
| **INV-Q2** | P0 | First legitimate receipt for new SKU, typed location+PRODUCT admission; `correct` on absent position rejects | CATALOG/ORG owner adapter, DDL unchanged if possible | New-SKU E2E, wrong location/product, concurrent first receive |
| **INV-Q3** | P0 | Remove false effective settings from API; always-reserve semantics preserved; reconcile defaults | DEC-INV-01 | Changes in config cannot silently change stock; reserve E2E still GREEN |
| **INV-Q4** | P1 | Movement actor/reason/source audit, reserved system requestKey namespace | New additive SQL | Immutable provenance tests, no lost/duplicate movement |
| **INV-Q5** | P1 | `getPosition`, `searchMovements`, `reservationHistory`; bounded + tenant-safe | Q4 for source fields | count/page/foreign ID/as-of and history tests |
| **INV-Q6** | P1 | Authenticated `writeOff` (correct negative still supported) | Q2/Q4 | no below-reserved, one write-off event, reason |
| **INV-Q7** | P1 | Atomic 2-location transfer | Q2/Q4, ORG active checks | 2 ledger legs, net=0, 2-thread inverse transfer, retry |
| **INV-Q8** | P1 | Stocktake start/count/post/cancel with rowVersion verification | Q2/Q4, Q7 lock order | actual physical count, stale snapshot rejects, no partial |
| **INV-Q9** | P1 | Full warehouse E2E and source↔consumer validation with SALES | CATALOG/STOREFRONT published offer contract | receive→sell→reserve→fulfill→movement; no payment fiction |
| **INV-VAL-R1.2** | P2 / separate | Cost layers, receipt financial data, MWA/FIFO actual engine, COGS | Independent methodology & migration | numerical financial golden set and FIN contract |

**Делать Q0→Q3 прежде Q7/Q8.** Независимые Q4/Q5 могут идти после P0; не создавать все сервисы до устранения false settings/first receipt defects. Если при реализации каскад с SALES меняет исторический flow, начать с rollback-safe transaction qualification и оставить существующее поведение неизменным по умолчанию.

## 12. Обязательная матрица приёмки (на реальном PostgreSQL)

| Case | Сценарий и данные | PASS condition |
|---|---|---|
| INV-E2E-01 | New ACTIVE WAREHOUSE, DRAFT PRODUCT, balance absent, receive 10 | Position automatically zero-initialized; onHand=10, reserved=0, exactly one RECEIPT with actor |
| INV-E2E-02 | Duplicate requestKey same payload / different qty | Exactly one movement and same receipt / typed conflict, no duplicate |
| INV-E2E-03 | Two parallel first receives on same tenant/location/item | One position PK, both unique receipts, no lost update |
| INV-E2E-04 | Correct negative -8 when stock 10 reserved 3 | Refuse; onHand remains 10 and evidence unchanged |
| INV-E2E-05 | SERVICE item, foreign SKU, foreign tenant location | Deny; zero new position/ledger row |
| INV-E2E-06 | INACTIVE/CLOSED/BRANCH location | Deny new receipt/reserve; existing historical reads preserved |
| INV-E2E-07 | Actor A/B/AB with differing grants | Only granted tenant/business operation; no foreign ID count/page/history |
| INV-E2E-08 | Revoke/stale membership concurrent with receive/transfer/stocktake | No unauthorized admitted post-revoke write; row-lock fencing and rollback proved |
| INV-E2E-09 | SALES order 2 mapped PRODUCT lines, confirm, cancel | Exactly one reservation per line, cancel releases, replay does not change balances |
| INV-E2E-10 | Confirm with insufficient available stock on one of N lines | Rollback ALL order state, reserves and stock; no partial commit |
| INV-E2E-11 | Confirm twice / different location on retry | Original receipts, original warehouse; mismatch denied |
| INV-E2E-12 | Fulfill once, repeat, cancel race | One immutable FULFILLMENT movement per line; no oversell; terminal conflict deterministic |
| INV-E2E-13 | Setting autoReserve=false in preexisting DB, confirm | **Mandatory reserve still occurs** under approved R1 policy; no API claim of optional reservation |
| INV-E2E-14 | Set/request FEFO with no lot/expiry | Cannot claim FEFO is active; no fabricated lot selection |
| INV-E2E-15 | Transfer 4 from 10 source (reserved=3) to 0 target | onHand source=6, target=4, reserved unchanged; -4/+4 ledger with common transfer |
| INV-E2E-16 | Transfer 8 when available=7 | Refuse; zero transfer or movement rows |
| INV-E2E-17 | Two opposite transfers concurrently | No deadlock, deterministic locks, conservation, exactly-once receipts |
| INV-E2E-18 | Transfer failure after source debit before target credit | Full rollback; no source or target movement |
| INV-E2E-19 | Stocktake book 10, count 9, no concurrent writes | onHand=9, single -1 stocktake movement, status POSTED |
| INV-E2E-20 | Stocktake count 9 then concurrent receipt/reservation changes version | RECOUNT_REQUIRED, original stock untouched by outdated adjustment |
| INV-E2E-21 | Counted physical < reserved | Refuse post, keep counted evidence and unchanged balances |
| INV-E2E-22 | Movement/history 150 rows across 2 tenants | stable page 0/1, 1..100 size, count correct, no cross-tenant actor/source IDs |
| INV-E2E-23 | Reused user key with reserved `fulfill-` or transfer/stocktake prefix | Fail closed without blocking later fulfillment |
| INV-E2E-24 | PostgreSQL history trigger UPDATE/DELETE posted event | Denied; legacy nullable actor preserved |
| INV-E2E-25 | Populated DB before/after Liquibase, restore and query legacy movements | Same balances and immutable history, new adapter active only after gate |
| INV-E2E-26 | FX/cost policy already approved, quantity receipt without cost layers | Quantity updates but **no COGS/valuation success claim**, financial workflow not admitted |
| INV-E2E-27 | Archived SKU/location with old active reservations | Legacy historical references remain; new ops blocked, ORG deactivation policy prevents inconsistent closure |

**Тестовые группы:** `InventoryMovementQualificationTest`, `InventoryReservationQualificationTest`, `InventoryTenantSettingsQualificationTest`, `StockBalanceQueryQualificationTest`, `SalesInventoryRbacQualificationTest`, `OrganizationLocationQueryQualificationTest`, `Tcrud04VerifiedEntrypointQualificationTest`; новые \`InventoryTransferQualificationTest\`, \`InventoryStocktakeQualificationTest\`, \`InventoryLifecycleE2EQualificationTest\`. Текущие тесты **не** покрывают автоматически новые 27 случаев.

**Релизные gates:** H2 smoke, PostgreSQL two-connection concurrency & deadlock/lock-order, exact-head Liquibase populated upgrade and restore, no cross-tenant page/count, full rollback, actor provenance; no new controllers, no main merge, production HOLD. p95/p99 измерять только на реальной тестовой нагрузке, **не придумывать пороги как существующие метрики**.

## 13. Открытые решения и стратегия без усложнения

| Decision | Рекомендация для R1 | Кто согласует / когда |
|---|---|---|
| **DEC-INV-01** reservation mode | Всегда резервировать целиком при SALES confirm; ложные опции не показывать | Product + SALES/INVENTORY до Q3 |
| **DEC-INV-02** stock location types | В R1 только ACTIVE WAREHOUSE; POS отдельно, когда появится сценарий | Product + ORG до Q2 |
| **DEC-INV-03** архивный PRODUCT и остатки | Не принимать новые приходы; историческое движение/списание отдельно по reason; не удалять ledger | Product + CATALOG до Q6/Q7 |
| **DEC-INV-04** reservation history rights | INVENTORY_STOCK_READ для складского аудита; SALES_ORDER_READ при открытии через заказ | Security + SALES до Q5 |
| **DEC-INV-05** assigned warehouses | Пока все доступные склады *своего tenant* при permission. Не обещать per-warehouse portfolio ACL | Product, отдельный later milestone |
| **DEC-INV-06** stocktake approval | Один уполномоченный кладовщик с INVENTORY_ADJUST; dual-approval при ценных/крупных списаниях позже | Product, до Q8 |
| **DEC-INV-07** valuation activation | POLICY_ONLY until actual cost journal/COGS implemented and qualified; no accounting export | Finance method owner, separate INV-VAL gate |

### Нецели R1Q

Не внедрять FEFO/серийные номера/партии без утверждённого UX и schema contract; per-warehouse user ACL, межтенантные перемещения, произвольные частичные резервы, форсированный отрицательный остаток, произвольные правки движения, свой event-bus, универсальный approval engine или SQL UPDATE баланса через admin interface. Каталог цен не служит источником себестоимости.

## 14. Финальные критерии и следующий сервис

INVENTORY quantity-R1 может получить статус **FUNCTIONALLY MATURE / QUALIFIED** только после:
1. Новый clerk способен через trusted services: ACTIVE склад → первый SKU → receive → get/search → correct/write-off → order confirm/reserve/cancel/fulfill → movement history, не используя SQL fixtures.
2. Состояния/settings не вводят в заблуждение; выбранная reservation policy исполняется реально.
3. Tenant, actor, location status/type, PRODUCT eligibility, immutable actor evidence и повторные запросы проверены на заполненном PostgreSQL.
4. Transfer и stocktake атомарны и проверены двумя конкурентными транзакциями; zero data leak.
5. Рабочие тесты на exact SHA и review статусы позволяют обновить 12-проходный отчёт из `PARTIAL` в `QUALIFIED` **только для заявленной quantity области**.
6. Cost/lot/FEFO, production transport и финансовые проводки могут оставаться отдельно маркированными HOLD.

**После согласования и реализации INVENTORY:** использовать тот же 12-проходный протокол на следующем одном домене; по логике междоменных зависимостей разумный кандидат — **SALES**, но переключение только после явного завершения текущего INVENTORY цикла. Не объявлять следующий домен изученным настоящим документом.


## 15. Реализация INV-Q1–Q3 (2026-11-22)

**Рабочие бизнес-решения, принятые для текущей quantity-R1 вертикали:**
- DEC-INV-01 = `MANDATORY_ON_CONFIRM`: подтверждение SALES всегда делает полный резерв заказа в транзакции. Устаревшее значение `auto_reserve_on_confirm=false` остаётся физически в БД, **не** выключает reserve. Пользовательский VerifiedActor-фасад не позволяет переключить резервирование.
- DEC-INV-02 = только активный `org_location(location_type=WAREHOUSE,status=ACTIVE)` для новых приходов и резервов; BRANCH/POINT_OF_SALE запрещены до собственного бизнес-контракта. Ветку данных со старым stock не переписывать автоматически.
- DEC-INV-03 = `cat_item(kind=PRODUCT,state=DRAFT|PUBLISHED)` может принять приход; новая резервация требует `PUBLISHED PRODUCT`; `ARCHIVED` и `SERVICE` не принимают новых приходов или резервов. Существующие reserve/fulfill/release, исторические movements и exact-payload replay сохраняются даже после archive.

**Реализовано в `base_dev` (только Q1–Q3):**
1. `AuthenticatedInventorySettings` — три подтверждённых действия: `initialize/get/approveCostPolicy(VerifiedActor,...)`; operational DTO сообщает `MANDATORY_ON_CONFIRM`, `NOT_IMPLEMENTED` для физического picking, `POLICY_ONLY` для утверждённого cost policy (без фиктивного COGS). `InventoryTenantSettingsService` — internal/package-private; getter теперь использует IAM `requireForCommand` в write-capable транзакции.
2. `StockEligibility` — внутренний tenant-qualified admission владельцев: liveness склада, тип WAREHOUSE, PRODUCT, PUBLISHED при новом reserve. Locks owner rows while command runs, preventing concurrent owner mutation during check. Физические статусы проверяются *дополнительно* к составным FK.
3. `InventoryMovementService.adjust` — для `RECEIPT` после идемпотентного tenant-wide request stripe и owner admission создаёт нулевую `inv_stock_balance` только если отсутствует; затем существующая атомарная проводка. `CORRECTION` и прочие ручные изменения не создают missing stock.
4. `InventoryReservationService.reserve` — новая резервация проверяет ACTIVE склад и published PRODUCT, но committed idempotent replay проверяет существующую запись прежде повторной проверки owner state. Cancel/fulfill сохраняют действующий контракт SALES.
5. Архитектурный TCRUD-04 тест для settings и отдельные регрессии `InventoryQ123QualificationTest`, `InventoryReservationQualificationTest.q123ReservationRejectsInactiveWarehouseServiceAndArchiveButHonorsOldCommit`, `SalesInventoryRbacQualificationTest.q123LegacyAutoReserveFalseCannotDisableRequiredSalesConfirmationReserve`.

**Не менялось:** прежние Liquibase changesets, SALES order status contract, valuation policy journal, transport/controllers, other domains. No migrations required for Q1–Q3; прежние исторические настройки остаются в схеме, но не обещаются как действующие. Этапы Q4–Q9, INV-VAL, per-location ACL и полный TCRUD-03 остаются OPEN.

**Квалификация INV-Q1–Q3:** source/code SHA `d9819250089872ead3ddb869f821974041bd46a4`. GitHub Actions [RM Domain Security and PostgreSQL Qualification / run 44985067340](https://github.com/alimbetov/RM/actions/runs/44985067340): H2 smoke, PostgreSQL integration, populated IAM PII **SUCCESS**; обновлённый `InventoryQ123QualificationTest` **4 tests, 0 failures, 0 errors, 0 skipped** на PostgreSQL (включая 2-connection revoke/read race), `InventoryReservationQualificationTest` и `SalesInventoryRbacQualificationTest` — SUCCESS. Повторный run 44985071453 также SUCCESS. CRM populated upgrade run 44985067351 SUCCESS. RustFS E2E [run 44985067336](https://github.com/alimbetov/RM/actions/runs/44985067336) **SUCCESS** (инфраструктурная регрессия, не предметная логика INVENTORY).

**Квалифицированная область:** Q1 authenticated settings + fenced read; Q2 первое поступление, новый ACTIVE WAREHOUSE/PRODUCT admission и конкурентная первая запись; Q3 mandatory reservation при старом FALSE и честный operational DTO. Не принимать это за закрытие Q4–Q9, FIN valuation, всех существующих TCRUD-00/03 методов или production deployment. Из-за row-lock owner metadata проверить p95/p99 под нагрузкой до production; не выводить производительность из synthetic single-row test.


## 16. Реализация INV-Q4–Q9: кодовая вертикаль, статус до CI

**Branch `base_dev`**, фактическая реализация (source-based; точный квалифицированный SHA указывать *после* полного CI). Не пересматривать ранее принятые Q1–Q3 инварианты.

| Этап | Код | Предметный результат |
|---|---|---|
| **Q4** | `InventoryMovementService`, `InventoryReservationService`, `OrderInventoryPort`, migration 0070 | Новые ручные движения, резерв и fulfillment записывают `actor_user_id`; fulfillment также хранит `source_type=SALES_ORDER`, `source_id=orderId`; исторические события сохраняют `NULL` как неизвестного автора; служебные request prefixes отделены от пользовательских |
| **Q5** | `InventoryHistoryQueryService`, `AuthenticatedInventoryQueries`, публичные typed DTO | Tenant-fenced `getPosition`, `searchMovements`, `reservationHistory`, отдельный stocktake detail; фильтры по локации/SKU/reason/времени, bounded page 1..100, ordered history, no raw repository transport |
| **Q6** | `AuthenticatedInventoryCommands.writeOff`, `InventoryAuthorizedCommandService` | Явное списание положительного количества с `WriteOffCause(DAMAGE/LOSS/EXPIRED/OTHER)`, ledger `WRITE_OFF`, `INVENTORY_ADJUST`, не ниже резерва; конфликтное повторение ключа с иной причиной отвергается |
| **Q7** | `InventoryTransferService`, `StockTransferDto`, migrations 0071–0072 | Синхронный трансфер между 2 ACTIVE WAREHOUSE в одном tenant, условие `available>=qty`, детерминированный порядок lock по UUID локации, receipt + две immutable ledger legs (TRANSFER_OUT/IN), сумма delta=0, повтор ключа идемпотентен |
| **Q8** | `InventoryStocktakeService`, `InventoryStocktakeDto`, migrations 0073–0074 | Count snapshot 1..100 SKU, `count` не меняет баланс, `post` сверяет версии до проводки и пересчитывает только дельту, запрет `counted<reserved`; terminal POSTED/CANCELLED неизменяемы в PostgreSQL; нулевая дельта не создаёт фиктивную проводку |
| **Q9** | `SalesInventoryRbacQualificationTest.q9WarehouseFirstReceiptToSellerFulfillmentProducesAuditableQuantityChain` | Сквозной service-only E2E: первый приход без исходной balance row → PLACED order → confirm/reserve → fulfill → ledger and actor/source evidence; повтор не создаёт движения |

**Тесты, которые обязаны быть GREEN на одном source SHA:** `InventoryQ123QualificationTest.q456ProvenanceWriteOffAndTenantHistoryAreRealOperations`, `q456WriteOffRequiresAdjustmentPermission`, `postgresSyntheticParallelReceiptLockProbePreservesAllMovements`; `InventoryTransferStocktakeQualificationTest` (идемпотентность/две ноги, обратные потоки, подтверждение физического количества, stale version, counted<reserved, no-change, terminal PG immutable); `SalesInventoryRbacQualificationTest.q4SalesReserveAndFulfillKeepStaffAttributionAndSourceOrder` и `q9WarehouseFirstReceiptToSellerFulfillmentProducesAuditableQuantityChain`; существующие INV-Q1–Q3, RBAC revoke и SALES/FILES регрессии.

**Ограничение нагрузки:** синтетический PostgreSQL lock probe = 24 поступления одного SKU на 8 workers; он выводит *измеренные* p95/p99 в CI, но **не является production capacity test** и не заменяет отдельное измерение SKU fanout, нескольких локаций, confirm/fulfill overlap или EXPLAIN/lock wait analysis.

**Nongoals и открытые границы:** нет реальных FIFO/FEFO/lot batches, COGS/финансовой себестоимости, перманентного stock-freeze, межтенантных перемещений, per-warehouse RBAC и транспортных HTTP endpoint. Для переноса старого склада архивированного SKU и корректировок после деактивации ORG нужен отдельный согласованный historical remediation flow. Production остаётся HOLD.

**Admission gate:** H2 и PostgreSQL integration, обе populated миграции, RustFS regression (не является тестом INVENTORY), SKU concurrency, revocation fence, immutable SQL triggers — все на exact source SHA. До фактических результатов этих workflow статус Q4–Q9 = **IMPLEMENTED / NOT YET QUALIFIED**.
