# IAM–CRM-R2.2F — Trusted OIDC Revocation Worker PostgreSQL Evidence

**Date:** 2026-11-22. **Branch:** `base_dev`. **Controllers/UI:** STOP. **Production:** HOLD.

## Exact-SHA scope and evidence

**Green qualification SHA:** `ce5191a3269a4459d0f17a752c0fde6ddc789b8f`
- Workflow: https://github.com/alimbetov/RM/actions/runs/45053738554 — SUCCESS.
- Job ID: `137304304034`.
- Surefire artifact: `iam-r22f-oidc-revocation-45053738554`, ID `14767135574`.
- **47 executed, 0 failures, 0 errors, 0 skipped**:
  - CRM Buyer / IAM deletion: 25
  - OIDC revocation worker: 5
  - IAM initial registration: 2
  - IAM email change / history: 6 + 9

Earlier GREEN for `e97b4db2c37875bed8ca7d91844cf736abf7123e`: run https://github.com/alimbetov/RM/actions/runs/45053600766, 47/47 PASS; superseded by the SHA above.

**Latest additional test change:** `793ed38fb65653f4b3a208c653f96efddc83428b` adds an explicit unscoped scheduled-worker claim test (expected six worker cases) and strict required-name assertion. **Run:** https://github.com/alimbetov/RM/actions/runs/45054043409. Do not equate the previous SHA's GREEN with this latest SHA until CI succeeds.

## Implemented components

- `OidcSessionRevocationGateway`: trusted provider boundary. A normal method return means explicit provider success; all other outcomes throw.
- `KeycloakOidcSessionRevocationGateway`: disabled-by-default HTTPS Admin REST POST /admin/realms/{realm}/users/{user-id}/logout. Requires explicitly configured realm, base URL and restricted admin bearer. Only HTTP 204 permits ACK; non-UUID Keycloak-subject mappings fail closed.
- `OidcRevocationOutboxWorker`: transactional PostgreSQL SKIP LOCKED claim, 60-second lease, monotonic attempt fencing, network I/O outside transaction, ACK only after provider response, retry metadata with exponential backoff capped at one hour. Stale workers cannot overwrite newer attempt/ACK state.
- `OidcRevocationOutboxScheduler`: bounded opt-in scheduled executor; defaults disabled; separate security configuration required.
- Liquibase `0120-iam-oidc-revocation-lease-fencing`: new attempt, lease, due-time and failure-code columns, backfill of due time and queue index.
- `GlobalAccountDeletionService`: deletion produces encrypted provider-subject outbox intent in the same transaction as the immutable tombstone and global-profile scrub.
- `EmailIdentityCrypto`: separate AEAD associated-data domains for email and OIDC subjects. The cross-domain decryption attempt is tested to fail.

## Operational guarantees and non-guarantees

- **At-least-once** provider delivery. Remote logout can be repeated if crash follows Keycloak success but precedes local ACK.
- No externally confirmed Keycloak E2E run was made in this development gate; test gateway simulates success/failure. No actual third-party session was revoked by qualification.
- No cloud scheduler or adapter is turned on by default. A production-capable running system requires deployment configuration, restricted admin rights, tested token refresh and live provider validation.
- Exact-SHA GREEN is not complete OpenID Connect security admission: existing JWTs may need fresh revocation checks by each resource server, session/bearer expiry needs independent validation.
- Provider subject is encrypted, not plaintext; retry error code cannot carry token or provider response.
- **Not a legal data-deletion approval.** Backup, WAL, PITR, audit and CRM/SALES retention periods are pending documented approval.

## Required approvals

1. IAM Security: approved Keycloak mapping and live API 204/403/timeout/404 behavior, token lifecycle, session invalidation and authorization.
2. Security Operations/SRE: retry backlog thresholds, lease-expiry alarms, DR restore reconciliation, secret rotation and monitoring.
3. Legal/Compliance: approved retention periods and basis for pseudonymous CRM/SALES references, encrypted outbox subject, immutable audit and backup expiration.
4. RM architecture/release: complete exact-SHA platform qualification and populated PostgreSQL migration before production.

No approval is implied by code commits or simulated adapter success.

## Reference

Technical policy and operator conditions: `docs/architecture/IAM-CRM-R22F-OIDC-REVOCATION-OUTBOX.md`.

**Decision:** worker design implementation and 47-test PostgreSQL qualification GREEN at the explicitly stated SHA. Additional unscoped test remains separately gated until its own exact-SHA evidence. **Production HOLD.**


## Final extended scheduler admission — exact SHA

- **Qualified source SHA:** `793ed38fb65653f4b3a208c653f96efddc83428b`
- **GitHub Actions:** https://github.com/alimbetov/RM/actions/runs/45054043409 — **SUCCESS**.
- **Job:** `137305226222`.
- **Artifact:** `iam-r22f-oidc-revocation-45054043409`, ID `14766876706`.
- **Surefire:** **48 executed, 0 failures, 0 errors, 0 skipped**.
  - 25 CRM Buyer / IAM deletion
  - 6 OIDC revocation worker including unscoped scheduled claim
  - 2 initial email registration
  - 6 email change
  - 9 email change / order-history scenarios
- The final test additionally exercised the no-event-ID `processOne(workerId)` scheduler query, proving the nullable PostgreSQL UUID admission path.
- The earlier 47-test GREEN is retained as historical evidence, not the current qualification SHA.

**Scoped technical gate: GREEN for this exact SHA only.** Real Keycloak provider E2E, provider secret lifecycle, operator monitoring and signed Security/Legal retention approval remain **HOLD**.
