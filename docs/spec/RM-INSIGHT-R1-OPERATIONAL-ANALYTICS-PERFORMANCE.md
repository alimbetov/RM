# RM-INSIGHT-R1 — Operational Analytics & Performance Foundation

**Тип:** техническое задание / согласование архитектуры и контрактов, без реализации Java/SQL.
**Версия:** DRAFT R1.0 от 2026-11-22.
**Рабочая ветка:** base_dev, без новых веток и без merge в main.
**Исходный audit SHA:** 58d400a83d5d58f771955a36806374b6eec8bef6.
**Release:** PRODUCTION HOLD. Создание этого документа не является результатом выполнения тестов или открытием HTTP/OIDC.
**Основные зависимости:** docs/architecture/RM-PLATFORM-STANDARD-R2.md; docs/spec/RM-COMMERCE-INVENTORY-COMPLETION-R1.md; docs/spec/RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md; docs/spec/RM-SERVICE-FIRST-SALES-INVENTORY-R1.2.md; docs/spec/RM-INVENTORY-VALUATION-R1.md; docs/strategy/RM-STRATEGY-R4-FUNCTIONAL-GAP-PRIORITIZATION.md.

**Связанное проектное ТЗ:** [RM-COMMERCIAL-GEO-REGION-QR-FULFILLMENT-R1](RM-COMMERCIAL-GEO-REGION-QR-FULFILLMENT-R1.md) — склады с GPS, поиск товара по региону, immutable warehouse-origin в SALES, безопасный QR самовывоза и возвраты. Статус SPEC; эти функции ещё не реализованы.

> **CR-MVP-INTELLIGENCE-EVT-01 (2026-11-25):** [Scoped MVP R1](RM-MVP-INTELLIGENCE-EVT-SCOPE-R1.md) — owner selected bounded INT-01…03 for staged MVP implementation after IE-00. Prior post-MVP/freeze language superseded only for admitted slices. Existing source semantics, safety, benchmark and G1–G4 retained. No geo prediction, ML, full reports or new REST/UI before G1. No implementation PASS inferred.

## 1. Назначение и границы

Создать проверяемую, tenant-safe и восстанавливаемую основу операционной аналитики RM поверх существующих транзакционных фактов SALES и INVENTORY; получить измеримое снижение стоимости аналитических чтений без ухудшения checkout/confirm/reserve/fulfill. Аналитика должна отвечать на реальные вопросы владельца: сколько заказов принято/отменено/исполнено, как быстро подтверждаются заказы, где движутся запасы, почему нет доступного товара, какие склады испытывают дефицит. Позднее добавить геоаналитику, рекомендации складов и прогноз спроса.

Один deployable Spring Boot 4.1.3 / Java 21 / Spring MVC / Spring JDBC/JPA / PostgreSQL; H2 только для совместимости и быстрых тестов, Liquibase formatted SQL для миграций. **Не вводить** микросервисы, отдельный generic analytics bounded context, Kafka, Redis, ClickHouse, Rust, TimescaleDB, сторонний event bus, универсальный rule engine, собственный policy engine или новый HTTP controller в рамках Feature Freeze. Отдельные платформенные компоненты — только после benchmark/ADR. Существующая реализация остаётся источником истины.

Это ТЗ описывает **R1.0 foundation**, **R1.1 customer-facing analytics**, **R1.2 geo insights**, **R1.3 performance qualification**, затем R2 recommendations. Упоминание функции означает план, не существующую реализацию.

## 2. Source-derived текущий контракт и пробелы

