# RM — SALES / AutoOrder: PASS 04/12 — команды, состояния, разрешения и резерв

**Снимок исходников:** `fe50589b88d104843623ed598024e0de962bbcc2`, branch `base_dev`, 2026-11-22. **Состояние:** SOURCE VERIFIED / DETAILED TZ PROPOSED, не изменённый runtime и не новая PostgreSQL qualification. **Feature Freeze:** без контроллеров, новых веток и merge main; production HOLD.

**Просмотрено:** `AutoOrderService`, `AuthenticatedSalesCommands`, `SalesInventoryCommandService`, `OrderState`, `OrderInventoryPort`, `InventoryReservationService`, `StockEligibility`, `InventoryTenantSettingsService`, `DomainPermission`, `TenantAuthorization`; `SalesInventoryRbacQualificationTest`, `InventoryReservationQualificationTest` и предыдущее PASS 02/03 ТЗ. В отличие от старого снимка, текущий INVENTORY уже содержит транзакционный `StockEligibility.requireReservable` с блокировкой `org_location` ACTIVE WAREHOUSE и `cat_item` PUBLISHED PRODUCT перед новым резервом.

## 1. Фактическая машина состояний и допустимые переходы

`OrderState` перечисляет `PLACED`, `CONFIRMED`, `PREPARING`, `READY_FOR_PICKUP`, `OUT_FOR_DELIVERY`, `COMPLETED`, `CANCELLED`, `REJECTED`, но enum/DDL **не равны** реализованному переходу.

| Из → в | Действующий сервис/вход | Изменение резервов и остатков | Статус проверки |
|---|---|---|---|
| — → PLACED | `AutoOrderService.checkout` через опубликованный storefront | Нет резерва; 1 status event `PLACED`, чек и frozen title/price | **Реализовано** |
| PLACED → CONFIRMED | `AuthenticatedSalesCommands.confirm` | Для каждой строки ровно один ACTIVE reserve; `reserved += qty`, `on_hand` неизменён | **Реализовано**, SKU snapshot пока отсутствует |
| CONFIRMED → CONFIRMED | повтор `confirm` на том же location | Проверяет число ACTIVE и совпадение location; effect=0, без новых events | **Реализовано**, проверка не сравнивает построчно SKU/qty |
| PLACED → CANCELLED | `AuthenticatedSalesCommands.cancel` | 0 резервов ожидается, событие CANCELLED, effect=0 | **Реализовано**, сейчас требует лишний INVENTORY_RELEASE |
| CONFIRMED → CANCELLED | `cancel` | Все ACTIVE reservations → RELEASED; `reserved -= qty`, `on_hand` прежний | **Реализовано** |
| PREPARING → CANCELLED | `cancel` | Такой же release; **нет публичной команды**, которая вводит PREPARING | Поддержан входной статус, недостижим через полный публичный workflow |
| CONFIRMED/PREPARING → COMPLETED | `fulfill` | Каждый ACTIVE → FULFILLED; `on_hand -= qty`, `reserved -= qty`, immutable stock movement, completed event | **Реализовано**, нет отдельной доставки/оплаты |
| COMPLETED → COMPLETED | повтор `fulfill` | Проверяется общее число FULFILLED; effect=0 | **Реализовано**, нужно сильнее проверять identity каждой line |
| CANCELLED → CANCELLED | повтор `cancel` | Нет ACTIVE reservations, effect=0 | **Реализовано**, historical state не переходит |
| PLACED → REJECTED | Нет команды `reject` | Никакого INVENTORY reserve, требуются reason/audit | **НЕ реализовано**; только enum + SQL status |
| CONFIRMED → PREPARING | Нет команды `startPreparing` | Должен сохраняться ACTIVE резерв | **НЕ реализовано** |
| CONFIRMED → READY_FOR_PICKUP / OUT_FOR_DELIVERY | Нет команд | Логистика/передача покупателю не моделируется | **НЕ реализовано** |
| Любое → PAID/REFUNDED/DELIVERED | Нет статусов/сервиса | Не считать выполнением склада | **Вне R1**, FINANCE/LOGISTICS |

