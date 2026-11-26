# D08 — Решения, порядок исполнения и готовность к реализации
PRODUCT CONCEPT FROZEN · GLOBAL DESIGN/IMPLEMENTATION ADMISSION OPEN · PRODUCTION HOLD. Original R2.0 review source d9242e539a07ef1843c0e72aba9c651204bf42ae; current aggregate review source a6bf99107aa64f4eb255b4997ef9417748b05607.
Владелец продукта: Ruslan Alimbetov. Технические решения согласуются с владельцами доменов/архитектуры; запись рекомендации не является их одобрением.

## 1. Что уже сохраняется из действующего проекта
Утверждённый выбранный scope: INT-01/02/03 и EVT-01, отдельный будущий Radar. Canonical owner services, BigDecimal, tenant isolation, current-link security, PostgreSQL outbox first, mock external, no automatic business command. Production HOLD. Эти ограничения не предлагаются заново и не требуют повторного подтверждения для документационной работы.

## 2. Конкретные решения к рассмотрению
| ID / связь Charter | Рекомендация R2 | Альтернатива / последствие | Когда требуется решение |
|---|---|---|---|
| DEC-R2-01 / D-01 | M-I01/02 по recorded_at, M-S по status occurred_at, явно разные timeBasis | Event-time stock history отдельной методикой и reconciliation | A2 перед B1 |
| DEC-R2-02 / D-01 | Версионированная tenant IANA zone, запрет system default, новая версия при смене | Ограничить первый tenant явно выбранной zone, не объявлять universal policy | A2/B1 |
| DEC-R2-03 / D-01 | 8 исходных owner-паспортов D02; актуальный каталог D06E.4, 12 pilot codes не предел; monetary refunds/paid/margin заблокированы | Расширение после qualified finance source и CR | A2/A3 |
| DEC-R2-04 / D-02 | Current-link attribution; current fence перед aggregate/detail | Historical identity model требует отдельного consent/privacy contract | A2/C1 |
| DEC-R2-05 / D-02,D-06 | Assigned warehouse read и explicit manager scope, отдельная policy | Tenant-wide read нельзя молча раздать всем warehouse workers | A2/W1 |
| DEC-R2-06 / D-03 | Первый ABC tenant+currency по booked line snapshots; warehouse dimension пока blocked | Fulfilled-warehouse ABC требует отдельного basis/attribution | A2/W3 |
| DEC-R2-07 / D-03 | ABC candidate 80/95 по cumulativeBefore, stable-key tie break | Group ties меняет boundary; нужны иные expected results | W3 до qualification |
| DEC-R2-08 / D-03 | XYZ population CV по fulfilled quantity, completed weekly buckets; candidate 12,10%,25% | Booked demand, иной horizon/seasonality дают другую методику | W3 до implementation |
| DEC-R2-09 / D-04 | PLACED first, in-app, closed DSL, occurrence binding фиксирует rule revision | Historical redelivery при изменении rule — отдельный scope | A2/E1/E2 |
| DEC-R2-10 / D-04 | Deadline invalidation + expired-window no-send, versioned schedule | Catch-up dispatch требует окна/лимита и отдельного approval | E3 |
| DEC-R2-11 / D-04,D-06 | Узкий EVT owner/prefix/grants/service identity ADR; переиспользовать IAM fences | Общий admin grant или повторный event bus неприемлемы | A2/B2/E1 |
| DEC-R2-12 / D-06 | Сначала профили нагрузки, затем budgets; bounded queries/workers | Новые SLO без измерений не заявлять | A2/X1 |
| DEC-R2-13 / D-02,D-04 | Retention payload/derived/dedup согласовать с replay horizon | Удаление receipts раньше horizon нарушает идемпотентность | A2/E2/B4 |
| DEC-R2-14 / D-05 | Future Radar один Sales provider, объяснимый rank, abstention при unknown | Multi-domain/ML отдельно после источников и resumption | R-02 |
| DEC-R2-15 / D-07 | После Q1 определить integration target и сверить drift, сохранить master R1.1 normative до review | Нельзя автоматически merge design по устаревшему baseline | D08/Q1 |

Все DEC-R2-xx OPEN/PROPOSED. Обязательные логические инварианты (no double debit, permission enforcement, no false zero) не являются факультативными бизнес-настройками. Значения ABC/XYZ и новые budgets — именно варианты для согласования.

