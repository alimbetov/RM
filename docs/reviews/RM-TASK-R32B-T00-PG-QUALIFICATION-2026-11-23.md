# RM-TASK R32B-T00-PG — Transaction & Concurrency Admission

**Date:** 2026-11-23 · **Branch:** `base_dev` · **Scope:** CRM canonical cancellation event, IAM suspension races and rollback on PostgreSQL. **Admission:** HOLD until exact-SHA proof.

## Implemented qualifications (source and CI)

The original `CrmInteractionQualificationTest` now contains **nine explicitly named T00 methods**:

| Case | Method | Expected PostgreSQL proof |
| --- | --- | --- |
| T00-PG-01 | `t00CancellationEventHasCanonicalIdentityAndSingleRevision` | exactly one persistent CANCELLED source event, canonical revision |
| T00-PG-02 | `t00OuterTransactionRollbackRemovesCancellationAndSourceEvent` | outer transaction crash rolls back both CRM state and source event |
| T00-PG-03 | `t00CancellationIntentPortCannotRunOutsideCrmTransaction` | transaction-mandatory disabled handoff refuses unbound invocation |
| T00-PG-04 | `t00PgConcurrentCancellationCreatesExactlyOneCanonicalEvent` | racing cancel(expectedVersion=0) has one winner, one event |
| T00-PG-05 | `t00PgActorSuspensionCommitsBeforeCancellationDeniesWithoutEvent` | IAM row status committed first; command rejected with no cancellation |
| T00-PG-06 | `t00PgCancellationCommitsBeforeActorSuspensionPreservesOneEvent` | admitted cancellation finishes before later suspension; event retained |
| T00-PG-07 | `t00PgCanonicalEventIdentityAndPrecisionAreStoredExactly` | event UUID/revision and microsecond timestamp persisted exactly |
| T00-PG-08 | `t00PgTransactionPortReceivesPersistedCanonicalEvent` | in-transaction typed port receives the exact persisted ID, instant, tenant and revision |
| T00-PG-09 | `t00PgPortFailureRollsBackCancellationAndEvent` | injected source-owner-port failure aborts CRM row change + event |

**New mandatory workflow:** `.github/workflows/rm-task-r32b-t00-postgresql.yml`. Runs all nine T00 tests against PostgreSQL 17 and fails on a skipped, failed or missing case; also checks persisted source-state/event consistency. Artifacts named `rm-task-t00-postgresql-{sha}`.

**Exact-SHA integration:** added eighth mandatory workflow family to `.github/workflows/rm-exact-sha-admission.yml` and `.github/scripts/verify_rm_qa_01b.py`. Surefire XML verifier independently requires exact 9/9 without skipped cases and mandatory named test coverage; synthetic verifier fixtures updated to fail closed when incomplete. Prior seven gates remain required.

**Static inspection:** a read-back of `base_dev` found nine distinct methods and expected references to the T00 workflow, the exact-SHA aggregator and validator. Static inspection is **not executable evidence**.

## R3.2A dependencies — independent qualification status

| Gate | Source/CI configuration | Current evidence |
| --- | --- | --- |
| 53 PostgreSQL TASKS tests / 0 skipped | mandatory workflow and verifier present | final HEAD PASS not retrieved |
| 53 H2 discovered / 18 PG-only skipped | mandatory workflow present | final HEAD PASS not retrieved |
| 0149–0152 applied to populated PostgreSQL | `rm-task-r32a-populated-upgrade.yml` defined | actual migration transcript not retrieved |
| preserved legacy IDs, count, immutable seal SHA | CI assertions defined | actual PostgreSQL output not retrieved |
| post-backup/restore digest recomputation | CI assertion defined | actual PostgreSQL output not retrieved |
| concurrent capture / IAM role revocation / 10k boundary | T47..T53 added | final HEAD PG run not retrieved |
| 10k p95/p99 and query plans | no passing quantitative report available | OPEN |
| security/legal retention and real OIDC | no production security acceptance available | HOLD |

**Evidence limitation:** the available GitHub connector commit-workflow lookup exposes only PR-triggered runs; current push-run status and Surefire artifact checksums could not be verified from it. No Maven/PostgreSQL runtime is mounted here. Consequently **no PASS is claimed** for new tests, R3.2A, T00-PG or global exact-SHA.

## Release and sequencing

- T00-PG source changes and CI gates: **implemented, unverified at runtime**.
- T00 contract admission: **HOLD** until nine tests pass on the final head and the eight exact-SHA workflow families validate.
- B1 SLA schema/services: **NOT STARTED**. Requires explicit approved policy durations, IAM activation permission matrix and earlier R3.2A operational evidence.
- B2 outbox, B3/B4 worker logic, scheduler, external delivery: **NOT STARTED**.
- No new migration, outbox, notification worker, UI, controller, branch or main merge. **Production HOLD.**

## Reproducible qualified commands

```bash
# Exact SHA from GitHub Actions, configured against PostgreSQL 17 in the workflow.
mvn -B -ntp '-Dtest=CrmInteractionQualificationTest#t00*' test

# Full TASKS source contract tests under the same SHA and PostgreSQL.
mvn -B -ntp -Dtest=TaskServiceQualificationTest test
```

Do not mark R32B-T00 as complete until the CI XML, job IDs, artifact checksums, migration/restore evidence and final SHA all match.