| Граница | Реализованный факт на audit SHA | Не считать реализованным |
|---|---|---|
| CATALOG | cat_item PRODUCT/SERVICE, tenant-owned SKU | SERVICE fulfillment и stock по SERVICE |
| STOREFRONT | seller offer, опубликованная цена, optimistic version | Гарантированный stock при просмотре offer |
| SALES | sales_order + immutable order_line snapshots; sales_order_status_event (PLACED, CONFIRMED, CANCELLED, COMPLETED и поддерживаемые статусы) | Оплаченная выручка, доставка и полная service-order вертикаль |
| INVENTORY | inv_stock_balance(tenant,location,item); inv_stock_movement, inv_stock_reservation, inv_reservation_event; posting_version; quantity reconciliation | FIFO/COGS, неизменный tenant-wide отчетный snapshot между страницами |
| ORGANIZATION/GEO | org_location с workspace_id, KATO и address_line; KATO reference | GPS lat/lon, точная зона доставки, исторический геоконтекст покупателя |
| IAM | VerifiedActor и TenantAuthorization; разрешения SALES_ORDER_READ/INVENTORY_STOCK_READ | Новый implicit ANALYTICS_READ или полномочия работника на все tenants |
| AUDIT | audit_event/retention/holds; по времени+tenant партиционированный audit | Универсальная аналитическая шина; audit_event не является event-bus |
| EMAIL | iam_email_delivery_outbox | Аналитический outbox SALES/INVENTORY |

**Неподвижные предметные инварианты:**
1. tenantId = public.iam_workspace.id; warehouseId = public.org_location.id; skuId = crm.cat_item.id; никакие новые tenant-идентичности не вводятся.
2. inv_stock_balance.on_hand >= inv_stock_balance.reserved >= 0, available = on_hand - reserved.
3. PLACED не резервирует; CONFIRMED резервирует полные товарные строки одним warehouseId на order в R1; fulfill уменьшает on_hand и reserved, создаёт movement; cancel освобождает резерв. Существующие SQL locks/OrderInventoryPort/dual IAM permission сохраняются.
4. Текущая цена offer принадлежит STOREFRONT; принятая цена и SKU snapshot — SALES. Аналитика не рассчитывает checkout price из будущей цены каталога.
5. FINANCE не завершён: booked/accepted order amount **не является** оплаченной выручкой, прибылью, COGS или задолженностью.
6. Не читать shared KMS/PII для агрегатов; CRM/SALES identity не объединять на основании совпавшего email.
7. Любая аналитическая проекция — derived, версия методики обязательна, разрешён replay/rebuild; при конфликте авторитетны исходные транзакционные факты.

## 3. Domain ownership и topology

### 3.1 Ownership matrix

| Домен | Владеет и публикует факты | Производная аналитическая ответственность |
|---|---|---|
| SALES | Order status event, immutable order snapshot, accepted amount/currency | sales_daily_metric и собственный outbox/dedup |
| INVENTORY | Physical movement, reservation event, stock balance | inv_daily_metric и собственный outbox/dedup |
| ORGANIZATION | Warehouse profile/type/status/KATO/address | Только read-only обогащение warehouse; не хранит quantity |
| GEO | KATO reference, будущая пространственная семантика | Географические измерения/проверки, без клона адресов |
| CATALOG | Tenant SKU/kind/description | Подпись измерения SKU, не владелец остатков |
| STOREFRONT | Offer/publication/current seller price | Цена продажи берётся из order snapshot, не из current offer |
| IAM | Actor/tenant membership/permission | Admission для read-facade; worker имеет отдельный узкий trust boundary |
| AUDIT | История безопасности и retention policy | Не использовать как источники financial/inventory facts |
| FINANCE | Будущие invoice, payment, allocation | Отложенные оплаченная выручка/маржа/дебиторка; без фиктивных показателей |

Нового DomainModule.ANALYTICS нет. Все новые таблицы имеют уже существующие префиксы **sales_** или **inv_** в schema crm, согласно RM-PLATFORM-STANDARD-R2; Java-модули остаются kz.rm.sales и kz.rm.inventory. Допускается узкая общая техническая утилита без владения доменными данными. Сквозной dashboard — composition через два авторизованных read-port, не JOIN без policy.

### 3.2 Data flow

Business transaction → canonical event + tenant-local analytics outbox record в **одной PostgreSQL транзакции** → committed pending work → bounded worker → atomically apply idempotent projection and mark handled → tenant-scoped query facade → report with asOf, data freshness, method version.

