# RM Platform — SALES / AutoOrder: PASS 05/12 — Read Model, order detail and status history

**Исходный HEAD для source-review:** `23f3d2048ea5335a2b463ed86b53b68eff229fab`, `base_dev`, 2026-11-22. **Результат:** PASS 05 SOURCE REVIEWED / QUERY CONTRACT SPECIFIED; **не Java implementation, не PostgreSQL qualification**. Feature Freeze: без HTTP controllers, новых веток, merge main. Production HOLD.

## 1. Реальный запросный контур — что уже есть

| Query / DTO | Подтверждённая SQL/permission семантика | Что пользователь реально получает | Недостаток |
|---|---|---|---|
| `AuthenticatedSalesQueries.search(VerifiedActor, tenantId, SalesOrderSearch, page,size)` | `actor.userId()` → `SalesOrderQueryService.search`; `TenantAuthorization.requireForCommand(...,SALES_ORDER_READ)` в writable transaction с IAM locks | `Page<SalesOrderDto>` | Только summary |
| `SalesOrderRepository.search` | `COUNT(*)` и отдельно `SELECT` из `crm.sales_order WHERE tenant_id=?` плюс optional status, created_at ≥from, <to; `ORDER BY created_at DESC,id DESC LIMIT/OFFSET` | `id,status,total_amount,currency,created_at,row_version`, без PII | Нет lines, detail, events, reservation; две SELECT при READ COMMITTED не единый snapshot |
| `SalesOrderSearch` | status strict enum allowlist, valid half-open interval; page≥0, size 1..100 | статус и период | Нет exact order ID, SKU и других подтверждённых фильтров; глубокий OFFSET не ограничен |
| `AuthenticatedBuyerOrders.mine(VerifiedActor)` | verified subject → package-private `AutoOrderService.buyerOrders(userId)`; require current VERIFIED email and ACTIVE account; SELECT `id,status` по `buyer_user_id=?` sorted created_at DESC,id DESC | История всех продавцов, только ID/status | Нет LIMIT, total/page, date, totals и line detail |
| `AuthenticatedInventoryQueries.getPosition/searchMovements/reservationHistory` | `INVENTORY_STOCK_READ`, same tenant, bounded pages | Физические позиции/движения/события резерва | Это складские read-API, **не** готовая карточка SALES; продавцу с SALES_ORDER_READ не нужно давать INVENTORY_STOCK_READ автоматически |

**Источник истины:** `crm.sales_order` header, `crm.sales_order_line` frozen title/price/quantity/currency, `crm.sales_order_status_event` chronological state facts, `crm.inv_stock_reservation` status/location/item/quantity, `crm.inv_reservation_event` history. В PASS 03 спроектирован будущий `catalog_item_id_snapshot`, текущий `sales_order_line` его ещё не содержит. Не читать живые `store_offer.title/unit_price` для детализации уже оформленного заказа.

## 2. Требуемая компактная карточка продавца

Одна точка входа в **существующем** `AuthenticatedSalesQueries`, с внутренними `SalesOrderQueryService` / `SalesOrderRepository`, без отдельного микросервиса или нового order read database:

```java
SalesOrderDetail get(VerifiedActor actor, UUID tenantId, UUID orderId)
Page<SalesOrderDto> search(VerifiedActor actor, UUID tenantId,
                           SalesOrderSearch filters, int page, int size)
Page<SalesOrderStatusEventDto> history(VerifiedActor actor, UUID tenantId,
                                       UUID orderId, int page, int size)
```

**Предлагаемые DTO:** 
- `SalesOrderDetail(orderId,status,totalAmount,currency,createdAt,rowVersion,List<OrderLineDto> lines,List<OrderLineReservationDto> reservations)`.
- `OrderLineDto(lineId,offerId,catalogItemIdSnapshot?,titleSnapshot,quantity,unitPriceSnapshot,currency,lineTotal)`.
- `OrderLineReservationDto(orderLineId,reservationId,locationId,itemId,quantity,state)`, без общего склада, цен закупки, баланса, customer identity.
- `SalesOrderStatusEventDto(eventId,eventType,occurredAt,actorUserId?,reasonCode?)`. Legacy NULL actor/reason отображать как `unknown/not recorded`, не придумывать инициатора.

