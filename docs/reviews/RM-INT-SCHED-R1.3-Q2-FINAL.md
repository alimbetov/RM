# RM-INT-SCHED-R1.3-Q2-FINAL — Batch Qualification & Closure

Status: APPROVED SCOPE / EVIDENCE PENDING / PRODUCTION HOLD.
Reviewed implementation SHA: 4a7d299e5b8e2b7d0a8d7c6e95ed39b69a849dbd.
This is an execution checklist, not a runtime evidence report or a change to admission policy. Qualify the actual final HEAD, including this document.

## Single package P1–P8
| Package | Required evidence | Acceptance |
|---|---|---|
| P1 Mathematical | Existing AnalyticalJobFoundationQualificationTest PostgreSQL results; independent owner-event oracle, persisted INTRADAY, same-day L3 and source revision; late visibility, distinct orders, civil-day boundaries | Exact equality for comparable source and period; no invented zero |
| P2 Security | Real tenants, signed service identity, foreign scope and revoked identity denial, existing least-privilege checks | Zero unauthorized reads or writes |
| P3 Multi-pod | At least two distinct application pods sharing PostgreSQL; concurrent claims, terminate one worker during work, lease takeover and stale token rejection | No duplicate acknowledgment or tenant leakage; bounded recovery |
| P4 Integrity | Fault injection before completion, transaction rollback, retry, immutable result | Zero partial commits or duplicate outcomes |
| P5 Performance, SQL & Index Optimization | Query-plan and index inventory evidence below; paired scheduler OFF/ON runs at each selected load, same pod count, dataset, arrival rate, operation mix and environment | Per operation p95 degradation <=10%, p99 <=20%; zero deadlocks, pool timeouts and missed in-scope deadlines |
| P6 Lifecycle | Existing NIGHT failure fences, explicit recovery, pilot cap, expiry, idempotency; new slot after cap | No automatic bypass, mutation or allocation beyond cap |
| P7 Admission | Source Inventory, PostgreSQL source schema, targeted JUnit XML, required security gate and existing Exact-SHA workflow | All mandatory tests executed and passed; existing Exact-SHA GREEN |
| P8 Closure | Evidence manifest tying P1–P7 to final SHA and qualified pilot scope | No missing evidence; explicit approval, not automatic runtime enablement |

## Execution
- Use existing CI PostgreSQL workflows and targeted test inventory; no additional release gates or duplicate scheduler.
- Collect workflow run/job URLs, exact SHA, test counts (pass/fail/skip), migration and environment identifiers. A PR-only workflow lookup returning no runs is not evidence of failure or success.
- Multi-pod evidence must record pod identities, shared DB, job and attempt token transitions, termination and takeover timing. Concurrent threads are not multi-pod evidence.
- Performance: proposed pilot scenarios 10, 50, 100 tenants with 2 pods and 100 tenants with 4 pods. For EACH scenario collect matched OFF and ON runs; warm up, repeat at least three times, report sample counts and p50/p95/p99 per operation, throughput, errors, SQL latency, pool active/pending/timeouts, scheduler due-to-ack latency and missed slots. Do not compare different tenant counts as a baseline. Report raw artifacts and worst paired degradation; insufficient samples or unavailable metrics remain pending. Thresholds are approved package targets, not measurements.
- Preserve current bounded pilot (BOOKED_ORDERS, 7 slots/day, <=31 days, <=217 stored requests per tenant). New slot at cap still requires evidence. Do not introduce retention deletion or auto-enable runtime.
- Existing parity test uses a controlled unchanged source and explicit NIGHT scope, not automatic closed-day planning evidence.
- If any gate fails, fix only the observed defect in base_dev, then qualify the new exact SHA. No new branch, architecture layer, or weaker gate.

## P5 — SQL, Composite Index & Query Plan Optimization (within existing package)

Goal: reduce analytical SQL latency and workspace-lock duration without weakening tenant authorization, source completeness, or exact distinct-order semantics. No independent phase, new infrastructure, or unconditional production index migration.

