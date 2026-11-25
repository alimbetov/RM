# D02 — Каталог метрик, расчётный контракт и независимые оракулы
R2.0-draft · SPECIFIED / NOT EXECUTED · Source SHA d9242e539a07ef1843c0e72aba9c651204bf42ae.
Владелец: аналитическая методика + SALES/INVENTORY owners. Источники [IE-00A](../../reviews/RM-INTELLIGENCE-IE-00A-SOURCE-CONTRACT-INVENTORY.md); общие правила [D01](D01-SEMANTIC-SECURITY-EVENT-CONTRACTS-R2.md). Утверждение точных методик — A2/A3; код — B1 после admission.

## 1. Восемь основных паспортов
Общие dimensions: tenant, approved subject scope, period/calendarVersion, methodVersion; currency либо SKU/unit обязательны по типу. Permission применяется до агрегирования. Базовые случаи в [D07](D07-SCENARIOS-QUALIFICATION-R2.md); точные связи в JSON.

| ID / code | Источник, grain, формула | Время / aggregation | Admission / quality |
|---|---|---|---|
| M-S01 PLACED_ORDERS | SRC-01/02; один допустимый PLACED на заказ; COUNT eligible order | occurred_at PLACED; additive по disjoint periods | Полнота обоих checkout paths, cardinality=1; legacy missing/duplicate отмечены |
| M-S02 BOOKED_ORDER_AMOUNT | SRC-01/02; SUM total_amount тех же eligible orders, отдельно currency | occurred_at PLACED; additive по той же валюте | Неизменяемость суммы и currency, точное совпадение cohort M-S01; packaging отдельно |
| M-S03 CONFIRMED_ORDERS | SRC-02; один допустимый CONFIRMED на заказ | occurred_at CONFIRMED; additive по времени | Owner transition, atomic reserve/order/event; повтор не увеличивает |
| M-S04 FULFILLED_ORDERS | SRC-02; один допустимый COMPLETED на заказ | occurred_at COMPLETED; additive по времени | Generic fulfill и secure pickup; обе реализации дают одну семантику |
| M-S05 CANCELLED_ORDERS | SRC-02; один допустимый CANCELLED на заказ | occurred_at CANCELLED; additive по времени | REJECTED отдельно; cancellation после другого периода не уменьшает его PLACED |
| M-I01 STOCK_IN_QTY | SRC-08; SUM positive delta_quantity на tenant/location/item/unit, breakdown movement/reason | Предложено recorded_at; additive совместимых quantities | Факт движения, одинаковая UOM; transfer-in/restock/correction различимы |
| M-I02 STOCK_OUT_QTY | SRC-08; SUM abs(negative delta_quantity), тот же grain | Предложено recorded_at; additive совместимых quantities | Исполнение/transfer-out/write-off отдельно; reservation не расход |
| M-I03 CURRENT_AVAILABLE | SRC-07; on_hand-reserved одной позиции в одном statement | Current as-of gauge; не суммировать по датам | Нет позиции → unavailable/not found по read contract, не 0; live owner authority |

M-S01/03/04/05 — переходы за период. Это разные множества: заказы, размещённые ранее, могут быть подтверждены сегодня. CONFIRMED/PLACED за календарный месяц не является конверсией. Настоящая конверсия требует cohort размещения, окна наблюдения и отдельно утверждённой методики.

Привязки дополнительных dimensions:
- buyer — только сохранённый buyer_user_id; nullable legacy отдельно.
- customer — текущая подтверждённая CRM link поверх buyer scope, не snapshot identity на дату продажи.
- SKU — только sales_order_line.catalog_item_id_snapshot для line metrics; не JOIN текущего offer.
- warehouse — для owner stock facts. Для PLACED может отсутствовать, current reservation/warehouse не переносится в прошлое.
- Категория/адрес — current labels явно маркированы; исторический category/geography rollup требует snapshot.

## 2. Производные показатели и границы
| Показатель | Контракт |
|---|---|
| Средний размещённый заказ | M-S02 / M-S01 из одной currency/cohort/filter выборки; denominator=0 → NOT_MEASURABLE; хранить numerator/denominator |
| Последний заказ | MAX допустимого PLACED time; current-link security; отсутствие полного покрытия не означает never purchased |
| Частота заказов | Count / явно определённая длительность наблюдения; версия окна и календаря, без LTV/churn утверждений |
| Изменение периода | current-prior; процент только при prior>0 и совместимых окнах; prior=0 → процент неопределён |
| Opening/closing | Доказанная позиционная история + signed movement sum, одна временная основа; initial balance/legacy отдельно |
| ABC | [D03/D04](D03-D04-CUSTOMER-WAREHOUSE-R2.md), выбранная положительная база и валюта |
| XYZ | Тот же документ; complete regular demand buckets и versioned methodology |

Никакого SUM дневных средних, SUM daily distinct customers или суммы текущих остатков за месяц. Return quantity не уменьшает BOOKED_ORDER_AMOUNT без отдельной monetary refund модели. Payment, AR/AP, margin, COGS, cashflow — BLOCKED_DEPENDENCY при отсутствии квалифицированного финансового ledger.

## 3. Контроль cardinality и SQL
Следующий SQL — проект независимой reference query, не исполнялся и не является разрешением добавления кода. Его SQL predicates дополняются approved authorized scope. Он не используется для определения полноты committed feed.

~~~sql
WITH placed AS (
  SELECT tenant_id, order_id,
         COUNT(*) AS event_count, MIN(occurred_at) AS placed_at
  FROM crm.sales_order_status_event
  WHERE tenant_id = :tenant AND event_type = 'PLACED'
  GROUP BY tenant_id, order_id
)
SELECT o.currency, COUNT(*) AS placed_orders, SUM(o.total_amount) AS booked_amount
FROM crm.sales_order o
JOIN placed p ON p.tenant_id = o.tenant_id AND p.order_id = o.id
WHERE o.tenant_id = :tenant
  AND p.event_count = 1
  AND p.placed_at >= :start AND p.placed_at < :end_exclusive
