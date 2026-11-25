# RM-TASK R32B-T00 — Contract & Dependency Qualification

**Date:** 2026-11-23 · **Branch:** `base_dev` · **Product:** RM Business · **Scope:** CRM cancellation → TASKS review SLA/outbox source contract.
**Status:** SOURCE REVIEW COMPLETE / DEPENDENCY ADMISSION HOLD. This is a source-level review, not a passed PostgreSQL concurrency suite or security approval.

## 1. Evidence reviewed

- `src/main/java/kz/rm/crm/CrmInteractionService.java`: `cancel`, `transition`, `event`, `history`.
- `src/main/java/kz/rm/tasks/TaskCrmReconciliationService.java`, `TaskReconciliationSnapshotService.java`.
- `src/main/java/kz/rm/iam/TenantAuthorization.java`, `DomainPermission.java`.
- `src/main/java/kz/rm/iam/OidcRevocationOutboxWorker.java`: existing IAM-specific lease/outbox pattern, not reusable notification worker.
- `src/main/resources/db/changelog/db.changelog-master.sql`: CRM interaction event schema, revision uniqueness and immutable PG trigger, TASK snapshot 0149–0152.
- `docs/spec/RM-TASK-01B-R3.2B-SLA-POLICY-DURABLE-OUTBOX-FOUNDATION.md`.
- `docs/reviews/RM-TASK-R32A-Q1-Q2-IMPLEMENTATION-STATUS-2026-11-23.md`.

## 2. Actual CRM cancellation transaction (verified from source)

```text
VerifiedActor / CRM_WRITE admission
  -> CRM interaction SELECT ... FOR UPDATE
  -> expected row_version CAS and OPEN-only transition
  -> UPDATE crm.customer_interaction status=CANCELLED, version+1
  -> INSERT crm.customer_interaction_event(action_code=CANCELLED)
  -> COMMIT via public @Transactional cancel(...)
```

The event has `tenant_id`, `id` (UUID), `interaction_id`, `actor_id`, `action_code`, `previous_version`, `new_version`, `occurred_at`; it is immutable in PostgreSQL and has unique `(tenant_id, interaction_id, new_version)`. The private `event(...)` currently creates a random UUID and a timestamp internally and **returns void**. Thus TASKS cannot yet atomically consume the exact inserted event identity/time via a typed source-owner port.

**Distinct clocks:** `customer_interaction.occurred_at` describes the business interaction, not the cancellation event. `task_item.due_at` and snapshot `captured_at` must never start SLA. CRM event `occurred_at` must be passed from the actual inserted row.

CRM `OPEN` is the only state from which `CANCELLED` can be committed by the current service. There is no source `CANCELLED → OPEN` transition in this service. Event replay must use the canonical committed event ID; the CRM `cancel` entry point uses expected-version CAS rather than a cancellation request-key receipt.

## 3. Findings / admission decisions

| ID | Severity | Verified finding or blocking decision | Required resolution |
| --- | --- | --- | --- |
| T00-01 | P1 | CRM event identity/time are created in private `void event()`, not exposed to transaction-local TASKS integration. | Return a typed immutable `CancellationEventRef(tenantId,interactionId,eventId,occurredAt,newVersion)` originating from the exact inserted row. The CRM public result contract may remain unchanged. |
| T00-02 | P1 | CRM cancel currently has no typed TASKS obligation port; inserting outbox later by polling status is not same-transaction atomicity. | Define a narrowly scoped source-owner-owned `@Transactional(MANDATORY)` port, invoked only after successful CRM event insert, joining the **same DB transaction**. No new aggregate or HTTP boundary. |
| T00-03 | P1 | Existing authorization validates CRM_WRITE before source lock; TASKS manual reconciliation requires TASK_MANAGE + CRM_READ; policy activation asks for TASK_MANAGE + TENANT_RBAC_MANAGE but is not approved or wired. | Separate CRM actor's authority to cancel from manager's later authority to review. No synthetic VerifiedActor or automatic task cancellation. Policy activation permissions require explicit IAM/product decision and negative PG tests. |
| T00-04 | P1 | CRM source lock follows IAM authorization; policy activation pointer and TASKS hook have no proven lock order. | Document and test IAM→CRM→policy activation→obligation/outbox lock DAG, including competing revoke, cancel, policy switch and retry. Fail closed on deadlock/retry; do not invert lock order. |
| T00-05 | P1 | No deterministic eligible pending-task count contract at CRM cancellation boundary. | Under source lock, use tenant-qualified pending status query; define 0 pending = no actionable intent, at least 1 = exactly one obligation for an approved active policy. Test 0,1,100,10000,10001; no truncation or invented obligation if >limit. |
| T00-06 | P1 | Policy revision/activation and consent are not approved; no SLA period or calendar may be invented. | Require explicit approved tenant revision and `ELAPSED_UTC`; `BUSINESS_TIME` remains STOP until qualified calendar. Fix duration, ownership, retry/retention governance separately. |
| T00-07 | P1 | R3.2A exact-SHA, populated PG upgrade and operational gates have not been evidenced as closed in available records. | Bind eventual B1/B2 admission to a **same-final-SHA** successful R3.2A + mandatory CI manifest; no inherited PASS. |
| T00-08 | P2 | IAM OIDC outbox worker has a working *pattern* but different identity/protocol/security domain. | Reuse only general transaction and fencing principles; do not reuse IAM tables, payload or scheduler. No actual notification delivery in B. |
| T00-09 | P1 | An `action_code='CANCELLED'` event could be mistaken for task cancellation, or its `new_version` for task row_version. | Source kind fixed as `CRM_INTERACTION_CANCELLED`; composite source identity `(tenant_id,source_event_id)`; separate task and CRM version namespaces. |
| T00-10 | P1 | No evidence yet that B1/B2 can be switched on without altering existing CRM semantics when disabled. | Feature OFF: CRM cancel result, event count and concurrency unchanged. Feature ON: atomic event/obligation/intent commit; injected failure rolls back all three. |

