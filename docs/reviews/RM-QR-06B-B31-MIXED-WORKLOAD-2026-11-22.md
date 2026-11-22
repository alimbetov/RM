# RM QR-06B/B3.1 — Mixed Workload PostgreSQL Qualification

**Date:** 2026-11-22
**Branch:** `base_dev`; no controller changes or modifications to `main`.
**Exact tested SHA:** `4e87392619f98f7adcc27eea17253b457fb165be`
**GitHub Actions run:** https://github.com/alimbetov/RM/actions/runs/45035614955
**Evidence job:** `qr06b-b31-mixed-load` — SUCCESS.
**Source:** `QrWarehouseHandoverQualificationTest` / `QrHandoverProcessWorker`, test-only.
**Release decision:** B3.1 defined scenario set PASS; whole QR-06B and production admission **HOLD**.

## Observed test evidence

PostgreSQL 17, Java 21, hosted GitHub runner, independent test Spring contexts and two Java child processes in the distributed profiles.
The B3.1 job ran `mvn -B -ntp '-Dtest=QrWarehouseHandoverQualificationTest#qr06bPgB31*' test` and produced **3 tests; 0 failures; 0 errors; 0 skipped; BUILD SUCCESS**. Surefire XML, console log, extracted measurements and exact-SHA file were uploaded under `qr06b-b31-<sha>`.

### One JVM, three orders sharing two warehouse stock positions

Three separate orders require 5 units of SKU1 and 3 of SKU2 each. All 3 must complete once, while other attempts using different request keys are rejected. Each of the 100/250 profiles was run three times with isolated tenant fixtures. The expected final balances were 5 of SKU1 and 11 of SKU2, with zero reserved and exactly one receipt/COMPLETED event and one fulfillment movement per reservation.

| Clients | Run | Completed | Expected rejected | p50 ms | p95 ms | p99 ms | Attempts/sec* |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 1 | 3 | 97 | 268 | 420 | 428 | 225.40 |
| 100 | 2 | 3 | 97 | 226 | 349 | 354 | 267.42 |
| 100 | 3 | 3 | 97 | 190 | 299 | 303 | 299.45 |
| 250 | 1 | 3 | 247 | 341 | 569 | 582 | 405.96 |
| 250 | 2 | 3 | 247 | 371 | 616 | 633 | 377.10 |
| 250 | 3 | 3 | 247 | 278 | 493 | 508 | 479.78 |

*Attempts/sec includes rejected conflicting operations, not successful handovers per second. Measured after releasing the test start barrier (no artificial 1-second denominator floor), therefore it is a diagnostic of contention processing, not business throughput capacity.

### Mixed authorized inventory and cancellation

40 competing physical handover attempts across 2 ready orders, plus 2 `AuthenticatedInventoryCommands.correct` operations, two read-only `AuthenticatedInventoryReconciliation.inspect` calls, and one `SalesInventoryCommandService.cancel` on a **different CONFIRMED** order:
- completed 2, rejected 38; two corrections and a separate cancellation succeeded; reconciliation had no RESERVED_MISMATCH or MOVEMENT_BALANCE_MISMATCH;
- p50=204 ms, p95=293 ms, p99=301 ms;
- final stock of first SKU = 11, second SKU = 15, reserved zero;
- READY_FOR_PICKUP cancellation was intentionally NOT attempted because the present SALES state machine denies it. This is an explicit architectural boundary, not missing cleanup.

### Two JVMs / shared PostgreSQL

Two independent JVMs with their own Spring contexts, JDBC pools and 50/125 concurrent clients each waited for a shared start gate:
- 100 attempts, exactly 1 completion / 99 conflicts; 1401 ms full distributed elapsed;
- 250 attempts, exactly 1 completion / 249 conflicts; 1928 ms full distributed elapsed;
- post-run SQL checked a single handover, one COMPLETED event, expected ledger/receipt lines, credential CONSUMED and exact nonnegative balances. These elapsed times include test-driver scheduling and completion collection and are **not** p95/p99 service latencies.

## Interpretation and limitations

- No duplicate durable stock debit or partial handover was found in these deterministic scenarios. They do NOT establish correctness for every concurrency ordering.
- Three repetitions per load tier are an initial qualification, not statistical performance characterization. p95/p99 include expected rejected attempts and must be segmented by outcome for an operational SLA.
- 100/250 are **simultaneous attempts**, not sustained concurrent successful orders or orders/sec. With only three distinct orders, at most three successful handovers are possible.
- CI runner hardware/DB pool size and data distribution are not production-representative. Avoid claiming production-ready capacity or approving absolute SLO thresholds.
- Concurrent RECEIVE/writeoff/transfer and direct warehouse reconciliation across separate instances, concurrent cancellation of the SAME READY order (currently forbidden), longer movement chains, multi-tenant fairness and sustained throughput tests remain outside measured scope.
- Network-fault injection at real HTTP/transport boundary, external OIDC/JWKS, HTTPS and real scanners remain HOLD. One-time QR 10-minute TTL remains UAT policy.

## Suggested next admission stages

**B3.2 — Capacity & mixed operations:** use many independently fulfillable orders, sustained load (duration-controlled, per-outcome latency), hot and cold warehouse SKUs, receive/correct/writeoff/transfer against reserve/fulfill, long movement chains, separate tenant cohorts, 2+ application instances. Document transaction deadlocks, retries, DB locks, connection pool saturation and p95/p99 by outcome.

**B4 — External integration and release admission:** proxy-injected broken response both before and after COMMIT; true OIDC revocation, HTTPS canonical domain and actual scanners; user-approved policy and meaningful SLO thresholds against fixed hardware. Remain HOLD until proven.
