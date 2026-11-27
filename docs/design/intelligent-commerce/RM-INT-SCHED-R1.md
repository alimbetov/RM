# RM-INT-SCHED-R1 — Tenant-Aware Analytical Lifecycle Scheduling & Automated Aggregation

2026-11-27 | MINI SPEC v1 | DRAFT FOR OWNER APPROVAL | DESIGN ONLY
Baseline: base_dev 16a8837895bc6f4c283f6022a83f99fd665b2099; 24/24 workflows GREEN, [Targeted PostgreSQL 57/57](https://github.com/alimbetov/RM/actions/runs/45924539216), [Exact-SHA Admission PASS](https://github.com/alimbetov/RM/actions/runs/45924539232), [Domain Security PASS](https://github.com/alimbetov/RM/actions/runs/45924539212). COR-01.7 exact-SHA evidence complete; COR-01.8 architectural acceptance recorded as a prerequisite, not bypassed. Parent contracts: [RM-DEV-GOV-R1](RM-DEV-GOV-R1.md), [single engine](RM-IC-SINGLE-ENGINE-CONTRACT-R1.md), [D06B](D06B-NIGHT-REPORTS-OPERATIONAL-TRIGGERS-R2.md), [D06C](D06C-TEMPLATE-FIRST-REPORTING-INTRADAY-R2.md), [roadmap](RM-IC-R2-PROJECT-ALIGNMENT-ROADMAP.md), [D08](D08-DECISIONS-AND-ADMISSION-R2.md). B4/API FREEZE, existing S1–S6/DAG, separate Radar gates and PRODUCTION HOLD remain. This is a bounded integration slice, not B6/B7 or a new analytics domain.

## 1. Бизнес-цель и границы
Автоматически подготавливать агрегаты по активным организациям, восстанавливать прерванный расчёт и предоставлять понятный статус свежести. Не создавать второй движок, новые метрики, альтернативный журнал фактов, брокер, Redis, Quartz, Spring Batch для агрегации, отдельный scheduler на tenant, HTTP API или автоматические бизнес-команды. Spring Batch остаётся отдельным условным кандидатом D06C для исполнения отчётных jobs, не второй оркестратор агрегации. Не генерировать файлы для каждого трёхчасового слота. На первом допущенном срезе — NIGHT; INTRADAY и публикация отчётов отдельно после identity/payload admission. Scheduler выключен по умолчанию.

## 2. Проверенный baseline и реальные ограничения
| Существующий исходник | Переиспользование |
|---|---|
| AutoOrderService, SalesInventoryCommandService, InventoryMovementService | Факт регистрируется в owner-транзакции через CanonicalFactAppender.appendLive/append; scheduler не вмешивается |
| CalendarPolicyService.append / CalendarPolicyRepository / PeriodResolver | Календарь настраивает уполномоченный владелец; версия immutable; D/W/DEC/M/Q/H/Y |
| CanonicalFactDeliveryService.openBackfill/backfillBatch/coverage | Только отдельно согласованное восстановление присутствующего исторического источника; 31 elapsed day / 500 facts; не автоматический ежедневный raw scan |
| L1ProjectionService.open/advance, L2ProjectionService.open/advance, L3RollupService.refreshIfStale | BUILDING-only, без изменения ACTIVE; факты и receipts, period stamps |
| GenerationRebuildService.step(Request,int,int) | 1..500 фактов на каждый consumer; 1..128 периодов; immutable range <=3660 days; READY = qualified mathematical snapshot, не historical READY |
| MultidimensionalCoverageService.activate(tenant,generation,proof,expectedPrevious) | CAS active pointer, existing full parity, workspace/lifecycle locks, atomic retire/activate |
| AuthorizedAnalyticalReadService / B5 | L3 closed/current, L0 fallback, PARTIAL/MISSING; owner ACL и snapshot; VerifiedActor обязателен |
| AuditRetentionScheduler, OidcRevocationOutboxScheduler/Worker | Spring @Scheduled opt-in; SKIP LOCKED + attempt fencing — образец, не переиспользование IAM outbox как analytics queue |

Пробел: нет автоматического discovery/dispatch, сохранённого job identity, связи между READY и activation для scheduler. SelfServiceTenantOnboardingService и PlatformTenantBootstrapService не инициализируют календарь; не добавлять тяжёлую работу в регистрацию и не назначать timezone по стране. Новое generation сейчас имеет новые consumer receipts; replay всех фактов tenant (в т.ч. OUTSIDE_COVERAGE) может быть дорогим, даже при коротком range. Dirty revision и source revision tenant-wide; новое событие после activation может сразу сделать proof STALE, даже в открытом дне. Никакой гарантии, что nightly L3 останется CURRENT весь день, что новая генерация будет инкрементальной или что отчёт прочитает только агрегаты. Измерить actual replay, proof и lock costs на пилоте; при несоответствии бюджету остановить rollout и согласовать минимальную следующую доработку, не обходить oracle/не обновлять ACTIVE. Полнота истории, доверенный persisted proof, пакетное 24M read — отдельно открытые задачи.

## 3. Регламент (предлагаемые defaults, требуют approval)
- Один Spring fixedDelay dispatcher 60s, первый tick после запуска приложения, clock from PostgreSQL; @ConditionalOnProperty rm.intelligence.scheduler.enabled=true, default false. На старте один логический worker на кластер; bounded discovery (например 50 tenants/tick, keyset + wrap) и один step на claim. Не блокировать Spring scheduling thread длительной обработкой; отдельный ограниченный executor одного worker, без unbounded queue. Никаких отдельных cron на каждый tenant.
- Tenant eligible: public.iam_workspace.kind='ORGANIZATION', status='ACTIVE', действующая организация/политика и явно включённый pilot scope; calendar effective_from<=dbNow, latest version. Неизвестный календарь → diagnostic skip, без фиктивных фактов/поколений; при следующем tick переоценить. Не создавать VerifiedActor из tenant/owner ID. До автоматизации reporting отдельный trusted internal identity, tenant/service permissions, current owner policy, audit; обычный read contract не ослаблять.
- NIGHT local 00:15 (после границы 00:00, не SLA). target closed day = localDate.minusDays(1), until_day = target+1 exclusive. Slot identity = nominal 00:15 resolved to Instant; store scheduled_at, calendar version and zone, target local day separately. At overlap use earlier offset once, at gap shift forward via ZoneRules, dedup; use PeriodResolver half-open day boundaries, not 24h arithmetic. If tenant calendar changes before activation, block/supersede old request after reconciliation, never silently rebind.
- INTRADAY local 03,06,09,12,15,18,21; no 00 duplicate. Current-day cumulative [dayStart, actual DB snapshot asOf), never add slices. Delayed/missed slots: latest due slot in same day; never invent prior cutoff. After midnight no retroactive intraday. Keep PARTIAL/MISSING, no qualified zero or forecast. Initial implementation gated; no scheduled report before durable payload/consumer contract and authorization. NIGHT completion != historical completeness or report delivery.
- NIGHT missed during outage: resume unfinished immutable job first; after resolving, latest due closed boundary covers missed days via range. Don't create N identical history rebuilds for N missed days; don't label replay as prior historical snapshot. Persist actual observed source revision/proof time. No automatic publication of superseded slots. Retry unchanged generation on stale proof or transient failure; missing/invalid fact receipts BLOCKED until owner remediation, not spin.
- Pilot history from explicit approved date (not inferred coverage), initially short 7–31 local days for benchmark; until = closed-day exclusive boundary. Do not shrink existing ACTIVE coverage or silently discard earlier range; if scope/calendar conflict, hold and ask for policy. Target 24 closed months plus relevant current closed days only after measured admission; only fully contained calendar periods materialized. Historical imports/backfill separately approved; no pre-onboarding zero, empty observation remains MISSING. Never automatically generate a new generation every poll; one immutable generation per night job, no work if already admitted same target and current contract.
- Limit source discovery/rebuild work per tick, rotate eligible tenants and backoff failures. Initial factBudget 100, periodBudget 16 (configurable within existing caps); retry 1/5/15 min then FAILED for transient errors (proposed), proof stale retry bounded, SOURCE_INCOMPLETE BLOCKED. Timeouts remain existing owner timeouts; cap lease/step budget after measured tests. No assumption all tenants finish by morning. Retention of retired generations and old receipts is OPEN; no automatic deletes or source retention changes.

## 4. Java design — only thin orchestration in kz.rm.intelligence
Proposed files (names/signatures are design, not existing implementation):
| Class / method | Responsibility |
|---|---|
| AnalyticalLifecycleScheduler.tick() | Conditional @Scheduled, delegates bounded planning and single-worker dispatch, no SQL/calculation |
| AnalyticalJobService.planDue(Instant dbNow,int limit); runNext() | Resolve eligible tenants/calendar/slots; create idempotent job, claim, resume generation, map existing progress states, error policy |
| AnalyticalJobRepository | JdbcTemplate: discoverEligible(after,limit), enqueue(JobSpec), claimNext(workerId), renew(Claim), yield/retry/block/skip(Claim,reason), lockClaimForActivation(Claim), completeNight(Claim,Activation) |
| AnalyticalJobService.runNight(Claim) | Request(tenant,generation,calendar,from,until); step(request,factBudget,periodBudget); pending/stale yield; READY calls fenced existing activation |
| MultidimensionalCoverageService.activateScheduled(...) | Proposed narrow package-private adapter of existing activation transaction, no new oracle. Public activate signature unchanged. Locks current job claim, checks tenant/calendar/expected pointer, invokes same internal activation body, marks job COMPLETED atomically with pointer switch. No public caller-supplied proof shortcut. See §6. If a simpler existing transaction-compatible hook suffices use it after review. |
| AnalyticalJobService.runIntraday(Claim) | R1.3 later; reuse authorized L0 contract with admitted system scope; no raw metric SQL/new calculation. No COMPLETED until durable authorized outcome exists. |

No changes to checkout, owner fact registration, L1/L2/L3 calculations, period rules, B5, existing read API or proof trust. No new data bank. Job DTOs internal (JobSpec, Claim including tenant/job/worker/attempt token; bounded error code). Keep one class for repository+typed SQL, no generic workflow framework. Recheck actual method signatures, source SHA, existing schedules and migrations before implementation.

## 5. SQL draft — one operational job table, not an analytical store
Existing owner tables: public.iam_workspace(id), crm.int_calendar_policy(tenant_id,version), crm.int_projection_generation(tenant_id,generation_id), crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id), crm.int_active_generation(tenant_id,projection_code). Existing consumer receipts remain only fact processing checkpoint. Do not reuse KMS/retention/IAM tables. One new table is proposed solely because current generation rows do not record logical schedule, retry, lease or completion identity. Not approved DDL; next changeset number allocate only against actual branch AND deployed DATABASECHANGELOG. Archive 0222/0223 are reserved, never reuse IDs or edit applied migrations.

