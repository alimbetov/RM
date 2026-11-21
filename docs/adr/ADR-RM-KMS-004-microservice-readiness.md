# ADR-RM-KMS-004 — Microservice-ready KMS without premature distribution

**Date:** 2026-11-21  
**Status:** ACCEPTED FOR DEVELOPMENT / DISTRIBUTED DEPLOYMENT HOLD  
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

## Single-JVM guard

`KmsDeploymentGuard` refuses application boot unless `rm.kms.deployment.mode=single-jvm`, `rm.kms.rotation.enabled=false`, and `rm.kms.migration.enabled=false`. Its purpose is to prevent someone from treating process-local Caffeine invalidation as a distributed revocation/fencing mechanism. This **does not detect two separately configured replicas**; deployment policy must set replicas=1 and reject scale-out until a cross-instance admission design is tested.

Do not use the JVM-local Caffeine cache to implement distributed key revocation. In a future multi-instance deployment implement persistent tenant key-state epoch, transactional DB fencing, cluster invalidation or bounded-lease guarantees, and tests for network partitions/stale caches/crash between commit and invalidation. Do not switch on extra replicas with the same settings.

## Data ownership and eventual consistency

- Provisioning is tenant lifecycle action: idempotency + company binding; if tenant registration lives in another service, **do not** assume one ACID DB transaction across both services. Prefer idempotent provisioning commands, durable outbox/inbox and retry with correlation ID. A tenant is not crypto-ready until KMS acknowledges generation 1.
- Encrypted values are opaque versioned envelopes with `generation`. Consumers store exactly the envelope without modifying tag, nonce or AAD fields.
- Never archive/reuse a generation based solely on local CRM counters. Future remote modules must register encrypted fields and participate in a **durable generation-reference verification** before key retirement.
- KMS control path (ACTIVE promotion, revocation, archive/recovery) is suspended until transaction-state fencing works across all writers. Archive recovery is a privileged offline pathway, not a cross-service fallback.
- Contract versions and tenant/generation identity remain stable when moving between processes. Backward-compatible envelope parsing requires golden fixture tests after serialization.

## Deployment phases and gates

| Phase | Deployment | Allowed |
|---|---|---|
| R1.6 | One JVM, H2 dev / PostgreSQL test | Provisioning, encryption/decryption in authorized test flows, Caffeine |
| R1.7 | Same JVM | Persisted envelope round-trip, generation/KEK resolver, tests |
| R1.8 | Same JVM | Transactional admission, safe migration/restore behind tests |
| MSA-R0 (future) | Boundary extraction proof of concept | Contract versioning, mTLS, authorization, failure injection |
| MSA-R1 (future) | Multi-instance / distributed | ONLY with demonstrated cache invalidation, DB fencing, outbox, recovery, p95/p99, security review |

**Release rule:** never represent an in-process port as a finished microservice; Kubernetes replicas >1 and remote KMS operations remain **HOLD**. Keep existing SQL migrations immutable.
