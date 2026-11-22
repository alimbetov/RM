# RM — ORGANIZATION: проход 06/12 — SALES Integration & Order Lifecycle

**Документ:** RM-ORG-FUNC-R1-P06. **Source SHA (base_dev):** `cf0ae73c22d881d703f072afcacd7c5de8f5deff`, 22.11.2026. **Результат:** SOURCE VERIFIED / CONTRACT SPECIFIED / NEW ORG+SALES CROSS-DOMAIN TESTS NOT RUN. В рамках данного прохода Java/DDL не менялись; feature freeze, без контроллеров, merge main и production admission.

**Основное ТЗ:** [RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md](../spec/RM-ORG-FUNCTIONAL-DEPTH-R1-12-PASSES.md). **Источники смежной проработки:** [SALES pass 04 — state machine](RM-SALES-AUTOORDER-STATE-MACHINE-PASS-04-2026-11-22.md), [SALES pass 06 — транзакции](RM-SALES-AUTOORDER-TRANSACTIONS-PASS-06-2026-11-22.md), [ORG pass 05 — INVENTORY](RM-ORG-FUNCTIONAL-DEPTH-PASS05-2026-11-22.md).

## 1. Изменение маршрута проекта пользователем

Серия продолжает называться `01…12` ради обратной совместимости документов. **Новый milestone — остановка детальной проработки ORGANIZATION после 07/12 с промежуточным closure report и переходом к другому сервису.** Проходы 08–12 остаются `DEFERRED / NOT DONE`, а не объявляются проведёнными. Домен не получит статус `FUNCTIONALLY MATURE` только на основании 7 specifications. Подготовить явный residual-risk/acceptance backlog для отложенных database/QA/load gates. Это замещает более раннее пожелание «переход только после 12/12».

## 2. Проверенный методный путь и фактическая реализация

| Точка входа | Реальная операция / требования | Транзакционное следствие |
|---|---|---|
| `AuthenticatedSalesCommands.confirm(actor,tenant,orderId,warehouseId)` | `SalesInventoryCommandService.confirm`: IAM `SALES_ORDER_CONFIRM` и `INVENTORY_RESERVE`, `sales_order FOR UPDATE`, `PLACED` only для новой команды | `PLACED→CONFIRMED`, `OrderInventoryPort.reserve` для каждой линии, один `sales_order_status_event(CONFIRMED)`, один commit |
| `OrderInventoryPort.reserve` | `Propagation.MANDATORY`, два `TenantAuthorization.Decision` того же actor/tenant/current TX; `InventoryReservationService.reserve` checks order status, order line/offer SKU, no previous reservation, `StockEligibility.requireReservable` | `location+SKU FOR UPDATE → stock FOR UPDATE → reserved+=quantity, ACTIVE reservation + RESERVED event` |
| `AuthenticatedSalesCommands.cancel` | Сейчас **всегда** требует `SALES_ORDER_CANCEL` + `INVENTORY_RELEASE` до проверки статуса; `sales_order FOR UPDATE`; для существующих ACTIVE reservations вызывает `OrderInventoryPort.release` | `PLACED/CONFIRMED/PREPARING→CANCELLED` в одной TX; release reserved, reservation event + seller status event |
| `AuthenticatedSalesCommands.fulfill` | `SALES_ORDER_FULFILL` + `INVENTORY_FULFILL`, `sales_order FOR UPDATE`, ACTIVE reservations count == line count, `OrderInventoryPort.fulfill` | Для каждой линии `reserved−=qty, onHand−=qty, reservation FULFILLED, immutable movement, COMPLETED event` |
| `InventoryReservationService.transition` | `peek reservation → order FOR UPDATE → stock FOR UPDATE → reservation FOR UPDATE`, then terminal-state return or new change | **Current GAP:** new fulfill does not check warehouse status; terminal replay unnecessarily acquires stock lock first |
| `crm.sales_order` | PK `(tenant_id,id)`; `status,row_version`; **warehouse/location_id нет** | `PLACED` нельзя считать относящимся к конкретному складу |
| `crm.inv_stock_reservation` | Unique `(tenant_id,order_line_id)`; order_line FK and balance FK; `location_id,item_id,quantity,state` | Только после создания резерва появляется достоверная привязка заказа к локации |
| `crm.inv_stock_movement` | UNIQUE `(tenant_id,reservation_id)` for fulfillment and immutable PG trigger | Одна зарезервированная строка не может иметь две fulfillment проводки по одному ID |

