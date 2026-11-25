# RM-TASK-01B/R3.1 — Manager Queue & CRM Reconciliation Hardening

**Branch:** `base_dev` · **Date:** 2026-11-23 · **Scope:** Spring service + H2/PostgreSQL + evidence CI; no REST/UI; no new aggregate or branch. **Production:** HOLD.

## Dependencies and baseline

R3 cancellation, requeue, queue-to-assignment and manual per-task CRM reconciliation are already implemented and exact-SHA qualified at `8898f03c3a0a1db66f87d4efe4734d904afc9482`. This slice improves operational discoverability and cursor traversal **without** adding a scheduler, implicit CRM cancellation or customer PII duplication.

## New service contracts

1. `TaskService.managerQueue(actor,tenant,limit,afterId)`: requires verified actor and **both** `TASK_MANAGE` and `TASK_READ` in the same active tenant; returns only `QUEUED` rows, ordered by UUID. A bounded page of 1..100 items uses `LIMIT (requested+1)`; `nextAfterId` is the final emitted item UUID only when more rows exist. Employee access is forbidden, and previous assignee remains unable to read unassigned queue entries through `getMine`. Canonical status/version/assignment are read from `crm.task_item`; no second queue table.
2. `TaskCrmReconciliationService.pendingPage(actor,tenant,interactionId,afterTaskId,limit)`: requires `TASK_MANAGE` plus `CRM_READ`, returns only nonterminal linked tasks for a source with `crm.customer_interaction.status='CANCELLED'`, with the same bounded UUID keyset cursor. No customer name/contact/note/PII is returned. Non-cancelled or foreign source yields empty page, never foreign data.
3. **Cursor semantics:** the UUID cursor is an **exclusive lower bound**, not a snapshot token. New rows with UUID below the current cursor, or rows leaving the queue between page reads, can affect cross-page completeness. Pages are individually authorized and tenant-scoped; clients must refresh a full scan when strong completion guarantees are needed. No claim of tenant-wide repeatable-read across requests.
4. **Reconciliation is review-driven:** `pendingPage` has no writes or side effects. Only existing manager `reconcileCancelled` may cancel individual tasks under the existing version/CAS and immutable event contract. Do not automatically act on CRM cancellation.
5. **No invented SLA:** `due_at` describes task due time, not the deadline for CRM reconciliation. Approval of a separate reconciliation SLA and a durable outbox/worker is required before autonomous escalation or batch processing.

## Test matrix

- T39: five QUEUED tasks, keyset traversal across pages, no duplicates, queue carries no assignee; employee denied, page bounds enforced.
- T40: foreign tenant tasks do not appear, nonmanager denied, tenant-bound read.
- T41: five follow-up tasks on CANCELLED CRM source, keyset traversal after one task is reconciled; no duplicates in traversal; cancelled task removed from new pending scan, employee denied.
- T42 (PostgreSQL): task observed in a queue page is claimed before refresh; next queue read no longer returns task; stale second claim rejected, exactly one ASSIGNED event.

**Admission:** 42 cases discovered; PostgreSQL executes all 42 without failures/skips; H2 executes 30 and skips 12 PostgreSQL-only cases. The matching six-workflow QA01B exact-SHA verifier must verify these tests and all other mandatory domain artifacts on the **same commit SHA**.

## Deferred hardening

- Snapshot-isolated cross-page queue/reconciliation reports and admission of SLA-based background reconciliation.
- Role-scoped manager teams/sub-organizations if RM later enables narrower ownership than current tenant-wide `TASK_MANAGE`.
- Historical PII inventory/remediation, KMS-encrypted task notes, legacy title migration and retention policies.
- Liquibase `0148` adds the non-destructive `idx_task_queue_keyset(tenant_id,status,id)` to support the manager queue's UUID ordering. Large-tenant EXPLAIN ANALYZE, populated-schema upgrade, planner qualification and database backup restore remain separate gates.
- External OIDC/JWKS, controller/UI, production admission. **HOLD**.

No `main` change, no automatic CRM-to-task state propagation, no task/CRM data duplication.
