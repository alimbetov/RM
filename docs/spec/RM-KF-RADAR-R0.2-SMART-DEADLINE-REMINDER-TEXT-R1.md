# RM-KF-RADAR-R0.2 — Smart Deadline Reminder Text
## R1.0 — Dynamic Countdown, Timeliness Text & Calendar Presentation

**Дата:** 2026-11-24. **Ветка:** base_dev. **Source review SHA:** dc52bb247cde80be4f61489ab23d37e3472a65d6.
**Статус:** SPECIFIED / DOC-ONLY / IMPLEMENTATION GATE PENDING / PRODUCTION HOLD.
**Owner request:** отдельное ТЗ R0.2, дополнить согласованную цветовую классификацию текстом «Осталось 3 дн.», сохранить фильтры, пагинацию и Outlook-style календарь из R0.1 R1.1.
**Scope distinction:** ранее в [R0.1](RM-KF-RADAR-R0.1-OVERDUE-TASK-SIGNAL-R1.md) R0.2 обозначал будущие сигналы истечения CRM-договоров. Настоящий R0.2-SMART-DEADLINE-REMINDER-TEXT — только представление сроков TASK; CRM contract-expiration остается PARKED под отдельным будущим идентификатором (предложение R0.2-CONTRACT, требует отдельного решения). Не присваивать этому ТЗ право реализации иных сигналов.
**Admission:** [MVP Freeze](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md) CR-MVP-KF-RADAR-01/01A разрешает R0.1 read-only service slice. Запрос на создание R0.2 ТЗ не трактуется как снятие freeze для нового Java/UI. Документирование разрешено; отдельное scope decision перед реализацией R0.2. G1/G2, BSR и production gates сохраняются; only base_dev, no main.

## 1. Цель, scope и non-goals

На календарной карточке, в agenda/list и очереди Radar рядом с цветом и текстом статуса показать понятный остаток времени или историческую величину досрочности/просрочки. Срок меняется при обновлении данных, активный countdown обновляется визуально без бизнес-записей. Сотрудник видит «Осталось 3 дн.», «Осталось 2 ч.», «Срок наступил», «Просрочено на 1 дн.», «На 5 дн. раньше». Не путать напоминальный текст с уведомлением: не создавать TASK, scheduler, outbox, delivery, push/email/SMS, SLA escalation, ML, новые таблицы, индексы, миграции, tenant settings или публичные endpoints. Нет синхронизации с Outlook. UI design now; real UI after G2 authorization.

## 2. Источники и наследуемые инварианты

R0.1 R1.1 §3–§11 — единственный источник определений overdue, timeliness, EARLY_50, effective/original due и scope. Проверенные source contracts: TaskService, TaskState, crm.task_item(due_at,created_at,row_version), crm.task_event(COMPLETED.occurred_at, due change history). R0.2 не вычисляет повторно COMPLETED из updated_at или ARCHIVED timestamp. Не копирует историю, не добавляет SQL join ради presentation. Использовать уже квалифицированные R0.1 DTO/owner-side read contracts; они пока SPEC, не утверждать реализацию. Для активной задачи единое trusted evaluatedAt на страницу, normalized to microseconds per R0.1; dueAt and completion timestamps authoritative. All timing values refer to elapsed time, not local calendar dates. R0.1 severity ATTENTION/HIGH/CRITICAL (24/72h) and DUE_SOON <=24h remain independent from text. TASK lifecycle and permissions unchanged.

## 3. Presentation taxonomy and precedence

