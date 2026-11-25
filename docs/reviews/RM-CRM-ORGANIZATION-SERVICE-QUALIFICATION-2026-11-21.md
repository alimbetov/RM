# RM CRM/ORGANIZATION — Service-First Qualification R1

**Branch:** `base_dev`. **Scope:** functional service-only qualification. No HTTP controllers, `SecurityConfig`, changes to `main`, production admissions or destructive migrations.

## Scope and findings

| Finding | Severity | Implemented mitigation / remaining action |
|---|---|---|
| Raw CRM methods accepted a caller-provided UUID actor on public service entrypoints | HIGH | Added trusted `AuthenticatedCrmCommands` / `AuthenticatedCrmQueries` facades requiring `VerifiedActor`. Raw UUID methods are package-private, with public CRM response records preserved. |
| Organization location mutation and read beans exposed UUID-based entrypoints to arbitrary Java modules | HIGH | Added `AuthenticatedOrganizationLocations`; raw query and create beans package-private; updated cross-module GEO and architecture tests. |
| CRM PII reads and organization queries did not use a transaction-duration IAM row-lock admission fence | HIGH | Use `TenantAuthorization.requireForCommand` in transactions for CRM read/PII and ORG reads, matching existing mutation fence. `readOnly` transaction mode disabled because PostgreSQL locking reads require writable transactions. |
| CRM create/contact/consent unit of work failure | MEDIUM | Added injected pre-commit exception test validating rollback of customer, encrypted contact, consent evidence and retry. |
| Unauthorized contact/person access, foreign tenant UUID and malformed input | MEDIUM | Added facade negative tests for actor null, cross-tenant reads and writes, no leaked plaintext in private rows. Existing encryption tests retained. |
| Real SQL lock race against tenant permission revoke | HIGH | Added PostgreSQL two-worker CRM PII read vs `CRM_PII_READ` revoke and organization create vs `ORGANIZATION_LOCATION_WRITE` revoke. Writer/reviewer ordering is synchronized with latches. |
| Consent evidence has append-only events but no client request-id idempotency contract | OPEN | Do not claim exactly-once consent append on ambiguous retry. Define consent business key or request-ID before introducing HTTP/queue retries. |
| Read-side list/filter support is strongest for organization locations; CRM does not yet have a paginated customer/contact search contract | OPEN | Define privacy-minimized CRM search DTO/filter/pagination before controller eligibility. |
| Existing tests use synthetic data and mocked OIDC decoders | LIMITATION | Does not qualify live issuer, key provider or production backup. |

## Required evidence

- `CrmR1aQualificationTest`: encrypted round trip, foreign tenant denial, consent opt-in/revocation, suspended access, viewer/member PII denial, synthetic rollback and recovered write, trusted actor facade, invalid no-plaintext contact, PostgreSQL PII read-vs-permission-revoke.
- `OrganizationLocationQueryQualificationTest`: paginated tenant-filtered reads, trusted facade, rollback of multiple location writes, foreign tenant denial, PostgreSQL create-vs-permission-revoke.
- `KatoTerritoryQualificationTest` uses only the trusted organization facade from GEO package.
- `DomainCommandBoundaryQualificationTest` rejects re-exposing raw UUID services or exposing a public constructor for `VerifiedActor`.

**Acceptance:** all three CI jobs GREEN on the precise final HEAD, tests not skipped in PostgreSQL, independent review before `main` merge. Any partial rollback, unauthorized PII, unauthorized tenant write, stale permit, or competing revoke ordering must block integration.

**Known deliberate limit:** this qualifies Spring service transactions, not process crashes, cloud identity integration, PostgreSQL RLS, production KMS, audit of historical raw backups, or SMTP/email delivery. Proceed to Catalog/Marketing/File only after this gate is exact-head GREEN.

## CRM Search + Consent Idempotency follow-up (R1.1)

**Implemented in `base_dev`, acceptance pending exact-head CI.**

- `AuthenticatedCrmQueries.customers(VerifiedActor,tenant,CustomerSearch,page,size)`: only `CRM_READ`, tenant-only metadata, no decrypted PERSON name, searchable COMPANY label; allowlisted type/status/region filters, bounded 1..100 page size, stable createdAt/id order.
- `AuthenticatedCrmQueries.contacts(VerifiedActor,tenant,ContactSearch,page,size)`: mandatory `CRM_PII_READ`, encrypted contact fields decrypted after read admission; tenant/customer/status/primary filters, no dynamic SQL identifiers or cross-tenant lookup; no contact PII in customer list.
- `AuthenticatedCrmCommands.recordEmailConsentOnce(VerifiedActor,tenant,contact,granted,purpose,version,source,requestKey)`: request key syntax `[A-Za-z0-9_-]{8,100}`, global within tenant; identical replay yields immutable original event ID; conflicting payload rejected; authorization checked before replay; database UNIQUE `(tenant_id,request_key)` and 64 persistent PostgreSQL/H2 transaction lock buckets protect concurrent writers.
- Legacy `recordEmailConsent` without a request key remains internal package-only for migration/test compatibility; new trusted external-facing service contract requires key. Old unkeyed evidence is not retroactively deduplicated.
- Liquibase changeset `0052-crm-consent-idempotency` is additive. Existing records keep nullable request_key; do not rewrite past audit evidence.
- Functional tests: `customerAndContactSearchAreBoundedTenantScopedAndPrivacyMinimized`, `consentRequestKeyIsIdempotentAndRejectsConflictingPayload`, `postgresConcurrentConsentSubmissionCreatesExactlyOneEvent`, plus architecture boundary checks.

**Safety and limitations:** key protects atomic event creation and consistent replay, not proof of genuine end-user consent. Real marketing dispatch still requires fresh eligibility/revocation checks and outbox. Offset pagination can move while rows are inserted; a stable client cursor contract is a separate future enhancement. Synthetic PostgreSQL concurrency tests cannot substitute production throughput or privacy/security approval.
