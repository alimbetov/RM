# RM-KF-RADAR-R0.1C2A — PostgreSQL concurrent transaction qualification
Qualified baseline a969f5b8f4e3c4b02c955c5f8c0b23bd3eb23d63: A/B/C1 52/52, TASK PG 53/53 and H2 35+18 expected skips. [A](https://github.com/alimbetov/RM/actions/runs/45434679716), [B](https://github.com/alimbetov/RM/actions/runs/45434680045), [C1](https://github.com/alimbetov/RM/actions/runs/45434679846), [TASK](https://github.com/alimbetov/RM/actions/runs/45434679833).
New candidate execution PENDING. Independent development, no release waiver. No production changes or migration.

C2A 11 named PG17 tests, two independent transaction threads and distinct pg_backend_pid(); observer uses separate connection. IAM first/second orders require actual pg_blocking_pids edge before release; expected denial type AND exact reason, actual IAM services, signed JWT. TASK writers require second operation committed before releasing first (no task row lock), coherent old tuple during uncommitted writer, new committed state afterward. Rollback cancels status/deadline changes; live cursor can omit moved row until refresh. No sleep-only assertion. Bounded futures and finally release. H2 explicitly skips this PG-only suite in full test runs, dedicated PG gate rejects fallback and any skip. Existing named A/B/C1/TASK counts unchanged.

| Case | Schedules | Trace |
|---|---:|---|
| c201 role revoke | 2 | RAD-20 |
| c202 account suspend | 2 | RAD-21 |
| c203 membership suspend | 2 | RAD-21 |
| c204 workspace suspend | 2 | RAD-21 |
| c205 complete | 2 | RAD-26 |
| c206 cancel | 2 | RAD-26 |
| c207 reschedule | 2 | RAD-27 |
| c208 reassign | 2 | RAD-27 |
| c209 requeue | 2 | RAD-27 |
| c210 rollback status and deadline | 2 | RAD-25 |
| c211 live cursor | 1 | RAD-31 |

Total 21 deterministic test variants, 11 JUnit methods, not 21 independent methods. C2B remains: DB failure/retry, statement-level SQL/no-business-DML instrumentation, evidence of no sensitive DTO/log exposure, CRM source cancellation independence and remaining admission negatives. C3 remains performance. Global exact-SHA gates unchanged and full RAD qualification not claimed. Next local gate 63/63 (52+11) and TASK regression on same SHA.
