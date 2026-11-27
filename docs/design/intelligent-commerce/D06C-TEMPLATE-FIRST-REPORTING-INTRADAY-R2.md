# D06C — Template-First Analytical Reporting & Intraday Refresh
2026-11-26 | R2.1 design refinement | PRODUCT DIRECTION CONFIRMED | IMPLEMENTATION NOT ADMITTED | PRODUCTION HOLD
Design parent: ec0e2f561e0818f118b1a1b36cc791e0e4642980. Implementation baseline: base_dev a6bf99107aa64f4eb255b4997ef9417748b05607 (выборочная проверка исходников, не новый runtime admission).
Parent: [D06B](D06B-NIGHT-REPORTS-OPERATIONAL-TRIGGERS-R2.md); [D06A](D06A-RADAR-ADAPTATION-AFTER-R2.md); [D05](D05-EVENT-BUILDER-DELIVERY-R2.md); [D08](D08-DECISIONS-AND-ADMISSION-R2.md). MIC-R1/R2, canonical owner contracts, existing DAG and security gates remain normative.

## 1. Цель и границы
Владелец согласовал Template First: заранее разработанные красивые Excel-шаблоны с таблицами, фильтрами, графиками, печатными листами и при необходимости сводными таблицами. Java заполняет готовый шаблон данными конкретного tenant. Бизнес-расчёты и интерпретации остаются в B4/B5. Radar показывает краткий декларативный вывод, допустимый совет и авторизованную ссылку на артефакт. Excel доступен для самостоятельного анализа; PDF — печатное представление; React/ECharts — интерактивное представление того же результата.
Не создавать новый домен REPORTING, B6/B7, новый расчётный движок, универсальный конструктор отчётов, DSL, второй event bus, второй scheduler, собственное файловое хранилище. B4 FREEZE; B5 roadmap без изменений. Только интеграционные gaps по доказанным потребностям. Не генерировать по одному отчёту для каждого из 112 семейств или все комбинации измерений. Автоматические действия и Radar resumption остаются отдельно gated. Данный документ не разрешает реализацию, подключение зависимостей или production.

## 2. Актуальная опора на код
| Компонент | Наличие/ограничение на baseline |
|---|---|
| AuthorizedAnalyticalReadService | read/readWindow/readMetricWindows; tenant и scope, RR snapshot; текущий день L0 с event_at >= period.startInclusive и event_at < min(period.endExclusive, transaction_timestamp()); наблюдение PARTIAL или MISSING; не настраиваемый точный исторический cutoff |
| PeriodResolver | D/W/DEC/M/Q/H/Y с версионированной tenant IANA zone |
| L2ProjectionService, L3RollupService | BUILDING-only mutation; активный L3 не менять на месте |
| GenerationRebuildService | bounded steps, refreshIfStale, proof; не scheduler |
| BusinessInterpretationEngine | OPEN_PERIOD блокирует полноценную интерпретацию; PARTIAL не становится READY |
| BusinessObservationService/BusinessFactorService | B5.1–B5.4, до 12 anchors / 3 совместимых метрик в одном существующем read port; нет доказанного 24-месячного общего snapshot |
| Files/EVT/Radar/FX | reuse по D06B, требуется inventory реальных owner методов и допусков |
Следствия: 3-часовой накопительный отчёт через L0 возможен численно, но точное расписание, bulk historical read, долгосрочная полнота, автоматическая генерация, service identity, file/report binding и Radar delivery не доказаны. Не объединять независимые запросы в якобы один snapshot. Для единого отчёта 24M весь согласованный план метрик читается под одним квалифицированным snapshot; совпадение одного sourceRevision не доказывает согласованность generation, coverage, calendar и mapping. Потеря snapshot незавершённой сборки означает новый dataset_id, а не продолжение из нового источника. Для 24 месяцев нужен квалифицированный ограниченный bulk read с переиспользованием существующего set-based L3DerivedSeriesService.readPeriods по существующим проекциям или явно раздельные наблюдения с provenance; при отсутствии контракта оставить историю ограниченной. Не повышать лимит readWindow и не обходить owner ACL только ради шаблона.

