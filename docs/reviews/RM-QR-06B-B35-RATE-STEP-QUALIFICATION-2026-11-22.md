# RM QR-06B/B3.5 — Sustainable Throughput Ceiling

**Branch:** `base_dev`; exact-SHA candidate `87485db0a58f73e91b84c0715955ab366683fd86`.  
**CI:** https://github.com/alimbetov/RM/actions/runs/45040418773  
**Status:** B3.5 smoke qualification **pending**; **sustainable throughput ceiling NOT ESTABLISHED**, production HOLD.

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
