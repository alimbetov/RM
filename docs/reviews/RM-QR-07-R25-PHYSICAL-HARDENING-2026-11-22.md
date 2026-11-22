# RM QR-07/R2.5 — Physical Return Hardening

**Date:** 2026-11-22. **Branch:** `base_dev`. **Latest code candidate:** `f40c8e1e6df8485e44f68b972ffc1fc1ead6e452`. **Admission:** HOLD pending exact-SHA PostgreSQL and full regression. Production HOLD.

## Added physical return controls

- **Isolated quarantine stock ledger**: `crm.inv_return_quarantine_balance` and immutable `crm.inv_return_quarantine_movement`, keyed by tenant/warehouse/SKU and linked to exactly one inspected return. Never increases sellable `crm.inv_stock_balance.on_hand`.
- `SalesReturnPhysicalService.post` supports `QUARANTINE → QUARANTINED` through a transaction-scoped internal `ReturnInventoryPostingPort.quarantine`; the return, posting, movement and balance commit or roll back together. Immutable posting references the new quarantine movement.
- Buyer-verified, **single-use** physical return proof `SalesReturnReceptionProofService.issue` with 256-bit CSPRNG token, tenant/warehouse/return-bound SHA-256, 10-minute UAT TTL, and replaceable generation. Raw token is returned once and not stored; original buyer owns issuance. `receive` consumes this proof within the same transaction as reception and saves only the digest in immutable evidence.
- IAM `SALES_RETURN_RECEIVE/INSPECT/POST` plus warehouse scope, separate `INVENTORY_RECEIVE` for quarantine/restock; denied/revoked credentials cannot post new movements.

## Qualification plans

**Current exact-SHA CI:** https://github.com/alimbetov/RM/actions/runs/45048262597; two jobs, `qr07-r0-postgresql` and `qr07-full-postgresql-regression`. Both required for full R2.5 technical closure.

Tests: immutable RESTOCK and WRITE_OFF baseline, QUARANTINE exact quantity and zero sellable-stock delta, multiple returns aggregated by SKU, foreign staff/scope, revoke `SALES_RETURN_POST` and `INVENTORY_RECEIVE`, invalid/rotated/replayed physical proof, 2 JVM race, hard process crash before/after COMMIT.

**Constraints:** 10-minute proof TTL is UAT only. This is cryptographically unpredictable server-issued scan proof, but **not** real scanner/HTTPS/external OIDC admission. Quarantine RELEASE/TRANSFER/DISPOSE workflows and reconciler are **not yet implemented**. There is no financial refund.

## QR-08 sequencing

Only after R2.5 evidence and remaining correctness defects are closed: QR-08/R0 service catalog and immutable order-service snapshot, then QR-08/R1 packaging consumables/reservation/precise single stock debit. Controllers remain out of scope. Production admission HOLD.

## Iterative qualification evidence (do not conflate SHAs)

The initial R2.5 candidate dedicated PostgreSQL job completed 20 tests, with two test data failures (wrong forged-token shape and `Set.of` duplicate when two fixtures choose the same SKU). A second candidate completed 20 tests with one intermittent failure because an otherwise valid 43-character Base64URL token may begin `-` or `_`, which the general evidence validator rejected. This was a **real production validation defect** and is now fixed by applying a token-specific `[A-Za-z0-9_-]{43}` format and explicit null guard.

Latest code candidate also includes the **concurrent scope revocation × return stock posting** PostgreSQL test; it requires fully consistent outcome and no extra movement under either possible winning transaction.

All subsequent source changes require a **new exact-SHA** result. The old failed/unfinished runs do not qualify the current candidate. Dedicated QR07 and full PG jobs are separate and must be reported separately.

## Out of R2.5 admission

Quarantine *intake* and segregated balances are implemented. Releasing stock back out of quarantine to sellable inventory, permanent disposal from the quarantine position and dedicated quarantine reconciliation have **not** been implemented and will be a subsequent qualification. A one-time buyer QR is synthetic in service tests and is **not real scanner, external OIDC, or HTTPS proof**. QR-08 packaging work is specified in `docs/spec/RM-QR-08-PACKAGING-SERVICE-FIRST-R0-R1-2026-11-22.md`, but not yet implemented.