## 3. Режимы и расписание
| Mode | Trigger | Data | Output |
|---|---|---|---|
| NIGHT | подтверждённое завершение ночных агрегатов tenant/закрытого периода | квалифицированный исторический L3 либо разрешённый L0 | сводка и значимые шаблонные XLSX/PDF, Radar |
| INTRADAY | каждые 3 часа по tenant calendar | накопительный L0 от начала текущих суток до фактического asOf | описательный оперативный Radar, XLSX/PDF при значимом изменении или запросе |
| EVENT | committed qualified domain occurrence | owner evidence (например FX revision) | существующий EVT и Radar; при необходимости XLSX/PDF |
Номинальные точки 03,06,09,12,15,18,21 local; 00:00 — граница суток и night close, не дублировать один и тот же отчёт. Время запуска после границы/готовности, не обещать завершение к точному часу без измерений. Записывать scheduledAt, actual asOf, tenant timezone, calendar version, source revision; delayed run не маркировать как точный cutoff. Для missed slot по умолчанию новый актуальный срез, не выдумывать исторический snapshot; policy уточнить. На DST/смене календаря использовать фактические границы PeriodResolver и уникальный instant. Не обновлять ACTIVE L3, не запускать полную пересборку истории каждые 3 часа. Поздние события учитываются существующим revision/rebuild механизмом.
INTRADAY: [dayStart, actualAsOf), новый накопительный итог замещает предыдущий, не складывать срезы; distinct orders по owner facts, не суммировать distinct из подгрупп. Current day OPEN, PARTIAL/MISSING; показывать «предварительно, на HH:mm». Сравнение только с тем же интервалом дня и сопоставимым календарём/coverage; сейчас такой исторический cutoff не доказан, значит comparison/anomaly/trend/causal inference HOLD, вывод только описательный. Для одинакового cutoff и 3h semantics отдельная методическая квалификация, не подмена READY. Zero только доказанный; MISSING — пустое значение и код качества. Не давать прогноз по неполному дню.

## 4. Технологический выбор (уточняет D06B §8)
| Technology | Role | Priority |
|---|---|---|
| Spring Boot/Java/PostgreSQL, B4/B5 | existing owner calculation and application | REUSE |
| Apache POI + XLSX templates | основной renderer | P0 |
| Spring Batch | предпочтительный кандидат исполнения отчётных jobs в существующем ночном workflow | P0 target |
| React + Apache ECharts | интерактивное представление | P0 target |
| LibreOffice Headless | кандидат Excel → PDF, только после проверки качества, безопасности и runtime | P0 qualification candidate |
| Playwright Java | HTML → PDF fallback/специализированные отчёты | P1 conditional |
| RustFS/File Service, Radar, IAM, EVT | действующие owner контуры | REUSE |
Новая приоритизация заменяет прежние Playwright P0 / Spring Batch условный P1 из D06B §8 только для reporting. Spring Batch не заменяет существующий агрегатор/EVT: запускается его completion event или существующим регламентом, JobRepository/metadata — после dependency/schema admission. Не создавать две очереди одного задания. Технологические версии, dependencies, deployment и библиотечная совместимость ещё открыты; никаких изменений pom/CI/DDL этим ТЗ.

## 5. Каталог 12 шаблонов (design targets, не scope admission)
| ID | Назначение | Данные / ограничения | Заранее подготовленные диаграммы | Режим | Старт |
|---|---|---|---|---|---|
| RM-R01 | Сводка предприятия | qualified measures; не смешивать валюты/units | KPI, line/bar | N, I selective | позже |
| RM-R02 | Динамика продаж | booked/fulfilled basis отдельно; сумма заказов не выручка; average check только qualified numerator/denominator | line, SMA/EMA, YoY | N, I descriptive | pilot |
| RM-R03 | Заказы | BOOKED_ORDERS tenant only, fulfilled scope отдельно; exact distinct | grouped bar, line | N,I | позже |
| RM-R04 | Номенклатура | historical group membership, eligible metric | Pareto, bar | N,I selective | позже |
| RM-R05 | География | allowed KATO/warehouse dimensions; booked geography unavailable | comparative bar | N,I selective | позже |
| RM-R06 | Остатки | W1/W2 current/historical owner balance qualification, не из flow subtraction | stock bar | N,I | gated |
| RM-R07 | Складские обороты | STOCK_IN/OUT per SKU, unit, assigned warehouses | paired bars, line | N,I | позже |
| RM-R08 | ABC/XYZ | W3 approved method and complete history | Pareto, matrix | N | gated |
| RM-R09 | Клиентская активность | C1/C2 current-link access and PII | activity line, bar | N | gated |
| RM-R10 | Аномалии | B5.3 preceding baseline, quality, threshold, no statistical probability claim | line, mean and band | N; I gated | pilot |
| RM-R11 | Факторный анализ | B5.4 fulfilled SKU units, stock in/out; flow is not availability | paired bars, small multiples | N; I gated | pilot |
| RM-R12 | Валюта и цены | CUR-R2C owner revision, actual vs candidate separate | FX line, price comparison | N, E | gated |
N=NIGHT, I=INTRADAY, E=EVENT. Pilot RM-R02/R10/R11 — проектирование шаблонов, не заявление READY historical coverage. Первый production slice требует действительного qualified input; synthetic demos маркировать и не публиковать как фактические tenant conclusions. Остальные шаблоны после доказательства reusable renderer. Не добавлять обязательные семейства к 88/112.

