# RM-TASK-01B/R3.2B — SLA Policy & Durable Outbox Foundation

**Дата:** 2026-11-23. **Репозиторий/ветка:** `alimbetov/RM` / `base_dev`.  
**Исходный код для согласования:** `dfff318a11fd72223745285ca3d6f168212a16ec` (HEAD на момент source review).  
**Состояние документа:** **DETAILED IMPLEMENTATION SPEC / DESIGN APPROVAL PENDING**. Файл — ТЗ, не доказательство созданного кода, запуска таймеров либо PASS тестов.  
**Product:** RM Business → TASKS / CRM Interaction. **Scope:** Java 21 / Spring Boot modular monolith, существующие application services, PostgreSQL/Liquibase, H2 diagnostics, JUnit and exact-SHA CI.  
**STOP:** новые REST-контроллеры/UI, автоматическая отмена задач, реальные отправки, планировщик, почтовый/SMS/push-провайдер, Redis/Kafka/второй TASKS aggregate, новый branch, merge `main`. **Production: HOLD.**

> **R32B-T00 source review (2026-11-23):** [Contract & Dependency Qualification](../reviews/RM-TASK-R32B-T00-CONTRACT-DEPENDENCY-QUALIFICATION-2026-11-23.md). Проверено: CRM `cancel()` записывает immutable `CANCELLED` event в той же транзакции. **Source contract corrected:** введены canonical `eventId/occurredAt` и transaction-mandatory порт с отключённым sink; PostgreSQL и IAM/SLA admission ещё не подтверждены. **До T00 gate нельзя начинать B1/B2 код или DDL на предположениях.** R3.2A independent gates по-прежнему обязательны. 

## 0. Цель, инженерное решение и смежные документы

**Проблема:** R3.1 `pendingPage` предоставляет живую пагинацию, R3.2A `TaskReconciliationSnapshotService.capture/page` фиксирует неизменяемый набор задач с SHA-256 и ограничением 10 000. Но пока отсутствуют **версионированные правила срока управленческого рассмотрения после отмены CRM Interaction**, аудит обязательства по рассмотрению и **надёжное, дедуплицируемое намерение уведомить ответственного**. Снимок — evidence, не уведомление и не команда на отмену.

**R3.2B должен подготовить управляемую SLA-политику и PostgreSQL durable-outbox foundation, безопасную даже без внешнего провайдера.** Разделить:
- **B0 — policy/architecture approval:** выбор понятных бизнес-правил, tenancy, роли, правовые сроки и канал;
- **B1 — versioned policy + review obligation foundation:** immutable approved policy revisions, вычисляемый dueAt и отдельная operational review projection;
- **B2 — atomic source event→outbox intent:** нет потерянных и дублирующихся намерений при commit/retry/crash;
- **B3 — delivery-state infrastructure:** bounded claim/lease/fencing/retry/dead-letter и диагностический adapter/fake без реальной отправки;
- **B4 — PostgreSQL qualification + populated upgrade + exact-SHA integration**;
- **R3.2C (отдельное ТЗ и разрешение):** доверенный scheduler/dispatcher, внешняя доставка, consent/routing/операционные SLO, живые уведомления и escalation.

**Нормативные источники (не изменять прежние решения):**
1. `docs/architecture/RM-PLATFORM-STANDARD-R2.md`;
2. `docs/spec/RM-TASK-01B-R3-CANCELLATION-RECONCILIATION.md`: manager-only explicit `cancelForSource`, source cancellation **не отменяет задачу автоматически**;
3. `docs/spec/RM-TASK-01B-R3.1-QUEUE-RECONCILIATION-HARDENING.md`: current manager queue/keyset read is **not a snapshot**;
4. `docs/spec/RM-TASK-01B-R3.2-OPERATIONAL-RECONCILIATION.md`: snapshot IDs/state/version, seal, 10 000 cap and source owner admission;
5. `docs/reviews/RM-TASK-R3.2A-TECHNICAL-AUDIT-2026-11-23.md` and `docs/reviews/RM-TASK-R32A-INDEPENDENT-CLOSURE-GATE-2026-11-23.md`: unclosed R3.2A gates;
6. `docs/architecture/IAM-CRM-R22F-OIDC-REVOCATION-OUTBOX.md`, `src/main/java/kz/rm/iam/OidcRevocationOutboxWorker.java`: **pattern** for transactional claim/lease/attempt CAS only, not permission to reuse IAM table, provider, timeout, failure semantics or production activation;
7. `docs/architecture/RM-DUAL-PRODUCT-FOUNDATION-R1.md`: TASKS is RM Business domain, does not own CRM or commerce aggregates.

