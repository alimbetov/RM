# RM — SALES / AutoOrder: PostgreSQL schema integrity, проход 03/12

**Снимок исходников:** `1cc19715e82551ec22dd451427ea34b2a8b0c86d` (`base_dev`, 2026-11-22). **Статус:** PASS 03 — DDL SOURCE VERIFIED / MIGRATION SPEC PROPOSED. Это **проект миграции**, не применённая миграция, не inspected production DB и не PostgreSQL E2E. Feature Freeze: без контроллеров, новой ветки и изменения main; production HOLD.

**Фактически прочитано:** `src/main/resources/db/changelog/db.changelog-master.sql` changesets rm:0014 (основные таблицы), rm:0019 (query indexes), rm:0025 (inventory reservation), rm:0026 (nullable offer→SKU), rm:0027/0028 (reservation history), rm:0068/0069 (catalog lifecycle/history), `AutoOrderService`, `SalesInventoryCommandService`, `InventoryReservationService`, `SalesOrderRepository` и методный аудит 02/12. Изменение конфигурации в другом HEAD не доказывает применённый physical catalog PG: перед исполнением требуется `pg_catalog` introspection.

## 1. Реальная схема шести таблиц: ключи, FK, CHECK, индексы

Все `crm.*` ниже. Нормативные названия — **действительные имена из Liquibase**, не предполагаемые.

| Таблица | Ключ и обязательные поля | FK / CHECK | Индексы (включая автоматически создаваемые PK/UNIQUE) | Что БД НЕ доказывает |
|---|---|---|---|---|
| `store_offer` | PK `(tenant_id,id)`; `title varchar(240)`, `unit_price numeric(19,4)`, `currency char(3)`, `state varchar(20)` NOT NULL; **`catalog_item_id uuid NULL`** (rm:0026) | `fk_store_offer_tenant (tenant_id)→store_front(tenant_id)`; `fk_store_offer_catalog_item (tenant_id,catalog_item_id)→cat_item(tenant_id,id)` (NULL допускается); price≥0; state `PUBLISHED/HIDDEN` | `pk_store_offer`, `idx_store_offer_catalog(tenant_id,catalog_item_id)` | Наличие SKU при PUBLISHED, SKU.kind=PRODUCT, SKU.state=PUBLISHED, право менять цену или SKU; FK не проверяет state и не сохраняет историческое значение |
| `sales_order` | PK `(tenant_id,id)`; email identity NOT NULL, buyer_user_id NULL, status/amount/currency/created_at NOT NULL; row_version DEFAULT 0 | `fk_sales_order_tenant→store_front(tenant_id)`, global email identity and user account FKs; amount≥0; 8 разрешённых status | `pk_sales_order`; `idx_sales_order_buyer(buyer_user_id,created_at)`, `idx_sales_order_tenant_created(tenant_id,created_at DESC)`, `idx_sales_order_tenant_buyer_created(tenant_id,buyer_user_id,created_at DESC)` | `total_amount = SUM(lines)`; валюта всех строк совпадает; допустимый переход состояния/права; email proof не равен OIDC |
| `sales_order_line` | PK `(tenant_id,id)`; `order_id`, `offer_id`, `title_snapshot`, `quantity`, `unit_price_snapshot`, `currency` NOT NULL | `fk_sales_line_order(tenant_id,order_id)→sales_order`; `fk_sales_line_offer(tenant_id,offer_id)→store_offer`; qty>0; rm:0025 `uk_sales_order_line_tenant_order_id(tenant_id,order_id,id)` | `pk_sales_order_line`, `uk_sales_order_line_tenant_order_id` | **Нет `catalog_item_id_snapshot`**; price не immutable на уровне БД; отсутствует CHECK unit_price_snapshot≥0; нет ограничения на max aggregated qty/number of lines |
| `sales_checkout_receipt` | PK `(tenant_id,idempotency_key)`, `request_sha256 bytea`, `order_id`, `created_at` NOT NULL | `fk_sales_receipt_order(tenant_id,order_id)→sales_order` | `pk_sales_checkout_receipt` | Нет CHECK length SHA=32; не доказано ровно один receipt на каждый заказ в обратном направлении; retry payload equality проверяется Java `MessageDigest.isEqual` |
| `sales_order_status_event` | PK `(tenant_id,id)`; `order_id`, `event_type`, `occurred_at` NOT NULL | `fk_sales_order_status_event(tenant_id,order_id)→sales_order` | `pk_sales_order_status_event`, `idx_sales_status_tenant_order_time(tenant_id,order_id,occurred_at DESC)` | Нет CHECK event_type, actor_user_id, reason_code, explicit idempotent event key или immutable trigger для status events; UPDATE/DELETE технически не блокируются на уровне текущего DDL |
| `inv_stock_reservation` | PK `(tenant_id,id)`; order/line/location/item, quantity, state, timestamps NOT NULL; row_version default 0 | `uk_inv_reservation_order_line(tenant_id,order_line_id)`; `fk_inv_reservation_order_line(tenant_id,order_id,order_line_id)→sales_order_line(tenant_id,order_id,id)`; `fk_inv_reservation_balance(tenant_id,location_id,item_id)→inv_stock_balance(tenant_id,location_id,item_id)`; qty>0; state `ACTIVE/RELEASED/FULFILLED` | `pk_inv_stock_reservation`, `uk_inv_reservation_order_line`, `idx_inv_reservation_tenant_state(tenant_id,state,created_at)`, `idx_inv_reservation_tenant_order(tenant_id,order_id)` | **Не гарантирует совпадение `item_id` с SKU строки заказа**; quantity=order_line.quantity, валидность склада и полноту резервирования всех строк проверяет Java |