## 6. Workbook contract (макеты заранее)
Общий стиль: белый фон, тёмно-синие заголовки, нейтральная база, акцент только на существенных сигналах; читабельные подписи, цвет плюс текст/иконка, no 3D. Четыре базовых типа макетов (executive KPI, dynamics, structural, anomaly) используют восемь согласованных композиций UI-P01–08 из D06E.3; не отдельные шаблоны на каждый компонент. Dashboard — 1–3 печатные страницы A4 по умолчанию, ориентация по шаблону; детализация отдельными листами/страницами по scope и стабильному sort. Не все строки Details печатать автоматически. Metric Code в заголовках диаграмм, легендах и аналитических столбцах; локализованный Hint RU/KK, EN target по D06E.1. Locale и font policy версионировать. Готовые chart series заранее привязаны к диапазонам; дизайн не создаётся Java-кодом.
| Sheet | Contract |
|---|---|
| Dashboard | title, tenant/display scope, asOf, period, quality, KPI, 1–3 native charts, conclusion, recommended checks, limitations |
| Dynamics | periodStart/End, label, metric, unit/currency, value, prior comparable, delta/growth, quality, baseline/band if admitted |
| Groups | groupId/code/label, grain, value, unit/currency, quality; stable order; top-N only with explicit OTHER if additive |
| Details | bounded scoped rows, filterable Excel Table, explicit truncation and totals semantics; no unauthorized rows in hidden sheets/cache |
| Metadata | template id/version/digest, payload digest, source revision/generation/proof, policy/method/calendar, asOf, coverage, limits, locale, disclaimer |
Optional Pivot/Print/ChartData only if needed. Default named cells REPORT_TITLE, REPORT_DATE, TENANT_NAME, REPORT_SCOPE, AS_OF, KPI_VALUE, KPI_DELTA, CONCLUSION, RECOMMENDATION, QUALITY; tables TBL_DYNAMICS/TBL_GROUPS/TBL_DETAILS. Each template manifest lists required names/sheets, typed columns and chart/print bindings. Use report-specific KPI names if multiple cards; no implicit coordinate magic.
Historical horizon target 24 months: monthly 24, calendar decades 72 for 24 full months, quarter 8 / half-year 4 / year 2 only if aligned; day/week counts determined from actual calendar boundaries (including leap year and partial boundaries). Default rolling 24 closed months for NIGHT, no fabricated missing values or future periods. YoY comparisons use like-for-like periods and approved historical scope; when partial, explicitly identify coverage. Fixed chart range capacity derived per template and period; overflow fail or explicitly paginate, never silently truncate. Fill unused cells with blanks and clear template demo values/caches. Use Excel Tables with autoFilter, resizing qualified; optional Pivot with deterministic data refresh and cache verification. For simple grouped output prefer precomputed aggregates from B4 over pivot-dependent calculations. One workbook for allowed scope; pagination not an authorization boundary.

### 6.1 Pilot layouts
RM-R02: top KPI booked or fulfilled amount, exact order count and quantity if scope/unit allows; avg check omitted until compatible ratio qualified. Main monthly 24-point or aligned 12+12 chart, secondary region/group comparison only for supported metric, brief conclusion and data quality. Rows Dynamics contain actual period bounds and value. No aggregate of distinct across region.
RM-R10: current point, historical SMA, SD_POP, CV, anomaly pattern/quality, line with preceding baseline and threshold band; target excluded from baseline; zero mean abstention, zero variance no invented z-score, volatile suppresses directional candidate. No use of sigma band as confidence interval. B5.3 baseline 3..11 preceding periods distinct from optional 24-month context.
RM-R11: two adjacent equal-duration periods, three panels or same-unit comparisons only if proven; fulfilled SKU units and stock in/out deltas, evidence revision, unranked plausible hypotheses and recommended checks. No claim of stockout/availability from flows, no price-volume decomposition without sources. Missing one metric blocks factor conclusion. Display separate unit labels.

