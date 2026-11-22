# RM — ORGANIZATION: проход 05/12, INVENTORY Integration & Warehouse Integrity

**Документ:** RM-ORG-FUNC-R1-P05. **Ветка:** `base_dev`. **Исходный HEAD изученного runtime:** `1185d5554ae4bd07aa83109d12237ee0eec34846` (22.11.2026). **Статус:** SOURCE AUDITED / CONTRACT SPECIFIED / ORG lifecycle NOT IMPLEMENTED / P05 TESTS NOT RUN. Только изменения документации, Feature Freeze: без HTTP-контроллеров, merge в `main` и production admission.

**ТЗ:** [RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md). **Связанные ревью:** [P03 state machine](RM-ORG-FUNCTIONAL-DEPTH-PASS03-2026-11-22.md), [P04 data integrity](RM-ORG-FUNCTIONAL-DEPTH-PASS04-2026-11-22.md).

## 1. Цель, владельцы операций и актуальное состояние

**Цель:** доказуемый контракт ACTIVE WAREHOUSE на записи INVENTORY/SALES; R1 пауза с товаром, без новых движений; CLOSED сохраняет ledger; отсутствие циклов ожидания, oversell, потери движений и смешения tenants.

| Действие (trusted boundary) | Права IAM | Owner SQL/сервис | Реальное требование к локации | Сегодня |
|---|---|---|---|---|
| `AuthenticatedInventoryCommands.receive` | INVENTORY_RECEIVE | `InventoryAuthorizedCommandService.receive` → `InventoryMovementService.adjust(RECEIPT)` | ACTIVE WAREHOUSE + non-archived PRODUCT | IMPLEMENTED |
| `correct` | INVENTORY_ADJUST | `adjust(CORRECTION)` | ACTIVE WAREHOUSE, существующая stock row | IMPLEMENTED |
| `writeOff` | INVENTORY_ADJUST | `adjust(WRITE_OFF,WriteOffCause)` | ACTIVE WAREHOUSE, onHand после списания ≥ reserved | IMPLEMENTED |
| `transfer(from,to,item,qty,key)` | INVENTORY_ADJUST | `InventoryTransferService.transfer` | Оба location должны быть ACTIVE WAREHOUSE, valid PRODUCT | IMPLEMENTED (lock-order GAP) |
| `AuthenticatedSalesCommands.confirm` | SALES_ORDER_CONFIRM + INVENTORY_RESERVE | `SalesInventoryCommandService.confirm` → `OrderInventoryPort.reserve` → `InventoryReservationService.reserve` | ACTIVE WAREHOUSE, PUBLISHED PRODUCT, sufficient available | IMPLEMENTED |
| `cancel` / `OrderInventoryPort.release` | SALES_ORDER_CANCEL + INVENTORY_RELEASE | `InventoryReservationService.transition(RELEASED)` | Cleanup существующего ACTIVE резерва без обязательного ACTIVE warehouse | IMPLEMENTED |
| `fulfill` / `OrderInventoryPort.fulfill` | SALES_ORDER_FULFILL + INVENTORY_FULFILL | `transition(FULFILLED)` | R1 target: new fulfillment только в ACTIVE WAREHOUSE, terminal replay допускается | **GAP**: отдельного location status guard нет |
| `startStocktake` | INVENTORY_ADJUST | `InventoryStocktakeService.start` | ACTIVE WAREHOUSE, 1..100 existing SKU positions | IMPLEMENTED |
| `recordCount` | INVENTORY_ADJUST | `InventoryStocktakeService.count` | Меняет count-draft, не stock balance; требует stocktake OPEN | IMPLEMENTED |
| `postStocktake` | INVENTORY_ADJUST | `InventoryStocktakeService.post` | ACTIVE WAREHOUSE, snapshot stock version unchanged | IMPLEMENTED |
| `cancelStocktake` | INVENTORY_ADJUST | `InventoryStocktakeService.cancel` | Cleanup OPEN draft без location ACTIVE eligibility | IMPLEMENTED |
| `ORG.deactivate/reactivate/close` | ORG_LOCATION_WRITE | Новые service methods из P03 | Атомарный location-row lock + typed blockers | **NOT IMPLEMENTED** |

**Техническая граница:** `VerifiedActor` допускается только в trusted фасадах; `TenantAuthorization.requireForCommand` и paired SALES/INVENTORY decisions обязательны до складских записей. Существующие package-private legacy overloads `InventoryMovementService.adjust` и `InventoryReservationService.reserve` могут использоваться в тестах, но не должны публиковаться клиентам как новые write ports.