### Evidence baseline: actually present vs not yet implemented

| Exists in source | Contract | Do not misrepresent |
|---|---|---|
| `crm.task_item`, `crm.task_event`, `TaskService` | one canonical task; version CAS, keyed receipt, immutable history | no second task root or automatic status transition |
| `crm.customer_interaction`, `crm.customer_interaction_event`, `CrmInteractionService.cancel` | event `action_code='CANCELLED'` committed in CRM owner transaction, unique `(tenant,interaction,new_version)` | `task.due_at` is not CRM review SLA |
| `crm.task_crm_followup`, `TaskCrmReconciliationService` | tenant-qualified link; manager explicit reconciliation under `TASK_MANAGE + CRM_READ` | outbox worker cannot call privileged user commands as a system bypass |
| `crm.task_reconciliation_snapshot*`, sealed SHA | immutable enumeration / 10 000 cap / actor-scoped read | captured `row_version` not an authorization or replay token |
| `OidcRevocationOutboxWorker` | same-DB atomic claim, worker lease + attempt fencing; external at-least-once | this IAM worker is NOT a TASK notification engine |
| Current HEAD source | no TASK SLA policy table, TASK notification outbox or external TASK dispatcher confirmed | **R3.2B DESIGN ONLY** before implementing B1–B4 |

**Prior-stage dependency:** Before declaring R3.2B `CODE QUALIFIED`, verify R3.2A's populated PostgreSQL upgrade of changesets 0148–0153, independent seal/digest check after restart, exact 10 000 success and 10 001 rejection, CRM cancellation/IAM revocation races, after-COMMIT response-loss retry and full current-SHA evidence. R3.2B *design* may proceed while those gaps remain; no inheritance of historical PASS.

## 1. Product behavior and state boundaries

**BIZ-R32B-01: Manager review obligation.** When an existing CRM Interaction changes `OPEN→CANCELLED`, that **immutable CRM event** is the business source for one review obligation, provided an explicitly approved policy is in force. The trigger is the **event's committed source occurrence time**; task `due_at`, snapshot `captured_at`, system polling instant and customer's next contact time are **not** SLA start.

**BIZ-R32B-02: No automatic task cancellation.** `PENDING_REVIEW / OVERDUE_REVIEW / ACKNOWLEDGED` are states of a **separate TASKS operational review record**, not `TaskState`. A manager must still inspect the live task and explicitly call existing `TaskService.cancelForSource` with expected version and current IAM. Snapshot membership is evidence only. Review `ACKNOWLEDGED` means manager handled the obligation and records a resolution code; it is **not** proof every task was cancelled.

**BIZ-R32B-03: No task or source.** A cancelled CRM source with zero linked pending tasks must not create fake work. Policy must explicitly decide whether an empty cancellation creates a closed `NOT_APPLICABLE` audit record or no obligation; for foundation default is **no actionable obligation, no dispatchable outbox intent**, with optional audit-only diagnostic counts.

**BIZ-R32B-04: No retroactive flood.** Activating policy at time T does not silently send notifications for all historical CRM cancellation events before T. Historical backfill requires a separate approved bounded import, one canonical source-event key, dry-run inventory and tenant-specific release decision. Source event revisions and history are immutable.

**BIZ-R32B-05: Policy changes.** New policies are **append-only revisions**; a review obligation captures policy revision and immutable calculated due instant. Policy revocation disables *new* obligations and blocks sending until explicit routing/consent revalidation. Earlier obligation deadlines are not silently recalculated. Any replay/reschedule creates separately audited revision or manual override, never UPDATE of old policy evidence.

**BIZ-R32B-06: Worker cannot exercise manager privileges.** Outbox delivers *an invitation to review*, not `cancelForSource`. It never acts as a synthetic `VerifiedActor`, skips CRM_READ or grants itself `TASK_MANAGE`. The eventual human reads an authorized snapshot and decides each task using fresh CAS.