## 7. Минимальный контракт данных и кода
Один immutable payload, рабочее имя MicroReportPayload/ReportPayload (одно понятие; окончательное имя после inventory):
- reportCode, templateVersion/templateDigest, mode, tenantId, authorizedScope, calendarVersion, period, asOf, sourceRevision, generation/proof/evidence references, method/policy versions, currency/UOM, quality;
- KPI/series/group/detail typed rows with per-value quality, row limit/truncation, chart binding keys; precise BigDecimal in source payload, explicit display rounding and Excel numeric precision limitations; no independent calculations;
- conclusion code and localized text, allowed recommended check codes, uncertainty and decisionEligibility, priority from admitted Radar policy; no invented causal facts;
- content digest over canonical versioned representation; timestamps of rendering distinct from analytical asOf. No embedded public URLs, no raw access token, no secrets.
Working implementation names only: ReportTemplateRegistry (classpath immutable trusted manifest), ReportDataProvider (existing authorized B5/owner reads), ExcelTemplateRenderer (POI), PdfConverter (optional), ReportBatchConfiguration, ReportPublisher (existing Files/Radar). Reuse existing matching components instead of creating duplicate classes. No generic expression engine, custom SQL per XLSX, macros, arbitrary tenant-uploaded templates or UI report builder. No synthetic actor; actual service identity and grants require owner admission.
Resource proposal: src/main/resources/reports/templates/RM-R02-v1.xlsx etc, reports/registry.yml. Manifest versioned together with workbook, SHA-256 pinned. Example declarative binding, not executable YAML DSL:
~~~yaml
reports:
  - code: RM-R02
    version: 1
    template: RM-R02-v1.xlsx
    historyMonths: 24
    modes: [NIGHT, INTRADAY]
    periods: [D, W, DEC, M, Q, H, Y]
    outputs: [XLSX, PDF]
    sheets:
      dashboard: Dashboard
      dynamics: Dynamics
      groups: Groups
      details: Details
      metadata: Metadata
    admission: QUALIFIED_INPUT_ONLY
~~~
Modes list is capability target, not activation permission; intraday descriptive exception explicit in policy. Registry maps existing pattern IDs after inventory; templates not 112 rules. No new business methodology encoded in YAML. Version/digest mismatch fails closed.

## 8. Оркестрация: Spring Batch, 10 заданий
Existing aggregation completion → candidate selection (qualified patterns, bounded scope) → existing scheduler/EVT dispatch → Spring Batch job → data snapshot/payload → fill trusted XLSX copy → optional PDF → owner file completion/scan → Radar publication. EVENT priority not dependent on batch. Select one authority for logical job identity; JobRepository persists execution/restart after admission. No dual scheduler or dual outbox for same lifecycle.
Initial cluster-wide max 10 concurrently running report jobs, configurable, not 10 per pod or per tenant. This is executor concurrency, not chunk(10) nor maximum queue depth. Enforce distributed bound via existing admitted coordination/claim mechanism; a local executor alone insufficient. Per-tenant fairness/limits, bounded queue, backpressure, event priority, reserve capacity for interactive DB queries; initial max PDF converters 2 independently configurable proposal. Prevent overlapping same logical scope/slot duplicate jobs, coalesce superseded pending intraday jobs only with audit; no silent loss of nightly/evidence obligations. No long database transaction across rendering/upload. Capture immutable payload under authorized snapshot, close DB transaction, render, reauthorize publication and reads. Bound workbook rows/pages/bytes, worker duration, temp disk/native heap. On cancellation/timeouts release capacity and cleanup. Job key = tenant + mode/occurrence + scope + reportCode + period/cutoff + source revision + method/policy + template version/digest; artifact adds format. Scheduled slot key distinct from immutable report revision. Retry same logical payload, not different data with same identity. If current authorization/coverage/revision changed, re-evaluate, new version or blocked/stale, not overwrite old file. No cross-tenant payload or converter context reuse.
Proposed settings (not actual properties):
~~~yaml
rm:
  reporting:
    enabled: false
    night:
      after-aggregation: true
      max-parallel-jobs: 10
      history-months: 24
    intraday:
      enabled: false
      interval: PT3H
      source: L0
      significant-only: true
    pdf:
      enabled: false
      max-parallel-conversions: 2
    storage:
      provider: existing-file-service
~~~
Disabled until implementation/admission. Confirm actual config and integration with existing scheduler before coding. Priority for FX event, then current intraday, then historical bulk is initial policy subject to fairness/night deadline qualification.

