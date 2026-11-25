# SALES / AutoOrder — проход 06/12: транзакции, идемпотентность, конкурентность

**Reviewed source SHA:** `51d410865c90cfb92e5b28df5f273f7d9c355103` (base_dev, 2026-11-22). **Статус:** SOURCE REVIEWED / TX SPEC PROPOSED; не реализовано, новые PostgreSQL-тесты не запускались, production HOLD. Во время подготовки документа base_dev параллельно обновлялась; выводы привязаны к названному SHA и требуют rebase ревью перед реализацией.

## 1. Транзакционные контракты существующих методов

| Операция | Реальный lock / SQL order | Атомарные последствия | Семантика повторов |
|---|---|---|---|
| `AutoOrderService.checkout(slug,email,key,lines)` | `@Transactional` → PUBLISHED storefront SELECT (без lock) → checkout stripe (`crm.sales_checkout_lock` bucket 0..255 `FOR UPDATE`) → lookup `sales_checkout_receipt` → отдельные незаблокированные offer SELECT → IAM `findOrProvision` с email stripe → INSERT order, lines, PLACED event, receipt, email challenge/outbox | В одном DB TX сохраняет IAM identity/заказ/receipt/outbox; не гарантирует отправку внешнего письма | `(tenant_id,idempotency_key)` уникален; hash нормализованного email + отсортированных объединённых offer/quantity, сравнение SHA-256 constant-time; same key returns same orderId и **текущий** status; changed payload error; retry после PAUSED storefront блокируется **до** чтения receipt; replay не переотправляет email |
| `verifyEmail(challenge,token)` | `@Transactional`, IAM challenge `FOR UPDATE` + single-use token, затем `UPDATE sales_order` для всех подходящих `buyer_user_id IS NULL` с verified CURRENT identity | Подтверждение identity и привязка заказа в одной БД транзакции | Второе потребление токена отклоняется, это не повторяемый success. OIDC session не создаётся |
| `confirm(actor,tenant,order,warehouse)` | `SALES_ORDER_CONFIRM + INVENTORY_RESERVE` через `requireForCommand` до `order FOR UPDATE`; отсортированные order lines; `OrderInventoryPort.reserve` MANDATORY → `StockEligibility` locks location+SKU → stock balance `FOR UPDATE` → reservation | PLACED→CONFIRMED, все ACTIVE reserves, stock reserved increments, event — **единый commit** | CONFIRMED replay проверяет count ACTIVE==line count и warehouse, не проверяет построчно item/quantity |
| `cancel(actor,tenant,order)` | `SALES_ORDER_CANCEL + INVENTORY_RELEASE` **до** order lock (включая PLACED); SELECT ACTIVE/FULFILLED; release через MANDATORY | CANCELLED + release каждого резерва + событие — единый commit | CANCELLED replay без ACTIVE returns zero, не повторяет events |
| `fulfill(actor,tenant,order)` | `SALES_ORDER_FULFILL + INVENTORY_FULFILL` → order lock → ACTIVE reservations sorted → inventory stock/reservation locks + immutable movement | COMPLETED, FULFILLED per line, `on_hand` и `reserved` списаны, events + movements — единый commit | COMPLETED replay по count fulfilled==lines, но не доказывает движение или точность каждой строки |
| Seller `search` | Writable `@Transactional` для IAM `requireForCommand` shared locks; отдельно COUNT и paged SELECT | Нет записи в order | При READ COMMITTED два SQL не обязательно видят одно и то же состояние; стабильный сорт не означает snapshot |
| Buyer `mine` | `@Transactional(readOnly=true)`, проверка ACTIVE user + verified CURRENT email, unbounded SELECT by buyer_user_id | Нет записей | Нет LIMIT; на долгой истории нужен paging из PASS 05 |

**Блокировки:** checkout stripe→IAM email stripe; seller: IAM account/workspace shared-lock→order FOR UPDATE→location/SKU FOR UPDATE на новом резерве→balance FOR UPDATE→reservation. Отзыв permissions в IAM сериализуется с admitted seller commands; операция, вошедшая до revocation, может законно завершиться раньше отзыва. `OrderInventoryPort` проверяет обе transaction-bound IAM decisions с идентичными actor/tenant, `Propagation.MANDATORY`. Не вводить новый global mutex и не менять порядок блокировок без конкурентного теста.

## 2. Подтверждённые пробелы и решения