**Консервативная целевая машина SALE-R1.4:** `PLACED→CONFIRMED→COMPLETED`, `PLACED→CANCELLED`, `PLACED→REJECTED`, `CONFIRMED→CANCELLED`. `PREPARING` допускается только как отдельная P1-команда после явного бизнес-решения; READY_FOR_PICKUP/OUT_FOR_DELIVERY не рекламировать как рабочие статусы. Все terminal states immutable для этих команд.

## 2. Командные контракты: inputs, permission и write effects

| Команда / сервис | Admission и валидации | SQL и состояние | Ошибки / повтор |
|---|---|---|---|
| `confirm(VerifiedActor,tenant,order,warehouse)` | `SALES_ORDER_CONFIRM + INVENTORY_RESERVE`; current transaction decisions one actor+tenant; ненулевой order/location; tenant PLACED | Locks actor/workspace; order `FOR UPDATE`; `UPDATE status=CONFIRMED,row_version++ WHERE status=PLACED`; `SELECT sales_order_line JOIN store_offer`; `OrderInventoryPort.reserve` (MANDATORY) → SQL balance/reservations/events; append CONFIRMED event | Insufficient stock/SKU/warehouse → rollback status+reserve+event; already CONFIRMED same location → zero-effect; different location reject |
| `cancel(VerifiedActor,tenant,order)` | Current **both** `SALES_ORDER_CANCEL + INVENTORY_RELEASE`, even for PLACED | order lock; read ACTIVE/FULFILLED reservations; CAS order status; call `OrderInventoryPort.release` each ACTIVE; event | COMPLETED/REJECTED/READY/OUT reject. Already CANCELLED 0 effect if no ACTIVE |
| `fulfill(VerifiedActor,tenant,order)` | `SALES_ORDER_FULFILL + INVENTORY_FULFILL`; state CONFIRMED or PREPARING | order lock; count active; each `InventoryReservationService.fulfill` updates balance/reservation and inserts immutable movement; CAS COMPLETED and event | Missing reservation→rollback; COMPLETED replay zero-effect if fulfilled count == lines; never debit twice |
| Proposed `reject(VerifiedActor,tenant,order,reasonCode,expectedVersion)` | Recommend `SALES_ORDER_CANCEL` (avoid new permission), **PLACED only**, uppercase code 3..64; no INVENTORY grant when zero reserve | Lock order; require `row_version=expectedVersion`; assert no ACTIVE/FULFILLED reservation; `PLACED→REJECTED` + status event with actor/reason, **no stock mutations** | retry with same expectedVersion is stale-version conflict; do not claim idempotency without persisted request key |
| Proposed `startPreparing(VerifiedActor,tenant,order,expectedVersion)` | Optional P1; reuse `SALES_ORDER_FULFILL`; CONFIRMED only; full ACTIVE reserves | `CONFIRMED→PREPARING`; one event, no stock debit | avoid until product needs a separate picking handoff |

**Permission decision for R1 (to approve in pass 12):** `cancel(PLACED)` should require only `SALES_ORDER_CANCEL` if it has **zero** reservations; `cancel(CONFIRMED/PREPARING)` must require `SALES_ORDER_CANCEL + INVENTORY_RELEASE`. Derive permission **after order lock/state read**, while retaining IAM actor→workspace admission lock order and no cross-domain privilege bypass. A standalone pre-confirm cancel may not call `OrderInventoryPort.release` or issue a stock decision. Existing command-level SELECT and transaction fences cannot be silently weakened.

## 3. Stock invariants / atomicity

For tenant order `O`, let `L` be the set of order lines; each full line quantity `q_i > 0` mapped to immutable SKU and one warehouse in R1.

