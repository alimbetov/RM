# RM-R1 — Single table tenant KMS (implementation draft)

One business KMS table: `security.tenant_key`, composite PK `(tenant_id,version)`.

Fields: tenant_id, version, wrapped_key, status, created_at, rotate_after, wrapping_key_id. Implementation adds `active_slot` (nullable, UNIQUE with tenant_id; required CHECK) to enforce **at most one ACTIVE** across H2 and PostgreSQL, and `row_version` for JPA optimistic locking.

Version rows are retained for decrypt-only access; `rotate` changes old ACTIVE to DECRYPT_ONLY and inserts next ACTIVE in one transaction. Do NOT delete old keys when the new version is activated.

Key wrapping uses AES-256-GCM with a new 96-bit random nonce and 128-bit tag; AAD includes tenant ID and version. `wrapped_key` stores nonce || ciphertext || tag. The root KEK **must be set** as `RM_KMS_ROOT_KEY_BASE64` (32 random bytes base64). Never check actual secrets into Git or reuse the test-only key. On macOS, generate a 32-byte key with `openssl rand -base64 32`. Back up and secure it outside PostgreSQL. Losing it makes wrapped tenant keys unreadable.

Current scope: key schema, JPA, provision and manually invoked rotate. There is no tenant registration API, automated scheduler, Caffeine, encryption of CRM fields or production revocation yet. These are later milestones.

`provision(tenantId)` assumes the caller validated the company; no tenant entity/FK exists yet. Concurrent first provisioning can race: the database rejects the second ACTIVE row, and the caller must retry/read the winner in a new transaction. Implement full signup idempotency with the tenant model.

`rotate` is serialized by pessimistic row locking of the current active key; multiple transactions must use the lock discipline. For eventual clusters, coordinate revocation and key-cache invalidation; never claim immediate revocation from an expiring cache alone.

Migration management: Liquibase formatted SQL only, no Hibernate schema creation. H2 used during development, PostgreSQL integration tests required before deployment.


## Proposed next milestone: RM-KMS-R1.2 — three-generation rolling key window (DESIGN ONLY)

Per tenant, keep **one ACTIVE**, **at most one DECRYPT_ONLY migration source**, and **at most one READY spare** in the *online operating window*. Historic RETIRED key records may be retained solely for backup/archive recovery and are not counted as online operational keys. Never claim an absolute three-row limit.

Lifecycle:
1. Signup: v1 ACTIVE.
2. Rotation: v1 DECRYPT_ONLY, v2 ACTIVE. No standby yet.
3. **Only after verified completion of re-encryption** of all live data using v1 (including all encrypted entity fields/versions and no outstanding batch jobs): move v1 to RETIRED with use prohibited in normal request path, create wrapped v3 READY.
4. Next rotation: promote v3 READY to ACTIVE; v2 becomes DECRYPT_ONLY. No v4 until v2 live-data migration has completed.
5. Do not revoke/delete v1 while older backups or legal retention require it. Archive restoration must have an explicit controlled procedure.

READY keys must be **pre-generated wrapped keys** in the same `security.tenant_key` table; they do not live in the fast-path cache and cannot decrypt user data before promotion. New table is unnecessary. Proposed DB constraints must enforce at most one ACTIVE and one READY per tenant. Current schema supports only ACTIVE/DECRYPT_ONLY/REVOKED, so this is **not implemented** and requires new Liquibase formatted-SQL changesets; do not modify executed changesets.

Automatic 90-day rotation becomes *eligibility*, not permission to bypass migration checks or exhausted spare policy: if v1 migration is incomplete, create v2 at the scheduled time but do not generate v3; if next rotation arrives with migration still unresolved, block further promotion and report rotation overdue rather than dropping v1.

Verification must examine **actual persisted encrypted records** and key version references across all encrypted CRM modules (and pending durable jobs), not just count cache hits or assume completion after TTL. Until a durable verified migration checkpoint exists, READY provisioning and RETIRED transitions remain disabled.

