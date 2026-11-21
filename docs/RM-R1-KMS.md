# RM-R1 — Single table tenant KMS (implementation draft)

One business KMS table: `security.tenant_key`, composite PK `(tenant_id,version)`.

Fields: tenant_id, version, wrapped_key, status, created_at, rotate_after, wrapping_key_id. Implementation adds `active_slot` (nullable, UNIQUE with tenant_id; required CHECK) to enforce **at most one ACTIVE** across H2 and PostgreSQL, and `row_version` for JPA optimistic locking.

Version rows are retained for decrypt-only access; `rotate` changes old ACTIVE to DECRYPT_ONLY and inserts next ACTIVE in one transaction. Do NOT delete old keys when the new version is activated.

Key wrapping uses AES-256-GCM with a new 96-bit random nonce and 128-bit tag; AAD includes tenant ID and version. `wrapped_key` stores nonce || ciphertext || tag. The root KEK **must be set** as `RM_KMS_ROOT_KEY_BASE64` (32 random bytes base64). Never check actual secrets into Git or reuse the test-only key. On macOS, generate a 32-byte key with `openssl rand -base64 32`. Back up and secure it outside PostgreSQL. Losing it makes wrapped tenant keys unreadable.

Current scope: key schema, JPA, provision and manually invoked rotate. There is no tenant registration API, automated scheduler, Caffeine, encryption of CRM fields or production revocation yet. These are later milestones.

`provision(tenantId)` assumes the caller validated the company; no tenant entity/FK exists yet. Concurrent first provisioning can race: the database rejects the second ACTIVE row, and the caller must retry/read the winner in a new transaction. Implement full signup idempotency with the tenant model.

`rotate` is serialized by pessimistic row locking of the current active key; multiple transactions must use the lock discipline. For eventual clusters, coordinate revocation and key-cache invalidation; never claim immediate revocation from an expiring cache alone.

Migration management: Liquibase formatted SQL only, no Hibernate schema creation. H2 used during development, PostgreSQL integration tests required before deployment.