### Inventory baseline
The existing status-event indexes are (tenant_id,order_id,occurred_at DESC) and (tenant_id,event_type,occurred_at,id). The sales_order_line UNIQUE(tenant_id,order_id,id), int_source_revision_event PRIMARY KEY(tenant_id,event_id), existing L2 day/reconcile indexes, L3 cell grain/order-union indexes and partial job registry indexes must be included in the inventory. Inspect pg_indexes and pg_index/pg_constraint on the actual qualified database, not only CREATE INDEX statements in source. Do not duplicate constraint-backed indexes or remove integrity/fencing indexes.

### Candidate indexes — experiment only, not approved DDL
```sql
CREATE INDEX idx_sales_placed_tenant_time
ON crm.sales_order_status_event (tenant_id, occurred_at)
INCLUDE (order_id) WHERE event_type = 'PLACED';

CREATE INDEX idx_sales_placed_tenant_order
ON crm.sales_order_status_event (tenant_id, order_id)
WHERE event_type = 'PLACED';
```
First test each candidate separately, then both if justified. Compare against existing range/order indexes. A covering index may still perform heap fetches; the second partial index may be redundant. Retain only measured beneficial candidates. Use isolated PostgreSQL fixtures; do not run experimental DDL or EXPLAIN ANALYZE on live production.

### Plan qualification
| Query family | Evidence |
|---|---|
| L0 BOOKED_ORDERS | Actual production query over int_l2_expected_source; tenant, stream and half-open business-day bounds |
| Ambiguous PLACED diagnosis | Candidate-day selection, all-visible-event uniqueness check (including duplicate outside cutoff), line existence |
| Source revision | Base revision plus exact tenant event count |
| NIGHT / L2 / L3 | Replay, contribution lookup, daily aggregation, distinct membership rollup, coverage proof |
| Scheduler | Due claim, expired lease, active NIGHT fence, request cap, latest result |

Use EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) on representative read queries and actual bind values. For mutating/locking SQL use disposable isolated fixtures and roll back where feasible; never infer absence of side effects from EXPLAIN ANALYZE. Record PostgreSQL version, schema SHA, statistics freshness, settings, index definitions/sizes, planning/execution time, estimated vs actual rows and loops, shared hits/reads, heap fetches, sort/hash spill and temporary blocks. Assess prepared-statement custom/generic plans where relevant. Keep scope, data, concurrency, warmup, repetitions and settings matched across comparisons.

Fixtures: 1,000 / 10,000 / 100,000 orders per tenant per day with representative order-line fanout, multiple statuses, 7–31 day history, skewed tenant sizes, and at least two real tenants. Include early/late-day cutoffs, duplicate PLACED outside cutoff, late commit, missing observations, multiple lines per order, cross-category membership. Use smaller tiers first; resource-intensive tiers run on an adequately sized test environment. Record actual cardinalities rather than assume fixture generation succeeded.

### Acceptance
- Compare exact outputs and quality classifications before/after optimization, with independently computed owner-event oracle and same-scope L3. Preserve all-visible-event ambiguity rejection, DISTINCT, MVCC, calendar boundaries, source revision, tenant filters and IAM locks.
- Compare per-query latency and workspace-lock duration; require reproducible benefit exceeding measured run-to-run noise before committing a new index. Record index size, INSERT/UPDATE overhead, WAL and maintenance impact. No unsupported speedup claim.
- Retain existing P5 paired scheduler OFF/ON thresholds (p95 <=10%, p99 <=20% degradation per operation), zero deadlocks, pool timeouts and missed qualified deadlines. Also compare pre/post index business throughput and latency at matched load; no material regression. Report p50/p95/p99 and raw artifacts.
- Consider extended statistics only if estimates are demonstrably inaccurate; consider SQL rewrite only if measured plans justify it and independent parity succeeds. No removal of DISTINCT, eligibility checks or tenant authorization. No partitioning, cache, sharding or revision-counter redesign in this pilot without demonstrated need.
- Any accepted schema change is a new forward-only changeset; never edit applied changesets. Assess lock/availability impact and rollback strategy, update tracked source inventory by reviewed blob if required, then rerun P1–P8 on final exact SHA. No weakening of existing gates.

Status: static inventory and candidates documented; EXPLAIN, comparative benchmark and runtime evidence PENDING. No candidate index admitted yet.

## Evidence ledger
P1–P8: PENDING runtime evidence. No test execution, real pod run or performance measurements are claimed by this checklist. Production admission HOLD until independently verified.
