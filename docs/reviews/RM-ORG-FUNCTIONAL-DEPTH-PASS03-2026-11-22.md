# RM — ORGANIZATION: проход 03/12, State Machine & Dependency Integrity

**ID:** RM-ORG-FUNC-R1-P03. **Ветка:** `base_dev`. **Исходный runtime HEAD:** `1cc19715e82551ec22dd451427ea34b2a8b0c86d` (22.11.2026). **Статус:** source-level VERIFIED / CONTRACT SPECIFIED / новая реализация ORG NOT STARTED. Без контроллеров, изменения main и production admission.

**Основное ТЗ:** [RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md). **Исторический проход 02:** [RM-ORG-FUNCTIONAL-DEPTH-PASS02-2026-11-22.md](RM-ORG-FUNCTIONAL-DEPTH-PASS02-2026-11-22.md).

## 1. Важное обновление исходной базы после 02/12

После прохода 02 в ветке было реализовано `INV-Q1..Q3`. Прежний ORG-11 «нет первой приёмки/создания позиции» **устарел как code GAP**: `InventoryMovementService.adjust` при `RECEIPT` после request-key replay, проверки склада/SKU теперь создаёт нулевой `inv_stock_balance` при отсутствии строки. `InventoryQ123QualificationTest.firstReceiptCreatesExactlyOnePositionAndOldReceiptReplaysAfterArchive` и `concurrentFirstReceiptsDoNotDoubleCreateOrLoseQuantity` проверяют это, а профильная спецификация `RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md` указывает PostgreSQL-qualification на code SHA `d9819250089872ead3ddb869f821974041bd46a4`. Ретроспективный документ 02/12 сохраняет наблюдение для своего SHA; **НЕ создавать повторный initializePosition в ORG или INVENTORY**.

Также появился `StockEligibility`: `requireReceivable` / `requireReservable` проверяют `public.org_location(workspace_id,id,location_type='WAREHOUSE',status='ACTIVE')` и PRODUCT eligibility, удерживая `FOR UPDATE` на локации и SKU в одной операции. `InventoryReservationService.reserve` проверяет существующий idempotent replay *до* новой eligibility, затем проверяет eligibility перед reserve. `SalesInventoryCommandService.confirm` проходит через этот port и возвращает rollback при ошибке reserve. Таким образом старые GAP ORG-02/03 **частично закрыты** для новых receipt/correct/reserve/confirm; они **не являются** квалификацией новых ORG lifecycle, concurrency с deactivate или правильной производительности.

**Source evidence:** `src/main/java/kz/rm/inventory/StockEligibility.java`; `InventoryMovementService.java`; `InventoryReservationService.java`; `SalesInventoryCommandService.java`; `OrganizationLocationEntity.java`; `db.changelog-master.sql`; `InventoryQ123QualificationTest.java`; `InventoryReservationQualificationTest.java` и `docs/spec/RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md` (раздел 15).

## 2. Бизнес-решение DEC-ORG-03-A: временная деактивация склада с товаром

**Утвердить в проектном ТЗ R1:** `INACTIVE` означает *операционную паузу*, а не «склад пустой». Если `on_hand > 0`, но `reserved = 0` на **всех SKU**, **нет ACTIVE reservations** и нет другой реально сохранённой активной зависимости, склад **можно перевести** в `INACTIVE`. Товар остаётся на своём месте, `on_hand` и ledger не меняются. Это разрешает ремонт, инвентаризационную паузу и временную остановку склада без фиктивных списаний.

**Сохранить строгий CLOSE:** `CLOSED` только после `on_hand=reserved=0` для всех SKU и отсутствия ACTIVE reservations. `CLOSED` финальный. Нулевые исторические `inv_stock_balance`, `inv_stock_movement`, `RELEASED/FULFILLED` reservations и старые заказы **остаются**; не delete/cascade.

**Не допускать деактивации при active reservation:** иначе подтверждённые заказы окажутся в подвешенном состоянии, так как штатный SALES cancellation разрешён не для всех стадий. Менеджер сначала завершает или отменяет соответствующие заказы через SALES/INVENTORY. Отдельный emergency lock/override не вводится без независимого сценария и прав.

**Практическое следствие:** временно остановленный склад с 500 единицами и 0 reserved разрешён. Склад с 500 onHand и 100 reserved — не разрешён, пока 100 не будут исполнены/освобождены. `CLOSED` при 500 onHand запрещён даже при 0 reserved.

## 3. Конечный автомат (все типы локаций)