## 2. Policy and governance decisions (blocking R3.2C activation)

| Decision ID | Proposed contract for B1/B2 | Approval gate |
|---|---|---|
| `SLA-01` Scope | per `tenant_id`, one active policy family for CRM_CANCELLED_REVIEW; no cross-tenant inheritance | business owner / TASKS domain owner |
| `SLA-02` Duration | `review_within_seconds > 0` in immutable revision; no default duration invented | tenant product owner |
| `SLA-03` Clock | `ELAPSED_UTC` only when explicitly approved; business-hours mode requires *separately qualified* versioned calendar, holidays, DST timezone and test oracles; fail closed if mode unsupported | product + operations |
| `SLA-04` Responsibility | active authorized tenant `TASK_MANAGE + TASK_READ + CRM_READ` manager or explicitly scoped team target; no catch-all org owner access | IAM/product |
| `SLA-05` Escalation | detection of elapsed deadline creates only review-state evidence, **not task mutation**, not external send until next gate | product |
| `SLA-06` Consent/routing | route `INTERNAL_MANAGER_REVIEW` may be stored as opaque code; email/SMS/push consent, destination and sender must be approved separately | security/legal/operations |
| `SLA-07` Retention | task history, snapshot, source event, SLA revision, review evidence and outbox attempts each need retention, legal hold and backup/PITR policy | security/legal |
| `SLA-08` Failure budgets | max attempts, jitter/backoff, lease duration, dead-letter response and requeue authority are **configuration with explicit UAT approval**; no covert operational default | operations |
| `SLA-09` Identity | policy activation via trusted VerifiedActor, explicit `TASK_MANAGE` + `TENANT_RBAC_MANAGE` admission; reviewer requires `TASK_MANAGE+CRM_READ`; no new implicit grants | IAM owner |
| `SLA-10` Empty/closed source | empty pending set no actionable notification; closed/completed task changes never force re-open; replay consults immutable source events and live eligibility separately | CRM/TASKS owners |

For B1 tests, **synthetic explicitly approved tenant policy fixtures** are permissible. Such fixtures do not count as commercial, security/legal or provider approval. There is no in-product self-service UI.

## 3. Proposed PostgreSQL model — ADDITIVE; choose unused changeset IDs at implementation time

Do **not** reuse existing `crm.task_reconciliation_snapshot*`, `crm.task_event` or `public.iam_deletion_oidc_revocation_outbox` for notification delivery.

### 3.1 `crm.task_review_sla_policy_revision` (immutable configuration)

Recommended fields: `tenant_id UUID`, `id UUID`, `policy_family VARCHAR` (one closed enum), `revision BIGINT`, `state DRAFT|APPROVED|RETIRED` (approval is append-only evidence, not mutable arbitrary UPDATE), `review_within_seconds BIGINT`, `clock_mode ELAPSED_UTC|BUSINESS_TIME`, `calendar_revision_id NULL`, `route_policy_code`, `max_attempts`, `lease_seconds`, `backoff_policy_code`, `effective_from`, `effective_until NULL`, `approved_by`, `approved_at`, `content_sha256`. Composite PK `(tenant_id,id)`, UNIQUE `(tenant_id,policy_family,revision)`; composite FK to tenant and actor checks; enforced positive durations, allowed ranges and temporal ordering. Only **approved and explicitly enabled** revisions are selectable; overlap of active effective windows prohibited by approved transactional claim/unique or PostgreSQL exclusion constraint, with H2 compatible tests. Record who approved and signed policy separately from a mere row status.

**Important immutability:** policy parameters/revisions never UPDATE/DELETE after publication; retiring an active policy uses a separate append-only policy activation/retirement event or versioned pointer, not a mutating “immutable approved row”. A distinct `crm.task_review_sla_policy_activation` pointer/guard may be mutable under CAS with immutable activation history. Separate `DRAFT` edits must use new revisions; no “unapproved DRAFT reused as approved” shortcut.

### 3.2 `crm.task_review_obligation` (operational projection)