GROUP BY o.currency;
~~~

Обязательная companion validation до выдачи READY: missing PLACED для ожидаемого admitted order population; event_count>1; некорректная terminal history; null/invalid attribution; несовместимые line/header currencies; line sums vs immutable total; источник временной полноты. Исключённые записи не превращаются в тихую потерю: PARTIAL/MISSING и безопасная диагностика. При неразрешимом конфликте не выдавать сомнительное число как authoritative.

Событие пересечения интервала не выбирается через MIN если переход повторён: event_count=1 обязателен. Не обрезать события по периоду до проверки duplicate cardinality, иначе повтор за его границей останется незамеченным. No COALESCE(value,0) до доказательства complete empty.

Stock reference:
~~~sql
SELECT location_id, item_id,
       SUM(CASE WHEN delta_quantity > 0 THEN delta_quantity ELSE 0 END) AS in_qty,
       SUM(CASE WHEN delta_quantity < 0 THEN -delta_quantity ELSE 0 END) AS out_qty
FROM crm.inv_stock_movement
WHERE tenant_id = :tenant
  AND recorded_at >= :start AND recorded_at < :end_exclusive
GROUP BY location_id, item_id;
~~~
Этот запрос считает движения в заданном окне, а не доказывает opening/closing, UOM или полноту поздних commit. Возвращаемые dimensions ограничиваются approved read scope. Quantity source NUMERIC сохраняется без double conversion. Для empty position scope проверяется owner position existence отдельно.

## 4. Независимость оракула
Три независимых уровня:
1. Небольшие hand-calculated examples с фиксированным ожидаемым результатом.
2. Direct SQL по каноническим фактам без чтения projections/receipts и без общего projection-calculation helper.
3. Проверяемые L1/L2 результаты после consumer processing/replay/rebuild.

Факт fixtures создаётся настоящими owner services; прямой SQL допустим для явно помеченных corrupted/legacy negative fixtures. Сравнение двух вызовов одной реализации не доказательство. Decimal equality числовая; method/period/currency/coverage сравниваются отдельно. Для exact arithmetic нет произвольного tolerance; согласованный rounding применяется только по контракту.

## 5. Сквозной ручной пример
Это проверяемые ожидания для будущих fixtures, не выполненная симуляция. Один tenant/warehouse/SKU PRODUCT, начальный остаток 0, receipt +12, price 1000 KZT. Два заказа A=3 шт (3000 KZT), B=2 шт (2000 KZT), оба PLACED и CONFIRMED в одном периоде. A исполнен, B отменён. Одна единица A возвращена и RESTOCK фактически проведён.

| Точка | onHand | reserved | available | Смысл |
|---|---:|---:|---:|---|
| После receipt | 12 | 0 | 12 | Одно движение +12 |
| После confirm A/B | 12 | 5 | 7 | Резервы без расходного движения |
| После fulfill A | 9 | 2 | 7 | Одно суммарное списание 3, резерв A закрыт |
| После cancel B | 9 | 0 | 9 | Освобождение 2 без движения |
| После RESTOCK 1 | 10 | 0 | 10 | Приход +1 со ссылкой на возврат |

Ожидание периода: M-S01=2, M-S02=5000 KZT, M-S03=2, M-S04=1, M-S05=1, M-I01=13, M-I02=3, M-I03=10; average=2500 KZT. Отдельные входные origins +12 RECEIPT и +1 RESTOCK видны в breakdown. Return не меняет booked amount. Если вместо RESTOCK был QUARANTINE, sellable=9, quarantine=1; после DISPOSE sellable остаётся 9, quarantine=0. Последовательности выполняются в отдельных fixtures, не складываются.

## 6. L1/L2, update и rebuild
L1 key включает metric/method, tenant, time bucket/calendar, допустимые dimensions/currency/unit и projection generation. Полный Cartesian cube не материализовать: cardinality и query patterns утверждаются до DDL. Tenant/buyer/SKU/warehouse slices квалифицировать отдельно.

L2 D→W/DEC/M и далее Q/H/Y только если операция композиционна и исходный grain достаточен. Week crossing month строится из точных дней. Ratios сохраняют sum numerator/sum denominator; current available всегда owner read. Customer link не материализуется как бессрочное разрешение.

Receipt+delta атомарны. Повтор canonical source identity+consumer generation — no-op с проверкой digest; тот же key с другим содержимым — conflict, quarantine/review, не overwrite. Исправление факта только по owner-correction semantics; subscriber не редактирует прошлый ledger. Устаревшая метрика не выдается READY.

Backfill/rebuild obey D01; additionally validate counts, amounts, cohort membership, bad rows, boundaries and lineage digests. Generation switch блокируется при mismatch; old readers завершаются согласованно. Прогресс отдельно по источникам/consumer generation; единый max timestamp не proof of completeness.

## 7. Производительность и exit B1/B3/B4
Подготовить EXPLAIN plans, rows scanned/returned, temp spill/IO, p50/p95/p99 и allocation/GC на PostgreSQL 17 с 1k/10k/100k sources, dense/sparse/tenant skew. Нагрузочные бюджеты утверждаются до замеров; лимиты batch/window/cardinality обязательны, конкретные значения OPEN.
Exit: все 8 паспортов admitted либо явно BLOCKED с причиной, signed identity fixtures, source validity, independent hand+SQL oracle, затем L1/L2 parity и recovery. Наличие этого документа не закрывает ни один runtime gate.