**Смежные гарантии:** `store_front` имеет PK `(tenant_id,id)`, UNIQUE `(slug)` и `(tenant_id)`; state `DRAFT/PUBLISHED/PAUSED`. `cat_item` имеет PK `(tenant_id,id)`, UNIQUE `(tenant_id,sku)`, kind `PRODUCT/SERVICE`, state `DRAFT/PUBLISHED/ARCHIVED`. `inv_stock_balance` имеет PK `(tenant_id,location_id,item_id)`, FK на org_location и cat_item **с tenant**, CHECK `on_hand>=0 AND reserved>=0 AND reserved<=on_hand`. `inv_stock_movement` имеет UNIQUE `(tenant_id,reservation_id)` и immutable trigger; `inv_reservation_event` (rm:0027/0028) имеет UNIQUE `(tenant_id,reservation_id,event_type)` и immutable PG trigger. **У sales_order_status_event аналогичного trigger не найдено.**

**Внимание к индексам:** уникальность FK `store_offer(tenant_id)` обеспечена `store_front.uk_store_tenant`. Индексы на ссылочных колонках **не создаются PostgreSQL автоматически**: у `sales_order_line` нет отдельного готового `(tenant_id,order_id)` индекса, хотя `uk_sales_order_line_tenant_order_id` начинается именно с них и уже покрывает запрос по префиксу; не добавлять дублирующий индекс без EXPLAIN. Аналогично `inv_stock_reservation` уже имеет `idx_inv_reservation_tenant_order`, а `sales_order_status_event` — готовый history index. `idx_sales_order_buyer` и `idx_sales_order_tenant_buyer_created` не считать автоматически дублирующимися: разные leading keys соответствуют global buyer и seller-tenant запросам. Проверить в `pg_stat_user_indexes` после нагрузочных данных.

## 2. Доказанная несогласованность SKU и защитное решение

Сейчас `AutoOrderService.checkout` читает только `store_offer.title,unit_price,currency`; сохраняет в `sales_order_line` только snapshot названия и цены. `SalesInventoryCommandService.lines` и `InventoryReservationService.reserve` повторно JOIN-ят **живой** `store_offer.catalog_item_id`, который nullable и не защищён от изменения. FK от reservation к line и balance не устанавливает тождество продаваемого и зарезервированного SKU.