Fields: `tenant_id,id,source_interaction_id,source_event_id,policy_revision_id,opened_at,due_at,review_state,revision,acknowledged_at,acknowledged_by,resolution_code,created_at,updated_at`. 
- Unique `(tenant_id,source_event_id,policy_family)` or `(tenant_id,source_event_id)` when only one policy family. Enforce tenant-composite FKs to `crm.customer_interaction_event`, `crm.customer_interaction`, approved policy and current tenant; source event action **must** be `CANCELLED` in service admission, and source event actual tenant+interaction must match obligation.
- `review_state` is **PENDING_REVIEW → OVERDUE_REVIEW → ACKNOWLEDGED**, plus explicit `CLOSED_NO_ACTION` / `HELD_POLICY` as needed. Declaring `OVERDUE` only changes the review projection and append-only review event. A manager may acknowledge pending/overdue with a non-PII resolution enum and expected revision; cannot silently mark a task done or expose client PII.
- Append-only `crm.task_review_obligation_event` records actor or trusted internal origin, transition, old/new revision, source_event_id, occurred_at. No mutable deletion of history. A `snapshot_id` optional link must be tenant-qualified; creating a snapshot or obligation must not be able to change a sealed snapshot.

### 3.3 `crm.task_notification_outbox` (durable routing intent and delivery state)

Fields: `tenant_id,id,event_type,source_event_id,obligation_id,policy_revision_id,route_policy_code,schema_version,created_at,next_attempt_at,status,lease_owner,lease_until,lease_generation,attempt_count,last_failure_code,acknowledged_at,dead_letter_at`.
- Immutable identity and provenance columns: tenant, event type, source event, obligation, route code, schema version, creation time. Use `UNIQUE(tenant_id,event_type,source_event_id)` for initial **single** review-required event; future escalation variants use distinct event_type and canonical obligation revision key. All references composite tenant FKs.
- Mutable delivery lifecycle is **operational state**, not immutable event evidence. Separate append-only `crm.task_notification_delivery_attempt` / `crm.task_notification_outbox_event` audit each claim, ACK, defer, fail, dead letter and authorized replay; no raw exception/provider response/body.
- **PII-minimizing envelope:** source/task/obligation IDs, version and opaque route policy only. **NEVER** raw task title, customer name, CRM note, email, phone, provider address, token, personal subject or secret.
- CHECK: `attempt_count >= 0`, `lease_generation >= 0`, lease owner/until both null or both set, valid status, ACK timestamp iff ACK state, dead-letter timestamp iff DEAD_LETTER, no ACK on a HELD record. Index `(status,next_attempt_at,lease_until,id)` with tenant filtering when querying scoped; exact query plan verified on populated PG.

### 3.4 Outbox vs snapshot references

Snapshot captures **which tasks existed when a manager reviewed CRM cancellation**; SLA obligation identifies **why/when a review is required**; outbox identifies **an intended internal notification about that obligation**. Never treat a snapshot as the review task aggregate or outbox receipt, and never attempt to deliver solely because a snapshot was captured or paged.

## 4. Atomic source transaction and catch-up semantics

**Correct source:** only successful transition to `crm.customer_interaction.status='CANCELLED'` and persisted **`crm.customer_interaction_event.action_code='CANCELLED'`**. Its `(tenant_id,id)` is canonical event identity; event `occurred_at` is SLA trigger. Source completion `COMPLETED` is not cancellation, and future task `CANCELLED` is a different event domain.

**Required source integration design:** CRM continues to own status/history. When feature flag and policy admission permit, a **typed, transaction-mandatory internal CRM→TASKS notification-intent port** can be called **inside the same CRM cancellation DB transaction**, after writing the immutable CRM event, to create review obligation + outbox intent. No direct TASKS-driven CRM mutation, no controller, no duplicate CRM event table. Ensure CRM owner transaction does **not** acquire a lock cycle with `TaskService.cancelForSource` (which already obtains IAM admission and CRM source lock). Derive and test lock DAG from actual JDBC sequence.

**Completeness guarantee:** 
- if source cancellation transaction rolls back, event/obligation/outbox all roll back;
- if it commits, and admitted policy applies with eligible pending tasks, exactly one obligation and at most one same-source notification intent survive;
- idempotent source event replay yields existing IDs, not another intent; new event/key with altered payload conflicts;
- if policy disabled/absent, existing CRM cancellation behavior stays unchanged; record out-of-band `NOT_ELIGIBLE/POLICY_HOLD` only through a separately accepted audit contract, never fabricate a notification or retrofit old events silently;
- if post-commit response is lost, the caller's existing CRM idempotency/expected version contract is respected; creation dedup is keyed by source event UUID, not wall-clock time.

