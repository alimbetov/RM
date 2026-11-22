# RM-QR-03B — Stable Order QR & Verified Buyer Status

**Contract:** RM-QR-R1.4, UC-QR-06, BR-018/019/025/034 and QR-O01/O02.
**Branch:** base_dev only; no controllers, public URLs, scanner, payment or pickup authorization.
**Stage:** service-only candidate; exact-SHA PostgreSQL/H2 qualification required; PRODUCTION HOLD.

## Verified source context

- AutoOrderService.checkoutInternal creates PLACED using immutable seller-priced lines, IAM pending_email_identity_id, receipt and status event in a single database transaction; buyer_user_id stays NULL before fresh verification.
- AutoOrderService.verifyEmail verifies the token through global IAM and only then associates buyer_user_id with appropriate orders. Neither the purchaser's raw email nor knowledge of order ID/ref is proof of ownership.
- AuthenticatedBuyerOrders.history requires VerifiedActor and verified global email identity. QR-03B must preserve that boundary and must not require buyer membership of seller tenant.
- Seller permissions are defined by DomainPermission.SALES_ORDER_READ and TenantAuthorization; seller cannot read other tenants.
- sales_order is composite keyed (tenant_id,id); original order history owns total and line snapshots; QR is NOT a second order source of truth.

## New service and data model

**SALES-owned service:** SalesOrderQrReferenceService
- issueAtPlaced(tenant,orderId) MUST run inside the existing AUTOORDER transaction; no reference returned in the unverified email-first checkout response. It runs after existing order/receipt/email challenge writes and rolls back together with them.
- ownReference(VerifiedActor buyer,tenant,orderId) requires ACTIVE global user, CURRENT verified IAM email and buyer_user_id exact match. Reprints existing ACTIVE ref. For pre-QR legacy orders without any ref, creates the first generation only after ownership proof.
- readOwn(VerifiedActor buyer, publicRef) requires the same fresh identity admission, tenant-qualified order join and exact buyer_user_id check. It returns a sanitized order status timeline, immutable line prices, total, currency and timestamps only; never email, buyer UUID, raw IAM identity, storage key or confidential payload.
- readSeller(VerifiedActor seller,tenant,publicRef) and sellerReprint require fresh SALES_ORDER_READ admission in the declared tenant. Neither accepts a buyer UUID or bypasses tenant RBAC.
- rotateOwned(VerifiedActor buyer,tenant,orderId,expectedReferenceVersion) explicitly revokes old ref and creates new generation under an existing SALES order lock; old ref cannot read after commit. A rotation never changes the order, price, stock or receipt.

**Migration 0088:** crm.sales_order_qr_reference, PK(tenant,id), tenant+order FK, global UNIQUE(public_ref), UNIQUE(tenant,active_order_id) with null for revoked, UNIQUE(tenant,order_id,generation), state/version lifecycle constraints. Issuer is NULL for internally created PLACED (no fictitious staff actor); subsequent buyer rotations are audited.

**Trust semantics:** ORDER_REFERENCE is long-lived, random opaque NONSECRET locator. It does NOT authorize viewing without VerifiedActor, physical handover, refund, payment, delivery or privilege elevation. It does not reveal order status in an anonymous public HTTP lookup.

## Ordering, concurrency, immutable facts

- PLACED: checkout stripe → seller workspace/offer/catalog → IAM verified email provision → order/line/receipt/event → QR insert, one transaction; no new checkout uniqueness namespace.
- Buyer reprint/legacy issue/rotate: ACTIVE IAM user row admission → verified CURRENT email evidence → sales_order row FOR UPDATE → QR read/revoke/insert. Explicit race CAS on reference row_version.
- Seller read: TenantAuthorization.requireForCommand actor/workspace fence → sales_order row FOR UPDATE → lines/events; do not acquire IAM after locking SALES.
- Status reads lock the order before loading its lines/event history, preventing official seller status updates from interleaving between projection queries.
- No QR read or rotation changes existing stock reservations and no additional price snapshot is invented.

## Qualification scenarios

1. PLACED creates one QR even for standard checkout, but neither raw email nor unverified seller actor reveals it.
2. Verification creates appropriate buyer association; ownReference/readOwn return historical amount and sorted timeline with no PII.
3. A second verified buyer and a seller of another tenant cannot read or reprint a foreign order reference.
4. Current offer repricing does not mutate order snapshots; status/event projection tracks the existing history.
5. Buyer rotation denies old reference, creates one active generation and does not create a new order, stock movement or reservation.
6. Old pre-QR orders without an alias get one only after verification; two PostgreSQL sessions racing on that issuance create only one ref.
7. Buyer suspension disables access even if an older VerifiedActor object still exists.
8. Exact checkout replay returns the same order and therefore one attached ORDER_REFERENCE.
9. Must preserve all existing H2/PostgreSQL, SALES, INVENTORY, IAM, CRM, RustFS regression suites and pass exact-SHA manifest.

## Remaining decisions / deliberate exclusions

- Public ORDER_REFERENCE is currently only the raw opaque key; canonical trusted HTTPS route, QR graphics with central checkmark, mobile scanning and anti-abuse transport are NOT added.
- Real OIDC/JWKS browser E2E and physical scanner qualification remain separate gates. Unit/service tests mint synthetic verified actors through the existing IAM resolver; they are not external OIDC admission evidence.
- QR-04 multi-round quote bargaining/accepted personalized prices and QR-05/06 warehouse-specific authorization, READY transitions and one-time pickup tokens remain independently gated. Nothing in order QR alone authorizes stock fulfillment.
- Data retention, customer data export/deletion, CDN/ref cache and operational performance require separate review. No production-ready or shipment claim before independent gates.
