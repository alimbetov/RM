# RM-MVP-INTELLIGENCE-EVT-SCOPE-R1 — Intelligent Commerce MVP
## R1.0 — Analytics First + Event Foundation

**Дата:** 2026-11-25. **Source inventory SHA:** e3b2b999991711f0ae3c582488f419210291e9ce. **Repository:** alimbetov/RM, base_dev only.  
**Decision:** CR-MVP-INTELLIGENCE-EVT-01 — владелец выбрал TOP-1…4 из рейтинга банка идей для MVP.  
**Status:** OWNER SELECTED / SCOPED MVP DEVELOPMENT / STAGED ADMISSION / PRODUCTION HOLD.  
Это письменное расширение MVP, а не доказательство реализации или release. Допускается разработка только описанных срезов после соответствующих входных gates. Остальные идеи остаются PARKED. Предложенные ниже числовые параметры и точные продуктовые метрики требуют утверждения в slice review, не являются production SLA.

> **Общее ТЗ:** [RM-INTELLIGENT-COMMERCE-MASTER-R1](RM-INTELLIGENT-COMMERCE-MASTER-R1.md) — полный объединённый план Intelligence/EVT + Radar Vision; текущий CR и staged MVP scope не изменены. Radar expansion сохраняет Analytics First и отдельный resumption gate.

## 1. Scope, priority and non-goals

Сохраняются RM Business и RM QR Commerce; BUS-MVP-J1 и QR-MVP-J1 неизменны, не добавляется третий order/stock engine. Новый дополнительный пользовательский acceptance slice BUS-MVP-I1 (аналитика и события) — часть RM Business. Базовые J1 должны продолжать квалифицироваться независимо, но расширенный MVP не объявляется полностью принятым без I1. Если требуется выпустить базовый J1 раньше, отдельное owner decision о phased release, без ложного расширенного MVP PASS. Не задерживать завершение текущих P0/security fixes из-за будущих модулей. Уже квалифицируемый frozen candidate не менять незаметно; зафиксировать новый candidate после staged work.

| Workstream | Bank IDs | MVP result | Excluded |
|---|---|---|---|
| INT-01 RM Intelligence | IDEA-17, enabling IDEA-02 | 6–8 core metrics, source admission, periods D/W/DEC/M/Q/H/Y where qualified, bounded Native SQL, L1 daily + L2 approved rollups, lineage and rebuild | FIN runtime, 166 forms, generic DSL, universal reports/graph, Tribuo |
| INT-02 Customer 360 | IDEA-01 | scoped client card, linked order/interaction/contract history, order count/amount, avg order, recency, comparable activity | churn probability, external enrichment, profiling without consent, AR/payment claims |
| INT-03 Warehouse 360 + ABC/XYZ | IDEA-03/04 | per warehouse/SKU balances, reservations, movements, turnover where authoritative, ABC value/count/quantity basis with UOM/currency constraints, XYZ qualified demand history | automatic purchasing/reorder, COGS/FIFO without source, forecasting, intercompany consolidation |
| EVT-01 Event Builder | IDEA-07 | canonical events/deadlines, closed typed rules, explicit audience, versioned safe templates, bounded schedule, durable intent/outbox, in-app delivery + mock external adapter | mass marketing, arbitrary scripts/SQL, actual SMTP/SMS/push before separately approved delivery gate, auction events, invoice/payment reminders without authoritative ledger |

IDEA-06 advanced INSIGHT-to-EVT alerts, IDEA-05 Tribuo, IDEA-18 expanded Radar, IDEA-08/09 auctions/chat, IDEA-10 FX_AUTO expansion, IDEA-11–16 additional features remain in prior scopes. R0.1 Radar code and existing CI retained; planned enhancement deferred Analytics First, unresolved mandatory gate is not waived. Smart Deadline R0.2 still PARKED. No new microservices, Kafka/Redis/ClickHouse, second event bus, new branch, merge main or production activation. Do not copy FIN IAM, schemas or financial semantics.