`StockEligibility.requireWarehouse` выполняет `SELECT location_type,status FROM public.org_location WHERE workspace_id=? AND id=? FOR UPDATE`; `requireProduct` — `SELECT kind,state FROM crm.cat_item WHERE tenant_id=? AND id=? FOR UPDATE`. Для нового RECEIPT / корректировки PRODUCT может быть DRAFT или PUBLISHED (но не ARCHIVED), reserve требует PUBLISHED; SERVICE всегда недопустим для quantity R1.

## 2. VERIFIED: точный путь и порядок блокировок

### 2.1 Standalone receive/correct/writeOff

`VerifiedActor → IAM requireForCommand → request-key stripe FOR UPDATE → search existing movement by (tenant,request_key) → если idempotent exact match, вернуть immutable receipt → StockEligibility(location FOR UPDATE → PRODUCT FOR UPDATE) → при first RECEIPT INSERT zero inv_stock_balance if absent → balance FOR UPDATE → balance UPDATE + inv_stock_movement INSERT → commit`.

- Повтор прежнего request key на INACTIVE/CLOSED с совпадающим исходным payload возвращает старый результат **после текущей IAM-проверки**, без нового движения и без повторного eligibility; mismatch — отказ. `writeOff` сравнивает также `business_reason_code`.
- Только RECEIPT создает отсутствующую zero stock position; correct/writeOff не могут инициировать новый складской баланс.
- Вся арифметика `on_hand >= reserved >= 0` защищается балансом `FOR UPDATE` и `crm.inv_stock_balance` CHECK. Новые операции на INACTIVE/CLOSED/BRANCH/POS отвергаются до balance/ledger mutation. Это относится к коду INVENTORY; окончательный lifecycle ORGANIZATION ещё не существует.
- `writeOff` изменяет остаток, не отменяет резерв: нельзя отрицательным списанием пройти ниже reserved; никакого автоматического аннулирования заказа.

### 2.2 Q7 transfer — два склада, один tenant

`VerifiedActor → IAM INVENTORY_ADJUST → request-key stripe FOR UPDATE → existing transfer receipt replay → same-tenant/type/status + PRODUCT admission → zero-init target → balance row source+target FOR UPDATE → insert inv_stock_transfer → debit source / credit target → two immutable inv_stock_movement legs TRANSFER_OUT/IN → commit`.

Фактически target zero-init допустим только после admission обоих складов и PRODUCT. Source обязательно должен иметь stock row. Успешная транзакция сохраняет сумму onHand двух позиций, reserved источника и назначения не меняются, transfer-history ссылается на оба склада. Старый exact transfer receipt (тот же requestKey и payload) возвращается до owner eligibility, даже если затем location стал INACTIVE/CLOSED. Новый transfer с INACTIVE source ИЛИ target отклоняется **целиком**, ни одна leg не создаётся.

**НОВАЯ НАХОДКА P05-01 / P0 — потенциальный deadlock смешанных операций:**
В `InventoryTransferService.transfer`:
- сортируются UUID двух складов, но затем вызывается `StockEligibility.requireReceivable(tenant,first,item)`: блокирует **location A, затем SKU X**;
- после этого `StockEligibility.requireReceivable(tenant,second,item)` пытается блокировать **location B**;
- другая транзакция `receive/correct(B,X)` может уже удерживать **location B** и ждать **SKU X**;
- тогда T1 держит `SKU X` и ждёт B, а T2 держит B и ждёт X. PostgreSQL имеет цикл ожидания (40P01). Прежний тест `inverseConcurrentTransfersDoNotDeadlockAndConserveStock` **не** доказывает отсутствие такого цикла с другой командой или с трехскладной комбинацией.

**Минимальная корректировка INV-Q7, не новая система:** выделить в существующем `StockEligibility` package-private `requireWarehouses(tenant,locationsSorted)`: lock **всех** location IDs в ascending UUID через отдельные scoped row locks; после них **ОДИН РАЗ** lock/check PRODUCT. Первая/вторая location не должны ждать уже после взятия PRODUCT lock. Затем stock balances в том же sorted order. Одиночные команды сохраняют `location→PRODUCT→stock`. `request-key stripe` и проверки previous transfer остаются первыми. **Не** менять `FOR UPDATE` на `FOR SHARE` до доказанного PG теста на реальных конкурентных командах. Для детерминированного repro выбрать разные request-lock buckets (коллизия stripe случайно скрывает цикл).

