# RM-QR-06B — Concurrent & Resilient Physical Handover Qualification

**Status:** SPEC / EVIDENCE PENDING. **Date:** 2026-11-22. **Target:** `base_dev`. **Production admission:** HOLD.
**Scope:** Service layer + PostgreSQL real-infra tests only. No controllers, no `main` modification.
**Authority:** `docs/architecture/RM-PLATFORM-STANDARD-R2.md`; this document defines a qualification gate, not a change to approved commercial policy.

## 1. Purpose and baseline

Qualify QR-05 preparation and QR-06 physical handover against multi-employee contention, IAM permission revocation, response loss, crash/retry, and sustained load. QR-05/06 internal test success is a reported baseline, **not** evidence that QR-06B passes. All acceptance decisions require exact Git SHA, test artifacts, database evidence, and explicit reviewer sign-off.

Invariant: for each (tenant, order, handover operation) at most one durable successful handover; the same completion must not generate duplicate inventory movement, debit or receipt. No partial transition of order, reservations, inventory movement or receipt.

`on_hand >= reserved >= 0`, `available = on_hand - reserved`. Do not equate completion with settlement/payment or delivered shipping.

## 2. Transaction and authorization contract

1. Resolve trusted actor at the internal service boundary; do not trust actor, tenant, warehouse or authorization assertions supplied as raw request fields.
2. Use `TenantAuthorization.requireForCommand` and the existing IAM lock ordering for status, membership, role and permission revocation; validate object/warehouse scope and an active one-time handover claim.
3. Define one PostgreSQL linearization point: under a consistent lock order, re-check completion state and binding to order/tenant/warehouse, apply exactly one stock debit/movement and order transition, persist immutable receipt and consumed token marker in the **same transaction**.
4. Maintain uniqueness for one successful handover per order, plus uniqueness of stock movement against its business source; concurrency control must be enforced by DB state and constraints, not only JVM locks or caches.
5. Never make token or QR raw contents the sole idempotency identity. Repeated request with a different requestKey for a completed order must not debit again.
6. For same actor/claim and replay after ambiguous COMMIT, return the established canonical result/receipt without reapplying side effects, subject to read authorization. For different actors, return an already-completed conflict or authorized read-only result according to existing domain policy; do not disclose tenant data.
7. Define token expiry using a single database-safe/server authoritative time rule at the linearization point. At exactly expiry boundary fail closed. Ten minutes remains **UAT configuration**, not commercial policy.
8. Lock ordering must be explicit and shared with IAM revoke, stock adjustment, and reconciliation. Deadlocks may retry a bounded full transaction; no partial retry of debit alone.
9. Zero externally visible side effects until transaction commit. Do not claim exactly-once network delivery; prove exactly-once durable business effect and deterministic replay.
10. Maintain immutable audit and correlation by order/tenant/claim/request without logging QR secrets or PII.

## 3. Test matrix

| ID | Action / injection | Expected | Evidence |
|---|---|---|---|
| QR06B-01 | 2, 5, 20 simultaneous employees scan same QR, distinct request keys | exactly one durable completion, movement and receipt | synchronized PG barrier; persisted row counts |
| QR06B-02 | reuse same QR sequentially and concurrently | zero additional debits, no refreshed token | rows before/after |
| QR06B-03 | checkout vs role removal/membership suspension, **both lock acquisition orders** | either approved checkout before effective revoke, or reject without side effects | two real DB connections, lock timeline |
| QR06B-04 | global account / tenant suspend vs checkout in both orders | same serializability rule, no stale JPA authorization | rollback + permission assertions |
| QR06B-05 | lose response *after durable commit*; retry same and new request keys | canonical replay, exactly one debit | fault proxy/client timeout + PG records |
| QR06B-06 | inject failures before debit, after debit before receipt, before commit | total rollback (order, movement, receipt, token) | transactional fault injection |
| QR06B-07 | restart worker/process during conflicting handovers | recovery based on persisted PG state, no duplicate debit | process kill + restart logs |
| QR06B-08 | token expires before, at, and just after boundary while contending | consistent one-time expiry policy; no expired first-use | deterministic clock and PG timing |
| QR06B-09 | 2+ independent JVM instances / single PostgreSQL | at most one completion, independent of local memory | process IDs + DB invariants |
| QR06B-10 | cross-tenant, wrong warehouse/order, revoked permission | deny; no side effects or information leak | negative assertions |
| QR06B-11 | mixed contention: checkout, stock correction, reconciliation, cancel | no deadlock starvation, negative stock, duplicated event | timed PG test + invariants |
| QR06B-12 | load and crash/retry campaign | zero invariant breach and no permanent stuck claim | load summary + recovery scan |
| QR06B-13 | stale claim, tampered QR, token guessing, replay across tenant | deny without secret exposure | security negative suite |

