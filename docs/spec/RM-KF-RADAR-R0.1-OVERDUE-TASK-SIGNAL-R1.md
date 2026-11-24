# RM-KF-RADAR-R0.1 — Overdue Task Signal
## R1.0 — сервисный контракт и матрица квалификации

**Дата:** 2026-11-24. **Ветка:** base_dev. **Source review SHA:** a15670566912e9c60e0ede52af489e72a58077ca.
**Статус:** SPEC R1.0 / OWNER SCOPE EXCEPTION — R0.1 IMPLEMENTATION AUTHORIZED / NOT IMPLEMENTED / PRODUCTION HOLD.
**Decision:** CR-MVP-KF-RADAR-01. Владелец продукта явно снял freeze только для R0.1; техническая редакция R1.0 предъявляется на review. Не считать этот документ разрешением R0.2+, UI, внешнего API, пилота или production.

## 0. Scope, normative sources and change control

- [MVP Freeze](../strategy/RM-MVP-CLOSURE-FREEZE-R1.md) сохраняется, кроме узкого исключения CR-MVP-KF-RADAR-01.
- [Platform Standard R2](../architecture/RM-PLATFORM-STANDARD-R2.md) — IAM, ownership, tenant, PII и обязательные gates не ослабляются.
- [BSR R1](RM-BSR-R1-SERVICE-SCENARIO-QUALIFICATION.md) — текущая квалификация сервисного ядра не прерывается; обязательные тесты не удалять и не переводить в skipped.
- [KF portfolio](RM-COMPETE-R1-POST-STABILIZATION-KILLER-FEATURES.md) / [Idea Bank IDEA-16](../ideas/RM-IDEA-BANK-R1.md) — только TASK_OVERDUE, остальные сигналы и KF остаются PARKED.
- Работать только в base_dev, не создавать ветку, не трогать main. Если идёт exact-SHA квалификация release candidate, сначала завершить её или явно выбрать новый candidate с повтором всех обязательных gates. Не объявлять прежние PASS результатом нового SHA.

## 1. User outcome and non-goals

Сотрудник с разрешением TASK_READ получает ограниченную, упорядоченную очередь просроченных задач, которые он вправе читать как создатель или исполнитель. Для каждой видны причина, длительность просрочки и версия исходной задачи. При повторном чтении после изменения задачи радар отражает новое committed состояние. Это advisory signal, не команда и не доказательство нарушения SLA.

R0.1 не создаёт задач, не пишет в task_item/task_event/CRM/SALES/INVENTORY, не хранит сигналы, не выполняет auto-escalation, snooze, notification, campaign, SLA automation, ML, scheduler, outbox, cache, global tenant dashboards, CRM joins, REST/OIDC endpoints, UI. Нет новых таблиц, индексов или Liquibase changesets. Если план запроса неприемлем — HOLD и отдельный ADR/CR, а не скрытая миграция.

## 2. Source review

| Existing source | Verified contract / limitation |
|---|---|
| [TaskService](../../src/main/java/kz/rm/tasks/TaskService.java) | JdbcTemplate, injected Clock, Task record with tenantId/id/title/assignee/createdBy/state/dueAt/version; getMine/listMine require TASK_READ and creator OR assignee. listMine supports one status, offset and bounded limit; cannot implement a globally ordered overdue list by postfiltering one page. managerQueue is QUEUED only and requires TASK_MANAGE + TASK_READ. |
| [TaskState](../../src/main/java/kz/rm/tasks/TaskState.java) | OPEN, IN_PROGRESS, BLOCKED, COMPLETED, ARCHIVED, QUEUED, CANCELLED. |
| [TenantAuthorization](../../src/main/java/kz/rm/iam/TenantAuthorization.java) | requireForCommand MANDATORY uses actor/workspace row locks and fresh membership/role checks; existing TASK reads use this same path in a regular transaction. require alone lacks the fence. |
| [VerifiedActor](../../src/main/java/kz/rm/iam/VerifiedActor.java) | IAM-minted request principal; userId() checks expiry. No new constructor/factory or raw actor UUID entrypoint. |
| [Liquibase master](../../src/main/resources/db/changelog/db.changelog-master.sql) | crm.task_item composite PK (tenant_id,id), due_at TIMESTAMP WITH TIME ZONE nullable for legacy, row_version; idx_task_assignee, idx_task_queue, idx_task_queue_keyset. No evidence of sufficient performance until measured. |
| [TaskServiceQualificationTest](../../src/test/java/kz/rm/tasks/TaskServiceQualificationTest.java) | 53 cases; existing CI expects 35 H2 executions + 18 expected PG-only skips; PostgreSQL 53 without skips. Do not change that suite/count for convenience. |

## 3. Rule semantics (normative candidate)

