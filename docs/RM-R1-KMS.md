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


## RM-KMS-R1.4 — per-slot usage statistics in the SAME tenant row (design)

Keep **one `security.tenant_key` row per tenant**. Add per-slot statistics:
- `remaining_a/b/c BIGINT NOT NULL DEFAULT 0`: last accounted number of live encrypted *field envelopes* referencing the exact generation in the slot (not number of customers or key cache hits).
- `migrated_a/b/c BIGINT NOT NULL DEFAULT 0`: successful migration count for this generation; reset when slot receives a new generation.
- `last_scan_a/b/c TIMESTAMP WITH TIME ZONE`: last authoritative reconciliation time.
- Optional `stats_verified_a/b/c BOOLEAN NOT NULL DEFAULT FALSE`: certification flag; invalidated by any mutation that could reintroduce the generation; never interpreted as proof independent of data scan.
- `row_version` protects against stale updates. Counts are nonnegative and relate to generation ID, not merely physical slot letter.

Batch re-encryption: read a bounded batch of encrypted records for a specific old generation, decrypt+encrypt with new generation and store new envelopes with optimistic checks/CAS. Update per-slot counters *only after confirmed successful persisted transitions*, ideally in the same DB transaction; increments/decrements must be atomic, idempotent and based on actual changed rows. On rollback no stats change. Avoid duplicate double-count from retries. A single tenant-row update per batch, not per field, reduces lock contention.

**Authoritative safety gate**: counters are progress telemetry, not proof of zero references. Before slot reuse, (1) fence new writers and background jobs using old generation, (2) await outstanding writes, (3) reconcile authoritative scans across ALL encrypted live tables and durable queues, (4) verify no references to old generation remain in the consistent fence, (5) account for old backups / external encrypted key archive, and (6) atomically change slot state and invalidate cache. If the live data or backups still depend on the old generation, defer reuse. Encrypted data may be moved by multiple modules and concurrent operations; a zero counter without this fence is unsafe.

Counters are in the same row, but **do not lock/update that row for every normal data-field write**, which would turn key management into a bottleneck. Use batch delta persistence plus periodic reconciliation; clearly label snapshot counters as advisory between scans. Exact accounting under concurrency needs transactional write discipline or a durable outbox/reconciliation mechanism.

No new SQL or Java is implemented by this section. Next implementation must migrate existing per-version JPA/SQL to three-slot model using a new Liquibase formatted-SQL changeset, without editing applied changesets. Test H2 and PostgreSQL before claiming this is production-ready.


## RM-KMS-R1.5 — nightly batch re-encryption, 100 clients (approved design, not yet implemented)

User decision: night-time batch migration, **up to 100 clients per transaction**. This is client-count batch size, not number of encrypted attributes or key rotations. Start schedule proposal: 02:00 to 05:00 in `Asia/Almaty`; configurable via properties. Single worker in MVP, 200-ms inter-batch pacing (configurable), up to three transient-error retries with bounded backoff. Stop at end of maintenance window; resume next night. An interrupted batch must be safely repeatable.

Proposed configuration:
```yaml
rm:
  kms:
    migration:
      enabled: false # enable once client/contact envelope migration is implemented and tested
      cron: "0 0 2 * * *"
      zone: "Asia/Almaty"
      batch-size: 100
      window-end: "05:00"
      pause-between-batches: 200ms
      max-retries: 3
```

Algorithm:
1. Select tenants requiring migration; acquire a single-worker execution/admission lock. At most one old DECRYPT_ONLY and one current ACTIVE generation are used.
2. Fetch **up to 100 distinct clients** still referencing the old generation, ordered deterministically by client ID; include any encrypted fields of those client aggregates and contact children. Ensure no unstable OFFSET pagination; query old-version references directly so completed clients are excluded.
3. Within one transaction per batch, use JPA/SQL row-version checks or row locks, re-read current encrypted envelopes, decrypt using the old generation and re-encrypt with ACTIVE using unique nonce per field and AAD bound to tenant/entity/field. Preserve business values and metadata.
4. Update data envelopes and *batch-level* per-slot advisory counters in the same transaction, derived from actual changed references; never increment by requested batch size. Retry whole batch on optimistic conflict/deadlock. Failed batch rolls back both data and statistics.
5. Rescan and reconcile snapshot statistics periodically; counters alone do not authorize reuse. If a client has no old fields, skip without increment.
6. Stop at configured local end time. Resume by querying remaining old-version records on the next night, rather than relying on in-memory cursor alone.
7. At zero apparent remaining, fence old-generation writers, scan all encrypted CRM entities (including independent communications, invoices, outbox/jobs), reconcile under a consistency protocol, verify required backups can still decrypt, then allow slot reuse/READY creation. Never overwrite old slot solely because a batch counter says zero.

Do not hold the `security.tenant_key` row lock during the whole night; acquire only around batch counter updates or short control transitions. The normal CRM fast path must remain available. To prevent the night job from repeatedly scanning already-clean clients, require a persisted key-generation reference on each encrypted envelope, not just the company-level slot ID.

Tests required: 100 vs 101 clients, partial contacts, retry/rollback, competing client update, zero-count false positive, timezone window, restart/resume, tenant isolation, recovery from lost root key, zero old-version references across all modules. Future PostgreSQL tests required in addition to dev H2.

**Implementation status**: architecture specification only. RM currently has neither a CRM client entity nor the three-slot schema. Do not enable or advertise the job as running until these prerequisites are delivered and the tests pass.


## RM-KMS-R1.7 — tenant_id partitioning proposal (design, not implemented)

Use bounded PostgreSQL `HASH(tenant_id)` buckets, **not one physical partition per tenant**. New companies are routed automatically to existing buckets; creating a physical partition at company signup is unnecessary.

Decision for MVP:
- `security.tenant_key`: one row per tenant, ordinary indexed table. A PK lookup over one row per tenant is unlikely to justify declarative partitioning initially.
- `security.tenant_key_archive`: PostgreSQL `PARTITION BY HASH (tenant_id)` with **8 buckets** initially, subject to actual query/load/volume evidence. Parent PK `(tenant_id, key_version)` includes the partition key. Both remain logically tenant-addressed.
- If business explicitly requires BOTH physically partitioned, use the same 8-bucket strategy for `tenant_key` too, but only at PostgreSQL migration and after assessing trade-offs. Do not create per-company physical partitions.
- On H2 development, use equivalent **non-partitioned tables** and identical JPA entities. H2 PostgreSQL mode does not implement PostgreSQL's declarative hash-partition DDL. PostgreSQL integration tests are mandatory to qualify native partitioning.
- Liquibase changesets MUST be SQL-only; use DB-specific contexts/dbms selectors to keep H2 and PostgreSQL DDL separate. Do not edit applied changesets. Do not introduce PostgreSQL `PARTITION BY` into H2 changelogs.
- No security guarantee from partition placement: tenant isolation requires tenant-aware authorization and PostgreSQL RLS. The KMS fast path and a separate restricted archive read path remain unchanged.
- Don't promise partitions speed up a tiny key table. Initial partition count is a benchmark hypothesis; changing hash-bucket layout later entails data movement and a migration plan.
- **Existing code is still per-version `tenant_key` and the archive table does not yet exist.** No KMS partition DDL has been deployed or tested.
