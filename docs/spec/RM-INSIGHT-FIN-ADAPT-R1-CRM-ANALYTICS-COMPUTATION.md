# RM-INSIGHT-FIN-ADAPT-R1 — FIN-to-RM Reusable Analytics Patterns
## Архитектурный контракт: Metric Registry · Period Resolver · Source Admission · Native SQL · Versioning · Metric Graph · Analytics · Reports · Tribuo

**Дата:** 2026-11-24  
**Репозиторий назначения:** `alimbetov/RM`, **ветка:** `base_dev` (не создавать ветку и не изменять `main`)  
**Сверенный исходник FIN:** `alimbetov/fin-analyzer`, `feat/fin-form-library-standalone`, commit `323f0d4e7c8f99fec97fa8be1f69a1b8414436d9`  
**RM source-review baseline:** `19ce05d4355eb1f0b3e52016f55ed47035d27211`  
**Статус:** `ARCHITECTURE / ADAPTATION SPEC / SCOPED MVP SELECTED (INT-01…03) / STAGED ADMISSION / PRODUCTION HOLD`  
**Владелец решения:** RM Business Intelligence; бизнес-источники остаются в SALES, INVENTORY, CRM, TASKS, STOREFRONT и IAM.  
**Связанные документы:** [RM-INSIGHT-R1](RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md), [банк идей](../ideas/RM-IDEA-BANK-R1.md), [MVP freeze](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md), [RM Business + QR](../strategy/RM-PRODUCT-STRATEGY-R2-SMB-QR.md).

> **Принятое архитектурное решение:** FIN Analyzer **не объединяется** с RM и не добавляется как внешний обязательный HTTP, Maven, runtime или database dependency. Заимствуются только точные идеи и, после отдельного legal/license review, небольшие чистые Java-алгоритмы. Все контракты адаптируются к авторизации, источникам истины, типам данных и миграциям RM. Сам документ НЕ меняет MVP freeze, не включает SQL DDL, HTTP API, новые workers, Tribuo dependency или UI.

> **Owner scope CR-MVP-INTELLIGENCE-EVT-01 (2026-11-25):** [Scoped MVP R1](RM-MVP-INTELLIGENCE-EVT-SCOPE-R1.md) authorizes F2R-01…04 and the necessary scoped XYZ/performance part of F2R-05 after IE-00 source/contract admission. F2R-06/07, generic graph/reports/ML, FIN runtime remain PARKED; earlier post-MVP language is historical for admitted slices. Current core J1 qualification independent, expanded MVP additionally I1; all G1–G4 unchanged.

## 0. Executive decision и цели

**Цель:** надёжная CRM/торговая аналитика по клиентам, заказам, складам, SKU и периодам, пригодная для ABC/Pareto/XYZ и последующего Tribuo ML. Отчёт должен объяснять откуда взят показатель, как рассчитан, в каком он периоде/валюте, насколько полны факты и когда проекция обновлена.

**Девять ЛОГИЧЕСКИХ компонентов, НЕ девять сервисов или bounded contexts:**

```text
RM Intelligence (read/projection composition across existing owners)
  ├── Metric Registry
  ├── Period Resolver
  ├── Source / Context Admission
  ├── Native SQL Aggregation
  ├── Methodology & Result Versioning
  ├── Metric Dependency Graph
  ├── Customer / Warehouse Analytics
  ├── Report & Export
  └── Tribuo Feature Adapter
```

**Необходимое условие:** source domain event / immutable commercial evidence имеет приоритет над аналитической проекцией. Проводки SALES/INVENTORY, остатки и текущие разрешения никогда не управляются расчётным модулем.

### 0.1. Отобранное для переноса: матрица FIN → RM