### 2.3 SALES confirm/reserve и release/fulfill

Новая confirm: IAM paired decisions → `sales_order FOR UPDATE` → проверка PLACED/lines → заявка на reserve → `existing reservation replay` → `location FOR UPDATE` → `PRODUCT FOR UPDATE` → `balance FOR UPDATE` → reserved += qty + event → order state/status event в одной TX. Если admission/stock fails, статус order и все ранее созданные резервы откатываются.

`InventoryReservationService.reserve` возвращает идентичную существующую ACTIVE reservation *до* location eligibility; это только read-only replay. Новую reservation при INACTIVE/CLOSED запрещает `StockEligibility`.

`release` берет order → stock → reservation и не требует ACTIVE warehouse; это осознанный cleanup. `fulfill` сейчас также берет order → stock → reservation, **не проверяя** ACTIVE и WAREHOUSE для новой negative movement. Состояния, создаваемые только легальными commands, не должны дать одновременно ACTIVE reservation и INACTIVE location, потому что будущий deactivate обязан видеть blocker. Однако legacy SQL/ошибка миграции могут это нарушить; не считать отсутствие штатного пути доказательством отсутствия дефекта.

**P05-02 / P1:** для **новой** fulfill (ACTIVE reservation) после order row lock и предварительной безопасной проверки terminal replay взять location eligibility lock **до** stock FOR UPDATE; для confirmed terminal replay не выполнять новый eligibility / stock debit. `release` остаётся разрешенным для cleanup при неактивном объекте. Точный transition ordering и идемпотентность — согласовать с SALES в проходе 06/12, не внедрять guard после stock lock, иначе возникнет обратный lock order.

## 3. Дополнительный потребитель, появившийся к P05: INV-Q8 stocktake

К актуальному HEAD реализованы `InventoryStocktakeService.start/count/post/cancel` и таблицы `crm.inv_stocktake`, `crm.inv_stocktake_line` со статусами OPEN/POSTED/CANCELLED. Сценарий start сохраняет snapshot версии stock в короткой транзакции; `recordCount` обновляет только draft; `post` проверяет unchanged stock version, reserved и ACTIVE warehouse через `StockEligibility` перед публикацией дельт в ledger. `cancel` не требует ACTIVE location.

**DEC-ORG-05-B: открытая инвентаризация (OPEN stocktake) — операционный blocker для deactivate и close.** Без этого физическая пауза может оставить незавершенный подсчёт, который потом невозможно post без reactivate. При наличии OPEN stocktake менеджер обязан провести или отменить её штатной INVENTORY-командой. CANCELLED и POSTED **не** блокируют, их историю хранить. Это **дополнение** к DEC-ORG-03-A, не запрет ставить на паузу из-за свободного onHand.

Если поддерживать emergency suspension с незавершённой инвентаризацией потребуется позднее, это должна быть отдельная явно утверждённая операция, не скрытый bypass.

## 4. Окончательная матрица допуска — рабочее решение P05

| Операция | ACTIVE WAREHOUSE | INACTIVE с onHand>0 и 0 reserved | CLOSED | Эффект R1 |
|---|---|---|---|---|
| Новый `receive` | ALLOW | DENY | DENY | Новая позиция только на ACTIVE |
| Новые `correct` / `writeOff` | ALLOW | DENY | DENY | Не обходить паузу через коррекцию |
| Новый `transfer` FROM | ALLOW | DENY | DENY | Reactivate source до реального вывода |
| Новый `transfer` TO | ALLOW | DENY | DENY | Не складировать в закрытый объект |
| Новый `reserve/confirm` | ALLOW | DENY | DENY | Откат всех order/reserve effects |
| Новая `fulfill` ACTIVE reservation | ALLOW | DENY, legacy anomaly | DENY | Guard до stock mutation |
| `cancel/release` existing reserve | ALLOW | ALLOW cleanup (legacy) | ALLOW cleanup (legacy) | Права и исходный order state обязательны |
| Старый exact-payload movement/transfer replay | ALLOW | ALLOW | ALLOW | Только ранее committed immutable receipt, без записи |
| `startStocktake` | ALLOW | DENY | DENY | Requires existing positions |
| `recordCount` OPEN draft | ALLOW | NOT EXPECTED после нового state guard | NOT EXPECTED | Только draft, не физический stock; cleanup через cancel |
| `postStocktake` | ALLOW | DENY | DENY | Eligibility, stock version/recount |
| `cancelStocktake` OPEN draft | ALLOW | ALLOW cleanup | ALLOW cleanup legacy | Не оставлять зависших OPEN draft |
| `stock/history query` | ALLOW by INVENTORY_STOCK_READ | ALLOW by same permission | ALLOW by same permission | Не раскрывать SKU/qty только по ORG_READ |
| `ORG.deactivate` | ALLOW iff 0 reserved, 0 ACTIVE reservations, 0 OPEN stocktakes | no-op with fresh version | DENY | Свободный onHand не блокирует |
| `ORG.close` | DENY (сначала INACTIVE) | ALLOW iff onHand=0, reserved=0, no ACTIVE reservations, no OPEN stocktakes | terminal no-op fresh version | Никакого DELETE истории |

