# RM-KMS: R1.6C + R1.6D qualification and next gates

**Branch:** `feat/rm-kms-r1-implementation`; draft PR #1.  
**Verified GitHub Actions run:** https://github.com/alimbetov/RM/actions/runs/44870605437  
**Result:** **BASELINE GREEN** on H2 and PostgreSQL, **FULL MULTI-POD / PRODUCTION HOLD**.

## Completed in current slice

- `rm:0006-kms-generation-invariants`: new additive SQL constraints for **at most one DECRYPT_ONLY** and `next_generation > each occupied generation`. Prior applied Liquibase changesets are unchanged.
- `TenantKeyService.provision`: explicit standalone `REQUIRES_NEW` transaction; one INSERT winner; unique PK losers roll back and read the winner in a *fresh* transaction. Unexpected constraint violations are rethrown when no successfully provisioned row exists. Legacy generation records remain protected from accidental overwrite.
- `EncryptedValueCodec`: deterministic versioned binary envelope, strict magic/version/generation, 12-byte nonce and length-bounded AES-GCM ciphertext/tag; no plaintext or DEK stored in the envelope.
- `KmsPersistedRoundTripTest`: test-only SQL table (not production schema), insert serialized ciphertext, load raw bytes, decrypt with **a new TenantCryptoService instance with empty cache**; wrong entity context rejected; malformed codec rejected; 2 concurrent provisioning callers; SQL constraint rejections.
- H2 `dev`: **22 tests run, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS. PostgreSQL `test`: **22 tests run, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS. Liquibase: **6 changesets**.

## Important limitations

- `R1.6D` verifies SQL persist/reload with fresh crypto instance, **not a full JVM/application restart, multi-process test, nor a real CRM table**. Production integration and application restart qualification remain required.
- Provisioning is a deliberately **independent transaction**. It is not atomically coupled to a future tenant-registration transaction. In a distributed tenant lifecycle use an idempotent registration command and acknowledged KMS provisioning state; do not assume distributed ACID.
- The two-thread concurrency test is a minimum race demonstration, **not** a high-contention multi-pod stress qualification.
- Rotation, archive re-wrap/restore, legacy migration and scheduler lease/fencing are **not implemented**. `KmsDeploymentGuard` forbids enabling rotation and migration.
- Existing per-generation legacy key records are retained. No external scheduler or multi-JVM coordination has yet been verified.

## Next gates in agreed order

### R1.6D completion
- Move `EncryptedValueCodec` into the actual authorized CRM field persistence adapter once CRM entity shape is approved.
- Test process restart / two distinct application contexts, persisted payload byte-for-byte stable and consistent AAD, negative mutation and migration compatibility.
- Confirm test DB not shared: disposable PostgreSQL database; do not run destructive tests on `rm_test` shared developer DB.

### R1.7 scheduler qualification
- Identify the *actual RM scheduler* and verify: single-winner claim, durable owner identity, lease expiry, cancellation semantics, fencing token, behavior after pod suspension and DB failover.
- If equivalent features absent, implement a DB-backed per-tenant lease and monotonic fencing token; no scheduled KMS jobs enabled until real PostgreSQL two-worker tests pass.
- Ensure stale worker checks token **in the same transaction as its business mutations** under lock; scheduler exclusivity alone is not sufficient.

### R1.8 multi-pod generation synchronization
- Persistent per-tenant epoch/generation gate and read/write admission encompassing commits, not just encrypt calls; no stale writer commit after promotion.
- Cache HIT for revoked generation must fail closed; local Caffeine eviction alone does not work across pods.
- Qualify pause/resume, crash after commit before broadcast, 2/5 pods, concurrent CRUD/promotion, expired lease, DB disconnect, retry/idempotency.

### R2.0 archive, restore and historic generations
- Re-wrap DEK under versioned archive KEK, independent backup/restore evidence.
- Legacy `security.tenant_key` safe migration including >3 generations, mapping each envelope and no destructive cleanup until fully verified.
- Register all encrypted-field scanner providers and perform exhaustive old-reference scan before reclaiming any slot.
- Recovery from old DB snapshot under expected historic KEK, no ordinary online archived-key fallback.

**Admission:** R1.6C basic gate PASS; R1.6D SQL fixture gate PASS; R1.6D real CRM process-restart gate PENDING; R1.7, R1.8, R2.0 PENDING. Production and automatic rotation remain HOLD.
