# RM-DOMAIN-MAP-R1 — Domain-driven modular CRM

**Status:** DRAFT / FOR DESIGN REVIEW.
**Scope:** business domains, aggregates, ownership, identifiers and cross-domain contracts. No new CRUD/API implementation authorized by this document.\n**Mandatory naming standard (proposed):** [RM-MODULAR-DOMAIN-DB-R1](RM-MODULAR-DOMAIN-DB-R1.md) — module prefixes for all new tables; legacy names migrate separately.
**Architecture:** one Spring Boot modular monolith, one PostgreSQL, KMS as internal port, future modules independently extractable if needed.

## Universal identity rules

- `tenant_id UUID`: owner and security boundary, stable across regions, corresponds to existing `iam_workspace.id` pending safe naming migration.
- `region_zone_id VARCHAR(2)`: first two digits of verified KATO code, only for geographically grounded locations and operations, never identity/crypto scope.
- `kato_code VARCHAR(16)`: exact canonical administrative territory code (region/district selectable). Detail address separate; missing/obsolete KATO must fail new placement and preserve historical relationships.
- `organization_id UUID`: legal/company profile. One tenant may represent a personal account or an organization; do not use region as tenant.
- `location_id UUID`: branch, warehouse, point-of-sale. One tenant can span multiple zones.
- `user_id UUID`: global individual identity.
- One tenant's business identifiers and references cannot cross tenant via application logic or DB constraints; composite `(tenant_id,id)` unique keys and FK in actual tables.

## Bounded contexts and aggregate ownership

| Domain | Core aggregate root | Internal entities and value objects | Sole authoritative responsibility |
|---|---|---|---|
| IAM | `UserAccount`, `Tenant`, `Organization` | `TenantMembership`, `GlobalRoleAssignment`, permissions | User identity, tenant membership, platform/admin/auditor authorization |
| GEO | `KatoTerritory`, `Location` | KATO parent/level/names, `RegionZone`, address | Shared territorial dictionary; tenant-scoped sites, region validation |
| CRM | `Customer` | `Contact`, `ConsentEvent`, `CustomerTimelineEvent` | Customer lifecycle and consent evidence; PII encrypted via KMS |
| CATALOG | `CatalogItem`, `PriceList` | `Category`, `SkuVariant`, `Unit`, `PriceRevision`, `CostRevision` | Product/service descriptions and current/effective price and costs |
| SALES | `CustomerOrder`, `Estimate` | `OrderLine`, `OrderStatusEvent`, `EstimateRevision`, `EstimateLine` | Accepted commercial terms, mutable order state, immutable estimate revisions |
| INVENTORY | `StockBalance` by SKU/location | `StockMovement`, `StockReservation` | Physical stock and concurrent reservation; no estimate automatically reserves |
| FINANCE | `Invoice`, `Payment` | `InvoiceLine`, `PaymentAllocation`, `Receivable` projection | Issued billing, settled payment allocations, overdue/outstanding |
| MARKETING | `Campaign`, `Segment` | `TemplateRevision`, `CampaignRecipient`, `DeliveryEvent` | Consent-gated communication and provider event history |
| TASKS | `Task` | `CommunicationActivity` | Due dates, assignees and task lifecycle, customer/order references |
| AUDIT | `AuditEvent` | immutable metadata | Tamper-evident, append-only change log without plaintext PII |
| KMS | `TenantKey` | wrapped DEK, generation, encrypted envelope | Tenant-context encryption/decryption; no ability to grant CRM access |
| FILES | `StoredFile`, `FileAttachment` | `FileStorageLocation`, checksum, file state | Tenant-scoped metadata and secure binary storage via replaceable LOCAL/RustFS adapter |

**Billing/Subscriptions:** `Plan`, `Subscription`, `UsageCounter` are a future separate domain (commercial entitlements), not role enums. Do not implement until IAM access is established.

## Boundaries and dependencies