**Минимальный целевой контракт R1:** новая nullable колонка `crm.sales_order_line.catalog_item_id_snapshot UUID` с tenant-composite FK `(tenant_id,catalog_item_id_snapshot)→crm.cat_item(tenant_id,id)`. Checkout обязан взять `catalog_item_id` из авторизованного PUBLISHED offer и PUBLISHED PRODUCT item, зафиксировать рядом с title/price/qty и подтвердить, что SKU принадлежит этому tenant. Обе функции confirm/reserve должны читать **только** line snapshot для новой схемы. Нельзя использовать `COALESCE(snapshot,offer.catalog_item_id)` для legacy заказа: это незаметная подмена исторических данных.

**Дополнительная DB-гарантия с минимальными изменениями:** возможен `UNIQUE(tenant_id,order_id,id,catalog_item_id_snapshot)` и составной FK из reservation `(tenant_id,order_id,order_line_id,item_id)` на line `(tenant_id,order_id,id,catalog_item_id_snapshot)`. Но такая FK не подходит старым `NULL` snapshots и может усложнить populated migration; решение для R1: **tenant FK snapshot + подтверждённое равенство в Java в одной транзакции**, а composite reservation→line FK добавить только после квалифицированного backfill активных резерваций/плана блокировок. Не выдавать Java-проверку за DB CHECK.

## 3. Populated-safe expand → backfill → contract без выдуманной истории

**Phase A — inventory/preflight (только SELECT; прогон на реальном клоне populated PostgreSQL):**

- Посчитать количество `sales_order_line` по статусам заказа, отдельно NULL/ambiguous offer SKU; предложение может иметь текущий `catalog_item_id`, но это **не доказательство исторического значения**.
- Доказательные кандидаты: для строки, имеющей reservation, использовать `inv_stock_reservation.item_id` как **доказательство фактически выбранного SKU для резервирования**, но не утверждать, что он был SKU при checkout, если история изменений offer отсутствует. Для `PLACED` без reservation (или старых CANCELLED) **не использовать** текущий `store_offer.catalog_item_id` как достоверный исторический snapshot.
- Разделить: `PROVEN_FROM_RESERVATION` (есть reservation и tenant FK valid); `PROVEN_FROM_IMMUTABLE_OFFER_HISTORY` (только если существует доказанная внешняя immutable history — в текущем DDL не найдена); `UNKNOWN_LEGACY` (нет достаточного evidence); `CONFLICTING_EVIDENCE` (current offer SKU противоречит reservation item). Никакой синтетический SKU не создавать.
- Снять counts на `tenant_id,status` и `NULL snapshot` из pg_catalog и реальных данных, размеры/индексы, FK validation readiness. Не логировать email, buyer_user_id, токены.

**Phase B — additive schema:** новый Liquibase changeset, *не править* rm:0014/0025/0026. `ALTER TABLE ... ADD COLUMN catalog_item_id_snapshot UUID` **nullable**; composite tenant FK `NOT VALID` в PG при необходимости online-add, затем `VALIDATE CONSTRAINT` после backfill (проверить H2 совместимость: отдельные dbms branches или обычный FK для малого R1). Отсутствие `NOT NULL` обязательно, пока есть unresolved legacy. Не создавать partial NOT NULL на всех старых строках.

**Phase C — dual-readable/no dual-writes:** deploy checkout writer, который всегда заполняет snapshot (и валидирует mapping и state); deploy confirm/reservation consumer, который использует snapshot и **fail-closed на NULL**. Обновления `store_offer` не переписывают старую `sales_order_line`. Для legacy NULL строк: `PLACED` нельзя подтвердить, пока не выполнено доказательное разрешение или явно утверждённое операторское корректирующее действие **новым подтверждённым заказом**, без переписывания исторической купли. `COMPLETED/CANCELLED` архивно читаются с неизвестным SKU как «историческая неизвестная привязка», а не заполняются произвольно.