- PLACED has **no** active/fulfilled reservation. CONFIRMED has **exactly one ACTIVE reservation per line** (matching lineId, orderId, tenant, expected SKU, warehouse and quantity). Not just `COUNT(ACTIVE)=COUNT(lines)`.
- Completed has **exactly one FULFILLED reservation for each line** and exactly one FULFILLMENT movement per fulfilled reservation; `reserved` net zero, `on_hand` debited by each line quantity. Cancelled has no ACTIVE reservation and no new fulfillment movement.
- Current implementation checks count and one location; referential FK enforces line membership, reservation unique(tenant,line), and movement unique(tenant,reservation), **but** the SKU snapshot was not present at reviewed HEAD; strengthen replay checks after PASS 03 migration.
- `OrderInventoryPort` demands paired `TenantAuthorization.Decision` tokens, same actor/tenant/current transaction. IAM fence is transaction scoped, not a new token scheme.
- `StockEligibility.requireReservable` now checks ACTIVE WAREHOUSE + published PRODUCT via `SELECT ... FOR UPDATE`. It is invoked **only for a new reservation**, after `InventoryReservationService.reserve` first checks existing same-line ACTIVE reservation. **Existing confirm replay intentionally does not re-evaluate current warehouse/catalog status**; this preserves legitimate retry after closure/archive, but must verify committed reservations and not create new ones. Do not demand currently published SKU for cancelling or fulfilling old confirmed orders.
- Transaction lock ordering currently: `IAM actor/workspace` → `SALES order` → `inventory location`/`catalog SKU` eligibility → `stock balance` → `reservation`. For multi-SKU orders, deterministic order by catalog SKU and line ID reduces deadlocks; check against ORG deactivate and CATALOG archive via PostgreSQL concurrency tests, not by claims alone.
- Keep one-seller/one-location R1, no partial shipment, no split reserve, no auto-substitute out-of-stock SKU. No stock mutation on reject/PLACED cancel.

## 4. New findings and corrected earlier assertions

| ID | Severity | Fact at reviewed SHA | Required specification adjustment |
|---|---|---|---|
| SA4-01 | P0 product | Eight statuses are declared; only PLACED/CONFIRMED/CANCELLED/COMPLETED are directly written by public SALES+checkout | Explicit command-based state machine, no implicit READY/OUT/REJECTED writer |
| SA4-02 | P1 least privilege | Cancel PLACED requires INVENTORY_RELEASE before checking state, despite no reserve | State-dependent permission with SQL proof of zero reservations |
| SA4-03 | P1 business | REJECTED declared but no reject command | Typed reason code, verified actor, CAS, immutable event; no generic status setter |
| SA4-04 | P1 integrity | CONFIRMED replay and COMPLETED replay verify only counts and partial location; do not prove 1:1 line item/qty matching | Same-tenant per-line join, match SKU snapshot and qty, reservation/movement count, zero duplicates |
| SA4-05 | P0 dependency | SALES/INVENTORY still JOIN live offer SKU; checkout line snapshot missing | Retain PASS 03 immutable snapshot plan, fail-closed legacy unknown |
| SA4-06 | **UPDATED** | Inventory `StockEligibility` already fences ACTIVE WAREHOUSE / PUBLISHED PRODUCT for **new reserve**; older audit said guard absent | **Do not implement duplicate guard.** Qualify confirm against inactive branch/warehouse/archive and concurrency. Historical fulfill/replay allowed under immutable commitment |
| SA4-07 | P1 operational truth | `InventoryTenantSettingsService.updatePicking` deprecated; `AuthenticatedInventorySettings` reports `MANDATORY_ON_CONFIRM` and `NOT_IMPLEMENTED` picking | Existing FALSE flag intentionally does not change SALES reserve. Do not add alternate reserve states; retain truthful read-only semantics |
| SA4-08 | P1 evidence | sales status events lack actor/reason and PostgreSQL immutable trigger, whereas reservation/movement evidence already has actor and provenance | Add event actor/reason with additive migration after PASS 05, retain historical null; do not invent paid/delivered state |
| SA4-09 | P1 QA | cancel-vs-fulfill PostgreSQL race tested (one terminal), rollback/retry tests exist; reject and cancel(PLACED) least privilege are NOT tested | Add precise state/permission/concurrency suite before status change rollout |
| SA4-10 | P1 subtlety | `cancel` permits any nonterminal state except a fixed deny set, including PREPARING; changes to enum in future could silently become cancellable | Switch to explicit allowlist `PLACED/CONFIRMED/PREPARING` with verified prerequisites; reject unknown/new state fail-closed |

## 5. Test matrix for upcoming implementation (NOT executed now)