Worker не ждёт внешней сети/геокодирования внутри SQL-транзакции и не задерживает подтверждение заказа; failure аналитики после commit не откатывает бизнес-факт. Если запись обязательного outbox не прошла, вся бизнес-транзакция откатывается; для исторических данных используем отдельный backfill.

## 4. R1.0 — факт, события, доставка, идемпотентность

### 4.1 Source keys и event envelope

- SALES: crm.sales_order_status_event.id + tenant_id; type/event time, связанный orderId. Для PLACED order total_amount/currency — уже сохранённый server-owned snapshot. Отдельные lifecycle events имеют отдельные event IDs. В R1.0 достаточно PLACED/CONFIRMED/CANCELLED/COMPLETED; остальные статусы сохранять как typed events без искусственного маппинга.
- INVENTORY: crm.inv_stock_movement.id + tenant_id + locationId + itemId + delta_quantity + movement_kind + recorded_at/occurred_at; immutable quantity ledger. Transfer имеет **две физические ноги** и обе должны попадать в географические складские потоки, но общий товар по tenant не создаётся/не уничтожается.
- RESERVATION: crm.inv_reservation_event.id + tenant_id + reservationId; immutable reservation.quantity/location/item читаются через tenant-composite key. События RESERVED/RELEASED/FULFILLED считаются отдельной reservation telemetry. FULFILLED reservation и физическое FULFILLMENT movement не суммируются в один «расход» дважды.
- Typed event: (tenantId, sourceDomain, sourceEventId, eventType, occurredAt TIMESTAMPTZ, payloadVersion, minimal immutable metrics). Запрещены plaintext email, phone, contact fields, file keys, challenge tokens и произвольные PII payload.
- События относятся к конкретному seller tenant. Buyer userId не эквивалентен CRM Customer; аналитику покупателей без корректного source-of-truth mapping отложить.

### 4.2 Domain-prefixed persistent state, additive Liquibase

R1.0-кандидаты; перед SQL-реализацией проверить существующие имена, FK и source events в latest base_dev.

1. crm.sales_analytics_outbox: (tenant_id, id, source_event_id, event_type, occurred_at, payload_version, payload_json, state, retry_count, next_attempt_at, created_at, handled_at); UNIQUE(tenant_id,source_event_id). Никакого API write.
2. crm.inv_analytics_outbox: тот же минимальный конверт для movement и reservation; UNIQUE(tenant_id,source_kind,source_event_id), чтобы разные журналы с одинаковым UUID не пересекались.
3. crm.sales_analytics_applied: (tenant_id, source_event_id, applied_at, projection_version), unique по source event и версии проекции; нет пропуска новой версии из-за старой.
4. crm.inv_analytics_applied: (tenant_id, source_kind, source_event_id, applied_at, projection_version), unique по источнику и версии.
5. crm.sales_daily_metric: ключ (tenant_id, day_utc, status_event_type, currency, projection_version); event_count, accepted_amount; accepted_amount означает сумму суммы заказа, связанной с конкретным событием статуса, **не payment amount**.
6. crm.inv_daily_metric: ключ (tenant_id, day_utc, location_id, item_id, movement_kind, projection_version); movement_count и signed_quantity. Не называть signed_quantity текущим on_hand/reserved.
7. crm.sales_analytics_state, crm.inv_analytics_state: статус backfill/rebuild, projection_version, freshness/evidence metadata, last_success_at, lifecycle; отдельный процесс/версия tenant. **Не использовать max(sequence/id) как commit-safe checkpoint.**

Все fact/metric таблицы tenant-first PK/unique/FK, bounded индекс под реальные фильтры; source event reference будет проверяться composite tenant key там, где исходный PK допускает это. Foreign ID должен возвращать deny/empty, не trigger чужой data access. При включении ограничений на заполненной базе — populated migration dry run, orphan scan, rollback strategy; старые Liquibase changesets не менять.

