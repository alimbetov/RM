# QR-06B/B3.3 — Sustained Capacity & Recovery Qualification

**Branch:** `base_dev`  
**Candidate code SHA with hard test timeout:** `eafa61cffae55b9c227ac7dde123afcf62993ea5`  
**CI:** https://github.com/alimbetov/RM/actions/runs/45037705438 (B3.3 sustained job SUCCESS, one JUnit test, zero failures/errors/skips)  
**Status:** 60-second two-JVM B3.3 subgate PASS on exact SHA; full sustained capacity and production admission HOLD.

## Implemented service-level load harness

- Test-only `QrHandoverProcessWorker` mode `B33_PACED`; independently booted JVM workers against PostgreSQL 17, each with separate Spring container and connection pool, with no HTTP listener.
- Two distinct tenant contexts, each 40 READY_FOR_PICKUP orders (two SKUs per order); private manifest of ephemeral pickup QR proofs and order version; inter-process synchronized release.
- Each worker attempts its 40 **independent successful** handovers on a 1,500 ms cadence, so the shared planned offered rate is about 1.33 successful orders/s for the paired workload. It is a correctness baseline, not saturation or certified throughput maximum.
- During the 60-second phase, owner-authorized inventory RECEIVE, CORRECT, WRITE_OFF (DAMAGE), and interwarehouse TRANSFER compete with handovers (8 stock operations total).
- Immutable per-order receipt, unique COMPLETED event and movement/reservation relationships verified, along with exact final stock balances per tenant, warehouse transfer target balances, no stranded reservations, and tenant-level fulfillment fairness (40 each).
- PostgreSQL `pg_stat_activity` lock waiting sessions sampled approximately once per second; `pg_stat_database.deadlocks` delta observed around the test; source-only latency p95 and p99 emitted by each JVM and retained in CI artifacts.
- JUnit time limit prevents a non-terminating soak from masquerading as qualification.

## Strict admission conditions

All 80 independent handovers must succeed; no unexpected denials, no duplicate/partial debit, zero new deadlocks, completed count by tenant exactly 40, exact inventory balance and zero open reservation for these orders, and all CI B1/B2/B3.1/B3.2 regression jobs at **the same code SHA** must pass.

## Gaps and performance caveats

- A single nominal 60-second run is insufficient for the required repeatability, 300-second qualification, capacity knee detection, SLO signoff or production admission. Another profile must repeat 60/120/300 seconds on specified hardware with measured warm-up and failure cohorts.
- The test gives throughput under low offered rate, not maximum steady-state TPS. It does not stress Hikari connection-pool saturation or measure per-operation pool utilization, queue depth, PostgreSQL `pg_stat_statements` or exact lock-wait durations.
- PostgreSQL background deadlock counters are database-wide. To attribute their delta exclusively to this workload, run against a dedicated isolated test DB.
- Synthetic verified OIDC actor and test-only manifests do not prove actual external OIDC, network packet-loss behavior or physical scanner correctness.
- **B3.3 = HOLD pending exact-SHA PostgreSQL job and extension. QR-06B production admission = HOLD.**

## Exact-SHA PostgreSQL 17 results — completed 2026-11-22

The `qr06b-b33-sustained` job in GitHub Actions run 45037705438 is **SUCCESS** at candidate SHA `eafa61cffae55b9c227ac7dde123afcf62993ea5`. Surefire: **1 test, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**.

| Scope | Observed |
|---|---:|
| Real JVM processes | 2 |
| Independent tenants | 2 |
| Independent orders completed | 80 (40 + 40) |
| Authorized overlapping inventory commands | 8 |
| Measured wall duration | 59,008 ms |
| Measured successful handover throughput | 1.356 orders/s |
| Tenant A successful p95 / p99 | 43 / 180 ms |
| Tenant B successful p95 / p99 | 46 / 176 ms |
| Technical errors, business rejects | 0 / 0 |
| PostgreSQL deadlock delta | 0 |
| Sampled Lock wait session sum / max (1 Hz) | 0 / 0 |

The test asserts **exactly one receipt, COMPLETED transition, and movement linkage for every order**, all expected warehouse and cross-warehouse balances and zero outstanding reservations. The observation of zero waiting sessions at 1 Hz **cannot rule out subsecond lock waits**. Tenant equality is an expected fixture workload and observed result, not a statistical fairness guarantee under asymmetric load.

**Interpretation:** The B3.3 60-second low-rate candidate is qualified. A 300-second repeat, stress-level offered rate, connection pool saturation, per-tenant fairness under asymmetric arrivals and multiple scenario repetitions are not qualified. In particular, 1.356 orders/s is not a system-wide capacity ceiling.

**Remaining admission:** 300-second/long-run repeatability and stress, actual network response loss, external OIDC/JWKS, HTTPS, physical devices, commercial policy and production SLO signoff. Overall production HOLD.
