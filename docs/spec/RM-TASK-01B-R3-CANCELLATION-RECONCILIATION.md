# RM-TASK-01B/R3 — Cancellation, Queue and CRM Reconciliation

**Date:** 2026-11-23 · **Branch:** `base_dev` · **Admission:** candidate, exact-SHA pending · **Production:** HOLD.

## Business decisions

1. `CANCELLED` is a **terminal task state**, not `ARCHIVED`. Only a current `TASK_MANAGE` manager can explicitly cancel an unfinished task (`OPEN`, `IN_PROGRESS`, `BLOCKED`, `QUEUED`). `COMPLETED`, `ARCHIVED`, `CANCELLED` cannot be cancelled again with a new request. The exact original keyed replay may return its committed result subject to current IAM rights and current task version. Cancellation never deletes inventory, sales, CRM, task history, or customer data.
2. `QUEUED` is an unassigned task: manager may return `OPEN` or `BLOCKED` task to queue; the prior assignee immediately loses task ownership and execution access. An `IN_PROGRESS` task must first transition to `BLOCKED`; this avoids silently abandoning active work. Managers can explicitly assign an active tenant member from `QUEUED`, producing `QUEUED → OPEN`; neither employee nor scheduler automatically claims queue items.
3. CRM source `CANCELLED` is **not automatically propagated** to an existing follow-up task. A manager with both `TASK_MANAGE` and `CRM_READ` can list its pending follow-ups and deliberately reconcile a selected task using `SOURCE_CANCELLED`. This locks the source interaction before task CAS; wrong interaction, foreign tenant and non-cancelled CRM source are denied. `COMPLETED` and `ARCHIVED` task evidence is not modified. New follow-up task creation for non-OPEN CRM sources remains forbidden.
4. Task cancellation and queue transitions use a caller-provided `requestKey` for recoverable exactly-once *database effects* and immutable `crm.task_event` evidence; after a newer task transition, a replay returns `TASK_RECEIPT_SUPERSEDED` rather than pretending to return historical state. Old no-key commands retain their non-idempotent contract. There is no external notification/outbox side effect in this slice.
5. Rollback of a task cancellation or reconciliation within a database transaction rolls back both task state and event. Concurrent cancellation × completion and requeue × reassignment with the same expected version allow **one successful mutation** and no orphan event.

## Schema / ownership

- Liquibase `0147` expands the existing `crm.task_item.status` CHECK constraint with `QUEUED`, `CANCELLED`. It adds `crm.task_event.reason_code` constrained to closed non-confidential codes (`MANAGER_CANCELLED`, `SOURCE_CANCELLED`, `RETURNED_TO_QUEUE`) and bounded `idx_task_queue`. The original task aggregate and immutable event ledger remain canonical.
- No user-entered text is stored in the reasons, request receipts, CRM links or events. New task `title` continues to be restricted to approved generic non-sensitive values. Historical plaintext titles still need separate security/retention resolution.
- `TaskService.cancel`, `TaskService.requeue`, and `TaskService.assign` from queue carry tenant CAS and current IAM permission checks. `TaskService.cancelForSource` verifies both permissions and locks the actual CRM source.
- `TaskCrmReconciliationService.pending` returns only task IDs, state, assignee UUID and version (max 100, deterministic order). `reconcileCancelled` targets one specific task per transaction. No bulk all-or-nothing or automatic background job is claimed.

## Mandatory qualification

- T29: manager cancellation, exact keyed replay, immutable reason; archive/start after cancellation forbidden.
- T30: cancellation from IN_PROGRESS, completion denied, previous work history preserved.
- T31: return OPEN to QUEUED clears assignee; former employee access denied; explicit active-member assignment restores OPEN.
- T32: cannot requeue active IN_PROGRESS without first blocking; no execution while unassigned.
- T33: cancelled CRM source produces pending list, deliberate per-task reconciliation, completed task unaffected, exact retry.
- T34: injected rollback before reconciliation commit restores both task and event; retry succeeds.
- T35 PostgreSQL: concurrent manager cancellation versus employee completion, exactly one winner.
- T36 PostgreSQL: concurrent manager requeue versus reassignment, exactly one winner.

**Specialized release gate:** 36 discovered TASKS scenarios; PostgreSQL 36 executed / 0 skipped; H2 26 executed / 10 PostgreSQL-only skipped; all errors/failures zero. Additionally, all mandatory cross-domain workflows and the aggregate exact-SHA evidence workflow must succeed against the **same final SHA**.

## Out of scope / next hardening

- Automatic source-change reconciliation, batch job/outbox, notifications and new task acceptance/rejection require independent policy and qualification.
- Queue-wide manager search with cross-team RBAC, retention of abandoned tasks, reconciliation SLAs and user-controlled confidential descriptions need separate designs.
- Real OIDC/JWKS, external CRM cancellation tests, load/recovery, REST/UI and production admission remain **HOLD**.
- Keep all changes on `base_dev` with no merge to `main`.
