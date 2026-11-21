# RM IAM-PII-R2.5D — Populated PostgreSQL migration and security qualification

**Status:** service tests / isolated **synthetic** populated PostgreSQL qualification. **Not** a claim that an actual production backup was mounted, decrypted, migrated, restored, or certified. **Branch:** `base_dev`. **No controllers.**

**Normative parent:** [RM-PLATFORM-STANDARD-R2 §25](../architecture/RM-PLATFORM-STANDARD-R2.md). [R2.5 spec](../spec/RM-IAM-PII-R2.5-GLOBAL-PROFILE-EMAIL-VERIFICATION.md). **Manual seal SQL:** [RM-IAM-PII-R2.5D-POSTGRES-SEAL.sql](RM-IAM-PII-R2.5D-POSTGRES-SEAL.sql).

## 1. What is qualified and what is still blocked

| Workstream | Evidence | Status |
|---|---|---|
| Encrypted IAM profile + phone blind index | `GlobalUserProfileCrypto`, self-only service, H2/PG tests | Implemented; no production cryptographic approval |
| First registration and email change | R2.5A/B, encrypted email, proof purpose, historical order isolation | Internal qualified |
| Legacy safety fix | `LegacyIamPiiMigrationService`: reject mismatched encrypted vs plaintext profile; permit nameless email-only; transactional rollback | Service implemented |
| Populated preflight | `IamPiiMigrationQualificationService.assertBackfillAdmissible()` checks ownership collision, normalized email, name/phone, existing ciphertext and phone/email indexes, with aggregate-only response | Implemented, synthetic |
| Post-backfill seal admission | `assertReadyForPlaintextSeal()` requires **zero** residual plaintext and decryptable, internally consistent stored envelopes | Implemented, synthetic |
| Bounded migration | `migrateBatch(1..500)` and `migrateUser`, **disabled by default**; no automatic deployment-time backfill | Implemented, offline-only |
| Dedicated 200-row PostgreSQL | `postgres-pii-populated` is a **fresh isolated PG17 job**; 150 named+phone+email, 30 email-only, 20 name-only, multi-batch migration and negative scenarios | CI gate, not a real customer DB |
| DB-level post-cutover protection | Manual, operator-run `RM-IAM-PII-R2.5D-POSTGRES-SEAL.sql` adds a validated `CHECK` prohibiting future plaintext | Prepared, **not applied to any real DB** |
| Populated backup rehearsal + restore | Requires externally authorized, minimized/sanitized DB snapshot and controlled environment | **HOLD** |
| Least-privilege DB / secrets, historical dumps/logs, independent privacy/security review | No access to production controls or independent approval | **HOLD** |

## 2. Operator runbook — no controllers

1. **Change approval:** confirm data owner, allowed environment, backup retention and access controls. Use **synthetic** fixture in CI. For real customer records, use approved isolated restore; do not attach raw backups to chat, PR, build logs or CI.
2. **Freeze all writers:** stop CRM/SALES checkout, IAM account creation/update, OIDC onboarding, background jobs and any direct SQL/legacy consumers writing `iam_user_account`. Disabling controllers alone is insufficient. Record time and responsible operator.
3. **Back up and verify restore** of a consistent PostgreSQL snapshot, plus separately backed-up global IAM encryption/HMAC keys and metadata. Without verified restore: STOP.
4. Run `IamPiiMigrationQualificationService.inventory()` and `assertBackfillAdmissible()` under protected operator identity. Only aggregate counts may go into the acceptance report. Any normalized-email collision, mismatch of legacy name/phone vs encrypted profile, wrong AAD, damaged email envelope or missing key: STOP and investigate. **Never** print raw PII or tokens.
5. Enable `RM_IAM_PII_MIGRATION_ENABLED=true` only in the isolated maintenance execution. Run `migrateBatch(100)` until zero. Every batch is transactional: an exception rolls back its whole batch. On restart, previously scrubbed records are skipped; run the preflight again to ensure no conflicting late writer.
6. Run `assertReadyForPlaintextSeal()`. Acceptance requires exactly **zero** non-null values across `display_name`, `phone_e164`, `email_normalized`, successful decrypt of every encrypted profile/email identity and keyed HMAC matching their actual values. Preserve only counts and the validated deployment SHA.
7. After independent approval, run [the manual SQL seal](RM-IAM-PII-R2.5D-POSTGRES-SEAL.sql) in a maintenance window. It checks zero plaintext **inside an exclusive account-table lock**, validates the constraint and revokes public schema/table access. No automatic Liquibase changeset applies it. An old writer attempting plaintext is then rejected by PostgreSQL.
8. Turn the migration flag off, restart only qualified IAM services, verify login identity resolution, role revocation, email verification, email change, guest checkout and historical order FK. Repeat inventory, prove residual plaintext remains zero, and monitor error codes without logging PII.
9. Conduct restore rehearsal of a post-seal backup and ensure the restored service uses the exact compatible key material. Hold production until issuer/JWKS/BFF/email provider, DB least privilege, credential/key lifecycle and independent security acceptance are complete.

**No physical `DROP COLUMN` in R2.5D.** Existing legacy SQL paths still refer to old column names. The validated `CHECK` prevents new plaintext and ensures rows contain no legacy PII, but columns remain empty. A subsequent, independently reviewed release must first remove those code paths and then drop the columns; historical dumps/backups require a separate lifecycle/retention decision.

## 3. Invariants and negative tests

- **INV-D01:** new email-only accounts can be migrated without inventing a name; their `emailVerified` remains `false`.
- **INV-D02:** encrypted profile already exists with identical plaintext → only scrub old columns; different plaintext → fail and retain old values, **do not silently prefer one source**.
- **INV-D03:** normalized email claimed by two global identities (including case-only variants) → preflight FAIL; no arbitrary assignment by query ordering.
- **INV-D04:** corrupted ciphertext, changed AAD/key, inconsistent phone HMAC or damaged email envelope → fail closed before any seal, including when stored encrypted tables are empty (cryptographic canary).
- **INV-D05:** migration of a batch with any conflict is atomic; no partial plaintext deletion.
- **INV-D06:** 200 synthetic populated users migrate across several bounded batches; 0 legacy plaintext, protected-name/phone integrity, no implicit verification, no duplicates, exactly once `PROFILE_MIGRATED` evidence.
- **INV-D07:** before manual seal, a legacy direct SQL writer could still write plaintext; after sealed CHECK, PostgreSQL rejects it. Test the DDL **inside a rolled-back PG transaction**, never persist seal in shared CI.
- **INV-D08:** existing historical email identities and order references remain immutable; neither shared email nor global profile migration transfers a buyer’s prior orders. A pending `CHANGE_EMAIL` identity is never accepted as verified legacy source data.
- **INV-D09:** new and existing global IAM PII is not encrypted with tenant keys, and no tenant OWNER/PLATFORM_ADMIN gains another person's plaintext profile through the self-only service.

## 4. Completion definition and limitations

**Internal gate GREEN** only if H2, ordinary PostgreSQL and isolated `postgres-pii-populated` all succeed at the *same exact commit* (not merely branch name), negative tests are green, docs are consistent and no controller has been introduced.

**Actual populated production migration gate remains HOLD** unless an operator supplies independent, authenticated evidence for real approved backup/restore, complete writer isolation, no PII-leaking logs, key escrow/recovery, DB privileges, manual seal result, post-seal restore and independent security approval. Never represent synthetic fixtures as migrated customer data.

**Main stays unchanged** until release owner approves and merges PR through standard CI/review gates; no force/auto merge.