| Исходное состояние | Операция | Предусловия | Результат |
|---|---|---|---|
| ACTIVE | deactivate(expectedVersion) | READ/WRITE admission, нет positive reserved, нет ACTIVE reservations; onHand может быть >0 | INACTIVE, rowVersion+1, updatedAt, audit |
| INACTIVE | reactivate(expectedVersion) | READ/WRITE admission; workspace ACTIVE; сохранённая KATO-ссылка корректна; без требования обнулить stock | ACTIVE, rowVersion+1, updatedAt, audit |
| INACTIVE | close(expectedVersion) | Не существует SKU с onHand>0 или reserved>0; нет ACTIVE reservations | CLOSED, rowVersion+1, updatedAt, audit |
| ACTIVE | close | Любые | INVALID_TRANSITION, 0 mutation |
| CLOSED | reactivate/deactivate/update | Любые | LOCATION_CLOSED, 0 mutation |
| ACTIVE → ACTIVE, INACTIVE → INACTIVE, CLOSED → CLOSED | Повтор прежней команды, совпадающий expectedVersion | Не меняет состояние | Тот же DTO без изменения версии/времени/event; stale version → STALE_VERSION |
| ACTIVE или INACTIVE | update(name,address,kato,expectedVersion) | Editable fields only, KATO acceptance, no changed tenant/type/id | Состояние не меняется; CAS на изменение атрибутов |
| CLOSED | get/search | ORG READ, выбранный tenant | Историческая карточка доступна, warehouse picker не показывает |

Матрица относится к `WAREHOUSE`, `BRANCH` и `POINT_OF_SALE`, но `INVENTORY` dependency проверяется только при реально существующей stock/reservation привязке. Для BRANCH/POS с ошибочными legacy stock rows закрытие fail-closed. Не добавлять дополнительные `SUSPENDED/ARCHIVED` статусы в DDL: существующих трёх достаточно.

**Порядок проверки:** verified actor + tenant WRITE → scoped location load/lock → expectedVersion check → допустимость перехода → dependency query (в той же БД-транзакции) → JPA mutation + audit → COMMIT. Wrong tenant location не должен раскрывать существование чужого объекта. Отказ не меняет `updated_at`, version, audit и остатки.

### 3.1 Обработка KATO, которое устарело во время паузы

`KatoLocationPolicy.requireOrganizationLocationEligible` запрещает *новый* выбор удалённого КАТО. Однако если уже созданная локация содержит положительный stock и КАТО позднее помечено `is_deleted=TRUE`, строгая повторная проверка eligible при reactivate создаст ловушку: склад нельзя закрыть с stock и нельзя открыть, чтобы вывести stock.

**Минимальное правило R1:** reactivate с **неизменённым сохранённым** `kato_code`, на который продолжает указывать FK, допустим, даже если source-code стал deprecated; не трактовать изменение справочника как автоматическое закрытие физического склада. Любое **новое** значение КАТО при update по-прежнему требует active eligible GEO. Если stored reference отсутствует/повреждён — fail closed и отдельное восстановление ссылочной целостности. Ни физический переезд склада с запасами, ни бесконтрольная KATO-модификация не допускаются. Перед реализацией сверить существующий порядок GEO snapshot migration; кейс обязателен в PG tests.

## 4. Матрица зависимостей и разрешённых операций

| Операция | ACTIVE WAREHOUSE | INACTIVE WAREHOUSE с onHand>0, reserved=0 | CLOSED | Примечание |
|---|---|---|---|---|
| `receive` (новый requestKey) | Разрешить с INVENTORY_RECEIVE | Запретить | Запретить | Создание позиции только на первой законной приёмке |
| `correct` (новая проводка, ±) | Разрешить с INVENTORY_ADJUST | Запретить | Запретить | Не разрешать «тайно обнулить» склад для CLOSE |
| `reserve`, новый заказ | Разрешить с paired SALES/INVENTORY permits | Запретить | Запретить | Требует published PRODUCT и достаточный available |
| `confirm` с новым reserve | Разрешить | Запретить | Запретить | Никакого COMMIT частичной CONFIRMED |
| `fulfill` новой ACTIVE reservation | Разрешить | Нарушение инварианта: отказ/ручная коррекция | Отказ | Штатный deactivate должен был запретить живую reservation |
| `cancel/release` активного резерва | Разрешить по существующим правилам | Допустить legacy cleanup, если order transition legal | Допустить только cleanup при аномальном legacy case | Не затруднять освобождение stock |
| `receive/correct` повтор старого idempotency key с исходным payload | Только тот же результат | Вернуть immutable receipt без новой записи | То же | Актуальная авторизация прежде replay обязательна |
| `reserve` повтор прежнего ACTIVE reservation | Разрешён idempotent replay | Только legacy replay без мутаций; штатный deactivate не допускает active reserve | Аналогично, без мутаций | Совпадение order/line/location/item/quantity |
| `cancel/fulfill` повтор уже terminal результата | Вернуть прежний результат | Вернуть прежний результат | Вернуть прежний результат | Только после существующей IAM проверки |
| `get/search` локаций | Админ READ | Админ READ | Админ READ | Операционный warehouse picker исключает INACTIVE/CLOSED |
| `stock query` и history | INVENTORY_STOCK_READ | INVENTORY_STOCK_READ | INVENTORY_STOCK_READ | Права ORG_READ не раскрывают количества |

