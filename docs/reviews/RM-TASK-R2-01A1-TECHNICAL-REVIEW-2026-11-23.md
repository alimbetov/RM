# RM-TASK-01A.1 — Technical Review & Hardening

**Date:** 2026-11-23 · **Repository:** alimbetov/RM · **Branch:** base_dev
**Scope:** service, IAM, PostgreSQL, H2, Liquibase, exact-SHA CI.
**Restrictions:** no controllers/UI, no new branch, no main merge, production HOLD.

## Baseline

Before review, `adebcefad4729c443c542af320e14dce78ff0359` had all 10 mandatory workflow runs SUCCESS, including TASKS H2/PG, common Module JUnit, security and exact-SHA admission. This qualified the 5-case original TASKS slice, not the subsequent hardening.

## P0 technical findings and corrections

1. **ASSIGNEE_ACCOUNT_RACE:** `TaskService.requireMember` previously read target employee status without locking the global account row. A concurrent account suspension could commit while a manager's task assignment was not yet committed. Fixed by transaction-scoped `iam_user_account FOR SHARE` (PostgreSQL) / `FOR UPDATE` (H2) before membership check; reassignment locks target account before task row to avoid introducing inverse task/actor lock ordering. Real PG suspension-after-creation test, suspension-first refusal; require active tenant membership.
2. **INCOMPLETE_ASSIGNMENT_AUDIT:** `ASSIGNED` events recorded version/state but neither previous nor new assignee, preventing audit-only responsibility reconstruction. Additive Liquibase 0142 adds two nullable actor FKs to immutable `crm.task_event`; service records old/new assignee for every transition. Tests assert old member loses access after reassignment and event contains exact IDs.
3. **LEGACY_NULL_ASSIGNEE_NPE:** `crm.task_item.assignee_user_id` was nullable in the original schema, whereas `assign` called `before.assigneeUserId().equals(...)`. Fixed with null-safe comparison and seeded legacy task test proving first assignment and append-only proof without rewriting any previous row.
4. **CI_MANIFEST_DRIFT:** 8 business tests ran successfully on PostgreSQL at earlier review candidate SHA `d0da38c0...`, but its workflow expected 5 and failed *only* the manifest assertion. Corrected the manifest; when the extra legacy test was added, raised mandatory total to 9.
5. **BOUNDARIES PRESERVED:** `TASK_READ` remains caller-owned/assigned only, `TASK_MANAGE` remains separate from execution; no automatic OWNER wildcard, no direct CRM mutation, no title PII added to event, no REST.

## Qualification matrix

`TaskServiceQualificationTest` must discover 9 tests:
- H2: 6 cases executed, 3 PostgreSQL-only skipped.
- PostgreSQL: 9 cases executed, zero failures/skips.
- Special PG cases: concurrent start exactly one, immutable history DML denied, suspension-after-creation serialized; complementary suspension-first denied.
- Full exact-SHA module/security/populated-schema workflows must be PASS on the **same final candidate SHA**; old SHA PASS is not reusable for final qualification.

## Remaining explicit risks and R2 prerequisites

- Task title is plaintext. Current API accepts arbitrary non-control operational text but cannot prove absence of customer PII. Before arbitrary end-user text, enforce business-coded templates or a qualified encrypted confidential note policy (KMS/PII, AAD and tenant scope).
- Membership revocation, permission withdrawal and reassignment under high concurrency are not fully characterized; PostgreSQL inverse-order races and lost-response retry proofs remain RM-TASK-01A.2.
- Legacy task rows may lack `created_by` and `title`; `listMine/getMine` remain restricted to caller assignee/creator, so pre-existing task ownership remediation is needed before broad user roll-out.
- No `ACCEPTED` or `CANCELLED` task state; these business decisions are separate.
- CRM link, due date edits, encrypted interaction follow-up, reminders and notifications remain RM-TASK-01B and later slices.
- External OIDC, real role/device UAT, generic load/DR, production admission remain HOLD.

**No production claim** until this exact SHA passes and the additional risks are independently accepted.