## 9. Rendering and PDF
POI XSSFWorkbook opens independent immutable template copy per job; validate sheets/names/types, write typed numeric/date/text values, resize tables/filters only with qualified behavior, clear stale cells, retain formatting/charts and print areas. FormulaEvaluator only for supported trusted formulas; force recalculation on open is not proof of cached values or PDF correctness. Verify chart cache, chart ranges, pivot cache and formula results before publication; no fake demo data. Large data SXSSF only after independent compatibility/resource test; otherwise paginate/export bounded XLSX. Exact business calculations in B4/B5; Excel numeric precision cannot faithfully encode arbitrary BigDecimal, preserve exact audit value separately where required, display rounding explicit. No macros, external links, formula injection, external data refresh, hidden unauthorized rows or PII.
Preferred PDF trial: LibreOffice headless with calc_pdf_Export, per-job isolated user profile and temp dirs, no network, bounded OS process/resources, trusted fonts, kill/cleanup on timeout, validated output. Do not assume fidelity with Excel charts/pivots, fonts or print area. Example command shape:
~~~sh
soffice --headless -env:UserInstallation=file:///isolated/job/profile --convert-to pdf:calc_pdf_Export --outdir /isolated/job/out /isolated/job/report.xlsx
~~~
Paths generated by worker, no shell interpolation of tenant data. PDF based on explicit print sheets, not every detail row; verify correct sheet selection, no hidden data leaks, A4 pagination, clipping, chart legends, ru/kk glyphs and values. If PDF fidelity fails, publish qualified XLSX only if policy permits, distinguish PDF failure; Playwright Java HTML renderer fallback uses same payload, local trusted HTML/ECharts SVG, isolated context, no external URLs. Do not duplicate all designs in HTML unless justified. React/ECharts independent interactive view of same data, not Excel execution.

## 10. Publication, Radar and security
Use D06B §5 and existing File Service/RustFS owner paths; no direct storage public URL or bypass scanning. File owner type/report binding and actor/service authorization must be qualified before live generation. Generated XLSX/PDF is immutable, versioned, file digest. Stage/upload/scan/complete/attach then confirm available and publish Radar report reference. Crash between storage and DB: existing idempotent resume/reclaim/compensating policy, not distributed transaction promise. If optional PDF fails, status per format; link only confirmed artifact. Revocation/tenant suspension/warehouse reassignment blocks summary/count/details/download at current fence. On stale source revision mark old insight obsolete or superseded according to owner policy; never mutate original report content. Retention approved separately.
Radar card contains short declarative title, period/asOf, scope, observation/KPI, quality, priority only when admitted, conclusion, recommended check, evidence ref and authorized XLSX/PDF links. User-facing text 1–3 sentences plus action; analytical appendix in file. No recommendation for unavailable evidence except explicit diagnostic check. No autonomous commercial command. Report link is owner-bound identifier resolved after fresh authorization. Existing Radar resumption/CUR gates remain.

## 11. Acceptance matrix (proposed detailed cases mapped to D06B)
| ID | Evidence / independent oracle | Parent |
|---|---|---|
| TPL-01 | Each pilot template manifest, named ranges, typed values, chart binding, filter and print layout, digest; missing/wrong sheet fails closed | NIGHT-02 |
| TPL-02 | Two tenants with different rows; fill blank/zero/large/overflow, no demo value, no stale cache, compare to independent expected data | NIGHT-02, SEC-01 |
| TPL-03 | 24-month boundary including leap/calendar change, explicit MISSING and unequal periods; no fake READY | NIGHT-01/02 |
| TPL-04 | Native charts/formulas/pivot if used match data on actual workbook and PDF; ru/kk print screenshot checks, no cropped labels or unauthorized sheets | NIGHT-02 |
| TPL-05 | B5.3 target excluded, variance=0/zero mean/noise; B5.4 ambiguous flow, mixed scope/revision blocked; payload and rendered values equal hand oracle | NIGHT-02 |
| INTRA-01 | 03:00/06:00/… actual asOf, late event, tenant zone; independent owner SQL event_at bounds; count distinct not sum of sprints | NIGHT-01/04 |
| INTRA-02 | Open day PARTIAL/MISSING; delayed run truthful asOf; unmatched historical cutoff cannot produce trend/anomaly | NIGHT-01/02 |
| INTRA-03 | Concurrent night/slot, replay after failure, missed slot, new day rollover; no duplicate and no ACTIVE L3 mutation | NIGHT-03/04 |
| JOB-01 | Multi-instance 10 cluster-wide in flight, per-tenant fairness, PDF limit; repeat/restart/timeout no duplicate | NIGHT-03, PERF-01 |
| JOB-02 | Crash before/after payload, render, upload/scan/attach, Radar publish; stable key and no broken links | NIGHT-03 |
| SEC-02 | Cross-tenant read and render, warehouse revoke/suspend during publication and download; no hidden/cache leaks; formula/external URL rejection | SEC-01 |
| FX-04 | Event bypasses night; proposed/approved/executed distinct; no report command authority, replay idempotent | FX-01/02/03 |
| PERF-02 | Representative tenant/workbook sizes, concurrent online queries/FX, heap/native/disk/CPU/backlog/p95/p99; compare 1/2/5/10 jobs, set limits after evidence | PERF-01 |
Cases are design requirements, not executed tests or new bank families. Exact-SHA service/PG/security, artifact visual UAT, dependency and operational admission after implementation. 2-year volume and morning completion SLA not established. Do not claim readiness until actual data coverage and authorization proven.