**Внимание:** `InventoryReservationService.transition` для `FULFILLED` сейчас не делает отдельную проверку `StockEligibility`, так как рассчитывает на ранее активный резерв. Это корректно только при новом инварианте «не деактивировать склад с ACTIVE reservations». Для legacy/внешней некорректной записи статуса добавить *узкую* проверку на случай новой положительной отгрузки, сохраняя terminal replay и cleanup release. Не добавлять eligibility check до чтения старого terminal результата, иначе сломается idempotency.

## 5. Критерии блокировок из существующей PostgreSQL схемы

Два фактических источника: `crm.inv_stock_balance(tenant_id,location_id,item_id,on_hand,reserved)`, `crm.inv_stock_reservation(tenant_id,location_id,item_id,state)`. На `crm.sales_order` **нет** warehouse_id до confirm; PLACED нельзя считать location-bound.

### 5.1 SQL — диагностика до транзакции и повторная проверка под row lock

~~~sql
-- Run only inside an authenticated, transaction-fenced service.
-- First lock public.org_location scoped by tenant and ID FOR UPDATE.
SELECT
  EXISTS (
    SELECT 1 FROM crm.inv_stock_balance b
    WHERE b.tenant_id = :tenant_id AND b.location_id = :location_id
      AND b.on_hand > 0
  ) AS has_on_hand,
  EXISTS (
    SELECT 1 FROM crm.inv_stock_balance b
    WHERE b.tenant_id = :tenant_id AND b.location_id = :location_id
      AND b.reserved > 0
  ) AS has_reserved_balance,
  EXISTS (
    SELECT 1 FROM crm.inv_stock_reservation r
    WHERE r.tenant_id = :tenant_id AND r.location_id = :location_id
      AND r.state = 'ACTIVE'
  ) AS has_active_reservation;
~~~

`deactivate` blocks iff `has_reserved_balance OR has_active_reservation`. `close` blocks iff `has_on_hand OR has_reserved_balance OR has_active_reservation`. При несогласованности `reserved > 0` без ACTIVE reservation или наоборот — **fail closed**, не устранять вручную под транзакцией ORG.

**SQL** не должен появляться как дублирующий «большой» репозиторий в ORGANIZATION. Реализовать небольшой `InventoryLocationUsagePort.checkBlockers(tenant,location)` с типизированным результатом из INVENTORY. ORG получает только три bool, а не данные SKU/количество. Никакого `COUNT(*)` по всей истории, только индексируемые EXISTS по tenant/location/state. Для reservation нужен селективный индекс `(tenant_id,location_id,state)` **после EXPLAIN** на populated PG; существующий `(tenant_id,state,created_at)` может оказаться нецелевым под location.

### 5.2 Гарантия от concurrent writer

Процесс A (deactivate) должен удерживать `SELECT ... FOR UPDATE` по `public.org_location WHERE workspace_id=? AND id=?` до COMMIT и только затем проверять блокирующие EXISTS. Все новые receive/reserve должны удерживать блокировку **этой же строки** до своей записи и COMMIT; иначе SELECT по balance/reservations может увидеть устаревший момент между проверкой и переходом.

Текущий `StockEligibility.requireWarehouse` использует `FOR UPDATE`: сериализует новые операции на одной локации. Это безопасная отправная точка для корректности, **но серьёзный потенциальный ограничитель throughput**: два независимых receipt разных SKU в одном складе будут ждать одну эксклюзивную строку. После квалификации допускается точечная замена на PostgreSQL `FOR SHARE` для операции *проверки* eligibility: shared locks между работающими складами, конфликтующие с `UPDATE/FOR UPDATE` на deactivate. H2 fallback `FOR UPDATE` как в IAM. Не менять автоматически до tests с two DB sessions и lock wait metrics.

