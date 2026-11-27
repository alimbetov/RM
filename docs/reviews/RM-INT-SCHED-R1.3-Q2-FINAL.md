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
| P5 Performance | Paired scheduler OFF/ON runs at each selected load, same pod count, dataset, arrival rate, operation mix and environment | Per operation p95 degradation <=10%, p99 <=20%; zero deadlocks, pool timeouts and missed in-scope deadlines |
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

## Evidence ledger
P1–P8: PENDING runtime evidence. No test execution, real pod run or performance measurements are claimed by this checklist. Production admission HOLD until independently verified.
