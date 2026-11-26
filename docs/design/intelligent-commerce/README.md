# RM Intelligent Commerce R2 — Design Charter
Дата: 2026-11-25. Статус: DESIGN ONLY / DRAFT / PRODUCTION HOLD.
Ветка: design/intelligent-commerce-r2. Исходный SHA: 271c5a9a57dd969a806bfd36f95ee9bde29559fc.

## 1. Назначение
Расширенное итеративное проектирование пяти контуров параллельно стабилизации ядра в base_dev. Не изменять код, DDL, зависимости, CI, тесты, branch protection или PR #9 из этой ветки. Не считать проектирование разрешением реализации. R1.1 master и действующие доменные контракты остаются нормативной базой; R2 — предложения до явного согласования. Не расширять фактический MVP без CR.

## 2. Исходные документы
- [Master R1.1](../../spec/RM-INTELLIGENT-COMMERCE-MASTER-R1.md)
- [Execution DAG](../../spec/RM-INTELLIGENT-COMMERCE-EXECUTION-DAG-R1.md) и [JSON](../../spec/RM-INTELLIGENT-COMMERCE-EXECUTION-DAG-R1.json)
- [IE-00A inventory](../../reviews/RM-INTELLIGENCE-IE-00A-SOURCE-CONTRACT-INVENTORY.md)
- [Intelligence & EVT](../../spec/RM-MVP-INTELLIGENCE-EVT-SCOPE-R1.md)
- [Radar Vision](../../spec/RM-RADAR-VISION-R1-INTELLIGENT-BUSINESS-CONTROL-CENTER.md)
- [Idea Bank](../../ideas/RM-IDEA-BANK-R1.md)
- [Idea catalog](../../ideas/README.md)
- [C3 deferral](../../reviews/RM-RADAR-C3-TEMPORARY-MERGE-DEFERRAL-R1.md)

Сохранить 22 группы источников, 8 исходных метрик, 88 семейств плановой приемки и DAG 32/48 как baseline; расширения вести с traceability и дедупликацией. Числа не означают выполненные тесты.

## 3. Контуры и результаты проектирования
| ID | Контур | Scope | Проектные результаты |
|---|---|---|---|
| INT-01 | RM Intelligence | MVP selected | каталог метрик, grain/period/currency/UOM, native SQL, quality/lineage, L1/L2, replay/rebuild, независимый oracle |
| INT-02 | Customer 360 | MVP selected | identity links, разрешенные CRM/SALES/TASK/contract факты, timeline, customer aggregates, visibility |
| INT-03 | Warehouse 360 + ABC/XYZ | MVP selected | movement/reservation/return facts, available stock parity, ABC/Pareto/XYZ methodology, coverage and unknown |
| EVT-01 | Event & Notification Builder | MVP selected | типизированные события, versioned rules/templates, audience/consent, preview, PostgreSQL outbox, lease/fencing, in-app/mock |
| RAD-EXT | Intelligent Business Radar | deferred; design only | каталог сигналов, relevance, explanation, stale/unknown, top-K, owner detail and reauthorization; prediction/advisor separately gated |

Существующий Task Radar R0.1 сохраняет обязательную квалификацию; C2 обязателен, C3 manual-only для core merge, не PASS; вернуть C3 push и доказать порог 10% перед возобновлением Radar/production. F2 и другие gates неизменны.

## 4. Предлагаемые дизайн-итерации (не заменяют execution DAG)
| ID | Содержание | Exit artifact |
|---|---|---|
| D00 | Baseline, inventory, нормативы, drift | source map + open decisions |
| D01 | Shared semantic and fact contracts | словарь, ownership, time, currency, quality, privacy, event identity |
| D02 | Intelligence engine | metric contracts, SQL examples, rollups, rebuild and oracle |
| D03 | Customer 360 | use cases, read contracts, identity/permission matrix |
| D04 | Warehouse 360 + ABC/XYZ | stock reconciliation, classifications, incomplete history |
| D05 | EVT builder | policy DSL constraints, recipient/template safety, durable state machine |
| D06 | Radar | signal contracts, dependency matrix, explanation, prioritization |
| D07 | Integration & reliability | scenario matrix, races, crash/replay, migration and workload plans |
| D08 | Design admission | resolved decisions, traceability, synchronized master/DAG/specs, owner review |

