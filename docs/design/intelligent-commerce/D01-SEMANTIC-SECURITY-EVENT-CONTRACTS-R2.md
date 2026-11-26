# D01 — Общие семантические, security и event-контракты
R2.0-draft · 2026-11-25 · SPECIFIED / PROPOSED. Source SHA: d9242e539a07ef1843c0e72aba9c651204bf42ae. Владельцы решений: domain owner + IAM/architecture; утверждение OPEN.
Основание: [D00](D00-PROJECT-ALIGNMENT-R2.md), Master R1.1, IE-00A, [Event Transport](../../spec/RM-EVENT-TRANSPORT-POSTGRES-OUTBOX-REDIS-READINESS-R1.md). Имена record/полей ниже — логическая модель, не утверждённая Java API/DDL.

## 1. Ownership
| Данные | Канонический владелец | Разрешённое использование |
|---|---|---|
| Заказ, строка, согласованная цена, статус, выдача, возврат | SALES / существующие AutoOrder entrypoints | Read contract и факт подтверждённого перехода |
| Остаток, резерв, движение, карантин | INVENTORY | Оперативный read и воспроизводимый исторический расчёт |
| CRM-клиент, связь покупателя, взаимодействие, договор | CRM + IAM для аккаунта | Current-link attribution, разрешённые секции |
| Timing задачи | TASK | Авторитетный timing projection и доступность задачи |
| Методика и производные проекции | INTELLIGENCE registered (int); runtime grant admission pending | Изолированное хранение производных данных, rebuild |
| Правило, intent, delivery receipt | EVT registered (evt); runtime grant admission pending | Уведомление без business command |

Derived storage не пишет напрямую в таблицы других владельцев. Любое будущее действие проходит их сервис и актуальную проверку состояния. PostgreSQL — authority для durable events/receipts. Redis, дополнительные сервисы, FIN runtime и partitioning не вводятся данным ТЗ.

## 2. Общий запрос и результат
Предлагаемый MetricQuery: metricCode + methodVersion, tenant, subjectScope, period либо asOf, filters, calendarPolicyVersion, currency/unit context; actor передаётся отдельно как VerifiedActor. Tenant не выводится из произвольного userId/claim клиента.

MetricResult:
- value: decimal либо null; money обязательно с currency, quantity с SKU/unit context.
- periodStart/endExclusive, periodCode, zoneId, calendarVersion; для gauge — snapshotEvaluatedAt.
- methodVersion/methodDigest, projectionGeneration либо DIRECT, source refs и lineage digest.
- quality: сохраняются семантики READY/PARTIAL/MISSING/STALE/NOT_MEASURABLE из master.
- coverage: известный интервал, полнота исходного набора и ограничения; observedAt, source/projection progress semantics.
- safe reasonCodes; drilldownToken с tenant/scope/method/period/generation.
- comparison только при совместимых cohort/calendar/currency/unit/method.

Внутренне полнота и свежесть независимы: PARTIAL может одновременно быть stale. Предлагается отдельный freshness flag, а итоговый quality согласуется A2. UNKNOWN — отсутствие обоснованного ответа/классификации; не шестой автоматически добавленный enum. NOT_MEASURABLE — нет требуемых фактов/методики; MISSING — отсутствует требуемое покрытие. Нельзя заменять любой из них value=0.

Доступ запрещён → отказ до построения результата, без скрытых counts, reason о чужом объекте, source refs или cursor. Доступ разрешён, но доказательств мало → безопасный quality result. Подробные причины ограничения идентичности доступны только при соответствующем праве; внешний безопасный reason не раскрывает статус чужого аккаунта.

## 3. Grain, деньги и единицы
SEM-01: grain фиксируется до JOIN; порядок или событие не умножаются на число строк/контактов.
SEM-02: BigDecimal/NUMERIC; агрегат не обязан помещаться в precision одной строки источника. Промежуточные вычисления без преждевременного округления; overflow/unsupported precision явный отказ.
SEM-03: currency входит в ключ агрегата и знаменатель ratio; FX conversion не подразумевается.
SEM-04: quantity по tenant + SKU + unit context; product/service и incompatible units не складываются. Имя unit_code из FX binding не является общей исторической UOM.
SEM-05: суммы/количества аддитивны только по непересекающимся совместимым наборам. Snapshot/gauge, средние, distinct и CV не складываются.
SEM-06: current price/title/warehouse не заменяют исторический snapshot. Неатрибутированные строки не исчезают без quality/coverage.
SEM-07: возврат физического товара сам по себе не создаёт refund, paid revenue, debt или COGS.

## 4. Время и согласованность
D/W/DEC/M/Q/H/Y: календарные границы в утверждённой tenant IANA zone → Instant [start,end). W — ISO week/year; DEC — 1–10,11–20,21–конец месяца. Месяц не собирается из целых недель, пересекающих его границу. DST допускает сутки 23/25 часов; одинаковая длительность физических секунд не предполагается для календарного дня.

Для каждого источника фиксируются business occurredAt, recordedAt если имеется и evaluatedAt. M-S — occurred_at события; M-I01/02 предлагаются по recorded_at согласно текущему history read. recorded_at не равен commit time. Историческое событие, которое закоммитилось позже, обязано быть найдено feed/reconciliation, даже если его timestamp меньше уже наблюдавшегося.