| ID | Риск / факт из кода | Приоритет | Минимальная доработка |
|---|---|---|---|
| SA6-01 | Повтор checkout после PAUSED storefront не доходит до receipt из-за ранней проверки PUBLISHED | P1 | Рекомендуется разрешить same-key same-payload возврат уже принятого заказа после PAUSED; новый key — запретить. Сначала закрепить tenant slug mapping и политику SUSPENDED tenant |
| SA6-02 | Offer title/price/SKU читаются разными unlocked SELECT, изменение во время multi-offer checkout может дать смешанный snapshot | P0 | Lock offer rows в детерминированном порядке до записи snapshot, плюс PASS 03 immutable SKU |
| SA6-03 | Confirm/reserve повторно смотрят live `store_offer.catalog_item_id` | P0 | Использовать `sales_order_line.catalog_item_id_snapshot`, fail closed на NULL legacy; никакого `COALESCE(current offer)` |
| SA6-04 | CONFIRMED и COMPLETED replay сверяет counts, но не item/qty/каждую line/movement | P1 | Tenant-scoped join line→reservation→movement; без write в replay |
| SA6-05 | PLACED cancel требует INVENTORY_RELEASE, даже когда резервов нет | P1 | Решить permission после order lock: SALES_ORDER_CANCEL only при доказанном zero reserve; две permissions для CONFIRMED/PREPARING release |
| SA6-06 | Buyer history unbounded; seller COUNT/page отдельными statement | P1 | Paging + ограниченные read-model в PASS 05; выбрать PostgreSQL snapshot semantics по tests |
| SA6-07 | Checkout outbox существует, но return PLACED не означает доставку; replay не создаёт новый challenge | P1 | IAM cooldown-bound resend command отдельно от создания заказа; dispatcher only committed outbox |
| SA6-08 | `Math.addExact` aggregate duplicate offer qty может превысить лимит 1000; единая сумма не ограничена по precision до INSERT | P1 | Validate merged per-offer quantity, aggregate BigDecimal bounds и controlled input error |
| SA6-09 | SALES status events и order lines на уровне текущего DDL не имеют immutable trigger | P1 | Additive immutability/evidence migration после populated compatibility, не в рамках TX pass |
| SA6-10 | PG revocation fence тестирован для confirm, но cancel/fulfill отдельной race matrix не покрыты | P1 | Revoke versus each command, two tenant members A/B/AB |
| SA6-11 | 256 checkout stripe buckets сериализуют часть независимых keys | P2 | Сначала измерить PostgreSQL lock waits, throughput/p95/p99; не менять корректную схему на Redis без evidence |
| SA6-12 | Тестируются rollback после успешных confirm/cancel/fulfill, но нет полного checkout+IAM identity/outbox failure injection | P1 | Failure after identity/lines/receipt/outbox → assert zero orphan and safe retry |

**Точность:** отличать idempotent *side effects* от неизменного HTTP-ответа. Checkout возвращает current status. `verifyEmail` single-use, не idempotent success. PAUSED replay — предметное решение, а не доказанная security vulnerability. Не обещать exact snapshot между PostgreSQL READ COMMITTED SELECT.

## 3. Обязательные инварианты rollback

- **Checkout**: после ошибки до DB COMMIT отсутствуют новые IAM identity, order/lines, PLACED event, receipt, challenge/outbox; sender не должен отправлять uncommitted email.
- **Confirm**: если у последней SKU нет запаса, вся уже записанная резервная часть откатывается вместе со статусом CONFIRMED и событием.
- **Cancel**: release любого резерва падает → rollback всех ранее освобождённых и CANCELLED event/order status.
- **Fulfill**: любое движение/резерв не записано → rollback всех stock deltas, terminal events и order status.
- **Revoke**: после вступления отзыва в силу новый command не получает grant; already-admitted TX может корректно завершиться до revoke commit.
- **Read**: ни один concurrent SELECT не должен видеть незафиксированный промежуточный write; SELECTs при READ COMMITTED могут увидеть разные committed revisions.

## 4. Проверенные существующие тесты — только их фактическая область