Текущий `OrderState` и DDL содержат `PLACED/CONFIRMED/PREPARING/READY_FOR_PICKUP/OUT_FOR_DELIVERY/COMPLETED/CANCELLED/REJECTED`, но **работающие trusted-команды** — только `confirm, cancel, fulfill`. `PREPARING` допустим как исходный статус для cancel/fulfill, но отдельной публичной команды перехода в PREPARING нет. `REJECTED/READY_FOR_PICKUP/OUT_FOR_DELIVERY` не объявлять работающим UX/workflow.

**Смежный SOURCE update после ORG-P05:** P05-01 потенциальный cycle из `transfer: location A→SKU→location B` уже **устранён на уровне кода**: `StockEligibility.requireTransferWarehouses` теперь берёт оба location locks по UUID до product lock; `InventoryTransferService.transfer` вызывает этот метод. Новый `InventoryTransferStocktakeQualificationTest.receiptAtTransferDestinationDoesNotCycleWithWarehouseOwnerLocks` присутствует в base_dev. Не писать, что P05-01 всё ещё не исправлен; full ORG deactivate×transfer E2E по-прежнему не тестировался.

## 3. DEC-ORG-06-A: инварианты склада и заказа (без новых статусов)

**Один подтверждённый заказ R1 = один склад; резерв полного количества каждой строки.** Для нового `confirm` склад должен быть `ACTIVE WAREHOUSE` с тем же tenant, товар `PUBLISHED PRODUCT`, available stock достаточен. Если нет — весь заказ остаётся `PLACED`, reserved/events/movements не меняются.

**Статус склада — операционный admission, а не способ переписать order history.** Отключение/закрытие локации:
- не отменяет уже подтверждённые заказы автоматически;
- не удаляет `sales_order`, `sales_order_line`, `inv_stock_reservation`, events/movements;
- не вводит `ON_HOLD`, новый status order или warehouseId для PLACED;
- не запрещает READ и exact read-only retry ранее committed terminal action после текущей IAM-проверки;
- допускает `cancel/release` старого ACTIVE reservation на legacy INACTIVE/CLOSED при законном статусе заказа и нужных permissions;
- **не допускает NEW `fulfill` с ACTIVE reservation на INACTIVE/CLOSED**, даже при legacy ручной смене статуса ORG;
- не создаёт SALES/INVENTORY side effects на `reactivate`.

DEC-ORG-03-A/05-B действуют в полной мере: `deactivate` с положительным свободным stock допускается только когда `reserved=0`, нет ACTIVE reservation и OPEN stocktake; `close` дополнительно требует `onHand=0`. Следовательно в штатном сценарии ACTIVE reservation «после деактивации» **не должна возникать**, но legacy defensive guard всё равно необходим.

## 4. Матрица операций по состоянию локации и заказа