**Data flow:** IAM `SALES_ORDER_READ` decision → exact tenant/order header EXISTS → ordered lines → per-order reservations → optional short bounded event preview or separate history page. Header, lines, reservations должны возвращаться из одной транзакции с согласованным снимком на PostgreSQL; под `READ COMMITTED` последовательные SELECT не гарантируют snapshot — утвердить либо `REPEATABLE READ` read-only после независимого admission fence, либо один SQL statement с grouped extraction / conditional query. **Не добавлять `@Transactional(readOnly=true)` на вызываемый код без проверки IAM `requireForCommand` (его shared locks требуют writable tx).** Рекомендация R1: **одна bounded read транзакция с гарантированной консистентностью, механизм выбрать по реальной PostgreSQL квалификации**; без универсального CQRS или материализованных views.

**Query contract:**
- Только `WHERE tenant_id=? AND id=?` в header и `WHERE tenant_id=? AND order_id=?` в children; foreign ID → not found без существования записи другого tenant.
- Проверять header перед children и не возвращать частичную карточку, если order не принадлежит tenant.
- Lines: `ORDER BY id ASC`; сумма `quantity*unit_price_snapshot` на Java `BigDecimal` без пересчёта по действующей цене. Сверить сумму со значением header и валютой; при несогласованности fail-closed / audit diagnostic, не исправлять silently в GET. Отдельный `UNRESOLVED_LEGACY_SKU` в статусе данных для неизвестных legacy SKU, не заменять текущим SKU оффера.
- Reservations: только имеющие привязку к строкам текущего order; `LEFT JOIN` нужен для PLACED без резерва. Разрешается показывать `reservation.locationId/itemId/state/quantity`, потому что это операционные сведения **заказа**; запрещено добавлять `inv_stock_balance.on_hand` / все движения только из SALES_ORDER_READ.
- Ограничить line count фактическим checkout admission R1 (≤100) и установить safety cap ≤200 для чтения legacy. Никаких N+1: максимум 3 scoped SQL для header/lines/reservations; history отдельно.
- Если бизнесу нужно видеть `available` и другой складской остаток, оставить `AuthenticatedInventoryQueries` с самостоятельным `INVENTORY_STOCK_READ`; не разрешать cross-domain permission bypass через detail.

## 3. История и происхождение событий

`crm.sales_order_status_event`: PK (tenant,id), FK (tenant,order), columns `event_type,occurred_at`, index `idx_sales_status_tenant_order_time(tenant_id,order_id,occurred_at DESC)`. Текущий DDL не содержит `actor_user_id` или `reason_code`, `CHECK event_type`, immutable trigger. Пока история обязана честно возвращать **только event id/type/time**. После PASS 04 предложения разрешены additive nullable поля + audit preservation, но **не** задним числом заполнять actor для старых событий.

ТЗ `history`: `SALES_ORDER_READ`, tenant/order presence, `page≥0,size 1..100`, stable `ORDER BY occurred_at DESC,id DESC`, `COUNT(*)` с идентичным predicate. Не смешивать order transition event и более подробные `crm.inv_reservation_event`: оператору можно показать reservation state в `get`, детальная складская история доступна только через INVENTORY permissions, иначе происходят утечки складского администрирования.

## 4. Buyer read: глобальная identity, но ownership по строке

В `AuthenticatedBuyerOrders` только `VerifiedActor`, без параметра `buyerId`. Держать проверку ACTIVE account + CURRENT verified email. Покупатель может видеть свои заказы **разных продавцов**; это НЕ tenant staff listing. Не использовать `tenantId` от покупателя как authorization guard. Рекомендуемые функции:

```java
Page<BuyerOrderSummary> mine(VerifiedActor actor,int page,int size);
BuyerOrderDetail ownDetail(VerifiedActor actor,UUID orderId);
```

`BuyerOrderSummary(orderId,sellerTenantId,status,totalAmount,currency,createdAt)`. Для `ownDetail` возвращать линии (id,titleSnapshot,quantity,unitPriceSnapshot,currency,lineTotal), состояния и **публично значимые** события. Не выдавать `pending_email_identity_id`, `buyer_user_id`, внутренние grants, reservation IDs, stock positions, creator email, customer/contact PII или другие организационные метаданные.