**Существующая последовательность блокировок не должна нарушаться:**
- автономный `InventoryMovementService.adjust`: IAM admission → `inv_movement_request_lock FOR UPDATE` → replay lookup → `location eligibility lock` → `CATALOG PRODUCT lock` → first receipt zero row (если нужно) → `stock balance FOR UPDATE` → ledger;
- `SalesInventoryCommandService.confirm` → `InventoryReservationService.reserve`: IAM dual admission → `sales_order FOR UPDATE` → existing reservation replay → location / product eligibility lock → `stock balance FOR UPDATE` → reservation + event;
- `OrganizationLocationService.deactivate/close`: IAM admission → `org_location FOR UPDATE` → **только обычные не-блокирующие EXISTS** к balance/reservation → state + audit; не заходить в обратном порядке в sales_order, SKU, request stripe, stock row `FOR UPDATE`;
- `InventoryReservationService.release/fulfill` существующие order→stock→reservation lock; для исполнений с дополнительной location eligibility аккуратно вынести guard до stock lock и не блокировать terminal replay.

**Тонкость FOR SHARE:** если PostgreSQL оптимизация выполняется, каждая транзакция, которая меняет stock под соответствующей location, должна подхватывать shared lock до stock mutation; иначе состояние зависит от неизвестного internal writer и подтверждённой гарантии нет. Изменение только одного фасада недостаточно.

## 6. CAS, изменения, audit и сведения о правах

- `@Version rowVersion` на `OrganizationLocationEntity` уже есть, но команд переходов нет. `expectedVersion` обязателен для update/deactivate/reactivate/close. Check сравнивается с актуально заблокированной строкой **до зависимости**, затем транзакционный update, increment ровно один.
- Для повторной команды на текущем статусе: при совпадающем `expectedVersion` вернуть текущий `LocationDto` без изменения version, updatedAt и event; при stale version — `STALE_VERSION`.
- При успешном переходе писать в той же транзакции `crm.audit_event(action_code='ORG_LOCATION_DEACTIVATE'/'ORG_LOCATION_REACTIVATE'/'ORG_LOCATION_CLOSE')` с точными tenant, actor и entityId. Таблица audit_event не имеет old/new value; не представлять как готовую историю содержательных изменений.
- Документировать коды предметных отказов: `LOCATION_NOT_FOUND` (в т.ч. foreign), `LOCATION_CLOSED`, `INVALID_TRANSITION`, `LOCATION_HAS_ACTIVE_RESERVATIONS`, `LOCATION_HAS_RESERVED_BALANCE`, `LOCATION_HAS_STOCK`, `STALE_VERSION`, `LOCATION_NOT_ACTIVE_WAREHOUSE`, `LOCATION_DEPENDENCY_INCONSISTENT`. Реализовать типизированную доменную ошибку только если текущая архитектура предоставляет такой контракт; иначе стабильные internal reason codes, без HTTP exception mapping во время freeze.
- Применять `ORGANIZATION_LOCATION_WRITE` к lifecycle, `ORGANIZATION_LOCATION_READ` к location queries, `INVENTORY_*` и `SALES_*` — исключительно в владельцах операций. Оператор склада не получает автоматически право закрывать филиалы.
- Обновление на `CLOSED` запрещено; чтение истории допускается при ORG_READ, складская история — только INVENTORY_STOCK_READ.

## 7. Таблица испытаний ORG-P03-Q01…Q14

