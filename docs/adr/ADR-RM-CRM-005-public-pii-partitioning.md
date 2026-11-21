# ADR-RM-CRM-005 — Public/business vs protected PII, KATO labels and tenant partitioning

**Status:** ACCEPTED AS TARGET ARCHITECTURE; physical CRM table split and partitions NOT IMPLEMENTED.
**Branch:** feat/rm-kms-r1-implementation
**Date:** 2026-11-21

## Decision 1: KATO shared technical directory, visible three-language labels

- Global, non-tenant `ref_kato_territory` contains `code VARCHAR(16) PK`, source hierarchy IDs, `name_kk` (NameKaz), `name_ru` (NameRus), `name_en` (NameEng), `level_no`, deletion flag. Only active levels 2–3 may be selected for new organization locations.
- `code` is **internal display-hidden reference**, not a secret; use it as the FK value and in API payloads as needed. User interface renders localized names, not code.
- `name_en` is a **Romanization/transliteration of Russian text**, NOT translated official English place names. Version: `RM-RU-LATN-1`; the original `name_kk` and `name_ru` remain authoritative. Transliteration changes require a versioned migration/golden test, not silent edits. Modern spelling and official English exonyms are a separate future `official_name_en` field when an authoritative source is available.
- First source snapshot contains only 100 entries; a general Kazakhstan picklist needs complete authoritative data, a source/version and effective-date tracking. Parent resolution uses source_id; no invented parents or naive code-prefix nesting.

## Decision 2: no identity/encryption/partition key from KATO hash

`hash(Code)` is not a unique identity guarantee. Java `String.hashCode()` and even truncated crypto hashes can collide, change meaning across algorithms, and do not isolate tenants. SHA-256 could be used as an optional derived checksum for ETL integrity, but not a primary key without collision checking, not an encryption key, not a secret, and not a tenant partitioning discriminator.

**Stable tenant identity** is `Workspace.id UUID`, and KMS `CryptoContext.tenantId` must equal that workspace ID. KMS derives or unwraps cryptographically random per-tenant DEKs and uses random nonces per AES-GCM envelope; KATO does not generate key material.

## Decision 3: split logical storage into two access-controlled PostgreSQL contours

**Open / business metadata:** schema `crm` (future) — `customer` non-sensitive workflow metadata and opaque `customer_id`, order status, product catalog/SKU/price, estimate versions, payment allocations, task status, branch/warehouse/POS references, KATO codes. Open **means not field-encrypted**, not public to all tenants; RBAC/tenant isolation still mandatory. Client names may themselves be personal data and must not enter open columns without privacy classification.

**Encrypted / personal data:** schema `crm_pii` (future) — customer and contact name *when personal*, email, phone, identity numbers, detailed private address, free-text sensitive notes, communications payload, consent evidence where personal. Each row: `workspace_id`, entity ID, field or type, `encrypted_envelope BYTEA` via `EncryptedValueCodec`, format/generation covered by envelope, timestamps and rowVersion. Do not duplicate plaintext in audit/outbox/search/log/analytics.

- Each contour gets a dedicated application DB principal, **least privilege, separate migrations/role grants** and explicit access service. Encrypted and unencrypted rows can reside in the same PostgreSQL instance initially; schema separation is a security control only with distinct grants, not a substitute for authenticated access or crypto.
- Do not create a general decrypt endpoint. Authorization/membership check must happen before decrypt, and platform AUDITOR must not obtain implicit PII decrypt capability.
- Reference `crm_pii` from `crm` by opaque ID in an authorized application service, with same-tenant `workspace_id` constraint; separate schemas still share same tenant identity. Cross-schema transactions work while in the same PostgreSQL database. Future separate microservice/storage needs outbox and fault semantics, not implicit distributed ACID.
- Personal information is not reliably classified by field alone: free text, company contacts, billing recipient, address and document attachments may also contain PII. Classify every CRM-R1A..R1G field and object store payload before migration.
- Exact-match PII search needs a separately keyed/HMAC-based, tenant-scoped blind index with privacy review and rate limits; a plain SHA256(email) leaks dictionary information. KATO hash cannot replace this.
- Consent email: deny by default, track purpose/source/time/withdrawal, recheck immediately before campaign dispatch, keep proof without exposing personal data in open logging.

## Decision 4: HASH partition only when data volume/performance justifies it

- **Candidate** large multi-tenant `crm.customer_event`, `crm.order_event`, `crm_pii.contact_secret`, audit/outbox or large orders: `PARTITION BY HASH(workspace_id)` with PostgreSQL fixed initial partition count (e.g. 16 or 32, subject to benchmarking, not automatic immutable allocation per new tenant).
- Never HASH partition the tiny `ref_kato_territory` dictionary by code. A 100-row table benefits from ordinary PK/index and in-process read-through cache, not partitions.
- Partitioning is by an actual `workspace_id UUID` column. PostgreSQL HASH determines routing internally; we do **not** store Java `hashCode` as a PK or manually choose partition from it.
- PostgreSQL requires a partitioned table's UNIQUE/PRIMARY KEY to include every partitioning column, e.g. `PRIMARY KEY (workspace_id, id)`, and all composite foreign keys reference both. Check these constraints, JPA identity, `ON CONFLICT`, global uniqueness requirements and restore paths before rollout.
- Unique personal IDs, invoice numbers, SKU and campaign idempotency keys are scoped to the workspace. Cross-tenant global uniqueness, if truly required, lives in an unpartitioned registry with transaction-safe enforcement.
- Partition creation is **DDL managed by Liquibase migrations**, not spontaneous `CREATE TABLE` for every organization. Increasing modulus requires a controlled repartition/copy/switch plan and load tests. Multi-pod apps never create partitions concurrently on business requests.
- Expose per-partition metrics, retain one-query tenant pruning, benchmark 2/5/10 pods, p95/p99 before selecting fixed bucket count. Consider time-based partition on huge immutable logs only after explicit retention needs; multi-level partition complexity must be justified.

