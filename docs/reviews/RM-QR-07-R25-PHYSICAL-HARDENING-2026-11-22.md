# RM QR-07/R2.5 — Physical Return Hardening

**Date:** 2026-11-22. **Branch:** `base_dev`. **Latest code candidate:** `8a171f31ff6e4d2ebb7c8eeab7658298a40b6e2d`. **Admission:** HOLD pending exact-SHA PostgreSQL and full regression. Production HOLD.

## Added physical return controls

- **Isolated quarantine stock ledger**: `crm.inv_return_quarantine_balance` and immutable `crm.inv_return_quarantine_movement`, keyed by tenant/warehouse/SKU and linked to exactly one inspected return. Never increases sellable `crm.inv_stock_balance.on_hand`.
- `SalesReturnPhysicalService.post` supports `QUARANTINE → QUARANTINED` through a transaction-scoped internal `ReturnInventoryPostingPort.quarantine`; the return, posting, movement and balance commit or roll back together. Immutable posting references the new quarantine movement.
- Buyer-verified, **single-use** physical return proof `SalesReturnReceptionProofService.issue` with 256-bit CSPRNG token, tenant/warehouse/return-bound SHA-256, 10-minute UAT TTL, and replaceable generation. Raw token is returned once and not stored; original buyer owns issuance. `receive` consumes this proof within the same transaction as reception and saves only the digest in immutable evidence.
- IAM `SALES_RETURN_RECEIVE/INSPECT/POST` plus warehouse scope, separate `INVENTORY_RECEIVE` for quarantine/restock; denied/revoked credentials cannot post new movements.

## Qualification plans

**Current exact-SHA CI:** https://github.com/alimbetov/RM/actions/runs/45048873534; two jobs, `qr07-r0-postgresql` and `qr07-full-postgresql-regression`. Both required for full R2.5 technical closure.

Tests: immutable RESTOCK and WRITE_OFF baseline, QUARANTINE exact quantity and zero sellable-stock delta, multiple returns aggregated by SKU, foreign staff/scope, revoke `SALES_RETURN_POST` and `INVENTORY_RECEIVE`, invalid/rotated/replayed physical proof, 2 JVM race, hard process crash before/after COMMIT.

**Constraints:** 10-minute proof TTL is UAT only. This is cryptographically unpredictable server-issued scan proof, but **not** real scanner/HTTPS/external OIDC admission. Quarantine RELEASE/TRANSFER/DISPOSE workflows and reconciler are **not yet implemented**. There is no financial refund.

## QR-08 sequencing

Only after R2.5 evidence and remaining correctness defects are closed: QR-08/R0 service catalog and immutable order-service snapshot, then QR-08/R1 packaging consumables/reservation/precise single stock debit. Controllers remain out of scope. Production admission HOLD.

## Iterative qualification evidence (do not conflate SHAs)

The initial R2.5 candidate dedicated PostgreSQL job completed 20 tests, with two test data failures (wrong forged-token shape and `Set.of` duplicate when two fixtures choose the same SKU). A second candidate completed 20 tests with one intermittent failure because an otherwise valid 43-character Base64URL token may begin `-` or `_`, which the general evidence validator rejected. This was a **real production validation defect** and is now fixed by applying a token-specific `[A-Za-z0-9_-]{43}` format and explicit null guard.

Latest code candidate also includes the **concurrent scope revocation × return stock posting** PostgreSQL test; it requires fully consistent outcome and no extra movement under either possible winning transaction.

All subsequent source changes require a **new exact-SHA** result. The old failed/unfinished runs do not qualify the current candidate. Dedicated QR07 and full PG jobs are separate and must be reported separately.

## Out of R2.5 admission

