# ADR-RM-CRM-006 — Unified tenant and Kazakhstan regional-zone naming

**Status:** PROPOSED naming decision, pending confirmation on physical rename.
**Date:** 2026-11-21
**Scope:** IAM, CRM, catalogs, orders, inventory, finance, campaigns, KMS adapters and analytics.
**Supersedes naming preferences only** in ADR-005; it does not change cryptographic identity or any deployed schema.

## Proposed field standard

| Field | Type | Meaning |
|---|---|---|
| `tenant_id` | UUID | Stable data owner/security partition (personal or organization account context); **rename target for `workspace_id`**, not geography |
| `region_zone_id` | VARCHAR(2) | Geographic KATO regional grouping, e.g. `11`, `43` |
| `kato_code` | VARCHAR(16) | Full KATO code of the location or event |
| `organization_id` | UUID | Legal entity |
| `location_id` | UUID | Branch, warehouse, POS or service location |
| `user_id` | UUID | Individual human account |
| `key_generation` | BIGINT | KMS generation, independent of region |

Do not rename `tenant_id` to `region_zone_id`, and do not use the KATO prefix to scope authentication or encryption: many organizations share the same geographic zone, while one organization can span many zones.

**Field inclusion:** `tenant_id` is required on tenant-owned business rows; `region_zone_id` is required only on entities representing physical locations or geo-attributed events (orders with a specific service/fulfillment site, stock transactions, in-person sales). It is inapplicable to global IAM accounts, KATO reference rows, remote-only activities with no grounded region and certain billing events. For those, use nullable zone with explicit reason or no zone field. Never invent a region from an organization’s legal address for unrelated operations.

The region code is derived from the first **two characters** of the canonical KATO string, after checking official KATO mapping; preserve leading zeroes and retain the full code. Persist event region snapshot for historical reporting. `region_zone_id` is not a country-independent global taxonomy; add countryCode if geographic scope expands.

## Physical storage and query design

- Security/tenant key: `tenant_id UUID`; KMS `CryptoContext.tenantId` retains the same UUID across renaming.
- Geographic search: `tenant_id + region_zone_id + customer_id + occurred_at` composite index candidate, validated by EXPLAIN ANALYZE.
- Regional partition candidates: `LIST(region_zone_id)` then optionally `HASH(tenant_id)` only on large geo-attributed tables after independent qualification. Global customer history without region predicate must remain efficient. No blanket partition-all requirement.
- Small KATO dictionary uses unique `code`, not `hash(Code)`.
- PII encryption depends only on workspace/tenant UUID and cryptographically random keys, never on region code.
- With PostgreSQL declarative partitions, any unique/primary constraint must include partition keys; explicitly evaluate child/parent FKs, global uniqueness and data movement.

## Migration requirements (NOT YET IMPLEMENTED)

The current schema and entities retain `iam_workspace`, `workspace_id`, `WorkspaceEntity`, and KMS `CryptoContext.tenantId`. Do **not** rename the column alone or change persisted tenant UUIDs.
1. Inventory every source, SQL/JPA/DTO, FK/index, audit event, token claim and serialized envelope using the old name.
2. Choose whether only the `*_id` column naming changes or the concept/entity becomes `TenantEntity`; API compatibility documented.
3. Add non-breaking views/aliases where necessary, migrate constraints and indexes transactionally, and update Java mappings.
4. Verify tenant isolation, KMS decrypt of historic encrypted data and two-pod concurrency; previous UUID must remain unchanged.
5. Drop legacy aliases only after clients/jobs/upstream consumers are upgraded.

## Relationships with previous ADRs

ADR-005 tenant isolation and two-contour `crm` / `crm_pii` remain normative. Any appearance of `workspace_id` in ADR-005 is to be read as current physical naming, with proposed target field name `tenant_id` after qualified migration. `region_zone_id` remains a separate geographic dimension; existing branch/warehouse/POS link via KATO remains unchanged.

**Product decision requested:** choose `tenant_id` as the new universal owner field while retaining `region_zone_id` for geography. Implementation is blocked on the safe migration gate, not on this ADR.