Capture trusted server Clock.instant() once per invocation after admission. Normalize the cutoff to microseconds (truncate, not round up), consistent with PostgreSQL timestamp precision; report that same evaluatedAt in response and use it in SQL and Java. Confirm H2/PG binding round trip in tests; never use client asOf, LocalDateTime, wall-clock time zones or multiple NOW() calls. Due time is the stored authoritative Instant.

Eligibility conjunction:
- row.tenant_id = admitted tenant;
- row.assignee_user_id = actor OR row.created_by = actor;
- assignee_user_id IS NOT NULL;
- status in OPEN, IN_PROGRESS, BLOCKED;
- due_at IS NOT NULL AND due_at < evaluatedAt.

OPEN/IN_PROGRESS/BLOCKED with overdue due qualify; QUEUED (including creator-owned), COMPLETED/CANCELLED/ARCHIVED, unassigned legacy rows and null due do not. A row matching both creator and assignee appears exactly once. No extra role grants. Creator visibility after reassignment follows current TASK read policy; previous assignee who is not creator loses access. No filtering by customer status/CRM linkage in R0.1: cancellation of a linked interaction alone does not erase an active task until the canonical TASK operation commits. No independent check of assignee account liveness: the source task remains actionable for a permitted creator; caller liveness is required.

overdueSeconds = Duration.between(dueAt,evaluatedAt).getSeconds(), nonnegative; a subsecond overdue task may have 0 whole seconds and is still overdue. No rounding of eligibility to whole seconds. Severity is a deterministic presentation classification, not persisted state or tenant SLA:
- ATTENTION: 0 <= elapsed < 24h;
- HIGH: 24h <= elapsed < 72h;
- CRITICAL: elapsed >= 72h.
Hours are elapsed 3600-second intervals, not calendar days. These are proposed R1.0 technical defaults, not externally approved SLA. No severity escalation solely because BLOCKED.

One signal per source task, logical identity (tenantId, taskId, TASK_OVERDUE); sourceVersion changes with task. No event IDs, random signal IDs or durable dedup store. Sort due_at ASC, id ASC in database (native UUID ordering), so oldest first, deterministic ties. No claim of cross-database Java UUID comparator equivalence.

## 4. Architecture and contract

TASKS remains the sole owner of read authorization, task SQL and state. Do not introduce a new DomainModule or cross-domain SQL. Proposed minimal placement: kz.rm.tasks.TaskOverdueQueryService (owner-side query and admission), small immutable DTO/record types and pure OverdueSignalEvaluator in the same package, or equivalent minimal owner-side design verified against architecture tests. A separate RelationshipRadarService facade is optional ONLY if needed; no generic rule engine or new component hierarchy for one rule.

Proposed service signature (internal Java only):
```java
OverdueSignalPage listMine(VerifiedActor actor, UUID tenantId,
                            int limit, OverdueCursor after);
```
DTO contracts:
- OverdueSignal: taskId UUID, type TASK_OVERDUE, state TaskState, dueAt Instant, sourceVersion long, overdueSeconds long, severity enum, reasonCode TASK_DUE_TIME_EXCEEDED.
- OverdueSignalPage: evaluatedAt Instant, immutable items List, nextCursor nullable. No total count.
- OverdueCursor: tenantId, actorId, lastDueAt, lastTaskId. Internal cursor, no HTTP serialization/admission or client-supplied cutoff. Cursor boundary is not an authorization decision. Validate structure, scope and precision; mismatch invalid. It is acceptable to choose a different shape that preserves the same scoping and ordering after review.
- No task title, contact/customer fields, names, email, phone, creator/assignee IDs in items. Internal cursor actor/tenant IDs must not be logged or exposed as public token. Future UI can retrieve permitted detail using existing getMine, with fresh authorization.

Authorization order: validate nonnull actor/tenant; actor.userId() verifies expiry; execute in a regular Spring transaction through proxy; call existing requireForCommand(actorId,tenant,TASK_READ) to match TASK reader fence; verify cursor belongs to admitted scope; capture Clock and read. Fail closed on errors. No tenant-wide scope for manager/OWNER/PLATFORM_ADMIN by default; no TASK_MANAGE implied read; enforced RBAC respected. No new read permission. Do NOT use @Transactional(readOnly=true) on this fenced transaction: PostgreSQL FOR SHARE requires a transaction allowing row locks. Read-only here means no business DML. Reuse IAM locking order; no task row lock and no cached permission decision. Return DTOs within transaction; each new request reauthorizes. Revocation committed before admission denies; admitted read may finish before revocation commits under same lock protocol; no promise to retract already returned data. Authentication token verification lives at IAM resolver, not in the Radar.

## 5. Query and pagination

