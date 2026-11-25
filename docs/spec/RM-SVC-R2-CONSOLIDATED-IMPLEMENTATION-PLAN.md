# RM-SVC-R2 — Consolidated Implementation Baseline

**Status:** APPROVED FOR PHASED IMPLEMENTATION / not production approved.  
**Repo:** alimbetov/RM, **single branch** base_dev. **Baseline for planning:** 1ea8c041829a84b99735b0acb229950b8db832ba.  
**Supersedes conflicts between:** RM-SVC-R2, R2A, R2B, TENANT-ISO-R1 audit. Those specifications remain normative for their detailed domain contracts.

## Architecture decision

**Hybrid domain persistence, NOT all-JPA and NOT all-JDBC.**

- **Commands for mutable CRM aggregates**: explicit JPA Entity / repository where it strengthens invariants, versions and lifecycle. Existing proven JDBC commands can be migrated incrementally only after equivalence testing; do **not** switch CRM PII encryption or move plaintext into Entity.
- **Queries for search, filters, keyset pagination and aggregate summaries**: keep prepared JDBC / native SQL DTO projections, with enforced tenant + optional owner scope and no N+1. JPA entities are not outward-facing DTOs.
- **Highly concurrent or byte-oriented operations**: keep proven transactional JDBC for SALES state transitions, INVENTORY balance/movements, FILES storage/promotion/recovery; Entity is not a mandatory wrapper for each SQL table.
- **Existing JPA ORGANIZATION Location** stays JPA.

## Mandatory tenant boundary

Always authorize `VerifiedActor` + selected `iam_workspace.id` + domain permission before work. Every root query `tenant_id=:tenant` and every tenant-owned join `child.tenant_id=parent.tenant_id`. Composite persistence identity `(tenant_id,id)`; UUID alone is never a tenant authorization token.

No second customer copy, catalog copy, file metadata registry, or globally shared customer entity. For FILES owner access remains CRM-owned; only qualified COMPANY owner is supported. CRM Contact tenant-wide reading under CRM_PII_READ is documented; per-assignee portfolio scope must be separately agreed.

## Functional domain commitments

| Area | Required functions / filters | Persistence |
|---|---|---|
| CRM Customer | type/status/region/company name/created interval, categoryCode, contract expiry/status; update/archive/restore | **JPA Customer metadata aggregate** + JDBC search DTO; encrypted PERSON name remains crm_pii |
| CRM Contact | customer/status/primary; update/primary/archive | JPA Contact metadata + encrypted contact_secret; JDBC search DTO |
| ORGANIZATION | location type/status/name/KATO, lifecycle | existing JPA + bounded pageable |
| FILES | owner/type/status/purpose/date, metadata/list, attach/detach/delete | keep JDBC/StoragePort + typed owner ACL |
| SALES | status/period/customer only after qualified CRM mapping | keep JDBC projections + existing state machines |
| INVENTORY | sku/location/movement type/available | keep JDBC projections/movements, composite FK and strict warehouse scope |
| CATALOG | category/status/SKU/seller/price and separate modelCode | JPA CatalogItem aggregate after functional service exists; JDBC catalog search |

**Categories:** tenant-configurable one primary customer category, distinct from PERSON/COMPANY; no hardcoded role constants. **Contracts:** customer can have multiple; contract expiry date is LocalDate and indicator computed as-of tenant date, by nearest active finite contract; warn 30 days / critical 7 by default. **Catalog:** modelCode not SKU, non-unique across SKUs, nullable for migrated existing items.

## Implementation slices & qualification