**Important consistency decision:** If the API cannot safely create obligation within the source transaction without expanding CRM’s current transaction graph, **BLOCK B2** and design a specific transactional source-owner port. A free-running polling job reading changed CRM rows and then inserting outbox rows is *not* equivalent to atomic source-event capture. Reconciliation/backfill of historical events needs separate proof of gap-free handoff, not guessed timing.

**Policy effective time:** snapshot and obligation capture actual policy revision selected at the *source event occurrence* within the same transaction. A policy change racing source cancellation must serialize on an explicit tenant-policy activation row; two commit orders result in exactly one old/new policy selection, never double intent. Never read draft policy as active. A future calendar/holiday policy must freeze its revision/digest as well.

## 5. SLA evaluation and human decision flow (no scheduler in B)

`reviewDueAt = occurred_at + reviewWithin` **only** for an explicitly approved `ELAPSED_UTC` policy. `reviewWithin` is a positive exact duration, timestamp conversion uses injected `Clock` for evaluation and PostgreSQL timestamp precision; offset/DST changes must not affect elapsed UTC evaluation.

For `BUSINESS_TIME`, require a versioned country+timezone+holiday/business-hours calendar and independent example dates before implementing calculations; unsupported type must **fail closed**, not fall back to UTC or invent 24-hour days.

At any current time `now`, classify a live review obligation as due when `now >= due_at` and still unacknowledged. The due transition must be idempotent, append immutable review evidence, and never change `crm.task_item.status`. The mere passage of time does not mutate a DB row without an explicitly scheduled reconciliation check in a later phase. Foundation B exposes deterministic evaluator / controlled service command only in tests or authorized internal invocation.

Authorized manager:
1. Select active tenant and current grants; view source review obligation and optional sealed snapshot under existing `TASK_MANAGE + CRM_READ` and `TASK_READ` where queue visibility is needed.
2. If source is CANCELLED, review live `task_item` state/version and decide each task independently using existing `TaskService.cancelForSource`/assign/requeue/complete as permitted.
3. Acknowledge obligation with typed reason (`REVIEWED_NO_CHANGE`, `RECONCILED_TASKS`, `ESCALATED_MANUALLY`), expected review revision and idempotency key. State/history updated atomically; no implicit task cancellation or inventory effect.

No active manager / revoked grant / suspended account must result in **no external send** later; define safe destination ownership and fallback explicitly before R3.2C.

## 6. Outbox lifecycle, transaction algorithm and fencing

### 6.1 Closed states and transition table

| From | Command | To | Durable guarantee |
|---|---|---|---|
| `HELD_POLICY` | policy/consent not admitted | unchanged | never eligible for claim; no implicit retro-send |
| ABSENT | CRM cancellation source transaction | `PENDING` (only eligible policy) | once per canonical source event; atomic with CRM event |
| `PENDING`, due, unleased | `claim(workerId)` | `PENDING` + lease + incremented generation/attempt | `SELECT FOR UPDATE SKIP LOCKED`, bounded batch, committed before any network effect |
| leased `PENDING` | `ack(worker,gen,receipt)` | `ACKNOWLEDGED` | CAS on tenant/id/owner/generation, still-valid lease and provider receipt; immutable audit |
| leased `PENDING` | `fail(worker,gen,code)` | `PENDING` delayed | typed failure, cleared lease, deterministic bounded backoff, attempts recorded |
| leased `PENDING`, threshold met | `fail` | `DEAD_LETTER` | non-retriable until explicit authorized operator command |
| expired lease | another worker claims | newer leased `PENDING` | older worker ACK/retry fenced, even if first transport later succeeds |
| `ACKNOWLEDGED` | duplicate processing | unchanged | no second ACK or renewed lease |
| `DEAD_LETTER` | authorized replay *future* | `PENDING` new generation | auditable operator-only event, policy/consent revalidation, no duplicate intent |

**B3 internal foundation may implement/test claim/ack/fail against an injected fake adapter, but MUST NOT schedule production processing or invoke external providers.** There is no exactly-once external network delivery: after-send/before-ACK crash may repeat the transport attempt, and an eventual consumer/provider must support event-ID idempotency or tolerate duplicates. A worker ACK is legal **only after reliable external acceptance evidence**, not after local queue dequeue or `HTTP 200` guessed from an unrelated provider; exact provider response policy belongs to R3.2C.