- **AUTOORDER** `AutoOrderR1QualificationTest.retryIsIdempotentAndChangesAreRejected`, `concurrentSameCheckoutKeyCreatesOneOrderAndOneIdentity`, `concurrentDifferentTenantsSameEmailReuseGlobalAccount`, `suspendedAccountCannotVerifyEmailOrReceiveNewOrders`.
- **SALES** `SalesInventoryRbacQualificationTest.confirmAndCancelAreAtomicAndRetriesAreIdempotent`, `insufficientStockRollsBackStatusAndEveryReservation`, `concurrentOrdersCannotOversellAndCrossTenantCannotCommand`, `opposingMultiSkuOrdersReserveWithoutDeadlock`, `postgresSalesConfirmationFencesConcurrentPermissionRevocation`, `postgresMixedConcurrentOrderLifecyclePreservesLedgerAndIdempotency`, `r13RollbackAfterSuccessfulDomainCallsCanBeSafelyRetried`, `r13PostgresCancelVsFulfillRaceHasExactlyOneTerminalOutcome`, `finalSecGateRejectsMixedActorTenantAndMissingInventoryPermits`, `q4SalesReserveAndFulfillKeepStaffAttributionAndSourceOrder`, `q9WarehouseFirstReceiptToSellerFulfillmentProducesAuditableQuantityChain`.
- **INVENTORY** `InventoryReservationQualificationTest.reservationReleaseAndIdempotentReplay`, `fulfillDebitsExactlyOnceAndPostsImmutableMovement`, `twoConcurrentOrdersCannotOversellSameSku`, `q123ReservationRejectsInactiveWarehouseServiceAndArchiveButHonorsOldCommit`.
- **SALES QUERY** `SalesOrderQueryQualificationTest.postgresPaginationRemainsTenantIsolatedDuringConcurrentStatusChanges`.

Описаны **наличие тестов в source**, а не их новый успешный запуск. PostgreSQL-ветки используют `Assumptions.assumeTrue` и могут быть skipped в H2. Диагностические micro-sample latency tests не являются SLA или performance qualification.

## 5. Новая квалификационная матрица PASS 06 — NOT IMPLEMENTED

| Test ID | Expected result |
|---|---|
| SALE-TX06-01 | Concurrent same-key/same-payload checkout across transactions → one receipt/order/event/challenge, same ID |
| SALE-TX06-02 | Same key/different normalized buyer or basket → strict conflict, no extra PII/order |
| SALE-TX06-03 | Paused storefront: accepted same-key replay per approved rule, new key rejected; suspended workspace separate rejection policy |
| SALE-TX06-04 | Inject failure after IAM identity, lines, receipt, outbox → full rollback; retry creates one accepted order |
| SALE-TX06-05 | Concurrent offer price/SKU change, multi-line checkout preserves one accepted product snapshot, no deadlock |
| SALE-TX06-06 | Concurrent confirm/replay same order and two orders competing stock → no oversell/duplicate reserve |
| SALE-TX06-07 | Insufficient SKU on second line → no partial reservation/event/stock delta |
| SALE-TX06-08 | Double cancel, double fulfill and cancel-vs-fulfill → exactly one terminal state, one movement per reservation |
| SALE-TX06-09 | Tampered replay reservation item/qty/location/movement → deny with no write |
| SALE-TX06-10 | IAM revoke vs confirm/cancel/fulfill; fresh denial, admitted transaction fencing and A/B tenant grants |
| SALE-TX06-11 | Email proof concurrent orders for same global buyer across sellers, no unrelated buyer link/session |
| SALE-TX06-12 | Buyer paging/seller search concurrent updates, no PII/tenant leak; documented count/page isolation |
| SALE-TX06-13 | Read order detail before/after commit, no uncommitted partial header/lines/reservations/events |
| SALE-TX06-14 | Warehouse deactivate/SKU archive vs new reserve, preexisting committed reserve replay |
| SALE-TX06-15 | Concurrent 2/6/12/32 workers with PG row locks: measured lock waits/deadlocks/p50,p95,p99 |
| SALE-TX06-16 | Populated-PG migration and exact-HEAD H2, PG, IAM/CRM, RustFS gates; no silent skipped tests |

## 6. Поэтапное ТЗ для R1.6

**SALE-R1.6A:** Checkout retry contract and bounded basket validation; verify pause and email outbox rollback. **SALE-R1.6B:** PASS 03 immutable offer/SKU snapshot and deterministic offer locking, real PG concurrency. **SALE-R1.6C:** Read-only per-line replay consistency validation. **SALE-R1.6D:** Revocation and rollback adversarial matrix incl. checkout+IAM+outbox and read-write consistency. No new distributed transaction engine, MQ, or Redis locks.

**Решения до кодирования:** разрешён ли replay при PAUSED/SUSPENDED storefront; current vs original status on retry (рекомендация current); read detail snapshot guarantee for count/header/lines/reserves; PLACED cancellation permission; legacy unknown SKU treatment.

**Verdict:** PASS 06 SOURCE REVIEWED / SPEC PROPOSED; maturity SALES 2/5, Java/DDL не менялись; next PASS 07/12 — settings flags, real consumer E2E. Production HOLD.
