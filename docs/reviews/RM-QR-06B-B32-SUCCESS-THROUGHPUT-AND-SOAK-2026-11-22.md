# RM QR-06B/B3.2 — Qualified successful handover throughput and paced soak

**Date:** 2026-11-22  
**Branch:** `base_dev`  
**Exact tested source SHA:** `b5dd6e570c75d61ab3a5eb10beb10243477188d9`  
**GitHub Actions:** https://github.com/alimbetov/RM/actions/runs/45036428295  
**Evidence:** `qr06b-b32-success-throughput` completed SUCCESS, 3 JUnit tests; 0 failures, 0 errors, 0 skipped. Regression job `qr06b-postgresql` SUCCESS at the same exact SHA.  
**Decision:** B3.2 synthetic successful-operations qualification is PASS in its tested scope; comprehensive B3.2 and production admission remain **HOLD**.

## What ran

1. **Independent success throughput:** 48 distinct READY orders, two independent tenant contexts, two interleaved 24-order bursts; all 48 successfully COMPLETED, 12 different-key replays were rejected and measured separately. Source-specific SQL invariants verified one handover, immutable receipt, one completion event and one stock fulfillment per line, no reserved inventory after handover. Tenants alternate within each wave instead of being benchmarked sequentially.
2. **Paced soak:** 20 one-second windows, four new orders offered per second (two per tenant): 80 distinct successful handovers, zero failed requests, all receipts and exact final stock balances verified.
3. **Mixed inventory workload:** 16 independent successful handovers in two tenants concurrently with eight authorized `AuthenticatedInventoryCommands` operations: receive, correct, writeOff and warehouse transfer (one each per tenant). Data reconciliation verified final stock positions and transfer target balances via SQL.

**Key operational constraint:** fixtures and authenticated command paths were used; no direct SQL mutation of stock in measured operations. Synthetic actors are not external OIDC. The write-off used the authorized DAMAGE cause. The transfer used distinct, active warehouses.

## Quantified exact-SHA results

| Scenario/outcome | Count | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|
| 48 independent successful handovers | 48 | 170 | 304 | 318 |
| 12 expected distinct-key business rejections | 12 | 3 | 8 | 8 |
| 20s paced successful handovers | 80 | 20 | 96 | 179 |
| Mixed successful handovers | 16 | 70 | 110 | 110 |
| Mixed authorized inventory mutations | 8 | 104 | 112 | 112 |

The 48-order two-wave burst completed in a cumulative measurement window of 523 ms: **91.77 completed orders/s during these bursts only**. Mixed 16 completed orders were recorded in 113 ms (**141.28 completed orders/s during the burst**); these are not sustained capacity results.

The paced soak offered 4 orders per second during 20 one-second windows, completed 80 in 19,021 ms including last response, giving **4.206 completed orders/s**. p95 and p99 refer to the 80 successful handovers, not technical or business-error responses. All 80 handovers were verified against immutable order, credential and inventory receipt state. This is 20 seconds and a low fixed offered rate; do not extrapolate to maximum sustainable orders/s or minutes/hours of service.

## Data and admission limitations

- All measurements come from GitHub-hosted runner and isolated PostgreSQL 17 service. No hardware, tuning, connection pool, JVM warmup and sustained production topology envelope has been approved.
- Technical failures trigger JUnit failure; therefore measured successful runs have zero *observed* technical failures, not a statistically bounded operational error rate.
- The p99 nearest-rank estimator uses limited samples: 48, 80, 16, 8. Confidence intervals and multi-run variance for successful sustained handovers have not been established.
- Same-run regression tests and B3.1 load must also pass at the exact tested SHA; a successful B3.2 sub-job alone is not full release admission.
- Stress across 2+ Java processes with *many distinct successful orders*, independent tenant fairness, live IAM revocation during sustained load, simultaneous reconciliation over long movement histories, DB deadlock/timeout/connection-pool saturation and cross-service network loss remain open.
- Real transport-layer response loss, external OIDC, public HTTPS, physical scanners, commercial pickup policy and production SLO sign-off remain blocked.

## Next step

**B3.3 — workload realism and variance:** multiple warm repeated durations of 60–300 seconds with prescribed offered rate, at least two independent Java processes, independently fulfillable orders, interference from stock movements, tenant-balanced acceptance rate, p50/p95/p99 by success/business rejection/technical fault, database lock wait and deadlock metrics, and immutable SQL proof after every phase. Do not call observed short-burst throughput a capacity guarantee.

**Production admission = HOLD.**

## Exact-SHA full regression and closure of this test candidate

All three jobs in the workflow completed SUCCESS:
- `qr06b-b32-success-throughput`: **3 tests, 0 failures/errors/skips**, including 20-second, 80-success paced soak.
- `qr06b-b31-mixed-load`: SUCCESS, requalified the 100/250 contention and cross-JVM profiles.
- `qr06b-postgresql`: **41 tests** = `QrWarehouseHandoverQualificationTest` 30 and `TenantRbacRevocationQualificationTest` 11, **0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.

**Decision:** the specific B3.2 synthetic profiles PASS at source SHA `b5dd6e570c75d61ab3a5eb10beb10243477188d9`. This version has zero claim of a 60–300 second continuous production-representative capacity test. An exact-SHA follow-up documentation commit is not a code retest. Full release and external qualification remain HOLD.