### 6.2 Required claim/ack mechanics

- `claim` runs in a **short independent DB transaction**: choose at most configured batch size of due events with `FOR UPDATE SKIP LOCKED`, then persist worker UUID, `lease_until`, increment monotonic `lease_generation` and attempts; commit **before** any IO. No transaction or row lock around simulated/external send.
- `lease_generation` must increment on **every new claim/takeover**, never decrease or reuse. Combine `(tenant,id,lease_owner,generation)` and `lease_until > DB_CURRENT_TIMESTAMP` in ACK/fail predicates. Time comparison must use one agreed PostgreSQL time source, not inconsistent application node clocks. An expired lease cannot ACK even before a competing claim.
- If work takes longer than lease, an approved `renew` CAS may extend it only for current live owner+generation; otherwise the old worker must stop/abandon durable writes. Provider send already in-flight may still finish: duplicates remain possible, no promise of retracting a network operation.
- `retry` sets `next_attempt_at` using explicit policy revision budget and bounded backoff+jitter. Jitter derives deterministically in tests (seeded strategy) and is bounded in production; never invent hidden tenant defaults. Retries after IAM/consent/policy disable become `HELD_POLICY` without losing source evidence.
- `last_failure_code` is an enum (e.g. `TIMEOUT`, `UNAVAILABLE`, `AUTH_REVOKED`, `POLICY_DISABLED`, `DESTINATION_INVALID`, `PROVIDER_REJECTED`), never raw exception/response/PII. Security redaction applies to telemetry too.

**No external delivery/ACK in B3:** in test, successful fake adapter receipt is explicitly marked `SIMULATED_ACK` and cannot be mistaken for live provider ACK or production admission. When no transport is installed, outbox rows remain pending or held safely. The future dispatcher should be `disabled` by default with explicit trusted startup and operational owner.

## 7. Security, source of truth and privacy

1. `VerifiedActor` for policy write/approval, review queries/acknowledgement. Use `TenantAuthorization.requireForCommand` in active transaction, live global account, workspace and explicit grants. For source event append the existing CRM_WRITE admission; a non-user background worker has a separate trusted internal identity and DB permissions, **not a forged user**.
2. `TASK_MANAGE + CRM_READ` for review; policy approval additionally requires explicit `TENANT_RBAC_MANAGE` pending final business approval. No OWNER wildcard or platform admin cross-tenant read. Manager snapshot createdBy isolation remains intact.
3. All CRM/task/source/policy/outbox relations use tenant-composite FKs; never join solely by raw UUID when tenancy is in question. Outbox worker DB role must be restricted to its queue and audit operations; no direct unrestricted CRM customer or SALES/INVENTORY write permission.
4. Never persist task title, customer name, free text, email/phone, bearer token, OIDC subject, raw message template variables or live endpoint credentials in the outbox or review event. Historical task titles may already be plaintext, so a "no-new-PII" rule does not resolve retention history.
5. Notify only managers with *current* permissions and approved route/consent when a real provider is integrated later. Cancellation of CRM interaction is **not** customer consent to send messages. No permission to contact a buyer.
6. Before enabling cleanup, approve retention intervals for source, snapshot/seal, policies, review, outbox and attempt history including legal hold, backups, replicas, restored WAL, account deletion and overseas provider transfers. No automatic hard purge while operational ACK/retention undecided.
7. Include tenant-scoped query bounds, limits, maximum payload sizes (references only), redacted log fields, audit actor, clock, source/policy revision. No global queue dump via user-facing service.

## 8. PostgreSQL / Liquibase migration and performance contract