**Существенная модель данных:** `sales_order.id` уникален только внутри составного PK `(tenant_id,id)`, **глобальный UNIQUE(id) в DDL отсутствует**. Поэтому `ownDetail(actor,orderId)` с одним UUID должен требовать доказанную глобальную уникальность либо проходить через bound `(sellerTenantId,orderId)`. **Рекомендован контракт:** `ownDetail(VerifiedActor actor, UUID sellerTenantId, UUID orderId)` и `BuyerOrderSummary` возвращает `sellerTenantId`. SQL одновременно `WHERE buyer_user_id=? AND tenant_id=? AND id=?`; передача sellerTenantId — идентификатор поиска, **не источник полномочий**. Идентификатор чужого заказа всегда not found. Это важная поправка к PASS 01/02 контракту.

**Пагинация:** `page≥0`, `1≤size≤100` (рекомендуемый default 20), `ORDER BY created_at DESC,tenant_id ASC,id DESC`, точный buyer predicate в `COUNT` и `SELECT`, offset bound overflow проверять до SQL; глубокие страницы ограничить документированным ceiling по фактической нагрузке. Buyer history может быть длинной и не должна оставаться без LIMIT. Не выполнять JOIN children до определения page header (иначе нарушится count/rows из-за мультипликации).

## 5. Фильтры, PII, tenant isolation и эксплуатационная модель

**Seller `SalesOrderSearch`** сохранить status, `fromInclusive`, `toExclusive`. Не добавлять большой динамический DSL. Минимальная P1 доработка: optional `orderId` exact и status; SKU/product filter добавлять только при доказанной задаче, через `EXISTS` со scoped line snapshot, а не `JOIN` с умножением строк. Статус из enum может быть фильтром даже если writer для него пока не реализован (legacy данные); не выдавать это за достижимый новый status.

**PII-матрица:**

| Роль/read permission | Seller summary/detail | Buyer own summary/detail | Email/contact/customer identity | Inventory global balances/movements |
|---|---|---|---|---|
| Сотрудник с `SALES_ORDER_READ` в tenant | Да, только выбранный tenant | Нет, если это не его личный заказ | **Нет** (без отдельного CRM/PII permission) | Нет |
| Покупатель с verified actor + current email | Нет tenant-wide seller list | Да, свои заказы по `buyer_user_id` у разных продавцов | Только собственная identity через IAM профиль, не чужая PII | Нет |
| Сотрудник с отдельным `INVENTORY_STOCK_READ` | По SALES правам отдельно | Нет | Нет | Да, в пределах tenant и имеющихся inventory contracts |
| Глобальный platform admin без tenant grant | Нет implicit доступа | Нет | Нет | Нет |

**Запрет утечек:** ни seller DTO, ни buyer DTO не должны сериализовать `pending_email_identity_id`, `buyer_user_id`, plaintext email, IAM profile, verification token; не возвращать raw JPA Entity/ResultSet. Не делать fake CRM Customer linking из совпадающего email. Предварительный IAM admission и решение `SALES_ORDER_READ` нужны для каждого seller read; query helpers остаются package-private. Тесты A/B/AB: один пользователь в A+B с неодинаковыми grants; foreign order ID in A must be indistinguishable from nonexistent; zero detail/history rows and no PII, even under revoked permission.

## 6. Индексы/конкурентность: уже есть и что не дублировать

**Существующие:** `sales_order` PK(tenant,id), `idx_sales_order_tenant_created`, `idx_sales_order_buyer(buyer_user_id,created_at)`, `idx_sales_order_tenant_buyer_created`; `sales_order_line` UNIQUE (tenant,order,id) from rm:0025 — covers leftmost (tenant,order); `sales_order_status_event` history index (tenant,order,occurred_at DESC); `inv_stock_reservation` (tenant,order) index and UNIQUE(tenant,line). Не создавать такие же индексы повторно.

**Potential new index:** buyer global sort by `created_at DESC,tenant_id,id` может использовать existing buyer index partially; только после `EXPLAIN (ANALYZE,BUFFERS)` on representative synthetic volume и P95 measurement. Id tie-break stability guaranteed by full composite sorting, not necessarily index-only scan. Deep OFFSET risk, no invented latency. `COUNT` и `SELECT` в READ COMMITTED не разделяют один snapshot при concurrent updates — без обещаний математически неизменного total across requests.

## 7. Mandatory qualification (NOT implemented yet)

