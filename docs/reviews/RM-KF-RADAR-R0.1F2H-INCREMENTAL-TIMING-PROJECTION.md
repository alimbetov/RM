# RM-KF-RADAR-R0.1F2H — Incremental Task Timing Projection & Indexed Calendar Read Model
Date: 2026-11-28. Reviewed source 46b7da32c92663b78166c617dd338a59d195a9bb. Status: F2G-R rollback / F2H.1 design candidate / runtime NOT admitted / production HOLD.
## F2G-R observed evidence
Run 46140251926 job 140964378695: cancelled at workload, incomplete evidence. 10k dense completed-early p95 1097.880/1078.357 ms (limit20 first/next), 1080.830/1081.897 (limit100); 100k due p95 26.706–37.790 ms; no completed 100k terminal measurement, ~37m33s between last due sample and cancellation. Prior SHA 15881a81c24feae276fe0af0970a16b15fb106ca F2 7/8, max reported p95 322.621 ms (100k dense completed-early); different runner/time and size, not paired causal benchmark. The new path is unqualified and clearly breaches 200ms at 10k. Restore two runtime files exactly from prior SHA; retain added outside-period conflict/reference regression in TaskCalendarQualificationTest. Prior baseline itself NOT qualified. No thresholds changed.
## F2H.1 owner contract
TASK owns task_item and immutable task_event. One derived row per (tenant_id, task_id, projection generation), with source_version and policy_version, terminal structural evidence, completion candidate timestamp, original deadline evidence, original_due_at, deadline_changed, last processed event version and sufficient chain/terminal validation accumulators. No PII/title or actor ACL cache. Current membership/status/deadline read from authoritative task_item, or transactionally mirrored with source-version equality and proven equivalence; choose measured index design before DDL. Composite FK and tenant-leading indexes; no independent permissions. Never persist time-dependent AFTER_CUTOFF, OVERDUE, DUE_SOON, remaining time or severity as timeless facts. At read evaluate cutoff using shared TaskTimingEngine rules, including future completion evidence and exact signed microseconds. Invalid/missing/unknown distinct, no guessed values. Preserve original deadline chain semantics, including malformed legacy histories. Full reference and point path unchanged.
## F2H.2 atomic writes
Review all TaskService create/assign/reschedule/requeue/cancel/cancelForSource/start/block/resume/complete/archive paths, direct SQL/import writers, event inserts and lock order. Within owner transaction update item, append event, update projection once using version fence; rollback all. Existing retries/idempotency remain; no duplicate event or lost version. Reuse task lock, avoid new shared tenant/global locks in hot path. Prefer bounded point recomputation initially if measured <=10% degradation; incremental accumulator only with independent mathematical equivalence. No remote I/O, no read-triggered rebuild, no scheduler dependency for freshness. Missing projection is not zero; fallback to qualified reference or explicit unavailable, no silent omission. No trigger or DDL until writer inventory and migration review.
## F2H.3 backfill/reconciliation
Additive migration only, clean and populated PG/H2 compatibility. Candidate generation, bounded keyset batches, task lock and compare source version before upsert, no overwrite of newer live state; stable generation/coverage manifest, concurrent inserts and late corrections reconciled. No long global transaction or unbounded scans. Crash/retry and two-pod fencing; detect gaps, malformed history, drift even when row_version unchanged through unauthorized event mutation; protect writes and audit. Rebuild from source not prior projection. Generation activation only after complete independent reconciliation and atomic fence; rollback to reference route, no destructive migration.
## F2H.4–5 shadow and activation
Shadow compare reference vs candidate in same snapshot/cutoff, page contents, ordering, cursor, evidence, all filters, title safe vocabulary, tenant/actor authorization and token expiry. No double user-visible result, no unbounded shadow traffic. Keep fresh IAM checks and owner membership before LIMIT. Read route explicit controlled activation, fail closed on version mismatch, absent coverage or unsupported policy; fallback never silently loses rows. Index candidates tenant+actor+date+id for due/completion, measure OR/union dedup and write cost before adopting. No new Redis/MV refresh/partition or Radar feature scope.
## F2H-Q
Existing 1k/10k/100k dense/sparse first/next limit20/100, p95 <=200ms, command degradation <=10%, 9000 commands each arm, >=100 overlapping reads, existing 8 F2 tests and mandatory gates unchanged. New parity for all terminal/active states, malformed history, future cutoff, equal timestamp pagination, two tenants same task id, role revoke/reassign both orders, rollback, duplicate/replay, crash, concurrent backfill/live, generation switch and rollback. Compare enabled vs disabled projection write cost on same workload (not cross-run causal inference); measure DB/SQL and service latency separately. Add bounded diagnostic timeouts only with separate review, no favorable rerun selection. All new tests NOT_RUN. F2H.1 is design candidate pending method/DDL inventory and admission; no runtime projection implemented. P4.1A.5-Q remains HOLD; P4.2 after actual GREEN. No main changes.


