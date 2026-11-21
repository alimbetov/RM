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