| No | Подтверждённый FIN артефакт | Решение для RM | Способ | Приоритет | Не переносить |
|---|---|---|---|---|---|
| AD-01 | `PeriodResolver` + `PeriodBatchPlanner` | Канонические D/W/DEC/M/Q/H/Y периоды с [start,end) и tenant calendar policy | Адаптировать небольшой чистый алгоритм | **P1** | FIN HTTP exceptions, UI colors, финансовые form codes |
| AD-02 | `Calc154AggregateFormulaJdbcPreview`, `Calc154CandidateArithmeticJdbcEngine` | Типизированные SQL `SUM`, `COUNT`, `GROUP_BY`, `RATIO_OF_SUMS`, `AS_OF` | Переиспользовать SQL-паттерн и whitelist semantics | **P1** | `fin_calc` schema/functions, FIN UAT status, произвольный formula DSL |
| AD-03 | `FieldMetricContractGuard`, FIN `fin_lib` definitions | Реестр метрических контрактов: источник, тип, размерности, единица, оператор, версия | Новый маленький RM-owned contract registry | **P1** | 166 financial forms, 840 FIN метрик, каталоги неиспользуемых форм |
| AD-04 | `FactDigest`, pinned source revisions, `MetricSourceAvailabilityService` | Check source/context admission, canonical fingerprints, explicit missing/ineligible evidence | Адаптировать протокол канонизации/хеширования | **P1** | FIN `FactSet`/financial S2, FIN issuer/Keycloak/ACL |
| AD-05 | `ReportGridService` derived-only totals contract | `SUM`, `LAST_VALUE`, ratio и output-only view; не смешивать input и derived | Правила отображения и числовой семантики | **P1** | Редактор финансовых форм и rewrite canonical CRM data |
| AD-06 | `Calc4DTypedDagCompiler`, `MetricGraphResolver` | Явный dependency graph KPI; циклы, missing sources, версия формулы | Узкий metadata graph только для нужных CRM KPI | **P2** | Исполняемый универсальный FIN planner и неутверждённые формулы |
| AD-07 | `FactMonthlyPartitionService` | Управляемое обслуживание месячных партиций только для больших исторических источников | Паттерн inspection/dry-run/advisory lock | **P2 при подтверждённой нагрузке** | `fin_fact`, auto-DDL для всех таблиц, partition-per-customer |
| AD-08 | `Calc4DUatLeaseQueue`, `Calc4DUatLeasedWorker` | Устойчивый rebuild/replay и fencing при длительных пересчётах | Извлечь принципы, не код worker | **P2 после проверки необходимости** | UAT-флаги, FIN job/receipt tables, production claims |
| AD-09 | `ReportGridService`, `FormXlsxExchangeService`, versioned import profiles | Table/grid/export, pinned metric metadata, Excel optional; операторский preview | Адаптировать read/export contract | **P2** | Импорт FIN форм как бизнес-фактов RM |
| AD-10 | FIN source availability, typed bindings | Versioned feature definitions/exports для Tribuo | Собственный read-only Feature Adapter | **P3** | Прямая DB-связь FIN→RM или autonomous ML stock actions |
| AD-11 | FIN KPI Navigator / Atlas | Дашборд «вопрос бизнеса → метрика → источник → действия» | UX-идея; **design only** | **P3** | Имитировать наличие готового пользовательского RM UI |

**Требование по статусу:** FIN исходники существуют, но ряд вычислений помечен `CANDIDATE`, `PREVIEW_ONLY`, `TEST_QUALIFIED SYNTHETIC` и `UAT`. Перенос не наследует финансовую S2-квалификацию или release qualification. Для RM каждый контракт должен получить самостоятельные тесты и exact-SHA admission.

### 0.2. Канонические ссылки FIN (проверены на exact source branch)

