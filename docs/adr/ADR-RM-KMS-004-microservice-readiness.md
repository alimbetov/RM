# ADR-RM-KMS-004 — Microservice-ready KMS without premature distribution

**Date:** 2026-11-21  
**Status:** ACCEPTED FOR MULTI-POD IMMUTABLE-KEY PHASE / ROTATION & DISTRIBUTED MUTATION HOLD  
**Source:** RM-KMS-SPEC-R1 + audit R1.6; branch `feat/rm-kms-r1-implementation`.

## Decision

RM remains a **modular monolith** in current deployment, but every CRM consumer must depend on the **framework-independent `TenantCryptoPort`**, not on `TenantKeyRepository`, `KeyWrappingService` or key slot state. A single internal `TenantCryptoService` implements the port. The KMS is a distinct bounded context owning:
- `security.kms_tenant_key` and `security.kms_tenant_key_archive`;
- wrapped DEKs, wrapping KEK IDs, tenant key generations, crypto envelope format and lifecycle;
- internal Crypto operations and generation policy.

CRM / orders / finance own their business entities and encrypted field storage. They provide authenticated context (tenant, entity type/id, field) and **never read raw DEKs from DB or logs**. No direct cross-module queries of KMS tables. There is no public key retrieval or admin decrypt endpoint.

## Boundary and future service contracts

Current in-process port:
```java
interface TenantCryptoPort {
    EncryptedValue encrypt(CryptoContext authorizedContext, String plaintext);
    String decrypt(CryptoContext authorizedContext, EncryptedValue ciphertext);
}
```

This Java interface is **not** an HTTP contract and is not exposed on the network. External contracts, if needed later, require separate review: mTLS/workload identity, service-to-service authorization, explicit tenant authorization backed by trusted claims, request quotas, transport protection, no raw DEK distribution, no plaintext logging, audit, bounded payloads, deadlines, retries preserving idempotency, strict response privacy and a measured availability budget.

Before extracting KMS to a service decide whether (A) cryptographic operations stay next to business data under a secure local key provider, or (B) plaintext crosses the service boundary to a centralized cryptographic service. Neither can be adopted silently. A remote KMS holding wrapping keys **must not return plaintext DEKs to untrusted clients** merely to preserve the current cache architecture. Evaluate latency, PII movement, blast radius, horizontal scaling and operations.

## Multi-pod baseline: immutable keys, no automatic rotation

Existing RM deployment is **multi-pod** and background tasks are scheduled. `KmsDeploymentGuard` now accepts `multi-pod` (default) and `single-jvm` for local development, provided `rm.kms.rotation.enabled=false` and `rm.kms.migration.enabled=false`. This guard does **not** prove distributed coordination; it merely rejects unsafe feature toggles.

Ordinary AES-GCM encrypt/decrypt with **immutable ACTIVE generation**, tenant-keyed Caffeine cache and shared PostgreSQL can be exercised across multiple pods; encrypted fields must remain bound to tenant/entity/field AAD. Decrypting a mutable/REVOKED generation from a stale local cache is NOT safe; production revocation/promotion is still disabled. Concurrent first-time provisioning is not yet certified and requires a PostgreSQL atomic idempotency contract.

Local Caffeine TTL/eviction is only a performance policy, never a revocation guarantee. Pod deployment counts, CI and rolling upgrades require their own test gates.

### Scheduler coordination != transactional data fencing

**Task acquisition:** any scheduled migration/rotation task must hold a **distributed, durable PostgreSQL-backed lease** (or an approved existing scheduler implementation with equivalent lease semantics) keyed by `(taskType, tenantId)`, with owner identity, expiry and **monotonically increasing fencing token**. Acquisition must be atomic. Each mutating transaction must verify its lease/fencing token before commit. Paused/expired workers, even if still alive, are not permitted to commit new writes after a successor acquires the lease. A scheduler-only single execution guarantee does not prevent concurrent CRM/API writers.

**Data-writer barrier:** before changing ACTIVE or retiring generations, introduce per-tenant persistent `key_state_epoch` (new SQL changeset) and transactionally enforce generation/epoch checks in every encrypted-data write path. A promotion must block/abort old generation writers until their transactions have completed or been rejected. Confirm this with PostgreSQL concurrency tests using two JVMs/pods.

**Failure semantics:** lease expiry, pod termination, network partition, database reconnect and concurrent scheduler executions must not permit duplicate migration effects or premature archival. Batch job remains idempotent and its progress derived from stored envelopes; counters are advisory, not proofs. Prefer the existing RM scheduler infrastructure if it already provides comparable distributed locks plus fencing, but verify in code and CI before relying on it.

No claim is made that a qualified distributed scheduler/fencing implementation exists in this branch. Do **not** enable `rotation.enabled` or `migration.enabled` until the DB-backed protocol, worker fencing, full-generation scans and restore checks are implemented and tested.

## Data ownership and eventual consistency

- Provisioning is tenant lifecycle action: idempotency + company binding; if tenant registration lives in another service, **do not** assume one ACID DB transaction across both services. Prefer idempotent provisioning commands, durable outbox/inbox and retry with correlation ID. A tenant is not crypto-ready until KMS acknowledges generation 1.
- Encrypted values are opaque versioned envelopes with `generation`. Consumers store exactly the envelope without modifying tag, nonce or AAD fields.
- Never archive/reuse a generation based solely on local CRM counters. Future remote modules must register encrypted fields and participate in a **durable generation-reference verification** before key retirement.
- KMS control path (ACTIVE promotion, revocation, archive/recovery) is suspended until transaction-state fencing works across all writers. Archive recovery is a privileged offline pathway, not a cross-service fallback.
- Contract versions and tenant/generation identity remain stable when moving between processes. Backward-compatible envelope parsing requires golden fixture tests after serialization.

## Deployment phases and gates

| Phase | Deployment | Allowed |
|---|---|---|
| R1.6 | Multi-pod immutable-key mode, H2 dev / PostgreSQL test | Encrypt/decrypt under unchanged generation and tenant-keyed Caffeine; concurrency provisioning requires qualification |
| R1.7 | Multi-pod, immutable keys | Persisted envelope round-trip and provisioning concurrency, tests |
| R1.8 | Multi-pod | PostgreSQL scheduler lease/fencing and transactional admission; safe migration/restore behind gates |
| MSA-R0 (future) | Boundary extraction proof of concept | Contract versioning, mTLS, authorization, failure injection |
| MSA-R1 (future) | Independent KMS microservice | ONLY with explicit network security, verified cache/DB fencing, outbox, recovery, p95/p99 and security review |

**Release rule:** multiple RM pods may execute immutable-key cryptographic operations in the current phase. **Distributed rotation, revocation, scheduled migration and extraction of a remote KMS remain HOLD.** Never represent an in-process port as a finished microservice. Keep existing SQL migrations immutable.