- **PERSIST-R1.0:** introduce JPA CRM Customer **metadata-only**, composite key entity and repository scoped by tenant; regression test proves two-tenant visibility and no PII decryption/plaintext leakage. Do not rewrite existing trusted JDBC production commands in this slice.
- **PERSIST-R1.1:** Contact metadata entity + scoped repository, with PII secret outside JPA projection; add tests. Decide whether existing entity lifecycle is maintained by JPA or JDBC per operation; **never mix persistence contexts for the same write transaction without flush/clear plan**.
- **TENANT-ISO-R1:** compulsory PostgreSQL two-tenant negative matrix for all existing domains and every new join/filter, including count & cursors. No full qualification claim before tests are actually run on matching commit.
- **SEARCH-R0/R1:** typed filter validation + bounded OFFSET compatibility, new keyset cursor where necessary, HMAC binds tenant/scope/filter/asOfDate, authorization on every page.
- **CRM-R2B.1/R2B.2:** additive category and customer contract tables, services, lifecycle/expiry, non-N+1 search after design accepted.
- **CATALOG-R2B.4:** modelCode column and typed query/command facade; permission gate.
- **R2.0A FILES quarantine CAS** and later FILES media expansion, SALES/INVENTORY agreement — do not weaken existing RustFS/ClamAV certification.

## PERSIST-R1.0 acceptance

1. `@IdClass` + `@Id tenantId/id` or `@EmbeddedId` correctly map `crm.customer` existing composite primary key without schema change.
2. `findByTenantIdAndId` cannot return foreign tenant even when caller knows UUID; two-tenant qualified PostgreSQL/H2 test.
3. `CrmCustomerEntity` does not map PII secret columns/tables or expose PERSON name; encrypted PII remains unchanged.
4. `rowVersion` can be represented as `@Version` for future update lifecycle, but no JPA writes until CAS/persistence consistency design is tested.
5. Existing CRM query JDBC and create/read behavior unchanged, no controllers.
6. Applicable CI H2/PostgreSQL/populated PII/RustFS E2E green **on same HEAD**; no claiming GREEN for unrun HEAD.

## PERSIST-R1.1 — Implementation and qualification addendum (2026-11-22)

**Implemented, pending exact-head CI qualification:** `CrmContactKey` (`tenantId,id`), `CrmContactEntity` (`@IdClass`, `@Version`) and package-private `CrmContactRepository` with `findByTenantIdAndId` and `findByTenantIdAndCustomerId`. Entity deliberately maps only `crm.contact` metadata. `crm_pii.contact_secret` is neither mapped nor read through JPA.

**No operational rewrite:** `CrmCustomerService.addContact/getContact`, `CrmSearchService.contacts`, `AuthenticatedCrmQueries`, encryption and JDBC SQL remain unchanged. This is a persistence foundation; it does not create contact Update/Archive/Primary operations.

**Mandatory tenant-isolation gate for the new JPA path:**
1. Seed two distinct organization workspace tenants, each with a customer and an encrypted contact.
2. Read own contact and customer association with `(tenantId, contactId)` and `(tenantId, customerId)`; assert the other tenant's known contact/customer IDs produce empty results even when UUIDs are known.
3. Assert `findById(CrmContactKey(otherTenant, knownId))` is empty. Composite-key access **must not** bypass selected tenant.
4. Assert metadata `CrmContactEntity` has no name/email/phone fields or encrypted envelope mappings; original PII must remain encrypted at rest and accessible only through authorized CRM query services.
5. Execute PostgreSQL + H2 + populated PII regression; independent real RustFS/ClamAV E2E confirms no unrelated breakage. Gate success **only for verified exact SHA**, not inherited from prior commits.

**Security caveat:** Spring Data `JpaRepository` exposes inherited `findAll`/`findById` and is **not** by itself an authorization boundary. Keep repository package-private/internal; never inject it into transport or other domains. Future tenant-scoped domain facades must authorize `VerifiedActor` and tenant before repository access. When hardening the repository API, prefer narrow read interfaces without inherited unscoped methods, subject to Spring Data compatibility tests.

**Next after PERSIST-R1.1 GREEN:** `TENANT-ISO-R1` expands mandatory tests to *both JPA repositories*, joins, filter/count/cursor and revocation. Only then implement CRM category and customer contract migrations.

## Sequence guardrails

No new microservice, controller, branch, unreviewed merge, persistent read-model copy, generic CRUD framework, global SQL fragment filter builder, arbitrary user sort expression, unsafe PII indexing, production admission or app-level file byte encryption.
