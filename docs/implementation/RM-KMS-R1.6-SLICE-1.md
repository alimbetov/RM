# RM-KMS-R1.6 — implementation slice 1 (in-progress)

Branch: `feat/rm-kms-r1-implementation` (forked from `docs/rm-kms-consolidated-spec`).
Mainline unaffected.

## Delivered in code

- Liquibase **new** SQL changesets `rm:0004-three-slot-kms` and `rm:0005-kms-archive`: `security.kms_tenant_key` (one tenant row, A/B/C slot fields and progress counters) and `security.kms_tenant_key_archive` (wrapped historical keys).
- Existing `rm:0001..0003` SQL remains unchanged and `security.tenant_key` remains for **legacy source compatibility**; no destructive legacy data migration.
- New `TenantKeyEntity`, `TenantKeyArchiveEntity`, repositories. Provisioning creates one ACTIVE v1 in A and rejects a tenant with legacy `security.tenant_key` records rather than generating an unrelated DEK.
- `EncryptedValue`, `CryptoContext`, `TenantCryptoService`: local AES-256-GCM, version+generation+nonce+tag, length-delimited AAD (tenant, entity, ID, field, generation), one Caffeine decrypted DEK cache.
- Integration test scaffolding: provision idempotency, tamper, wrong field, wrong object, wrong tenant, distinct nonce, legacy collision.
- Added Caffeine dependency.

## Safety controls / explicit limits

**NOT IMPLEMENTED / DO NOT ENABLE:** automated rotation, READY promotion, archive re-wrap/recovery, safe slot reuse, nightly 100-client migrations, cross-transaction writer admission fencing, live migration from existing per-version keys, PostgreSQL archive HASH partitions.

`TenantKeyService.rotate` deliberately throws `UnsupportedOperationException` until concurrency/recovery contracts are implemented and qualified. No job has been enabled. Existing legacy tenants are blocked from re-provisioning on new schema until a tested legacy-to-3-slot migration exists.

`TenantCryptoService` currently resolves ACTIVE from PostgreSQL/H2 on each **encrypt**, so cache HIT guarantees no additional unwrap or SQL for decrypted DEK but **not zero SQL for ACTIVE metadata**. Decrypt uses generation-keyed cache. A future active-generation cache must be coordinated with commit-time admission fencing, never merely TTL-based.

`EncryptedValue` is presently a Java record contract; persistent CRM encrypted field mapping and DB serialization codec are next. No production service should treat these classes as authorization checks: caller authenticates tenant and authorizes object access.

Archive table is currently **non-partitioned** for H2 portability; PostgreSQL HASH DDL is a separate later qualification. Existing schemas must not be rewritten or data dropped to fake success.

## Verification status

GitHub writes completed. Tests **NOT RUN**: execution environment has Java 21 but no Maven binary (and no verified Maven wrapper in repo). `mvn clean test` and a PostgreSQL integration run are mandatory; do not declare GREEN.

Immediate next acceptance:
1. H2 boot, Liquibase SQL checksum + JPA validate, run all existing/new tests.
2. Correct dialect discrepancies (BYTEA, TIMESTAMPTZ, constraints) based on actual failures.
3. PostgreSQL Testcontainers integration, concurrent tenant provision/idempotent retries and no legacy key loss.
4. Add complete envelope storage codec / test vectors + transactional writer admission before any key rotation.
