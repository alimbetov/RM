# F2D diagnostic follow-up and QR CI recovery

Baseline 66c624ec8526c9dca3d325ab2675f9eee67ef027: 34 success / 4 failure / 2 skipped.
- QR-07 target: 81/81 Java, 56/56 scenarios and three manifests on exact SHA. [Job](https://github.com/alimbetov/RM/actions/runs/45527454527/job/138914215753).
- QR-07 full: Maven dependency:build-classpath failed before tests with NoPluginFoundForPrefixException. Global gate correctly terminated with TERMINAL_EVIDENCE_FAILURE. [Full job](https://github.com/alimbetov/RM/actions/runs/45527454527/job/138914215428).
- C3: 100k max read p95 4.390 ms, mixed reader 2.621 ms (153 samples), command 2.336 -> 3.903 ms, +67.0838918% FAIL. EXPLAIN for worst read 0.233 ms. [C3](https://github.com/alimbetov/RM/actions/runs/45527454570/job/138914215190).
- F2: max read p95 245.314 ms, mixed read 213.736 ms (6 samples), command 2.411 -> 3.157 ms, +30.924% from independent profiler. F2 concurrency 5/5 PASS. EXPLAIN for dense completed-early limit100 first 198.636 ms, next 204.197 ms. [F2](https://github.com/alimbetov/RM/actions/runs/45527454541/job/138914215101). Both profiler self-test runs 6/6 PASS.

Candidate removes the redundant Maven dependency plugin goal from both QR jobs and obtains child JVM classpath from the executing Surefire test JVM (explicit environment override remains available). Missing classpath fails closed; no worker/crash scenario skipped. Full and targeted qualification on new SHA required. Maven repository availability may still affect ordinary dependency resolution; this does not claim otherwise.

Calendar mixed assertions now report both budgets independently. Profiler prints top inclusive plan nodes and lock observations to job logs; raw artifacts retained. No production SQL changes, thresholds, sample schedules, or security changes. These diagnostics do not prove contention causality. Need actual plan/lock investigation and controlled longer mixed workload before further production performance remediation. CORE-RET-04 E.6C/E.7 remain pending; production HOLD.
