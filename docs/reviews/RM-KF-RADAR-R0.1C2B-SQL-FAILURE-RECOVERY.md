# RM-KF-RADAR-R0.1C2B — statement evidence and failure recovery
Qualified C2A baseline 8648b9cd95b368e0ae6353a50485a885f49f9262: A/B/C1/C2A 63/63, TASK PG 53/53 H2 35+18. Integration parent 70b6205a52003c4d2c0ff4e76f0c22c09dc9a533 adds only CORE-RET-04 evidence/test/workflow changes. No inherited PASS; independent engineering development, release gate strict, production HOLD.

Test-only DataSource wrapper captures JDBC execution categories per thread and only during scope, no SQL bind values or raw SQL persisted. JDBC and Hibernate use the same wrapped DataSource; positive control requires JPA SELECT and JDBC UPDATE with rollback. Unknown/non-SELECT or batch fails read assertion. IAM locking SELECT is permitted. This is evidence of the exercised application JDBC path, not general DB audit/RLS, not detection of server-side trigger internals or all possible logging paths. No production changes/dependencies. Fault injection uses SQLState 08006 at task SELECT before delegate; separate PG real lock timeout 55P03 and retry. No fake empty result accepted.

| Method | Evidence |
|---|---|
| c212InjectedBusinessSelectFailureNeverReturnsEmptyPage | injected SQL exception propagated, ledger intact |
| c213RetryAfterFaultReadsNewCommittedVersion | fresh retry after mutation |
| c214ExactlyOneBusinessSelectWithIamAdmission | one business SELECT, IAM admission |
| c215ZeroBusinessDmlAndLedgerUnchanged | zero DML, task/event snapshots |
| c216PageSizeDoesNotCreateNPlusOne | page-size invariant and next page |
| c217DeniedReadAndInvalidCursorNeverQueryBusinessRows | authorization/cursor failures no business SQL |
| c218SignalDtoUsesStrictAllowlistNoTitleOrActorFields | signal DTO JSON allowlist, not exhaustive log audit |
| c219CancelledCrmSourceDoesNotHideActiveLinkedTask | canonical CRM cancel does not hide linked active task; task cancel does |
| c220ProbePositiveControlCapturesJdbcDmlAndJpaSelect | positive JDBC/JPA capture and rollback |
| c221RealPgLockTimeoutAndRecovery | real PG lock timeout and recovery |

10 named methods PG, 9 executed H2 + one explicit PG-only skip. Target 82/82 executions (63+19); no unexpected skips. All prior gates unchanged. C3 performance and remaining full RAD trace/expiry/log review remain; no final qualification claim.