**Сокращение схемы допустимо** после доказательства, что оно сохраняет exactly-once effect и per-domain ownership. Запрещено объединять эти данные с crm.audit_event или держать бизнес-источник истины в analytics tables.

### 4.3 Publication points и atomicity

SALES emission подключать в общую точку создания status event — AutoOrderService.checkout и SalesInventoryCommandService lifecycle, без новой бизнес-транзакции, новых row locks и смены lock order. INVENTORY — в непосредственные точки записи inv_stock_movement и inv_reservation_event: adjust/receive/writeOff, transfer out/in, stocktake posting, reserve/release/fulfill. Сначала провести inventory каждого producer'а: не объявлять coverage complete, пока не указаны все реальные write paths.

- Успешная бизнес-транзакция: immutable fact + ровно один outbox signal на source event; UNIQUE защищает повторный emitter.
- Rollback: ни fact, ни outbox, ни аналитический вклад не фиксируются.
- Retried business command: existing idempotency contract; новый сигнал не создаётся, если нет нового canonical source event.
- Analytic worker: at-least-once consumption, exactly-once **projection effect** при одном PostgreSQL transaction insert applied receipt → increment metric → mark outbox handled. Повторный источник не увеличивает показатель.
- Если worker падает до commit — весь batch откатывается и повторяется. После commit — повтор обнаруживается applied receipt. Не заявлять буквально exactly-once delivery.
- R1.0: транзакционные bounded batches с SELECT ... FOR UPDATE SKIP LOCKED; без длительной lease, отдельной очереди и внешнего IO. Crash recovery через откат PG транзакции. Ошибки записывать отдельно, retry/backoff bounded, poison row и operator requeue с сохранением исходного события. Нельзя тихо помечать failed event как DONE.
- Event processing order может отличаться от event time/commit order; delta-based daily sums должны быть коммутативными. Status conversion latency и «последний статус» нельзя строить на последнем полученном событии по arrival order.
- Не вводить cursor вида max(source_event_id), max(seq) или last timestamp без доказанного commit-safe fence. PostgreSQL sequence allocation ≠ commit ordering.

### 4.4 Initial backfill без утрат и двойного учёта

После additive migration включить source-event emitter. Затем для каждого tenant выполнить bounded scan исторических status/movement/reservation events, вставляя **тот же idempotent outbox identity** с ON CONFLICT DO NOTHING. Конкурирующий live emitter и backfill одного event ID дают одну учётную единицу. Нельзя начинать report READY до завершения backfill, отсутствия unresolved source gaps и drain всех применимых событий. Для legacy events, где исходных фактов недостаточно, выставлять PARTIAL_HISTORY, не реконструировать несуществующие движения, даты или цены.

Rebuild новой projection_version выполняется в shadow metrics/applied namespace, затем сравнивается с независимым SQL, и только после полного drain + проверок атомарно переключает ACTIVE версию. Старая версия сохраняется для rollback; никакого destructive truncate production active view.

## 5. Контракт метрик: точность важнее числа графиков

| Код | Метрика | Первичный источник | Правило |
|---|---|---|---|
| S-01 | placed_orders | sales_order_status_event PLACED | COUNT distinct source_event_id tenant/day |
| S-02 | booked_order_amount | PLACED + immutable order snapshot | SUM order.total_amount, GROUP BY currency; не revenue |
| S-03 | confirmed_orders | CONFIRMED event | COUNT по дате подтверждения; отличается от числа новых заказов |
| S-04 | cancelled_orders | CANCELLED event | COUNT по дате отмены; не суммировать со «всеми заказами» как независимые заказы |
| S-05 | fulfilled_orders | COMPLETED event | COUNT по дате warehouse fulfill; не payment/delivery |
| I-01 | stock_in_qty | inv_stock_movement positive physical legs | По SKU/location; transfer IN отдельно от внешнего receipt |
| I-02 | stock_out_qty | inv_stock_movement negative physical legs | Коррекция/write-off/fulfillment/transfer OUT отдельными types |
| I-03 | reservation_activity | inv_reservation_event | RESERVED/RELEASED/FULFILLED; не double count movement |
| I-04 | current_available | **live** inv_stock_balance | on_hand - reserved, с IAM and warehouse policy; НЕ дневная projection |
| O-01 | worker_lag | outbox/state | По committed pending events; stale/unknown label при ошибке |