Single bounded parameterized business SELECT per page, following IAM admission. No N+1 queries, no fetch-all, no listMine offset loop, no filters after LIMIT, no dynamic untrusted SQL, no count query. Illustrative owner-side SQL (bind all values; use two static query variants):
```sql
SELECT id, status, due_at, row_version
FROM crm.task_item
WHERE tenant_id = ?
  AND (assignee_user_id = ? OR created_by = ?)
  AND assignee_user_id IS NOT NULL
  AND status IN ('OPEN','IN_PROGRESS','BLOCKED')
  AND due_at IS NOT NULL
  AND due_at < ?
  -- subsequent page only:
  AND (due_at > ? OR (due_at = ? AND id > ?))
ORDER BY due_at ASC, id ASC
LIMIT ?
```
First page omits cursor predicate. limit 1..100, default at caller if required 20; SQL limit+1 (max 101); return at most limit, nextCursor from last returned row iff extra row present. Invalid limit (including >100) or malformed cursor fails without reading task rows. Timestamp bind/UUID compare must be proven on PG and H2. Each page has fresh evaluatedAt and committed view. Keyset is live, NOT stable snapshot; concurrent deadline edits/reassignment/insertion may move items across cursor causing omission/reappearance. Never promise exactly-once multi-page export; refresh from first page for current complete top list. On unchanged fixture, full traversal has no duplicates/omissions. No cursor offset or durable snapshot.

## 6. Concurrency and failure guarantees

- SELECT sees a coherent committed task row (state, due, version together); no task modification, no stale cached result. Statement snapshot on PostgreSQL; changes after its snapshot may be absent until next read.
- complete/cancel/reschedule/reassign/requeue committed before the SELECT affect result; rollback does not. Races permit either legitimate committed observation, never a mixed row or foreign scope. R0.1 must not block task rows via FOR UPDATE/SHARE; IAM locks are intentionally reused.
- IAM revoke/block/member/tenant suspension before admission: deny, no list. Read first: trusted writer waits for existing IAM fence as applicable; test both orderings with actual independent transactions. Verify current writer lock protocol; if divergence found, stop for security fix, don't weaken admission.
- DB failure propagates via standard service exception; no fabricated empty success, no partial page, no fallback cross-tenant cache. Retry is fresh read, not immutable replay. No DML, task history/event/receipt/outbox side effects. Logs exclude PII/title/token/cursor/payload; minimal technical diagnostics only.

## 7. Acceptance matrix — 36 planned cases (not executed)

Unit/pure deterministic evaluator and SQL-backed service cases must map to test methods; use real PostgreSQL for DB precision, independent SQL oracle, transactions and races. H2 only compatibility; PG-only skips enumerated explicitly in own suite.

| ID | Scenario | Expected |
|---|---|---|
| RAD-01 | OPEN overdue assigned, visible | one TASK_OVERDUE |
| RAD-02 | IN_PROGRESS overdue | one signal |
| RAD-03 | BLOCKED overdue | one signal, normal elapsed severity |
| RAD-04 | COMPLETED/CANCELLED/ARCHIVED overdue | none for each state |
| RAD-05 | QUEUED including creator-owned | none |
| RAD-06 | legacy null due / null assignee | none for each |
| RAD-07 | future due / due exactly at cutoff | none for each |
| RAD-08 | 1 microsecond before cutoff | signal, zero whole seconds |
| RAD-09 | 24h / 72h boundaries and immediately before | exact severity |
| RAD-10 | timezone and midnight, UTC offsets | equal Instant equal result |
| RAD-11 | Clock sampled once; microsecond normalization | SQL and DTO cutoff match |
| RAD-12 | creator only / assignee only / both | one row each; no duplicate |
| RAD-13 | unrelated actor same tenant | no row, no count leak |
| RAD-14 | same UUID in two tenants / foreign cursor | no foreign data; reject wrong scope |
| RAD-15 | missing/expired VerifiedActor or null tenant | deny |
| RAD-16 | no TASK_READ / TASK_MANAGE only in enforced RBAC | deny |
| RAD-17 | OWNER or global admin without grant (enforced RBAC) | deny |
| RAD-18 | suspended actor / membership / tenant; personal workspace | deny each |
| RAD-19 | revoke role after actor minted before admission | deny |
| RAD-20 | PG read vs revoke, both orders | correct fence, no post-revoke admission |
| RAD-21 | PG read vs account/member/tenant suspension | same fence, both orders for each writer |
| RAD-22 | old assignee after reassignment | disappears unless creator |
| RAD-23 | committed complete/cancel/requeue/archive | no signal |
| RAD-24 | reschedule into future and back past | disappear/reappear with source version |
| RAD-25 | rollback status/deadline change | committed prior state remains |
| RAD-26 | PG race read × complete/cancel | legitimate committed row or absence |
| RAD-27 | PG race read × reschedule/reassign/requeue | coherent tuple and authorized scope |
| RAD-28 | stable tie ordering and full keyset traversal | all eligible IDs exactly once on static fixture |
| RAD-29 | limit 1, 100, empty, exact limit, limit+1 | correct items and nextCursor |
| RAD-30 | invalid limit, malformed or wrong-actor cursor | reject, no task data query |
| RAD-31 | mutation between pages | live cursor semantics; refresh sees current top |
| RAD-32 | CRM source cancelled but task not yet reconciled | task state alone governs |
| RAD-33 | DB error / retry | fail closed then fresh result, no fake empty |
| RAD-34 | DTO serialization, logs, no PII | allowlisted fields only; no sensitive logs |
| RAD-35 | independent PG SQL oracle + before/after ledger checks | exact IDs/versions/due, zero business DML |
| RAD-36 | populated workload, EXPLAIN ANALYZE BUFFERS, baseline regression | bounded query, no N+1; actual plans and p50/p95 recorded |

