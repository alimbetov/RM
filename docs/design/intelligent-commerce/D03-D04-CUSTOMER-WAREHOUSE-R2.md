# D03/D04 — Customer 360 и Warehouse 360
R2.0-draft · SPECIFIED / PROPOSED · Source SHA d9242e539a07ef1843c0e72aba9c651204bf42ae. Owners: CRM, SALES, INVENTORY, IAM; methodology owner approval OPEN.
Контракты [D01](D01-SEMANTIC-SECURITY-EVENT-CONTRACTS-R2.md), [метрики](D02-METRIC-CATALOG-AND-ORACLES-R2.md).

## 1. Customer 360: операции сервисного уровня
Предлагаемые use-case contracts, не существующие классы:
- customerSummary(actor,tenant,customer,period,currency): разрешённые метрики, безопасное состояние attribution, quality.
- customerTimeline(actor,tenant,customer,filter,cursor,limit): типизированные ссылки на order/interaction/task/contract, стабильный tie-break; timestamps с указанным смыслом.
- customerMetricEvidence(actor,tenant,customer,resultRef,cursor): факты именно выбранной методики/cohort/generation, повторная authorization.
- customerPeriodComparison(...): две совместимые выборки; неполный текущий период обозначается, допускается сравнение одинаковых elapsed windows по отдельной policy.

Граница ответа по секциям: профиль, SALES, TASK, договоры/взаимодействия. Отсутствие SALES_ORDER_READ не разрешает count/sum/lastOrder в другом блоке. Разрешённый профиль может отдаваться без закрытой секции; существование скрытых заказов не раскрывается. Детали PII читаются из CRM owner под CRM_PII_READ.

### Identity и timeline
CrmBuyerLinkService.orderHistory — исходный security contract; текущая подтверждённая связь и активные customer/buyer. Current attribution явно указана; никакого matching email/phone/name. Unknown/missing link не даёт вывод «клиент ничего не покупал». Не возвращать buyer account status пользователю без права на него.

Timeline объединяет только заранее разрешённые query slices. Сначала owner filters, потом LIMIT; запрещено загрузить всё и отфильтровать в памяти. Cursor привязан к tenant/customer/filter/sort/versions и непрозрачен для клиента. Порядок по нормализованному time + source type + stable id; tie-break не означает причинный/commit порядок.
Прямые owner detail ссылки не содержат токены получения, контакты или сырые event payload.
Live pagination не snapshot; если требуется неизменяемый отчёт, отдельная зафиксированная generation. Limit и max history window — ограниченные значения из будущей policy, не новая публичная SLA.

### Критерии
CUSTOMER-01: суммы и списки используют одинаковую cohort и currency.
CUSTOMER-02: изменение storefront price не меняет исторические суммы.
CUSTOMER-03: revoke/dispute/suspend × summary/detail проверены в обоих commit orders.
CUSTOMER-04: TASK сохраняет creator/assignee visibility даже при CRM_READ.
CUSTOMER-05: permission denied не превращается в PARTIAL с утёкшим счётчиком.
CUSTOMER-06: link change не требует ожидания rebuild для прекращения видимости.

## 2. Warehouse 360: live + history
Предлагаемые операции:
- warehousePosition(actor,tenant,warehouse,item): current onHand/reserved/available, owner row version.
- warehouseMovements(...,period,timeBasis,cursor,limit): signed movements с source refs и reason.
- warehouseReconciliation(...,period): opening + signed movements = closing, coverage и version basis.
- warehouseReservations(...): активные резервы, возраст с Clock, связь с доступным заказом.
- assortmentClassification(...,method,period,currency): qualified ABC/XYZ, каждый SKU с reason и coverage.

Current read authority — InventoryHistoryQueryService/StockBalance owner. Worker warehouse assignment и manager tenant-wide visibility требуют отдельного утверждённого read-scope contract; reuse command scope не даёт новые полномочия. Склад может быть исторически закрыт: analytical history и возможность текущей операции различаются, доступ по-прежнему проверяется.

WAREHOUSE-01: reservation changes не включаются в stock in/out.
WAREHOUSE-02: transfer даёт два legs в одном owner transaction; tenant net=0 для того же SKU/unit, по складам видны -/+; transfer header не третий movement.
WAREHOUSE-03: RESTOCK учитывается по linked movement один раз, не ещё раз по return posting.
WAREHOUSE-04: QUARANTINE отдельный баланс, недоступный для продажи; DISPOSE из возврата не повторный debit sellable.
WAREHOUSE-05: nullable legacy posting_version → explicit coverage limitation; gaps после reserve/release допустимы.
WAREHOUSE-06: initial balance без reconstructible incoming ledger не позволяет выдумать opening=0.
WAREHOUSE-07: stock days/turnover требуют положительного и квалифицированного demand denominator; zero/unknown demand не делить и не маскировать.
WAREHOUSE-08: возврат не трактуется автоматически как отрицательный demand/refund; methodology decision отдельно.