| ID | PostgreSQL-focused acceptance |
|---|---|
| SALE-RD05-01 | Seller summary status/time/page/filter, exact tenant predicate, no PII, stable tie-break |
| SALE-RD05-02 | Seller get one header and all frozen lines, totals/currency/order version, no price re-evaluation |
| SALE-RD05-03 | Seller detail contains only reservations belonging to exact tenant/order/line, no unrelated stock |
| SALE-RD05-04 | Seller history includes PLACED/CONFIRMED/CANCELLED/COMPLETED exactly as committed; no event duplicates; bounded stable page |
| SALE-RD05-05 | Seller A/B/AB tenant grants, foreign UUID and non-existing ID return same surface, revoke during read denied/fenced |
| SALE-RD05-06 | PII projection reflection/JSON serialization rejects email, phone, buyer_user_id, pending identity and hidden columns |
| SALE-RD05-07 | Buyer mine page of 101+ orders across ≥2 sellers, exact count/order, no unbounded lists |
| SALE-RD05-08 | Buyer ownDetail by (sellerTenantId,orderId), wrong tenant/other buyer denied as not found; duplicate UUID across tenants handled correctly |
| SALE-RD05-09 | Buyer email changed / old email reused: historical order remains bound to original global userId |
| SALE-RD05-10 | Buyer inactive/unverified/expired actor, seller suspended/revoked actor, no side effects and no partial data |
| SALE-RD05-11 | Pagination concurrent mutations, snapshot consistency of header/lines/reservations, line-count caps, large offsets and count/page behavior |
| SALE-RD05-12 | Inventory grants independent: SALES_ORDER_READ-only user can read order-associated reservation summary but cannot see global stock balances/movements |
| SALE-RD05-13 | Legacy NULL SKU snapshot remains visible as unknown; no join to current offer for title/price/item |
| SALE-RD05-14 | Exact-head H2 and PostgreSQL tests, populated migration, index EXPLAIN and no silent skipped PG race; report artifact SHAs |

**Existing actual tests:** `SalesOrderQueryQualificationTest.verifiedReadFacadeEnforcesActorTenantAndPagination`, `postgresPaginationRemainsTenantIsolatedDuringConcurrentStatusChanges`, `orderListPaginatesFiltersAndNeverReturnsBuyerIdentity`, `tcrud03MixedMembershipSalesReadIsTenantBoundAndCannotBorrowPermission`; `AutoOrderR1QualificationTest.tcrud04BuyerOrdersCannotBeReadBySupplyingAnotherUsersUuid`, `verificationGrantsAccessOnlyOnProofAndDoesNotRepeatAccount`; `IamEmailChangeOrderHistoryQualificationTest.historicalBuyerOrderStaysWithOriginalUserWhenOldMailboxIsReused`. Эти тесты подтверждают **только текущую** выдачу/изоляцию, не новые `get/history/mine(page,size)`.

## 8. Минимальный backlog / DoD

- **SALE-R1.5A P0:** Seller `get` + ordered immutable lines + reservation summary, 1 query facade, scoped SQL and authorization; no extra warehouse grants; SALE-RD05-02/03/05/06/12/13.
- **SALE-R1.5B P0:** Seller `history` bounded, no actor/reason fabrication, additive actor/reason schema separately post-PASS04; SALE-RD05-04/05/11.
- **SALE-R1.5C P0:** Buyer `mine(page,size)` + `ownDetail(actor,sellerTenantId,orderId)`, global buyer account proof and exact ownership; SALE-RD05-07..10.
- **SALE-R1.5D P1:** Search improvements after real usage: only exact orderId filter; verified page/count semantics, EXPLAIN; SALE-RD05-01/11/14.
- **Deferred:** buyer shipping address, full inventory ledgers via SALES, customer PII, task SLA, payments/refunds, multi-warehouse and a new CQRS projection/store.

**Decision before coding:** seller detail may expose order-linked `reservationId/locationId/itemId/state` under SALES_ORDER_READ (not inventory global balances). Buyer detail does **not** expose reservation internal IDs. Buyer order lookup must use composite `(sellerTenantId,orderId)` because DDL provides only composite key. Both are recommended normative R1.

**Verdict:** PASS 05 **SOURCE REVIEWED / SPEC PROPOSED**; no Java or DDL modifications, no newly executed tests. Functional maturity stays 2/5. Next PASS 06/12: transaction consistency, idempotency, concurrent reads/writes and rollback.