## 4. Proposed typed port contract (DESIGN, not implemented)

```java
record CrmCancellationEventRef(
    UUID tenantId,
    UUID interactionId,
    UUID eventId,
    Instant occurredAt,
    long newVersion
) {}

interface CrmCancellationReviewIntentPort {
    // Must participate in the CRM transaction; never REQUIRES_NEW.
    void onCancelled(CrmCancellationEventRef event);
}
```

**Transaction and consistency rules:**
1. CRM owns event creation, event ID and timestamp. Avoid selecting `max(occurred_at)`, creating another cancellation event, or calling TASKS asynchronously without durable capture.
2. Port must run in the already-authorized CRM source transaction. If feature OFF, its implementation is a no-op. If a tenant lacks an approved active policy, no notification intent is emitted.
3. TASKS owns review obligation/outbox tables only when approved later. Composite tenant FKs and `UNIQUE(tenant_id, source_event_id, event_type)` are mandatory; ensure an outbox attempt never creates an additional CRM cancellation event.
4. Policy selection must serialize on a tenant-policy activation guard; a revision valid at event occurrence is selected deterministically. `occurredAt` is the SLA trigger.
5. No actor ID or contact data in outbox payload as routing destination; actor only stays within protected audit fields. No automatic task cancellation, CRM mutation or synthetic manager elevation.
6. Fail-closed decisions for cancellation with no pending tasks, policy absent/retired, suspended tenant, missing approver, or unsupported calendar mode. No retroactive backfill by default.

## 5. Mandatory T00 qualification scenarios

| Case | Proof | Expected |
| --- | --- | --- |
| T00-C01 | CRM OPEN→CANCELLED event written with source row update in single tx | exactly one immutable CRM event |
| T00-C02 | stale version, already completed/cancelled, cross tenant | zero new events/intent |
| T00-C03 | CRM cancel rollback injected after event insertion | no status/event/intent committed |
| T00-C04 | two concurrent CRM cancel callers with same expected version | one winner; one event and one optional intent |
| T00-C05 | eventId/occurredAt passed to typed hook from inserted row | exact source identity and time |
| T00-C06 | disabled policy/feature | CRM behavior unchanged; no obligation/intent |
| T00-C07 | eligible 0 vs 1+ pending tasks | no fake review for zero; exactly one for eligible |
| T00-C08 | approved policy switch races cancellation | one deterministic policy revision, not duplicate |
| T00-C09 | CRM cancellation races IAM revoke/account suspension | no invalid privileged commitment |
| T00-C10 | outbox insert injected failure after event | entire CRM cancellation rollback |
| T00-C11 | post-COMMIT response lost, same event reprocessed | same obligation/intent IDs, no duplicates |
| T00-C12 | source event vs snapshot/task timestamps | SLA starts only from CRM event occurred_at |

C05–C12 cannot be marked PASS until B1/B2 support exists. Tests must be actual PostgreSQL two-connection integration tests, not just mocks or a document checklist.

## 5A. T00 implementation increment — canonical event handoff (2026-11-23)

Source changes in `base_dev` now include:
- `CrmCancellationEventRef` (tenant, interaction, actual event UUID, microsecond-precision occurredAt, CRM newVersion).
- `CrmCancellationReviewIntentPort` and the only registered implementation `DisabledCrmCancellationReviewIntentPort`, a `@Transactional(MANDATORY)` no-op with non-null event validation.
- `CrmInteractionService` creates `eventId` and `occurredAt` once, writes the same values into the CRM event row, and calls the disabled port **after successful cancellation event INSERT but before transactional return**. This leaves the CRM public cancel method signature unchanged.
- Two JUnit baseline tests: canonical cancellation event identity/revision and outer-transaction rollback after event insert. These are newly committed tests, **not yet PostgreSQL-executed qualification evidence**.

This increment closes the **source implementation** aspect of T00-01/T00-02 but not T00 admission. Feature remains OFF. No policy/obligation/outbox DDL, scheduler, notification or IAM grant was created. If future feature enabling changes the implementation of the port, CRM cancellation must remain fail-closed and atomically rollback on port failure; actual PostgreSQL dual-connection tests and policy approval are still mandatory.

## 6. T00 exit gate / implementation order

**T00 source review: completed; typed source handoff implemented with disabled sink. T00 dependency qualification: HOLD pending real PostgreSQL tests, IAM/SLA approvals and same-SHA CI.**

- **Before B1:** close/verify R3.2A gates, approve permissions and policy semantics, establish T00-C01..C04 baseline and source-event typed port contract.
- **B1 design/DDL next, only when above decisions are explicit:** immutable policy revisions, activation guard, review obligation and evidence; do not implement an outbox at the same time by assumption.
- **B2:** wire the CRM transaction-local port and outbox insert; run C05–C12 with crash and concurrent interleavings.
- **B3/B4:** lease/fencing and populated PostgreSQL gates with fake adapter, no real delivery.
- **R3.2C:** scheduler/provider/consent activation remains separate STOP.

**Project constraints:** `base_dev` only; no `main` merge, controllers, UI, worker, real notifications, Redis/Kafka or new microservice. Production HOLD.
