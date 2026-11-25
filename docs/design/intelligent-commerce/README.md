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

Next: D00/D01 — exact source-to-fact-to-metric-to-view-to-signal traceability and shared semantic contract draft.
