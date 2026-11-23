# RM-TASK-01B/R1 — CRM Interaction → Task Follow-up

**Branch:** `base_dev` · **Date:** 2026-11-23 · **Scope:** Java/Spring service + PostgreSQL/Liquibase + synthetic E2E only · **Release:** HOLD until exact-SHA CI.

## Business journey

A CRM employee creates an OPEN customer interaction. An authorized manager with **both** `TASK_MANAGE` and `CRM_READ` schedules one or more predefined, non-confidential follow-up tasks for active employees of the same organization. Employee processes the task via the existing `TaskService`. The follow-up link preserves only tenant-qualified UUIDs, actor and timestamp. It never copies customer/contact names, phone, email or raw interaction notes into the task or event.

## Implementation boundaries

- Canonical task root stays `crm.task_item`; canonical interaction root stays `crm.customer_interaction`. **No new task aggregate**.
- Additive Liquibase 0144: `crm.task_crm_followup(tenant_id, task_id, interaction_id, created_by, linked_at)`. Composite FKs `(tenant_id,task_id)` and `(tenant_id,interaction_id)`, unique `(tenant_id,task_id)`; one task has at most one originating interaction but one interaction may have many tasks.
- Liquibase 0145: PostgreSQL immutable source link trigger. No update/delete of linked history.
- `TaskCrmFollowupService.create` requires verified actor, current `TASK_MANAGE` and `CRM_READ`; locks OPEN CRM interaction with ACTIVE customer `FOR UPDATE`, invokes existing `TaskService.create` in the **same transaction**, then writes the immutable link. CRM completion/cancellation cannot interleave after the business admission and before commit.
- A retry with the same task request key and same interaction returns the existing canonical task/link without new event; request key reused for another interaction rejects `TASK_CRM_LINK_REPLAY_CONFLICT`.
- `getMine` requires both `TASK_READ` (owner or current assignee) and `CRM_READ`. It does not expose the interaction identifier to employees without CRM authorization.
- Only fixed generic `TaskTitlePolicy` labels allowed; no arbitrary PII-bearing task text.

## Qualification

- 18: source interaction creates one task, exact retry returns same task, link is visible only with both read permissions, one task event.
- 19: foreign-tenant or terminal interaction rejects, zero task rows or links.
- 20: replay of the same request key against a different CRM interaction rejects without overwriting origin.
- 21: PostgreSQL source link immutable; composite tenant FK blocks unrelated tenants and orphan task IDs.
- Specialized TASKS suite: **21 PostgreSQL executed / zero skipped**; H2: **21 discovered, six PostgreSQL-only skipped**. The mandatory exact-SHA admission verifier must check that same evidence.

## Explicitly deferred

- Close interaction `COMPLETED/CANCELLED` after follow-up task creation: immutable link remains; separate lifecycle cancellation/notification policy required.
- Customer/contact data may be deleted or retained by CRM rules independently; this service never duplicates their PII.
- Due-date edits, task acceptance/rejection, team queues, notification outbox and new UI are out of scope.
- Real OIDC/JWKS, populated upgrade tests and production readiness require independent admission. Production HOLD.
- No arbitrary confidential text, no new controllers, no merge into `main`.

**Admission decision:** a passing specialized suite is necessary but insufficient. A successful mandatory exact-SHA workflow (same SHA, all required artifact digests) is also required before moving to RM-TASK-01B/R2.