## 3. Выход проектных итераций
| Итерация | Артефакт текущего прохода | Что ещё открыто |
|---|---|---|
| D00 | Source delta, 14 alignment findings, pinned manifest | Полный producer/schema runtime proof после Q1 |
| D01 | Семантика, identity, time, event/receipt, retention boundaries | Ownership/IAM/calendar/feed ADR approval |
| D02 | 8 original owner metric passports (D06E.4 current inventory), SQL sketches, hand oracle, L1/L2 contract | Source eligibility и независимые runtime tests |
| D03/D04 | Customer/Warehouse use cases, ABC/XYZ formulas/limits | Read scope, historical attribution, methodologies |
| D05 | Rules, activation binding, delivery lifecycle, recovery | Конкретная схема, worker lock DAG и numeric quotas |
| D06 | Future signal contract и зависимости | Resumption и slice-specific ADR; DEFERRED |
| D07 | 40 scenario rows + 88-family crosswalk + existing test reuse | Concrete new methods/CI evidence по admission |
| D08 | Реестр рекомендаций и gates | Owner review; это не DESIGN APPROVED |

## 4. Execution handoff без замены DAG
P0 → Q0 exact-SHA required gates → Q1 verified core merge/reconciliation.
После Q1 A1: actual schema/source proof, A2: semantic/IAM/event decisions; A3: IE-00 admission.
После A3:
- B1 Metric Registry/PeriodResolver/direct oracle; B2 canonical source contract; E1 rule/preview.
- B3 L1 после B1/B2; B4 L2/rebuild после B3.
- C1/W1 после B1 и A3; C2/W2 после B4; W3 после W2/B4.
- E2 после B2/E1; E3 после E2 и deadline decisions.
- X1 после C2/W3/E3/B4; G1 после X1/P0; далее G2 → G3 → G4.
Конкретные зависимости всегда [DAG JSON](../../spec/RM-INTELLIGENT-COMMERCE-EXECUTION-DAG-R1.json), 32 nodes/48 edges; текст не ослабляет внешние gates.
Разрешено логическое пересечение независимых работ, не обещание параллельных агентов/новых веток.

## 5. Design admission checklist
D08 можно закрывать только когда:
1. Каждый обязательный fact/metric имеет owner, source/field, current permission, time/unit/currency, coverage и independent oracle.
2. DEC-R2 закрыты для допускаемого slice, remaining future items explicitly deferred.
3. Новые и прежние IDs прослеживаются без duplicate claims; каждый required family имеет полный case inventory.
4. Runtime proof не смешан с source inspection; миграции, grants и event ports проходят owner review.
5. Master/дочерние specs/DAG синхронизируются после принятия R2; конфликтные копии не сохраняются.
6. После Q1 заново проверен actual source drift и выбран integration target; docs-only diff reviewed.
7. Явно записаны baseline SHA, accepted design revision, ограничения и следующий gate.

Текущий результат — подробный пакет для такого review. Он не утверждает IE-00B/A3/G1, merge PR #9 или production. Дизайн-ветка содержит только документы; код, DDL, CI и существующие тесты не меняются.

## Обязательное дополнение MIC-R1 (2026-11-26)
[RM Mathematical Integrity Contract R1](../../architecture/RM-MATHEMATICAL-INTEGRITY-CONTRACT-R1.md). MIC-R1 утверждён как обязательный архитектурный контракт; methodology и implementation admission остаются отдельными.


## Решение владельца 2026-11-26 — D06B
[Ночные микроотчёты и оперативные триггеры](D06B-NIGHT-REPORTS-OPERATIONAL-TRIGGERS-R2.md): направление согласовано; архитектура не расширяется, реализация только доказанных gaps. Не разрешает production, автоматическую корректировку цен, Radar resumption или новый MVP scope. Методики и интеграционные решения остаются отдельными.


## D06C — уточнение reporting 2026-11-26
[Template First & Intraday Refresh](D06C-TEMPLATE-FIRST-REPORTING-INTRADAY-R2.md): владелец согласовал подход, 3h срезы, очередь 10 и заранее подготовленные шаблоны. Технологические версии, конкретные методы и runtime/owner admission остаются открытыми; ACTIVE L3 не мутировать, OPEN_PERIOD не повышать до READY. Не менять DAG, B5 roadmap или production HOLD.