- [PeriodResolver.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/service/PeriodResolver.java)
- [PeriodBatchPlanner.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/service/PeriodBatchPlanner.java)
- [Calc154AggregateFormulaJdbcPreview.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/calc/hybrid/Calc154AggregateFormulaJdbcPreview.java)
- [Calc154CandidateArithmeticJdbcEngine.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/calc/hybrid/Calc154CandidateArithmeticJdbcEngine.java)
- [FieldMetricContractGuard.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/service/FieldMetricContractGuard.java)
- [FactDigest.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/calc/fact/FactDigest.java)
- [Calc4DTypedDagCompiler.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/calc/plan/Calc4DTypedDagCompiler.java)
- [MetricGraphResolver.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/calc/graph/MetricGraphResolver.java)
- [FactMonthlyPartitionService.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/calc/partition/FactMonthlyPartitionService.java)
- [ReportGridService.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/service/ReportGridService.java)
- [FormXlsxExchangeService.java](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/src/main/java/kz/fin/formlib/service/FormXlsxExchangeService.java)
- [FIN Derived Totals contract](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/form-library/docs/FIN-FORM-LIB-R5-OUTPUT-ONLY-DERIVED-TOTALS.md)
- [FIN KPI Navigator](https://github.com/alimbetov/fin-analyzer/blob/feat/fin-form-library-standalone/docs/FIN-KPI-R0-INDICATOR-NAVIGATOR-TZ.md) — проектное описание, НЕ реализованный AI-инструмент.

## 1. Архитектурная граница внутри RM

```text
CRM/CONTRACT/TASK ─────┐
SALES/QR/RETURNS ──────┤ owned canonical state/events
INVENTORY/WAREHOUSE ───┘
          │
          ▼
Domain-owning transactional event/outbox (INSIGHT-R1)
          │
          ▼
Idempotent projection worker / applied receipts
          │
          ├─ SALES daily metric (domain owner: SALES)
          ├─ INVENTORY daily flow metric (domain owner: INVENTORY)
          ├─ CRM daily events and customer-link attribution (CRM owner, only when approved)
          └─ tiny shared pure contract helpers: time/digest/typed validation
          │
          ▼
Versioned period rollups / read facade (composition, not new ownership)
          │
          ├─ Customer 360 + Sales KPI
          ├─ Warehouse 360 + ABC/XYZ
          ├─ Metric graph / report / export
          └─ Tribuo feature snapshots (read-only) → forecasts
```

**Не создавать новый `DomainModule.ANALYTICS`, отдельные `fin_` схемы или собственный публичный `rm_intelligence` домен без ADR.** Исходное [RM-INSIGHT-R1](RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md) закрепляет таблицы/сервисы с префиксами SALES/INVENTORY и ограниченную dashboard composition. Новые cross-domain структуры данных — только после отдельного ADR и evidence, без подмены владельцев фактов. Один Spring Boot modular monolith, Java 21, Spring JDBC/JPA, PostgreSQL, Liquibase. H2 для совместимости и быстрых тестов, не доказательство PG correctness.

**Транзакции:** canonical business change + source event + outbox в одной OLTP транзакции; projection применяется независимо с идемпотентным receipt. Недоступность аналитического read facade не должна превращать inventory lock/checkout в ожидание долгого ML/отчёта.

## 2. Metric Registry: минимальный собственный контракт RM

**Идея:** бизнес-показатели как строго описанные договоры, а не SQL-текст из интерфейса.

Предлагаемый read-only `MetricDefinition`:
```text
metricCode             e.g. SALES.BOOKED_ORDER_AMOUNT
ownerDomain            SALES | INVENTORY | CRM | TASK
sourceEventKinds       explicit allowlist
valueType              DECIMAL | INTEGER | BOOLEAN | DURATION
unit                   KZT | QTY:<uom> | COUNT | DAYS | PERCENT
dimensionsAllowed      tenant, customer, sku, warehouse, calendar-period
currencyPolicy         EXACT_SAME | NONE | CONVERSION_APPROVED
timeSemantics          EVENT_FLOW | PERIOD_RATE | AS_OF_GAUGE | COHORT
aggregationRule        SUM | COUNT | RATIO_OF_SUMS | WEIGHTED_AVG | LAST_AS_OF | NON_ADDITIVE
missingPolicy          FAIL_CLOSED | PARTIAL_WITH_REASON | ZERO_IF_OBSERVED
methodologyVersion     immutable
definitionSha256       canonical semantic hash
sourcePermission       existing domain permission(s), no implicit ANALYTICS_READ
status                 DRAFT | QUALIFIED_FOR_RM | DISABLED
```

**Не вводить 166 форм и FIN financial metric identifiers.** Коды RM устойчивые; UI translations и формат отображения не изменяют identity, SHA или методику. Никаких исполняемых пользовательских SQL/Java выражений, dynamic identifiers должны разрешаться только серверным whitelist.

### 2.1. Начальная матрица целевых KPI (предложения, не реализованный счётчик)

| Metric Code | Owner | Source oracle | Operator | Не допускать |
|---|---|---|---|---|
| SALES.PLACED_ORDERS | SALES | `sales_order_status_event` PLACED | unique sourceEvent count | counting one order multiple times because status transitions |
| SALES.BOOKED_ORDER_AMOUNT | SALES | PLACED + immutable `sales_order` snapshot | SUM in exact currency | paid revenue / recognized revenue claim |
| SALES.CONFIRMED_ORDERS | SALES | CONFIRMED event | COUNT | historical restatement based on current status |
| SALES.COMPLETED_ORDERS | SALES | COMPLETED event | COUNT | infer delivered/paid |
| SALES.CANCELLATION_RATE | SALES | defined cohort source by orderId | ratio of counts with same cohort cutoff | sum/average of daily percent |
| SALES.AVG_BOOKED_CHECK | SALES | booked amount and placed orders same exact cohort | ratio of SUM/COUNT | average of segment averages |
| INVENTORY.STOCK_IN | INVENTORY | positive physical movement with typed kind | SUM QTY SKU/UOM | transfer in counted as external purchase |
| INVENTORY.STOCK_OUT | INVENTORY | negative physical movement by reason | SUM QTY SKU/UOM | reservation interpreted as physical issue |
| INVENTORY.AVAILABLE_NOW | INVENTORY | **live** `inv_stock_balance` | AS_OF onHand-reserved | L2 flow used for checkout promise |
| INVENTORY.ON_HAND_AS_OF | INVENTORY | approved historical immutable balance/snapshot/ledger reconstruction | AS_OF, NOT ADDITIVE | fake historical balance derived from one latest row |
| INVENTORY.DAYS_OF_COVER | INVENTORY+SALES read composition | current available and time-aligned observed demand | controlled ratio | unknown demand, days-of-stockout ignored |
| CRM.CUSTOMER_ORDER_ACTIVITY | CRM + SALES | customer↔buyer CONFIRMED mapping + owned order events | tenant-keyed event attribution | join on email or disputed buyer link |
| CRM.CONTRACTS_EXPIRING | CRM | active contract + explicit `endsOn` and report asOf | filtered COUNT / AS_OF | invent invoice or due payment |
| TASK.OVERDUE_OPEN | TASK | task state with dueAt and report asOf | AS_OF filtered COUNT | add period snapshots as a flow |
| SALES.SKU_PARETO_SHARE | SALES | qualified booked order amounts grouped SKU | ordered share/window cumulative | claim COGS, gross margin or paid turnover |
| INVENTORY.SKU_XYZ_CV | SALES+INVENTORY | equal-length observed demand periods + stockout indicators | model-specific CV | mixed weekly/decade periods or zeros from stockout |
| ML.DEMAND_PREDICTION | derived projection only | pinned versioned feature set + model | forecast, NOT KPI fact | inventory adjustment or autonomous purchase |

**Первый технический slice:** 6–8 простых метрик из SALES/INVENTORY с уже существующими событиями и независимым SQL oracle. Не реализовать сразу всю таблицу.

## 3. Period Resolver: адаптация FIN без изменения предметной семантики

FIN `PeriodResolver` реально определяет `D/W/DEC/M/Q/H/Y`, ISO week, декады 1–10, 11–20, 21–конец месяца, а также exclusive end. В RM первоначально нормализовать события к **UTC daily L1** (текущий INSIGHT-R1); tenant-local policy вводить только после `timezonePolicyVersion` и re-projection.

Пример contract:
```text
RmPeriodKey(periodType, periodCode, zonePolicyVersion)
RmPeriodRange(startInclusive: Instant, endExclusive: Instant, localStart: LocalDate,
              localEndExclusive: LocalDate, isComplete, asOf)
```

- Недели и декады **не могут** выводиться точно друг из друга. `W`, `DEC` и `M` строить независимо из daily L1.
- `2026-W48` = 2026-11-23 (Mon) .. 2026-11-30 exclusive; декады ноября 1–10/11–20/21–30; декада 3 имеет переменную длину.
- `W` использовать ISO week-based year, включая W53 и переход December/January.
- `day_utc` и local business day не смешивать в одном `period_code`; tenant time zone policy/version и DST тестировать отдельно.
- `SUM` по различным интервалам только на событиях, попадающих в единый закрытый/частичный asOf cutoff; день/период `INCOMPLETE` не маркировать готовым.
- Чистые Java-алгоритмы допускается адаптировать без FIN package names и Web exceptions; H2 tests не подтверждают timezone data integrity.

## 4. Source / Context Admission и lineage

Контракт допуска до вычисления:
```text
AdmittedMetricInput(
  tenantId, domainOwner, sourceEventId, sourceRevision,
  optionalCustomerId, optionalSkuId, optionalWarehouseId,
  metricCode, eventAtUtc, periodPolicyVersion,
  currencyCode, unitCode, sourceDigest, mappingVersion,
  authenticatedReadScope, dataQualityState
)
```

**Обязательное:** tenant/actor/object permission, source kind allowlist, verified customer linkage, current authorization at query time; точная currency/UOM/period/methodology policy; immutable source revision; explicit `MISSING_SOURCE` / `UNATTRIBUTED` / `PARTIAL_HISTORY` / `STALE_PROJECTION`. Широкие UUID из URL не дают права на чужие данные.

FIN `FactDigest` использует версионированный length-delimited SHA-256. Принять как идею для RM `SourceDigest` с domain separation, canonical ordering и normalized BigDecimal; SHA обеспечивает **контроль целостности/идемпотентности, не аутентификацию или электронную подпись**. IAM остаётся в RM.

```text
sourceDigest = SHA256(
  "RM-INSIGHT-SOURCE-V1" |
  tenant | owner | sourceEventId | sourceRevision |
  metricCode | normalizedDimensions | periodPolicyVersion |
  numericValue | unit | currency
)
```

Код выше — **логическая схема**, реальные байты должны быть типизированы/length-delimited с тестовым golden vector и разделением nullable значений. Нельзя строить критический SHA простым соединением строк через `|`, если поля могут содержать этот символ.

**Replay/late updates:** applied receipt unique на (tenant,owner,sourceKind,sourceEventId,projectionVersion); correction — новая авторитетная ревизия или компенсирующий event с явно типизированным effect. Изменение CRM buyer link не исправлять тайно произвольным JOIN: revoke/dispute вызывает контролируемый reattribute/rebuild, не утечку приватной истории.

## 5. Native SQL Aggregation: безопасные формулы и rollup

**SQL-first, не JPA entity scanning** для миллионов событий. Статический/allowlisted SQL под фактические источники SALES/INVENTORY, BigDecimal/NUMERIC, `REPEATABLE READ` для pinned read preview когда нужна согласованная выборка; запись агрегатов транзакционна и идемпотентна.

**Основные операторы:**

| Operator | RM example | Норматив |
|---|---|---|
| `COUNT` | orders placed | unique order/status source event identity, не приписывать cancel повторно |
| `SUM` | physical stock movement quantity | только совместимые SKU/UOM/warehouse/movement types |
| `RATIO_OF_SUMS` | cancellation rate | SUM(cancel numerator)/SUM(eligible denominator); division by zero policy |
| `WEIGHTED_AVG` | средний показатель с весами | хранить сумму произведений + сумму весов, не AVG средних |
| `LAST_AS_OF` | исторический on-hand, latest valid snapshot | только при наличии подтверждённого as-of source |
| `GROUP_BY` | warehouse/SKU/customer KPI | whitelist dimensions + tenant predicate before GROUP BY |
| `PARETO` | топ SKU по booked amount | `SUM` затем `SUM(amount) OVER (ORDER BY amount DESC, sku_id)`; tie-break stable |
| `XYZ_CV` | коэффициент вариации спроса | равномерные интервалы с достоверной доступностью товара; sparse demand отдельный policy |

Пример SQL-эскиза на **уже квалифицированной** ежедневной сумме:
```sql
WITH sku_amount AS (
  SELECT tenant_id, sku_id, SUM(booked_kzt) AS amount
  FROM qualified_sales_daily_sku_metric  -- illustrative read model, not current RM table
  WHERE tenant_id = :tenant_id AND day_utc >= :from_day AND day_utc < :to_day
  GROUP BY tenant_id, sku_id
)
SELECT sku_id, amount,
       SUM(amount) OVER (
         ORDER BY amount DESC, sku_id
         ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
       ) AS cumulative_amount
FROM sku_amount
ORDER BY amount DESC, sku_id;
```
**Not executable RM DDL:** приведённые названия условны. Для ABC дополнительно вычислять denominator total и накопленную долю, отдельно обрабатывать total=0, currency != KZT и возвраты/отмены. Не называть `booked` оплаченным доходом. Нельзя добавлять SQL-фрагменты из пользовательского фильтра.

**Две гранулярности:**
- **L1** day × domain × authorized metric dimensions → genuine event flow. Текущий `available` — живой gauge, **не суммируется**.
- **L2** `ISO_WEEK`, `DECADE`, `MONTH` × совместимые dimensions × methodVersion → воспроизводимые `SUM/COUNT/RATIO` from L1. Period id, isComplete, source watermark, asOf and version are mandatory.
- late correction → targeted day and dependent weeks/decades/months; full rebuild only for methodology upgrade and source retention rules. No `MAX(id)` / timestamp as a commit-safe event cursor without proof.

**PostgreSQL performance:** tenant-first selective indexes; `EXPLAIN (ANALYZE, BUFFERS)`, `pg_stat_statements`, source/live mixed write workload before partitioning. FIN `FactMonthlyPartitionService` demonstrates offline preflight and dryRun, but no partition per tenant, client, category or small metric table. Use month partition on large fact/event history *only* after workload ADR. Validate populated migrations and rollback.

## 6. Methodology & Result Versioning

**Metadata owner:** no new financial methodology approval semantics. For RM, label `DRAFT → TECHNICALLY_QUALIFIED → PILOT_APPROVED` means approved source/math/query behavior **only**, not IFRS, audited revenue or legal guarantee.

Every published projection/report result must pin:
```text
tenantId; metricCode; methodologyVersion; formulaSha256;
sourceSetDigest; projectionVersion; periodType/Code; zonePolicyVersion;
currency; unit; dimensions; asOf; freshness; completeness;
resultSha256; processingEvidence; businessMeaning
```
Derived-only: computed totals/ratios/forecasts **не пишутся обратно** в business fact or invoice/order. For independent audit can persist versioned materialized result/receipt in existing owner domain; historical result remains readable until retention, new version rebuilds in shadow then atomic switch.

Use states `READY`, `PARTIAL_HISTORY`, `SOURCE_MISSING`, `SOURCE_UNQUALIFIED`, `STALE`, `CONFLICT`, `REBUILDING`. Reporters must not convert `null` to zero or silently omit inaccessible warehouses/customers.

## 7. Metric Dependency Graph: minimal, staged

Graph only for actual multi-input metrics:
```text
SALES.BOOKED_ORDER_AMOUNT ─┐
SALES.PLACED_ORDERS ───────┴─> SALES.AVG_BOOKED_CHECK
SALES.COMPLETED_SKU_QTY ──┐
INVENTORY.AVG_ON_HAND ────┴─> INVENTORY.TURNOVER
SALES.DAILY_SKU_DEMAND ───┬─> ABC / XYZ
INVENTORY.STOCKOUT_WINDOW ┴─> ML.DEMAND_FEATURES
```
Pinned edges require matching tenant, SKU/UOM, reporting period and currency; explicit exceptions for cross-period models (e.g. baseline → actual), no automatic inference. Detect cycle, missing source, incompatible versions/units. **R2 only**: Until at least three real dependent metrics require DAG, use static Java contracts; do not import full FIN graph runtime.

## 8. Customer 360 / Warehouse 360 business views

**Customer 360:** activity by authenticated tenant and explicit customer ID, `CrmBuyerLinkService` confirmation for buyer-order join; order counts, booked amount, quote/negotiation outcomes (only with trustworthy events), interactions, tasks, contract ending. Unknown relationship is `UNATTRIBUTED`, not cross-customer SQL join. Consent and PII policy apply.

**Warehouse 360:** `inv_stock_balance` for live onHand/reserved/available; movements and reservation events separate; physical transfer legs separated and company-net = 0 for warehouse-to-warehouse transfer. Returns/quarantine are not all immediate available stock; existing disposition owner decides. Historical stocks/turnover require correct snapshot baselines; a movement ledger delta is not itself a complete as-of snapshot.

**ABC/Pareto:** classify by qualified sales/physical demand value, source/methodology explicit; configurable A/B/C thresholds and tie policy; no COGS/profit assumption.

**XYZ:** volatility of equal-duration comparable demand windows; avoid constructing weekly values from decade alone; censored stockout demand and sporadic demand must be flagged separately. Business decisions are advice only.

**Financial boundary:** current RM completed order ≠ payment, delivery, revenue recognition or profit; no FIN A05/ECL/DCF kernel migration into sales dashboard.

## 9. Report & Export

- Typed `ReportDefinition`: allowed metric IDs, dimensions, period types, period range, page size, methodology pins, locale, projection readiness, read permissions.
- Supported initial presentations: summary cards, period grid, drill-down link to authorized source; later role dashboards and read-only XLSX export. Content semantics stable across ru/kk/en and sorting.
- `XLSX` and CSV files contain export metadata: metric registry/method version, asOf, incomplete markers, source digest, tenant context (without PII leakage). No bearer tokens, arbitrary SQL or server path traversal.
- Export is **read-only**. Any future spreadsheet import is separate from analytics facts, using server-pinned field mapping; Excel result cannot overwrite SALES/INVENTORY.
- Do not copy FIN form editor, React page, controller or IAM chain. RM frontend policy and MVP stage govern introduction of views.

## 10. Tribuo Feature Adapter (post-SQL quality)

Purpose-built **read-only** feature snapshot:
```text
FeatureSnapshot(tenantId, skuId, warehouseId, featureSchemaVersion,
                trainingCutoff, sourceProjectionVersion, sourceDigest,
                observationWindow, observedDemand, availableHistory,
                stockoutMask, leadTimeState, qualityState)
Prediction(modelVersion, featureSnapshotSha, targetHorizonDays,
           predictedDemand, uncertaintyState, evaluationEvidence)
```
Only after daily and period rollups are independently verified. Time-based train/validation/test, no future leakage; compare naive baseline, seasonal naive, WAPE/MAE/MASE and fairness by ABC/XYZ/warehouse; record stockout-censored days separately. A Tribuo model is not a time-series oracle and cannot turn missing supplier lead time into reliable stockout probability.

**No autonomous inventory movement, price change, order cancellation, marketing contact, or stock reservation from a prediction.** Future [RM-EVT-R1](https://github.com/alimbetov/RM/issues/11) may consume versioned, explained predictions as informational notifications subject to permissions and channel consent.

## 11. Security and non-functional invariants

1. Actor is verified by RM IAM; tenant scope and object ACL checked before read/aggregation. Do not port FIN's `user_id+project_id` authorization as RM authorization.
2. Worker operates with technical identity limited to owner-specific analytics projections, not a fake `VerifiedActor` of arbitrary user.
3. Every source/derived row includes tenant, ownerDomain, source event identity, schema/metric/method versions. Composite tenant constraints and deny negative tests.
4. PII from CRM person secrets, emails and phone numbers must never enter feature vectors or metric labels without explicit lawful purpose/review; prefer opaque IDs and aggregated quantities.
5. No OLTP transaction waits for ML training, export or full-backfill; bound memory/CPU/query pool and worker budgets, monitor p95/p99 checkout and report latency.
6. Outbox consumer `at least once` with `exactly once effect` from unique applied receipts. Crash-before/after, backfill+live overlap and rate/price/order revisions must not double-add.
7. Do not use audit security log or notification delivery status as canonical SALE/INVENTORY event feed; they serve different purposes.
8. Source-to-reported KPI data contract must explicitly represent `dataFreshness`, `asOf` and partial/unknown status.
9. Reporting current stock from `last published L2` is forbidden for commerce reservation/availability. Read-only analytic forecast cannot be used to approve checkout.
10. No new Redis, ClickHouse, TimescaleDB, Rust, Kafka, FEngine coupling or separate ML microservice without performance ADR and post-MVP approval.

## 12. Acceptance plan: 32 proposed tests (NOT executed)

| ID | Test purpose / independent oracle |
|---|---|
| F2R-01 | ISO week 53 / week-based year correctness versus `java.time` |
| F2R-02 | DEC 1/2/3 inclusive boundaries and 28/29/30/31-day months |
| F2R-03 | UTC vs versioned tenant timezone / DST rollover semantics |
| F2R-04 | Weekly aggregation across two different calendar months ≠ from decade buckets |
| F2R-05 | Event count dedup: replayed immutable status event produces exactly one contribution |
| F2R-06 | Sales PLACED/CONFIRMED/COMPLETED/REJECTED groups do not double-count orders |
| F2R-07 | Booked monetary sum vs explicit paid/revenue missing: fail closed |
| F2R-08 | Ratio of sums vs average of daily ratios, with unequal denominators |
| F2R-09 | True zero vs NULL/MISSING/PARTIAL source states |
| F2R-10 | ASN stock current gauge vs daily movement sum and average stock |
| F2R-11 | Transfer two legs company sum=0; per location movements correctly signed |
| F2R-12 | Reservation event does not double-count physical stock-out |
| F2R-13 | Return quarantine cannot inflate available if not RESTOCK-posted |
| F2R-14 | Distinct customer count across days does not double-count repeat buyer |
| F2R-15 | CRM buyer link pending/disputed/revoked yields no unauthorized attribution |
| F2R-16 | A/B tenants with overlapping SKU and customer UUIDs remain isolated |
| F2R-17 | Dual-tenant role with different permissions denies forbidden dashboard section |
| F2R-18 | Compromised caller cannot pass arbitrary SQL operator/metric/dimension/tenant |
| F2R-19 | Exact source digest mismatch / wrong methodology version blocks publication |
| F2R-20 | Numeric BigDecimal scale/rounding and currency/UOM mismatch block |
| F2R-21 | ABC top sorted deterministic ties; zero denominator, threshold boundary |
| F2R-22 | XYZ irregular/intermittent and stockout periods report quality limitations |
| F2R-23 | Period completeness, asOf freshness and late order correction propagation |
| F2R-24 | Import/backfill overlapping live delivery produces no gap or duplicate |
| F2R-25 | Two PG workers race on same receipt, exactly one projection effect |
| F2R-26 | Worker crash before commit and after result commit returns stable receipt |
| F2R-27 | Historical methodology rebuild on shadow version, atomic switch, old stable |
| F2R-28 | Metric graph missing source, cycle, wrong period/unit/version fail closed |
| F2R-29 | Bounded reports: permission, pagination, stable ordering and XLSX safety |
| F2R-30 | Partition preflight dry-run and concurrent ensure against populated PG, if enabled |
| F2R-31 | Tribuo leakage guard, feature SHA, temporal split, naive baseline (once enabled) |
| F2R-32 | Mixed checkout/stocktake/backfill load: measured p95/p99 and 0 duplicate/lost facts |

Each F2R requires traceability `requirement → Java/SQL → concrete JUnit method → PG report → exact SHA`. H2 tests are compatibility only, not proof of PostgreSQL race/index/partition semantics. Performance gates and fixture sizes from [RM-INSIGHT-R1](RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md) apply; its numeric SLOs are hypotheses, not achieved metrics.

## 13. Incremental implementation roadmap and independent gates

| Stage | Deliverable | No-go if |
|---|---|---|
| **F2R-00 (now, DOC ONLY)** | This adoption spec + idea bank registration and source/code review | MVP freeze changed, new module/schema created |
| **F2R-01 (after IE-00)** | Adapt pure PeriodResolver tests + 6–8 metric registry entries, source owner matrix | Period/currency/permission contract ambiguous |
| **F2R-02** | Native SQL/day L1 + exact source outbox/applied receipts aligned to INSIGHT-R1 | Lost/double event, negative PG admission |
| **F2R-03** | L2 week/decade/month + metric methodology, result SHA and replay/rebuild | Irreconcilable weekly/decade boundary, stale asOf hidden |
| **F2R-04** | Customer 360 and Warehouse 360 read facades; ABC/Pareto pilot | Identity/stock/paid-revenue semantics ambiguous |
| **F2R-05** | Minimal dependency graph, XYZ and performance + optional partition ADR | DAG not needed, no measurable workload benefit |
| **F2R-06** | ReportGrid/export in RM UX, XLSX with safe versioned metadata | Permission or memory/PII concerns |
| **F2R-07** | Tribuo feature adapter + offline validation; optional EVT event outputs | No sufficient data quality, temporal baseline failed |

**Non-blocking rule:** Existing RM-INSIGHT-R1 source/outbox design is prerequisite. Core J1 qualification remains independent; expanded MVP acceptance requires I1 per CR-MVP-INTELLIGENCE-EVT-01, phased release only by explicit owner decision. No scope expansion by referencing FIN branch.

## 14. Definition of Done and explicit exclusions

**DoD for first release:** 6–8 source-traceable metrics; day/week/decade/month exact reconciliation on PostgreSQL; tenant-safe CRM/stock queries; 0 duplicate/lost facts in tested replay; explicit asOf/freshness/metric methodology; source data never overwritten; exact-SHA regression; real OIDC/UI only under separate approved product gate.

**Out of scope:** FIN 166 financial forms, `fin_lib/fin_intake/fin_fact/fin_calc` schemas, FIN-specific IAM, S2 accounting policy, A05 loans, C01/C02 accounting, ECL/DCF, universal formula authoring, direct FIN database calls, new ML microservices, eager monthly partitions on all tables, ML-driven autonomous inventory and price mutation.

**Final decision:** Adopt a **small RM-owned calculation/analytics capability** by borrowing FIN's best *patterns*; keep FIN and RM independent products; preserve domain owner, single source of truth, staged post-MVP admission and measured PostgreSQL performance.