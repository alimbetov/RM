# RM CRM-R1.3 — Customer and Contract CRUD Completion

**Branch:** `base_dev` only. **Feature Freeze:** no controllers, no merge to main.  
**Scope:** tenant-scoped business services, immutable history, PII protection, PostgreSQL qualification.

## Delivered application contracts

1. `AuthenticatedCrmCommands.updateCompany`: modify company name and KATO; optimistic `row_version` compare-and-swap.
2. `AuthenticatedCrmCommands.updatePerson`: tenant-key encryption of revised person name using stable record AAD; store only encrypted envelope in `crm_pii.customer_secret`.
3. `AuthenticatedCrmCommands.archiveCustomer`: soft archive; does not delete contacts, consent or contract history; rejects customers with ACTIVE contracts. Customer row locking serializes with concurrent new contract creation.
4. `CrmCategoryService.assignPrimary`: audited changes, including removal of category.
5. `CrmContractService.create/changeDates/changeStatus/renew`: immutable revision per material state/date change; renew only ACTIVE fixed-term contracts with strictly later end date. Optimistic `@Version` and tenant-local composite FK remain authoritative.
6. `CrmHistoryService.customerHistory/contractHistory`: tenant and `CRM_READ` authorization; bounded 1..100 historical records, never includes natural-person plaintext.

## Persistent evidence

- `crm.customer_change_event`: `(tenant_id,id)` PK; tenant+customer FK; actor FK, action, previous/new row version, occurrence. New R1.3 events only; no invented backfill for legacy records.
- `crm.contract_revision`: `(tenant_id,id)` PK, unique `(tenant_id,contract_id,revision_no)`; exact start/end/status per revision, actor, occurrence and action. The initial R1.2 contracts have no synthetic creation revision; first R1.3 modification starts from their current optimistic row version and must be interpreted accordingly.
- PostgreSQL append-only mutation triggers on both tables; no PII, contract document body, contact details or free-text snapshots.
- All revisions are inserted within the same transaction as the mutation. Transaction rollback must roll back both.
- `crm.customer.primary_category_id` retains one primary category only; no general many-to-many tags.

## Qualification gates

| ID | Required evidence |
|---|---|
| R13-01 | Edit COMPANY and PERSON with tenant-specific authorization; encrypted PERSON name stays out of `crm.customer` |
| R13-02 | Invalid KATO, stale version, archived customer and cross-tenant edit rejected |
| R13-03 | Archive leaves contacts/history/consent intact and blocks ACTIVE contracts |
| R13-04 | Category reassignment audited once, no-op does not duplicate history |
| R13-05 | DRAFT→ACTIVE→CLOSED/CANCELLED transition and revisions atomically persisted |
| R13-06 | Renewal requires strictly later expiration and keeps previous exact dates |
| R13-07 | Tenant A cannot list/read tenant B historical entries even with known UUID |
| R13-08 | Direct SQL UPDATE/DELETE of history rejected by PostgreSQL triggers |
| R13-09 | Two concurrent updates with same row version allow only one winner |
| R13-10 | Populated PostgreSQL Liquibase upgrade preserves existing customers/contacts |
| R13-11 | H2 smoke, PostgreSQL integration, IAM populated, RustFS E2E remain GREEN |

## Non-goals / remaining product decisions

No HTTP adapters, REST controllers, deletion of customers, editing arbitrary contract identifiers, financial amount/fees, attachment body, customer-contact CRUD completion, outbox notifications or automatic renewal jobs. Contact CRUD and full field-level history would require a separately approved R1.4 scope. The present history contains action and version metadata, not cryptographically signed before/after content. Production suitability still requires the exact CI commit to pass the above gates, plus appropriate operational permissions and retention policy.
