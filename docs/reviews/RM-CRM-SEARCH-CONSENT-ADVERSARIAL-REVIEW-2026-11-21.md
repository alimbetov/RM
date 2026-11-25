# CRM Search / Consent Idempotency — adversarial service review

**Branch:** `base_dev`. **Security/production:** HOLD. **No controllers or SecurityConfig changes.**

## Source findings

| ID | Severity | Evidence | Resolution |
|---|---|---|---|
| CR-01 | HIGH | Consent events were described as append-only but PostgreSQL allowed direct UPDATE/DELETE, permitting historical grant/revocation edits | Added PG-only Liquibase 0053 immutable trigger and actual SQL UPDATE/DELETE rejection test |
| CR-02 | HIGH | Keyed consent retry success and concurrency were tested, but failure after successful inner service call and before outer COMMIT was not qualified | Added rollback, persisted absence of event, re-submit, duplicate no-op test |
| CR-03 | HIGH | AAD-bound encrypted contact values were round-tripped but cross-record envelope substitution was not denied by an explicit qualification | Added contact ciphertext substitution negative tests for singular and paginated reads |
| CR-04 | MEDIUM | SQL wildcard escaping in customer company-name substring filter had no adversarial test | Added literal `_`, `%`, and backslash assertions, plus case-insensitive search |
| CR-05 | OPEN | Offset pagination runs count and row query separately; concurrent creates can shift pages and counts | Keep stable createdAt/UUID order and size 1–100. Before infinite-scroll consumers, define keyset cursor/snapshot contract |
| CR-06 | OPEN | 64 global consent lock buckets intentionally serialize unrelated tenants on stripe collision | Correctness safe for R1 but throughput may degrade under sustained writes. Profile contention before production; avoid introducing distributed locks |
| CR-07 | OPEN | The existing internal unkeyed consent command still appends a separate event on each invocation | Kept for backward compatibility and historic tests, but only expose keyed `recordEmailConsentOnce` in authenticated external facade; migrate internal call sites before transport |
| CR-08 | OPEN | Marketing consent proofs do not implement dispatch-time eligibility checks | Must check latest committed grant after relevant locks immediately before sending; separate Marketing/outbox qualification |

## Acceptance matrix

- Existing CRM functional suite including cross-tenant, roles, privacy, encrypted storage, permitted search, read-revoke concurrency.
- `consentKeyDoesNotSurviveRollbackAndCanBeRetried` — rollback + retry.
- `customerNameFilterEscapesSqlWildcardsAsLiteralText` — literal LIKE metacharacters.
- `contactCiphertextSwapFailsClosedUnderPerRecordAad` — ciphertext swap fails without PII disclosure.
- `postgresConsentEvidenceRejectsDirectSqlUpdateAndDelete` — DB-level immutable event history.
- R1.1 12 concurrent keyed consent calls yield one event.
- Exact-head H2, PG integration, and synthetic populated PG all GREEN; failures are HOLD, not waivable.

**This is service-only functional/security qualification**, not deployed OIDC, realistic attack penetration test, production KMS, high-volume benchmark, or independent review.