### Example (design only, NOT applied)

```sql
CREATE TABLE crm_pii.contact_secret (
  workspace_id UUID NOT NULL,
  contact_id UUID NOT NULL,
  encrypted_envelope BYTEA NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (workspace_id, contact_id)
) PARTITION BY HASH (workspace_id);

CREATE TABLE crm_pii.contact_secret_p00
  PARTITION OF crm_pii.contact_secret FOR VALUES WITH (MODULUS 16, REMAINDER 0);
-- p01..p15 explicitly created by versioned migration
```

## Rollout order and mandatory QA gates

1. **GEO-R1 (current):** `NameEng` migration + UI read model for KATO with code as hidden reference and levels <=3. SQL tests on H2 and PostgreSQL.
2. **IAM-R1 finish:** user/global roles, organization/workspace memberships; authorize actions. Until authorization exists, do not publish tenant mutation REST endpoints.
3. **CRM-R1A:** classify fields, implement `crm` + `crm_pii` schema/grants, customer/contact + consent, encrypted persistence round-trip across restarts and two pods.
4. **CRM-R1A.5:** catalogs, categories, variants, prices, units, costs; connect locations to warehouses/branches/POS.
5. **CRM-R1B/C:** orders and immutable estimates; snapshot catalog prices/cost, same-tenant FK.
6. **CRM-R1D:** invoices, payments, receivables and meaningful margin formula; avoid currency mixing.
7. **CRM-R1E–G:** consent-gated campaigns, tasks/communications/audit, reproducible dashboards.
8. **HTAP-HASH-QUAL (deferred):** partition candidates based on production-like cardinality and p95/p99, equality of query totals partitioned/unpartitioned, tenant isolation, schema/PK/FK correctness, copy/migration and restore. No blanket partition-all policy.

## Non-negotiable acceptance

- KATO names across RU/KK/Latin, deterministic romanization, missing parents handled, deleted rows protected and Level >3 rejected.
- Tenant A cannot link to or decrypt tenant B data; platform auditor metadata read-only.
- PII plaintext never persisted in business, logs, outbox, audit, cache keys or dashboard materialization.
- KMS continues to use workspace-scoped random DEKs, independent of any territory code. Rotation/migration remain gated as previously.
- HASH partitioning and logical split are **architecture design only** until implemented, migrated and qualified in PostgreSQL. H2 smoke is not evidence of native PostgreSQL partition behavior.


## Decision 5: KATO regional zoning and region-local client operation search

**Product decision confirmed:** a separate `region_zone_id VARCHAR(2)` may be derived from the first two digits of a KATO code to support **regional access paths**. `workspace_id UUID` remains independent, is never overwritten with the geographic prefix, and remains the tenant identity for IAM/KMS. Region zone is a non-secret derived attribute.

- For codes like `110000000` → `11`, `433200000` → `43`, the two-digit zone must be cross-checked against a complete authoritative KATO dataset before global production adoption. No inference of a missing KATO ancestor from a prefix.
- `org_location` can carry `region_zone_id` as a validated derived field, but one organization can span many zones. The operation's zone is **its location at event time**, not merely the customer's registered address; retain snapshots on immutable business events when territories or store assignments change.
- Use regional zoning **only for high-volume geographically scoped operations** such as sales, orders, service visits, warehouse movements and regional analytics. In each row keep `workspace_id`, `region_zone_id`, `kato_code`, `location_id`, `customer_id`, `operation_type` and `occurred_at` where appropriate.
- Region-local query predicates must always include both `workspace_id` (authorization) and `region_zone_id` (partition pruning). Candidate index: `(workspace_id, region_zone_id, customer_id, occurred_at DESC)`; confirm via `EXPLAIN (ANALYZE, BUFFERS)` and realistic distributions.
- Candidate physical hierarchy: `PARTITION BY LIST(region_zone_id)`, optionally `PARTITION BY HASH(workspace_id)` in each regional child **only after benchmark**. This is NOT automatic DDL for each tenant/region. Partition count, DDL, PK/UNIQUE including partition keys, cross-tenant FK integrity, fallback/default partition, reassignment/migration, lock contention and maintenance overhead must be qualified first.
- **Geographically nearby** means administrative proximity by default (same province/district); the prefix cannot determine road distance or a radius in km. Genuine nearest-branch/radius search requires validated coordinates and a separate spatial index (PostGIS). **Similar customer transactions** (items, amounts, time patterns) require transactional indexes or analytical/search features and are not guaranteed by region partitioning.
- If most queries are `workspace_id + customer_id` without region, regional LIST partitions may *worsen* performance by scattering a customer's activity across partitions. Keep an unpartitioned or hash-only baseline and compare regional and customer-centric workloads before selecting storage topology.
- **Qualification cases:** overlapping tenant UUIDs in same region never leak; multi-region organization history is complete; same customer in multiple regions; missing/obsolete KATO; region reassignment without changing historical snapshots; customer-by-date search without region; region filtered search uses partition pruning; p95/p99 benchmarks across 1/5/20 million operations and multiple pods.

**Implementation status:** architectural decision only. No `region_zone_id` column or regional partition DDL is included in current RM source; do not report as implemented.