## D06E.3 — Analytical Presentation & Progressive Business Insights (2026-11-26)
[Согласованное владельцем дополнение](D06E.3-ANALYTICAL-PRESENTATION-PROGRESSIVE-INSIGHTS-R2.md) фиксирует восемь переиспользуемых композиций: Morning Brief, Business Pulse, Heatmap, Top Winners & Losers, Drill-Down, Contribution Analysis, Historical Range Selector, What Changed. Один qualified payload, Metric Code + Hint, без новых движков/доменов/шаблонов на каждый вариант. D06E.2 сохранён; qualification trace сформирован; D08 global admission и runtime qualification остаются OPEN. DESIGN ONLY / B4 FREEZE / PRODUCTION HOLD.


## D06E.2 — Minimal Code Contracts & PRODUCT CONCEPT DESIGN FREEZE (2026-11-26)
[Итоговый контракт CC-01–12](D06E.2-MINIMAL-CODE-CONTRACTS-QUALIFICATION-R2.md) и [qualification trace](D06E-QUALIFICATION-TRACE-R2.json) фиксируют согласованную продуктовую концепцию D06A–D06E.3, минимальные code contracts, 12 pilot metric passports (extended catalog D06E.4), synthetic profiles и handoff S0–S6. PRODUCT CONCEPT FROZEN; further functional expansion requires explicit change control. D08 global admission checklist/DEC decisions, source completeness, semantic/city/owner/runtime qualification remain OPEN by slice; no claim of full DESIGN APPROVED or implementation/production admission. Existing normative DAG, B4 FREEZE, MIC and Radar resumption unchanged. Next technical priority B5.5.


## D06E.4 — Aggregate Capability Inventory (2026-11-26)
[Source audit and metric registration](D06E.4-AGGREGATE-CAPABILITY-INVENTORY-R2.md) / [machine-readable inventory](D06E.4-AGGREGATE-CAPABILITY-INVENTORY-R2.json): 12 B4.8 pilot codes, 8 owner registry codes (7 materialized L1), 11 existing numerical operator codes, 22 proposed aggregate-derived candidates. 20 literal base codes are not 20 certified unique metrics. 12 is a pilot, not ceiling; no new runtime scope or automatic admission. Historical completeness, city, semantic crosswalk and bulk qualification remain OPEN. Product concept/B4 FREEZE, DAG, MIC, separate Radar admission and Production HOLD unchanged.

## Current metric baseline — D06E.4 reconciliation (2026-11-26)
[Audited inventory](D06E.4-AGGREGATE-CAPABILITY-INVENTORY-R2.md) / [JSON](D06E.4-AGGREGATE-CAPABILITY-INVENTORY-R2.json) is the current aggregate capability catalog: 12 B4.8 pilot codes, 8 owner registry codes (7 L1 materialized, 1 live), 11 numerical operator codes, 22 unadmitted candidates; 53 catalog entries, not 53 unique business metrics. Pilot is not a ceiling. Additional registered capabilities are admitted individually after semantic, source, applicability, authorization and independent oracle qualification. No new architecture or automatic runtime admission. Reuse existing L3DerivedSeriesService.readPeriods set-based extraction within a qualified authorized bulk contract; existing 12-anchor/3-metric factor path remains unchanged. Product concept and B4 FROZEN; D08 global admission OPEN; B5.5 NEXT; Radar and production HOLD.

## D06E.5 — Dynamic Analytical Contracts & Visualization Matrix (2026-11-26)
[Reverse report-to-metric design](D06E.5-DYNAMIC-ANALYTICAL-CONTRACTS-VISUALIZATION-R2.md) defines 12 reusable logical data contracts DC-01–12 for the existing 12 reports and 8 UI compositions, stable typed payload bindings, chart interpretation safeguards and qualification mapping. D06E.4 inventory unchanged; no new metric admission, engine, DAG stage or test family. Product concept/B4 FREEZE, D08 OPEN, B5.5 NEXT, Radar/production HOLD.

## D06E.5.1 — Adaptive Ranked Distribution (2026-11-26)
[Owner-agreed grouping contract](D06E.5.1-ADAPTIVE-RANKED-DISTRIBUTION-R2.md): direct display ≤5, otherwise up to seven bands with 15% step; COUNT_PERCENTILE default / CUMULATIVE_VALUE_SHARE alternative, FIXED/DYNAMIC comparison, deterministic ranking, exact additive reconciliation and authorized drill-down. Reuse DC-05/06/08/09 and existing payload/templates; no new engine or metric admission. Product concept/B4 FREEZE, B5.5 NEXT, D08 OPEN, Radar/production HOLD.