| Order state / действие | ACTIVE warehouse | INACTIVE warehouse | CLOSED warehouse | Правило |
|---|---|---|---|---|
| `PLACED → CONFIRMED`, новый reserve | ALLOW при доступном stock+grants | DENY с rollback, оставить PLACED | DENY с rollback | Admission под location lock |
| `CONFIRMED → CONFIRMED`, exact replay | Только read-only outcome | Разрешить лишь при точном ранее committed reservation snapshot (аномальный/legacy случай) | То же | Без новых резервов и статусов |
| `PLACED → CANCELLED` (резервов нет) | ALLOW | ALLOW | ALLOW | Location не привязана к PLACED, нужна только SALES_CANCEL |
| `CONFIRMED/PREPARING → CANCELLED` | ALLOW по роли и reserve state | Cleanup ALLOW, если legacy ACTIVE | Cleanup ALLOW, если legacy ACTIVE | Не делать новое списание; использовать paired permission RELEASE |
| `CANCELLED → CANCELLED`, exact replay | Read-only | Read-only | Read-only | Вернуть прежний статус; проверить отсутствие ACTIVE/FULFILLED inconsistency |
| `CONFIRMED/PREPARING → COMPLETED`, NEW fulfill | ALLOW при полноте и ACTIVE location | DENY, legacy anomaly | DENY | Один movement на reservation, в той же TX |
| `COMPLETED → COMPLETED`, replay | Read-only | Read-only | Read-only | Нет новой записи независимо от текущего складского статуса |
| `COMPLETED/CANCELLED/REJECTED → CONFIRMED` | DENY | DENY | DENY | Терминальные состояния заказа неизменяемы |
| Новый `checkout→PLACED` | При PUBLISHED storefront, независимо от выбранного склада | Не связан со складом до confirm | Не связан со складом до confirm | Не invent warehouse dependencies; live storefront controls отдельно |

**Разграничение:** `checkout/PLACED` не принимает warehouseId; надо сохранить возможность принять заказ в рамках storefront даже если конкретный склад недоступен, но затем честно отказать в подтверждении до восстановления склада. Не представлять автоматический re-routing/stock substitution как реализованную функцию.

## 5. DEC-ORG-06-B: точная корректность идемпотентных повторов

Сейчас `confirm(CONFIRMED)` сравнивает `COUNT(ACTIVE)` с количеством линий и отсутствие других warehouse IDs. `fulfill(COMPLETED)` сравнивает `COUNT(FULFILLED)` с количеством линий. **Этого недостаточно, чтобы доказать соответствие SKU и qty каждой строке**, особенно потому что `SalesInventoryCommandService.lines()` и `InventoryReservationService.reserve()` используют `store_offer.catalog_item_id` **из текущего каталога**, а frozen `sales_order_line.catalog_item_id_snapshot` пока отсутствует на исследованном HEAD. При редактировании оффера live-SKU может измениться до нового confirm.

**ТЗ R1, не реализовано:**
- при checkout фиксировать immutable SKU snapshot (план SALES pass 03), с populated-safe nullable migration для legacy;
- при confirm/confirm-replay и fulfill/fulfill-replay проверять **по каждой** строке `(tenant,order,line,SKU_snapshot,quantity,warehouse,state)`, без повторной подстановки live offer SKU;
- при successful `COMPLETED` replay сверять также связанную `FULFILLMENT movement.reservation_id`, qty negative exactly-once и отсутствие противоречащих terminal reservation state;
- `CANCELLED` replay должен иметь 0 ACTIVE reservations **и** 0 FULFILLED (если нет модели partial fulfill), иначе `ORDER_INCONSISTENT`;
- legacy `NULL SKU_snapshot`: разрешить историю и существующий terminal replay по доказательным reservation/movement evidence; **новый** confirm с legacy UNKNOWN → `ORDER_SKU_UNRESOLVED`, не брать текущий offer в качестве прошлого;
- не менять `LocationDto` или `org_location` ради исправления SKU snapshot: owner этого contract — SALES.

**Отдельная тема PLACED cancel / least privilege:** сейчас `cancel(PLACED)` избыточно требует `INVENTORY_RELEASE`. Предусмотреть `SALES_ORDER_CANCEL` only, если после locked order доказаны **0 reservations любого state**; `CONFIRMED/PREPARING` сохраняют `INVENTORY_RELEASE` и release через `OrderInventoryPort`. IAM текущий share lock (actor/workspace) уже получен через SALES permission; последующий INVENTORY permission в общей TX должен пройти PostgreSQL deadlock/revoke race qualification. Не просто удалять вторую авторизацию для всех cancel. При `PLACED` с обнаруженным legacy reserve — отказ `ORDER_INCONSISTENT`, не безусловная отмена.

## 6. DEC-ORG-06-C: новый fulfillment guard — правильное место в lock graph