| ID | Scenario and independent PostgreSQL assertion |
|---|---|
| SALE-SM04-01 | PLACED→CONFIRMED: full reserve per line, active warehouse/Product; exactly one event, no movement or on_hand debit |
| SALE-SM04-02 | CONFIRMED retry same warehouse after SKU archived or warehouse disabled: no new reserve; validate old committed reserves and no new state event |
| SALE-SM04-03 | CONFIRMED retry different warehouse, incomplete/wrong-SKU/wrong-quantity reservation → fail closed, no mutation |
| SALE-SM04-04 | PLACED cancel by SALES_ORDER_CANCEL-only employee, zero reserves → CANCELLED; no INVENTORY_RELEASE required and no inventory writes |
| SALE-SM04-05 | CONFIRMED cancel without INVENTORY_RELEASE → deny before state/stock change; with both grants release once |
| SALE-SM04-06 | PLACED reject with reason + expectedVersion → REJECTED, one immutable event, no reserve/movement; repeat stale version fails |
| SALE-SM04-07 | CONFIRMED/PREPARING/COMPLETED cancel/reject legal state table, invalid and unknown states fail closed |
| SALE-SM04-08 | CONFIRMED→COMPLETED: each line FULFILLED once, one movement per reservation, stock correct; idempotent replay |
| SALE-SM04-09 | Cancel vs fulfill after confirm, two PostgreSQL transactions → one terminal result and consistent reservations/movements/events |
| SALE-SM04-10 | Reject vs confirm concurrent from PLACED → only one wins, no conflicting events or orphan stock reservations |
| SALE-SM04-11 | Permission revoke/suspend during confirm/cancel/fulfill: current transaction fencing and no unadmitted partial effects |
| SALE-SM04-12 | Rollback after last inventory operation before event/commit, then replay: exactly one committed outcome |
| SALE-SM04-13 | Foreign tenant order/location, A+B membership with asymmetric grants, null/expired VerifiedActor rejected |
| SALE-SM04-14 | Existing legacy NULL SKU snapshot orders remain readable; new reserve/confirm fail closed, no substitution from mutable offer |
| SALE-SM04-15 | Actor/reason event after populated migration; immutable SQL UPDATE/DELETE rejected, no loss of old history |
| SALE-SM04-16 | H2/PG populated migration, concurrency/EXPLAIN, exact-head workflows; no invented latency measures |

**Existing test evidence (not new tests):** `SalesInventoryRbacQualificationTest.confirmAndCancelAreAtomicAndRetriesAreIdempotent`, `insufficientStockRollsBackStatusAndEveryReservation`, `fulfillmentDebitsOnceAndRequiresBothDomainPermissions`, `postgresSalesConfirmationFencesConcurrentPermissionRevocation`, `r13RollbackAfterSuccessfulDomainCallsCanBeSafelyRetried`, `r13PostgresCancelVsFulfillRaceHasExactlyOneTerminalOutcome`, `q123LegacyAutoReserveFalseCannotDisableRequiredSalesConfirmationReserve`; `InventoryReservationQualificationTest.q123ReservationRejectsInactiveWarehouseServiceAndArchiveButHonorsOldCommit`. These support the current partial functionality, **not** new reject/state-dependent cancellation or immutable SKU snapshot.

## 6. Smallest implementation slices and admission

1. **SALE-R1.4A (after PASS 03 schema):** explicit transition allowlist and correct replay validation, retaining existing two-permission guard on reserved operations; negative tests.
2. **SALE-R1.4B:** `reject` with typed reason/expectedVersion and immutable status event; no release or reserve, no new RBAC code. Alternative to rewrite of state machine.
3. **SALE-R1.4C:** separate PLACED vs CONFIRMED cancel permission, preserving lock order, revoked-permission races, and nil-reserve negative tests.
4. **SALE-R1.4D:** populate-safe evidence additions and concurrent qualification (reject/confirm, cancel/fulfill, warehouse deactivate/reserve), only after agreed business semantics.
5. **Explicitly deferred:** payments, refunds, delivery logistics, partial fulfillment, manual editing of already placed buyer orders, new workflow engine, Kafka/outbox and REST.

**Verdict:** **PASS 04/12 SOURCE VERIFIED / STATE MACHINE SPEC PROPOSED**, NOT IMPLEMENTED; SALES maturity remains **2/5**. Next **05/12** — seller detail/line/history, buyer own detail, scoped pagination and PII.
