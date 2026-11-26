# CODE-01 · Java и SQL на границах сервисов
R2.1 · 2026-11-25 · проект для согласования. Исходный код [base_dev](https://github.com/alimbetov/RM/tree/f7910269df73564a6b1bf7d8f45bcc2766cc1b32).
Спецификация уточняет D01–D04 и D06. Имена новых классов, пакетов, таблиц и политики из D08 являются предложениями. Действующий граф определяет допуск к написанию кода.

## 1. Пакеты и владение зависимостями
| Этап | Предлагаемый Java-код | Граница ответственности |
|---|---|---|
| B1 | kz.rm.intelligence: MetricQueryService, MetricRegistry, PeriodResolver, MetricCalculator. Подпакет dto для неизменяемых контрактов | Реестр, типизированный расчёт, метаданные. INTELLIGENCE registered, int_ prefix; reuse actual types. Полномочия определяет источник |
| B1, C1 | kz.rm.sales: SalesIntelligenceReadPort. kz.rm.crm: Customer360QueryService, CustomerSalesAttributionReader | SALES владеет SQL заказа. CRM владеет проверкой актуальной связи. Композиция получает уже отфильтрованные секции |
| B1, W1–W3 | kz.rm.inventory: InventoryIntelligenceReadPort, Warehouse360QueryService, InventoryHistoryReconciliation. Pure ABC/XYZ в аналитическом пакете | Остаток и движения читает INVENTORY. Классификация принимает квалифицированные факты и не выполняет SQL |
| C1 | kz.rm.tasks: CustomerFollowupReadPort | TASK возвращает только видимые задачи и использует текущую семантику timing |
| B2–B4 | kz.rm.intelligence.projection: ProjectionApplier, GenerationRebuildService. Общий контракт события в пакете, согласованном A2 | Канонический producer остаётся у владельца факта. INT обновляет только свои производные данные |
| E1–E3 | kz.rm.evt: EventRuleService, EventPreviewService, OccurrenceAdmission, DeliveryWorker, DeadlineWorker | Доступ, правила, очередь и доставка. Выделение домена, регистрация и grants требуют решения A2 |
| R2–R4 графа | Owner-side SignalProvider и BusinessRadarQueryService | Будущее расширение после отдельного допуска. TASK timing сохраняет своего владельца |

Модульная граница: публичный read port принимает только типизированные запросы и возвращает immutable records. JdbcTemplate и repository классы остаются внутри владельца. CRM-композиция собирает секции, опираясь на разрешения каждого источника. Снимки отчётов и общая библиотека периодов не создают встречную зависимость SALES → CRM. Если переиспользование приводит к циклу Spring beans или пакетов, общие records и чистые функции выделяются на A2, а доступ к данным остаётся в домене.

## 2. Java-контракты B1
Ниже эскиз типов Java 21. Семантика конструкторов и ошибок составляет часть контракта. Запросы внутренних worker-ов используют отдельные entrypoints с узкими правами.

~~~java
enum PeriodCode { D, W, DEC, M, Q, H, Y }
enum MetricCode {
    PLACED_ORDERS, BOOKED_ORDER_AMOUNT, CONFIRMED_ORDERS,
    FULFILLED_ORDERS, CANCELLED_ORDERS, STOCK_IN_QTY,
    STOCK_OUT_QTY, CURRENT_AVAILABLE
}
enum DataQuality { READY, PARTIAL, MISSING, STALE, NOT_MEASURABLE }
enum TimeBasis { STATUS_OCCURRED, STOCK_RECORDED, LIVE_POSITION }

record CalendarPolicy(ZoneId zoneId, long version) {}
record PeriodChoice(PeriodCode code, LocalDate anchorDate) {}
record ResolvedPeriod(Instant startInclusive, Instant endExclusive,
                      String zoneId, long calendarVersion) {}

sealed interface MetricSelection permits OrderPeriod, MovementPeriod, LivePosition {}
record OrderPeriod(PeriodChoice period, Optional<String> currency)
        implements MetricSelection {}
record MovementPeriod(PeriodChoice period, UUID warehouseId, UUID skuId)
        implements MetricSelection {}
record LivePosition(UUID warehouseId, UUID skuId) implements MetricSelection {}
record MetricQuery(MetricCode code, int methodVersion, MetricSelection selection) {}

record Coverage(Instant fromInclusive, Instant untilExclusive,
                boolean complete, Set<String> reasonCodes) {}
record MetricCell(Optional<BigDecimal> value, DataQuality quality,
                  Optional<String> currency, Optional<String> unit,
                  Optional<UUID> skuId, Optional<UUID> warehouseId,
                  Coverage coverage) {}
record MetricResult(MetricCode code, int methodVersion, TimeBasis basis,
                    Instant evaluatedAt, Optional<ResolvedPeriod> period,
                    OptionalLong generation, String methodDigest,
                    List<MetricCell> cells, Optional<String> evidenceCursor) {}

interface PeriodResolver {
    ResolvedPeriod resolve(PeriodChoice choice, CalendarPolicy policy);
}
interface MetricQueryService {
    MetricResult calculate(VerifiedActor actor, UUID tenantId, MetricQuery query);
}
~~~

- MetricRegistry задаёт metric code/version → поддерживаемый subtype, доменный scope, параметризованный SQL plan, единицу, критерий покрытия и rollup operator. Любая неизвестная комбинация отклоняется до DB reads. Реестр статический для деплоя. Запрос клиента выбирает enum и bind values.
- Public entrypoint валидирует структуру, проверяет VerifiedActor и текущие полномочия, выбирает версию календаря, запрашивает источник, строит quality, перечитывает срок действия через actor.userId() непосредственно перед выдачей. Все коллекции копируются. Строковые reason codes берутся из серверного справочника.
- В SALES общий count не требует currency. При выводе BOOKED_ORDER_AMOUNT результат всегда содержит одну ячейку на валюту. Для среднего заказа denominator вычисляется в том же currency cohort. CurrentAvailable разрешён только для LivePosition.
- R2 DATA quality и ошибка доступа различаются на уровне типов. Для авторизованной выборки READY с доказанно пустым набором даёт суммируемый 0. Missing position возвращает типизированный NOT_FOUND внутри доменного контракта. SQL timeout даёт техническую ошибку или изолированный unavailable блок при независимо авторизованной композиции. Чужой объект и отсутствие объекта имеют одинаковый внешний ответ, если детальное различие раскрывает область доступа.
- Interval boundaries вычисляются как LocalDate и переводятся через atStartOfDay(zone), reusing exact same policy for direct and projection queries. Week = ISO Monday, DEC = 1/11/21-е, Q/H/Y начинаются с соответствующих календарных месяцев. Для неоднозначных timestamp inputs пользователь передаёт Instant или offset. Тесты фиксируют 23/25-часовой день, границы DEC и переход ISO года.
- Для денег и quantity используется BigDecimal. Сумма хранит точность исходных NUMERIC. Registry определяет допустимый scale и rounding только для отображения или отдельно утверждённого ratio. Null currency или unit в неподходящей ячейке даёт MISSING либо нарушение данных.

## 3. Параметризованные SQL-планы
| MetricCode | Где размещён SQL | SQL-предикаты и shape | Как проверяется полнота |
|---|---|---|---|
| PLACED / CONFIRMED / FULFILLED / CANCELLED | SALES | tenant_id, admitted transition kind, [start,end). Cohort строится по (tenant_id,order_id), event cardinality проверяется отдельно от фильтра времени | Producer inventory A1 + duplicates, missing first event, invalid transition, legacy |
| BOOKED_ORDER_AMOUNT | SALES | Тот же PLACED cohort → один join на sales_order(tenant_id,id) → GROUP BY currency с SUM(total_amount), COUNT для average | Header ∕ line snapshot parity на admitted paths. Упаковка живёт в service_line |
| STOCK_IN / STOCK_OUT | INVENTORY | tenant_id, location_id, item_id, recorded_at >= from и < until. Signed delta по движению. Breakdown по movement_kind/reason_code | Scope и покрытие движения. Пропуск posting_version может быть следствием изменения резерва |
| CURRENT_AVAILABLE | INVENTORY | Чтение одной строки inv_stock_balance по составному ключу. on_hand - reserved в той же выборке | Отсутствие строки сохраняется как отсутствие. Reconciliation со snapshot chain отдельно |

Для переходов сначала фиксируется допустимое множество заказов. Повтор kind по тому же заказу за границей интервала проверяется для затронутой cohort. Missing historical transition нельзя строго отнести к периоду без отдельного источника покрытия. При сомнительном покрытии виден PARTIAL/MISSING. Строки документов и упаковка загружаются через отдельную read-model ветку, чтобы JOIN не умножал количество заказов.

Пагинация для движения использует фильтры до LIMIT, сортировку recorded_at DESC, id DESC и seek `(recorded_at < :lastTime OR (recorded_at = :lastTime AND id < :lastId))`. Выдавать limit+1 для hasMore. Стабильную пагинацию доказать на фиксированном наборе. Keyset live-pagination позволяет изменения между запросами. Cursor связывает tenant, subject, filters, actor scope, sort, methodVersion и generation, защищён от изменения. Обычный list использует ограниченное число SQL reads без полного COUNT и без N+1. Точный состав новых индексов принимается после EXPLAIN и mixed load.

### SQL-пример: sales cohort с текущей атрибуцией CRM

Следующий фрагмент выполняется после допуска CRM/SALES и buyer-account fence. Передаваемый buyerId получен из подтверждённой связи, а запрос повторно проверяет её. Полный план добавляет проверки source coverage и status event cardinality по D02 до выдачи READY.

~~~sql
WITH current_link AS (
    SELECT l.tenant_id, l.buyer_user_id
    FROM crm.customer_buyer_link l
    JOIN crm.customer c ON c.tenant_id = l.tenant_id AND c.id = l.customer_id
    WHERE l.tenant_id = :tenant AND l.customer_id = :customer
      AND l.buyer_user_id = :fencedBuyer AND l.status = 'CONFIRMED'
      AND c.status = 'ACTIVE'
), placed AS (
    SELECT e.tenant_id, e.order_id, min(e.occurred_at) AS placed_at
    FROM current_link l
    JOIN crm.sales_order o ON o.tenant_id = l.tenant_id
                         AND o.buyer_user_id = l.buyer_user_id
    JOIN crm.sales_order_status_event e
      ON e.tenant_id = o.tenant_id AND e.order_id = o.id
    WHERE e.event_type = 'PLACED'
    GROUP BY e.tenant_id, e.order_id
    HAVING count(*) = 1
)
SELECT o.currency, count(*) AS order_count, sum(o.total_amount) AS booked_amount
FROM placed p
JOIN crm.sales_order o ON o.tenant_id = p.tenant_id AND o.id = p.order_id
WHERE p.placed_at >= :startInclusive AND p.placed_at < :endExclusive
GROUP BY o.currency
~~~

Вариант repository возвращает признак current_link из того же statement вместе с grouped rows, например отдельной однострочной метаданной частью через LEFT JOIN. Это различает отозванную связь и доказанно пустую cohort. Cardinality probe использует тот же утверждённый scope и snapshot. WHERE по currency параметризован. H2/PG binding проверяется на соответствующем этапе.

## 4. Customer 360 · C1/C2
~~~java
interface Customer360QueryService {
    CustomerSummary summary(VerifiedActor actor, UUID tenantId,
                            UUID customerId, CustomerSummaryQuery query);
    CustomerTimelinePage timeline(VerifiedActor actor, UUID tenantId,
                                  UUID customerId, TimelineQuery query);
    MetricEvidencePage metricEvidence(VerifiedActor actor, UUID tenantId,
                                      UUID customerId, EvidenceQuery query);
}
~~~

1. CRM авторизует профиль. SALES секция требует CRM_READ + SALES_ORDER_READ, CONFIRMED link и ACTIVE customer/buyer. Сохранить существующее поведение [CrmBuyerLinkService.orderHistory](https://github.com/alimbetov/RM/blob/f7910269df73564a6b1bf7d8f45bcc2766cc1b32/src/main/java/kz/rm/crm/CrmBuyerLinkService.java#L143). Кандидат C1: внутри отдельного CRM-owned SQL read contract взять buyer fence по тому же протоколу, затем одним финальным SQL statement перепроверить связь и отфильтровать факты. При разделе SQL между владельцами проверка и защищённая выборка обязаны оставаться одним statement в рамках согласованного owner read port или иметь квалифицированный transaction fence. Простой возврат buyerId после ранней проверки недостаточен.
2. Для C2 финальный запрос так же проверяет current link и присоединяет per-buyer projection по (tenant,buyer,method,generation). Read допускает оба порядка с revoke/dispute. Если изменение committed до финального statement, суммы и ссылки недоступны. Если операции перекрылись, результат соответствует snapshot финального statement. Account fence удерживается до commit. Глобальный actor/workspace и buyer lock порядок проверяется вместе с writer-ами CRM/IAM. Запрещено сохранять самостоятельный доступ к агрегату по customerId.
3. Timeline содержит typed ORDER/INTERACTION/TASK/CONTRACT refs. Owner фильтрует до LIMIT. Запрос TASK сохраняет creator/assignee predicate из TASK-owned read, включающий связь task_crm_followup. Существующий getMine полезен для detail, list требует bounded query. Profile/CRM_PII_READ и client-safe fields остаются самостоятельным решением owner.
4. При отсутствии доступа к секции ответ содержит только безопасный признак доступности либо опускает секцию. При разрешённом запросе и недоказанной атрибуции возвращается MISSING с безопасным reason. Detail повторно вычисляет eligibility, period/currency и matching generation. Изменение связи между страницами отменяет старый cursor scope.

## 5. Warehouse 360 · W1/W2/W3
~~~java
interface Warehouse360QueryService {
    StockPositionView position(VerifiedActor actor, UUID tenantId,
                               UUID warehouseId, UUID skuId);
    MovementPage movements(VerifiedActor actor, UUID tenantId,
                           WarehouseMovementQuery query);
    StockPeriodView reconcile(VerifiedActor actor, UUID tenantId,
                              StockPeriodQuery query);
    AssortmentPage classify(VerifiedActor actor, UUID tenantId,
                            AssortmentQuery query);
}
~~~

- W1 повторяет current SQL и observed row_version из [InventoryHistoryQueryService](https://github.com/alimbetov/RM/blob/f7910269df73564a6b1bf7d8f45bcc2766cc1b32/src/main/java/kz/rm/inventory/InventoryHistoryQueryService.java#L43). Adapter поддерживает verified principal. Требуемый warehouse scope для сотрудника применяется внутри запроса. Manager tenant scope появляется только после утверждения D08. Catalog/location labels выдаются по отдельным rights.
- Кандидат W1: IAM actor/workspace fence, затем единый owner SQL с eligible warehouse scope + stock position/history. Snapshot этого statement определяет порядок с assignment/revoke. [WarehouseStaffScopeService](https://github.com/alimbetov/RM/blob/f7910269df73564a6b1bf7d8f45bcc2766cc1b32/src/main/java/kz/rm/iam/WarehouseStaffScopeService.java#L53) содержит command-path locks. requireAssigned берёт scope → location, assign берёт location → scope после owner permission. Новый read не копирует цепочку блокировок. Существующие writers и предлагаемое чтение квалифицируются на PostgreSQL в обоих порядках до выбора более сильной сериализации.
- W2 использует recorded_at как предложенную time basis для window и сравнивает суммы с канонической историей. Позиционный posting_version проверяет порядок коммитов позиции, а recorded_at задаёт принадлежность окну. Если порядок версий и временное окно расходятся, opening/closing готовы только по утверждённой методике и доказанному anchor. Прямой расчёт по одному SQL snapshot и coverage впервые подтверждает parity. Проверяются legacy NULL версии, split transfer legs, quarantine and later restock lineage.
- W3 pure classifiers получают SKU, currency/unit, eligible amount/demand and coverage. Для ABC использовать line quantity × snapshot price при квалифицированной line/header parity. Предлагаемая ось tenant/currency в первом срезе сохраняет неопределённость складской атрибуции. Для XYZ owner выбирает fulfilled quantity и завершённые weekly buckets. Пороги из D03/D04 остаются proposed; код получает их из versioned method. Истинный complete zero отличается от unknown bucket.
- Live projection remains an owner read. C2/W2 analytics can lag and label freshness independently; drilldown получает соответствующие методику и поколение. Multi-query section summary не обещает глобальный snapshot между разделами.

### SQL-пример: позиция для назначенного сотрудника

После domain permission fence read statement включает текущий scope. Для роли управляющего используется отдельно одобренная ветка policy. SQL не принимает role из запроса.

~~~sql
SELECT b.item_id, b.location_id, b.on_hand, b.reserved,
       b.on_hand - b.reserved AS available, b.row_version
FROM crm.inv_stock_balance b
JOIN public.org_location w ON w.workspace_id = b.tenant_id AND w.id = b.location_id
JOIN public.iam_warehouse_staff_scope s
  ON s.tenant_id = b.tenant_id AND s.warehouse_id = b.location_id
 AND s.actor_user_id = :actor
WHERE b.tenant_id = :tenant AND b.location_id = :warehouse AND b.item_id = :sku
  AND s.state = 'ACTIVE' AND w.status = 'ACTIVE' AND w.location_type = 'WAREHOUSE'
~~~

## 6. Будущий Radar · проект интерфейса
~~~java
interface SignalProvider {
    SignalPage findVisible(VerifiedActor actor, UUID tenantId, SignalQuery query);
    SignalExplanation explain(VerifiedActor actor, UUID tenantId, SignalKey key);
}
~~~

Первый будущий SALES provider читает PLACED + текущую версию и owner status transition. Общий comparator используется одинаково у всех providers. Provider timeout маркирует результат как partial. EvidenceCursor и owner reference проходят повторную проверку, а действие идёт через существующий owner command с expected version. R0.1 adapter переиспользует TaskTimingQueryService; собственная классификация времени не вводится.

## 7. Контракты безопасности на уровне кода
Существующие Authenticated…Queries задают pattern verified entrypoint, а TenantAuthorization.requireForCommand используется в нескольких текущих fenced reads. Общий интерфейс R2 для fenced read утверждает IAM на A2. Он повторно проверяет active actor/member/workspace и domain grant с транзакционной свежестью. Не расширять DomainPermission или DomainModule «для удобства». Clock фиксируется один раз для периодов и evaluation metadata, но VerifiedActor expiry проверяется на текущем времени. Блокирующая postgres-транзакция должна позволять row locks. H2 проверяет совместимость, PostgreSQL определяет поведение блокировок.

## Обязательное дополнение MIC-R1 (2026-11-26)
[RM Mathematical Integrity Contract R1](../../architecture/RM-MATHEMATICAL-INTEGRITY-CONTRACT-R1.md). Registry обязан явно задавать aggregation strategy, fact identity, grain, cohort и единицу. Неизвестные комбинации отклонять. SKU count — exact identity union; не count offer_id или warehouse position.

## Final technical reconciliation — 2026-11-26
Actual base_dev types authoritative: MetricRegistry.Code/Definition; DataQuality READY/PARTIAL/MISSING/STALE/NOT_MEASURABLE; MetricResult(code, methodVersion, basis, evaluatedAt, period, quality, cells, reasons, Coverage); PeriodResolver.Period half-open Instant bounds, zoneId, calendarVersion; AuthorizedAnalyticalReadService.Query/Result and UUID generation/proof. Historical DTO sketches (including OptionalLong generation) superseded, not instructions to duplicate registry/enums. Extend only proven gaps; NOT_APPLICABLE and ticket validity distinct from DataQuality.