В R1.1: confirmation latency = confirmed_at - placed_at по canonical events и cohort; conversion определять по уникальным orderId в фиксированной когорте и cut-off; считать корректно при out-of-order replay. Незавершённая когорта явно помечается. Возвраты/оплаты/маржа отсутствуют.

В R1.2: регион склада по текущему org_location.kato_code можно показывать **только** с лейблом current warehouse location. Исторический спрос покупателя нельзя связывать с текущим адресом склада или строить по buyer email. Для полноценной geo demand нужен утверждённый immutable destination KATO/geo snapshot в SALES при принятии заказа; GPS warehouse появляется только после отдельной ORG/GEO миграции. Для public витрины показывать ограниченные availability bands, не полный внутренний ledger.

**Date/quantity/currency:** timestamps UTC, day_utc определяется явно и воспроизводимо; будущие tenant local business-day требуют версионированную timezone policy и пересчёт. Все monetary sums строго по валюте, no implicit FX; quantity суммируется только в совместимом SKU/UOM, BigDecimal NUMERIC precision; не сворачивать разные SKU в «общее количество единиц товара» без UOM normalization. Математический тест сравнивает числовые суммы точно, без double.

## 6. Query API / доверенная граница

R1.0 service-only:
- AuthenticatedSalesAnalyticsQueries.daily(VerifiedActor actor, UUID selectedTenant, SalesMetricFilter, PageWindow) → Page<SalesMetricDto>.
- AuthenticatedInventoryAnalyticsQueries.daily(VerifiedActor actor, UUID selectedTenant, InventoryMetricFilter, PageWindow) → Page<InventoryMetricDto>.
- AuthenticatedInventoryAnalyticsQueries.currentAvailability(...) → live authoritative INVENTORY read with permission.
- Combined dashboard composer проверяет **каждую** доменную permission отдельно: SALES_ORDER_READ для SALES, INVENTORY_STOCK_READ для INVENTORY. Не считать один grant разрешением на всё; политика частичных секций должна быть явно согласована (R1.0 — fail closed для объединённого ответа, либо независимые вызовы отдельно).
- Проверить active account/workspace/membership при каждом запросе; selected tenant приходит из контекста пользователя и проверяется, не выводится из произвольного фильтра.
- До появления warehouse/portfolio ACL нельзя обещать видимость только «своих» складов и клиентов; запретить расширять аналитикой имеющийся read scope.
- Все filters, group keys, count, pagination and DTOs tenant-scoped до SQL aggregation, не post-filter в Java. Numeric counts не раскрывают наличие объектов другого tenant. Stable sort + bounded page ≤100; no unbounded export. Signed cursor (tenant, actor-scope, filter hash, method version) — только отдельным приёмочным этапом, с fresh admission на каждой странице.
- Background workers не создают fake VerifiedActor или «пользователя ADMIN». Они являются доверенным техническим компонентом с узкими SQL правами на свои доменные таблицы и обязательной tenant-меткой каждой записи. Внешние task adapters требуют отдельной identity/admission policy.
- Запрет PII в metrics/outbox/logs; REDACT tracing; audit of administrative replay/rebuild с tenant/actor либо именованной service identity.
- RLS не вводится автоматически: текущая модель RM — application-layer tenant isolation, дополнительная RLS оценка отдельным ADR.

## 7. Performance design и измерения

**Главное правило:** ни один dashboard query, report export, projection backfill или worker не держит блокировку stock/order на пути checkout/confirm сверх существующих transaction boundaries.