**Текущий код:** `InventoryReservationService.transition` берет `order FOR UPDATE → stock FOR UPDATE → reservation FOR UPDATE`, а terminal replay `if(current.state()==target) return current` наступает **после** stock lock; warehouse не проверяется. В случае legacy INACTIVE warehouse при ACTIVE reservation NEW fulfill может списать товар несмотря на status, что нарушает ORG contract.

**Предлагаемая узкая реструктуризация внутри INVENTORY**, без новой абстракции:

1. `OrderInventoryPort.fulfill` валидирует paired SALES+INVENTORY `Decision`, текущий actor/tenant/TX.
2. `InventoryReservationService.transition` читает reservation identity, затем `sales_order FOR UPDATE`; повторно читает reservation/current state **пока order lock удерживается**. Для `FULFILLED` или `RELEASED` exact terminal retry вернуть прежний receipt *без stock mutation*, для несовпадения terminal state — отказ.
3. Если текущий state ACTIVE и target `FULFILLED`, **до** stock row lock вызвать узкую eligibility `requireExistingActiveWarehouse(tenant, location)` — только location type/status + tenant и row lock, **не требовать PUBLISHED PRODUCT** (существующие исторические зарезервированные SKU могут быть ARCHIVED).
4. Затем `stock balance FOR UPDATE → reservation FOR UPDATE`; при условии совпадения текущих полей и state и достаточном onHand/reserved выполнить единственную проводку и `FULFILLED` transition; все изменения заказа и SKU в одной DB TX.
5. Если target `RELEASED`, **не** требовать ACTIVE warehouse, но сохранить order/stock/reservation lock, quantity checks и доказательство прав, чтобы cleanup legacy ACTIVE reservation прошёл и на INACTIVE/CLOSED.

**Новый обязательный порядок для корректной SALES fulfil:** IAM account/workspace → seller order → location (`FOR UPDATE`) → stock row → reservation; для terminal retry запрещено создавать новую ledger. `ORG.deactivate/close`: IAM → location FOR UPDATE → **read-only EXISTS** stock/reservations/stocktake, без order/stock locks, поэтому не вводится цикл order→location→order. Обязателен PostgreSQL тест с барьерами, а не предположение.

**Важная техническая тонкость:** внутренние `InventoryReservationService.reserve/release/fulfill` имеют package-private methods и собственный `@Transactional`; квалификацию сценариев проводить прежде всего через `AuthenticatedSalesCommands`/`OrderInventoryPort`, а не обходом IAM через прямой service call. Test-only synthetic low-level fixture допустим, но отдельно маркируется.

## 7. Детерминированные concurrent schedules и rollback

| Race | Если первая транзакция победила | Если победила вторая | Неизменяемый инвариант |
|---|---|---|---|
| `confirm(order,L)` vs `deactivate(L)` | Confirm commits → reserved+ACTIVE; deactivate видит dependency и DENY | Deactivate commits → reserve видит INACTIVE и DENY; SALES order остаётся PLACED | Не существует CONFIRMED без резерва после commit |
| `cancel(CONFIRMED)` vs `deactivate(L)` | Cancel releases → deact может пройти после исчезновения reservations | При ACTIVE reservation deact не может пройти раньше cancel; может вернуть blocker | Никогда не оставить живой резерв после успешной паузы |
| `fulfill(CONFIRMED)` vs `deactivate(L)` | Fulfill commits → reserved=0; deact может пройти после проверки других blockers | При ACTIVE reservation deact DENY; у legacy anomaly NEW fulfill denied | Одно списание, не два |
| `confirm` vs `cancel(PLACED)` | Confirm первым: cancel требует paired release или DENY по grants | Cancel первым: confirm DENY terminal; no reserve | Не оставить CANCELLED с ACTIVE reserve |
| `cancel` vs `fulfill` | Ровно один terminal commit из-за seller order row lock | Другой получает terminal conflict/replay status | Нет и RELEASED, и FULFILLED одной reservation |
| `close(L)` vs historical `confirm/fulfill` retry | Replay после permissions возвращает доказанный результат | Close законно только при отсутствии active/reserved/onHand | Ни один replay не пишет новый stock/event |
| `confirm` multi-SKU second-line failure | Rollback first-line reserve, order status, status event | n/a | PLACED, 0 active reservations from failed attempt |
| `fulfill` multi-line failure | Rollback ранее списанные lines и movement | n/a | CONFIRMED, исходный reserved+onHand |