One ACTIVE per tenant is guaranteed by the existing `active_slot` constraint. To support READY uniqueness with both H2 and PostgreSQL, add a nullable `ready_slot` guarded by CHECK and UNIQUE(tenant_id,ready_slot), or use native per-dialect indexes when PostgreSQL becomes primary. A row-version and transaction locks are also necessary; serialization must account for concurrent encrypted writes and migration proof to avoid races.

Cache fast path: cache only ACTIVE/DECRYPT_ONLY as needed, key reference `(tenantId,version)`; invalidate active-version metadata at commit; ready keys are never cached for encryption. Cluster-wide revocation requires a separate consistency protocol.

Implementation gate: PostgreSQL and H2 integration tests for duplicate ACTIVE/READY, concurrent promotion, interrupted migration, backup/recovery, and no leftover ciphertext using a retired key.


## Superseding design decision: RM-KMS-R1.3 — ONE ROW / THREE CYCLIC KEY SLOTS

**This section supersedes earlier proposed per-version table design for the next implementation. Existing code and migrations still implement `(tenant_id, version)` rows and MUST NOT be described as compliant with the new design.**

User-approved model: one `security.tenant_key` row per company, three physical key slots A/B/C. The slots are reused cyclically, but cryptographic key bytes are freshly CSPRNG-generated on every reuse. Key generation IDs/versions are globally monotonic per tenant, never reused. Ciphertext envelope must carry an unambiguous generation ID plus nonce and authenticated tenant/entity/field AAD. An envelope referring to an overwritten generation must never silently decrypt with a replacement slot key.

Proposed columns: `tenant_id UUID PRIMARY KEY`, `key_a BYTEA`, `version_a BIGINT`, `key_b BYTEA`, `version_b BIGINT`, `key_c BYTEA`, `version_c BIGINT`, `active_slot CHAR(1)`, `ready_slot CHAR(1)`, `next_version BIGINT`, `rotate_after TIMESTAMPTZ`, `wrapping_key_id VARCHAR(128)`, `row_version BIGINT`. Add CHECK constraints and slot-state validations; if root KEK rotation can result in differing wrap KEKs, store wrapping-key ID per occupied slot or rewrap all slots atomically.

Lifecycle for a newly registered tenant:
- Initially v1 ACTIVE in A; B/C empty.
- First rotation installs fresh v2 ACTIVE in B; A becomes DECRYPT_ONLY.
- Once *provably* no live data/jobs depend on v1, A becomes reusable and fresh v3 is wrapped in C as READY (reserve).
- On second rotation promote READY v3 in C; B becomes DECRYPT_ONLY.
- Once v2 is fully migrated, generate fresh v4 READY in A; repeat cyclically.
- No promotion/overwrite while an old generation has references. The time-based schedule is an eligibility trigger, not authority to break the safety gate.

Critical safety: exactly 3 slots cannot by themselves guarantee recoverability from historical backups. Never overwrite the only copy of a key while backups/archived data still reference it. Options: (a) retain an external encrypted, access-controlled key archive with independently testable restore capability, or (b) retain old keys until every backup requiring them expires and delay slot reuse accordingly. If strict one-row-only storage AND long-lived backups AND uninterrupted rekey cycles are all mandatory, the design is incompatible without external archival storage. Decide retention policy before implementing slot overwrite.

Concurrency: a write lock around row-level rotation and a protocol coordinating active encryption writers, migration workers, proof of zero outstanding references, cache invalidation and commit is required. A single SQL row transaction cannot by itself make migration safety atomic across unrelated encrypted domain records. Preserve only one ACTIVE and at most one READY. A rotated-away ACTIVE must stay available for decrypt until migration proof; REVOKED is not synonymous with migrated/retired.

**Migration note:** existing Liquibase SQL changeset `rm:0003-tenant-key` was already committed and must not be edited on deployed databases. Introduce a new formatted SQL changeset for old-to-new migration after verifying actual deployed environments and data. In dev-only disposable H2, recreation from a fresh changelog is possible, but don't silently mutate applied changesets.

**Status:** design decision only. No JPA/SQL code transition or runtime verification has been performed for this model.