- Для отдельных SQL проверить EXPLAIN (ANALYZE, BUFFERS) на репрезентативном populated PostgreSQL. Tenant-first индексы только по реальному predicate/ORDER BY, оценивать write amplification; индекс без плана и benchmark не добавлять.
- Actuator/Micrometer + PostgreSQL statistics: app command latency p50/p95/p99, SQL duration, Hikari active/wait/timeout, row lock wait/deadlocks, connection usage, WAL/I/O, worker batch throughput/failures, outbox oldest pending age, projection lag, rebuild duration, per-tenant fairness. Не использовать raw tenantId/PII как высококардинальные metric labels в Prometheus.
- Worker batchSize/budget/concurrency configuration, max retries, bounded memory, rate limits, cancellation, pause/rebuild state. В R1.0 worker и OLTP в одном Java artifact, но отдельный ограниченный executor и JDBC connection budget; slow-analytics никогда не должен исчерпывать pool заказов. При нагрузке — выделить datasource/pool или PostgreSQL replica **после** измерения и ADR.
- R1.0 aggregate SQL читает только малые daily projection tables; текущий available — индексированный live read, а не scan многолетнего movement history.
- Existing inventory request lock stripes и order row locks не менять в этом проекте. При contention нужен A/B benchmark, а не произвольная оптимизация блокировок.
- Не партиционировать все таблицы автоматически; существующий partitioned audit_event не является эталоном для небольшой analytics projection. Переход на partitioning основан на workload и retention ADR.

### 7.1 Предварительные цели (НЕ текущие измерения и НЕ unconditional SLA)

| Метрика | Target for synthetic qualification | Ограничение |
|---|---|---|
| live stock availability p95 | ≤150 ms | Утвердить железо, пул, набор SKU и concurrency |
| bounded stock list p95 | ≤250 ms | Сравнить с baseline |
| checkout p95 | ≤800 ms | Business baseline до outbox и после |
| warmed daily metric read p95 | ≤500 ms | Только READY projections |
| analytics end-to-end lag p99 | ≤15 s | От committed event до projection, под steady-state |
| added checkout/confirm latency | ≤5% regression | Проверять A/B в эквивалентной среде |
| lost or duplicate projection contributions | **0** | Hard correctness gate, не среднее значение |

Пример нагрузки для отдельного стенда (не per-commit CI): 100 tenants, 100 000 SKU, миллионы immutable movements; минимум две группы mixed workload (write-heavy 1 SKU и fan-out 8/100 SKU), checkout/confirm/transfer/stocktake вместе с analytics backfill. Число пользователей, data skew, CPU, DB memory, network, JVM warmup, длительность и repetitions зафиксировать до сравнения. Сохранить EXPLAIN/pg_stat_statements/lock stats/histograms и exact source SHA. Известные исторические Q7–Q9 small-sample p95/p99 — диагностические, а не production benchmark.

## 8. Проверки и acceptance matrix

**PostgreSQL обязательна** для correctness, DB constraints, concurrency, worker recovery, isolation. H2 — compile/compatibility. Все assertions независимым SQL считывают source facts, projection и applied receipts.