**Важное дополнение:** historical warehouse movements, `inv_stock_transfer` и `inv_stocktake` записи не должны удаляться при смене status. `CLOSED` означает административный terminal state, не уничтожение FK или ledger.

## 5. Минимальный inventory usage port для новых ORG commands

Расширить предложенный `InventoryLocationUsagePort` до компактного результата:

~~~java
public record LocationUsageBlockers(
    boolean hasOnHand,
    boolean hasReservedBalance,
    boolean hasActiveReservation,
    boolean hasOpenStocktake
) {}
~~~

Порт вызывается только **внутри уже IAM-authorized ORG transaction под location FOR UPDATE**. Он выполняет tenant-scoped `EXISTS`, не возвращает фактические остатки или SKU и не предоставляет право на чтение stock через `ORGANIZATION_LOCATION_READ`. Сохранить владельцем SQL INVENTORY.

~~~sql
-- Authenticated ORG transaction, org_location row already FOR UPDATE.
SELECT
  EXISTS (SELECT 1 FROM crm.inv_stock_balance b
          WHERE b.tenant_id=:tenant AND b.location_id=:location AND b.on_hand>0)
      AS has_on_hand,
  EXISTS (SELECT 1 FROM crm.inv_stock_balance b
          WHERE b.tenant_id=:tenant AND b.location_id=:location AND b.reserved>0)
      AS has_reserved_balance,
  EXISTS (SELECT 1 FROM crm.inv_stock_reservation r
          WHERE r.tenant_id=:tenant AND r.location_id=:location AND r.state='ACTIVE')
      AS has_active_reservation,
  EXISTS (SELECT 1 FROM crm.inv_stocktake s
          WHERE s.tenant_id=:tenant AND s.location_id=:location AND s.status='OPEN')
      AS has_open_stocktake;
~~~

**Deactivate:** deny iff hasReservedBalance OR hasActiveReservation OR hasOpenStocktake. **Close:** additionally deny if hasOnHand. Если только исторические движения, POSTED/CANCELLED stocktakes и zero balance — close допустим. Если фактический reserved и active reservation расходятся — fail closed; никаких «автоматически исправить» в ORG.

**Индексы:** `inv_stock_balance` PK уже начинается с `tenant_id,location_id`; `inv_stocktake_location` уже покрывает `tenant_id,location_id,status`; `inv_stock_reservation` сейчас имеет `(tenant_id,state,created_at)` и `(tenant_id,order_id)`, не оптимизированный конкретно под location. Индекс `(tenant_id,location_id,state)` — только после `EXPLAIN (ANALYZE, BUFFERS)` на populated PG, без преждевременной DDL.

## 6. Конкурентность: single-location ORG vs INVENTORY/SALES

| Конкурентные операции | Точка сериализации | Требование commit-order |
|---|---|---|
| `deactivate L` vs `receive/correct/writeOff L` | `org_location L FOR UPDATE` | Writer committed before deact → сохранить stock, деактивация может состояться без reserves; deact committed first → writer DENIED, no movement |
| `deactivate L` vs `reserve/confirm L` | Та же строка L, plus order row within SALES | Если reservation committed первой, deact увидит reserved/ACTIVE и DENY; если deact первой, reserve/confirm DENY + rollback order |
| `deactivate A` vs `transfer(A→B)` | Lock A (если source/target A) | Transfer committed first → учесть новый stock; deact first → transfer denied entirely |
| `deactivate B` vs `transfer(A→B)` | Lock B, transfer requires both ACTIVE | Не допускать частичного списания source |
| `deactivate L` vs `startStocktake L` | Lock L | Start first → OPEN stocktake blocker; deactivate first → start DENIED |
| `deactivate L` vs `postStocktake L` | Lock L after header/lines | После post проверить reserved+OPEN; post first may finalize and then deactivate; deactivate first should block on OPEN |
| `close L` vs `transfer incoming L` | Lock L | После commit transfer hasOnHand>0 → close DENY; close first → transfer DENY |
| `close L` vs `idempotent historical replay` | Для replay отсутствует необходимость брать location lock | Replay после IAM+request stripe только читает receipt; никаких new stock effects |

