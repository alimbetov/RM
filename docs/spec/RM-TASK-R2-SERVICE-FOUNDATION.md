# RM-TASK-01A — Employee Task Lifecycle, Service-First R2

**Date:** 2026-11-23 · **Branch:** `base_dev` · **Status:** candidate; exact-SHA qualification pending · **Production:** HOLD

## Goal / explicit business journey

A tenant-authorized manager creates a non-PII operational task for an active organization employee. The employee sees the assigned task, starts, blocks/resumes and completes it. The manager archives a completed task. All transitions preserve tenant boundaries, row-version CAS and append-only event history.

**Do not claim:** complete RM TASKS, CRM follow-up linkage, free-text confidential notes, notifications, cancellation, scheduler, personal portfolio for groups, REST/UI, or production identity admission. Existing `crm.task_item` remains the sole task root. No second task table.

## Commands and access

- `TASK_MANAGE`: create, reassign (OPEN/BLOCKED), archive only COMPLETED. No implicit OWNER wildcard once tenant RBAC enforced.
- `TASK_EXECUTE`: only the current assigned employee may start/block/resume/complete. Must be ACTIVE account/member within the same selected tenant.
- `TASK_READ`: get/list/history of tasks created by or assigned to the caller. No general tenant-wide listing or exposure of foreign employee tasks.
- `VerifiedActor` and `tenant = iam_workspace.id` through `TenantAuthorization.requireForCommand`; privileges are rechecked in the command transaction.

States: `OPEN → IN_PROGRESS → BLOCKED → IN_PROGRESS → COMPLETED → ARCHIVED`. Completion may also occur directly from IN_PROGRESS. Reassignment permitted only from OPEN/BLOCKED. Task cancellation remains **DECISION / next slice**; no artificial CANCELLED mapping to ARCHIVED.

## Data ownership and consistency

Additive Liquibase 0140 extends `crm.task_item` with `title`, `created_by`, unique `(tenant_id,created_request_key)`, semantic SHA and `updated_at`. Original task row remains canonical. Create requests serialize on 32 persisted stripes; same key/actor/payload yields the same task, changed payload fails. Event table `crm.task_event` references tenant/id and actor, contains from/to states, versions, time, and is immutable on PostgreSQL by 0141 trigger. Status CAS and event commit atomically.

No names, phone, email, contact notes, tokens or secret payloads go into task event or request fingerprint. Plain `title` must contain operational non-sensitive text; confidential description/CRM PII requires separate encrypted-field design. The SQL schema is not yet a mandatory non-null `title` for pre-existing task rows. No direct deletion of historical events.

## Acceptance tests

- T01: idempotent task creation and OPEN→START→BLOCK→RESUME→COMPLETE→ARCHIVE; event history matches versions.
- T02: tenant A/B reference poisoning; staff cannot manage/archive; cross-tenant assignment rejected.
- T03: stale version or forbidden state transition: no event or state mutation.
- T04: real two-transaction PostgreSQL concurrent starts: exactly one successful mutation.
- T05: PostgreSQL trigger denies event deletion.

H2: 5 discovered, 2 PG-only skipped. PostgreSQL: 5 discovered, zero skipped/failures. Exact-SHA workflows for this candidate and full mandatory CI are required separately; a newly committed file is not evidence of successful execution.

## Next RM-TASK-01B (not implemented)

After 01A passes: owner-scoped CRM interaction follow-up linkage with composite tenant FK and authenticated CRM permission; accept/reject/return-to-queue behavior and reassignment policy must be decided, plus due-date filters, employee portfolio, notification outbox, IAM revoke/suspension race and crash/retry tests. Do not widen employee rights to CRM_WRITE simply to execute tasks.

**Core constraints:** one branch `base_dev`, no controllers or main merge, no Redis or microservices, production HOLD.


## RM-TASK-01A.1 — targeted technical review and hardening (2026-11-23)

Initial exact-SHA admission completed successfully at `adebcefad4729c443c542af320e14dce78ff0359`, but that qualification did not yet prove concurrent IAM suspension of a *different* task assignee or reconstruct full responsibility changes from immutable history.

**P0 corrections implemented:**
- Before assignment or creation, lock the target assignee's global `iam_user_account` row until transaction commit: PostgreSQL `FOR SHARE` (allowing concurrent admitted employee reads), H2 `FOR UPDATE` compatibility. Require `ACTIVE`, then tenant-scoped `ACTIVE` membership. For reassignment the target account is locked **before** the task row, preserving lock-order discipline; any later IAM account suspension must serialize after the command or the task command rejects if suspension committed first.
- Add Liquibase `0142` nullable assignee-before/assignee-after UUID FK columns to append-only `crm.task_event`. `CREATED` captures the initial assignee, `ASSIGNED` captures both actors; subsequent state events capture the same assignee on both sides. No rewriting old event history or synthesizing prior data.
- A former assignee loses `getMine` task access after reassignment; creator can inspect the history.
- Negative tests for suspended assignee, changed assignee historical evidence, and PostgreSQL `task create -> concurrent account suspend` ordering. Complementary suspension-first path denies creation or reassignment without side effects.
- The expected dedicated matrix is **8 scenarios**, with **3 PostgreSQL-only skips on H2** and no skips in PostgreSQL.

**Remaining non-blocking decisions before RM-TASK-01B:** confidential free-text titles are currently not encrypted or classified by server-side business semantics; callers must restrict their contents to non-sensitive operational descriptions. Do not place CRM PII in task titles. Establish an encrypted task description or closed business-coded task template contract before enabling arbitrary user-entered text. Further PostgreSQL IAM membership-revocation races, cross-tenant performance, two-manager reassign contention and crash/retry require separate qualification. Real external identity, REST and production admission remain HOLD.

The implementation and updated test matrix are **not** considered qualified until the *same latest SHA* GitHub Actions complete successfully.
