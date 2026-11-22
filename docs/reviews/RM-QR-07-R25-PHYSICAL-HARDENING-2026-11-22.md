# RM QR-07/R2.5 — Physical Return Hardening

**Date:** 2026-11-22. **Branch:** `base_dev`. **Admission:** CANDIDATE pending PostgreSQL and full regression. Production HOLD.

## Added physical return controls

- **Isolated quarantine stock ledger**: `crm.inv_return_quarantine_balance` and immutable `crm.inv_return_quarantine_movement`, keyed by tenant/warehouse/SKU and linked to exactly one inspected return. Never increases sellable `crm.inv_stock_balance.on_hand`.
- `SalesReturnPhysicalService.post` supports `QUARANTINE → QUARANTINED` through a transaction-scoped internal `ReturnInventoryPostingPort.quarantine`; the return, posting, movement and balance commit or roll back together. Immutable posting references the new quarantine movement.
- Buyer-verified, **single-use** physical return proof `SalesReturnReceptionProofService.issue` with 256-bit CSPRNG token, tenant/warehouse/return-bound SHA-256, 10-minute UAT TTL, and replaceable generation. Raw token is returned once and not stored; original buyer owns issuance. `receive` consumes this proof within the same transaction as reception and saves only the digest in immutable evidence.
- IAM `SALES_RETURN_RECEIVE/INSPECT/POST` plus warehouse scope, separate `INVENTORY_RECEIVE` for quarantine/restock; denied/revoked credentials cannot post new movements.

## Qualification plans

**Exact-SHA CI:** https://github.com/alimbetov/RM/actions/runs/45047657023; two jobs, `qr07-r0-postgresql` and `qr07-full-postgresql-regression`. Both required for full R2.5 technical closure.

Tests: immutable RESTOCK and WRITE_OFF baseline, QUARANTINE exact quantity and zero sellable-stock delta, multiple returns aggregated by SKU, foreign staff/scope, revoke `SALES_RETURN_POST` and `INVENTORY_RECEIVE`, invalid/rotated/replayed physical proof, 2 JVM race, hard process crash before/after COMMIT.

**Constraints:** 10-minute proof TTL is UAT only. This is cryptographically unpredictable server-issued scan proof, but **not** real scanner/HTTPS/external OIDC admission. Quarantine RELEASE/TRANSFER/DISPOSE workflows and reconciler are **not yet implemented**. There is no financial refund.

## QR-08 sequencing

Only after R2.5 evidence and remaining correctness defects are closed: QR-08/R0 service catalog and immutable order-service snapshot, then QR-08/R1 packaging consumables/reservation/precise single stock debit. Controllers remain out of scope. Production admission HOLD.
