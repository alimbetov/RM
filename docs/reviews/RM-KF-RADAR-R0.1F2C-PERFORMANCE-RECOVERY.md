# RADAR R0.1F2C — targeted performance recovery

## Exact-SHA evidence before remediation

Baseline 0ce88aee7135b59dbea5b7af3524aaf2e6ffc470, 35 successful, 3 failing, 2 skipped.
- [C3 job](https://github.com/alimbetov/RM/actions/runs/45463565413/job/138693896428): 100k maximum standalone read p95 20.190 ms; mixed read 22.988 ms (34 reads); command p95 4.666 → 5.831 ms (+24.96645354%, FAIL versus 10%).
- [F2 job](https://github.com/alimbetov/RM/actions/runs/45463565362/job/138692366455): 8 tests, 3 failures, 0 errors; maximum 100k standalone read p95 1324.821 ms; mixed read p95 1276.973 ms (6 reads); command p95 21.910 → 11.986 ms. Negative degradation in this noisy run does not offset read failure. F2A prior SHA 254d3ee10b5cb94c679accf5bdf99b10afb73fa8 measured max standalone read p95 160.900 ms, mixed read 176.163 ms and command degradation +13.32908548%, so F2A was not an overall performance PASS.
- [RET04 target](https://github.com/alimbetov/RM/actions/runs/45463566110/job/138692368837): 81/81 Java, 56/56 evidence, three manifests. [Full regression](https://github.com/alimbetov/RM/actions/runs/45463566110/job/138692369386): 1007 tests, 0 failures/errors, 24 skipped. Global semantic gate remains HOLD.

## Controlled candidate

Restore only eventSummary to the F2A SQL shape (page-key predicate for history window; original composite-key prior event join). Keep F2B exact native timestamp overdue predicate, deferred overdue arithmetic, page-key materialization, SQL equivalence tests, signed identity, tenant/actor fencing and independent SQL oracles. No migration or threshold changes. This isolates the event-summary rewrite as a candidate cause of the read regression; CI observations alone do not establish causality. Do not claim F2C PASS until measured at its exact SHA.

Published EXPLAIN/raw-sample artifacts: [F2B](https://github.com/alimbetov/RM/actions/runs/45463565362/artifacts/14954942540), [C3B](https://github.com/alimbetov/RM/actions/runs/45463565413/artifacts/14955105674). Plans are not independently inspected in this review. Next: compare actual scan/join/loops/buffers, and characterize command concurrency under reproducible resource conditions. 6 mixed-reader samples are inadequate for a robust tail estimate. Do not reduce workload, increase the 200 ms/10% limits or select a favorable retry. C3 mixed-command gate and global semantic admission remain open. Production HOLD; no REST/UI.