- CRM references `tenantId` and ID of IAM/GEO records; it does not own authorization or KATO classification.
- SALES reads catalog price/cost and copies **immutable snapshots** into lines; future catalog edits do not reprice confirmed orders or accepted estimates.
- Estimate acceptance writes immutable revision/lines and order snapshots atomically; repeated request must not duplicate lines.
- FINANCE references finalized orders/estimates, but does not mutate their item snapshots; payments and allocation are distinct from order amount, and currency never mixes.
- INVENTORY relies on SALES order line references but is authoritative for `available = onHand - reserved`, not SALES.
- MARKETING never treats contact creation as `consent_email=true`. Check current purpose-specific consent and unsubscribe **at send time**.
- TASKS links by `(tenantId, targetType, targetId)`, with application resolution/permission validation; no arbitrary cross-tenant polymorphic reference without checks.
- AUDIT records actor/tenant/action IDs, timestamps, correlation, *sanitized metadata*. PII body and raw encryption keys are excluded.
- FILES is the only owner of storage keys/paths. CRM, CATALOG, SALES, FINANCE and TASKS store tenant-scoped file references, never blob URLs. Initial binaries on durable server disk; RustFS later via a provider adapter without changing `file_id`.
- KMS `CryptoContext.tenantId` uses stable tenant UUID only; location/region cannot derive DEKs.
- All new domain services accept authorized actor context from a future security adapter; never expose public API that trusts `actorId` provided by caller.

## Storage strategy without premature complexity

- **`public`**: current foundational IAM and reference tables (later schema organization separately if warranted).
- **`crm`**: tenant-scoped business entities and non-sensitive workflow fields; "not encrypted" does **not** mean public.
- **`crm_pii`**: sensitive personal/contact data and confidential notes with encrypted envelope and `(tenant_id, entity_id)` linkage.
- Maintain one PostgreSQL instance and normal indexes initially. No automatic partition DDL and no obligatory `region_zone_id` on identities, purely online tasks or billing.
- Query patterns: `(tenant_id,customer_id,created_at)` for customer timelines and `(tenant_id,region_zone_id,created_at)` for region-scoped operations. Consider LIST(region) / HASH(tenant) only if real EXPLAIN and p95/p99 improve, accounting for global customer history.
- Keep `KatoTerritory.code` as unique identifier; no `hash(Code)` identity or crypto.

## Incremental vertical slices and qualification

1. **IAM-R1** — identity, tenant, membership, global role, authoritative authorization; existing `iam_workspace` renamed later by deliberate migration, never UUID replacement.
2. **GEO-R1** — KATO 3-language labels, level <=3, location branch/warehouse/POS, region zoning.
3. **FILES-R1.0** — minimal PostgreSQL file metadata, attachment links, local disk adapter, staged upload/download and tenant authorization; [file domain spec](RM-FILES-R1.md). Sensitive file bytes require encryption-at-rest before production.
4. **CRM-R1A** — customer, contact, consent with encrypted PII and tenant isolation; **first implementation currently under CI**, not fully admitted.
5. **CATALOG-R1A.5** — service/product category, variants/SKU, price lists, versioned effective prices; inventory optional.
6. **SALES-R1B/C** — orders, lines, pipeline, estimates and revisions; snapshots.
7. **FINANCE-R1D** — invoice/payment/allocation/receivables/margin with reconciled test numbers.
8. **MARKETING/TASKS/AUDIT/RPT-R1E–G** — consent-gated campaigns, tasks, audit and read-only dashboards.
9. **PERF-R2** — load measurement and partition selection after data volumes are known.

## Mandatory quality gates by domain

- Cross-tenant guessed UUID denied in reads, writes and reference linking.
- Viewer cannot mutate; auditor does not automatically decrypt PII; inactive membership denied.
- All sensitive fields and sensitive file payloads protected via appropriate KMS-backed encryption, no plaintext in audit or application logs.
- FILES local storage rollback/recovery, path traversal, cross-tenant attachments and restart persistence are verified before use.
- Consent opt-in/withdrawal and delivery gating tested with conflicts.
- SKU uniqueness per tenant; accepted estimate/order snapshots stable after catalog price changes.
- Payment allocations cannot exceed settled payment or issued invoice and preserve currency.
- Geographic search works for multiple regions in one tenant without missing a customer history.
- H2 smoke plus real PostgreSQL integration, and two-pod stress tests before production for relevant shared state.

## Compatibility and current development note

The branch currently contains KMS, IAM/geo foundations and a first CRM-R1A implementation attempt. **The user shifted focus to DOMAIN DESIGN.** Do not assume all submitted CRM-R1A Java/SQL changes are qualified or approved for production. Keep CRUD REST and partition implementation out of this design decision. Resolve CI failures separately before a release candidate.