| Source timeliness / situation | Text code | ru-RU compact example | Semantic color |
|---|---|---|---|
| EARLY_50 | EARLY_BY | На 5 дн. раньше · 50%+ | existing dark green |
| ON_TIME, completed < effectiveDue | EARLY_BY | На 2 дн. раньше | existing green |
| ON_TIME, completed = effectiveDue | COMPLETED_ON_DEADLINE | Выполнено точно в срок | existing green |
| COMPLETED_LATE | COMPLETED_LATE_BY | Опоздание на 5 ч. | existing orange |
| ACTIVE / DUE_SOON, due > evaluatedAt | REMAINING | Осталось 3 дн. / 18 ч. | existing blue/yellow respectively |
| assigned active, due = evaluatedAt | DUE_NOW | Срок наступил | existing yellow |
| OVERDUE | OVERDUE_BY | Просрочено на 2 дн. | existing red |
| CANCELLED | TASK_CANCELLED | Задача отменена | existing gray |
| QUEUED | TASK_QUEUED | Ожидает назначения | existing gray |
| UNSCHEDULED, due null | NO_DEADLINE | Срок не установлен | existing gray |
| UNSCHEDULED, due present but unassigned | NO_ASSIGNEE | Исполнитель не назначен | existing gray |
| UNKNOWN | TIMING_UNKNOWN | Недостаточно данных о сроке | existing gray |

Terminal first, then neutral queued/unassigned, then active. ARCHIVED uses original completion timeliness, plus separate archived lifecycle label; never restart countdown. COMPLETED_LATE is not active OVERDUE. A known completion exactly at deadline uses on-time label, not «0 мин. раньше». If valid original deadline was missed after reschedule, retain independent warning «Первоначальный срок нарушен» and original due date; green for effective deadline does not conceal warning. If original evidence unknown, label «Первоначальный срок не подтверждён» when details requested; no invented date. Color remains from R0.1; text is not a separate SLA or severity rule.

## 4. Time arithmetic and display rounding (proposed R1.0)

Authoritative classification from R0.1 uses exact Instants at microsecond precision. R0.2 displays derived duration magnitudes only:
- remaining = dueAt - evaluatedAt for active future tasks;
- overdue = evaluatedAt - dueAt for active overdue;
- early = effectiveDueAt - completedAt for valid early completion;
- late = completedAt - effectiveDueAt for valid late completion.
- Completion labels freeze at completedAt; use no current-time calculation for completed/archived. Positive magnitude required for BY/REMAINING; due equality handled separately. Unknown evidence never converted to zero. No epoch subtraction overflow: Duration or exact integer arithmetic; validate supported timestamp range; no floating point.
- Select largest applicable unit at actual magnitude: DAY >=86400s, HOUR >=3600s, MINUTE >=60s, LESS_THAN_MINUTE for 0 < magnitude <60s. Floor count in selected unit for all positive durations; e.g. 3d23h -> 3 дн., 23h59m -> 23 ч., 59m59s -> 59 мин. Do not round 23h to 1 day. Floor labels mean elapsed/full remaining units, approximate compact display, not exact deadline. Tooltip/detail shows exact timestamp and optionally full day/hour/minute breakdown. One-day means 24 elapsed hours, even during DST. A positive sub-minute duration gets «Осталось менее 1 мин.» or «Просрочено менее чем на 1 мин.»; never label «0 мин.» or confuse with equality.
- No calendar-day difference used for magnitude. Display date/time uses selected authorized IANA ZoneId and locale per R0.1; timezone changes must not affect remaining/overdue amount or EARLY_50. Week/month grouping still uses local day boundaries per R0.1.
- For EARLY_50 retain R0.1 exact threshold, optional displayed savingsPercent with R0.1 rounding; do not reclassify from rounded text. No separate countdown threshold.

| Exact duration or state | Example |
|---|---|
| remaining 72h | Осталось 3 дн. |
| remaining 71h59m | Осталось 2 дн. (exact deadline in detail) |
| remaining 24h | Осталось 1 дн.; DUE_SOON per R0.1 |
| remaining 23h59m | Осталось 23 ч. |
| remaining 60s | Осталось 1 мин. |
| remaining 59s or 1 microsecond | Осталось менее 1 мин. |
| due == evaluatedAt | Срок наступил |
| overdue 1 microsecond | Просрочено менее чем на 1 мин. |
| overdue 48h | Просрочено на 2 дн. |
| completed 5h late | Опоздание на 5 ч., stable |
| completed 0s relative to due | Выполнено точно в срок |
| completed 5d early, plan 10d | На 5 дн. раньше · 50%+ |

