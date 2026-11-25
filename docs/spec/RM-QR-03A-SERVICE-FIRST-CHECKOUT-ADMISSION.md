# RM-QR-03A — QR Product Card to Authoritative Email-First Checkout

**Parent:** RM-QR-R1.4. **Branch:** base_dev.
**Status:** service-first implementation candidate, production HOLD; no new controllers or identity bypass.
**Scope:** PRODUCT_OFFER stable QR → seller slug + offer → existing AutoOrderService PLACED. Order reference/private buyer history and negotiated quote are separate QR-03B/QR-04 gates.

## Invariants

1. Anonymous QR card read creates no IAM identity or SALES order. Client selects integer qty 1..1000 and explicitly acknowledges current public price, currency and immutable offer row version at display time.
2. PublishedQrCheckoutService resolves the opaque public QR alias to seller slug and seller offer only; it does NOT create orders and never trusts a caller-supplied tenant, price override, warehouse or catalog ID.
3. AutoOrderService.checkoutPublishedQr is a separate entry into the SAME checkout engine as regular email-first checkout. This guarantees one seller, server-authoritative title/qty/unitPrice snapshot, existing mandatory pending_email_identity_id, status PLACED, immutable line + event, buyer verification outbox and no stock reservation at checkout.
4. The new overload's QrPriceAcknowledgement contains public ref, offer ID, expected row version, accepted unit price and currency. All are validated **after locking** existing checkout stripe → seller workspace → storefront → published offer/PRODUCT. The public ref must remain ACTIVE under the same store/offer locks, preventing a revoke or seller price update from silently replacing terms.
5. Changed rowVersion, price or currency leads to PRICE_RECONFIRM_REQUIRED before buyer provisioning, order/line/receipt/event/stock writes. Revoked alias for a NEW request leads to QR_UNAVAILABLE. Hidden offer or inactive seller remains blocked by existing checkout.
6. Same seller-wide crm.sales_checkout_receipt stores request keys for both modes. QR uses an operation/version/ref/normalized-price-aware SHA-256 suffix; legacy list checkout's existing request hash remains EXACTLY unchanged. Same key reused across QR/list modes or changed terms is a conflict; no duplicate order.
7. Exact authorized/idempotent QR checkout replay after QR revoke/price edit or seller pause can still read the previously accepted order receipt, because preliminary stable-ref lookup does not impose current publication. All NEW checkouts fail closed after existing seller locks.
8. Email-first PLACED is not a verified buyer or authenticated session. Private ORDER_REFERENCE and buyer-owned reads require independent IAM verification later; no buyer-as-seller tenant membership.
9. No second reservation or stock ledger, no paid/delivered claim, no SERVICE checkout or mixed negotiated basket, no HTTP controller and no direct price override in AUTOORDER.
10. Future UI must present reconfirm screen on PRICE_RECONFIRM_REQUIRED, not implicitly retry at another price.

## Qualification

- Acknowledged price and qty produce one PLACED, immutable unitPrice_snapshot and SKU snapshot, mandatory email identity, one receipt, one status event, no inventory reservation.
- Seller price changes after card read: stale acknowledgement rejects with zero new buyer identity/order side effects; latest confirmed price can create PLACED.
- Revoke after successful checkout: exact requestKey+terms reads the same order ID, new key denied.
- QR and normal checkout cannot reuse the same seller request key for different operation context.
- qty 0/>1000, foreign/missing alias, wrong currency, hidden offer fail closed.
- Two concurrent real PostgreSQL same-key requests produce one order and receipt. H2 is not proof of concurrency.

**Limits:** The QR-HTTP web/mobile flow, live browser, email delivery and own-order QR remain HOLD. The same-source atomic price/admission check is delivered by AutoOrderService, not by a preliminary public-card read.