**Phase D — evidence-only backfill:** если бизнес одобрит восстановление `item_id` из фактической reservation, заполнить nullable snapshot **только** где reservation item неизменяемо подтверждён и не конфликтует с tenant/snapshot, отдельным офлайн батчем с фиксированным source и receipt/audit. Назвать такой атрибут «резервировавшийся SKU» для legacy, а не «SKU на момент покупки». Более строгая рекомендация: **не backfill legacy checkout snapshot вообще** без исторически достоверного SKU, а сохранённый reservation.item_id продолжать использовать в истории фактического исполнения без повторного confirm.
- Каждая партия: `WHERE tenant_id=? AND id=? AND catalog_item_id_snapshot IS NULL`, `row count`, transaction/rollback, row counts before/after, никаких массовых `UPDATE` по текущему offer.
- Не запускать destructive delete, `SET NOT NULL`, снятие FKs или auto-drop старых orders.

**Phase E — prove/contract:** квалифицировать concurrent checkout vs offer update, confirm vs pause/deactivate, rollback, repeat, old order history, two tenants with same SKUs, backfill interrupted/resumed; only then decide whether `NOT NULL` is admissible. Если `UNKNOWN_LEGACY>0`, глобальный `NOT NULL` запрещён; enforce new writes in Java + narrow DB trigger/constraint only if approved and tested. Новые business commands fail closed on missing snapshot. До production — backup/PITR and EXPLAIN on populated DB.

**Deployment compatibility:** сервер старой версии после additive migration продолжит писать строки с NULL snapshot. Нельзя допускать старого checkout writer во время cutover к новой policy; обеспечить одномоментное отключение предыдущих writers или дополнительную DB admission-проверку новых строк. Не полагаться на dual-write без доказательства. Документ не разрешает rollout сам по себе.

## 4. SQL-предложения для preflight (read-only)

```sql
-- 1. Status / snapshot provenance inventory; column is introduced only in Phase B.
SELECT l.tenant_id, o.status,
       COUNT(*) AS line_count,
       COUNT(*) FILTER (WHERE s.catalog_item_id IS NULL) AS current_offer_unmapped,
       COUNT(*) FILTER (WHERE r.id IS NULL) AS lines_without_reservation,
       COUNT(*) FILTER (WHERE r.id IS NOT NULL) AS lines_with_reservation,
       COUNT(*) FILTER (WHERE r.id IS NOT NULL AND s.catalog_item_id IS DISTINCT FROM r.item_id)
           AS current_offer_vs_reserved_mismatch
FROM crm.sales_order_line l
JOIN crm.sales_order o ON o.tenant_id=l.tenant_id AND o.id=l.order_id
JOIN crm.store_offer s ON s.tenant_id=l.tenant_id AND s.id=l.offer_id
LEFT JOIN crm.inv_stock_reservation r ON r.tenant_id=l.tenant_id
                                  AND r.order_id=l.order_id AND r.order_line_id=l.id
GROUP BY l.tenant_id,o.status
ORDER BY l.tenant_id,o.status;

-- 2. Current referential integrity and constraints (no assumptions about applied migrations).
SELECT conrelid::regclass AS on_table, conname, contype,
       convalidated, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid IN (
    'crm.store_offer'::regclass,'crm.sales_order'::regclass,
    'crm.sales_order_line'::regclass,'crm.sales_checkout_receipt'::regclass,
    'crm.sales_order_status_event'::regclass,'crm.inv_stock_reservation'::regclass)
ORDER BY on_table,conname;

-- 3. Column types/nullability and indexes.
SELECT table_schema,table_name,column_name,data_type,is_nullable,numeric_precision,numeric_scale
FROM information_schema.columns
WHERE table_schema='crm' AND table_name IN
 ('store_offer','sales_order','sales_order_line','sales_checkout_receipt',
  'sales_order_status_event','inv_stock_reservation')
ORDER BY table_name,ordinal_position;
SELECT schemaname,tablename,indexname,indexdef FROM pg_indexes
WHERE schemaname='crm' AND tablename IN
 ('store_offer','sales_order','sales_order_line','sales_checkout_receipt',
  'sales_order_status_event','inv_stock_reservation')
ORDER BY tablename,indexname;
```

