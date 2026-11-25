# RM QR-06B — B2 process crash / multi-JVM PASS and B3 entry load evidence

**Date:** 2026-11-22
**Branch:** `base_dev`
**Exact test SHA:** `be1fd8a1658296b559303de243243cf78e99c9a9`
**PostgreSQL 17 CI run:** https://github.com/alimbetov/RM/actions/runs/45034966282
**Status:** B2 in-process + process-crash / multi-JVM tests PASS; B3 entry smoke PASS; **full B3 and production remain HOLD**.

## Qualification proof

GitHub Actions job `qr06b-postgresql` succeeded with exact-SHA check and published Surefire artifacts. Maven results:
- `QrWarehouseHandoverQualificationTest`: **24 tests, 0 failures, 0 errors, 0 skipped**;
- `TenantRbacRevocationQualificationTest`: **11 tests, 0 failures, 0 errors, 0 skipped**;
- **Total: 35 tests, zero failures/errors/skips; BUILD SUCCESS**.

Test-only `QrHandoverProcessWorker` runs separate Spring Boot JVMs with independent connection pools and servlet security configuration but no exposed HTTP connector (`--server.port=-1`). The parent verifies durable SQL state after the child hard exits:

1. **Pre-COMMIT halt** (`Runtime.halt(86)`) while inside an outer active transaction: PostgreSQL aborts connection and rolls back prior inventory/order/receipt writes. Retrying from the parent succeeds once.
2. **Post-COMMIT halt** (`Runtime.halt(87)`) after transactional complete() has returned but before sending the result: authorized request replay reuses the original receipt with no debit duplication, and a different key is rejected.
3. **Two independent JVMs** wait on a shared file barrier before competing with distinct request keys for one order: one exit code 0, one business rejection exit code 2; persisted inventory movements/receipt invariant confirmed.

Child process credentials are read from private temporary fixture file, never placed in command-line arguments. Logs are redacted on test failures. Test-only secret fixture and logs are deleted afterward.

## B3 early load evidence

Single GitHub hosted runner, Spring test services, PostgreSQL 17, *one measured run per profile*. All latency values are end-to-end service-call attempts and include rejected outcomes. Millisecond percentiles are nearest-rank; workload is **hot-order contention**, not transactions-per-second capacity.

| Profile | Attempts | Business completions | Expected denials | p50 (ms) | p95 (ms) | p99 (ms) |
|---|---:|---:|---:|---:|---:|---:|
| Hot order | 10 | 1 | 9 | 48 | 55 | 55 |
| Hot order | 50 | 1 | 49 | 98 | 155 | 156 |
| Hot order + warehouse-scope revoke | 20 | 1 | 19 | 27 | 32 | 33 |

For all three profiles test assertions validate at most one completed handover, one receipt, one COMPLETED event, no extra fulfillment movement per reservation, nonnegative balances and absence of partial stock postings. The mixed IAM revocation run allows 0 or 1 completions by valid serialization; observed one. No absolute latency thresholds or service-level SLO have been approved.

## Failure investigations resolved before acceptance

- Initial worker compilation failed due a Java local variable named `java` shadowing the `java.nio` namespace; corrected.
- Worker startup initially failed with `WebApplicationType.NONE` due required `HttpSecurity`; changed test-only boot context to SERVLET.
- Two independent workers initially conflicted on port 8080 because default Spring properties overrode `server.port=-1`; changed to explicit CLI arg `--server.port=-1`. Verified exact-SHA multi-process CI PASS.
- Initial B3 compilation failed due compound `var` declarations; corrected. The successful B3 test SHA is the one above, not the failed candidate.

These are qualification-harness defects, not evidence of duplicate stock movement.

## Still open — do not promote to production

1. **Actual network fault injection:** break client transport after durable commit using a proxy or socket fault; qualify timeout detection and replay through the real external boundary.
2. **Expanded B3:** repeated independent runs with warm-up, mixed inventory receive/adjust/reconciliation and order cancellation; warehouse hot spots and distinct tenant orders; profiles 100/250, at least 2 app processes, pooled-connection saturation, process restarts, deadlocks and recovery lag.
3. **Quantified admission:** define SLO p95/p99, accepted throughput, DB utilization and recovery thresholds against specified hardware and data sizes. Preserve raw logs.
4. **External readiness:** OIDC/JWKS, scanned devices, public HTTPS and separately approved operational pickup policy. One-time QR TTL 10 minutes remains UAT configuration.

**Decision:** `QR-06B/B2 tested scope = PASS`; `QR-06B/B3 entry load = PASS`; `QR-06B overall = HOLD`; `production admission = HOLD`. No new controllers, no changes to `main`, no QR-07/08 implementation.
