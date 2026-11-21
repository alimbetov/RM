# CRM-PERSIST-R1.2 — Customer categories and contracts

**Status:** IMPLEMENTED IN `base_dev`; PostgreSQL test execution and release qualification pending.
**Scope:** JPA, application services, Liquibase and tenant-isolation qualification. No controllers.

## Model

- `crm.customer_category`: composite `(tenant_id,id)` PK, tenant-local unique code, name, ACTIVE/ARCHIVED, timestamps, optimistic row version.
- `crm.customer.primary_category_id`: nullable primary classification; composite FK `(tenant_id,primary_category_id)` to category. This stage deliberately supports **one primary category per customer**; multi-tag categories would need a separate link table and approval.
- `crm.customer_contract`: composite `(tenant_id,id)` PK; composite FK to customer; tenant-local unique `contract_code`; inclusive `starts_on` and nullable `ends_on`; DRAFT/ACTIVE/CLOSED/CANCELLED; timestamps and row version.
- Category name and `contract_code` are non-PII metadata, not personal free text. `contract_code` is a restricted internal identifier, **not** a field for names, national IDs or contact information. Contract originals and sensitive attachments belong in the separately authorized encrypted file service.

## Application contracts

- `CrmCategoryService`: create, rename, archive, assign/clear customer's primary category, get, list.
- `CrmContractService`: create draft, amend dates, legal transitions DRAFT→ACTIVE/CANCELLED, ACTIVE→CLOSED/CANCELLED, get/list with customer filter.
- Both require `VerifiedActor`, tenant and IAM's `CRM_READ`/`CRM_WRITE` permission at every entry point.
- All repository reads use explicit tenant filters. Internally inherited Spring Data JPA methods must never be surfaced to untrusted caller layers.
- Unique constraints and composite FKs enforce isolation even in direct SQL or a concurrent race.
- Updates have `@Version` and explicit expected version; stale updates fail. No hard delete.
- Date interval: end date is **inclusive**; `null` end date means open-ended. Dates are business `LocalDate`, not timestamps.
- A pure `ContractExpiryPolicy.evaluate(status,startsOn,endsOn,asOf,warningDays)` computes `DRAFT, NOT_STARTED, OPEN_ENDED, VALID, EXPIRING_SOON, EXPIRED, CLOSED, CANCELLED`. Expiry is never stored as a mutable database status. `warningDays` defaults to 30 on service mutations, configurable per query (0..365).
- Sorting: categories by code/id, contracts by creation time desc/id. Page size 1..100.

## Qualification — TENANT-ISO-R1.2

| Case | Evidence to establish |
|---|---|
| ISO-01 | Tenant A and B may independently create the same category code and contract code |
| ISO-02 | Duplicate code within the same tenant rejected by DB UNIQUE |
| ISO-03 | Cross-tenant category assignment rejected by service and composite FK |
| ISO-04 | Cross-tenant contract customer reference rejected by service and composite FK |
| ISO-05 | Cross-tenant get, update, list and pagination fail closed |
| ISO-06 | Repository ID lookups cannot reveal another tenant's data |
| ISO-07 | Filtered counts and multi-page results remain tenant scoped |
| ISO-08 | Archived category cannot be newly assigned but historical reference remains |
| ISO-09 | Invalid dates, stale versions and illegal state transitions rejected |
| ISO-10 | As-of expiry calculation covers end-date boundary, future start, nullable end |
| ISO-11 | Rollback and migration validate against populated PostgreSQL schema |
| ISO-12 | Negative IAM permissions, concurrency and SQL constraint checks pass on real PostgreSQL |

**Gate:** No GREEN or production readiness claim until the new tests execute against PostgreSQL, migration/rollback is confirmed, and the full regression suite passes.

## Out of scope

REST/controller adapters, multiple classifications per customer, contract amendments/audit evidence stream, renewal scheduling, reminders, personal contract document contents, billing and file attachments. Follow-on audit should decide whether contract status changes require an append-only event log before production use.