## 12. Implementation handoff — no new architectural stages
1. Reconcile base_dev, current B5 and owner/transport/Radar gates; inventory actual report-compatible reads, scheduler/worker, Files and FX/EVT; identify only gaps.
2. Prepare RM-R02/R10/R11 workbook fixtures with synthetic qualified inputs, manifest and hand oracle; one reusable POI renderer; open in supported spreadsheet clients.
3. Bind actual authorized B5/owner data; no hidden history promotion, add bounded historical input only where required and admitted.
4. Attach Spring Batch to existing completion/trigger, 10-cluster cap with restart/idempotency; qualify PDF candidate separately; no new domain.
5. File Service/Radar integration after owner gates; intraday L0 3-hour descriptive slice and qualification, comparison only after separate methodology proof.
6. Expand remaining nine templates only on proven renderer and qualifying sources. Existing B5 implementation continues independently. No automatic merge/design branch code changes.
Open decisions: precise approved versions, actual report/worker owner grant, historical read contract, timezone/business day and missed slots, template locale/print profiles, storage retention, report scope and queue resource budgets, PDF fidelity, admitted pattern mapping, production threshold/SLA. Initial 10 jobs, 24 months, 3h and template-first are target choices; 2 PDF workers, sheet names, report IDs, registry/config shapes are engineering proposals requiring qualification. No actual XLSX template binaries or Java code supplied by this design-only commit.


## D06D — Unified Analytical Dataset & Pattern Tickets (2026-11-26)
[Сквозной архитектурный контракт](D06D-UNIFIED-ANALYTICAL-PATTERN-TICKETS-R2.md) фиксирует согласованное направление B5.5/D06B/D06C/Radar: 24M history / 12M display, допустимые многомерные scope, относительные паттерны, Pattern Ticket → Information Ticket, единые labels и template bindings, night + 3h intraday. Не меняет текущий roadmap, B4 FREEZE, MIC, existing gates, descriptive-only intraday до квалификации сопоставимого cutoff и отдельный Radar admission. DESIGN ONLY / PRODUCTION HOLD.


## D06E.1 — Synthetic history and Metric Code / Hint (2026-11-26)
[Обязательное уточнение контракта представления и тестовых данных](D06E.1-SYNTHETIC-HISTORY-AND-METRIC-PRESENTATION-R2.md): 4 synthetic tenants (25/15/10/6 closed months), 12 B4.8 pilot analytical metrics at eligible grain (not a catalog ceiling), English geography fixture labels, chart title/legend/header use Metric Code; localized purpose as Hint (static in PDF). Reuse existing registry and payload. DESIGN ONLY, no runtime admission or change to B4 FREEZE/DAG.


## D06E.3 — Analytical Presentation & Progressive Business Insights (2026-11-26)
[Согласованное владельцем дополнение](D06E.3-ANALYTICAL-PRESENTATION-PROGRESSIVE-INSIGHTS-R2.md) фиксирует восемь переиспользуемых композиций: Morning Brief, Business Pulse, Heatmap, Top Winners & Losers, Drill-Down, Contribution Analysis, Historical Range Selector, What Changed. Один qualified payload, Metric Code + Hint, без новых движков/доменов/шаблонов на каждый вариант. D06E.2 сохранён; qualification trace сформирован; D08 global admission и runtime qualification остаются OPEN. DESIGN ONLY / B4 FREEZE / PRODUCTION HOLD.

## Current metric baseline — D06E.4 reconciliation (2026-11-26)
[Audited inventory](D06E.4-AGGREGATE-CAPABILITY-INVENTORY-R2.md) / [JSON](D06E.4-AGGREGATE-CAPABILITY-INVENTORY-R2.json) is the current aggregate capability catalog: 12 B4.8 pilot codes, 8 owner registry codes (7 L1 materialized, 1 live), 11 numerical operator codes, 22 unadmitted candidates; 53 catalog entries, not 53 unique business metrics. Pilot is not a ceiling. Additional registered capabilities are admitted individually after semantic, source, applicability, authorization and independent oracle qualification. No new architecture or automatic runtime admission. Reuse existing L3DerivedSeriesService.readPeriods set-based extraction within a qualified authorized bulk contract; existing 12-anchor/3-metric factor path remains unchanged. Product concept and B4 FROZEN; D08 global admission OPEN; B5.5 NEXT; Radar and production HOLD.

