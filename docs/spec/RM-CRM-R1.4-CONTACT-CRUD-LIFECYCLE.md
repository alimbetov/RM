# RM CRM-R1.4 — Contact CRUD & Customer Lifecycle Completion

**Branch:** `base_dev`. **Feature freeze:** service APIs only, no controllers, no merge to main.

## Contact lifecycle

- `AuthenticatedCrmCommands.updateContact`: full name/email/phone replacement with stable tenant/record/field AAD, persisted only as ciphertext in `crm_pii.contact_secret`. Nullable email or phone clears the field; name required.
- `archiveContact`: soft archive of contact metadata. No deletion of PII, consent, attachments or audit.
- `restoreContact`: restores only when parent customer is ACTIVE. `getContact` refuses archived contacts and contacts of archived customers.
- All mutations check a `VerifiedActor` and `CRM_WRITE`, use tenant-qualified IDs and expected `row_version` for optimistic updates. On conflict, no PII or event writes may commit.
- `restoreCustomer`: versioned soft restoration, does not alter contract status, consent or contact status; existing consent history is never retroactively modified.

## Audit

`crm.contact_change_event` is a new tenant-first immutable history table (Liquibase 0062), with action, actor, parent customer, contact, previous/new version and timestamp only. No plaintext PII, ciphertext copies or values are stored in the audit. The PostgreSQL immutability trigger is installed in 0063. A separate additive changeset 0064 enables RESTORED in the existing customer audit; 0065 enables CREATED for new contact audit events. Prior changesets remain byte-for-byte compatible with the previously qualified schema.

## Scope boundaries

No hard deletion; no generalized multi-contact primary contact switching or bulk contact import. Contact creation retains its established R1A behavior; the R1.4 audit captures CREATED, UPDATED, ARCHIVED and RESTORED for new operations (no fictitious CREATE events for legacy contacts). There are no controllers or DTO expansion for untrusted principals. `CrmContactRepository` remains package-private internal persistence and is not a security boundary by itself: all trusted entrypoints authorize tenant and permission first.

## Mandatory qualification

- Active contact changes PII and returns exact values, while encrypted envelope excludes plaintext; null email/phone clearing.
- Stale version, archived contact, archived parent, wrong tenant and unauthorized actor fail closed.
- Customer archive/restore audit and contact read restrictions.
- Exactly one winner for concurrent updates with the same revision; one history event persists.
- Tenant A cannot read tenant B contact histories or alter tenant B contacts.
- PostgreSQL trigger rejects audit UPDATE/DELETE; H2 smoke does not claim to enforce PostgreSQL-only triggers.
- Liquibase upgrade from populated pre-R1.2 database and H2/PostgreSQL/PII/RustFS CI regressions on the same final commit.

**Release status:** HOLD until the exact final commit has GREEN evidence for every mandatory gate; production admission remains separate.
