# RM QR-06B/B3.4 — Capacity Saturation & Long-Run Qualification

**Branch:** `base_dev` (no changes to `main`, no new controllers)  
**Candidate exact-SHA:** `b7cfb3734eb2150d38274f8f348f2d7020197486`  
**CI:** https://github.com/alimbetov/RM/actions/runs/45038309495  
**Admission:** staged 60/300s B3.4 qualification PASS on exact-SHA; maximum sustainable capacity and production admission remain HOLD.

## Implemented first-stage B3.4

Two Java JVM processes, two independent tenant workspaces, a private set of 40 unique orders per tenant, one PostgreSQL 17 database. Each order uses authenticated pickup handover via the normal service layer; both worker JVMs use their own Spring application contexts/connection pools and have no HTTP listener.

Three prescribed phases per tenant:

- LOW: 10 independent handovers, 1.5 seconds between arrivals;
- PEAK: 20 independent handovers, 0.5 seconds between arrivals;
- RECOVERY: 10 independent handovers, 2.0 seconds between arrivals.

The child JVM records p95/p99 by **phase and outcome** and 20-ms-sampled Hikari active connections, pending pool waiters and total connections. Parent verifies immutable receipt, order status event, credential consumption, fulfillment ledger, exact balances and no stranded reservations for all 80 orders, plus no new PG deadlocks. This is not a measure of customer traffic through real HTTP, nor a demonstration of a saturated Hikari pool without observed waiting and a throughput knee.

## Admission matrix

| Gate | Rule | Status |
|---|---|---|
| B34-JVM | 2 truly independent JVM processes | Code implemented; CI pending |
| B34-TENANT | 40 valid successful handovers per tenant | Code implemented; CI pending |
| B34-CORRECT | 0 duplicated movements, partial transactions, negative reserved | Code implemented; CI pending |
| B34-METRIC | p95/p99 per phase/outcome; Hikari pool peak active/waiting/total | Code implemented; CI pending |
| B34-DB | PG deadlock delta zero, journal invariant maintained | Code implemented; CI pending |
| B34-SATURATION | Prove knee and Hikari pool pressure, with controlled ramp | NOT YET TESTED |
| B34-FAIRNESS | Unequal tenant offered load and throughput isolation | NOT YET TESTED |
| B34-300S | Repeat 60/120/300-second warm runs | NOT YET TESTED |
| B34-RECOVER | Recovery after *measured saturation*, not merely a traffic-rate decrease | NOT YET TESTED |
| B34-EXTERNAL | actual packet loss, external OIDC, real scanners, HTTPS | NOT YET TESTED |

**Do not promote this first-stage result to full B3.4 PASS without the final five gates.**

## Required next engineering increments

1. Decouple order fixture preparation from measurement; avoid counting checkout/QR issue setup in service-call latency. Use a fixed, published machine/DB/Hikari configuration; repeat profiles.
2. Introduce configurable rate and run duration (60, 120, 300 s) with **unequal tenant groups**, sufficiently many independent orders and correct load generator pacing (late-arrival and queue metrics).
3. Measure DB lock durations, wait events, connections and Hikari pending threads over time, export raw samples with exact SHA. Assert cleanup after overload.
4. Increase offered successful orders per second until p95/p99 inflation and worker saturation are observed. Do not treat conflicts on the same order as throughput successes.
5. Promote a quantitative performance SLO only after statistically repeatable runs and separate pass/fail criteria for business rejects versus infrastructure faults.

**Production admission remains HOLD.**

## Extended qualification candidate (2026-11-22)

**Tested source candidate:** `de7b4f0170721257d46acbbedf6e274cd0885df2`  
**New exact SHA CI:** https://github.com/alimbetov/RM/actions/runs/45038810734 — evidence pending.

The original staged run completed successfully for both tenants, but **never saturated Hikari**: observed peak active connections = 1, peak waiting = 0 on each JVM. Therefore it was admitted only as a low-pressure baseline. The new revision replaces the PEAK arrivals with a *concurrent burst*, adds two repeated 60-second runs plus a separate 300-second run and makes tenant request distributions asymmetric.

- 60-second variant: 50 orders from tenant A, 30 from tenant B. LOW 12/7, PEAK 25/15 simultaneous requests, RECOVERY 13/8.
- 300-second variant: 240 orders from tenant A, 160 from tenant B. LOW 60/40, PEAK 120/80 simultaneous requests, RECOVERY 60/40. Every request is an independent reserved order, not a conflicting scan against one order.
- Test-only private manifest, hard separate JVMs, exact SQL receipt/stock/tenant checks, p95/p99 per phase and operation outcome, Hikari peak active/waiting/total, zero-deadlock guard.
- Parent simultaneously polls `pg_stat_activity` lock wait sessions every ~100 milliseconds, reporting sample sum and maximum waiters. This does not measure precise lock-wait duration or eliminate sampling bias.
- The 300-second run and the two 60-second runs are configured in `qr06b-b34-capacity-recovery` under the same exact SHA; no result is considered PASS until Surefire and all three runs succeed.

**Saturation admission remains conditional:** a sustained Hikari waiting queue and/or clear response-latency knee must be observed; a short burst reaching pool capacity is an entry signal, not proof of stable maximum service throughput. Production readiness remains HOLD.