```sql
CREATE TABLE crm.int_analytics_job_run (
  tenant_id uuid NOT NULL,
  job_id uuid NOT NULL,
  job_type varchar(16) NOT NULL CHECK (job_type IN ('NIGHT','INTRADAY')),
  scheduled_slot timestamptz NOT NULL,
  scheduled_at timestamptz NOT NULL,
  business_day date NOT NULL,
  calendar_version bigint NOT NULL,
  zone_id varchar(64) NOT NULL,
  from_day date,
  until_day date,
  generation_id uuid, -- allocated before generation creation; verify binding at execution
  expected_previous uuid,
  active_proof_id uuid,
  source_revision bigint CHECK (source_revision >= 0),
  actual_as_of timestamptz,
  status varchar(16) NOT NULL CHECK (status IN
    ('PENDING','RUNNING','COMPLETED','FAILED','BLOCKED','SKIPPED')),
  next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lease_owner uuid,
  lease_until timestamptz,
  attempt_token bigint NOT NULL DEFAULT 0 CHECK (attempt_token >= 0),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error_code varchar(96),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id,job_id),
  UNIQUE (tenant_id,job_type,scheduled_slot),
  FOREIGN KEY (tenant_id,calendar_version)
    REFERENCES crm.int_calendar_policy(tenant_id,version),
  FOREIGN KEY (tenant_id,generation_id,active_proof_id)
    REFERENCES crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id),
  CHECK ((job_type='NIGHT' AND generation_id IS NOT NULL
       AND from_day IS NOT NULL AND until_day IS NOT NULL AND from_day < until_day)
      OR (job_type='INTRADAY' AND generation_id IS NULL AND from_day IS NULL
       AND until_day IS NULL AND expected_previous IS NULL AND active_proof_id IS NULL)),
  CHECK ((status='RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
      OR (status<>'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)),
  CHECK (status<>'COMPLETED' OR (actual_as_of IS NOT NULL
       AND source_revision IS NOT NULL
       AND (job_type<>'NIGHT' OR active_proof_id IS NOT NULL)))
);
CREATE INDEX idx_int_analytics_job_due
 ON crm.int_analytics_job_run(next_attempt_at,scheduled_slot,tenant_id)
 WHERE status='PENDING';
-- Initial one admitted RUNNING job cluster-wide; expired row reclaimed in place.
CREATE UNIQUE INDEX uq_int_analytics_job_single_running
 ON crm.int_analytics_job_run ((1)) WHERE status='RUNNING';
-- Never run two unfinished NIGHT generations for one tenant.
CREATE UNIQUE INDEX uq_int_analytics_job_night_open
 ON crm.int_analytics_job_run(tenant_id)
 WHERE job_type='NIGHT' AND status IN ('PENDING','RUNNING','BLOCKED','FAILED');
REVOKE ALL ON crm.int_analytics_job_run FROM PUBLIC;
```
Proposed DDL only. Generation UUID deliberately not FK before open; at execution and completion bind exact tenant/generation/calendar/from/until, and completed proof FK enforces tenant. Expected predecessor fixed at enqueue (capture active pointer, null for first); do not overwrite on conflict. Need immutable job spec enforced through repository + restricted DB permissions (or narrowly required DB constraint/trigger after review); worker not allowed to mutate identity/range/calendar. Retain completed jobs at pilot; decide retention later. FAILED/BLOCKED night prevents silently starting another until explicit operator resolution, SKIPPED only after safe reconciliation/abort of BUILDING if necessary; no public operator endpoint in R1. Table alone is not authorization or evidence. Don't add report values, file links, outbox or metric columns. INTRADAY schema reserved but disabled until durable consumer outcome contract.