Один asOf в DTO не создаёт общего снимка для нескольких SQL statements. Для строгой сверки одного экрана/оракула использовать один statement либо отдельно квалифицированную bounded snapshot transaction; для независимых секций возвращать их версии/свежесть. Keyset не гарантирует snapshot между запросами; выбранная generation закрепляет исторический rebuild, но не делает live owner данные неизменяемыми.

Смена timezone/calendar/method создаёт новую версию и rebuild; сравнение разных версий возможно только после явной нормализации. Для XYZ определить равные календарные buckets и сезонность; DST-нюансы не скрывать.

## 5. Identity и разрешения
| Операция | Минимальная проверка |
|---|---|
| Метрики SALES | VerifiedActor, active tenant/member/account, SALES_ORDER_READ |
| Customer profile | CRM_READ; PII отдельно CRM_PII_READ |
| Customer sales | CRM_READ + SALES_ORDER_READ + current CONFIRMED link + ACTIVE customer/buyer |
| Customer tasks | CRM_READ + TASK_READ + owner creator/assignee visibility |
| Warehouse | INVENTORY_STOCK_READ; labels CATALOG_READ/ORGANIZATION_LOCATION_READ по выдаваемым полям |
| Assigned warehouse view | Дополнительная approved read-scope policy; текущая command scope не объявляется новым read grant |
| EVT управление | Отдельный предложенный management grant + права source domain; название/назначение OPEN |
| EVT delivery | Trusted service identity, active recipient/tenant, domain eligibility и warehouse scope если применимо |
| Drilldown | Повторная owner authorization и текущая версия |

VerifiedActor разрешён только из существующего IAM resolver после криптографической проверки access token: signature, trusted issuer, RM audience, expiration и назначение API token. Входные actorId и подмена tenant claims не создают полномочий. Worker не подделывает VerifiedActor пользователя и не сохраняет bearer token в outbox.

Повторная проверка expiry перед возвратом соответствует TaskTimingQueryService. Для revoke/suspend оба порядка commit: уже допущенная fenced операция может завершиться перед отзывом; отзыв первым запрещает следующую выдачу. Не обещать ретроактивное удаление уже прочитанного. Новые блокировки обязаны совместно соблюдать IAM/domain lock order; read semantics с FOR SHARE не означают JDBC readOnly=true.

Current-link Customer 360 не заявляет историческую принадлежность на дату заказа. Проекция по buyer key остаётся защищёнными псевдонимизированными данными; такой ключ не делает её анонимной. Link revoke/dispute/delete/suspend учитываются на каждом read и drilldown. Нельзя один раз проверить link при materialization и затем читать customer total без текущей проверки.

## 6. Канонический event envelope
Предлагаемые поля: tenantId, owner, eventKind, sourceEventId, subjectId, sourceRevision (nullable только с описанной owner семантикой), occurredAt, recordedAt если доказано, schemaVersion, safeFactRef, evidenceDigest.
Для SALES sourceEventId — реальный status_event.id; не checkout idempotency key и не request fingerprint. Отсутствующая sourceRevision не заменяется номером доставки или временем.
Для deadline — отдельный occurrence key: tenant+subject+ownerRevision+due/window+policy identity/calendar; не вымышленный ID исходного status_event.

Одна canonical occurrence может потребляться аналитикой и EVT независимо. Receipt аналитики: consumer + projection/method generation + tenant + canonical event identity; receipt и изменение результата в одной транзакции. EVT имеет собственные intent/receipt; его сбой не откатывает уже подтверждённую аналитику.

Новый producer, если требуется, сохраняет event/outbox атомарно в исходной owner transaction. After-commit best effort не даёт гарантии. Повторная доставка допустима, повторный эффект запрещён. Нельзя строить discovery только WHERE id>lastId, max(timestamp) или max(sequence), потому что commit order отличается.

## 7. Replay, backfill и retention
Предложение для B2: durable pending feed + per-consumer receipts. Backfill обрабатывает зафиксированный bounded source set/snapshot, live feed покрывает concurrent commits; overlap устраняется canonical identity. До выбора механизма полнота incremental ingestion не заявляется. Полная reconciliation и контроль late commits остаются обязательными.

Rebuild: новая generation → bounded backfill/live catch-up → independent parity → атомарный switch активной generation. Reader не смешивает поколения; cursor закрепляет generation либо отклоняется. Старое поколение сохраняется на утверждённое окно rollback; возврат допустим только при доказанной актуальности либо с STALE, не скрытой потерей late facts.

PII не включать в generic envelope, logs или шаблоны. Retention задаётся отдельно для фактов, derived rows, receipts, schedules, audit. Удаление dedup receipts до конца replay horizon создаёт повторы: сроки должны быть совместно доказаны; физическое удаление или бессрочное хранение здесь не утверждены.

## Final technical reconciliation — 2026-11-26
A2 source approval [15 decisions](https://github.com/alimbetov/RM/blob/a6bf99107aa64f4eb255b4997ef9417748b05607/docs/reviews/RM-IE-00B-R2-A2-CONTRACTS.json). Current confirmed buyer link, independent CRM_READ and SALES_ORDER_READ, active warehouse assignment; manager exception DEFERRED. No worker may mint VerifiedActor. Report/EVT grants and service identity separately admitted. Domain registration not runtime authorization.
