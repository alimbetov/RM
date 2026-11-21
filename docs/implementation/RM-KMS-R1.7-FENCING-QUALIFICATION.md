# RM-KMS-R1.7 — PostgreSQL scheduler fencing, first implementation slice

**Branch:** `feat/rm-kms-r1-implementation`. **Status:** IMPLEMENTED IN TEST BRANCH; CI evidence must be read before PASS.  
**Scope:** one durable KMS key-maintenance lease per tenant, shared by all RM pods. No rotation/nightly migration enabled.

## Audit of actual RM scheduler

Recursive GitHub tree inspection of this branch found **no existing production scheduler implementation, lease provider, ShedLock, Quartz or task worker**. Existing source comprises RM bootstrap and KMS modules only. Therefore no existing scheduler was reused, and no claim is made about another separately deployed scheduler. Integration with a future scheduler is deliberately external to this step.

## Implemented fencing protocol

One additive Liquibase formatted-SQL changeset `rm:0007-kms-job-fencing` extends `security.kms_tenant_key`, avoiding a third operational KMS table:
- `job_lease_owner UUID` — unique owner UUID for a single acquisition;
- `job_lease_until TIMESTAMP WITH TIME ZONE` — DB-clock expiry;
- `job_fence_token BIGINT NOT NULL DEFAULT 0` — monotonically incremented **on every successful acquisition**, never reset by release.
- Pairwise `NULL` constraint; nonnegative fence token. Prior applied changesets `rm:0001..0006` are not modified.

`KmsFencedJobService` methods:
- `acquire(tenantId, Duration)` returns a `Lease` or `null` if active owner exists;
- `renew(Lease, Duration)` verifies current owner and token, refuses renewal after expiration;
- `executeFenced(Lease, JdbcTemplate -> result)` locks the tenant key row `FOR UPDATE`, verifies owner, token and expiry **before and after callback** within a single `REQUIRES_NEW` transaction;
- `release(Lease)` cannot unlock another owner's generation, is idempotent for a stale lease.

Lease duration 1 second to 15 minutes. For Postgres, authoritative time is `clock_timestamp()` instead of transaction-frozen `CURRENT_TIMESTAMP`; for H2 smoke, `CURRENT_TIMESTAMP`.

**Important invariant:** every **job-mutated SQL row must be updated via the guarded JDBC callback in the same DB transaction**. A developer who writes outside this callback or starts another transaction will bypass fencing; automated review and tests must prevent it. Every short batch is an independent transaction with a fresh check. A paused worker cannot commit after the successor acquires a higher fencing token because both paths hold the same `FOR UPDATE` tenant row lock; if its own lease expires during execution, it rolls back.

## Explicitly not implemented

1. No actual scheduled KMS worker currently calls `executeFenced`. Existing external scheduler still requires review and integration.
2. No periodic automatic heartbeat, orphaned job recovery or long-running scheduler loop.
3. **Ordinary CRM business writers are not fenced.** Scheduler exclusivity is not permission to promote ACTIVE while concurrent CRM writes are in progress.
4. Key `rotate()` still throws `UnsupportedOperationException`; `rotation.enabled=false` and `migration.enabled=false` remain fail-closed.
5. No cross-database / external-system atomicity; the protected callback cannot safely perform external side effects as part of its SQL transaction.
6. The tests are two independent service objects sharing a DB, **not two independent Kubernetes pods/processes**.

## Mandatory acceptance

- H2 Liquibase/JPA smoke + PostgreSQL integration: lease acquisition, expiration handoff and old-token rejection, rollback, renewal, concurrent one-winner.
- PostgreSQL two-connection contention, lease owner crash, paused callback > TTL with rollback, database failover, cache invalidation after promotion, real 2+ JVM pods are **future P0 tests**.
- Then integrate the real scheduler with `KmsFencedJobService`, enforce callback transaction boundary and per-tenant idempotency, keep auto-migration disabled until CRM writer barrier (R1.8).
- Record exact CI run/test count before changing status to PASS.

**R1.7 gate distinction:** protocol implemented and DB-tested does **not** equal a qualified scheduled multi-pod migration worker. No production admission.