## D06E.5 — Dynamic Analytical Contracts & Visualization Matrix (2026-11-26)
[Reverse report-to-metric design](D06E.5-DYNAMIC-ANALYTICAL-CONTRACTS-VISUALIZATION-R2.md) defines 12 reusable logical data contracts DC-01–12 for the existing 12 reports and 8 UI compositions, stable typed payload bindings, chart interpretation safeguards and qualification mapping. D06E.4 inventory unchanged; no new metric admission, engine, DAG stage or test family. Product concept/B4 FREEZE, D08 OPEN, B5.5 NEXT, Radar/production HOLD.

## D06E.5.1 — Adaptive Ranked Distribution (2026-11-26)
[Owner-agreed grouping contract](D06E.5.1-ADAPTIVE-RANKED-DISTRIBUTION-R2.md): direct display ≤5, otherwise up to seven bands with 15% step; COUNT_PERCENTILE default / CUMULATIVE_VALUE_SHARE alternative, FIXED/DYNAMIC comparison, deterministic ranking, exact additive reconciliation and authorized drill-down. Reuse DC-05/06/08/09 and existing payload/templates; no new engine or metric admission. Product concept/B4 FREEZE, B5.5 NEXT, D08 OPEN, Radar/production HOLD.


## Historical report detail and evidence
Default HISTORICAL_EVIDENCE drill-down must reproduce the published report's exact authorized scope, metric/method, period/asOf, payload digest, generation/proof/source revision manifest (or owner-source evidence), and grouping policy/membership digest where applicable. Reauthorize every request against current permissions, verify the complete historical result and detail totals or reject; retained exact details or provably reproducible pinned evidence may be used. A revision number/digest alone is not a historical snapshot. If evidence expired, cannot be reconstructed, membership mismatches or permissions no longer cover the original scope, fail closed with localized unavailable/forbidden reason; no substitution of current facts, no silent narrower denominator. Offer explicit LATEST_DATA as a new authorized query/payload with new asOf and revision, clearly not historical drill-down. Do not expose revoked detail or counts; no mandatory new storage or indefinite generation retention. Paginate with pinned manifest, fail on drift. Previously downloaded files cannot be remotely revoked; future server reads/downloads remain subject to existing ACL.

For ranked Groups/Details, FULL_DISTRIBUTION uses the entire authorized metric-applicable expected population, including eligible proven-zero rows, before materiality or top-N. Establish scope and interval completeness independently; missing/partial expected rows or budget exhaustion block complete-distribution claims, not silently shrink the denominator. MATERIAL_FINDINGS evaluates all required applicable scopes and applies admitted materiality only to qualified findings/ranking/ticket selection; it never supplies the denominator of FULL_DISTRIBUTION. Both reuse compatible evidence. These are logical selection purposes, not new engines or public enums.

## D06E.5.2 — Tenant-owned reports and template readiness (2026-11-26)
[Ownership, metadata, table bindings and audited template matrix](D06E.5.2-TENANT-REPORT-OWNERSHIP-METADATA-TEMPLATES-R2.md): single tenant per report, tenant-managed audience intersected with current owner/IAM/file permissions, immutable actual parameters/evidence and separate mutable access policy, mandatory chart/table parity. 8 aggregate-backed / 1 live owner-backed / 1 method-gated / 2 owner-gated templates; zero runtime-qualified. Preserve pilot R02→R10→R11; all 12 IDs reserved. Existing FileOwnerAccessPort CRM-only report binding gap explicit; no physical schema admission, no scope expansion. B4 FREEZE, B5.5 NEXT, D08 OPEN, Radar/production HOLD.

## D06E.5.3 — Partitioned Report Staging (2026-11-26)
[Owner-agreed staging and partition lifecycle](D06E.5.3-PARTITIONED-REPORT-STAGING-ARTIFACT-LIFECYCLE-R2.md): existing PostgreSQL, daily RANGE by allocated UTC staging_date; automatically ensure current and next two days through existing coordinated maintenance (lookahead proposal), delete only entire eligible sealed child partitions after all consumers terminal and successful publication + at least 24h, no active pins or unresolved evidence. Durable report metadata/ACL and File Service artifacts survive staging cleanup; historical drill-down fails closed if exact evidence expired. Temporary delivery staging only, not new analytical bank or B4 change. Physical implementation/DDL not admitted; B5.5 NEXT, D08 OPEN, Radar/production HOLD.

## Report mockup artifacts — MOCK-0.1 (2026-11-26)
[12 synthetic XLSX visual mockups](report-templates/README.md) and [manifest](report-templates/manifest.json) register actual design binary artifacts (14 native charts, tables, Metadata, 24M history in R01/R02, 500 SKU/7 rank bands in R04). Local OOXML and chart cache parity checks only; no production template binding, actual tenant data, PDF/Excel/POI runtime qualification or source/ACL admission. Existing R02→R10→R11 pilot, B5.5 NEXT, B4 FREEZE, D08 OPEN, Radar/production HOLD.

