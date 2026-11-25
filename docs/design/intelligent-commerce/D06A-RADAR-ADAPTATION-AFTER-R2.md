# D06A · Адаптация Business Radar после Intelligent Commerce R2

R2.2 · 2026-11-25 · проект · DEFERRED_ANALYTICS_FIRST. Снимок исходников [base_dev a02766d](https://github.com/alimbetov/RM/tree/a02766d76c38e1e15362d717a2bf729e6f01dfba), исходная редакция [R2.1 015f765](https://github.com/alimbetov/RM/tree/015f765447fd1d26e5d119c38c30e3dc9e6e05ea/docs/design/intelligent-commerce). Это приложение к [D06](D06-RADAR-FUTURE-CONTRACTS-R2.md) и [Radar Vision](../../spec/RM-RADAR-VISION-R1-INTELLIGENT-BUSINESS-CONTROL-CENTER.md). Приёмка описана в [реестре адаптации](RADAR-ADAPTATION-TRACE-R2.json).

## 1. Источники RM и порядок адаптации

Первый результат для продавца — список заказов, которые пора подтвердить, с временем ожидания, объяснением и переходом к заказу. Предлагаемый первый сигнал **S01 ORDER_PENDING_CONFIRMATION**. Он использует текущий статус SALES и подтверждённое время перехода. Остальные вертикали подключаются по мере готовности собственных контрактов.

| Что уже есть в RM | Что получает Radar | Что требуется подтвердить |
|---|---|---|
| TASK R0.1: [TaskOverdueQueryService](https://github.com/alimbetov/RM/blob/a02766d76c38e1e15362d717a2bf729e6f01dfba/src/main/java/kz/rm/tasks/TaskOverdueQueryService.java#L60), [TaskTimingQueryService](https://github.com/alimbetov/RM/blob/a02766d76c38e1e15362d717a2bf729e6f01dfba/src/main/java/kz/rm/tasks/TaskTimingQueryService.java#L33), [TaskCalendarQueryService](https://github.com/alimbetov/RM/blob/a02766d76c38e1e15362d717a2bf729e6f01dfba/src/main/java/kz/rm/tasks/TaskCalendarQueryService.java#L113) | TASK provider переиспользует классификацию, original/effective timing, permissions, курсор и повторную проверку срока токена | R0.1D, полный набор RAD/CAL и эквивалентный результат по одному входу. Для общей выдачи отдельно подтвердить comparator и общий cutoff. R0.2 reminder text сохраняет собственный этап |
| SALES: [AutoOrderService](https://github.com/alimbetov/RM/blob/a02766d76c38e1e15362d717a2bf729e6f01dfba/src/main/java/kz/rm/autoorder/AutoOrderService.java#L243), [SalesInventoryCommandService](https://github.com/alimbetov/RM/blob/a02766d76c38e1e15362d717a2bf729e6f01dfba/src/main/java/kz/rm/sales/SalesInventoryCommandService.java#L82), [SalesOrderQueryService](https://github.com/alimbetov/RM/blob/a02766d76c38e1e15362d717a2bf729e6f01dfba/src/main/java/kz/rm/sales/SalesOrderQueryService.java#L24) | S01 читает текущий PLACED и канонический status event. Позже S03 использует READY_FOR_PICKUP, S04 — стадию комплектации | Producer inventory A1/B2, единый логический ключ одноразового перехода, scope и согласованный SQL. Списочный SQL квалифицируется отдельно от текущих detail/history |
| CRM и Customer 360 | C01 по политике договора, C02 по разрешённой истории взаимодействий, C03/C04 по квалифицированным когортам C2 | PLACED без buyer и поздняя привязка через [verifyEmail](https://github.com/alimbetov/RM/blob/a02766d76c38e1e15362d717a2bf729e6f01dfba/src/main/java/kz/rm/autoorder/AutoOrderService.java#L490). Current CRM link и её revision проверяются при выдаче и раскрытии оснований |
| INVENTORY и Warehouse 360 | I01 по живому остатку, I02 по активным резервам, I03 по факту инвентаризации, I04 по квалифицированной истории оборачиваемости | Один read scope для B1/W1/Radar, в том числе legacy IAM. Прямой путь [STOCKTAKE_ADJUSTMENT](https://github.com/alimbetov/RM/blob/a02766d76c38e1e15362d717a2bf729e6f01dfba/src/main/java/kz/rm/inventory/InventoryStocktakeService.java#L147) входит в manifest |
| R2 Metric Registry и поколения проекций | Период, валюта/unit, методика, coverage, freshness и происхождение данных для аналитических сигналов | Воспроизводимая пересборка, поздние факты и проверяемое переключение поколения |
| Event & Notification Builder | Возможный канал сигнала после допуска MC-07 | Версия правила для запоздавшего события, audience/expiry, дедупликация и текущие права получателя |
| FX и будущий финансовый ledger | F03/F04 по квалифицированным rate/policy. F01/F02/F05 после квалификации финансового источника | Раздельные валюты и версии курса, источник invoice/payment/allocation для долга, подтверждённый COGS для маржи и обязательства для cashflow |

## 2. Первый срез S01

Актор передаётся через VerifiedActor. Запрос содержит tenant, разрешённые фильтры, limit и cursor. Кандидат допуска — актуальный SALES_ORDER_READ и утверждённый scope заказов. Политика включается для конкретного tenant и имеет revision. Порог, календарь и severity подлежат согласованию. Наличие новой политики влияет только на новый тип сигнала.

- Канонический факт — текущий PLACED и ровно один допустимый переход PLACED для того же tenant/order. Один SQL связывает строку заказа, owner event и область видимости. Карточка содержит только безопасный order ref, observed state, reason, время ожидания, policy revision и отметку полноты.
- Предлагаемый первый порог использует elapsed time от подтверждённого occurred_at и включает равенство. Проверки охватывают до порога, ровно порог и после, будущую или отсутствующую дату, поздний commit и дубликат перехода с разными UUID.
- Stable signal key состоит из tenant + type + order + authoritative PLACED occurrence. Policy revision меняет explanation и ranking. Повторная оценка того же факта сохраняет ключ.
- CONFIRMED/CANCELLED исключает ожидающий заказ по актуальному состоянию. Detail повторно проверяет права и владельца факта. Если действие требует expected version, а owner API ещё не поддерживает контракт stale action, добавить его отдельным решением SALES перед допуском действия.
- Missing или duplicate transition отражается как неполнота источника. Для скрытого объекта отсутствуют карточка, сведения о причинах и счётчики. Полная пустая выборка отличается от нуля при неполных данных.

~~~java
interface BusinessSignalProvider {
    SignalPage listVisible(VerifiedActor actor, UUID tenantId, ProviderQuery query);
    SignalDetail explain(VerifiedActor actor, UUID tenantId, SignalKey key);
}
~~~

Имена типов уточняются по [CODE-01](CODE-01-JAVA-AND-READ-CONTRACTS-R2.md). Предлагаемый SQL сначала применяет tenant, scope, статус, policy и eligibility. Cardinality переходов проверяется до фильтра возраста и LIMIT. Полный порядок использует severity, actionable due и stable key. При фиксированном elapsed policy actionable due = occurred_at + threshold. Если для стоимости ограничен диапазон проверяемых кандидатов, указать PARTIAL/BUDGET_EXHAUSTED. Полнота проверяется независимым SQL из owner ledger, в том числе для статуса без события и редких совпадений.

Первая реализация ограничена прямым owner read и одним SALES provider. План проверяется на старых заказах, редких совпадениях, tenant skew, 1k/10k/100k и реальном пересечении с checkout/confirm/cancel/TASK. Индексы по фактическим предикатам рассматриваются после EXPLAIN и замеров.

В пилоте продавец видит «Заказ ожидает подтверждения», время начала ожидания, применённый порог и переход к заказу. При изменении статуса оператор получает актуальную деталь. Подтверждение выполняет SALES. До пилота определить eligible population и окно наблюдения. Измерять медиану времени до первого полезного действия, долю применимых сигналов и число пропущенных случаев по независимому оракулу. При отсутствии базы для сравнения метрика считается NOT_MEASURABLE.

## 3. Общий контракт карточки и выдачи

| Ситуация | Ожидаемый результат |
|---|---|
| Достаточно свежие факты подтверждают условие | ACTIVE с объяснением и owner ref |
| Текущий источник доказывает прекращение ранее наблюдавшегося риска | RESOLVED в доступной истории, если история отдельно согласована |
| Факты отсутствуют, просрочены, противоречивы или provider недоступен | UNKNOWN/STALE, безопасный reason и PARTIAL на уровне выдачи |
| Истекла политика или срок карточки | Пересчёт пригодности. Истечение само по себе не подтверждает устранение причины |
| Текущие права исключают объект | Объект исключён до ранжирования, счётчиков и детализации |
| Источник полностью обработан и карточек нет | COMPLETE_EMPTY для авторизованной области |

Состояние риска и полнота выдачи живут в отдельных полях. Один cutoff помогает сравнивать возраст. Каждый provider указывает observedAt и границу доказательств. При PostgreSQL READ COMMITTED видимость фактов определяется снимком отдельного SELECT. Общий cutoff сам по себе не синхронизирует снимки разных запросов.

Top-K нескольких providers использует равный полный comparator и фильтрацию до LIMIT. На фиксированном наборе каждый provider возвращает лучшие K после общего seek. Тест сравнивает объединение с независимым полным порядком. Сортировка TASK из текущего API требует проверки совместимости. Partial-выдача сохраняет недоступные домены в безопасных метаданных и не выдаёт количество видимых карточек за total count.

Cursor связывает tenant, actor scope, filters, limit, comparator/policy revision, список providers, режим, generation где применимо и последнюю позицию. При изменении доступности источников между страницами начать новое чтение либо выбрать явно описанный режим с фиксированным набором providers. Permissions проверяются заново. Для CRM cursor включает версию связи и инвалидируется при отзыве и повторной привязке той же пары buyer/customer. При live changes возможны сдвиги между страницами. Точный обход относится к фиксированному набору.

Для I01 ключ позиции и правила даёт одну текущую карточку. Выделение отдельных эпизодов выхода за порог зависит от подтверждённой истории переходов. Scope и policy учитывают warehouse, SKU и единицу. Секреты выдачи, контакты, bearer, платёжные данные исключены из карточек и логов. Explain повторяет owner authorization и live-state проверку.

## 4. Как сигналы наследуют выводы аудита R2

1. **SALES.** S01/S03 используют канонический переход и current state. Проверяются оба порядка read vs confirm/cancel/fulfill. Для S02 актуальные quote revision, expiry и отзыв. S05 получает return stage от владельца. Сквозной пример с физическим возвратом использует Secure Pickup handover.
2. **CRM.** C03/C04 требуют, чтобы прямое и материализованное чтение учитывали один набор заказов после verifyEmail. В расчёте и evidence действует current-link policy. Неполное и несопоставимое окно исключает вывод об изменении активности. Для C04 подтвердить horizon, eligibility и consent. C01 сохраняет inclusive endsOn из ContractExpiryPolicy.
3. **INVENTORY.** I01 читает live balance в текущем складском scope. Для I02 связанный заказ раскрывается с SALES grant. I03 сверяет stocktake с audit/ledger. Для I04 различаются обороты по recorded_at и доказуемый исторический остаток по позиционному порядку. ABC booked amount зависит от tenant/currency и доступа к SALES данным. XYZ fulfilled quantity требует покрытия, единиц и явного учёта периодов отсутствия товара.
4. **Проекции.** Методика, generation, актуальность и coverage попадают в основания сигнала. Неполное переключение или отставание даёт UNKNOWN/STALE. Действие опирается на live read владельца.
5. **EVT.** Допуск MC-07 фиксирует политику late commit относительно enable/disable, аудиторию, expiry и recheck. Replay сохраняет первоначальное решение. Разрешённый ручной retry после DEAD использует прежний intent ID и новый бюджет с возрастающим lease generation.
6. **Predictive/Advisor.** R6 требует воспроизводимых outcome labels, as-of features, temporal split, baseline, calibration, abstention и drift/rollback. При недоступности модели остаются доступны детерминированные сигналы. R7 требует явного подтверждения пользователем, повторной owner-проверки, expected version, идемпотентности и атомарности команды.

## 5. Допуск по действующему DAG

| Этап | Что требуется |
|---|---|
| R0 | R0.1D и существующая TASK регрессия. Временный режим C3 относится к интеграции ядра. Перед расширением восстановить C3 push и подтвердить PASS по действующим ограничениям 200ms/10% |
| R1 | R0.2 Smart Deadline под отдельным решением. Текст, локаль, границы и stale state по действующему контракту. UI проходит G2 |
| R2 | R0, B4, G1 и Vision A1–A6 для SALES. Отдельный scope, первый кандидат S01. Следующие сигналы добавляются по собственным доказательствам. Публичный пилот следует G2/G3 и действующему post-MVP решению либо явному исключению |
| R3 | R2 + C2 и собственные Vision A1–A6 по CRM. Отдельно late buyer, revoke/relink и качество когорт |
| R4 | R2 + W3 по действующему графу. Внутри этапа сначала live I01/I02, далее I03/I04 при квалифицированном ledger и покрытии истории |
| R5 | Отдельные FX и финансовые контракты, версии policy/rate и проверяемый ledger |
| R6/R7 | Отдельная квалификация модели и действий с утверждёнными пользовательскими сценариями |

Vision A1–A6 относятся к **отдельному провайдеру Radar**. Узлы A1/A2/A3 разработки аналитики сохраняют исходный смысл. G1 устанавливает интеграционное доказательство R2. Расширение Radar требует также R0, соответствующей ветви DAG и отдельного согласования scope. В оговорке C3 зафиксирован исторический результат +12.900%. На актуальном candidate такой результат проверяется заново в своей квалификации.

## 6. Приёмочная карта

[Реестр адаптации](RADAR-ADAPTATION-TRACE-R2.json) связывает 21 сигнал с этапами и 48 семейств BV с предлагаемыми проверками. R2-S40 остаётся зонтичной записью. Для первого SALES среза выбираются применимые проверки, включая BV-13 и общие identity, security, performance. MC-06 связывает Radar с квалифицированными источниками. MC-07 относится к отдельному допуску доставки/Advisor. MC-08 сохраняет базовые MVP journeys и опциональную ветвь BUS-RADAR-I2. Runtime verdict определяется артефактами на candidate SHA.

[Оговорка C3](../../reviews/RM-RADAR-C3-TEMPORARY-MERGE-DEFERRAL-R1.md) · [Execution DAG](../../spec/RM-INTELLIGENT-COMMERCE-EXECUTION-DAG-R1.json) · [PostgreSQL 17 Read Committed](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED)
