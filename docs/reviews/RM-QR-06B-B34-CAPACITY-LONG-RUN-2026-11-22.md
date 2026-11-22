# RM QR-06B/B3.4 — Capacity Saturation & Long-Run Qualification

**Branch:** `base_dev` (no changes to `main`, no new controllers)  
**Candidate exact-SHA:** `b7cfb3734eb2150d38274f8f348f2d7020197486`  
**CI:** https://github.com/alimbetov/RM/actions/runs/45038309495  
**Admission:** **HOLD** until measured exact-SHA PostgreSQL job success; full 300s production-style saturation remains separate.

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
