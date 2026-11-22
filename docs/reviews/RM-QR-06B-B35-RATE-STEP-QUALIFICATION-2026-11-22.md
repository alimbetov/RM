# RM QR-06B/B3.5 — Sustainable Throughput Ceiling

**Branch:** `base_dev`; exact-SHA candidate `87485db0a58f73e91b84c0715955ab366683fd86`.  
**CI:** https://github.com/alimbetov/RM/actions/runs/45040418773  
**Status:** B3.5 entry rate-step PostgreSQL PASS on candidate SHA; **sustainable throughput ceiling NOT ESTABLISHED**, production HOLD.

## Implemented first stage

- Separate `qr06b-b35-rate-steps` PostgreSQL 17 job, fixed Git commit SHA and upload of metrics and Surefire reports.
- Two independent JVM processes, each an authenticated synthetic staff user in a distinct tenant, 40 separate READY pickup orders per tenant.
- Normal service-layer `SalesPickupHandoverService.complete` with per-order pickup proof, expected row version and unique idempotency key. No controller and no direct SQL mutation of pickup state.
- Four paced stages, each approximately 20 seconds: LOW (5 orders / tenant), RAMP (10), STRESS (20), RECOVERY (5). 80 total independent successful handovers.
- Child JVM emits p95/p99 by phase and outcome, completed count, per-phase *completion span* throughput, and observed Hikari peak active/waiting/connection counts; parent verifies 80 immutable order/receipt/stock relationships and exact final stock, no newly observed database deadlocks.
- The child modes preserve use of a shared release gate and an **absolute** planned arrival clock for the new B35_RATE mode, rather than resetting each task's schedule based on its individual thread wake-up.

## Important interpretation

**This is an executable instrumentation / admission smoke, not the user-requested sustained-capacity ceiling proof.** It does not use 120–300 seconds *per offered-rate level*, and its deliberately low arrival rates may not saturate Hikari. Do not infer a maximum TPS from its successful completion or extrapolate the short completion-span throughput into steady-state throughput. Expected business failures, technical errors, successful handovers and queueing must remain separate statistical populations.

## Blocking issues before full 120/300-second rate sweeps

1. **One-time QR TTL:** current proof TTL is UAT-limited to about 10 minutes. A proper full sweep needs just-in-time per-order proof issuance or a staged, policy-respecting renewal process. Pre-creating every QR proof at fixture-setup time may cause expired credentials unrelated to capacity.
2. **Rate-accurate load:** introduce explicit offered-arrival events, late/queued arrivals, completion timestamps and per-phase achieved successful orders/sec, rather than relying only on event count divided by first-to-last completion span.
3. **Saturation characterization:** run warm 120-second (then 300-second) stages at multiple increasing rates; identify a reproducible latency knee, plateau in sustained successful throughput, persistent Hikari queue, technical timeout threshold, and stable recovery.
4. **Fairness:** test unequal offered rate and record achieved success-rate ratio and p99 per tenant, with agreed minimum acceptance / bounded p99 criteria.
5. **Database evidence:** attribute pg_stat_activity lock waits and deadlock deltas to a dedicated PG database; capture connection pool metrics as time series with sampling intervals, not only maxima. Record Postgres settings, pool config, hardware and JVM GC.
6. **Correctness:** enforce exactly-one handover/stock debit per order, zero partial receipt, zero unauthorized completion, zero unmatched inventory reservations, and reproducible crash/restart recovery after overload.

## Transition plan to Returns & Services

After the B3.5 capacity and operational correctness gates have evidence, implement **QR-07 returns first**, then **QR-08 packaging/delivery/add-on services** on the service layer, as specified by `docs/spec/RM-QR-07-08-RETURNS-AND-SERVICES-SERVICE-FIRST-R1.md`. Do not reinterpret returns as `cancel(COMPLETED)`; do not mutate original handover receipt/stock history. These new modules require their own PostgreSQL and IAM concurrency gates.

**Production admission remains HOLD.**

## Executed B3.5 entry rate-step evidence

**Exact SHA:** `87485db0a58f73e91b84c0715955ab366683fd86`  
**Job:** `qr06b-b35-rate-steps` in https://github.com/alimbetov/RM/actions/runs/45040418773 — **SUCCESS**, Maven 1 JUnit test, 0 failures/errors/skips, BUILD SUCCESS.

| Phase | Tenant A completed | Tenant B completed | Tenant A p95/p99 (ms) | Tenant B p95/p99 (ms) |
|---|---:|---:|---:|---:|
| LOW | 5 | 5 | 144 / 144 | 119 / 119 |
| RAMP | 10 | 10 | 32 / 32 | 32 / 32 |
| STRESS | 20 | 20 | 26 / 28 | 25 / 27 |
| RECOVERY | 5 | 5 | 19 / 19 | 17 / 17 |

Each worker observed phase completion-span rates (completed count / time from first arrival to last completion):
- LOW 0.312, RAMP 0.555, STRESS 1.052, RECOVERY 0.312 completions/s for both tenant JVMs;
- combined STRESS approximately 2.104 successful completions/s, **not measured sustainable maximum capacity**.
- JVM A and B: **peak Hikari active=1; peak waiting=0; peak connections=10** each; 0 technical failures, 0 business denials.
- Parent: 80/80 durable handovers, 10 completions in RECOVERY, zero observed new PG deadlocks; exact per-tenant stock and receipt invariants hold.

**Important result:** The monotonically increasing offered rate **did not** produce pool saturation or latency degradation: p95 improved as the JVM warmed. B3.5 cannot be closed from these results, even though the synthetic workload is functionally correct. The prior B3.4 burst demonstrated transient queueing, but a burst cannot substitute for a sustained rate knee.

**Separate CI admission:** all parallel B1/B2, B3.1/B3.2/B3.3/B3.4 regressions must succeed at this same code SHA; report those results independently after final workflow completion. Any later documentation-only commit is not itself a new test proof.