- Append changesets **after current highest source ID** (current inspected master ends with 0153), choose unused IDs on implementation HEAD; never rewrite applied 0140–0153, 0105 or historical IAM outbox migrations. New changesets must be forward-only and additive, with a realistic rollback/compensation policy for populated tables.
- Real PostgreSQL is the concurrency authority. H2 equivalents may omit PostgreSQL-only partial/exclusion/trigger mechanics while preserving relevant functional checks; conditional dbms changesets must not modify PostgreSQL checksums. A green H2 run never substitutes for PG uniqueness, lock or failover evidence.
- Index plans: unique source event dedup, `(tenant,policy_family,effective_from,revision)`, due reviews `(tenant,review_state,due_at,id)`, `(status,next_attempt_at,lease_until,id)`, and foreign-key access `(tenant,obligation_id)`. Inspect `EXPLAIN (ANALYZE, BUFFERS)` for bounded claim/overdue queries; avoid full scans and tenant hot-spot lock contention.
- **Populated upgrade:** seed actual R3.1/R3.2A CRM interactions (OPEN/CANCELLED), linked task items/events/queue, sealed 0/1/100/10 000 snapshot, nonempty old IAM outbox; run new migrations, compare record counts, FK, digest, task state, request-key and inventory/order independence. No retrospective creation of dispatchable notification rows.
- **Recovery:** crash before commit → no event/obligation/outbox; crash after commit → one replayable set with stable source event key. Restore from a DB backup and reconcile obligations/outbox while keeping original immutable CRM and task events; replay cannot duplicate notice intent.
- **Migration permissions:** separate DB privileges for CRM cancellation writer, TASK internal intent port and trusted worker. If schema owner role creates triggers on immutable tables, normal service role cannot disable them; prove cross-tenant FK and mutation-denial behavior.

## 9. Deterministic test matrix (planned cases, not executed)

Create named tests `R32B-01…R32B-30` **only after source inventory/de-duplication**. If an existing JUnit test substantively covers a case, link it; do not add redundant tests to inflate counts. For every test, verify DB rows/events with independent SQL oracle, not only DTO.

| ID(s) | Group | Mandatory proof |
|---|---|---|
| B01–B03 | Policy revision | approved/draft/retired state, explicit actor grants, no overlapping active revision |
| B04–B05 | SLA calculation | exact UTC due/overdue boundary, duration >0, forbidden unsupported calendar |
| B06–B07 | CRM source | OPEN→CANCELLED has one canonical source event; COMPLETED/OPEN do not emit eligible obligation |
| B08–B09 | Atomicity | CRM rollback means no obligation/intent; after-commit lost-response retry returns same IDs |
| B10–B11 | Idempotency | duplicate source event/key exact replay no duplicates; mismatched source/policy fails closed |
| B12–B13 | Tenant/privacy | tenant A/B source, policy, review and outbox isolation; no text/secret in persisted payload |
| B14–B15 | Lifecycle | overdue projection only; manual acknowledged with CAS and immutable revision history |
| B16–B17 | Policy races | cancellation×policy enable/retire both commit orders; old/new selection coherent |
| B18–B19 | IAM races | revoke/suspend×manager ack/review, both orders; zero unauthorized writes |
| B20–B21 | Worker claim | two actual PG workers, one row once/lease; SKIP LOCKED progress on independent rows |
| B22–B23 | Lease fencing | expired old worker ACK/RETRY loses; lease takeover/renewal generation CAS |
| B24–B25 | Retry/dead letter | bounded backoff+jitter, max attempts, no raw provider data; operator replay gated |
| B26–B27 | Crash recovery | fail before ACK, after fake send before ACK, retry at-least-once and dedupe; zero fake live ACK |
| B28 | Migration | clean + populated PG, immutable provenance, old snapshot digest preserved |
| B29 | Scale/plan | 0/1/100/10 000 eligible tasks; bounded query + actual plan, no unbounded memory |
| B30 | Regression | R3.2A T43–T48 unchanged, all mandatory domain workflows exact-SHA artifacts PASS |

**PostgreSQL-only**: B08–B11 rollback/races, B16–B27 fencing, B28 populated upgrade, B29 load/EXPLAIN. H2 smoke verifies policy validation and atomicity where dialect permits. Do not hardcode expected test *suite* counts until new JUnit methods and workflow are actually implemented; update QA01B synthetic fixtures and named test manifest atomically.

Additional adversarial cases to extend matrix where justified: source cancel×task link creation, cancelled source and no remaining pending tasks, 10k boundary, 10,001 previously qualified capacity denial, expired lease before any takeover, deactivated tenant manager, provider success then lost ACK, policy disabled after lease claim, policy re-approved with different revision, historical backfill not triggering delivery.

## 10. Explicit acceptance gates