**No write SQL in preflight**; скрипт выше предполагает, что changeset rm:0025 уже применён. Для более старых клонов выполнить проверку наличия таблицы до запроса.

## 5. Ответственность БД и Java — без overengineering

| DB constraints / PostgreSQL | Java domain transaction |
|---|---|
| tenant-composite PK/FK и link `line→order→tenant`, `reservation→line+stock`, `offer→cat_item`, `line.snapshot→cat_item` | `VerifiedActor` + tenant RBAC, own buyer, allowed statuses, ACTIVE warehouse, current catalog item PRODUCT/PUBLISHED at checkout |
| CHECK quantity>0, price≥0 where added, valid status, reserved≤on_hand, 1 reservation/line, 1 checkout key/tenant | Merge duplicate offer lines, 1..100 distinct offers, aggregated qty bound, same currency and exact total, immutable chosen snapshot |
| Indexed tenant query paths, immutable movement and reservation event triggers; optional immutable order event trigger after compatibility review | Lock order IAM admission → order/offer/location/stock with proven no deadlocks, confirm/replay/cancel/fulfill, one transactional outbox/event, idempotent conflict handling |
| SQL FK keeps all legacy lines and referential consistency even when snapshot NULL | Fail-closed command on unknown legacy snapshot, no synthetic backfill, explicit business remediation; versioned rollout and audit |
| DB default 0 row_version, unique receipt and reservation keys | CAS status/version and typed permission; payment and delivery not inferred from COMPLETED |

**SQL cannot validate a dynamic state predicate (PUBLISHED SKU, ACTIVE WAREHOUSE), aggregate total, role membership, or an email proof using only a simple FK/CHECK.** Не строить триггерный state machine: оставить процессы в одном небольшом Java command orchestration; выделить только действительно строгие DB invariants.

## 6. Квалификационные тесты прохода 03 (вход в реализацию)

| ID | Required independent PostgreSQL proof |
|---|---|
| SALE-DB03-01 | PK/FK/CHECK and `pg_catalog` actual match Liquibase; cross-tenant offer/item/line/reservation rejected |
| SALE-DB03-02 | Checkout 2 PUBLISHED PRODUCT SKUs with same tenant, immutable row snapshots after offer title/price/SKU update |
| SALE-DB03-03 | NULL/unmapped/SERVICE/ARCHIVED/foreign SKU checkout fails with zero order, email identity, outbox and receipt |
| SALE-DB03-04 | Reservation.item_id must equal original snapshot; mutate offer mapping after placement then confirm still reserves historical SKU |
| SALE-DB03-05 | Legacy `PLACED` NULL snapshot: fail closed; legacy terminal history remains queryable, never auto-mapped to current offer |
| SALE-DB03-06 | Populated legacy ≥two tenants, NULL mapping, mismatch, missing reservation; migration counts and data SHA before/after, no destructive changes |
| SALE-DB03-07 | Interrupted/resumed batch, concurrent checkout during rollout, old-writer fence; no new NULL snapshots after cutover |
| SALE-DB03-08 | Same tenant concurrent confirm/cancel/fulfill, no duplicate reservations/movements/events, rollback all records on stock failure |
| SALE-DB03-09 | Query/index EXPLAIN with meaningful volume and stable paging, no fabricated p95/p99 |
| SALE-DB03-10 | Exact HEAD CI: H2, populated PG migration, normal PG revocation and RustFS/ClamAV; security permissions and lock order intact |

## 7. Verdict

**03/12 — SOURCE SCHEMA REVIEWED, TARGET MIGRATION SPECIFIED, NOT IMPLEMENTED.** Действующих SQL/Java изменений по этому этапу нет. Наиболее важная проблема — недостаточная историческая идентичность SKU. Следующим **04/12** изучить полную state machine SALES, допустимые команды/permissions и semantics отмены PLACED до внесения изменений в runtime.
