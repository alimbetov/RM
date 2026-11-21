# RM — Service-first qualification policy and SALES-INVENTORY-R1.2

**Branch:** `base_dev`. **Status:** service-first feature freeze. No new HTTP controllers, filter chain or application `SecurityConfig` until functional-service gates are accepted for every module. Existing bootstrap/health components are not scope for new transport features. Do not merge `main` or claim production-ready based solely on synthetic CI.

## Global rule: test service contracts before transport

Each service's acceptance record must cover: normal paths, invalid payloads, NULL/limits/precision, tenant isolation, authorized and unauthorized actor, revoked membership/account, idempotent retry and conflicting replay, transaction rollback, database constraints, concurrent updates, repository filtering and stable ordering, data redaction, negative cases, evidence at exact Git SHA.

| Service family | Principal requirements | Admission |
|---|---|---|
| IAM, RBAC, global profile/PII | verified identity, permission revoke fences, role/ownership, encrypted identity and migration | Existing synthetic functional tests; independent review remains open |
| Organization/CRM | tenant membership, PII scoping, location lifecycle, page/filter contract | Dedicated functional negatives required |
| Catalog, Marketing, File | tenant isolation, permitted writes, validation, immutable evidence/file lifecycle | Module-by-module functional matrices required |
| SALES | order state machine, retry and replay, quantity/currency, cross-tenant denial, cancel/fulfill conflict | R1.2 extended synthetic contention qualification |
| INVENTORY | reservations, stock invariant, immutable movement, idempotency key, multi-SKU lock order | R1.2 extended synthetic contention qualification |
| Shared audit, KMS and data lifecycle | immutable evidence, rotation/fencing, retention approvals, restore | Existing qualification plus operator evidence |

**Controller entry gate:** all module service test matrices GREEN at a frozen SHA, independent security review, DTO validation and an explicit decision to activate transport endpoints. `SecurityConfig` and real issuer integration remain a distinct subsequent task; synthetic actor tests never establish deployed auth.

## R1.2 — repeatable PostgreSQL contention micro-qualification

Run the exact-head GitHub `postgres-integration` job, not H2 for PG row-lock assertions.

1. Prepare one authorized tenant, one warehouse/SKU with stock 40, and 12 seller orders of quantity 1.
2. Submit each `confirm` **twice** concurrently through six workers. Exactly 12 first executions must apply; remaining calls must be idempotent. Verify 12 ACTIVE reservations, on_hand=40, reserved=12.
3. For 6 orders submit `cancel` twice and for 6 orders `fulfill` twice concurrently. Exactly 12 terminal transitions commit; resulting on_hand=34, reserved=0, six RELEASED, six FULFILLED, exactly six fulfillment ledger movements, and 36 status events including order creation.
4. Assert each order has the expected terminal state and no task errors/timeouts. Record diagnostic p95/p99 elapsed microseconds for confirms and terminal commands.
5. In a separate query test, write statuses concurrently with multiple stable-ordered pages; confirm no duplicate/missing IDs and no cross-tenant rows when ordering keys are unchanged.
6. Continue running real `confirm` vs RBAC revoke tests. Test each revoke-first and command-first interleaving before broader scale.

**Metrics interpretation:** 24 confirmations and 24 terminal calls at six-way contention are *not* throughput or latency SLA certification. CI runners are variable. Store source SHA, job logs and sample sizes. Do not assert fixed p95/p99 thresholds or label this as 100/1,000 user load. Larger repeatable warmup/steady-state benchmarks with database lock/deadlock telemetry and error distribution must be separately qualified.

**Failure policy:** failure to compile, timeout, deadlock, wrong ledger count, oversell, mismatched tenant, duplicate event, or stale/replayed permit means HOLD. No controllers until service gates are verified.
