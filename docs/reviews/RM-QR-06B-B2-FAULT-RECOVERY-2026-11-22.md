# RM QR-06B/B2 — Transaction fault injection and recovery evidence

**Branch:** `base_dev`; **candidate SHA:** `9044482f975c0c8c9e761a0f7aa1d5b9c27fc89c`  
**Status:** SQL fault-injection tests committed; PostgreSQL exact-SHA CI evidence pending final verification. **Production:** HOLD.

## Scope

Two new real PostgreSQL tests are included in `QrWarehouseHandoverQualificationTest`:

- `qr06bPgReceiptInsertFailureRollsBackAllEffectsThenRetrySucceeds`: a per-order PostgreSQL AFTER INSERT trigger throws at the handover receipt after inventory fulfillment and SALES completion statements. Expected: READY_FOR_PICKUP, no receipt, COMPLETED event or fulfillment movements, ISSUED credential, unchanged balances; after removing trigger, the same request key completes exactly once.
- `qr06bPgCredentialConsumptionFailureRollsBackReceiptAndInventory`: a per-order AFTER UPDATE trigger throws when the credential becomes CONSUMED, after all business writes. Expected: total rollback; retry succeeds and another identical retry returns the same canonical receipt without new movement.

Both tests clean up test-only triggers/functions in finally blocks. They assert the other SKU balance too, in addition to shared SQL invariants. Neither modifies production schema.

## Distinction between evidence levels

These tests **do not terminate an application process**, bring down a database, break network transport, or prove cross-JVM recovery. They are deterministic SQL failure injection to qualify rollback semantics. Service-level replay after a durable commit is already in B1, but real packet-loss / timeout handling is not qualified.

## Remaining B2 gates

- Kill a separate JVM process after a durable COMMIT before client response; restart and replay with same and different request keys, comparing receipt IDs and stock ledger.
- Kill the JVM before COMMIT with a controlled barrier; confirm database rollback and successful retry.
- Run two independent Java processes against one PostgreSQL with synchronized concurrent complete() and verify a single successful durable business effect.
- Capture reproducible scripts, environment, exact SHA, timeouts, immutable raw logs and SQL queries. Confirm a clean recovery scan and test cancellation/timeout classes.

## Remaining B3 gates

- Mixed hot-order scans, inventory receive/adjust/reconciliation, role suspension and cancellation, across 10/50/100/250 clients.
- Repeated runs, p50/p95/p99 by outcome, throughput, deadlocks, blocking, error rates and recovery lag.
- Strict correctness criteria: zero duplicated debits, zero partial receipt, zero unauthorized completed orders, zero invalid balances.

Only the exact-SHA real PostgreSQL job and its Surefire reports can promote the new tests to PASS. An H2 run or CI started status is insufficient. QR-07 and QR-08 must not be treated as production-ready on this basis.