Quarantine *intake*, segregated balances, and **append-only release/disposal exits** are now implemented as a candidate. `SalesReturnQuarantineResolutionService.resolve` makes `QUARANTINED → RESTOCKED/DISPOSED` with exact return-linked quantities, row/version fencing, IAM and warehouse checks, atomic decrement of quarantined balance, optional authenticated stock restock, and immutable exit evidence. A dedicated independent quarantine reconciler and mixed real-device E2E remain open. None of these latest candidate claims is PASS before same-SHA CI. A one-time buyer QR is synthetic in service tests and is **not real scanner, external OIDC, or HTTPS proof**. QR-08 packaging work is specified in `docs/spec/RM-QR-08-PACKAGING-SERVICE-FIRST-R0-R1-2026-11-22.md`, but not yet implemented.

## R2.5 extended implementation candidate

- Changeset `0103` and `0104`: immutable `sales_return_quarantine_resolution` with non-sellable before/after quantity, return-derived provenance and stock movement reference for restock exits.
- `SalesReturnQuarantineResolutionService.resolve`: protected `INVENTORY_RECEIVE` on RESTOCK; `INVENTORY_ADJUST` on DISPOSE; locks source return, warehouse scope, stock and quarantine balance. Uses one Spring transaction for outgoing quarantine quantity, optional inbound sellable stock and return/event status.
- New PostgreSQL service tests for exactly-once quarantine release/restock, disposal without sellable stock change, prior test of posting/revoke race, credential rotation, quarantine SKU aggregation.
- Physical token validation uses exact URL-safe 43-character format including leading `-`/`_`; the prior first-phase CI failure exposed that real production bug.
- **Latest source SHA above requires a fresh complete CI result.** The full-project PostgreSQL job can be slow; its status is independent of focused return tests.

**QR-08** remains specification-only until QR-07/R2.5 full correctness qualification. See `docs/spec/RM-QR-08-PACKAGING-SERVICE-FIRST-R0-R1-2026-11-22.md`.

## Whole-project PostgreSQL evidence from earlier exact SHA

The first full `mvn test` PostgreSQL 17 workflow ran **522 tests, 0 failed assertions, 1 test error, 18 skips** at an earlier candidate SHA. The one error was the test-only `Set.of(item,itemTwo)` fixture, which could contain duplicate randomly generated SKU; fixed by deduplicating with a mutable set. This historical result is a **FAIL**, not a current production gate. Follow-on candidates showed a failed negative IAM case caused by passing a malformed legacy `SCAN_004` string after the service started enforcing 43-character Base64URL. That fixture has been changed to pass a correctly shaped invalid proof so the intended IAM gate is actually reached.

The current code SHA above adds independent quarantine restock/dispose exit and is the only source revision eligible for R2.5 admission. Its dedicated and full PostgreSQL jobs must both finish GREEN. Earlier failures and runs still underway never count toward that admission.

## Latest QR-07 hardening continuation

- Implemented concurrent quarantine `RESTOCK × DISPOSE` check: exactly one terminal decision and exactly one quarantine exit event, preserving stock conservation.
- Added cross-tenant/warehouse resolution denial test.
- Corrected lock order in `SalesReturnQuarantineResolutionService`: standard INVENTORY request-stripe and stock admission is performed ahead of the separate quarantine balance lock under the same atomic outer transaction. This prevents the obvious inventory-stripe/stock-row inversion risk.
- Updated concurrency test to accept either a state-version conflict or an immutable-decision replay conflict as the loser, with the same exactly-once outcome invariant.
- **Latest QR-07 code SHA:** `6a06f8119f0d7959b1f49e371a719dec352b42e7`.
- **QR-07 exact-SHA verification:** https://github.com/alimbetov/RM/actions/runs/45049455205 . At the last inspection, dedicated and full PostgreSQL jobs were both queued, **not PASS**.
- The repository branch has independent CRM commits after this SHA. Separate the QR-07 tested candidate from later branch HEAD and do not assert broader admission by ancestry alone.

**QR-08 implementation deferred pending QR-07 PostgreSQL gate.** Specification exists, no QR-08 executable code committed in this step.