## Proven first-stage baseline — before stronger saturation profile

The first B3.4 job at exact SHA `b7cfb3734eb2150d38274f8f348f2d7020197486` completed **SUCCESS** on PostgreSQL 17: 80/80 successful pickups, 2 real JVMs, 2 tenants, zero reported new deadlocks, 0 failures/errors/skips. Recorded Hikari peak active=1, waiting=0 per worker. Success-only p95/p99:

| Phase | Tenant A (ms) | Tenant B (ms) |
|---|---|---|
| LOW | 258 / 258 | 290 / 290 |
| PEAK | 25 / 25 | 26 / 33 |
| RECOVERY | 32 / 32 | 32 / 32 |

These figures explicitly **disprove any claim that the baseline reached connection-pool saturation**; peak response time improvement reflects a different arrival profile and warmup, not demonstrated high-load resilience.

The enhanced exact-SHA target `de7b4f0170721257d46acbbedf6e274cd0885df2` is a **different** code candidate that changes PEAK to simultaneous arrivals, adds asymmetric tenant groups (50/30 for 60s and 240/160 for 300s), instruments 100ms PostgreSQL waiter samples and executes two 60s runs plus one 300s run. It must be evaluated separately. The documentation commits do not themselves count as source-code test qualification.

## Confirmed enhanced saturation evidence — exact SHA `de7b4f0170721257d46acbbedf6e274cd0885df2`

**CI:** https://github.com/alimbetov/RM/actions/runs/45038810734  
**Result:** all five jobs SUCCESS: B3.4, B3.3, B3.2, B3.1, and `qr06b-postgresql`. The common regression job reports **43 tests; 0 failures; 0 errors; 0 skipped; BUILD SUCCESS** (32 handover qualification and 11 IAM revocation tests). Dedicated B3.4 CI ran its JUnit test three times (two 60s, one 300s); all three PASS with zero failures or skips.

### Load and reliability

| Trial | Tenant A completed | Tenant B completed | Total | Hikari A peak active / waiting | Hikari B peak active / waiting | PG sampled max waiting sessions | New deadlocks |
|---|---:|---:|---:|---|---|---:|---:|
| 60s #1 | 50 | 30 | 80 | 10 / 15 | 10 / 5 | 16 | 0 |
| 60s #2 | 50 | 30 | 80 | 10 / 15 | 10 / 5 | 15 | 0 |
| 300s | 240 | 160 | 400 | 10 / 110 | 10 / 70 | 17 | 0 |

Every independent pickup was verified against the durable order/receipt/fulfillment journal and expected inventory balances. All phases reported only `COMPLETED`; there were **zero technical failures** in these scenarios. Total independent successful handovers across the three runs: **560**.

### Latency by phase (success-only, p95 / p99 in ms; microsecond source rounded)

| Trial | Tenant | LOW | PEAK | RECOVERY |
|---|---|---:|---:|---:|
| 60s #1 | A | 165 / 165 | 797 / 811 | 29 / 29 |
| 60s #1 | B | 175 / 175 | 688 / 688 | 30 / 30 |
| 60s #2 | A | 267 / 267 | 690 / 705 | 29 / 29 |
| 60s #2 | B | 258 / 258 | 554 / 554 | 25 / 25 |
| 300s | A | 108 / 381 | 2,646 / 2,687 | 23 / 26 |
| 300s | B | 74 / 332 | 2,169 / 2,261 | 25 / 27 |

The PEAK creates a **real connection-pool waiting queue** with `peak_active=10` and `peak_connections=10` in both JVMs; under the 300s trial peaks of `threadsAwaitingConnection=110` and `70` were measured. Meanwhile p95 increased sharply in the peak, then fell again for RECOVERY, while all requests completed correctly.

PG `pg_stat_activity` polling about every 100 ms recorded sample sums **75**, **63**, and **350** waiter-count observations respectively, and maximum waiting sessions 16/15/17. These are **counts of waiting sessions in sampled instants**, NOT 75/63/350 distinct waits, nor measured wait time in milliseconds. Observed `pg_stat_database.deadlocks` delta was zero in each isolated trial.

### Decision and unresolved admission

**B3.4 staged burst saturation and recovery: PASS for tested 60/300 second shapes.** This supports that the limited Hikari pool was saturated transiently, queueing increased latency, and the operation remained correct and recovered during the subsequent low-rate phase. The test **does not** establish maximum *sustainable* throughput: the peak stage is a synchronized burst of finite orders, not a stepped series of steady-state offered rates until a stable throughput knee is characterized.

Still required for capacity release:

- Fixed hardware, repeatable warm throughput-duration sweeps (e.g. 3–5 levels, each 120–300s) with measured offered, accepted and completed orders/sec and pool pending-queue occupancy over time.
- p95/p99 segmented by outcome (successful, expected business denial, technical error), timeout/error rate, per-tenant fairness under unequal offered load and effective rate.
- Query/DB lock wait duration and connection-pool saturation/recovery beyond one peak observation, high-confidence variance across replicated 300s runs.
- Fault recovery after overload, transport packet-loss injection, external IAM/OIDC, HTTPS and scanner integration.

**Production admission HOLD.** No controllers introduced, no changes to `main`, QR-07 and QR-08 not started. The documentation commit incorporating results is not itself the qualified source SHA.