**Lock-order guard:** `ORG` после actor/workspace и location lock делает только **обычные SELECT EXISTS** для stock/reservation/stocktake, **не** `FOR UPDATE` на balances, sales orders, request stripes или stocktake headers. `INV-Q7` должен сначала получить **оба** location locks в стабильном порядке до PRODUCT lock. `INV-Q8` count/post сохраняет stocktake header/lines → location/SKU → stock; ORG read-only EXISTS не должен дополняться header-lock, иначе возможна обратная зависимость. Полноту доказать двух-/трёхсоединительными PostgreSQL тестами с барьерами.

**Throughput:** текущий `StockEligibility` использует эксклюзивный `FOR UPDATE` по location для любых новых мутаций. Это сериализует даже разные SKU одного склада. Сначала закрыть риск P05-01 и квалифицировать функциональный порядок; затем отдельно проверить `FOR SHARE` (Postgres)/`FOR UPDATE` (H2) и p95/p99. Не объявлять производительность проблемой без измерений.

## 7. Закрытые/оставшиеся функциональные вопросы (не overengineering)

1. **НЕ** создавать новый microservice Warehouse, saga, lock-manager, duplicate location table или отдельный inventory ledger. `ORGANIZATION` owns state, `INVENTORY` owns balances.
2. **НЕ** разрешать «списать товар в INACTIVE через специальную correction». Для закрытия с товаром `reactivate→qualified transfer/writeOff (реальное основание)→deactivate→close`; transfer now implemented, so старое описание «ожидаем transfer» устарело.
3. Сохранить идемпотентность старых receipts (movement, transfer), terminal reservations; не отключать исторические query при CLOSED.
4. Два типа зависимостей при closed: **живые** blockers по `inv_stock_balance/ACTIVE reservation/OPEN stocktake`; **исторические** ledger, posted transfer, terminal reservation, completed stocktake остаются и сами по себе не блокируют close.
5. **READ** только по соответствующим permission: `ORG_READ` не даёт `INVENTORY_STOCK_READ`, никакого расширения персональных прав на локации без отдельной бизнес-потребности.
6. Поменять только внутренний последовательный acquisition locks INV-Q7; не переносить authorization в ORG и не открывать internal stock mutation facade.

## 8. Приёмочная матрица ORG-P05-Q01…Q20 (ПЛАН, НЕ GREEN)

| ID | Сценарий | Обязательный результат |
|---|---|---|
| P05-Q01 | ACTIVE WAREHOUSE, first receipt without row | Auto-zero + onHand + immutable movement, scoped tenant |
| P05-Q02 | INACTIVE/CLOSED/BRANCH/POS, new receipt/correction/writeOff | Deny, no new stock/movement |
| P05-Q03 | Exact movement retry after INACTIVE/CLOSED, mismatched payload | First same immutable receipt; second conflict, zero side effects |
| P05-Q04 | WriteOff with onHand 10, reserved 3, qty 8 | Deny below reserved, stock and ledger intact |
| P05-Q05 | Two ACTIVE locations, transfer 4, source 10 reserved 3 | source 6, target +4, reserved same, two linked legs |
| P05-Q06 | Transfer FROM INACTIVE, TO ACTIVE | Atomic deny; no target zero position, transfer row or legs |
| P05-Q07 | Transfer FROM ACTIVE, TO INACTIVE/CLOSED | Atomic deny; no source debit or target credit |
| P05-Q08 | Historical transfer exact retry after status CLOSED, mismatch different qty | Same receipt without ledger; mismatch denied |
| P05-Q09 | Concurrent inverse A→B and B→A, equal SKU | Stable location/balance acquisition; conservation, no deadlock |
| P05-Q10 | **Real PG 3-session cycle probe**: transfer A→B SKU X vs receive at B SKU X with distinct request stripes | Before fix reproducible risky schedule / after fix no 40P01, full commit/rollback correctness |
| P05-Q11 | Transfer A→B X vs transfer B→C X, where A<B<C | No location–SKU–location cycle, no oversell |
| P05-Q12 | ACTIVE warehouse reservation by confirm, then deactivate | Denied on reserved/ACTIVE; order and stock remain consistent |
| P05-Q13 | Deactivate first, then confirm/reserve | New order confirm rejects, PLACED retained, no reservation |
| P05-Q14 | Deactivate vs writeOff/receive, both commit orders | Source invariant, 0 new mutation after INACTIVE; stock preserved on paused warehouse |
| P05-Q15 | Transfer into/from location being closed, both commit orders | Close blocks if onHand remains; transfer denied if CLOSED |
| P05-Q16 | OPEN stocktake vs deactivate/close | Block while OPEN; POSTED/CANCELLED history does not block |
| P05-Q17 | Deactivate first, then start/post stocktake; cancel open legacy draft | New start/post denied; cleanup cancel permitted |
| P05-Q18 | Legacy INACTIVE location with ACTIVE reservation: release/cancel, attempt NEW fulfill, old completed replay | Cleanup allowed, new fulfillment denied, terminal replay read-only |
| P05-Q19 | A/B/AB users + foreign location/SKU + revoked IAM membership/roles | No cross-tenant stock movement, no local-data leak, valid verified grants required |
| P05-Q20 | PostgreSQL multi-SKU stocktake/transfer/reserve, source error mid-write | Atomic rollback, no duplicate legs/events, no lock timeout/deadlock, exact audit/provenance |

