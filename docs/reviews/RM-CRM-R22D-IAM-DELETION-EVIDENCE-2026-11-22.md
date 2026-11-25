# CRM-R2.2D — Global IAM Deletion, Retention and Buyer-Link Qualification

**Date:** 2026-11-22. **Repository/branch:** `alimbetov/RM` / `base_dev`.
**Scope:** service-layer logical deletion/de-identification with CRM/SALES referential continuity. Controllers/UI STOP. No merge into main.

## Exact-SHA PostgreSQL evidence

**Service qualification SHA:** `a92a22aa758d1b82b739d94a0d617ba54bf754a8`
- GitHub Actions: https://github.com/alimbetov/RM/actions/runs/45051660329 — SUCCESS.
- Job ID: `137297896853`
- Surefire artifact: `crm-r22-buyer-postgresql-45051660329`, artifact ID `14766175562`.
- Tests: CrmR1aQualificationTest **37**, CrmBuyerLinkQualificationTest **20**, CrmInteractionQualificationTest **19**; **76 total, 0 failures, 0 errors, 0 skipped**.

**Populated-upgrade SHA:** `51a1bdabb7eaa3d2df0981ff3dd5fa0cdad706df`
- GitHub Actions: https://github.com/alimbetov/RM/actions/runs/45051689372 — SUCCESS.
- Job ID: `137297983890`
- Artifact: `crm-r22c-populated-upgrade-45051689372`, ID `14766126588`.
- Previous qualified schema `a7d4ac44482f22cba6ba2f52451ef6d4ff773e75`: 12 tests PASS, **16 existing tenant links**, **27 append-only events** seeded.
- Current schema Liquibase upgrade on populated DB: 20 buyer/IAM tests PASS; old link keys/states/revisions and event keys/action codes retained, using before/after snapshots.
- Fresh-schema service tests and populated-upgrade tests ran against PostgreSQL 17.

## Implementation

- `src/main/java/kz/rm/iam/GlobalAccountDeletionService.java`: platform-admin authorization, target row lock, idempotent request key, rejection of target with active organizational OWNER memberships or global admin role, irreversible logical tombstone `DELETED`, secret scrubbing in one database transaction.
- `src/main/resources/db/changelog/db.changelog-master.sql` changesets `0108/0109`: durable, immutable deletion event keyed by target user, with actor/request-key provenance and no duplicated PII.
- `src/test/java/kz/rm/crm/CrmBuyerLinkQualificationTest.java`: confirms order/customer references remain, account profile is removed, active email identity becomes HISTORICAL with no live lookup, old authenticated buyer cannot continue CRM link mutation, unauthorized deletes rejected, deletion event immutable, and both deterministic `delete × confirm` commit orders qualified.
- `.github/workflows/crm-r22-buyer-link-pg.yml`: explicit test-name evidence gate for 20 Buyer/IAM tests, plus CRM regressions.

## Deletion semantics

1. Only current ACTIVE platform administrator may delete another user; cannot delete self or an existing active organization OWNER / global PLATFORM_ADMIN without separate role/ownership transition.
2. Target IAM row locked before modification. `DELETED` blocks subsequent service admissions, including buyer link confirmation and seller history projection.
3. Account identifiers `external_subject`, `email_normalized`, `phone_e164` and `display_name` are set NULL. Global `iam_pii.user_profile_secret` row removed.
4. Email identity IDs remain to preserve existing SALES order FKs. Identity state becomes HISTORICAL, active email lookup is cleared, email ciphertext and prior deterministic lookup bytes are replaced with non-identifying random bytes.
5. Pending email delivery is marked FAILED; unconsumed verification challenges are invalidated and token material rotated.
6. Existing CRM customer, buyer link, link events, adjudication decisions and SALES order references remain; the deleted account cannot see orders through the CRM Buyer service.
7. Same `(admin,target,requestKey)` request retries do not generate additional deletion events; other attempts for an already deleted account fail closed.

## Non-goals and residual qualification

**Scoped R2.2D PostgreSQL service and migration evidence: GREEN on the two exact SHAs above.**

**Production HOLD:** this does NOT establish full regulatory erasure. In particular:
- External OIDC/Keycloak account disable/delete and token-revocation propagation are not qualified here.
- Database WAL, point-in-time backups, audit logs and external exports require a separately approved retention/erasure schedule.
- Some operational reference IDs remain pseudonymous and may be linkable to retained CRM and SALES records; lawful basis and retention durations must be approved.
- Concurrent IAM email-provisioning/verification × deletion lock-order stress and recovery under process crashes are not yet exhaustively qualified.
- Requester-driven deletion, notice, legal holds and review/approval workflows need dedicated policy and implementation.
- No full system-wide RM QA gate, large-volume deletion migration or production OIDC E2E qualification was claimed.

**Status:** initial IAM-to-CRM deletion integration implemented and qualified; overall production admission HOLD.
