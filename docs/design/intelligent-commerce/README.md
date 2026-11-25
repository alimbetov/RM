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
