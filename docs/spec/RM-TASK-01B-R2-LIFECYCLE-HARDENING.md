# RM-TASK-01B/R2 — CRM Follow-up Lifecycle Hardening

**Date:** 2026-11-23. **Branch:** `base_dev`. **Scope:** service-only, Java/Spring, PostgreSQL/H2; no controllers/UI. **Production:** HOLD.

## Approved business behavior

1. **CRM Interaction cancellation:** An already-linked task is not silently cancelled, deleted, reassigned, or archived. The immutable link and task event history survive source `CANCELLED` and `COMPLETED`. Workers may finish their current task. Managers must explicitly decide further action; cancellation/reconciliation policy beyond this first vertical is deferred. New follow-ups are refused on any non-OPEN interaction; exact keyed retries of previously committed follow-ups remain idempotent after source closure.
2. **Due date:** `TaskService.reschedule(actor, tenant, taskId, dueAt, expectedVersion, requestKey)` is restricted to current `TASK_MANAGE`. Allowed in `OPEN`, `IN_PROGRESS`, `BLOCKED`; forbidden for `COMPLETED` or `ARCHIVED`. A CAS increments the same canonical task row version and appends immutable `DUE_DATE_CHANGED` evidence containing previous and new timestamps. Null due date invalid. A key validates exact command fingerprint and preserves the existing superseded-receipt rule. No auto changes to `crm.customer_interaction.next_action_at` or commercial records.
3. **Concurrent assignment:** Both `assign` and `reschedule` serialize on the task row with expected-version CAS. Only one concurrent mutation with the same expected version can succeed; loser receives `TASK_STALE_VERSION` and creates no event. IAM target admission and workspace membership checks remain mandatory for assignment.
4. **Crash/retry:** Creating the linked task and CRM follow-up row is a single transaction. A rollback before commit leaves neither task nor link, so exact request can be retried. For an already committed request, repeated invocation returns the same task, including after the source becomes terminal. Separate notification outbox, crash after DB commit with external effects, and distributed UAT remain deferred.
5. **Privacy:** `title` is a restricted non-confidential enum-like label, not a free-text CRM note. No customer name, email, phone or notes are written to task event, receipt, or link. Existing plaintext titles from historical rows still require retention/cleanup qualification.

## Data changes

Liquibase `0146` adds `previous_due_at` and `next_due_at` as nullable columns of immutable `crm.task_event`. No alternative task aggregate or duplication of CRM interaction/customer identity. Earlier task event records remain untouched.

## Required qualification

- T23: manager reschedules, keyed retry returns same version, mismatched replay fails, immutable previous/next date evidence.
- T24: terminal task rejects reschedule; old command receipt after subsequent transitions rejects as superseded.
- T25: a cancelled CRM Interaction leaves prior task and history available; new follow-up creation denies.
- T26: synthetic abort before transaction commit rolls back both canonical task and CRM link; retry creates exactly one.
- T27 (PostgreSQL): concurrent reschedule × reassign with same version has exactly one winner and one additional event.
- T28 (PostgreSQL): two distinct authorized managers concurrently reassign to different active employees; exactly one wins, version advances once and only one ASSIGNED event commits.

Full TASKS matrix: **28 discovered tests**, **20 H2 executed / 8 PostgreSQL-only skipped**, **28 PostgreSQL executed / 0 skipped**. Exact-SHA CI must include TASKS evidence and all mandatory domain workflows. A green targeted suite is not production admission.

## QA process repair

Before R2, exact-SHA verifier `verify_rm_qa_01b.py` had already qualified six workflows with `qa01bGate=PASS` but `rm_r35_maturity_report.py` hardcoded five; resulting overall workflow failed. R2 changes report verification to require six independently verified workflow digests, and adjusts the synthetic report tests to six. This is a gate contract fix, not changing the historic R3 denominator of 75 capabilities.

## Next R2 slice

Follow-up lifecycle reconciliation after source cancellation, possible `CANCELLED` task state, secure confidential notes with KMS/AAD/key rotation, owner-only requeue, scheduled reminders and notification outbox need separately approved designs. Do not add these by implication. Real OIDC, API/UI and production admission remain HOLD. No `main` merge.
