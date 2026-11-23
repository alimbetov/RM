# RM-TASK R3.2A-Q1/Q2 — implementation and evidence status

**Branch:** `base_dev` · **Production:** HOLD · **R3.2B:** SPEC ONLY (no worker, outbox table, REST or new infrastructure).

## Changes delivered
- `TaskReconciliationSnapshotService.capture` locks the existing CRM source row before checking the idempotent receipt; a committed exact-key replay is preserved even if the source status changes after capture. New capture still requires `CANCELLED`.
- Added `verifyIntegrity` with current tenant/actor permission checks and count+SHA-256 recomputation over immutable persisted snapshot rows.
- Added T49–T53: lost-response replay following COMMIT/source-status change; authenticated digest verification; exactly 10,000 captured items and complete pagination; PostgreSQL source-status race; manager permission revocation race.
- Mandatory TASKS CI now discovers 53 tests, requires PostgreSQL 53/53 with zero skips, and H2 53 discovered with 18 PostgreSQL-only skips.
- Added `.github/workflows/rm-task-r32a-populated-upgrade.yml`: checkout R3.1 pre-0149 SHA, populate task and CRM rows, checkout exact target SHA, run migrations and R3.2A suite, assert historical row survival, recompute canonical digest from database, perform backup/restore and verify restored digests.
- At the original R3.2A review, the populated-upgrade workflow became the seventh mandatory exact-SHA family; R32B T00-PG subsequently became the eighth. Both TASKS workflows are triggered on every `base_dev` push so a docs-only commit cannot accidentally pass without same-SHA TASKS evidence.
- Extended exact-SHA aggregator timeout to await populated migration without prematurely claiming FAIL.

## Qualification status
All changes above are **committed source and CI configuration**, not a passing execution attestation. This review did not obtain completed same-head PostgreSQL/manifest proof; a limited set of GitHub Actions run metadata may be visible without final artifact verification. Never mark Q1/Q2 as PASS solely from these commits.

### Mandatory acceptance
1. On one identical final SHA: TASKS H2/PostgreSQL, populated upgrade, T00-PG, 5 other existing domain workflow families and `RM Exact-SHA Qualification Admission` all SUCCESS with nonexpired artifacts and verified Surefire checksums.
2. Evidence must show the 53-case matrix, 18 expected H2 skips, zero PostgreSQL skips, persistence and integrity checks and backup/restore.
3. Review actual PostgreSQL EXPLAIN/lock contention and p95/p99 for a 10,000-item snapshot; qualify customer-account/membership revocation races in addition to role grant revocation.
4. No production admission or worker delivery until retention/PII and external IAM security acceptance is approved.

## Decision
**R3.2A-Q1/Q2 implementation substantially advanced; final exact-SHA and operational qualification remain OPEN.**
Do not begin R3.2B implementation, and do not merge to main.
