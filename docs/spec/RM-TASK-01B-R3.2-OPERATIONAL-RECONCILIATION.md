# RM-TASK-01B/R3.2 — Operational Reconciliation, SLA Policy & Outbox Architecture

**Date:** 2026-11-23. **Branch:** `base_dev`. **Scope:** Java/Spring service and immutable PostgreSQL snapshot foundation; SLA and outbox are documented design contracts, not enabled runtime. **Production:** HOLD.

## Dependencies and admission

R3.1 latest baseline: `38a082073b1f9975794c5d7f42e4760dc51f3a70`. Specialized TASKS, CRM, SALES, INVENTORY, module and RustFS qualification were successful; the aggregate exact-SHA was **still running at admission assessment** and cannot be inferred as PASS. Do not reuse another SHA's evidence. R3 cancellation, manager queue and keyset pagination are canonical; no second task domain.

## Problem statement

R3.1 `pendingPage(afterTaskId)` provides bounded incremental traversal, NOT a stable snapshot. Task status transitions can remove rows between pages; new tasks would also affect pagination if source status policy changes. An operations review needs a fixed **enumeration of task IDs** that is auditable while permitting live task state changes.

## R3.2 implemented snapshot boundary

- Additive Liquibase 0149 creates `crm.task_reconciliation_snapshot` and `crm.task_reconciliation_snapshot_item`. Both use `tenant_id` and composite tenant-qualified FKs to canonical CRM interaction/task; only IDs and closed state/version data, actor ID, request key, timestamp and item count are retained. PostgreSQL migration 0150 rejects UPDATE/DELETE on both tables.
- `TaskReconciliationSnapshotService.capture(actor,tenant,source,requestKey)` requires verified actor with `TASK_MANAGE + CRM_READ`. It locks the tenant's CANCELLED CRM Interaction before capturing all currently pending linked task IDs in a single SQL statement. Max 10,000 tasks per snapshot: above cap **fail closed** rather than silently truncate. Same actor+tenant+requestKey returns exactly the existing snapshot for the same source; different source conflicts; schema unique key protects simultaneous requests.
- `page(actor,tenant,snapshotId,afterTaskId,limit)` reads the immutable captured set ordered by task UUID, with bounds 1..100. Only the manager who created that snapshot and still has both current grants may retrieve it; cross-tenant and other actors fail closed. Page cursor is exclusive and stable because manifest membership cannot be changed.
- A snapshot is **evidence, not a command**. Captured task state/version may be stale. Reconciliation must call existing `TaskService.cancelForSource` with fresh current IAM and row-version CAS; no bypass of `TASK_MANAGE`, `CRM_READ` or source-status check. No implicit cancellation, notification, transfer or deletion occurs on capture/page.
- A capture provides a statement-consistent list of task IDs; it is **not** a multi-request transactionally consistent view of their evolving status. New captures may include a different set. Snapshot persistence requires future approved retention and access auditing.

## Qualification

- T43: 5 task IDs, immutable pagination with status change between page reads, no duplicates/omissions; live stale version fails CAS.
- T44: invalid source, cross-tenant and employee read denial, invalid page, actor-scoped replay conflicts.
- T45: injected rollback before snapshot commit leaves no manifest/items, exact request succeeds on retry.
- T46 (PostgreSQL): snapshot header and entries reject update/delete through immutable triggers.
- Mandatory gate: 46 PostgreSQL tests (0 skipped) and 46 discovered H2 (13 PostgreSQL-only skipped); all six required domain/workflow artifacts with exact SHA and matching checksums.

## Proposed SLA — **not yet approved for execution**

| Attribute | Contract |
|---|---|
| Scope | Tenant and policy revision; later optional business unit or task type |
| Trigger | CRM interaction enters `CANCELLED`, recorded from CRM's canonical transition/event |
| `reviewWithin` | Positive `Duration`, tenant-configurable; **no hardcoded default or automatic timing** |
| Clock | UTC instants for calculation; calendar/business hours defined only by approved tenant policy |
| Escalation | `PENDING_REVIEW → OVERDUE_REVIEW → ACKNOWLEDGED` as operational projection, not TaskState |
| Responsibility | Authorized task manager; tenant/role checks on each review and escalation |
| Changes | Immutable policy versions with effective dates; active snapshot records policy version when implemented |
| Notifications | Disabled until a delivery channel, consent policy, retry budget and retention are approved |
| Auto cancellation | Explicitly forbidden; **no escalation may modify task state automatically** |

Policy approval needed for the SLA duration, business hours, holidays, ownership, overdue reminders, security retention, backups, deletion rules and legal compliance. These fields are **design only**, no scheduler, worker or DB policy table in this slice.

## Reliable notification outbox — **design, not running code**

Use a tenant-qualified `task_notification_outbox` keyed by event UUID and `(tenant_id, event_type, source_event_id)` UNIQUE. A future transaction that records an authorized business event inserts its outbox intent in that **same database transaction**. Include event schema version, referenced task/event IDs, minimal non-PII payload, creation timestamp, destination/route policy identifier and delivery state. No raw task title, phone, email, CRM content or auth token in payload.

Dispatcher contract for a later slice:
1. Poll using bounded `FOR UPDATE SKIP LOCKED`; lease with expiration and monotonically increasing fencing generation. Workers revalidate lease before state changes.
2. Delivery is **at-least-once**, never claim exactly-once external delivery. An external consumer must deduplicate on immutable event UUID / idempotency key.
3. Retry with bounded exponential backoff and jitter; durable attempts/error classification, terminal dead-letter state and explicit operator replay. No holding DB lock during network calls.
4. Authenticated delivery adapter (provider credentials out of payload), destination consent/tenant verification, rate limits, network timeouts and circuit breaking.
5. Only acknowledge after provider's verifiable success; crash after send but before ACK may resend. Block sending on global IAM revocation when identity-sensitive, according to separately approved notification rules.
6. Do not introduce a new general messaging broker or Redis solely for TASKS; use the project's existing PostgreSQL outbox conventions after verifying their contracts.

## Remaining gates

- At scale: snapshot >10k requires partitioned snapshot/streaming design; the current cap is intentional and prevents false claims of completeness.
- PostgreSQL populated-upgrade, concurrent source status change during capture, idempotency and after-commit worker failures need dedicated real-infra qualification.
- Snapshot retention, encryption for historical plaintext title and sensitive notes, KMS/AAD, tenant data deletion and legal review.
- Real OIDC/JWKS, production auth and external notifications: **STOP**.
- No controllers/UI, no `main` merge, production **HOLD**.
