# RM Mathematical Integrity Contract R1 (MIC-R1)

Дата: 2026-11-26. Требование владельца продукта: обязательное для текущих и будущих аналитических ТЗ RM. P0. Не является доказательством реализации или допуском production.

## MIC-01 — Паспорт метрики
Каждая метрика задаёт code/methodVersion, owner source, fact grain и identity, cohort/status, временную ось и calendarVersion, tenant и authorization scope, dimensions и их историческую версию, currency/UOM, null/zero/coverage, стратегию агрегации, независимый oracle и сценарии приёмки. Неизвестная комбинация отклоняется. Изменение семантики требует новой версии.

## MIC-02 — Математика
- ADDITIVE: SUM только непересекающихся contributions в совместимых currency/UOM; booked quantity, fulfilled quantity и stock movement — разные метрики. PRODUCT и SERVICE не смешивать. Коммерческие order units не объявлять физической UOM без доказательства.
- EXACT_DISTINCT: cardinality объединения tenant-local order_id, line_id, sku_id, customer_id на целевом grain; не SUM дочерних counts. Статусы и cohorts не смешивать. При одном доказанном уникальном событии disjoint time buckets могут быть аддитивны исключительно по этой оси.
- SEMI_ADDITIVE: snapshot balances на один as-of, не SUM по времени; совместимые units и непересекающиеся позиции.
- DERIVED_RATIO: пересчёт numerator/denominator на целевом grain с собственной стратегией каждого операнда (distinct denominator не суммируется). Zero denominator → NOT_MEASURABLE; неизвестный → MISSING/PARTIAL.
- NON_ADDITIVE: пересчёт из достаточной статистики или исходных фактов (например median, ABC/XYZ, quantile); не среднее дочерних коэффициентов.
- NUMERIC/BigDecimal, округление по versioned method; отсутствие данных не равно нулю. Approximate distinct не подменяет exact.

## MIC-03 — Rollup и безопасность
Область КАТО L2 × историческая номенклатурная группа × период; склад сохраняется в grain. Tenant и Warehouse RBAC применяются до агрегации, без утечки через total, denominator, cache или drilldown. Booking без доказанного склада/географии не получает вымышленное распределение. AS_BOOKED и RESTATED разделены. L0/L1/L2/L3 (где метрика применима) используют одинаковую семантику. Материализация exact distinct сохраняет membership или эквивалентное точное mergeable состояние; additive totals не умножаются при JOIN membership. Пересекающиеся периоды не суммировать. Late events, replay, rollback, rebuild и revision fence сохраняют математику и provenance. Старый proof не допускает новую методику; необходима повторная квалификация.

## MIC-04 — Обязательная приёмка
| ID | Fixture / invariant |
|---|---|
| MIC-Q01 | Один заказ, смартфон и ноутбук: orders=1, units=2, SKU=2, lines=2 на родительской категории |
| MIC-Q02 | Один заказ, 3+2 единиц: orders=1, units=5; повтор SKU в двух строках: SKU=1, lines=2 |
| MIC-Q03 | Один заказ/строка split по складам/областям (если owner contract допускает): exact union, сумма только непересекающихся fulfillment contributions |
| MIC-Q04 | День/неделя/месяц/квартал/год и пересечения; исторические категории; distinct customers |
| MIC-Q05 | PRODUCT/SERVICE/UNKNOWN; несовместимые UOM/currency; true zero vs missing |
| MIC-Q06 | Отмена, возврат, частичное исполнение, duplicate/replay, late event; booked history не переписывается |
| MIC-Q07 | Ratio of sums с exact distinct denominator, zero denominator, snapshot as-of, non-additive classification |
| MIC-Q08 | Tenant collision, warehouse allow/deny/revoke, aggregate/drilldown/cache parity |
| MIC-Q09 | Независимый owner SQL vs materialized result; mutation of quantity/membership must fail proof; concurrency/rebuild/rollback |
| MIC-Q10 | Real PostgreSQL, exact-SHA evidence, no skipped mandatory cases, performance budget based on declared workload |

## MIC-05 — Admission и распространение
RM Intelligence, Customer 360, Warehouse 360/ABC/XYZ, Radar, Event & Notification Builder (metric predicates) и будущие аналитические ТЗ обязаны ссылаться на MIC-R1 и перечислять применимые MIC-Q IDs. При расхождении исправляется production code/DDL, не ожидаемое значение теста. B4.5D включает REV-B4-04; B4.6/production HOLD до обязательной квалификации. Не расширять MVP автоматически: новые метрики вне согласованного scope требуют отдельного admission. Нормативное требование не означает, что текущие тесты уже пройдены.