Normative:
- [Freeze](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md) new explicit exception CR-MVP-INTELLIGENCE-EVT-01; [Platform Standard](../architecture/RM-PLATFORM-STANDARD-R2.md), [Product Strategy](../strategy/RM-PRODUCT-STRATEGY-R2-SMB-QR.md).
- [INSIGHT-R1](RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md), [FIN Adaptation](RM-INSIGHT-FIN-ADAPT-R1-CRM-ANALYTICS-COMPUTATION.md).
- [TASK SLA & Outbox](RM-TASK-01B-R3.2B-SLA-POLICY-DURABLE-OUTBOX-FOUNDATION.md) — reference patterns, not blanket authorization for TASK R3.2B/B1–B4 or R3.2C.
- [Bank of Ideas](../ideas/RM-IDEA-BANK-R1.md), [EVT discovery Issue #11](https://github.com/alimbetov/RM/issues/11), [Radar Vision](RM-RADAR-VISION-R1-INTELLIGENT-BUSINESS-CONTROL-CENTER.md).
Preceding post-MVP statements in INSIGHT/FIN/Bank are superseded only for the bounded INT/EVT slices in this CR; other prohibitions and all safety gates survive. Use existing contracts where compatible, record conflicts, no duplicate implementation.

## 2. Source-first implementation and ownership

Initial inspection confirms existing TASK/CRM/SALES/INVENTORY/FX services and immutable histories. No complete dedicated analytics/event engine established by inventory. Before code, produce source field-to-metric/event mapping on exact HEAD; verify DDL, nullable legacy records, source transition producers, event IDs, tenant/warehouse/SKU/customer dimensions, accepted price snapshot, return disposition, actor/roles, timestamps, transaction boundaries, actual populated history. Identify events missing stable ID, event time, customer attribution, UOM or monetary snapshot as BLOCKED or PARTIAL, never infer. Source owner remains truth; no cross-domain arbitrary writes. Owner-side SQL and shared approved read contracts, not a separate canonical data warehouse. Use source canonical identity links, no email fuzzy matching. IAM/tenant role check at each read, write, job delivery and drilldown. All tenant-owned keys composite.

INSIGHT and EVT consume same canonical source events; INSIGHT does not depend on notification delivery. Existing IAM email/outbox is not a generic business event bus; no reusing identity secrets or delivering commercial mail as IAM. If source event producer/outbox needs changes, additive Liquibase only after review/clean+populated tests; maintain canonical business transaction atomicity. No outbound I/O under owner locks. No source mutation from analytics, alerts or recommendations. Runtime Spring Boot + Java + PostgreSQL, use existing libraries; H2 compatibility and PG real qualification mandatory. F2R design from FIN is adaptation of patterns not runtime/code import without review.

## 3. INT-01 — analytical foundation

First choose 6–8 mandatory core metrics from source inventory: placed order count, booked amount by currency, confirmed order count, fulfilled order count, cancelled order count, stock in/out by SKU/location/UOM, live available (on_hand-reserved). Status event counts not distinct cumulative order populations; placed and confirmed not added as independent orders. Booked amount not revenue, cash or debt. Returns separate authorized facts; no fake net sales if return monetary evidence missing. Average order = ratio of sums on matching cohort, not mean of daily averages. Inventory transfer internal two legs, per warehouse signed; company total net zero only for matched unit/SKU transfer. Reservations not movements. Stock as-of/gauge never sum across periods. No mixed currency/UOM sum without approved normalization.

Metric Registry: code, version, owner, source and required dimensions, operator, currency/UOM, cohort, event/asOf time, quality semantics, scope, precision, permitted filters, rollup legality, method SHA. Closed typed SQL expressions and prepared bind parameters. PeriodResolver D/W/DEC/M/Q/H/Y half-open intervals, ISO week-year, tenant IANA zone and DST, daily L1 and independent L2 week/decade/month rollups; Q/H/Y only if validated from compatible grains. Distinct counts and ratios not additive; use eligible facts/denominator or separately qualified set semantics. Source quality READY/PARTIAL/MISSING/STALE/NOT_MEASURABLE; zero only when complete scope proven. Result metadata asOf, watermark semantics, method/source version and data coverage; no synthetic global commit watermark. Exact BigDecimal and units, no float for money.

Sequence: reference direct Native SQL oracle → approved owner source/outbox contracts → L1 idempotent application → L2 → rebuild/backfill and freshness. Each receipt atomic with projection update; uniqueness composite source event+projection version; no max(sequence) assumption. Rebuild shadow version, reconcile, drain, atomic switch, previous version retained. Bounded backfill, retry, failure handling; no eager partitioning, benchmark and ADR first. Read model not authoritative for checkout/reserve/fulfill. Full F2R-01…04 and scoped F2R-05 acceptance; F2R-06/07 remain outside except minimal G2 read presentation. No dependency graph unless required by selected metrics.

## 4. INT-02 — Customer 360 MVP

Customer selector scoped by CRM permission + relevant SALES/TASK/contract rights, no aggregate leakage if domain denied. Profile history with bounded keyset; customer identity only canonical link, explicit unresolved attribution. Metrics: placed/confirmed/fulfilled/cancelled counts by period, booked amount per currency, avg order (booked / eligible distinct orders), last eligible order date, frequency, time series monthly, recent interactions, open tasks, active contract dates and expiry using owner policy. No hidden employee/customer score. Filter D/W/DEC/M/Q/H/Y where qualified; exact range, currency, asOf. Compare like periods and indicate incomplete current period, unknown prior baseline. No causal claim from a decline. Sales amounts restricted to authorized source; current price never substituted for agreed price. Separate deny vs partial vs complete empty.

## 5. INT-03 — Warehouse 360 + ABC/XYZ MVP

Read scoped tenant/location/SKU quantity onHand/reserved/available, movements (receipts, fulfill, correction, transfers, qualified return dispositions), opening/closing reconciliation, reservations and their age, stock days/turnover only if required balances and demand history exist. Returns RESTOCK alone increases available when actual posting; quarantine/write-off not available. No cross-SKU UOM totals by default. Inventory value/COGS only with qualified valuation; otherwise quantities and booked amounts shown separately.

ABC uses selected positive metric (e.g. booked amount in one currency, or demand quantity within homogeneous units) and selected period. Sort descending metric + stable SKU key; cumulative share exact, boundaries/version approved; candidate A 80%, B next 15%, C remaining 5%, tie/zero/negative policy explicit and tested. Do not call ABC profitability without verified margin. XYZ requires regular complete equal-length demand buckets, zero vs missing distinction, mean >0, explicit coefficient-of-variation methodology and thresholds, adequate observations, seasonality/stockout/censoring flags; candidate 12 weekly buckets, X CV<=10%, Y<=25%, Z>25% subject to business review. Insufficient history = UNKNOWN, not Z. Report ABC×XYZ matrix, drilldown and reason, not auto purchasing/reorder. KPI semantics and threshold approval required before implementation of classification; SQL independent oracle for each. Stock availability remains live owner view.

## 6. EVT-01 — Event & Notification Builder MVP

One configuration path: canonical trigger → scope → closed condition → eligible recipient → versioned template → delivery intent → bounded worker → receipt. Initial trigger candidates limited to verified order status changes (PLACED/CONFIRMED/READY_FOR_PICKUP), contract expiry and task deadline, subject to source inventory and policy; no derived future ML alert or auction/payment events. Explicit in-app recipients (authorized tenant roles or specific eligible users), verified actor, owner grant and per-recipient fresh permission. Minimal G2 management: select trigger, typed condition, recipient role, time/zone, template, preview, enable/pause, delivery history and operator retry. Test mode and default disabled. No arbitrary SQL, script, rich HTML/template code; allowlisted typed placeholders from safe projections; output escaping; no secrets or pickup credential in URL, no raw source event payload. In-app notification contains safe code and authorized deep link, reauthorization on open. Service and marketing consent separate; marketing delivery excluded. External adapter MOCK only for initial MVP; real email/SMS/push require separate owner/legal/consent/provider gate. No automatic confirmation, release reservation, changing price, completing task, marketing send. Scheduler only if approved within EVT stage after deterministic clock and persisted schedule qualification, no duplicate TASK worker. Original TASK R3.2B obligations/auto-review and R3.2C not blanket admitted.

Versioned policy + template, identity (tenant, trigger occurrence/source event, recipient, policy revision, channel), immutable rendered-safe intent, bounded delivery state PENDING/LEASED/DELIVERED/RETRY/DEAD/CANCELLED with lease generation fencing and claim SKIP LOCKED, retry/backoff/expiry, tenant stop, cancel/permission revocation check at delivery, terminal evidence. In-app insert + receipt atomic, dedup on lost response. External mock is not provider ACK; external at-least-once semantics and provider idempotency limitations explicit. Outbox/intent separate from analytics receipt; one canonical event contract not one shared mutable delivery status. Preview does not enqueue. Approval required before enabling scheduled dispatch; workload caps per tenant, no real external recipient data during test. Retention/deletion and audit of policy, recipient eligibility, disposition; minimal PII. Disable/stop fences, restart recovery and duplicate/late event tests. No unbounded retry or polling. B1–B4/T00 patterns can be reused only when compatible, with explicit review of changed scope and mandatory existing tests.

## 7. Staged roadmap (implementation admission)

| Gate | Work | Exit evidence |
|---|---|---|
| IE-00 Source & Contract Admission | source matrix; dependency/permission/event topology; 6–8 metrics; ABC/XYZ policy decisions; EVT trigger/recipient/consent; CI baseline and exact stage tickets | approved source+contract matrix; no invented facts; no code until IE-00 resolved |
| IE-01 Core metric foundation | F2R-01, period/registry, Native SQL source queries, independent oracle | exact calendar/unit/currency/scoping; clean PG/H2; no owner regression |
| IE-02 L1/L2 and recovery | F2R-02/03, approved owner emitter, idempotent projection, backfill/rebuild | no lost/duplicate facts, source reconciliation, PG races/crash and populated upgrade, measured workload |
| IE-03 Customer 360 | read-only scoped customer vertical | source attribution, exact counts/amounts, partial/missing/denied, paging and contract evidence |
| IE-04 Warehouse 360 | read-only balances, movements and reservation view | owner balance reconciliation, returns/transfers, no mutation, PG performance |
| IE-05 ABC/XYZ | deterministic classification and quality | approved thresholds, oracle, zero/missing/seasonality, independent replay |
| IE-06 EVT contracts & preview | owner event admission + rule/template/audience, mock intent/outbox | no delivery without permission, policy version, duplicate/cancel; secure preview |
| IE-07 EVT delivery | durable in-app worker + mock adapter, controlled scheduling, retry/lease/recovery | PG races, crash/retry, stop, expiry, no duplicate visible notification, no external sends |
| IE-08 Integrated G1 | exact-SHA all mandatory domain and IE tests; immutable RC | zero mandatory failures, no unexpected skips, no security/stock/price regression |
| IE-09 G2/G3/G4 | approved narrow REST/UI, OIDC, I1 real UAT alongside J1, pilot/release | separate G2/G3/G4 evidence; no automatic production admission |

IE-06 contract design may parallel IE-01…05; EVT core event delivery does not depend on analytical availability, new analytics-triggered events require separate scope. No active worker until stage proof. No REST/UI before integrated G1 and existing stage permissions. No broad FIN copy. Implementation selection is explicit, not permission to bypass prerequisite. Current next execution priority IE-00; core release blockers retain P0.

## 8. BUS-MVP-I1 — additional acceptance journey

In one qualified organization, owner/authorized seller opens customer analytics, verifies historical orders and contracts, compares periods, drills into an authorized order. Warehouse worker opens SKU/location view, reconciles stock with actual movements and reservation; owner views ABC/XYZ with method and missingness. Authorized operator configures a service notification rule, safe template and audience, previews without delivery; enables only after stage admission. Trigger from canonical event creates exactly one visible in-app notification for each eligible recipient; user opens authorized owner record, checks history, disables rule, retries failure safely. External send remains mock. Second tenant and restricted role cannot see list, count, cursor, export, notification or underlying source. Operator can distinguish missing data and stale projection. J1 sales/QR workflows run unchanged, no extra inventory/price/order writes due to analytics/EVT. User acceptance signed in G3, no raw SQL or developer console.

## 9. Qualification matrix (32 planned families, not executed)

| ID | Required proof |
|---|---|
| IE-Q01 | Metric registry version/source/whitelist, no arbitrary SQL |
| IE-Q02 | D/W/DEC/M/Q/H/Y and ISO week-year, leap/DST, half-open periods |
| IE-Q03 | Exact BigDecimal, currency and UOM segregation |
| IE-Q04 | Distinct events/orders and ratio-of-sums; gauge not sum |
| IE-Q05 | Missing vs zero, stale/partial, immutable provenance |
| IE-Q06 | Independent SQL per metric, cancellations and booked≠paid |
| IE-Q07 | L1/L2 exact reconciliation with cross-month week |
| IE-Q08 | Replay/backfill×live, dedup, source version gap |
| IE-Q09 | Two PG consumers, atomic receipt+result, crash before/after commit |
| IE-Q10 | Shadow rebuild, atomic switch and rollback |
| IE-Q11 | Customer canonical link, disputed/unlinked not attributed |
| IE-Q12 | Customer metrics and history period/denominator/filters |
| IE-Q13 | Restricted domain denied, cross-tenant same UUID, no counts |
| IE-Q14 | Stock balance invariant, reservations not physical debit |
| IE-Q15 | Transfers, return quarantine/write-off and RESTOCK evidence |
| IE-Q16 | Opening/closing and turnover, missing history and cost |
| IE-Q17 | ABC deterministic order/ties/threshold/zero/currency |
| IE-Q18 | XYZ complete buckets, CV boundary, stockout/missing, UNKNOWN |
| IE-Q19 | Metric/detail keyset and scope, no N+1 or unbounded export |
| IE-Q20 | Trigger source committed/rollback, clock/DST and late occurrence |
| IE-Q21 | Template allowlist/escaping, safe QR/link, no PII or credential |
| IE-Q22 | Explicit recipient eligibility, permission/consent and policy version |
| IE-Q23 | Preview no intent/delivery, disabled rule no dispatch |
| IE-Q24 | Replay/idempotency, exactly one in-app row, mock ≠ live ACK |
| IE-Q25 | Two PG claimers, lease fencing, stale ACK and retry/dead letter |
| IE-Q26 | Crash before/after in-app commit; restart and deterministic replay |
| IE-Q27 | Disable/revoke/expiry × dispatch both commit orders, no unauthorized receipt |
| IE-Q28 | No automatic domain command, no external send, log/DTO allowlist |
| IE-Q29 | Clean/populated Liquibase, compatible history and rollback plan |
| IE-Q30 | PG 1k/10k/100k source fixtures, plans, p95/p99, concurrent checkout/reserve/fulfill/TASK, bounded projection/EVT backlog |
| IE-Q31 | G2 real OIDC/role/tenant/detail tests, I1 UI and J1 no regression |
| IE-Q32 | Exact-SHA XML/manifests, G3 UAT/backup/restore, G4 independent admission |

Map existing F2R/INSIGHT/TASK tests to appropriate subcases, avoid duplicating counts; no claim PASS for planned families. Test independent real signed JWT, PG 17, H2 compatibility. Performance proposed design review: compare predeclared same resource baseline/mixed workload, actual overlap, raw sample/plan evidence, no lowering existing 200ms/10% Radar gates; for new slices targets per actual workload approved before coding qualification (e.g. bounded warm p95 and <=10% command degradation candidate). Existing failure remains HOLD; unrelated engineering can proceed within explicit scope but no global release waiver. Record workload/hardware, lock/CPU/IO/GC and data quality/freshness; no unmeasured ROI or SLA.

## 10. User acceptance and open decisions

Proposed measurable outcomes: correctly answer selected customer and stock questions without manual spreadsheet; same result vs independent SQL, correct UNKNOWN when facts insufficient; notification exactly once visible per eligible recipient on tested canonical event, no unauthorized or real external delivery; existing J1 unchanged. Before IE-01/IE-06 respectively agree exact selected metrics/dimensions and business thresholds, approved event type list, recipient roles and schedule/limits. IE-00 to inventory actual source, select methods/test cases and define workload and reference fixtures. Owner confirms any broader campaign/SMTP/SMS/push, financial/ML, code import/licensing, advanced reports, additional providers separately. No new automatic actions. R1.0 documented and chosen for MVP; no code, no execution results, no production admission in this commit.
