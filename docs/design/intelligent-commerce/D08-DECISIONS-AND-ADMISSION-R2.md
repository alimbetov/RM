# D08 — Решения, порядок исполнения и готовность к реализации
R2.0-draft · DESIGN REVIEW OPEN · Source SHA d9242e539a07ef1843c0e72aba9c651204bf42ae.
Владелец продукта: Ruslan Alimbetov. Технические решения согласуются с владельцами доменов/архитектуры; запись рекомендации не является их одобрением.

## 1. Что уже сохраняется из действующего проекта
Утверждённый выбранный scope: INT-01/02/03 и EVT-01, отдельный будущий Radar. Canonical owner services, BigDecimal, tenant isolation, current-link security, PostgreSQL outbox first, mock external, no automatic business command. Production HOLD. Эти ограничения не предлагаются заново и не требуют повторного подтверждения для документационной работы.

## 2. Конкретные решения к рассмотрению
| ID / связь Charter | Рекомендация R2 | Альтернатива / последствие | Когда требуется решение |
|---|---|---|---|
| DEC-R2-01 / D-01 | M-I01/02 по recorded_at, M-S по status occurred_at, явно разные timeBasis | Event-time stock history отдельной методикой и reconciliation | A2 перед B1 |
| DEC-R2-02 / D-01 | Версионированная tenant IANA zone, запрет system default, новая версия при смене | Ограничить первый tenant явно выбранной zone, не объявлять universal policy | A2/B1 |
| DEC-R2-03 / D-01 | 8 базовых метрик D02, monetary refunds/paid/margin заблокированы | Расширение после qualified finance source и CR | A2/A3 |
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
| D02 | 8 metric passports, SQL sketches, hand oracle, L1/L2 contract | Source eligibility и независимые runtime tests |
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
