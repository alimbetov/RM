# RM FILES-MEDIA-R1.0C — concurrent upload fencing and local crash recovery

**Status:** service-only implementation; exact-head CI required for acceptance. **Branch:** `base_dev`. **Production:** HOLD.
**Parent:** [RM-FILES-R1.0 service core](../spec/RM-FILES-R1.0-SERVICE-CORE-IMPLEMENTATION-DECISIONS.md) and [R1.0B qualification](RM-FILES-MEDIA-R10B-SERVICE-QUALIFICATION-2026-11-21.md).

## Problem and implemented fix

R1.0B rechecked `STAGED` at the final database transaction but allowed two workers to stream, scan and physically promote the same upload concurrently before one lost the SQL race. A JVM crash after physical promotion, before the PostgreSQL commit, could also leave private unreferenced local files. A stale disk pointer could be marked AVAILABLE even if bytes disappeared or changed after restart.

R1.0C introduces **no new microservice, Redis, distributed lock, HTTP controller or second file table**.

1. **Token-fenced claim:** additive Liquibase 0055 adds `processing_token` and `processing_started_at` with an invariant CHECK. `FileMetadataService.claim` locks the tenant/file row in a *short* transaction and issues one opaque token, marking `STAGED/SCANNING`. A competing worker fails before consuming the caller stream. Scan/storage work happens outside the DB transaction. On completion, the token and state must still match in a new authorized transaction before any AVAILABLE commit. A stale worker cannot finish after claim takeover.
2. **Lease expiry:** claims are treated as stale after 15 minutes; subsequent claim or internal reconciler can supersede them. The TTL is a failure-recovery fence, not an SLA for successful uploads. Long uploads can lose the claim: they fail closed instead of being published without current authorization. The first retry following a crash requires a fresh complete stream from the client; it does not reuse unchecked staging data.
3. **Failure transitions:** scanner INFECTED -> terminal REJECTED/INFECTED and clears claim; scanner ERROR, invalid content or stream/storage interruption -> STAGED/NOT_SCANNED after best-effort cleanup; missing/wrong persisted AVAILABLE content -> REJECTED/ERROR, not false success. Replayed AVAILABLE completion recomputes stored SHA-256 and size instead of trusting the state column.
4. **Local storage verification:** `FileStoragePort.matches` computes SHA-256 over persisted bytes; `LocalFileStorage.listKeys` exposes only a bounded file-scoped enumeration of private stage/final keys, never an HTTP API or user-supplied path. Active immutable final key is excluded from orphan cleanup.
5. **Recovery:** internal package-private `FileRecoveryService.reconcileOne(tenant,fileId)` locks DB state briefly. Active claims are skipped; stale claims are fenced with a new token before filesystem cleanup. Unreferenced local blobs are deleted, then the internal claim released. AVAILABLE content is checked; missing or modified content is quarantined. A bounded tenant-only `reconcileBatch` returns a `created_at,id` keyset continuation cursor so all candidate rows can be visited without recurring first-page starvation.
6. **After physical promote before SQL commit:** the orphan final key is neither a downloadable file nor an authoritative pointer. Recovery removes it after the old claim is stale; replay then creates one new verified immutable final key and one PostgreSQL storage pointer.

## Acceptance matrix (synthetic data only)

| Test scenario | Assertion |
|---|---|
| Two concurrent `acceptAndComplete` calls for one file | Second rejected before stream read; exactly one ACTIVE storage row, one AVAILABLE state |
| Complete replay after ACK lost | Recomputes persisted SHA-256; returns existing immutable file identity without replacing bytes |
| Crash after physical promote / before SQL commit | DB stays STAGED, local orphan is identified and cleaned, expired original token cannot finalize, subsequent upload completes |
| Active claim during reconciliation | No deletion; BUSY |
| Expired original worker and claim takeover | Token mismatch prevents stale finalization |
| Corruption with unchanged byte length | Stored SHA mismatch quarantines file |
| Missing persisted blob | Recovery marks REJECTED/ERROR, never reports AVAILABLE success |
| Foreign tenant batch and active scan | No cross-tenant blob deletion, no active file cleanup |
| Batch pagination over multiple files | Continuation cursor reaches later candidates and can finish |
| Existing R1.0B and R1.0A | All synthetic regression tests must remain GREEN on H2 and PostgreSQL |

Evidence must include GitHub Actions `h2-smoke`, `postgres-integration`, and `postgres-pii-populated` on **the same exact `base_dev` HEAD**. Document-only commits require a new exact-head run; never reuse a prior commit's run as exact-head evidence.

## Non-goals and remaining risks

- **Not an always-on scheduler:** the reconciler is deliberately a package-private trusted service port with bounded, tenant-local invocation. An operational job scheduling policy, metrics, alerting and secured service identity are a separate admission before production. The 15-minute expiry does not cause cleanup by itself.
- **Not lossless power-failure recovery:** LOCAL rename/FS durability and fsync/OS crash/drive loss guarantees have not been independently qualified. If bytes are irrecoverably lost, the system quarantines the database file; it does not reconstruct missing contents. Storage+DB+keys backup and restore remain a future gate.
- **No real customer files:** byte content is still unencrypted in synthetic LOCAL UAT and the production scanner is an explicit ERROR. Real confidential documents require streaming authenticated encryption, tenant key recovery and real AV qualification. IMAGE/PDF signature checks alone are not comprehensive safe-content parsing.
- **No CRM/CATALOG attachment or user download:** typed owner policy, PII restrictions and authorized read streaming are not implemented. Future read must validate current owner and persisted checksum before returning bytes.
- **Lease and concurrency robustness:** 15-minute claim TTL is a policy constant. Multi-node, long streams, stale-worker cleanup timing and throughput/p95/p99 under load need further qualification; cleanup may require an additional pass if an already-fenced process writes a late orphan.
- **Security review:** independent security sign-off is still absent. `main` merge, production deployment, controllers and `SecurityConfig` remain HOLD.

## Decision

R1.0C is eligible for **synthetic service-layer GREEN** only after the exact-head CI matrix and all failure-path assertions pass. No claim of production readiness is permitted.