D03/D04/D05 may be designed in parallel after D01; D06 consumes qualified analytical contracts conceptually, not implementation admission. D07 covers all five. D00/D01 first. New numerical targets are proposals only.

## 5. Rules
Canonical owner services remain source of truth; derived projections cannot mutate business facts. Missing data is UNKNOWN/NOT_MEASURABLE, not zero. Tenant access and permission enforced per service operation; signed access token/JWKS and revocation race scenarios. No direct cross-domain DML, automatic business commands, external notification or ML runtime. PostgreSQL outbox first, Redis deferred. BigDecimal and versioned methodology; bounded workloads; no new infrastructure without measured need and approval.
Each design artifact records source SHA, requirement IDs, assumptions, owner, contracts, state transitions, negative scenarios, data quality, security, acceptance oracle and open decisions. Distinguish SPECIFIED from IMPLEMENTED / SERVICE TESTED / PG QUALIFIED / USER ACCEPTED / RELEASE ADMITTED. No PASS based on document alone.

## 6. Reconciliation and admission
Before implementation: verified PR #9 core merge and mandatory exact-SHA CI, reconcile latest main/base_dev with design baseline (no force), recheck code/schema/contract drift, execute IE-00B and IE-00 admission according to operative DAG, approve revised scope and design; no implementation or migration before required gates. Radar extension requires separate explicit resumption. Do not merge design branch automatically; review docs-only diff, synchronize canonical documents and select integration target after baseline reconciliation. Production HOLD remains.

## 7. Decision register (open)
- D-01 metric eligibility, dimensions and period completeness.
- D-02 customer identity/visibility and retention.
- D-03 ABC/XYZ methodology and sufficient history.
- D-04 event taxonomy, recipient consent, dispatch policy and limits.
- D-05 signal priority, freshness and explanation.
- D-06 performance budgets and workload distributions; existing Radar thresholds unchanged.
- D-07 final branch integration target after PR #9.
All are design questions, not approved decisions.

Next: review the detailed R2.0-draft package below, resolve slice decisions, then after Q1 execute IE-00B/A3. D00/D01 source/design drafts now exist; runtime admission remains OPEN.


## 8. Detailed R2.0-draft — current-project alignment, 2026-11-25
Source review pinned to base_dev `d9242e539a07ef1843c0e72aba9c651204bf42ae`; design parent `f5c86672e36dabb1445265c9276195d844e1a1f9`. User requested detailed elaboration and alignment with current project. All artifacts below are design proposals/source review, not implementation approval or tests PASS.

| Iteration | Document | Main result |
|---|---|---|
| D00 | [Project alignment](D00-PROJECT-ALIGNMENT-R2.md) | 14 source findings, branch drift and current gate snapshot |
| D01 | [Shared contracts](D01-SEMANTIC-SECURITY-EVENT-CONTRACTS-R2.md) | Grain/time/quality, IAM, event identity, replay and retention |
| D02 | [Metric passports and oracles](D02-METRIC-CATALOG-AND-ORACLES-R2.md) | 8 metrics, SQL sketches, numeric business fixture, L1/L2 |
| D03/D04 | [Customer and Warehouse 360](D03-D04-CUSTOMER-WAREHOUSE-R2.md) | Scoped read cases, stock reconciliation, ABC/XYZ methods |
| D05 | [Event Builder](D05-EVENT-BUILDER-DELIVERY-R2.md) | Policy revisions, occurrence binding, audience, lease/fencing/crash |
| D06 | [Future Radar](D06-RADAR-FUTURE-CONTRACTS-R2.md) | Signal identity, explanation, partial top-K and resumption boundaries |
| D07 | [Scenario qualification](D07-SCENARIOS-QUALIFICATION-R2.md) | 40 detailed scenario rows, 11 owner-test reuse references |
| D08 | [Decisions and admission](D08-DECISIONS-AND-ADMISSION-R2.md) | 15 concrete OPEN recommendations, staged handoff |
| Registry | [Traceability JSON](TRACEABILITY-R2.json) | 22 sources / 8 metrics / 88 original families; source blob SHAs |

