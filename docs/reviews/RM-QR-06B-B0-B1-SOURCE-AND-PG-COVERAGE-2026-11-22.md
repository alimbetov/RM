# QR-06B/B0–B1 — Source-based coverage review and PostgreSQL test evidence

**Date:** 2026-11-22  
**Branch:** `base_dev`  
**Target test commit:** `322fa3c72f9d462fa932d73ce9dba3f0ae78c99f`  
**Status:** CODE COMMITTED / CI PENDING — **NOT QUALIFIED**  
**Production admission:** HOLD

## B0 actual source inspection

Read `src/main/java/kz/rm/sales/SalesPickupHandoverService.java`, `src/main/java/kz/rm/iam/TenantAuthorization.java`, `src/main/java/kz/rm/iam/WarehouseStaffScopeService.java`, `src/test/java/kz/rm/sales/QrWarehouseHandoverQualificationTest.java`, and `src/test/java/kz/rm/iam/TenantRbacRevocationQualificationTest.java` on `base_dev`.

The handover service obtains fresh tenant SALES_ORDER_FULFILL and INVENTORY_FULFILL decisions, locks an order and warehouse scope, validates a credential, calls `OrderInventoryPort.fulfill` for each reservation, updates SALES to COMPLETED, creates a status event, immutable handover receipt and line receipts, consumes the credential and audits. All mutations are in one Spring transaction. Real PostgreSQL uses `clock_timestamp()` for proof expiry. Replay with matching request key and fingerprint reads prior receipt. Distinct key after completion fails before more movements.

The existing QR-05/06 tests exercise full picking, permission / warehouse scope denial, issue/reissue/expiry, no-debit preview, successful handover and same-key retry, different-key rejection, two simultaneous scans and fault-triggered rollback of the final inventory movement. The existing IAM concurrency suite separately exercises role/member/tenant/account revocation barriers, but not the concrete handover call.

## B1 additions in candidate SHA

Three `requirePostgres()`-guarded methods added to `QrWarehouseHandoverQualificationTest`:
- `qr06bPgHandoverCommitPrecedesConcurrentScopeRevocation`: hold an already completed handover inside an outer PG transaction before COMMIT; verify a parallel scope revocation cannot finish until the first commits; verify exactly one persisted receipt and debit.
- `qr06bPgScopeRevocationCommitPrecedesHandoverAdmission`: hold a committed-first scope revocation transaction before release and verify an actual `pickup.complete` waits then fails closed, with zero persisted handover effects.
- `qr06bPgAmbiguousResponseReplayCannotDuplicateInventory`: re-invoke after a successful COMMIT with identical and distinct request keys; assert persisted one-time effects.

Shared SQL invariants assert order state, single handover receipt, one COMPLETED event, correct number of fulfillment movement/receipt rows, single consumed credential, nonnegative reserved stock and no `on_hand < reserved`. Negative path asserts READY status, no status event / movement / receipt, unconsumed credential and unchanged stock.

**Evidence scope:** the tests have been written and committed. No PASS is claimed without an exact-head PostgreSQL CI report.

## Gaps still open

1. Independently authorized 5/20 employee contention (current original race test uses one employee with distinct request keys).
2. Full `handover × IAM grant revocation` both orderings; `handover × global suspension` both orderings. The new direct B1 tests focus on *warehouse scope* revocation; neighboring IAM-only tests are insufficient to certify the end-to-end handover race.
3. Crash immediately before/after COMMIT, physical network response loss and multi-JVM tests (the deterministic retry simulates ambiguous response at service level).
4. Mixed cancellation, adjustment, reconciliation, long chains, expiry boundary, cross-tenant high-volume, throughput and p95/p99.
5. Independent exact-SHA evidence manifest and real scanner / OIDC / HTTPS / approved TTL policy.

## Admission decision

`QR-06B/B0 = PARTIAL SOURCE REVIEW`; `QR-06B/B1 = IMPLEMENTED TEST CANDIDATE / NOT YET VERIFIED`; overall `QR-06B = HOLD`.

Review GitHub Actions for the candidate SHA and require a successful PostgreSQL run and its reports before elevating status. Do not infer pass from workflow initiation.