## F2H.1A — method-level schema and transaction design (2026-11-28)
Reviewed SHA 041ccd252ad427cec5e33a3df521d9a99c981398. F2 run 46145370167 / job 140980143639 PASS 8/8, measured evidence PASS; worst reported service p95 90.737 ms, mixed 89.893 ms (221 reads), command degradation -17.702%. This is a qualified F2 run only, not global GREEN or projection evidence. Projection is optional optimization, not a reason to block P4.2 after existing admission GREEN. Do not activate it or change hot-path runtime before separate implementation admission; prefer finish Reporting critical path if both compete.

### Writer map
TaskService.create -> event(CREATED); assign -> event(ASSIGNED); start/block/resume/complete -> move -> event; archive -> event; reschedule -> eventDue; requeue/cancel/cancelForSource -> eventWithReason (cancelAdmitted for both cancel variants). Three concrete INSERT sites; overload event delegates to full event. Invoke one internal writer exactly once after each successful INSERT, not in overload or public method. Replay/no-op returns before insert and does not write. TaskCrmFollowupService.create delegates to TaskService.create within ambient transaction, including replay; TaskCrmReconciliationService.reconcileCancelled delegates to cancelForSource. CrmInteractionService owns CRM transition and review intent only. Relevant source reviewed; exhaustive repository-wide direct SQL inventory not completed (connector call limit); require static scan across main Java/resources, deployment scripts/import/admin roles and DB grants before enabling. Tests deliberately insert raw malformed histories; fixture population must be explicit, not bypass correctness.
Existing schema: task_item PK(tenant_id,id); task_event PK(tenant_id,id), unique(tenant_id,task_id,new_version), composite FK, idx_task_event_order; PG 0141 BEFORE UPDATE/DELETE immutable trigger. INSERT privileges and TRUNCATE remain separate review. Existing active-due index and queue indexes preserved.

### Candidate schema (design SQL, not applied)
Start with a narrow point-summary table, do not duplicate current task ownership/status/deadline or titles. Generation is separate from task row version; policy code TASK_TIMELINESS_V1 is not deployment generation.
```sql
CREATE TABLE crm.task_timing_projection (
 tenant_id UUID NOT NULL,
 task_id UUID NOT NULL,
 generation BIGINT NOT NULL CHECK (generation > 0),
 source_version BIGINT NOT NULL CHECK (source_version >= 0),
 policy_version VARCHAR(40) NOT NULL,
 completion_structural_code VARCHAR(24) NOT NULL
  CHECK (completion_structural_code IN ('NOT_APPLICABLE','MISSING','INVALID','VALID')),
 completion_candidate_at TIMESTAMP WITH TIME ZONE,
 original_evidence VARCHAR(24) NOT NULL
  CHECK (original_evidence IN ('UNKNOWN','KNOWN_NO_DEADLINE','KNOWN')),
 original_due_at TIMESTAMP WITH TIME ZONE,
 deadline_changed BOOLEAN,
 refreshed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(tenant_id,task_id,generation),
 FOREIGN KEY(tenant_id,task_id) REFERENCES crm.task_item(tenant_id,id),
 CHECK ((completion_structural_code='VALID' AND completion_candidate_at IS NOT NULL)
     OR (completion_structural_code<>'VALID' AND completion_candidate_at IS NULL)),
 CHECK ((original_evidence='KNOWN' AND original_due_at IS NOT NULL)
     OR (original_evidence<>'KNOWN' AND original_due_at IS NULL))
);
CREATE INDEX idx_task_timing_completed
 ON crm.task_timing_projection(tenant_id,generation,completion_candidate_at,task_id);
```
PG partial index WHERE completion_structural_code='VALID' is an alternative after EXPLAIN; H2 compatible DDL separate if required. Candidate owner indexes on task_item (tenant_id,assignee_user_id,due_at,id) and (tenant_id,created_by,due_at,id), not blanket approved: test current indexes first. Completion actor/date composite index requires mirroring actor identity, adds write cost and is deferred unless measured need; authoritative membership join is mandatory either way. Do not introduce accumulator columns until point recompute cost measured. Generation registry/coverage and grants designed in F2H.3 before activation; no assumed complete coverage from row counts alone.