## 6. Transaction and concurrency contract
T1 discovery/enqueue: short RC, explicit tenant eligibility; INSERT ... ON CONFLICT (tenant_id,job_type,scheduled_slot) DO NOTHING, compare immutable existing spec, no overwrite. Conflicting unfinished night means resume/block, not create new UUID. Job identity unique, immutable calendar/range; no auto calendar insertion.
T2 claim: short RC, existing OIDC pattern: SELECT eligible pending or expired RUNNING row FOR UPDATE SKIP LOCKED; prioritize reclaim expired RUNNING, then due jobs ordered by due time/fairness. Single-running unique index prevents competing logical claims across pods; conflict = no work, retry later. UPDATE with exact row identity, increment attempt_token and attempts, set lease_owner, lease_until using clock_timestamp(), RETURNING claim. Initial lease proposal >= longest measured bounded step, renew only valid claim; no hardcoded unproved SLA. On yield/retry/complete use WHERE tenant_id/job_id AND status='RUNNING' AND lease_owner=? AND attempt_token=? AND lease_until>clock_timestamp(); zero rows => lost claim, no status overwrite. Progress retries due to normal bounded batch are not counted against failure budget (use error attempt policy, not total claim count; propose separate consecutive_failures only if needed). No expired worker can complete newer claim.
T3 work: outside job transaction, call GenerationRebuildService.step; it explicitly rejects an ambient transaction. No long job lock around replay. If worker dies mid-step, owner receipts/stamps persist; new worker same immutable generation. An old lease-lost worker may have already started bounded BUILDING work; rely on existing consumer/lifecycle locks and idempotence; check claim before each step and after. Lease alone is NOT a hard physical concurrency bound; do not claim one physical worker under partitions. Test expired attempt and competing replay. No new lock on checkout.
T4 activation (critical): existing activate owns RC. A narrow scheduled entry point locks job row first in SAME transaction as activation, verifies current claim/token/expiry, job scope, READY proof, current active organization and latest effective calendar; then existing workspace FOR UPDATE -> pointer FOR UPDATE -> lifecycle FOR UPDATE NOWAIT -> full oracle -> switch pointer. Recheck eligibility after workspace fence; calendar changes admitted via existing owner workspace SHARE, old job cannot publish after calendar drift. Recheck job claim expiry before successful commit; COMPLETED update (active proof ID, exact proof observed_at and source revision read from crm.int_dim_coverage_proof in same tx) atomic with activation. Roll back pointer/lifecycle/proof/job completion on any failure. Existing public manual activation retains behavior; manual pointer conflict blocks or reconciles exact same completed job, never reassign expected predecessor. No job locks acquired inside source/checkout transactions. Lost worker cannot activate using stale lease; no split commit between activation and completion. This narrow B4 integration change requires explicit approval, no removal of independent parity or change in activation semantics. If lock/identity approach cannot qualify, keep automatic activation disabled and stop, don't build extra infrastructure.
T5 INTRADAY: no activation, no generation mutation. Current authenticated read requires VerifiedActor; system job permission + explicit tenant/warehouse/metric scope and durable idempotent consumer acknowledgement must be approved before R1.3. Use actual read snapshot asOf/sourceRevision, not scheduler time; if no admitted recipient/payload, do not mark COMPLETED. No new owner fake principal or raw SQL bypass.
All operations fail closed on inactive tenant, invalid calendar or scope, source mismatch. Completion means prepared generation, not permanent CURRENT or historical READY; consumer rechecks freshness and access at read/publish. New events after completion can stale the proof. No direct automatic Radar/file publication in R1.