| Gate | Requirements | Verdict if incomplete |
|---|---|---|
| `R32B-G0` **Design authority** | SLA-01…10 reviewed; approvers, policy scope, clock, retention/routing decisions signed, no hidden defaults | `DESIGN HOLD` |
| `R32B-G1` **R3.2A prerequisite** | full exact-SHA evidence; populated 0148–0153, source/IAM races, post-commit recovery, independent digest verification | `DEPENDENCY HOLD` |
| `R32B-G2` **DB & policy** | additive schema, immutable versions, tenant grants, current-activation guard, clean+populated PG; failed foreign FK/unique attempts | `FOUNDATION HOLD` |
| `R32B-G3` **Atomic intent** | real CRM cancellation event in same transaction as eligible obligation + outbox; replay, rollback and policy races | `FOUNDATION HOLD` |
| `R32B-G4` **Durability mechanics** | two workers/lease takeover/CAS, retries/dead-letter/restore, fake adapter only, no external sends | `OUTBOX FOUNDATION HOLD` |
| `R32B-G5` **Evidence** | source HEAD + task PG suites + security + all mandatory populated upgrades + six-workflow exact-SHA PASS, Surefire XML named proof; no unexpected skips | `EXACT-SHA HOLD` |
| `R32C-G0` **Executor admission** | separate business/provider/consent/SLA/ops design, real provider tests, secured invoker, metric SLO, security/legal approval | **NOT PART OF R3.2B** |

**Only after G0–G5 at a common final candidate SHA:** `R3.2B FOUNDATION PASS`. It must say **`EXTERNAL NOTIFICATION DELIVERY = NOT IMPLEMENTED`** and **`PRODUCTION = HOLD`**. There is no implied authorization to launch scheduler or send any message.

## 11. Implementation task breakdown for Codex (future work; not performed by this spec)

1. `R32B-T00` — reconcile current HEAD, IAM outbox reusable utility patterns, CRM event writer and R3.2A closure; resolve decisions SLA-01…10; create source-to-test matrix.
2. `R32B-T01` — add schema for immutable SLA policy revisions/activation and secure draft/approval service; explicit VerifedActor + grants, all policy CAS/unique races, no default activation.
3. `R32B-T02` — add review obligation/audit + pure deterministic due evaluator and manager ACK without mutating `crm.task_item`.
4. `R32B-T03` — add typed CRM cancellation event→TASKS intent port inside CRM owner transaction; one-source-event unique provenance and exact-key replay; no direct TASK cancellation.
5. `R32B-T04` — add outbox state, attempt evidence and internal lease/claim/ACK/retry/dead-letter CAS primitives with fake adapter; no scheduler/real provider.
6. `R32B-T05` — real PostgreSQL concurrency and crash tests, populated migration and backup/restore, H2 compatibility; compare ledger/events/locks and seal digests.
7. `R32B-T06` — wire specialized R3.2B and main QA01B exact-SHA evidence, publish immutable manifest + known decision register; qualify one final SHA in `base_dev`.

**Every ticket** must specify exact HEAD, owner, tenant/actor, business invariant, migration/lock order, negative and two-transaction race tests, rollback/retry, named Surefire XML, Actions URL, gate verdict. No new branch, no `main` merge, no unrelated SALES/INVENTORY/FX change.

## 12. Beyond R3.2B / roadmap

`R3.2A evidence closure → R3.2B policy and durable intent foundation → R3.2C trusted executor + provider delivery acceptance → TASKS operational UAT → RM Business full FC-60 / real OIDC and UI later → pilot → production`.

R3.2C will separately require provider acceptance/ACK semantics, consent/revocation at send time, worker election/scheduler, dead-letter operational replay, observability/alerting, throughput and security/legal approval. Worker dispatch is feature-flagged **OFF by default** and requires explicit activation. This roadmap does not block RM QR Commerce on a TASKS notification system and does not replace the platform's two-product `RM-CORE-FUNCTIONAL-CLOSURE-R1` service programme.

**Final decision:** implement **policy-as-versioned-evidence + review obligation + atomic event-driven outbox intent + tested durable lease/fencing primitives**, while keeping task mutation manual and external notification delivery disabled. Any claim of exact-once external delivery, approved retention, real-provider integration or production readiness is prohibited by this R3.2B spec.
