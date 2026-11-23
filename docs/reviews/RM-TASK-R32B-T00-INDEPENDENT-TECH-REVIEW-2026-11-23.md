# RM TASK R32B-T00 / R3.2A — Independent Source and CI Review

**Branch:** `base_dev` only · **Date:** 2026-11-23 · **Production:** HOLD · **B1:** NOT ADMITTED.
**Verification class:** GitHub repository source review and CI configuration read-back. **No executed PostgreSQL/migration/exact-SHA attestation claimed.**

## Review scope

- CRM: `CrmInteractionService`, `CrmCancellationEventRef`, transaction-mandatory internal `CrmCancellationReviewIntentPort` and disabled sink.
- IAM: `TenantAuthorization.requireForCommand`, verified actor, account row/workspace locking.
- TASKS: `TaskReconciliationSnapshotService`, source lock, immutable snapshot/seal, digest verification, 10,000 limit.
- PostgreSQL: incremental changesets 0149 through 0153, migration/restore, header-item-seal constraints.
- CI: 8 mandated exact-SHA workflow families, Surefire XML verifier and fixtures, T00 targeted PostgreSQL suite.

## Confirmed defect fixed in this review

**R32A-SEC-01 / HIGH — MVCC seal race, late insertion after sealed snapshot.**

- In 0152 an item `BEFORE INSERT` trigger only checks for a *committed visible seal* without locking the header against concurrent seal creation. A transaction inserting the seal and a concurrent item INSERT could each observe a state that lets both commit, violating manifest immutability.
- **Fix:** additive PG-only 0153 upgrades the item trigger function to lock the canonical header `FOR UPDATE` before checking the seal. A new seal-insert trigger locks the same header and rejects mismatched `item_count`. This ensures both operations share a serialization fence.
- **Regression:** T46 now tests a **committed unsealed header**, two concurrent transactions with an uncommitted seal, blocking item insertion, post-commit rejection, and separate mismatched-count sealing. The exact 53-method TASKS matrix stays unchanged.
- **CI:** R3.2A populated-upgrade workflow checks presence of the 0153 trigger and publishes synthetic original/restore evidence in the same artifact as Surefire reports. The final run is not yet verified.

**Second confirmed test/CI defect fixed — R32A-QA-02 (P1):** the original T46 malformed-count negative test would persist an unsealed header and then cause the separate populated-upgrade integrity scan to fail on a test-generated fixture. The invalid header/seal attempt now runs inside an explicitly rollback-only transaction, followed by a query asserting the malformed header is absent. This preserves the validity of the independent all-snapshot SHA/restore check.

## Contract and architecture consistency

| Concern | Decision / verified state |
| --- | --- |
| Canonical CRM cancellation source | Retained existing `crm.customer_interaction_event` and `(tenant_id, interaction_id, new_version)` unique; no duplicate CRM event aggregate |
| CRM→TASKS boundary | Typed transaction-local event reference, identical ID/time to persisted event; disabled implementation only |
| IAM | CRM caller must have current `CRM_WRITE`; manager still needs `TASK_MANAGE + CRM_READ` for explicit reconciliation; no privilege escalation |
| SLA dueAt | Must derive from immutable CRM event time, not `task_item.due_at` or capture time |
| TASKS scope | Snapshot evidence is not a cancellation command, delivery queue or new task root |
| DB versioning | Additive 0153 preserves historical 0149–0152 migration checksums; H2 unaffected by PostgreSQL-only trigger |
| Infrastructure | Existing modular monolith, PostgreSQL/Liquibase; no Kafka/Redis, no new server, no external worker |
| Branch/release | `base_dev` only; `main` and production remain untouched |

## CI audit — required admission matrix

1. `RM Domain Security and PostgreSQL Qualification`: H2/PG + populated IAM evidence.
2. `CRM R1.2 Populated PostgreSQL Upgrade Qualification`.
3. `INVENTORY 0081 Populated PostgreSQL Upgrade Qualification`.
4. `SALES 08.4 Populated PostgreSQL Upgrade Qualification`.
5. `RustFS Real Infrastructure E2E`.
6. `RM TASK R2 Service Qualification`: 53 PG no skip; H2 53 total, 18 PG-only skip.
7. `RM TASK R3.2A Populated PostgreSQL Upgrade Qualification`: pre-0149 populated → 0149–0153, immutable seal, 10k cap, backup/restore/digest, T46 regression.
8. `RM TASK R32B T00 PostgreSQL Contract Qualification`: nine explicit source event / rollback / IAM lock interleavings, no skip.

`rm-exact-sha-admission.yml` lists **eight** groups. `verify_rm_qa_01b.py` has corresponding eight REQUIRED/SUITES and requires the T00 nine named tests, not merely a successful workflow label. A static read-back found these names in all required places.

**Important CI observation:** on review commit `a012eae255d6a08563190cf23afb7bf829ac9ee8`, connector-visible domain-security and module workflow runs were pending/queued. No reliable completed push workflow manifest or downloadable, checksum-verified final-SHA artifacts were available. Absence of a status is NOT PASS; no exact-SHA admission can be declared.

## Open findings before T00/B1 admission

| ID | Priority | Residual gap | Acceptance |
| --- | --- | --- | --- |
| R32A-Q1 | P0 | Complete same-final-SHA CI proof across all 8 required families missing | 8 SUCCESS, unexpired artifacts, verified Surefire checksums, no skipped mandatory PG tests |
| R32A-Q2 | P0 | Migration 0149–0153, 0153 PG trigger and seal-race concurrency have no executed PG proof yet | PostgreSQL 17 populated upgrade, T46 PASS, independent digest recheck and restore evidence |
| T00-IAM | P1 | Direct account-status SQL interleavings prove lock protocol but not the trusted IAM revoke/suspend command path | Execute end-to-end IAM service race (both commit orders), no rogue CRM event |
| T00-CRM | P1 | New T00 event handoff and rollback tests not observed executed on final SHA | 9/9 named PG tests with zero skipped/failures |
| R32A-PERF | P1 | 10,000-item capture contention and query-plan p95/p99 not measured | documented synthetic load on populated PG with thresholds agreed |
| SLA-GOV | P1 | Active policy duration/calendar, actor authority, retention and review ownership remain unapproved | explicit product/IAM/security decision records before B1 activation |
| CI-OBS | P2 | GitHub connector provides only partial workflow/PR-triggered visibility and cannot replace native Actions admission evidence | obtain native per-run IDs, finished conclusions, artifacts and manifest at one SHA |

## Release verdict

**Confirmed source-level repair delivered** (0153 plus regression and migration gate). No positive production, R3.2A-Q1/Q2, or T00-PG acceptance is claimed before actual exact-SHA workflows finish. B1 SLA DDL/services, B2 outbox, B3/B4 dispatcher, scheduler, controllers and external delivery remain STOP. Maintain **production HOLD**.