| ID | Сценарий | Проверка PostgreSQL |
|---|---|---|
| P03-Q01 | Пустой ACTIVE, 0 reserved → INACTIVE | State, version+1, audit ровно один |
| P03-Q02 | ACTIVE, onHand=500, reserved=0, ACTIVE reservations отсутствуют → INACTIVE | Успех; onHand остаётся 500, ни одного movement |
| P03-Q03 | ACTIVE onHand=500, reserved=100, ACTIVE reservation имеется → deactivate | Deny, 0 state/audit/stock изменений |
| P03-Q04 | reserved=100, но ACTIVE reservation отсутствует | Fail closed: inconsistency, отдельно зафиксированный blocker |
| P03-Q05 | ACTIVE reservation, reserved=0 | Fail closed, несогласованность не «исправляется» |
| P03-Q06 | INACTIVE с onHand=500 → close | Deny LOCATION_HAS_STOCK |
| P03-Q07 | INACTIVE с onHand=0,reserved=0,no ACTIVE reservation → close | CLOSED terminal, legacy zero row + ledger retained |
| P03-Q08 | INACTIVE → ACTIVE, stored KATO = valid; stored KATO now deprecated | Реактивация с прежним кодом допустима, новая KATO assignment blocked |
| P03-Q09 | CLOSED → reactivate; ACTIVE → close | INVALID_TRANSITION; no side effects |
| P03-Q10 | Same-state retry with exact version; stale version | No-op/no duplicate audit или STALE_VERSION |
| P03-Q11 | Receive started before deactivate | Receive commit then deactivate succeeds only if no reservations; positive stock preserved, no lost update |
| P03-Q12 | Deactivate committed before receive/new reserve/confirm | New operations fail; no stock/reservation/order mutation; first-receive row not created |
| P03-Q13 | CANCEL/RELEASE cleanup, old movement/terminal replay after INACTIVE/CLOSED, including auth revoked | Safe cleanup/replay only after valid permission; no new ledger |
| P03-Q14 | Two independent SKU receipts same warehouse, concurrent ORG state change; PG lock wait / potential deadlock | Lock order qualified, no deadlock / oversell; document throughput and lock modes |

Для каждого теста фиксировать точный HEAD, DB backend, SQL state до/после, случай skip, обособленные коннекты/барьеры, события и replay. Добавить совместную регрессию `InventoryQ123QualificationTest`, `InventoryReservationQualificationTest`, `SalesInventoryRbacQualificationTest`, `OrganizationLocationQueryQualificationTest`, `KatoTerritoryQualificationTest`. H2 не заменяет PostgreSQL lock semantics.

**Существующие Q1–Q3 PostgreSQL GREEN не квалифицируют P03-Q01..Q14**, потому что ORG state commands ещё отсутствуют, а тесты INACTIVE ставят прямым SQL вне доверенного lifecycle.

## 8. Изменения к ORG-R1.1 и DoD на текущий проход

**Изменение решения 01/12:** «deactivate только при onHand=0» **заменено** на «deactivate при reserved=0/no ACTIVE reservations, независимо от свободного onHand». `CLOSE` остаётся строгим.

**Уже реализовано в другом домене:** stock zero-init при первом RECEIPT; ACTIVE warehouse/product eligibility для нового receive/reserve; mandatory reserve при confirm; historical replay. Это сохраняется как dependency Q1..Q3, не переписывать.

**Осталось в ORGANIZATION:** state transitions, scoped row lock + version, typed dependency blockers, audit, reactivation KATO policy и PostgreSQL race qualification. **В INVENTORY/SALES:** runtime guard для новой fulfill на legacy inactive location (с сохранением terminal replay), тесты блокировок и оптимизация FOR UPDATE→FOR SHARE только после доказательной приёмки.

**Проход 03/12:** **ANALYZED / CONTRACT SPECIFIED / NOT IMPLEMENTED**. Зрелость ORGANIZATION по общей шкале остаётся **1/5**. Нет изменений runtime и нет новых выполненных P03 tests.

**Следующий проход 04/12:** input validation, profile patch/KATO, type immutability, CAS/update specifics, retention/audit action contracts; синхронизировать с 03 state semantics. Не переключаться на другой домен до завершения 12/12.


---

## Актуализация после прохода 05/12

Проход 03 изучал состояние до появления `INV-Q8`; в текущем коде есть реальный `crm.inv_stocktake.status='OPEN'`. Поэтому DEC-ORG-03-A о временной деактивации с **свободным** `onHand>0` сохраняется, но дополнен **DEC-ORG-05-B**: `ACTIVE→INACTIVE` и `INACTIVE→CLOSED` требуют также отсутствие OPEN stocktake. Не перекладывать на ORGANIZATION автоматическое завершение, списание или удаление инвентаризации. Открытую операцию сначала законно POST/CANCEL в INVENTORY. `POSTED/CANCELLED` истории не блокируют.

**Актуальный cross-domain lock hazard:** в `InventoryTransferService.transfer` текущая последовательность `location A → PRODUCT X → location B` потенциально deadlock'ится с `receive(location B, PRODUCT X)`. До одновременной реализации ORG deactivate/close требуется исправление P05-01: получить все location locks до PRODUCT; см. [проход 05/12](RM-ORG-FUNCTIONAL-DEPTH-PASS05-2026-11-22.md). Этот раздел фиксирует более позднее решение, не переписывая исходный source snapshot/не выдавая запланированные race tests за GREEN.