### Java contracts and exact transaction order
Internal TaskTimingProjectionWriter.refresh(UUID tenant, UUID taskId, long expectedVersion) proposed @Transactional(MANDATORY), no public API/actor impersonation. Caller owns task row lock (or fresh insert); check tenant/id/current version. TaskTimingEngine extracts shared structural completion expression without cutoff; current completionEvidence retains AFTER_CUTOFF by wrapper. Reuse exact original evidence/chain validation in owner-internal point query with tenant+task predicates and no actor bypass exposed to callers. Use persisted timestamps and versions, not Java request clock values. Point event history uses existing unique index; cost O(history for this task), not O(all tenant tasks). No arbitrary truncation of history; bound statement execution, fail transaction rather than certify partial history. F2H.2 candidate may be disabled by default; once enabled for a generation, writer failure rolls back task and event. Lock order retains existing IAM/CRM/request stripe/task sequence, then projection row; no new global hot-path lock. Never use REQUIRES_NEW or after-commit writer for atomic route.
SQL upsert PostgreSQL candidate:
```sql
INSERT INTO crm.task_timing_projection AS p
 (tenant_id,task_id,generation,source_version,policy_version,
  completion_structural_code,completion_candidate_at,original_evidence,
  original_due_at,deadline_changed,refreshed_at)
VALUES (?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT (tenant_id,task_id,generation) DO UPDATE SET
 source_version=EXCLUDED.source_version, policy_version=EXCLUDED.policy_version,
 completion_structural_code=EXCLUDED.completion_structural_code,
 completion_candidate_at=EXCLUDED.completion_candidate_at,
 original_evidence=EXCLUDED.original_evidence, original_due_at=EXCLUDED.original_due_at,
 deadline_changed=EXCLUDED.deadline_changed, refreshed_at=EXCLUDED.refreshed_at
WHERE p.source_version < EXCLUDED.source_version
  AND p.policy_version=EXCLUDED.policy_version;
```
For zero affected rows verify same version/policy and exact semantic equality for legitimate retry; otherwise fail closed. Same-version discrepancy requires explicit repair path under task lock and audit, not overwrite by stale worker. New generation policy change uses separate key. H2 equivalent conditional update/insert requires dedicated tests; no unconditional MERGE overwrite.

### Read and qualification boundary
TaskCalendarQueryService.listMine retains fresh requireTaskRead and cursor validation; candidate join on tenant/task and exact version/generation. Compute completion evidence at evaluation cutoff (future structural valid becomes AFTER_CUTOFF and completed_at NULL); use shared classification and signed microsecond rules. Apply all actor/status/date/timeliness predicates before LIMIT. Missing/stale rows cannot be silently filtered: route whole request to reference or explicit unavailable until a qualified completeness fence. Shadow read same snapshot and cutoff. Point/overdue reference paths unchanged. Future Reporting uses only admitted owner read contract, never direct projection permissions.
F2H.2 tests: every three insert hook families and all actions; replay/no-op no projection mutation; transaction rollback including CRM outer transaction; deliberate writer failure; two tenants same ID; stale version rejected; malformed and missing history; future completion cutoff; exact original evidence, same-version mismatch, PG/H2 populated migration; mixed 9000 commands per arm with >=100 overlapping reads and <=10% degradation, no new read SLA waiver. F2H.3 adds backfill/live races and activation fence; F2H.4/5 remain disabled until qualified. No runtime or migration changed here. Remaining direct-writer/grant inventory and measured index plan block F2H.2 activation. Existing P4.1A.5-Q remains current gate.