| ID | Сценарий | Условия приёмки |
|---|---|---|
| INS-01 | One PLACED and replay checkout | 1 order/status event, 1 outbox source key, 1 aggregate contribution |
| INS-02 | CONFIRMED reserve + cancel | SALES lifecycle counts; reservation +N/-N; on_hand неизменён |
| INS-03 | CONFIRMED reserve + fulfill | on_hand уменьшается один раз; один movement; status event counted once; reservation not double counted |
| INS-04 | Two warehouses same SKU | Метрики раздельны по location; physical tenant total корректен |
| INS-05 | Transfer source/destination | Две movement legs; net tenant stock delta=0; no fake demand |
| INS-06 | Rollback of business transaction | Нет ни business event, ни outbox, ни applied metric |
| INS-07 | Same event delivered twice | UNIQUE/applied prevents duplicate SUM or COUNT |
| INS-08 | Two concurrent workers SKIP LOCKED | Exactly-once **effect**, no double apply, no loss |
| INS-09 | Crash before commit, restart/retry | Полный rollback и затем единственный successful apply |
| INS-10 | Poison event / bounded retry | Error visible, no false DONE, manual controlled requeue |
| INS-11 | Backfill overlapping live emit | Same source key, exact same aggregate, no gap |
| INS-12 | Rebuild v2 while v1 is active | v1 reads stable; v2 READY only after verification, reversible switch |
| INS-13 | Tenant A/B overlapping IDs and filters | No cross-tenant data, counts, export, source IDs |
| INS-14 | Dual-tenant actor different grants | Rights not borrowed across tenants; denied reads no data |
| INS-15 | Revocation × analytic read | Two PostgreSQL commit orders, no unauthorized results after revoke |
| INS-16 | Currency and date | KZT/USD separate, UTC date boundary/replay deterministic |
| INS-17 | SOURCE legacy gap | Explicit PARTIAL_HISTORY, no synthetic financial/quantity facts |
| INS-18 | FULLFULLED amount vs payment | Labels never claim paid revenue or profit |
| INS-19 | Benchmark with/without outbox | Same load/data; no unjustified write latency regression |
| INS-20 | Worker under OLTP contention | Connection/CPU budget, no deadlocks/oversell, bounded lag |
| INS-21 | Geo attribution before location snapshot | No invented buyer destination / historic warehouse position |
| INS-22 | NEW SQL / Liquibase populated migration | Old rows retained; no FK/duplicate/lock regression; rollback plan |

**Minimum mandatory correctness gates:** INS-01..18, INS-21..22 after their corresponding stage is implemented. INS-19/20 require reproducible separate load qualification before an operations SLO is accepted. Every new metric needs one independently computed SQL oracle (using canonical facts, not projection code), a negative tenant test, a replay test and a method-version fingerprint.

## 9. Delivery plan, each with own DoD

### Stage 0 — RM-INSIGHT-00: source inventory and baseline (start here)
- Enumerate every producer of sales_order_status_event, inv_stock_movement, inv_reservation_event and relation to existing tables; compare with actual migration, no duplicate event engine. Pin one SHA.
- Define 10 metrics above as contracts with units, dates, amount semantics and explicit non-supported statuses.
- Measure current checkout/confirm/stock queries and PostgreSQL index baseline with saved data generator and plan fingerprints.
- Deliver: reviewed producer→source→outbox matrix, baseline benchmark, test fixture generator and DDL review. **No new code paths yet.**

### Stage 1 — RM-INSIGHT-R1.0A: transactional events + outbox
- Additive Liquibase, domain local emitter hooks, tenant-qualified uniqueness and migrations. No controllers.
- Qualified by INS-01..07 and INS-13..14 with exact SHA H2 and PostgreSQL. Preserve all SALES/INVENTORY races and historical migrations.

### Stage 2 — RM-INSIGHT-R1.0B: worker + projection correctness
- Bounded PostgreSQL worker, dedup, retry, backfill, rebuild/version switch, source reconciliation and freshness indicator.
- Qualified by INS-08..12, INS-16..17 and populated PostgreSQL replay/restart test; invariant no missing/double contributions.

### Stage 3 — RM-INSIGHT-R1.0C: authenticated read and operations
- Domain-specific VerifiedActor read ports; dashboard composition; metrics/health/structured logs without sensitive payloads.
- Qualified by INS-13..15, 18, 22 and permission/revocation PostgreSQL races.
- Record production HOLD until independent external OIDC/UI/security gates.

### Stage 4 — RM-INSIGHT-R1.1: business analytics
- Confirmation latency and cohort-based conversion, seller KPI, meaningful product/warehouse demand; no artificial paid revenue.
- History/performance tests plus source-version documentation and screen design only after correct service-only contract.