## 3. ABC: предлагаемая первая методика
Цель — классификация SKU по доле выбранной положительной базы за период, не оценка прибыли.
Рекомендуемый минимальный срез: tenant + одна currency + период + eligible PLACED line amount по historical catalog_item_id_snapshot. Warehouse dimension для booked orders BLOCKED до доказанной historical allocation; нельзя распределять продажи по текущему stock/reservation или дублировать order amount на всех складах. Warehouse ABC может потребовать другой, отдельно утверждённой fulfilled-line методики.

Входы: line quantity × unit_price_snapshot; доказанное совпадение line/header currencies и totals, исключение unallocated discount. Null SKU/неполная атрибуция отражаются отдельно; итог READY только при достаточном покрытии. Не использовать product/service смесь без явного scope.
Кандидат границ: A 80%, B до 95%, C остаток. Не утверждён.

Предлагаемый алгоритм:
1. Отобрать eligible positive metric values; zero показать отдельно как NO_ACTIVITY, отрицательные — отдельное объяснение/методика.
2. Сортировать value DESC, стабильный SKU ID ASC.
3. Рассчитать точную сумму и cumulativeBefore/cumulativeAfter.
4. Кандидат правила: класс по cumulativeBefore — меньше 0.80 A, меньше 0.95 B, иначе C. Позиция, пересекающая границу, остаётся в предыдущем классе.
5. Показать metric base, share, cumulative shares, methodVersion и quality.

Это правило обеспечивает включение крупнейшего SKU в A, но может разделять равные значения по stable key. Альтернатива «все ties вместе» меняет результат и требует отдельной версии; решение D-03 OPEN.
Примеры для обсуждаемого алгоритма: [80,15,5] → A,B,C; [70,20,10] → A,A,B; [100] → A. Полный нулевой набор → NO_ACTIVITY, не деление на 0. Mixed currency → отказ/раздельные результаты.

## 4. XYZ: спецификация вычисления и достаточности
Предлагается сначала completed quantity по SKU из квалифицированного fulfillment evidence; booked demand — отдельная методика. Нельзя брать весь STOCK_OUT_QTY: transfer/write-off не продажи. Выбор demand definition OPEN.
Bucket: завершённые ISO недели в tenant zone, одинаковая календарная политика; текущая неполная неделя исключается из READY. Candidate minimum 12 buckets, candidate boundaries X<=10%, Y<=25%, Z>25%; всё требует business approval.

Формула кандидата: population mean=sum(x)/n; variance=sum((x-mean)^2)/n; CV=sqrt(variance)/mean. Для сравнения с неотрицательным threshold t можно сравнивать variance и t²×mean², избегая округления sqrt у границы. Отображаемый CV округляется отдельно; sample variance n-1 не подменяет population variance.

Проверка eligibility до классификации: полные bucket histories, same SKU/unit/calendar/method, n>=approved minimum, mean>0. Нули допустимы только при доказанном покрытии; missing bucket не заменяется 0. Неполная история, существенное цензурирование из-за stockout или неразрешённая сезонность → UNKNOWN/explicit caveat по утверждённой policy. Наблюдаемые продажи не доказывают неудовлетворённый спрос.

Контрольные примеры: 12 значений по 10 → CV=0, X при выбранных границах; шесть 0 и шесть 20 → mean=10, population variance=100, CV=100%, Z; один пропущенный bucket → UNKNOWN; все нули → NO_ACTIVITY/NOT_MEASURABLE, не Z.
ABC×XYZ matrix включает отдельные UNKNOWN/NO_ACTIVITY категории, не исключает их из denominator незаметно. Drilldown показывает исходные buckets/основания без закрытых деталей.

## 5. Приёмка и зависимости
C1 после B1: direct Customer reads; W1 после B1: live Warehouse. C2/W2 после B4: reconciliation с проекциями. W3 после W2/B4 и утверждения методики. Ранний direct read не закрывает весь BUS-MVP-I1.
Основные семьи: IE-Q11…19, MC-01/02/04; возвращаемся к source owner тестам из D07. Сервисные сценарии проектируются сейчас, REST/UI отложены до G2.