PostgreSQL `READ COMMITTED` и обычные `EXISTS` в ORG безопасны только если все новые STOCK writers используют location lock до их записи; не делать label-only status check без lock. Для rejected warehouse судить по **коммитнутому** DB state, а не моменту SQL SELECT в гонке.

## 8. Ошибки и права (сервисный R1, не HTTP)

| Причина | Право и когда | Что не должно произойти |
|---|---|---|
| `ORDER_NOT_FOUND` / foreign tenant | Seller permission, scoped order lock | Никаких cross-tenant existence/qty/PII |
| `ORDER_NOT_CONFIRMABLE` | SALES_ORDER_CONFIRM + INVENTORY_RESERVE | Нет частичных reservation |
| `WAREHOUSE_NOT_ACTIVE` / `NOT_WAREHOUSE` | После paired admission + locked eligibility | order status не фиксируется CONFIRMED |
| `ORDER_RESERVATION_INCOMPLETE` | После order lock + per-line evidence | Нет COMMITTED/COMPLETED без полной связки |
| `ORDER_SKU_UNRESOLVED` | Новый reserve при legacy NULL immutable snapshot | Не угадывать SKU из live offer |
| `ORDER_INCONSISTENT` | Статус vs reservation/movement нарушен | Fail closed, не исправлять автоматически |
| `WAREHOUSE_FULFILLMENT_BLOCKED` | NEW fulfill ACTIVE reservation на INACTIVE/CLOSED | Не списывать onHand, не завершать заказ |
| `INSUFFICIENT_STOCK` | Locked stock row after eligibility | Никаких oversell/частичных mutations |
| `ACCESS_DENIED` | VerifiedActor; current IAM role/tenant; paired decisions | Нельзя выполнить mutation по одному permission |
| `STALE_ORDER_STATE` / terminal conflict | Под locked seller order row | Нет phantom status events или перезаписи terminal state |

Existing code now throws broad `IllegalStateException/IllegalArgumentException/SecurityException`. Таблица — **проектные reason codes**, не утверждение о реализованной error taxonomy или HTTP mapping. Не добавлять generic exception framework во время freeze.

## 9. PostgreSQL qualification plan ORG-P06-Q01…Q20 (NOT RUN)

| ID | Scenario | Expected |
|---|---|---|
| P06-Q01 | PLACED + ACTIVE warehouse + 2 published SKU + enough stock → confirm | CONFIRMED, 2 matched ACTIVE reservations, stock reserved, event once |
| P06-Q02 | PLACED + INACTIVE/CLOSED/BRANCH/POS location → confirm | Rollback, remains PLACED, no reservation |
| P06-Q03 | Confirm with insufficient stock on second line | First reserve rollback, no status/events |
| P06-Q04 | Confirm same order & warehouse replay | Exact line/item/qty/status evidence, no duplicate event |
| P06-Q05 | Confirm replay after ACTIVE→INACTIVE/close legacy data | Read-only only if consistent prior committed reservation, current warehouse not re-required |
| P06-Q06 | Confirm replay with changed warehouse or corrupted reservation SKU/qty | Explicit conflict, no silent success |
| P06-Q07 | PLACED cancel with SALES_ORDER_CANCEL only and zero reservations | CANCELLED, no INVENTORY_RELEASE required, no inventory mutation (new contract) |
| P06-Q08 | PLACED cancel with hidden legacy ACTIVE reservation | ORDER_INCONSISTENT, no orphan release bypass |
| P06-Q09 | CONFIRMED/PREPARING cancel with paired grants, release | CANCELLED, reservation RELEASED, reserved zero, one event |
| P06-Q10 | Cancel on INACTIVE/CLOSED with synthetic legacy ACTIVE reservation | Permitted cleanup with valid paired IAM; no new movement |
| P06-Q11 | New fulfill confirmed order ACTIVE location | Exactly one FULFILLMENT movement per line, onHand/reserved debited, COMPLETED |
| P06-Q12 | New fulfill ACTIVE reservation on legacy INACTIVE/CLOSED | Denied WAREHOUSE_FULFILLMENT_BLOCKED; no onHand debit |
| P06-Q13 | COMPLETED replay after warehouse CLOSED | Same terminal result, no stock row/event/ledger mutation |
| P06-Q14 | CANCELLED replay with inconsistent FULFILLED/ACTIVE reservations | Fail closed; no fictitious clean state |
| P06-Q15 | Confirm×deactivate racing, two PostgreSQL connections both commit orders | Either CONFIRMED+deactivation denied, or INACTIVE+PLACED; never CONFIRMED orphan |
| P06-Q16 | Cancel×deactivate with existing reserve | Cancel first may permit pause; pause first must reject due blocker |
| P06-Q17 | Fulfill×deactivate with existing reserve; legacy anomaly included | One terminal movement; pause cannot invalidate active reserved order |
| P06-Q18 | Confirm×cancel and cancel×fulfill multi-line, 2–3 PG sessions | Exactly one legal transition; no mixed RELEASED/FULFILLED |
| P06-Q19 | Different tenants, staff A/B/AB, wrong warehouse, missing one permission, revoke mid-command | No cross-tenant leaks / invalid mutation |
| P06-Q20 | Full rollback/replay after simulated failure post-first reserve or fulfillment | Zero orphan events/reservations/movements, safe successful retry |