### Stage 5 — RM-INSIGHT-R1.2: geo scope (dependency on RM-GEO-LOC-R1 / RM-SALES-GEO-R1)
- Current KATO warehouse view vs immutable order destination snapshots separated; geographical availability; user-approved delivery zones; historical attribution correct.
- No geographic distance promises before GPS/geo policy; services SERVICE stockless until approved fulfillment.

### Stage 6 — RM-PERF-R1: performance qualification
- Repeatable baseline A/B benchmark on populated PG with command and worker mixed load; idx/plans review, pool and memory budget, p95/p99 regression gate. Prometheus/OTel labels cardinality reviewed.
- Optimization only with evidence, no silent semantic change.

### R2 (later) — Demand/Decision Intelligence
- Forecast by SKU/location, replenishment proposals, confidence/lead-time/safety-stock, owner approval, explanation and shadow evaluation against naive baseline; **never autonomous stock write** in initial R2.

**Dependency relative to RM-STRATEGY-R4:** W0 trusted issuer/onboarding/seller workspace/buyer-seller E2E remain highest product priority. INSIGHT-00 and foundational R1.0 may proceed as strictly bounded infrastructure slice without replacing W0 or reopening Feature Freeze.

## 10. Delivery checklist / exact-SHA evidence

1. Review scope, domain ownership and producer matrix against the **latest base_dev** before any implementation.
2. Every stage: feature code + additive Liquibase + targeted PG negative/race tests + H2 compatibility + populated CRM/IAM/INVENTORY/SALES migration + non-regression RustFS if mandatory by existing exact-SHA gate.
3. Add new suites and *specific required cases* to existing rm-exact-sha-admission workflow and verify_rm_qa_01b.py as required; no fake counters, no H2-only proof and no previous-SHA inheritance. Preserve existing required five workflows and manifest fingerprints.
4. Repeat after final code SHA: source inspection, no skipped mandatory tests, project CI pass, exact-SHA artifact with manifest and sample sizes, performance evidence only under defined environment.
5. No migration rewrite, no create new branch, no merge main, no new controller during current freeze. Any architectural change conflicting with RM-PLATFORM-STANDARD-R2 requires ADR before code.
6. Definition of DONE: replay/rebuild deterministic, zero cross-tenant disclosures, zero double applications/losses, verifiable metrics and dated asOf/freshness, no regression in business invariants, exact-SHA PostgreSQL gate PASS. **Production remains HOLD** until independent OIDC/security, load, migration/restore and operational acceptance.

## 11. Explicit decisions to approve before code

| Code | Proposal | Reason |
|---|---|---|
| INS-DEC-01 | Reuse SALES / INVENTORY domains, do **not** add ANALYTICS DomainModule | Preserve R2 ownership and avoid duplicate truth |
| INS-DEC-02 | One source event → one tenant-scoped domain outbox key; at-least-once transport / exactly-once effect | Correct transaction and replay semantics |
| INS-DEC-03 | R1.0 daily bucket = UTC, currency never co-mixed; timezone-local days in versioned next step | Deterministic historical replay |
| INS-DEC-04 | R1.0 report shows accepted/order amount, never recognized/paid revenue | FINANCE not implemented |
| INS-DEC-05 | Combined SALES+INVENTORY read requires both independent grants | No privilege escalation |
| INS-DEC-06 | Inventory stock gauges remain live SQL; daily analytics store historical flows only | No oversell or stale stock promise |
| INS-DEC-07 | Backfill + rebuild use immutable source IDs and versioned shadow projection | No data loss across live write/rebuild |
| INS-DEC-08 | Metrics SLO values are targets pending benchmark and workload approval | Avoid invented performance claim |
| INS-DEC-09 | Historic geo demand is HOLD until accepted destination snapshot; current warehouse region separately labelled | Prevent retrospective misattribution |
| INS-DEC-10 | First engineering task is producer inventory + benchmark, before event schema/code | Align with project strategy and no redundant components |

**Release decision:** DRAFT / for review. The present commit is documentation-only; no analytics functionality or production qualification is claimed.