**Evidence:** Spring service tests via VerifiedActor (except explicitly tagged lower-level invariant tests), PostgreSQL ≥2 connections with controlled synchronization, inspect `pg_locks`/`pg_stat_activity` where needed, SQLSTATE `40P01`, before/after row versions, counts in `inv_stock_balance`, `inv_stock_movement`, `inv_stock_transfer`, `inv_stock_reservation`, `inv_stocktake`, `crm.sales_order`. H2 single-session smoke недостаточен. Не принимать случайные green outcomes без детерминированного barrier schedule. Не выдумывать p95/p99.

**Existing regression set:** `InventoryTransferStocktakeQualificationTest.transferWritesExactlyTwoLegsAndReplaysWithoutDuplicateBalances`, `inverseConcurrentTransfersDoNotDeadlockAndConserveStock`, `stocktakePostsOnlyMeasuredDeltaAndRejectsStaleOrBelowReserved`, `InventoryQ123QualificationTest`, `InventoryReservationQualificationTest`, `SalesInventoryRbacQualificationTest`, `OrganizationLocationQueryQualificationTest`. Эти тесты **НЕ** закрывают P05-Q10 и будущий ORG state transition, так как отсутствует runtime deactivate/close.

## 9. Итог прохода и адресная очередь

| Приоритет | ID | Владелец | Реализация после всех 12 проектных проходов |
|---|---|---|---|
| **P0** | P05-01 | INVENTORY INV-Q7 | Взять обе location rows перед PRODUCT row и затем stock в стабильном порядке; отдельные PG mixed-command race tests |
| **P0** | P05-03 | ORGANIZATION / INVENTORY | Реализовать `deactivate/reactivate/close` с location lock и typed dependency flags (включая OPEN stocktake); integrity tests |
| **P1** | P05-02 | INVENTORY / SALES | Перед NEW fulfill проверить location ACTIVE до stock lock; preserve terminal replay/cleanup and paired permits |
| **P1** | P05-04 | INVENTORY / PERF | Только после P0 — проверить lock contention `FOR UPDATE`, варианты `FOR SHARE` и реальную нагрузку |
| **P1** | P05-05 | ORG/INV/SALES QA | Интеграционный E2E + все Q01..Q20, two-/three-session PostgreSQL, rollback, tenant security |

**Принятое решение DEC-ORG-05-A:** new R1 mutations require ACTIVE WAREHOUSE on both ends of transfer; old receipt replay and authorized cleanup survive INACTIVE/CLOSED, terminal ledger immutable; ORG must use a shared location-row fence and inventory-owned read-only blockers. **DEC-ORG-05-B:** OPEN stocktake дополнительно запрещает deactivation/close, пока не POSTED/CANCELLED. Свободный `onHand>0` по-прежнему **НЕ** запрещает temporary INACTIVE согласно DEC-ORG-03-A.

**Статус:** проход 05/12 **SOURCE VERIFIED / SPECIFIED**, runtime ORG lifecycle и новая mixed-command deadlock qualification **НЕ реализованы**. ORGANIZATION остается **1/5**; следующий 06/12 — SALES confirmed/fulfill/cancel, order-state admissions и concurrency с локациями. Другой домен не начинать до 12/12.
