# IAM–CRM-R2.2F — OIDC Revocation Outbox Worker Contract & Security Approval

**Status:** implementation under exact-SHA PostgreSQL qualification; provider-live E2E and Security/Legal approval **HOLD**.
**Branch:** `base_dev`. **No controllers/UI**, no automatic scheduler, no `main` merge.

## Ownership and trust boundary

`GlobalAccountDeletionService` persists the account tombstone and encrypted provider subject under a single PostgreSQL transaction. The global account is immediately `DELETED` in local IAM and all guarded application paths must deny it; the separate asynchronous worker is not used as a substitute for local access rejection.

`OidcRevocationOutboxWorker` processes `iam_deletion_oidc_revocation_outbox` with independently committed `claim → provider request → ACK/retry`. It does not emit any HTTP application endpoint or run automatically. A trusted scheduled invoker is needed and must not expose arbitrary worker operations to customers.

`KeycloakOidcSessionRevocationGateway` is **disabled by default** and requires configured HTTPS-only base URL, realm and a provider admin bearer token delivered through secret management. The external subject must already be the actual Keycloak UUID user ID; issuer-qualified or other OIDC subjects fail closed pending explicit trusted mapping. Remote operation: **POST /admin/realms/{realm}/users/{user-id}/logout**; only **204 No Content** is accepted as provider ACK. See official Keycloak Admin REST reference: https://www.keycloak.org/docs-api/latest/rest-api/.

The provider subject is stored as an encrypted envelope with separate AEAD associated data (`rm:iam:oidc-subject:v1`). Decryption and network delivery occur only in trusted backend worker memory. No subject, bearer or exception text is logged or copied into retry metadata. Failure code is an enumerated constant, not a provider response.

## State machine

| Current | Event | Next | Invariant |
|---|---|---|---|
| PENDING, due, unlocked or expired | PostgreSQL claim | PENDING + lease + attempt increment | `FOR UPDATE SKIP LOCKED`; no duplicate concurrent lease |
| PENDING, leased | Provider HTTP 204 | ACKNOWLEDGED | CAS on worker ID and attempt sequence; ACK timestamp only here |
| PENDING, leased | HTTP error, timeout or decryption failure | PENDING, delayed | Lease cleared; next attempt delayed with exponential backoff, cap 3600 s |
| PENDING, lease expired | New worker takes over | PENDING + newer attempt | Old worker's ACK/RETRY cannot overwrite newer attempt |
| ACKNOWLEDGED | Duplicate invocation | unchanged | Never reattempt on acknowledged record |

**No exactly-once network delivery is claimed.** If Keycloak applied logout but ACK was lost, the worker will retry. Provider logout must be safe to call again; any non-204 including 404 remains unacknowledged until a separately approved policy defines whether a missing user counts as completed revocation.

Backoff schedule: 60, 120, 240, 480, 960, 1920, capped at 3600 seconds. Worker lease is currently 60 seconds. HTTP connect timeout 5 s and request timeout 8 s. Recovery is by querying expired leases; no in-memory job queue or JVM-affinity dependency.

## Mandatory evidence and limitations

- PostgreSQL concurrency tests: two workers cannot simultaneously claim one event; lease takeover fences a late ACK; provider exception leaves retryable PENDING; successful simulated provider ACK is exactly one state transition; a disabled real gateway cannot fabricate ACK.
- Exact-SHA JUnit evidence must include CRM Buyer and email regression suites.
- No real Keycloak credentials or remote integration are present in CI; simulated gateway success validates worker behavior, **not provider correctness under live IAM integration**.
- Required future provider E2E: real Keycloak 204, stale admin bearer (401/403), service outage, HTTP timeout after remote success, logout request replay, role restriction, session evidence before/after, offline session behavior and BFF/API token invalidation. Existing short-lived access JWTs can remain cryptographically valid until expiry unless resource servers enforce fresh account status/revocation.
- Without a configured trusted invoker and provider E2E, this is **not** a complete operational revocation service. Production admission remains HOLD.

## Retention approval register (not approved)

Security and Legal must explicitly approve the following before release:
- exact statutory/contractual retention for IAM deletion records, CRM buyer identity links, SALES orders and financial audit, and the lawful basis for each;
- whether the encrypted OIDC subject is retained until ACK plus a limited audit interval, the maximum retention age, automatic purge and access controls;
- backup/PITR/WAL/log/replica/export expiration, restore-time deletion reconciliation and legal hold treatment;
- monitoring SLA for PENDING outbox age, retry attempts, age of expired leases, provider failures and operator escalation;
- review/approval authority for deletion, requester's identity proof, dispute route and cross-border deletion obligations.

Default working proposal (NOT a legal determination): monitor revocation tasks older than one hour, escalate persistent provider failure rather than silently marking ACK, and never purge before external ACK is independently evidenced.

**No approval has been granted on behalf of Security or Legal.** Only a signed decision with owner/date/version can close the governance gate.