## 7. Реализация по небольшим согласуемым срезам
| Slice | Result / gate |
|---|---|
| R1.0 P0 | Contract review, actual SQL/DDL/roles inventory, COR-01.8 acceptance, explicit pilot calendar/range/worker settings, schema/activation approval; no runtime change |
| R1.1 P0 | Disabled-by-default dispatcher + one table + bounded plan/claim/retry; no auto activation; verify PG concurrency and restart |
| R1.2 P0 | Night pilot uses existing step/activation with fenced job completion; initial small actual tenant-like fixture, no historical READY or report admission |
| R1.3 P1 | 3h L0 after separate approved system authorization and durable consumer contract; no new calculations; descriptive only |
| R1.4 P0 | PostgreSQL integrated recovery, resource/latency and role qualification, exact-SHA gates. Qualify R1.1/R1.2 before enabling pilot; R1.3 separately gated. Production HOLD. |
No separate DAG/roadmap: integration prerequisite for existing S5/NIGHT/INTRA contracts; no bypass of S1 historical completeness or S2 bulk read. Do not implement whole package before R1.0 approval.

## 8. Acceptance tests (proposed class/method names; actual PG17, existing CI profile)
| ID / proposed method | Required evidence |
|---|---|
| SCH-01 dueSlotsRespectTenantCalendar | Clock-controlled 2 timezones, DST gap/overlap, midnight, calendar revision; exactly one logical slot; missed slots not fake cutoff |
| SCH-02 activeTenantWithCalendarOnly | Existing/new tenant, disabled scheduler, missing calendar, inactive/non-organization, pilot scope; no new facts or fake owner |
| SCH-03 concurrentClaimAndLeaseFencePg | Two scheduler instances same/different tenant, one logical active claim, exact immutable scope, expired claim takeover, old token cannot renew/complete/activate; no cross-tenant effect |
| SCH-04 resumeNightFromReceiptsPg | Actual order and stock operations; kill after committed batch; same generation, existing receipts/stamps, no duplicate contributions, bounded work; no historical backfill by default |
| SCH-05 activationAndCompletionAtomicPg | Real proof, fault before job completion and after pointer switch, rollback, retry, expected predecessor conflict, calendar/tenant deactivation and old claim; ACTIVE unchanged on failure |
| SCH-06 lateEventAndSourceMismatchPg | Late event during step/activation, revision change, valid rebuild, rejected receipt BLOCKED, no false CURRENT/READY; prior generation intact, L0 fallback as existing |
| SCH-07 intradayCumulativeAuthorizedPg | Gated R1.3: two authorized snapshots with actual asOf, exact distinct, MISSING != zero, missed slots, no ACTIVE mutation; unauthorized worker/warehouse rejected; durable result ack and retry idempotency |
| SCH-08 boundedLoadAndRolePg | Pilot actual 1k/10k/100k fact shapes (progressive, not assumed PASS); report replayed/outside facts, receipt growth, periods, proof scans/time, DB pool wait, checkout p95/p99, backlog/fairness, activation lock time; existing constrained pool=4 fixture, role allow/deny, no raw bypass. Stop on impact; budget/SLA and horizon expansion after measured baseline. |
Test new code in existing targeted PostgreSQL workflow/registry (append only actual test methods; preserve existing 57). Use real owner fixture + existing B4 independent mathematical oracle; do not introduce another oracle engine. Exact-SHA, Domain Security, tenant isolation, source schema, activation/revocation regression required. No weakened tests. No H2 substitute for PG locks. Production deployment roles and applied 0222/0223 status remain separate blockers.

## 9. Approval checklist / open decisions
Owner agreed to create mini spec and direction only. Explicit approval required for proposed one-table schema and fenced activation change, defaults (60s poll, 00:15 night, 100/16, one logical worker), pilot tenant/range and retry policy, scheduler identity and actual DB role grants. INTRADAY authorized durable result contract OPEN; retention of generations/jobs, 24M workload, historical completeness and actual DB migration compatibility OPEN. No production release, no DQR restoration, no actual runtime/DDL/tests executed by this document. Current exact-SHA baseline verified GREEN. Next action: owner approves narrow R1.0–R1.2 design or requests changes; then implementation in base_dev only, with actual code and migration recheck before first commit.