**Evidence requirements:** exact runtime SHA, PostgreSQL connections/barriers, order status/version, `sales_order_status_event` count, `inv_stock_balance.on_hand/reserved`, per-line `inv_stock_reservation`, unique `inv_stock_movement`, location status/version, actor and business event provenance, before/after snapshot; no skip. Existing regressions `SalesInventoryRbacQualificationTest` cover core atomic confirm/cancel/fulfill, rollback, permission revocation and cancel-vs-fulfill; `InventoryReservationQualificationTest.q123ReservationRejectsInactiveWarehouseServiceAndArchiveButHonorsOldCommit` covers new reserve vs manually-set inactive warehouse. **НЕТ** текущего service-level real `ORG.deactivate`, поэтому не заявлять 06 tests as GREEN.

## 10. Приоритеты, итог, нецели

| Priority | ID | Owner | Minimal implementation after spec |
|---|---|---|---|
| P0 | ORG-SA06-01 | ORGANIZATION | Lifecycle row-lock + inventory dependency blocker for reserved/ACTIVE/OPEN stocktake and closed zero-stock; real E2E |
| P0 | ORG-SA06-02 | SALES | Immutable order-line SKU snapshot and per-line reserve/replay proof (already designed SALES pass03/04) |
| P1 | ORG-SA06-03 | INVENTORY | Narrow existing-ACTIVE-WAREHOUSE admission for NEW fulfill **before** stock lock, no PRODUCT republish check, allow terminal replay and release |
| P1 | ORG-SA06-04 | SALES | PLACED cancel SALES-only permission with proved 0 reservations; CONFIRMED cancellation paired permissions; state allowlist |
| P1 | ORG-SA06-05 | QA / cross-domain | Deterministic PostgreSQL confirm/cancel/fulfill×deactivate, rollback and A/B IAM races |

**Не делать:** повторную систему order/location ACL, новый warehouse_id в PLACED, новый generic event bus/state machine, автоматическую отмену при закрытии, rewrite immutable history, fulfillment через correction вместо reservation, новый microservice SALE/WAREHOUSE или Redis locks. Для quantity R1 не подменять `COMPLETED` доказательством оплаты/доставки.

**Итог:** ORGANIZATION pass 06/12 **SOURCE REVIEWED / CONTRACT SPECIFIED**; сервисная зрелость **1/5**; код новых ORG lifecycle отсутствует. **Следующий 07/12 — IAM, Tenant RBAC, VerifiedActor & Integration Admission**, с обязательным промежуточным closure report и передачей оставшихся проходов 08–12 в явный deferred backlog согласно изменённой последовательности пользователя.