## 5. Typed contract and localization

Proposed pure presentation function: DeadlineTextEvaluator.describe(existing R0.1 task projection, evaluatedAt). No SQL, authorization bypass, network or writes. An equivalent small typed extension of existing DTO is preferred to a new service hierarchy. Only the existing authorized task read facade can expose this projection; standalone pure evaluator is not a query entrypoint.

Proposed immutable DeadlineTextDescriptor: textCode enum (table above), unit enum DAY/HOUR/MINUTE/LESS_THAN_MINUTE/NONE, value nullable nonnegative long (positive when DAY/HOUR/MINUTE, absent otherwise), precision EXACT_BOUNDARY/COMPACT_FLOOR/LESS_THAN_UNIT/NOT_APPLICABLE, effectiveDueAt nullable Instant, evaluatedAt Instant, sourceVersion long; historical completedAt/savingsPercent remain from R0.1, do not duplicate if composed. Source timestamps travel with typed data to the admitted UI; no localized DB values. No free-text templates, PII or user data embedded in message, no new grant. Error on internally inconsistent enum/unit shape; safe UNKNOWN for missing source evidence, not silent zero.

Localization uses approved resource bundles/client locale, same textCode and numeric value across ru-RU/kk-KZ/en-US; fallback ru-RU proposed (explicit locale configuration wins). Use exact templates and grammar, plural forms in English, Unicode. RU compact day «дн.», hour «ч.», minute «мин.»; KK proposed «3 күн қалды», «2 сағ. қалды», «1 мин. қалды», «Мерзімі жетті», «2 күнге кешікті»; EN «3 days left», «1 hour left», «Less than 1 min left», «Due now», «Overdue by 2 days». Completed early/late, neutral, unknown, original deadline warning and all sub-minute variants must exist in all three locales and be reviewed; examples are not exhaustive translations or approved localization. Long readable accessibility labels localized (including Russian 1 день/2 дня/5 дней); no HTML in message resource. UI fallback must not render untrusted HTML. No new locale persistence. Date detail includes timezone and seconds where needed, same authoritative timestamp.

## 6. Calendar, filters, pagination and refresh

R0.1 §11.3–11.4 remains normative: Day, Work Week, Week, Month, Agenda, List; DUE/COMPLETED bases, tenant/actor scope, status/timeliness filters before LIMIT, keyset 20/50/100, range navigation. Text enrichment only after authorized selection, no text-based postfilter or sorting that changes page membership; filter on typed codes/timeliness via existing R0.1 predicate. No new counts, full-table scan or N+1. Countdown text is shown beside lifecycle/timeliness label; on compact month view text may truncate visually with tooltip/accessible full string. Detail includes exact due, effective/original due distinction, completion and source version through existing authorized detail path. Null deadline not forced into a calendar date. No task duration invented.

UI refresh design (G2 only): compute relative time from last trusted evaluatedAt and monotonic elapsed clock, not user wall-clock as authority; use max(0, monotonic elapsed) and retain sourceVersion. All active items can update locally on visible screen every 60s (or at earlier due boundary), without per-task network polls; request fresh authorized page on focus, manual refresh, filter/view change and proposed max cache age 60s while visible, coalesced per page, no background delivery. When data not refreshed, explicitly show «Данные на … / требуется обновление» after age threshold; do not promote stale local countdown into confirmed OVERDUE classification or move item across filtered pages. At due/24h boundary locally show tentative relative text only if aligned with source semantics, mark pending refresh and request fresh authorized result; authoritative status/color and filter membership from new server result. If refresh denied or expired, clear displayed task data and cursor; on transient error show stale indicator and explicit retry, no fake empty. Stop timers when view hidden; no durable client cache of tenant data. Completed labels stable unless source revision changes. Backend never runs a reminder scheduler and does not persist countdown; page evaluatedAt authoritative, no client-supplied asOf.

## 7. Acceptance matrix — 24 new scenario families

All 60 R0.1 RAD/CAL families and existing TASK 53 regression remain. REM-01…24 are new planned scenario families, not executed tests. UI-only subcases deferred to G2, not counted as backend PASS.

