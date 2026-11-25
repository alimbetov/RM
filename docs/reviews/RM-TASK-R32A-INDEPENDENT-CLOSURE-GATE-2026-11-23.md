# RM-TASK-R3.2A — Independent consistency review and closure gate (2026-11-23)

**Scope:** `base_dev`, RM Business TASKS ↔ CRM, R3.1/R3.2A. **Production:** HOLD. **No controllers/UI/worker; no main merge.**

## Evidence examined
- `docs/spec/RM-TASK-01B-R3.1-QUEUE-RECONCILIATION-HARDENING.md`
- `docs/spec/RM-TASK-01B-R3.2-OPERATIONAL-RECONCILIATION.md`
- `docs/reviews/RM-TASK-R3.2A-TECHNICAL-AUDIT-2026-11-23.md`
- `src/main/java/kz/rm/tasks/TaskReconciliationSnapshotService.java`
- `src/test/java/kz/rm/tasks/TaskServiceQualificationTest.java`
- `.github/workflows/rm-task-r2-service.yml`, `.github/workflows/rm-exact-sha-admission.yml`
- `docs/architecture/RM-PLATFORM-STANDARD-R2.md`

This review inspected repository contents, not a local PostgreSQL instance or full GitHub Actions artifacts. No new passing test result, production gate or current head SHA is asserted.

## Positive alignment
1. One canonical `crm.task_item`; snapshots contain IDs, captured state and version only. No competing task aggregate.
2. `capture` requires `TASK_MANAGE` and `CRM_READ`, locks a CANCELLED CRM interaction, captures up to 10,001 rows in a single statement, rejects >10,000 before inserting, persists header/items/seal transactionally.
3. `page` checks actor, tenant, both current permissions and snapshot ownership. Keyset traversal of immutable membership does not imply live-state consistency.
4. The service does not auto-cancel tasks or dispatch notifications. This matches the approved service-first, modular monolith architecture.
5. Dedicated CI source expects 48 test cases on PostgreSQL without skips, 48 discovered on H2 with 15 expected PostgreSQL-only skips; source-level expectations are not evidence of a passing workflow at the final SHA.

## Open findings / required repairs

| ID | Severity | Finding | Closure evidence |
| --- | --- | --- | --- |
| TASK-R32A-01 | P0 gate | No verified **final-head** exact-SHA admission across all six mandatory workflow families, job suites, Surefire checksums and Liquibase fingerprint. | Same-SHA artifacts, PASS from exact-SHA verifier, record SHA and immutable URLs. |
| TASK-R32A-02 | P1 | PostgreSQL 0148–0152 *populated upgrade* is unqualified; fresh bootstrap is not an upgrade test. | Seed real pre-upgrade CRM interactions/task rows/events, migrate with Liquibase, verify counts/FKs/immutability, test re-run, backup/restore and rollback strategy. |
| TASK-R32A-03 | P1 | Crash after COMMIT before caller receives snapshot ID remains unqualified. | Inject lost response after commit, retry exact actor/source/key in a separate transaction; assert exactly one sealed manifest, same ID and same digest; distinguish precommit failure. |
| TASK-R32A-04 | P1 | Same-key concurrent capture T47 is documented, but concurrent CRM status transition, membership revocation and snapshot capture are not separately demonstrated. | Two-connection PostgreSQL latches in both lock orders; no unauthorized snapshot, no orphaned row, no stale-authorization leak. |
| TASK-R32A-05 | P1 | 10,001 overflow is asserted; exact **10,000 successful capture**, deterministic pagination and query plan are not qualified. | 0/1/100/10,000/10,001 matrix, EXPLAIN ANALYZE BUFFERS, pool/lock p95-p99 and bounded resources. |
| TASK-R32A-06 | P1 | Seal SHA-256 is written, but no independent post-commit recomputation and restore verification is admitted. | Canonical digest verifier over persisted ordered rows; detect deliberate mismatch, torn restore and item count discrepancy; no self-approval of corrupt snapshots. |
| TASK-R32A-07 | P2, confirm contract | `capture` checks source `CANCELLED` before looking up existing requestKey. If CRM status may reopen, a lost-response retry may not return the immutable existing snapshot. | Explicit decision on whether CANCELLED is terminal; if nonterminal, idempotent replay must return original committed receipt safely, without allowing new captures on active source. Cover both same-source and mismatched-source requestKey. |
| TASK-R32A-08 | Policy hold | Retention, task-title plaintext remediation, snapshot backup/deletion and notification consent / responsibility are undecided. | Approved tenant policy and legal/security retention criteria before background dispatch. |

## R3.2B transition contract (2026-11-23)

The next stage is defined in [RM-TASK-01B/R3.2B — SLA Policy & Durable Outbox Foundation](../spec/RM-TASK-01B-R3.2B-SLA-POLICY-DURABLE-OUTBOX-FOUNDATION.md). It separates (a) a tenant-approved immutable SLA policy revision, (b) an operational manual-review obligation tied to the canonical CRM `CANCELLED` event, and (c) an atomic durable notification intent with independently tested lease/fencing/recovery. A real notification executor and external delivery remain **R3.2C only**. No automatic task cancellation.

**Gate rule:** this cross-link does not close the unresolved R3.2A source, migration, exact-SHA, replay or digest findings above. R3.2B architecture planning may proceed, but R3.2B foundation PASS requires the independent prerequisites and a new exact-SHA qualification on implementation HEAD.

## Order of implementation
**Gate A — QA evidence:** exact head SHA; populated PostgreSQL migration; restore and digest verification.  
**Gate B — concurrency/recovery:** source status and IAM races; lost-response retry; 10k boundary/performance.  
**Gate C — R3.2B design admission:** approved policy revision, SLA calendar/clock, role ownership and consent; durable outbox table and separately tested lease/fence/retry/poison/dead-letter/after-send-before-ACK semantics.

**No assumption of exactly-once external delivery.** Preserve the existing PostgreSQL outbox conventions. Network calls must not occur within a database lease transaction. Every worker mutation must compare current fencing token and lease ownership. Notification outbox payloads must not contain task title, CRM free text, email, phone, bearer tokens or customer PII.

## Decision
**R3.2A = IMPLEMENTED, NOT YET FULLY QUALIFIED. R3.2B = DESIGN ONLY / HOLD.**
Do not start executor implementation until Gates A/B are evidenced on the same final SHA and policy requirements for Gate C are approved.
