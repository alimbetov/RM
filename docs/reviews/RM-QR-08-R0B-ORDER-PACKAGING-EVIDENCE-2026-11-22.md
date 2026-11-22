# RM — QR-08/R0B Order Packaging Service

**Branch:** `base_dev`. **Candidate SHA:** `843ab52d5191c8aa9e64673d1bd59e2173a73c41`. **Status:** implemented candidate, not qualified. Production HOLD.

## Implemented

- `OrderPackagingService.offer`: permissioned seller offer, original SALES order lock, live immutable catalogue version, order-currency match, exactly one packaging service per (tenant,order,code).
- `OrderPackagingService.decide`: original buyer admitted through existing `SalesOrderQrReferenceService.readOwn` (verified actor and current QR), `ACCEPT | REJECT | CANCEL`, optimistic revision, single terminal decision.
- `crm.sales_order_service_line`: original tenant/order composite FK, catalogue-version composite FK, immutable price/currency/tax/policy snapshot and buyer/offer provenance.
- Append-only `crm.sales_order_service_event`, persistent `crm.sales_order_service_receipt` with actor/action/request fingerprint and immutable command-time result.
- PostgreSQL trigger protects snapshots from SQL updates; event and receipt mutation denied.
- R0 performs no stock movement, stock reservation, payment, refund or shipping.
- Allowed order state for initial offer and buyer decision: `PLACED` / `CONFIRMED`; `PREPARING`, terminal, fulfilled and other states rejected.

## Added test cases (candidate, not execution evidence)

1. Accepted immutable snapshot survives catalog price drift, with no inventory movements.
2. Exact offer/decision request replay and payload conflict.
3. Stale revision and cancelled terminal decision.
4. Foreign tenant seller and unrelated buyer denied.
5. Concurrent `ACCEPT × REJECT`, PostgreSQL-only, exactly one winner.
6. Offer forbidden after order moves to PREPARING, with zero side effects.
7. Buyer acceptance forbidden after preparation began.
8. Rejection is terminal and does not consume stock.
9. One request key cannot be reused for a different service code.
10. PostgreSQL direct mutation of accepted snapshot/event refused.

Existing R0A qualification suite includes 4 initial tests. **10 R0B tests + 4 R0A tests are not a complete 20+ case gate**; expand with `ACCEPT × CANCEL`, IAM suspension/revoke races, crash/retry, populated-schema migration, catalogue publish/deactivation race, H2/PG and full regressions.

## Gate

- QR-07 R2.5: PASS on separate exact SHA `6a06f8119f0d7959b1f49e371a719dec352b42e7`.
- QR-08 R0A/R0B: source committed; no claimed H2/PostgreSQL PASS.
- Current branch is subject to concurrent commits: exact-SHA evidence must match final candidate, and cancelled older runs do not count.
- Do not start R1 physical material consumption until R0 exact-SHA focused and whole-project PostgreSQL gates pass.
- No controllers/UI. External OIDC, scanners/HTTPS, real workplace acceptance remain production HOLD.