The original 88 acceptance families remain 32 IE-Q + 48 BV + 8 MC. The 40 detailed rows overlap these families and are not 40 new passed tests. Existing RAD/CAL/F2R/BSR obligations remain independent normative dependencies. New R2 parameters are PROPOSED, see D08. Master R1.1 and execution DAG remain operative; synchronize canonical documents after design decisions are approved, not by silently replacing them with a draft.

Documentation validation: counts/IDs, links and source symbols, DAG 32/48 and acyclicity, hand arithmetic and docs-only diff. Runtime tests/migrations/load not executed for this design pass. No code, schema, CI, dependency or test changes.

Pre-save drift checked through base_dev `d1019e6acdb04a5c9a03da79d0f9232649723ead`: RET04 proof/test changes only, no production service/schema delta; source analysis remains pinned as above. Details and limitations: D00 §6.

## 9. R2.1 · Спецификация на уровне кода

Исходный код сверен на [base_dev f791026](https://github.com/alimbetov/RM/tree/f7910269df73564a6b1bf7d8f45bcc2766cc1b32). Приложения раскрывают D01–D08 до реализации и сохраняют действующий execution DAG.

| Документ | Что определено |
|---|---|
| [CODE-01 · Java и SQL](CODE-01-JAVA-AND-READ-CONTRACTS-R2.md) | Пакеты, Java 21 records и сервисные интерфейсы, SQL формы, authorization и scope для всех read-контуров |
| [CODE-02 · PostgreSQL и обработчики](CODE-02-POSTGRESQL-EVENTS-AND-WORKERS-R2.md) | Producer points, логические таблицы и ключи, L1/L2, rebuild, lifecycle доставки и SQL claim |
| [CODE-03 · Изменения и квалификация](CODE-03-IMPLEMENTATION-AND-QUALIFICATION-R2.md) | Последовательность diff, существующие fixtures и независимые оракулы |
| [CODE-TRACE-R2.json](CODE-TRACE-R2.json) | Привязка 40 сценарных записей к этапам, будущим методам и действующим семействам |

Статус R2.1 — проект для согласования. Предлагаемые имена классов, права, префиксы и параметры проходят review на соответствующем этапе. Кодовые фрагменты являются частью ТЗ. Первым аналитическим срезом после A3 остаётся B1 с прямыми запросами и независимой сверкой восьми метрик.

## 10. R2.2 · Адаптация Business Radar после R2

[Проект адаптации Radar](D06A-RADAR-ADAPTATION-AFTER-R2.md) связывает TASK R0.1, будущий SALES provider и остальные вертикали с квалифицированными данными RM. Первый предложенный сигнал — S01 ORDER_PENDING_CONFIRMATION. Порядок допуска опирается на существующий execution DAG и Vision A1–A6.

[Карта Radar](RADAR-ADAPTATION-TRACE-R2.json) связывает 21 сигнал и все 48 будущих семейств BV с этапами и предлагаемыми проверками. MC-06…08 сохраняют исходные условия. Статус DEFERRED_ANALYTICS_FIRST.

## 11. RM-UX-R1 — Guided Business Management & Progressive Intelligence

Дата: 2026-11-26. Статус: **OWNER-AGREED PRODUCT TARGET / DESIGN ONLY / IMPLEMENTATION NOT ADMITTED**.
Пользователь согласовал целевую концепцию: сложное внутри, простое снаружи; RM направляет и помогает управлять бизнесом даже человеку без профессиональной аналитической, финансовой или технической подготовки. Это сквозной продуктовый ориентир будущих React/Flutter интерфейсов, а не новый runtime-модуль, не расширение текущего MVP и не изменение действующего execution DAG.

### 11.1. Ценность для пользователя

RM должна помогать ответить: что происходит, что требует внимания, почему это важно, что можно сделать сейчас и какой результат получен. Пользователь не обязан знать ABC/XYZ, SQL, ML, методики агрегации или складской логистики. Профессиональные инструменты при этом остаются доступны по запросу и в пределах полномочий. Простота не означает скрывать риски, неопределенность или лишать пользователя контроля.

| ID | Принцип целевого UX | Требование |
|---|---|---|
| UX-T01 | Simplicity First | Понятные термины, ясное основное действие, безопасные defaults; не переносить внутреннюю сложность на пользователя. |
| UX-T02 | Guidance First | Ролевой главный экран показывает наиболее важные ситуации и следующий шаг; ограничивать информационный шум. |
| UX-T03 | Action First | Полезный вывод связывать с разрешенным действием или объяснять, почему действие недоступно; переход к owner workflow, без обхода доменного сервиса. |
| UX-T04 | Explainability First | Объяснять причину, источник, период, актуальность, полноту, существенные ограничения и предположения. |
| UX-T05 | Business Effect First | Измерять полезность для пользователя и экономический эффект, различать прогноз, оценку и наблюдаемый результат. |
| UX-T06 | Human Control First | Существенные действия требуют явного подтверждения; черновик, проверка последствий, возможность отклонить; запрет скрытого исполнения. |
| UX-T07 | Progressive Intelligence | Начинать с полезных детерминированных правил; применять ML только при наличии квалифицированных данных и допуска. |

### 11.2. Progressive Disclosure и guided journeys

Три уровня раскрытия одной и той же информации, не три независимых вычислительных контура:
1. Базовый: что случилось, насколько срочно, что предлагается сделать.
2. Управленческий: почему, какие клиенты/товары/склады затронуты, сравнения, альтернативы и возможные последствия.
3. Аналитический: методика, измерения, период, валюта/UOM, lineage, coverage, quality, версия и воспроизводимые основания расчета.

Главный экран: «Что требует моего внимания сегодня?»; карточка сигнала содержит понятный заголовок, основание, приоритет, свежесть/неопределенность, разрешенный следующий шаг и доступ к деталям. Целевые сценарии: ожидающий подтверждения заказ (S01), риск дефицита, неликвид, снижение активности покупателей. Последующие прогнозные и финансовые сценарии допустимы только после квалификации соответствующих источников и методов. Базовые операции (заказ, остатки, клиенты) должны оставаться доступны напрямую, без обязательного прохождения Radar.

Сохранять обязательные контракты tenant isolation, RBAC, актуальную повторную авторизацию owner action, PII minimization, audit и idempotency. Не показывать недоступные объекты, их счетчики или детали даже в рекомендациях. UNKNOWN/STALE/PARTIAL не трактовать как ноль, отсутствие риска или уверенный прогноз. Не выдавать стоимость исполненного заказа за признанную выручку; маржа требует квалифицированного COGS. Не вводить автоматические команды, внешний delivery или ML runtime этим решением.

### 11.3. Предлагаемая измеримая UX-квалификация (PROPOSED)

| Сценарий | Начальная цель для проверки |
|---|---|
| Первое освоение базовых операций | до 15 минут |
| Найти наиболее важную проблему | до 30 секунд |
| Перейти от рекомендации к черновику/разрешенному действию | до 3 осмысленных взаимодействий; не исключать обязательные проверки и подтверждение |
| Создать типовой заказ на подготовленных справочниках | до 1 минуты |
| Выполнить базовый сценарий без предварительного обучения | >=90% участников |
| Верно объяснить причину и смысл рекомендации | >=90% участников |
| Завершить сценарий без посторонней помощи после стандартного onboarding | >=95% участников |

Пороги — первоначальные гипотезы, не утвержденные release gates и не достигнутые результаты. Перед квалификацией определить роли, репрезентативную выборку (включая начинающих предпринимателей), устройства, языки, сценарии, начальное состояние, метод измерения времени, доли успеха, ошибки, доступность и критерии безопасности. Не оптимизировать число кликов за счет контроля и понимания. Проверить прототипы и наблюдать реальных пользователей; затем оценить пилотную полезность, время до первого полезного действия, релевантность сигналов и результат относительно исходного уровня. Готовность платить и снижение потерь остаются коммерческими гипотезами до измерения.

### 11.4. Границы и порядок

Использовать как вход для будущего UX design review всех интерфейсов, в том числе Customer 360, Warehouse 360, Event Builder и Radar. Связь: D03/D04, D05, D06/D06A, D07, D08. Первый проверяемый guided slice Radar — S01 после выполнения действующих условий допуска. Не создавать новый самостоятельный движок и не менять Java/DDL/CI/DAG, текущую B4 квалификацию, MVP scope, production HOLD или отдельный Radar admission. Детальный UI specification, прототипы и согласование UX gates — отдельная будущая работа. Коммерческий ориентир: дать полезный результат до требования полной миграции, если безопасный импорт/интеграция будут отдельно согласованы и реализованы.

## Обязательное дополнение MIC-R1 (2026-11-26)
[RM Mathematical Integrity Contract R1](../../architecture/RM-MATHEMATICAL-INTEGRITY-CONTRACT-R1.md). MIC-R1 обязателен для всех пяти контуров; design-only сохраняется.

## Mandatory analytical integrity extension
[MIC-R2](../../architecture/RM-MATHEMATICAL-INTEGRITY-CONTRACT-R2.md) supplements MIC-R1 for derived metrics, continuous history, metric activity, decision admission and point-in-time feature evidence. Shadow results are not production permits. Implementation and qualification status must be verified against base_dev.


## D06B — интеграция ночных микроотчётов и оперативных триггеров (2026-11-26)
[Утверждённое продуктовое направление](D06B-NIGHT-REPORTS-OPERATIONAL-TRIGGERS-R2.md): использовать существующие Intelligence, FX/Pricing, EVT, Files и Radar; никаких B6/B7 или новых доменов. Design-only, B5 roadmap и gates неизменны.


## D06C — Template-First Analytical Reporting & Intraday Refresh
[Техническое задание](D06C-TEMPLATE-FIRST-REPORTING-INTRADAY-R2.md) уточняет D06B: готовые XLSX-шаблоны, Spring Batch 10 jobs, 3h накопительный L0, 24-месячная qualified history, Excel/PDF/Radar. Design-only; B4 freeze и gates сохранены.


## D06D — Unified Analytical Dataset & Pattern Tickets (2026-11-26)
[Сквозной архитектурный контракт](D06D-UNIFIED-ANALYTICAL-PATTERN-TICKETS-R2.md) фиксирует согласованное направление B5.5/D06B/D06C/Radar: 24M history / 12M display, допустимые многомерные scope, относительные паттерны, Pattern Ticket → Information Ticket, единые labels и template bindings, night + 3h intraday. Не меняет текущий roadmap, B4 FREEZE, MIC, existing gates, descriptive-only intraday до квалификации сопоставимого cutoff и отдельный Radar admission. DESIGN ONLY / PRODUCTION HOLD.


## D06E — Minimal Analytical Dataset & Pattern Qualification (2026-11-26)
[Техническое приложение D06E](D06E-ANALYTICAL-DATASET-PATTERN-QUALIFICATION-R2.md) детализирует T01–T17: четыре логических представления без новых физических банков, historical coverage first, bounded authorized 24M reads, sparse scopes, typed pattern registry, минимальные tickets, existing template/worker/Radar contracts и последовательность qualification. DESIGN ONLY; B4 FREEZE, MIC, execution DAG, separate Radar admission и Production HOLD сохраняются.
