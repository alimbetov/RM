# D06 — Business Radar: будущий потребитель квалифицированных фактов
R2.0-draft · DESIGN ONLY / DEFERRED · Source SHA d9242e539a07ef1843c0e72aba9c651204bf42ae.
Owner: соответствующий domain provider; aggregator ownership ADR OPEN. [Radar Vision](../../spec/RM-RADAR-VISION-R1-INTELLIGENT-BUSINESS-CONTROL-CENTER.md) сохраняет 21 signal candidate и 48 BV families. Здесь уточняется их общий контракт, не разрешается реализация.

## 1. Signal contract
Signal identity: tenant + signalType + subject + occurrence identity; policyVersion и sourceVersion входят в snapshot/evaluation metadata, не обязательно создают новый сигнал. Повтор refresh того же риска не даёт вторую карточку.
Поля: safe subject ref, source refs/digest, method/policy version, evaluatedAt/validUntil, observed state, reasonCodes, allowed detail/action refs, dimensions/unit/currency, coverage/freshness, ranking components.
Условие присутствует → ACTIVE. Условие доказанно прекратилось → RESOLVED. Источник недоступен/устарел/неполон → UNKNOWN/STALE, не RESOLVED. Snooze/acknowledge пользователя, если позднее согласованы, не меняют факт owner риска.

## 2. Первый будущий вертикальный срез
| Сигнал | Источник | Допуск | Проверка перед действием |
|---|---|---|---|
| ORDER_PENDING_CONFIRMATION | Current PLACED + доказанное occurredAt | SALES grants, age policy | SALES current state/version |
| PICKUP_DELAY | READY_FOR_PICKUP transition, warehouse | Warehouse scope, ready time, policy | Current readiness, no pickup secret |
| RETURN_PENDING | Return stage and history | Return read policy | Owner current stage, no stock/refund mutation |
| CUSTOMER_ACTIVITY_DROP | Customer 360 qualified comparable cohorts | Current buyer link + sufficient history | Explain periods; decline не causal churn |
| LOW_AVAILABLE_STOCK | Live INVENTORY position | Scope/unit/threshold | Current owner stock, не stale L1 |
| LONG_LIVED_RESERVATION | Active reservation + eligible order | Age policy + dual domain visibility | Recheck released/fulfilled state |
| CONTRACT_EXPIRING | ContractExpiryPolicy | CRM rights/calendar | Current revision, renewal/cancel |
| TASK_OVERDUE | Existing TASK authoritative timing | TASK visibility | Reuse classification без второго расчёта |

Все значения age/threshold/priority — owner policy, OPEN. Первую реализацию предлагается ограничить одним Sales provider после R0.1D/data gates/явного resumption; текущий запрос не меняет DEFERRED.

## 3. Слияние, приоритет и полнота
Предлагаемый comparator: утверждённая severity → deadline/age rank в сравнимой нормализации → stable signal key. Денежные amount не сравниваются между валютами без qualified conversion. «Сумма заказов под риском» не становится ожидаемым убытком.
Top-K merge требует тот же comparator и раннее применение filters у всех providers. Недоступный provider отмечается в completeness; partial top-K не выдаётся как полный глобальный список. Bounded fan-out, timeout и per-provider limits утверждаются до реализации.
Cursor связывает tenant, actor visibility scope, filter, comparator/policy version и live/snapshot mode. Permission change инвалидирует старую видимость, даже если cursor был выдан ранее. Нельзя выводить total count из размера страницы.

## 4. Объяснение и действие
Explain сообщает, какие разрешённые факты вызвали сигнал, почему выбран приоритет, насколько свежи данные и что пользователь может проверить. Drilldown повторяет owner authorization. User action передаёт expected source version/idempotency и проходит owner service. Stale recommendation приводит к актуальному конфликту/refresh; не исполняется по старому snapshot.

Нет зависимости analytics → delivery → analytics. Radar-to-EVT — MC-07 с отдельным CR/policy; отсутствует в текущем EVT MVP. Predictive Radar/Advisor требуют dataset/labels, temporal split, baseline, calibration/abstention, drift/rollback и права; LLM/ML не создают бизнес-команду автоматически.
F01 AR, F02 margin, F05 cashflow BLOCKED_DEPENDENCY без финансового ledger. FX signals читают existing rate/policy lineage, не пересчитывают согласованные заказы и не обходят FX guardrails.

## 5. Приёмка будущего этапа
BV-01…48, MC-06/07 и Radar часть MC-08 остаются DEFERRED. Existing TASK Radar обязательные проверки продолжают действовать сейчас; они не откладываются под видом будущего D06.
Возобновление требует R0.1D, C3 push+PASS по прежним 200ms/10%, qualified source contract и отдельное решение владельца. G1 Intelligent Commerce не является автоматическим разрешением Radar, financial runtime, ML, внешней доставки или production.