| ID | Scenario | Expected |
|---|---|---|
| REM-01 | 72h, 71h59m future | 3 дн., 2 дн.; exact due unchanged |
| REM-02 | 24h and one microsecond below | 1 дн., 23 ч.; correct R0.1 color |
| REM-03 | 1h and 1min boundaries | exact unit selection |
| REM-04 | sub-minute future including 1 microsecond | less than 1 min, not zero |
| REM-05 | equality due == evaluatedAt | DUE_NOW, not overdue |
| REM-06 | sub-minute overdue incl 1 microsecond | OVERDUE_BY less than 1 min |
| REM-07 | overdue 2d, 5h, 3min | correct magnitudes |
| REM-08 | complete exactly at deadline | stable COMPLETED_ON_DEADLINE |
| REM-09 | completed early >=50% and just below | correct existing green code, actual duration |
| REM-10 | completed late, later refresh/archive | frozen late duration, orange |
| REM-11 | cancelled, queued, no assignee/no due | neutral, no active countdown |
| REM-12 | invalid/missing completion, unknown due | UNKNOWN, no false green |
| REM-13 | original deadline missed after reschedule | independent warning preserved |
| REM-14 | DST/zone change, midnight, leap day | elapsed magnitude unchanged, local detail correct |
| REM-15 | long duration, microsecond precision | no overflow/rounded eligibility |
| REM-16 | RU/KK/EN all keys/plurals/sub-minute | valid text, same numeric result |
| REM-17 | DTO enum/unit/precision consistency | typed allowlist, no PII or raw HTML |
| REM-18 | concurrent reschedule/complete read | coherent sourceVersion/due/text |
| REM-19 | role revoke, tenant switch, refresh | deny/clear stale page, no scope expansion |
| REM-20 | keyset/filter/calendar ranges | identical membership to R0.1, no N+1 or DML |
| REM-21 | active timer crosses deadline/24h | G2: pending refresh, server authority |
| REM-22 | stale page/offline/hidden tab, clock jump | G2: monotonic elapsed, stale indicator, no background sends |
| REM-23 | month/week/list compact text, accessible labels | G2: no color-only meaning, exact detail accessible |
| REM-24 | load 20/50/100 rows and refresh | bounded enrichment O(page size), coalesced requests, unchanged SQL count |

Unit tests for pure formatter/evaluator; integration for source revision and scope using actual R0.1 read contract, independent PG verification of no DML and query count; no new DB fixtures beyond synthetic existing schema. Measure enrichment cost separately and full existing R0.1 p95 at 100k fixture; proposed no measurable >10% p95 regression under comparable workload, no new production SLA. G2 browser checks only after authorization. Exact-SHA evidence, test mapping and no mandatory skip; preserve all original CI workflows.

## 8. Roadmap and DoD

R0.2-A: documentation and review (current); resolve R0.2 identifier collision and obtain narrow implementation authorization; qualify R0.1 prerequisites (read projections, timing, source version, security, filtering) before code. R0.2-B: pure typed evaluator/descriptor and unit tests, no database changes. R0.2-C: integrate existing admitted service reads and qualification, no new external route; verify 24 families' applicable service subcases, R0.1 full regression and exact SHA. G2-RADAR-TEXT: shared calendar/list rendering, resource bundles, local refresh/accessibility; only after G1 and approved UI scope. No claim of executed tests or production admission. Done means each valid record has correct localized deadline text without false green, missing evidence or scope leak; exact date remains accessible, no background notifications, no data changes or paging/filter regressions.

**User-confirmed:** separate R0.2 ТЗ, retain colors and add reminder text (e.g. «Осталось 3 дн.»). **Proposed engineering defaults:** largest-unit floor, less-than-minute, three locales, 60s refresh, monotonic display, exact deadline tooltip, completed early/late text, R0.2 contract-expiry naming resolution. **Not authorized by this document:** Java, REST/UI now, notifications or CRM contract signals, SLA/ML. R0.1 scope unchanged. 