RAD-20/21/26/27 require separate physical connections, latches/barriers, bounded waits, explicit commit/rollback and independent SQL verification. No Thread.sleep-only evidence. RAD-35 compare task_item/task_event and other relevant domain tables in an isolated fixture (exclude legitimate concurrent writer deltas or compare against expected oracle); verify no DML with statement evidence. RAD-34 never use actual customer data. Test identities from existing trusted test resolver/real JWT fixture; do not add production identity mint API.

## 8. Performance and delivery gates

Use existing indexes first. For RAD-36 record plan/rows/buffers/p50/p95 at 1k, 10k and 100k synthetic tasks across >=2 tenants, mixed statuses, creator/assignee overlap, sparse and dense overdue sets, limits 20/100; query warm/cold conditions and hardware documented. Proposed engineering target for 100k total fixture: p95 <= 200 ms service read in documented local/CI environment (not production SLA); compare existing TASK command workload under mixed reads, report p95 delta and lock waits; proposed <=10% degradation against comparable baseline. Record repetitions, sample size and variability; if missed or noisy, HOLD performance claim and investigate; no index addition without separate change decision. No claims about 100k RPS.

| Stage | Deliverable / gate |
|---|---|
| R0.1A | Recheck actual HEAD, existing IAM lock writers, task schema, architecture boundary, test runner wiring; spec review and traceability table; no changes to frozen candidate while qualifying |
| R0.1B | Owner-side query/DTO/evaluator; pure tests and source-aware service tests with bounded keyset and no business writes |
| R0.1C | Independent PG qualification and concurrency evidence, H2 compatibility; performance/SQL oracle; preserve original TASK suite and existing mandatory gates |
| R0.1D | Exact-SHA admission review: all planned cases bound to actual methods and executed in proper env, zero unexpected skips, failures/errors; full applicable mandatory regression; source/SQL evidence and review report. SERVICE TESTED / PG QUALIFIED only on evidence |

Use separate TaskOverdueSignalQualificationTest (proposed name) so original TaskServiceQualificationTest counts and 18 expected H2 skips remain intact. Add dedicated test execution to appropriate existing TASK CI or separate required qualification job on base_dev; explicitly require expected case IDs/Surefire reports and no PG skips. Existing workflows may select test classes explicitly; merely creating a class does not prove CI execution. No mandatory job removal, no CI bypass, no new production flag. Preserve full-domain CI exact-SHA requirements. Record test command, commit, workflow URL, DB version, XML, query plan, manifest and reviewer verdict; status HOLD until evidence. User acceptance/release is later through G2/G3/G4, never inferred from service PASS.

## 9. Decisions and exclusions

Owner approval received only for narrow freeze exception and development scope. R1.0 engineering choices for review: creator OR assignee scope, exclude unassigned/QUEUED, 24/72 elapsed-hour severity, keyset live pages (limit <=100), minimal DTO without title, owner-side SQL and performance targets. No hidden automatic grant, no generic platform rewrite. R0.2 contract expiration, R0.3 sales, R0.4 inactivity, ML and notification automation remain parked and require new explicit decision. A new production/REST/UI change is not authorized by this specification.

## 10. Definition of Done

A verified actor receives only eligible task signals with correct due/version/severity and deterministic bounded ordering, no business writes, no cross-tenant leak, fresh permission decisions and valid race evidence. All 36 scenarios mapped and qualified, unchanged TASK mandatory suite and relevant full regression passed on exact SHA; no additional migrations. Report actual evidence instead of invented coverage. Revert code if needed without DB rollback. Production HOLD remains.

**Revision history:** R1.0 — initial source-derived technical specification and explicit CR-MVP-KF-RADAR-01. No Java/SQL/test changes in this documentation revision.
