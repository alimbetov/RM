# RM-TASK R2 → R3 — Project Consistency & Technical Review

**Date:** 2026-11-23. **Branch:** `base_dev`. **Scope:** CRM/TASKS services, IAM admission, transactional ownership, PostgreSQL/Liquibase, CI. **Production:** HOLD. No controllers, UI, or merge into `main`.

## Reconciled factual baseline

The reviewed branch had already implemented **RM-TASK-01B/R3** (not merely planned) before this audit. At SHA `52071a49c1dd1af1dafa546caedf30a9bdf2c3a1`, all nine matching GitHub Actions runs were SUCCESS, including common exact-SHA qualification. The R3 implementation comprises:
- `TaskService.cancel`, `requeue`, `assign` from QUEUED and `cancelForSource`; `TaskCrmReconciliationService.pending/reconcileCancelled`.
- Single canonical `crm.task_item`, append-only `crm.task_event`, immutable tenant-qualified CRM follow-up association; Liquibase `0140–0147`.
- Explicit state split: `COMPLETED` (work done), `ARCHIVED` (done and archived), `CANCELLED` (unfinished work stopped), `QUEUED` (unassigned waiting for manager).
- Never automatically delete/cancel a task on CRM Interaction cancellation; manager with both `TASK_MANAGE` and `CRM_READ` reviews and reconciles individually. `IN_PROGRESS` requires BLOCKED before requeue.

This audit was requested *before* R3 implementation, but R3 already existed when read. Avoid double-implementation or creating alternate state machinery.

## Findings and corrections in this review

1. **ARCH-01 — unnecessary concrete dependency (corrected).** `TaskCrmFollowupService` injected `CrmInteractionService` but never used it. Removed import, field and constructor parameter. The service still depends on the existing canonical CRM tables through tenant-qualified read/locks, rather than duplicating aggregate state or introducing a new orchestration layer.
2. **QA-02 — wrong-source and cross-tenant reconciliation negative proof (added).** T37 asserts an unrelated interaction inside the same tenant and a foreign tenant interaction cannot authorize cancellation of a task; no extra event/state mutation may commit.
3. **QA-03 — source cancellation versus follow-up creation interleaving (added).** PostgreSQL-only T38 keeps the originating CRM interaction row locked during task-and-link insertion, then races a competing CRM cancellation UPDATE. It proves the previously admitted create commits exactly once, source closure completes subsequently, new follow-ups are denied, and exact-key retry returns the original task. This is synthetic database interleaving, not full external/UI CRM E2E.
4. **QA-04 — mandatory evidence enforcement (updated).** Dedicated TASKS workflow and common exact-SHA evidence verifier now require **38 PostgreSQL cases, zero skips**, **38 discovered H2 cases with 11 PostgreSQL-only skips**. Synthetic verifier fixtures include both cases. No test count relaxation.

## Project architecture conformance

| Contract | Assessment |
|---|---|
| Modular monolith, Spring service-first, PostgreSQL | Conforms; no controller, Redis, microservice or extra task aggregate |
| Tenant data ownership | Composite FK task↔CRM interaction, tenant-bound queries and negative proof |
| IAM and permissions | Separate TASK_MANAGE, TASK_EXECUTE, TASK_READ, CRM_READ; current admission revalidated |
| Concurrent mutations | Task-row lock, version CAS, immutable event revision; manager races qualified |
| Lost-response retries | Actor-scoped immutable keyed command receipts; superseded results fail closed |
| CRM historical provenance | Interaction and link not overwritten or deleted by task cancellation/requeue |
| Commercial isolation | No SALES/INVENTORY/FX state changes or transactional side effects in TASKS |

## Important remaining risks (NOT admitted)

- **PII/retention:** Historical plaintext `crm.task_item.title` and legacy null creator/assignee rows need inventory, migration/remediation and approved retention policy. The current fixed-title policy applies to new commands only. Do not allow arbitrary confidential notes without a dedicated KMS/AAD encryption design and populated migration qualification.
- **Real IAM/identity:** The race tests are synthetic and do not replace real OIDC/JWKS, tenant membership administration qualification, revocation and session expiry acceptance.
- **Operational reconciliation:** `pending` is bounded (max 100) without a paged reconciliation job or SLA/outbox; automated source-change detection and reliable notifications are NOT IMPLEMENTED. A cancelled CRM source is not a permission for background auto-cancellation.
- **Queue visibility:** Returning a task to QUEUED clears assignee. An organization-wide manager queue query with role-safe pagination and team boundaries remains a separate requirement, not implied by `listMine`.
- **Crash/recovery:** Single database transaction rollback and keyed retry are qualified; distributed messaging, post-commit event delivery, backup restore and load/latency qualification remain pending.
- **Service API:** Existing legacy no-request-key overloads do not provide exactly-once retry guarantees. New callers should use keyed commands and retain expected version.
- **Release:** Technical green is not production readiness; production HOLD, controllers/UI STOP, `main` unchanged.

## Admission / next decision

The historical SHA `52071a49c1dd1af1dafa546caedf30a9bdf2c3a1` was fully exact-SHA green. The *new* review commits require a **fresh, same-final-SHA** green specialized TASKS workflow, domain CI and overall exact-SHA gate before marking this hardening qualified. No reuse of previous SHA evidence.

**Recommended next scope:** formal **R3.1 follow-up reconciliation qualification** (manager queue visibility, paging/cursors, cancellation SLA, membership-revocation races, after-commit failure recovery) without starting controller/UI. Do not reimplement R3 cancellation and queueing, which already exist.
