# RM QR-07/R0 — Return Domain Foundation: PostgreSQL Qualification

**Date:** 2026-11-22  
**Branch:** `base_dev`; **qualified exact source SHA:** `4efc90201b59c461944ae60c5be88ec6b8086e6e`.  
**PostgreSQL 17 CI:** https://github.com/alimbetov/RM/actions/runs/45041857312  
**Status:** **QR-07/R0 initial request-contract qualification PASS** (3/3), **QR-07/R1/R2/R3 and production HOLD**.

## Implemented

1. Liquibase changesets `0095` and `0096` in `db.changelog-master.sql`: `crm.sales_return_request`, `crm.sales_return_line`, `crm.sales_return_event`, composite tenant-bound source references and append-only PostgreSQL return-event trigger. Existing completed SALES orders, immutable pickup receipts and INVENTORY movements are not rewritten.
2. `SalesReturnState` Java enum defines allowed transition topology. R0 service only creates `REQUESTED`, not later states. No service implementation for review/approve/receive/inspect/restock is claimed yet.
3. `DomainPermission.SALES_RETURN_REVIEW/RECEIVE/INSPECT/POST`: registered in `TenantAuthorization` as **explicitly fail-closed** without a tenant RBAC grant; future R1/R2 service commands must require their own authorized decision and staff/warehouse scope.
4. `SalesReturnRequestService.request`: receives the live verified buyer principal; uses `SalesOrderQrReferenceService.readOwn` for ownership, PostgreSQL order row lock for concurrent claims, requires `COMPLETED` and an existing immutable pickup receipt/fulfilled source line, enforces nonzero quantity and the total of active claims not exceeding fulfilled quantity. Uses tenant-scoped idempotency key plus SHA-256 input fingerprint. Inserts request, line, and immutable `REQUESTED` event in one transaction.
5. Dedicated `.github/workflows/rm-qr07-r0-pg.yml` exact-SHA qualification gate with PostgreSQL 17 and Surefire artifacts; **no new controllers and no main merge**.

## Verified service scenarios

The dedicated R0 job ran `QrWarehouseHandoverQualificationTest#qr07R0*` at SHA `4efc90201b59c461944ae60c5be88ec6b8086e6e`. Surefire actual log: **3 tests; 0 failures; 0 errors; 0 skipped; BUILD SUCCESS**.

- `qr07R0ReturnPartialReplayAndOverReturnProtectsReceipt`: exact first request + idempotent replay, conflicting replay rejection, cumulative 2+3 units allowed but sixth prohibited, receipt/ledger/stock unchanged.
- `qr07R0RejectsUncompletedOrForeignBuyerBeforeAnyReturnWrite`: READY order rejection; unauthorized buyer and unknown/unfulfilled line rejection with no return writes.
- `qr07R0ConcurrentPartialClaimsCannotExceedFulfilledQuantity`: two independent concurrent requests to return four units of a five-unit issued line; exactly one succeeds under original order lock; total allocated quantity remains four.

All three tests executed on PostgreSQL; the CI job also bootstrapped Liquibase successfully. A harmless PostgreSQL startup log may mention `public.databasechangeloglock` before Liquibase initializes it; test outcome and migration completion, not that uncontextualized startup diagnostic, determine the gate.

## Security and transactional contract

- One completed SALES order still has one immutable pickup receipt and original stock fulfillment.
- Creating a claim has **no stock posting and no refund**. No financial settlement model is inferred.
- Return request is a distinct aggregate with tenant-qualified composite FK to source order, handover and order line. Unlike original order cancellation, it cannot turn a completed sale back into pending.
- The returning buyer is verified from existing IAM/QR ownership and is never supplied as an arbitrary request parameter.

## Explicitly open

- Complete R0 state topology and migration-negative tests on populated PostgreSQL data; R0 currently has three essential service tests, not the 20+ R1 scenario matrix.
- QR-07/R1: tenant-authorized review/approve/reject with scope and policy version, immutable decision evidence and concurrent/negative IAM cases.
- QR-07/R2: authorized physical receipt, inspection, quarantine/disposition and **exactly once** linked stock movements, 2-JVM/crash/retry/rollback tests.
- QR-07/R3: refund/exchange integration contracts only after separate financial qualification.
- QR-08 packaging, delivery and service lifecycle not yet implemented.
- QR-06B/B3.5 full sustainable-throughput ceiling and external OIDC/network/HTTPS/scanners remain **HOLD**.

**Release decision:** QR-07/R0 initial foundation PASS; QR-07 full and production admission HOLD.
