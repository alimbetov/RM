# RM — CRM Modular Monolith

Architecture baseline: **RM-R1 (proposal, not implementation)**. Java 21, Spring Boot, H2 (development), PostgreSQL (later), Liquibase (SQL-only), Caffeine (planned). One deployable application, no Redis/Vault/microservices in MVP.

## MVP bounded modules
- `identity`: company (tenant), users/membership, RBAC, registration
- `crm`: clients, contacts, consent, communication history
- `sales`: orders, lines, immutable estimate revisions
- `finance`: invoices, payments, allocations, receivables (management reporting only)
- `marketing`: segments, campaigns, templates, delivery/unsubscribe events
- `workflow`: tasks, audit, dashboard queries
- `security`: embedded KMS, encryption, tenant security (no standalone service)

Keep relational business entities where necessary; **minimize only the KMS schema**, not financial integrity. No generalized JSON mega-table.

## ADR-001 — Embedded tenant KMS, fast path and automatic rotation

### Storage (one dedicated KMS table)
`security.tenant_key`:
- `tenant_id UUID NOT NULL`
- `version INT NOT NULL`
- `wrapped_key BYTEA NOT NULL`
- `wrapping_key_id TEXT NOT NULL`
- `status TEXT NOT NULL` in ACTIVE, DECRYPT_ONLY, REVOKED
- `created_at TIMESTAMPTZ NOT NULL`
- `rotate_after TIMESTAMPTZ NOT NULL`
- `PRIMARY KEY (tenant_id, version)`
- partial unique index on `tenant_id WHERE status='ACTIVE'`

Optional tenant status/membership in normal identity tables; KMS does not duplicate them.

### Registration
1. Authenticate owner and validate company registration idempotently.
2. Generate random 256-bit tenant data-encryption key (DEK) with a CSPRNG.
3. Wrap DEK under an independently protected root key-encryption key (KEK).
4. Commit company and version-1 ACTIVE wrapped key atomically.
5. Only after commit make key available to encryption operations.

**Root KEK must live outside PostgreSQL, Git, application.yml, database backups and source control** (e.g. protected host secret/volume with separate backups and strict permissions). Encrypting DEKs with another key stored beside the DB would not protect against a full DB compromise. Recovery of root KEK and encrypted database must be tested. Multi-host production requires a stronger centrally controlled root-key facility or equivalent.

### Fast path
`encrypt(tenantId, entityId, fieldName, plaintext)` and `decrypt(..., envelope)` are the only business-facing APIs.
- Caffeine cache key: `(tenantId, keyVersion)`; value: unwrapped AES key; bounded (initially max 1,000, expire after access 10 minutes).
- Cache hit: no SQL for key fetch; cache miss: single-flight fetch of wrapped key and unwrap.
- Per-field AES-256-GCM with unique random 96-bit nonce; persisted envelope contains format version, tenant key version, nonce, ciphertext+tag.
- AAD binds tenant ID, entity type, entity ID and field name; reject altered values.
- Exact-match phone/email lookup uses separately protected HMAC key(s), never bare SHA256 or AES ciphertext.
- Keys never exposed through controllers, logs, serialization, or app heap dumps; disable heap dumps in production. Java heap eviction is not guaranteed memory erasure.
- AuthZ and tenant isolation remain mandatory: key caching does not imply access authorization.

### Scheduled rotation
Default policy for design: evaluate daily, rotate data keys every 90 days (configurable; not a compliance claim). On each eligible tenant:
1. Acquire per-tenant control lock; repeat eligibility check under transaction/row lock (or advisory lock) to ensure idempotency.
2. Generate/wrap vN+1 using secure randomness.
3. Atomically mark current vN DECRYPT_ONLY and vN+1 ACTIVE; enforce uniqueness and ensure exactly one active version.
4. After commit invalidate active-version metadata/cache; future encryption uses vN+1. Old ciphertext declares vN and remains decryptable.
5. Lazy re-encrypt old fields on safe update, with compare-and-set; optionally schedule a bounded background migration later.
6. Retain old DEKs while old rows, backups, or legal retention require them. Never automatically revoke/delete merely because 90 days elapsed.
7. Root KEK rotation is a separate re-wrapping operation and must be tested independently.

**Correctness:** active-version caches need explicit invalidation and synchronization. For MVP, support one Spring Boot instance and serialize rotation with encryption admission; a cluster needs distributed coordination and acknowledged invalidation before claiming immediate rotation/revocation. Eviction alone is not an immediate revocation guarantee. Key revocation fails closed.

### Tests/gates
- Cross-tenant decryption and changed AAD fail.
- Registration rollback and idempotent retry.
- Concurrent scheduler executions yield one new ACTIVE key.
- Cache hit performs no key-query; concurrent miss single-flight.
- Old data readable after rotation; new data uses new version.
- Re-encryption preserves exact plaintext, nonce uniqueness, and backup recovery.
- Restart loads wrapped keys correctly; lost root key leads to explicit recovery procedure, not silent key reset.
- Revocation prevents newly admitted crypto operations in supported single-node deployment.
- Test with PostgreSQL and JMH; publish p50/p95/p99, allocations, cache hit rate.

## Delivery order
1. RM-R1.0: schema/registration/tenant constraints, Flyway.
2. RM-R1.1: KMS fast path and negative crypto tests.
3. RM-R1.2: scheduled rotation, lazy migration, concurrency tests.
4. RM-R1.3: CRM clients/contacts and tenant-aware APIs.
5. Sales, estimates, finance, campaigns and reporting.

## Non-goals
No homemade cryptographic primitives, no database-wide transparent encryption substitute, no separate KMS service, no unnecessary partitioning of low-volume transactional tables, no immediate multi-node revocation guarantee in MVP.