## 4. Load qualification protocol

Use repeatable PostgreSQL data; separate uncontended baseline from hot-order contention (single QR), multi-order contention, and mixed workload. Proposed **test profiles, not SLO commitments**: concurrent clients 10/50/100/250, per profile 3 repeated runs after warm-up; 2 separate application processes for distributed contention. Measure successful distinct handovers/sec, throughput attempts/sec, p50/p95/p99 latency by outcome, error/retry/deadlock/lock-wait rates, max PG connections, CPU and recovery duration. Capture exact hardware and datasets; avoid declaring absolute pass thresholds until agreed. Correctness gate is strict: **0 duplicated debits, 0 negative stocks, 0 partial receipts, 0 unauthorized completions, 0 unreconciled state after recovery** across every run.

Any deadlock, timeout or cancellation must be classified and tested for safe retry; do not conceal them in aggregate successful throughput.

## 5. Evidence and admission

Required evidence package under `docs/reviews/evidence/qr-06b/<commit-sha>/` or linked immutable CI artifacts:
- Git SHA and branch, Java/PostgreSQL versions and test commands;
- individual scenario/suite list and report (PG actual, not only H2/mocks);
- schema constraints and transaction-order inspection;
- pre/post DB SQL invariant probes (orders, reservations, movements, receipts, token consumption);
- deterministic barriers, reproducible fault injection, dual-JVM evidence;
- benchmark setup, raw run outputs, p95/p99 and error classifications;
- recovery replay proof and security-negative evidence;
- reviewer disposition: PASS / FAIL / NOT RUN for every ID.

**Gate QR-06B = PASS** only if each mandatory scenario passes on the exact candidate SHA and has persisted evidence. Otherwise **HOLD**. External OIDC (issuer/JWKS/revoke), real physical scanners, public HTTPS/TLS and separately approved handover policy remain independent gates. The one-time code lifetime of **10 minutes is UAT-only**.

## 6. Execution sequence

1. **B0 Audit**: locate actual QR-05/06 services, locks, table constraints, tests, and CI suites; map code locations and observed coverage. Never infer PASS from previous chat statements.
2. **B1 Deterministic PG contention**: QR06B-01/02/03/04/08/10/13 with two connections, forced race order and DB invariant SQL.
3. **B2 Transactional fault injection**: QR06B-05/06/07/09, include restart and cross-process execution.
4. **B3 Mixed workload + load**: QR06B-11/12, baseline and hot-spot results.
5. **B4 Exact-SHA gate**: qualified evidence manifest, reviewer signoff, update status. No production approval by inference.

### Open decisions and risks

- Canonical idempotent replay response may expose sensitive receipt fields; retain access checks.
- Expiry clock and exact boundary semantics must be demonstrated in code and tests.
- IAM and order/warehouse lock-order conflicts require source-based audit.
- Whether distinct employees receive `ALREADY_COMPLETED` or an authorized canonical receipt is a policy decision; both prohibit a second debit.
- Agree operational SLO thresholds independently after workload evidence.
