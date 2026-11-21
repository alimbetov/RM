# RM SALES-INVENTORY-R1.3 — Rollback & Recovery Qualification

**Branch:** `base_dev`. **Nature:** Java/Spring service tests with real PostgreSQL concurrency; no HTTP controllers, no `SecurityConfig` changes, no schema changes. **Production and merge:** HOLD until independent review and service-wide qualification.

## Purpose

R1.2 established concurrent double-submit idempotency, consistency under 6 workers, and diagnostic p95/p99. R1.3 adds failure recovery and conflicting terminal command qualification, without adding a retry framework, distributed transaction coordinator or prematurely exposed transport.

## Test scenarios

| Test | Fault/interleaving | Required invariant |
|---|---|---|
| `r13RollbackAfterSuccessfulDomainCallsCanBeSafelyRetried` | Deliberately throw after `confirm`, `cancel` or `fulfill` returned but before caller's enclosing transaction commits. | Rolled-back status, stock, reservations, immutable events and fulfillment ledger leave **no** partial effects. After fresh retry exactly one complete outcome exists; immediate identical retry is a no-op. |
| `r13PostgresCancelVsFulfillRaceHasExactlyOneTerminalOutcome` | Two concurrent workers attempt opposite transitions for the same CONFIRMED order. | Exactly one terminal operation commits; no active reservation, no negative stock, single corresponding state event and (only when fulfilled) one immutable fulfillment movement. | 
| Existing `postgresSalesConfirmationFencesConcurrentPermissionRevocation` | Real confirmation competes with RBAC permission revoke. | Already admitted mutation completes before revoke; subsequent commands denied. |
| Existing R1.2 mixed workflow | Twelve orders, retries, six workers | No ledger duplication or oversell. |

## Evidence

Before R1.3: exact-head `92c407e2032bf715ba660d514c6cc42577abd014` was GREEN. R1.3 acceptance requires a *new* exact-head H2 + PostgreSQL integration + populated-PG GREEN, including the new methods; count assertions and negative tests are not satisfied solely by successful compilation.

**Important limitation:** exception injected after domain method and before outer COMMIT models transaction rollback and deterministic retry. It does not constitute real OS process kill, network partition, PostgreSQL crash/restart, distributed exactly-once delivery, or PITR. Those require a separately controlled PostgreSQL fault-injection environment with a reliable service recovery harness.

## Next module

After R1.3 is GREEN, proceed to CRM/ORGANIZATION with a separate service-contract and tenant/PII matrix. HTTP controllers, `SecurityConfig`, transport-layer authentication and full production admission remain deferred until each relevant service reaches its evidence gate.