## RM-IC-R2 source alignment and roadmap (2026-11-26)
[Project alignment and roadmap](RM-IC-R2-PROJECT-ALIGNMENT-ROADMAP.md) is the current design handoff against base_dev a6bf99107aa64f4eb255b4997ef9417748b05607. Existing S0–S6 and operative DAG R1.2 (33 nodes/54 edges) preserved; older descriptive counts are historical, not current topology. MPC-1 in D06E.1 and D06E.4 JSON covers 53 registered entries without runtime admission. B5.5 NEXT; coverage before bulk/pattern/report publication; C/W/E lanes retain own gates, D08 OPEN, Radar/production HOLD. Only design branch updated.

## Final technical reconciliation — 2026-11-26
Durable reporting lifecycle, initial cluster-wide ceiling 10 jobs, fairness, bounded queue, retry/recovery and verified completion mandatory. Spring Batch preferred candidate conditional on existing job inventory and dependency/schema admission; if existing admitted mechanism meets same contract reuse it. One job authority, no second scheduler/queue. Supersedes unconditional library wording. 3h cumulative analytical refresh does not require XLSX/PDF per tenant/scope/slot; files scheduled, on demand or admitted event only.


## 2026-11-27 — RM-INT-SCHED-R1 mini specification
[Analytical lifecycle scheduling](RM-INT-SCHED-R1.md) records the agreed direction and proposed Java/SQL/transaction/test design, pending explicit implementation approval. Source base_dev 16a8837: 24/24 workflows GREEN, Exact-SHA 45924539232 PASS, targeted 57/57. COR-01.7 complete, COR-01.8 architectural acceptance prerequisite. Existing Canonical Fact capture and B4 rebuild/activation reused; one conditional scheduler, one proposed operational job table, NIGHT first, INTRADAY 3h L0 separately gated by system identity/durable result. No second engine or Spring Batch for aggregation; D06C reporting dependency decision remains conditional and separate. ACTIVE immutable; global stale proof/full generation replay measured before expansion; historical coverage and 24M bulk remain separate. Integration prerequisite for existing S5/NIGHT/INTRA, not new DAG node. No runtime code, migration, production/Radar admission. Approval required for proposed schema, narrow fenced activation adapter, defaults and pilot scope; B4/API FREEZE otherwise intact.


## 2026-11-27 — RM-INT-SCHED-R1 v1.1 multi-pod refinement
[Updated mini-spec](RM-INT-SCHED-R1.md#6a-multi-pod-scheduling-scale-out-and-crash-recovery-normative-v11) supersedes the single RUNNING cluster restriction in v1. All pods may discover/dispatch, one bounded local worker per pod; proposed cluster logical capacity 2, DB ceiling 4, max one RUNNING per tenant across modes. Same operational job table: numbered slots + partial unique indexes, SKIP LOCKED, expired same-job/generation takeover, attempt fencing, atomic activation/completion. At-least-once attempts, no physical exactly-once guarantee; lease-lost bounded step may finish under existing locks. No second scheduler/leader/Redis/broker or additional tables. Same-tenant conflicts and pod rollout/drain, fairness, capacity/DB-pool workload tests explicitly required. No implementation admission, DDL, runtime or CI changes. Initial limits and actual production identity/roles still require approval. Existing D06C reporting limit is separate; no dual queue. B4/API FREEZE, D08 OPEN, production HOLD.

## RM-ARS-R1 — уточнение поставки и ресурсов, 2026-11-28
В [основной контракт хранения](../RM-ARS-R1-TENANT-AWARE-ANALYTICAL-REPORTING-STORAGE.md) внесены freeze перед проверкой, canonical encoding v1, отдельные build/consumer tokens и публикация через общий CAS потребителя. Dataset READY подтверждает целостность сериализации, а аналитический DataQuality.READY требует своей доказательной базы. Текущий readMetricWindows удерживает внешнее IAM-соединение и внутреннее RR-соединение: лимит отчётных jobs не равен числу допустимых одновременных extractors, нужен бюджет 2×extractors плюс измеренный резерв для команд/revoke/recovery (и отдельный учёт streaming sink). Полный файл проходит актуальные IAM/owner/file проверки; publish+receipt+PENDING_ACK атомарны, ACK отдельно, очистка до ACK запрещена. [Проверенные проектные примеры и оставшиеся runtime-проверки](RM-ARS-R1-DESIGN-QUALIFICATION.md) привязаны к существующим CC/UAP/JOB/SEC/PERF/TPL; новые этапы/семейства не добавлены.
