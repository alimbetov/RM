# RM-QR-04A/04B — Price Negotiation and Accepted Quote Checkout

**Source:** RM-QR-R1.4 canonical contract, UC-QR-04/05, BR-020..027.
**Branch:** base_dev only. **Status:** CODE CANDIDATE until exact-SHA PostgreSQL qualification. No controllers or public transports.

## QR-04A

- SALES-owned SalesPriceProposalService, global OIDC VerifiedActor buyer with ACTIVE verified email identity.
- Seller requires explicit tenant SALES_PROPOSAL_RESPOND RBAC; seller in tenant B cannot mutate tenant A.
- PRODUCT_OFFER public QR is only a locator; every new action validates current seller workspace, published STOREFRONT offer and published CATALOG product. No change to public list price.
- One seller offer, one buyer, one immutable quantity and currency. State machine: AWAITING_SELLER, AWAITING_BUYER, AGREED, REJECTED, WITHDRAWN, EXPIRED. Only the party opposite the last price author can accept/reject/counter; current proposer can withdraw.
- UAT CANDIDATE policy, NOT APPROVED production policy: maximum three counter events, 48h expiry; unit price strictly positive, BigDecimal scale <= 4. No invented discount floor.
- Revision/row_version compare-and-set, append-only one event per revision, immutable command receipts. Same seller-tenant request key and fingerprint returns historical operation result including accepted quote ID; changed payload or action conflicts.
- Successful acceptance creates one AVAILABLE accepted quote, in same transaction as AGREED event; negotiation never creates an order or INVENTORY reservation. Expiry is effective immediately from the server clock and an internal expireDue can materialize an EXPIRED event.

## QR-04B

- AutoOrderService.checkoutAcceptedQuote shares the existing crm.sales_checkout_lock and crm.sales_checkout_receipt with list-price and QR-list checkout. No second order creator.
- A buyer is verified by a CURRENT verified IAM email identity, OIDC-mapped ACTIVE global account, and exact quote buyer_user_id. Existing non-null pending_email_identity_id receives that real IAM identity ID, not null or a guessed email.
- Only AVAILABLE, unexpired, AGREED, same-tenant quote with active seller, storefront, offer and published PRODUCT creates a new PLACED order.
- Explicit buyer acknowledgement of accepted revision, quantity, price, currency; no client price override. Later seller list-price revision does not mutate the agreed amount; hidden offer or changed currency fails.
- One order line with price_source NEGOTIATED, immutable unit_price_snapshot and exact accepted_quote_id; single order status event, receipt, order QR and CONSUMED quote ID committed in one PostgreSQL transaction; no INVENTORY reservation at PLACED.
- Same request key and fingerprint retries yield the original order; distinct keys cannot consume the same quote twice. Cross-mode duplicate key is rejected.

## PostgreSQL / Liquibase

- 0089: SALES proposal, append-only revision event, accepted quote, persistent immutable command receipt; composite tenant FKs, guarded lifecycle and uniqueness.
- 0090: sales_order_line nullable price_source and accepted_quote_id with composite quote FK; legacy rows remain unaffected.
- 0091: PG-only UPDATE/DELETE rejection for proposal event and command receipt.

## Qualification

Mandatory: full proposal transitions and negative guards; fixed qty/currency, 3-counter limit; expire, reject, withdraw; seller and buyer isolation; exact idempotency replay; concurrent two-session accept and quote checkout; shared seller checkout key; immutable event and quote price; genuine IAM email owner; no inventory side effects; full H2 and real PostgreSQL regression; exact-SHA manifest. Scanner and public URL tests are separate.

**PRODUCTION HOLD:** pending explicit commercial approval of 48h/three counters, seller discount floor, real OIDC/browser, migration/load qualification, payment and pickup contracts QR-05/06.
