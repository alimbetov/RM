# RM-QR-R1.3 — Implementation Blueprint & Evidence Contract

**Document class:** нормативное инженерное приложение к RM-QR-R1, SPEC ONLY.
**Date:** 2026-11-22. **Target branch:** base_dev. **Source audit:** 8f5db620a9d220ed5688a908372246d6c37e6e8f and reinspection at R1.3 authoring.
**Delivery status:** NONE — no new Java code, SQL migration, endpoint or tests in this documentation change.
**Production:** HOLD. **No new controllers / no microservices / no main merge / no new branch**.
**Parent specification:** RM-QR-R1-ALIGNMENT-BUSINESS-FLOWS-STATE-MACHINES-DTO-QA.md.
**Other normative sources:** RM-PLATFORM-STANDARD-R2.md; RM-COMMERCIAL-GEO-REGION-QR-FULFILLMENT-R1.md; RM-STOREFRONT-AUTOORDER-R1.md; RM-COMMERCE-INVENTORY-COMPLETION-R1.md; RM-INVENTORY-FUNCTIONAL-COMPLETION-R1.md; RM-FILES-R1.md; RM-INSIGHT-R1-OPERATIONAL-ANALYTICS-PERFORMANCE.md; RM-STRATEGY-R4-FUNCTIONAL-GAP-PRIORITIZATION.md.

**Document precedence:** architecture standard R2 + actual latest code/schema + already-accepted business invariants > parent QR-R1 specification > this detailed R1.3 implementation contract for implementation choices. If two written requirements conflict, annotate it in QR-00 discrepancy register, block that affected slice and resolve by explicit change of one source; do not silently substitute.

## 1. Source-to-target delta: concrete non-implemented features and verification anchors

| Source/method/table | Verified current behavior | Required change; prohibited assumption |
|---|---|---|
| AutoOrderService.checkout(slug,email,idempotencyKey,List<Line>) | Existing email-first order creation, per offer integer qty 1..1000, up to 100 submitted lines; merge duplicate offers; one seller; validate PUBLISHED PRODUCT; server-owned price; status PLACED; checkout receipt + email queue in transaction | QR alias is a new **read/admission adapter** to existing semantic checkout, not an additional public order ledger. Explicit buyer acceptance of displayed price version must be a separately qualified contract; current method does not receive expectedPriceVersion |
| AutoOrderService.verifyEmail | Email challenge proof, pending buyer account may precede verified ownership | Public QR card needs no identity. Buyer-owned order history/proposals require verified buyer proof and verified association; PLACED creation must not imply verified owner |
| StorefrontOfferService | PUBLISHED/PAUSED/HIDDEN, seller-controlled offer price revisions, CAS, offer linked to PRODUCT | Create stable public offer alias without changing offer identity. All public reads revalidate store, offer and CATALOG publication. STOREFRONT_OFFER_* permissions are currently mapped to DomainModule.SALES in DomainPermission; do not relabel registry automatically |
| FileAttachmentService | CRM_CUSTOMER owner, FILES/CRM read policy; cannot serve arbitrary product gallery | New typed CATALOG_ITEM media owner policy, safe public variants and no CRM bypass. Before proof, card displays placeholder instead of leaking file bytes |
| SalesInventoryCommandService.confirm | PLACED → CONFIRMED and reserve all lines on one locationId, dual SALES_ORDER_CONFIRM + INVENTORY_RESERVE, order lock | Add immutable warehouse origin snapshot only on first successful confirm, not on checkout; preserve original lock order |
| SalesInventoryCommandService.fulfill | Only CONFIRMED/PREPARING → COMPLETED, full reserve fulfillment, dual permits | Not a physical buyer handover proof. QR pickup requires new READY-only facade/receipt and compatibility testing, without silently changing old service semantics |
| InventoryReservationService.fulfill | Allows READY_FOR_PICKUP at reservation level; status + stock locks, physical on_hand debit and reservation consumption | Resolve difference with SALES ready guard by qualified new orchestration; do not bypass OrderInventoryPort |
| SalesInventoryCommandService.cancel | READY_FOR_PICKUP explicitly rejected | No-show cancellation is not implemented; no TTL-triggered auto-release |
| StockEligibility | PostgreSQL FOR SHARE location/item owner locks, WAREHOUSE ACTIVE + PRODUCT admissibility | GPS edit and confirm must see one owner revision in same transaction; preserve warehouse close/reconciliation races |
| OrganizationLocationService.update | KATO change blocked by stock/history, CAS on location, no GPS columns | Separate GPS pair update through ORG authenticated mutation while retaining KATO restrictions; no fake coordinates |
| public.org_location | workspace_id, type, KATO, address, status, version | Add nullable WGS84 latitude/longitude and source/verification metadata |
| crm.sales_order | status CHECK includes PREPARING, READY_FOR_PICKUP, OUT_FOR_DELIVERY, COMPLETED | Schema status value does not prove command or UI. New snapshot fields additive and nullable for legacy |
| crm.sales_order_line | quantity INTEGER; unit_price_snapshot NUMERIC(19,4); catalog_item_id_snapshot | New price_source and accepted_quote_id additive. Do not change quantity type as part of QR |
| crm.inv_stock_balance | on_hand/reserved NUMERIC(19,4) tenant+location+item; CHECK on_hand >= reserved >= 0 | Remains sole current authoritative availability; QR never updates it directly |
| crm.sales_checkout_receipt | idempotency key unique per seller tenant + request SHA | Quote checkout must preserve this cross-pod idempotency quality; no dual source of receipt identity |
| KATO reference | Partial source snapshot, incomplete ancestry | Exact code matching R1, no guessed parent/child by code prefix |
| DomainPermission | No SALES_PRICE_NEGOTIATE / SALES_PICKUP_PREPARE / SALES_RETURN_* currently | Add explicit permissions only in relevant service stage, role matrix, fail-close default and PostgreSQL negative tests |

**Hard acceptance wording:** all unimplemented lines above are target functionality, not code status. Documented SQL/table names are proposals until checked against the current migration during QR-00.

## 2. Business outcome, bounded capabilities, and explicit deferrals

R1 should produce one end-to-end product path whose behavior is independently reproducible: seller publishes product → prints product QR → buyer opens public card/locale/photo/region → chooses integer qty → list-price checkout or negotiated quote checkout → obtains buyer-owned order status → seller selects active warehouse → full reserve → staff prepares/marks ready → buyer receives a one-time credential → authenticated staff confirms physical handover → one immutable receipt and one set of inventory debits.

For completeness of the entire vision, explicitly catalog deferred processes: return with inspected condition and typed stock receipt (R1.2 delivery), services by appointment/provider capacity (R2), QR packages/multi-parcel/partial order (R2), shipment/courier/POD (R2), warehouse bin labels, stocktake, transfer, multi-warehouse splitting (R2). They are **not** prerequisites to declaring the narrower QR-product R1 vertical qualified. They must not be described as supported just because a QR purpose enum exists.

## 3. Actors and trust boundaries: no buyer-as-seller, no QR-as-auth

**Anonymous visitor:** can resolve an active published offer QR and read sanitized PRODUCT card. Cannot submit bargaining as a verified buyer, read private orders, see raw stock, scan pickup as staff, or access unpublished FILES. Anonymous availability is advisory.

**Email-first buyer:** current AutoOrderService may create a PLACED record and pending email identity. This is not a fully verified principal. Order details, negotiated proposal, accepted quote consumption and issue/reissue pickup credential require a verified global buyer association. Do not convert email provided in checkout to trusted seller tenant membership.

**VerifiedBuyer:** proposed explicit authenticated identity port with global userId, verified email/active status, and own-order association; it is **not** TenantAuthorization.Decision and is not seller-staff permission. Separate ownership check in SALES uses authoritative verified identity mapping.

**Seller staff:** VerifiedActor + selected active seller tenant, active membership and concrete seller permission from DomainPermission; seller can manage his offer and handle allocated sales. Seller permissions do not imply CRM_PII_READ.

**Warehouse staff:** VerifiedActor + selected seller tenant + selected warehouse + authenticated grants SALES_ORDER_FULFILL and INVENTORY_FULFILL + object-scoped warehouse assignment. Existing generic tenant RBAC does not imply an employee may work in any warehouse. Multi-warehouse production remains HOLD until this object policy is approved, implemented and proven.

**Service identity:** future scheduled expiry/recovery handlers run under explicit bounded service identity and tenant context; never fabricate OWNER/PLATFORM_ADMIN. Sensitive actions have separate immutable audit without raw token.

## 4. Identifier registry, lifecycle and privacy contract

| Purpose | Owner | Identifier contents | Allowed lifecycle | Authorization consequence |
|---|---|---|---|---|
| PRODUCT_OFFER | STOREFRONT | Versioned stable opaque random public_ref mapped to tenant+offer | ISSUED/ACTIVE/REVOKED/REISSUED | None; public sanitized offer only when PUBLISHED |
| STOREFRONT | STOREFRONT | Store public slug or stable alias | PUBLISHED/PAUSED | None; only public offers |
| ORDER_REFERENCE | SALES | Stable opaque non-secret public_ref to tenant+order | ACTIVE/REVOKED | Cannot reveal private order without buyer proof or seller read permission |
| PICKUP_CREDENTIAL | SALES | Opaque version+credential locator+random secret, purpose-bound | ISSUED/CONSUMED/REVOKED/expired by server time | Secret only proves token presentation; staff IAM and warehouse matching mandatory |
| RETURN_REFERENCE | SALES | Opaque RMA reference, not bearer return authorization | ACTIVE/REVOKED | Return case requires owner/staff policy |
| SERVICE_OFFER | STOREFRONT | Nonsecret offer ref for service card | Public read, checkout HOLD | No SERVICE reservation |
| SERVICE_CHECKIN | SALES later | One-time purpose-bound proof | ISSUED/CONSUMED/etc | Service resource/actor policy |
| PACKAGE_LABEL | SALES later | Package/fulfillment-group ref | Created/packed/handover etc | Does not debit stock by scan |
| DELIVERY_HANDOFF | SALES later | Separate courier or recipient proof | Delivered process | Not the same as warehouse pickup |
| WAREHOUSE_LOCATION | ORGANIZATION/INVENTORY later | Nonsecret warehouse/bin ref | Current location state | Must check staff warehouse scope |
| STOCKTAKE_ITEM | INVENTORY later | Stocktake session and SKU ref | Current stocktake state | No adjust without explicit inventory command |
| TRANSFER_BATCH | INVENTORY later | Transfer ref | Existing transfer lifecycle | No stock move by reference alone |

For permanent public refs: minimum 128-bit random entropy, globally UNIQUE ref so seller tenant need not be supplied by anonymous user. Store the literal public_ref because reprint must yield exactly the same URL; treating it as a secret hash-only identifier would make reprint impossible. It is never sufficient for private authorization.

For pickup: 192–256-bit CSPRNG token preferred, purpose and token version bound, digest from keyed HMAC/approved KMS rotation, raw proof in successful issuance response **once only**, never in DB/log/audit; expiry enforced by server clock at confirm, not by stale persisted state. Offline consume forbidden. A short numeric recovery PIN is not an equivalent proof unless independently designed with rate limit, entropy and recipient verification.

**No generic qr_payload table:** each owner domain knows its object identity and lifecycle. A visual PNG/SVG is a rendering artifact, never the authoritative credential record. Product QR graphic with central circle/check is cosmetic, scan-qualified, not an assurance label.

## 5. Seven independent state spaces: exact guards and prohibitions

### 5.1 Published offer and QR alias

Offer state comes from current STOREFRONT: DRAFT → PUBLISHED; PUBLISHED → PAUSED/HIDDEN; PAUSED/HIDDEN can be republished according to current service. Public QR does not set this state. QR alias: ABSENT → ACTIVE; ACTIVE → REVOKED; REISSUE creates a **new** ACTIVE generation. REPRINT does not create a new generation.

Public resolution must return generic unavailable if store not PUBLISHED OR offer not PUBLISHED OR SKU not PUBLISHED PRODUCT OR alias REVOKED. If valid, return current price/description/gallery and optional advisory stock band. Product QR must not expose exact private stock balance or seller-internal warehouse coordinate.

### 5.2 Negotiation state machine R1

**Proposed enum:** AWAITING_SELLER, AWAITING_BUYER, AGREED, REJECTED, WITHDRAWN, EXPIRED. Every proposal revision records a current proposed unit price, fixed integer qty and currency, last_sender and next_actor role. A seller can accept buyer's offer only when AWAITING_SELLER; a buyer can accept seller's counter only when AWAITING_BUYER. Accepting own last offer is prohibited.

| From | Command | Preconditions | Next | Event |
|---|---|---|---|---|
| ABSENT | submit buyer | Verified buyer, PUBLISHED offer, qty 1..1000, valid currency & unitPrice, rate limit | AWAITING_SELLER rev1 | PROPOSAL_SUBMITTED |
| AWAITING_SELLER | sellerCounter | Tenant permission, expectedRev, time before expiry, maxRounds | AWAITING_BUYER rev+1 | SELLER_COUNTERED |
| AWAITING_SELLER | sellerAccept | Seller permission, accepts exact buyer last revision | AGREED and one immutable AcceptedQuote | PROPOSAL_AGREED |
| AWAITING_SELLER | sellerReject | Exact rev | REJECTED | PROPOSAL_REJECTED |
| AWAITING_SELLER | buyerWithdraw | Own buyer before terminal | WITHDRAWN | PROPOSAL_WITHDRAWN |
| AWAITING_BUYER | buyerCounter | Verified original buyer, exact rev, maxRounds | AWAITING_SELLER rev+1 | BUYER_COUNTERED |
| AWAITING_BUYER | buyerAccept | Original buyer accepts seller last revision | AGREED and one AcceptedQuote | PROPOSAL_AGREED |
| AWAITING_BUYER | buyerReject | Original buyer exact rev | REJECTED | PROPOSAL_REJECTED |
| ANY waiting | read/command at now >= expiresAt | Server clock | EXPIRED treated as effective terminal; persistence may be lazy | PROPOSAL_EXPIRED once if persisted |
| ANY terminal | counter/accept | Always forbidden | No transition | None |

Quote states: AVAILABLE, CONSUMED, EXPIRED, INVALIDATED. An already CONSUMED quote remains tied to its original PLACED even if it would later expire; no second order may use it. Agreement is a negotiated offer, **not** a payment or stock guarantee.

### 5.3 Product order, reservations and pickup

| From | Action | Required role and proof | To | Inventory |
|---|---|---|---|---|
| ABSENT | list/quote checkout | Buyer accepts exact server-owned qty/price | PLACED | None |
| PLACED | confirm | SALES_ORDER_CONFIRM + INVENTORY_RESERVE; selected ACTIVE tenant WAREHOUSE, full stock | CONFIRMED | ACTIVE reservation per line |
| CONFIRMED | startPreparing | Authorized warehouse preparer; full order reservation | PREPARING | No debit |
| PREPARING | markReady | Full line pick check, same warehouse and status version | READY_FOR_PICKUP | Reserved unchanged |
| READY_FOR_PICKUP | issue pickup credential | Verified buyer/authorized recipient | READY_FOR_PICKUP | No debit |
| READY_FOR_PICKUP | confirm handover | Two seller staff permissions + warehouse scope + valid one-time proof + explicit human confirmation | COMPLETED | Fulfill all lines once |
| PLACED/CONFIRMED | cancel | Existing permitted cancellation commands | CANCELLED | Release active reservations if present |
| READY_FOR_PICKUP | no-show cancel | HOLD, separate approved cancellation & release contract | None in R1 | None |
| COMPLETED | return requested | Separate RMA | Remains COMPLETED | No automatic increment |
| CONFIRMED/PREPARING | legacy fulfill | Existing internal stock fulfillment (not pickup proof) | Existing COMPLETED | Existing movement path |

**Existing inconsistency to close:** SalesInventoryCommandService.fulfill currently rejects READY_FOR_PICKUP, whereas InventoryReservationService.fulfill supports it. New QR pickup orchestration must not simply change existing method to accept READY until receipt/token and complete rollback tests exist.

### 5.4 PickupCredential and RMA

Pickup proof ISSUED → CONSUMED (same atomic transaction as handover) or REVOKED (reissue/cancel) or effective EXPIRED at server now; terminal states cannot be revived. Only one active ISSUED per order and purpose; reissue serializes on order lock and revokes prior token generation before insertion.

RMA REQUESTED → AUTHORIZED/REJECTED; AUTHORIZED → RECEIVED; RECEIVED → INSPECTED; INSPECTED → RESTOCKED, QUARANTINED, or REJECTED per line. Mixed-quality returns require line-level disposition, not a single all-or-nothing blanket status. Return inspection **does not** set original order back to PLACED or trigger payment refund.

## 6. Contract-level commands, results, preconditions and redaction

These are proposed Java package-level service contracts; names can be adjusted during QR-00 but behavior/guards must remain. No controllers.

| Package / use case | Method command (semantic fields) | Result | Required protections |
|---|---|---|---|
| storefront PublishedOfferQrService.issueOrGet | VerifiedActor, tenant, offerId, expectedOfferVersion, requestKey | PublicOfferQrRef(publicRef,generation,issuedAt) | Seller grant, stable alias, CAS, publication |
| storefront PublishedOfferQrService.reprint | VerifiedActor, tenant, offerId | Same PublicOfferQrRef | No new alias, safe even after price change |
| storefront PublishedOfferQrService.revoke | VerifiedActor, tenant, publicRef, expectedVersion | RevokedRef | Seller grant, no reuse, audit |
| storefront PublishedOfferCardQuery.find | publicRef, locale, regionKato? | PublicOfferCardDto | Current publication allowlist, no private details |
| catalog PublishedProductDetailsPort.get | tenant, publishedItemId, locale | LocalizedProductDto | Published owner and locale fallback |
| files CatalogItemPublicMediaPort.list | tenant, itemId, boundedPage | List<PublicImageDto> | Clean+approved+owner/published, no private bytes |
| inventory RegionalPublicAvailabilityPort.summary | sellerTenant,itemId,exactKato | AvailabilityDto(band,asOf) | Live warehouse only, no promise/reserve |
| sales BuyerProposalService.submit | verifiedBuyer, publicOfferRef, qty, unitPrice, currency, expiry, key | ProposalDto | Buyer verified, offer eligible, no side-effect in stock |
| sales SellerProposalService.counter/accept/reject | VerifiedActor, tenant, proposalId, expectedRev, optionalAmount, key | ProposalDto or AcceptedQuoteDto | Explicit negotiate permission; CAS; seller owner |
| sales BuyerProposalService.counter/accept/reject | verifiedBuyer, proposalId, expectedRev, optionalAmount, key | ProposalDto or AcceptedQuoteDto | Same verified original buyer |
| sales AcceptedQuoteCheckoutService.checkout | verifiedBuyer, quoteId, buyerConfirmedRev, key | CheckoutResult(orderId,PLACED) | One consumed quote, server-owned price, current seller eligibility |
| sales OrderReferenceService.issueOrGet | trusted order creation context, orderId | OrderPublicRefDto | Stable nonsecret lookup, no private read |
| sales OrderReferenceQuery.readOwn | VerifiedBuyer or VerifiedActor, publicOrderRef | SanitizedOrderDto | Buyer ownership or seller SALES_ORDER_READ |
| sales PickupPreparationService.start/markReady | VerifiedActor,tenant,orderId,warehouse,expectedOrderVersion,key | OrderTransitionReceipt | Correct reservation set, object scope, status CAS |
| sales PickupCredentialService.issue/revoke | VerifiedBuyer, orderRef, reissueKey | PickupCredentialDto(rawProof only at issue, expiresAt) | Buyer proof, row-lock generation, digest storage |
| sales PickupHandoverService.preview | VerifiedActor, selectedTenant,warehouse,presentedProof | PickupPreviewDto | Permission and object scope, zero stock side effects |
| sales PickupHandoverService.complete | VerifiedActor, selectedTenant,warehouse,proof,explicitConfirm,expectedOrderVersion,key | HandoverReceiptDto | Full atomic commit; no double debit |
| sales ReturnRequestService.create/authorize/inspect | verified actor, order/line IDs, qty, reason/quality, version,key | ReturnRequestDto | Complete origin proof and line qty ceiling |
| inventory InventoryReturnPort.acceptSellable | Trusted typed RMA receipt with actor/tenant/warehouse/qty | Immutable stock posting | Original movement provenance, once-only receipt |

**DTO rules:** all amounts BigDecimal with server arithmetic, NUMERIC(19,4), no currency conversion; qty integer 1..1000 for current product checkout. One order currency and one seller. Locales kk/ru/en/zh-Hans with explicitly labelled fallback. Stable bounded pages, at most 100 entries per page; no unbounded raw asset lists. Source IDs are not proofs.

**PublicOfferCardDto:** publicOfferRef, displayName, name/description, locale, approved images (safe variant refs, alt and sort), unitPrice, currency, UOM or UNKNOWN, publication and stock bands, stockAsOf, selectedKato, canOrder and canPropose; no raw tenant internal IDs, internal stock or secret URL. Availability UNKNOWN when source cannot be trusted. Until UOM contract exists, do not silently advertise fractional meters.

**Proposed PriceProposalDto:** proposal public-safe ID, offer, owner-specific currentUnitPrice/listPriceAtSubmit, currency, integer qty, revision, state, nextActor, deadline, history summary; seller and buyer views apply distinct object scopes.

**PickupPreviewDto:** limited order reference, line names and quantities, warehouse label, READY status, canHandover; no PII or raw proof echoed. Preview cannot grant or reserve a later complete call. HandoverReceiptDto includes immutable receipt ID, order ID, warehouse, event timestamp, line count and event reference — not invoice/paid/delivery status.

## 7. Field-level PostgreSQL design and constraints

**All definitions here are proposals, not migrations already applied.** Tenant columns ALWAYS participate in FK/queries. Actual naming and SQL must be checked in QR-00 against current Liquibase schema and existing constraints. Preserve existing tables and ledger.

### 7.1 STOREFRONT

Proposed crm.store_offer_qr_ref:
- tenant_id UUID NOT NULL, id UUID NOT NULL; PK(tenant_id,id).
- offer_id UUID NOT NULL; FK(tenant_id,offer_id) → crm.store_offer(tenant_id,id).
- public_ref VARCHAR(64) NOT NULL globally UNIQUE. Random 128+ bits, stable literal because it is NOT a secret.
- generation INTEGER NOT NULL CHECK >=1, state VARCHAR(16) ACTIVE/REVOKED, row_version BIGINT NOT NULL.
- issued_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ NULL, issued_by UUID.
- Partial UNIQUE(tenant_id,offer_id) WHERE state='ACTIVE'. Reissue revokes old generation and creates fresh ref in one transaction.
- Public resolver first finds alias by global UNIQUE public_ref, then tenant-qualified joins through store_front/store_offer/CATALOG with current PUBLISHED conditions. No private rows returned.

### 7.2 SALES — bargaining

Proposed crm.sales_price_proposal: tenant_id,id as composite PK; offer_id, catalog_item_id with composite tenant FKs; buyer_user_id global IAM FK; quantity INTEGER CHECK 1..1000; list_price_at_submit NUMERIC(19,4); current_unit_price NUMERIC(19,4) CHECK >0; currency CHAR(3); state, revision BIGINT, next_actor, rounds, expires_at TIMESTAMPTZ, created_at/updated_at, row_version. Buyer email is global IAM-sensitive, **do not duplicate plaintext email**.

Proposed crm.sales_price_proposal_event: tenant_id,id,proposal_id,revision,actor_reference/role,action,price,quantity,currency,occurred_at; PK tenant/id; FK tenant/proposal; UNIQUE(tenant,proposal_id,revision); append-only.

Proposed crm.sales_accepted_quote: tenant_id,id,proposal_id,agreed_revision,buyer_user_id,offer_id,qty,agreed_unit_price NUMERIC(19,4),currency,expires_at,state,consumed_order_id NULL,created_at; PK(tenant,id); composite FK to proposal and eventual sales_order; UNIQUE(tenant,proposal_id,agreed_revision), UNIQUE(tenant,consumed_order_id) excluding NULL; guarantee single use even with two buyer devices.

Proposed crm.sales_order_line additions: accepted_quote_id UUID NULL with tenant-qualified FK; price_source enum LIST/NEGOTIATED nullable for legacy. All old price/amount snapshots remain immutable and untampered.

### 7.3 SALES — order reference, pickup secret and immutable receipt

Proposed crm.sales_order_qr_reference: tenant_id,id,order_id,public_ref globally UNIQUE, generation,state,issued_at,revoked_at; tenant order FK, stable literal public ref for reprint; no bearer access to order details.

Proposed crm.sales_pickup_credential: tenant_id,id,order_id,warehouse_id,credential_digest BYTEA, key_generation,issued_at,expires_at,consumed_at,revoked_at,state,row_version. PK tenant/id, tenant-qualified order/warehouse FK. UNIQUE active issued credential per tenant/order. Expiry must be evaluated with server time, not only scheduled state; issuance revokes any stale ISSUED row in same locked transaction. Raw secret not stored; include nonsecret credential locator in encoded proof for indexed lookup without exposing order details.

Proposed crm.sales_pickup_handover: tenant_id,id,order_id,warehouse_id,credential_id,staff_actor_id,request_key,request_sha256,happened_at,status_event_id. PK tenant/id; UNIQUE(tenant,order_id) for **R1 full-order handover**, UNIQUE(tenant,request_key), composite FKs. Never overwrite successful receipt. Multiple parcels or partial handover would require new R2 key and ledger modeling, not relaxing unique silently.

### 7.4 GEO + SALES origin snapshot

public.org_location proposed nullable latitude NUMERIC(9,6) CHECK -90..90, longitude NUMERIC(9,6) CHECK -180..180, coordinate_source, coordinates_updated_at, geo_verified_at; paired NULL CHECK. Existing location row_version and KATO update restrictions preserved. Do not invent GPS coordinates from KATO centroid.

crm.sales_order proposed fulfillment_warehouse_id UUID, warehouse_kato_snapshot VARCHAR(16), warehouse_address_snapshot VARCHAR(500), warehouse_latitude_snapshot/warehouse_longitude_snapshot NUMERIC(9,6), warehouse_snapshot_captured_at TIMESTAMPTZ. FK(tenant_id,fulfillment_warehouse_id) → public.org_location(workspace_id,id). New successful confirm writes all source metadata from one owner revision; legacy CONFIRMED/COMPLETED with NULL treated UNKNOWN_LEGACY, not silently backfilled or rejected by a global historical CHECK.

### 7.5 FILES and returns

FILES uses existing file_object/storage/attachment; new typed CATALOG_ITEM owner access, publicly approved sanitized thumbnail/medium variants, CLEAN/AVAILABLE gate, publication conditions. No direct presigned private storage URL in public DTO, no CRM_CUSTOMER owner bypass.

RMA proposed sales_return_request, sales_return_line, sales_return_event; link original tenant order and fulfilled order lines, typed quality, source movement and accepted qty. New INVENTORY RETURN_RECEIPT can increase **sellable** on_hand only after verified INSPECTED sellable qty; damaged/uninspected/quarantined do NOT increase sellable available. Quarantine stock policy requires separate explicit data-model approval, not direct receive to ordinary ACTIVE warehouse balances.

### 7.6 Constraints, keys and populated upgrade

- Every tenant-owned table tenant-first PK and composite FK; beware global buyer IAM user FK legitimately does NOT have tenant ID. No generated duplicate catalog, order, warehouse or stock balance tables.
- All mutable state rows with expectedVersion/CAS; immutable events and receipts append-only. Validate nullable fields, length, amount range/precision, paired coordinates and selected enum values in both Java and PG.
- All new changesets additive. Validate orphan/duplicate conditions and index build/lock cost on **populated PostgreSQL** with synthetic realistic fixtures. Before full success, preserve old checkout and stock path.
- Existing Liquibase changesets cannot be rewritten. After accepting real receipts, no destructive DROP rollback; use feature disable + corrective forward migration with audit.
- Partial constraints may need staged NOT VALID validation. Apply strict NEW-row checks in service first, migrate/mark legacy, then tighten global constraints after independently validated data. Never invent historical origin.

## 8. Lock graph, transaction protocols and concurrency proof

**Immutable lock order existing checkout:** checkout key stripe → IAM workspace status lock → store_front → store_offer → cat_item. Already implemented in AutoOrderService. Negotiated checkout must retain this order while ensuring quote is locked against concurrent consumption; QR-00 must produce a full directed lock DAG including proposal/quote and email identity stripe before implementation. Do not acquire quote lock first and then storefront lock absent deadlock proof.

**Immutable lock order existing inventory:** IAM actor/workspace admission → sales_order row FOR UPDATE → deterministic reservation request stripes and owner location/item guards → stock balance row FOR UPDATE → inv_stock_reservation row. StockEligibility requires PostgreSQL FOR SHARE on org_location and cat_item; GPS edits/warehouse closure take conflicting row UPDATE. Avoid reordering through RMA/pickup.

### 8.1 First confirm and origin capture

1. Fresh verified staff admission: SALES_ORDER_CONFIRM and INVENTORY_RESERVE for same tenant.
2. Lock order row. Require PLACED and immutable order lines in deterministic order.
3. Lock/check selected ACTIVE warehouse and PRODUCT owner per existing InventoryPort/StockEligibility.
4. Reserve all lines on one warehouse; fail any line → whole transaction rollback, not partial reserved.
5. In the **same transaction**, read selected org_location tenant+warehouse under a compatible row lock to obtain a consistent KATO/address/GPS revision and store full immutable snapshot.
6. Update order status CONFIRMED and append status event in same commit; idempotent repeat same warehouse leaves snapshot untouched.
7. If ORG GPS edit races confirm, results are fully old or fully new owner revision, never mixed old KATO and new coordinates.

### 8.2 Pickup proof issue/reissue

1. Trusted buyer proof, authoritative own-order relation.
2. Lock sales_order row; require READY_FOR_PICKUP, fulfillment_warehouse_id known, all reservations ACTIVE and full-line.
3. Revoke any current ISSUED credential (including expired but not yet persisted as EXPIRED), then generate new CSPRNG proof + store digest/purpose/key generation/expiry in same transaction.
4. Return raw token only once to trusted caller after commit. For lost response, do not attempt to retrieve raw token from DB; reissue securely with new proof.
5. Two simultaneous reissue calls on one order serialize; at most one active ISSUED token. A successful handover or cancellation prevents a future issue.

**Failure mode:** a client timeout after commit makes the raw token unrecoverable. The recovery policy is revoke+reissue with buyer proof, never plaintext-token persistence. This tradeoff is intentional.

### 8.3 Complete physical pickup

1. Resolve selected tenant and warehouse from authenticated staff context (not QR).
2. Require fresh paired SALES_ORDER_FULFILL + INVENTORY_FULFILL admission and approved warehouse object scope.
3. Lookup nonsecret credential ID/key generation to locate order under restricted tenant ownership without returning details, then lock order row.
4. Under order lock, lock credential row. Verify constant-time keyed digest match, purpose PICKUP, server clock < expiresAt, ISSUED, not revoked/consumed, same warehouse as immutable origin.
5. Require READY_FOR_PICKUP and full ACTIVE reservations matching exactly each order line, same warehouse, no service line. Require explicit staff confirmation **after** preview.
6. Fulfill all stock lines by existing OrderInventoryPort in deterministic order; write immutable handover receipt, change order to COMPLETED, append status event, and mark credential CONSUMED all in **one transaction**.
7. Commit; only then notify/print receipt through durable notification process. Any failure rolls back all movement, reservation, status, receipt and credential state.
8. If same key+same payload arrives after successful commit, re-authorize and read existing receipt without second debit. Same key with changed payload returns IDEMPOTENCY_CONFLICT.
9. If a different request tries reused proof, return ALREADY_HANDLED/invalid generically without stock movement or leaking owner identity.

**Never** claim a generated QR or a staff preview proves physical delivery. If physical goods are handed over but operator loses network before commit, use controlled incident reconciliation (independent evidence and supervisor approval), not arbitrary SQL UPDATE to mark completed. Offline stock commit is forbidden R1.

### 8.4 Concurrency scenario contract

| Pair | Required serializing point | Exact allowed outcome |
|---|---|---|
| Offer price update × checkout | Existing offer lock; price acknowledgement | Old agreed price or explicit PRICE_RECONFIRM_REQUIRED, no silent reprice |
| Counteroffer × accept same revision | Proposal row CAS | One winner, one event, one AGREED max |
| Accepted quote checkout × checkout | Quote lock and unique consumed_order_id | Exactly one PLACED |
| Offer paused × accepted quote checkout | Storefront/offer locks | Eligible before pause or rejection after |
| GPS update × confirm | Org location row lock and order transaction | One consistent location revision |
| Warehouse close × reserve | StockEligibility owner lock | No reserve in CLOSED |
| Pickup token reissue × reissue | Order row + credential lock | One issued proof generation |
| Pickup reissue × handover | Same order row order | Either valid issued proof is consumed or reissue invalidates older proof |
| Handover × handover | Order/credential lock + receipt uniqueness | One COMPLETED, one movement set |
| Staff revoke × handover | IAM admission/revocation fences | Two commit orders, no unauthorized side effects |
| Proof expires between preview and confirm | Server-time check at commit command | Denied/no movement |
| RMA receipt × receipt | RMA line row lock + unique source receipt | No double stock increase |

Independently verify the negative case: order, status event, inventory balance/reservation/movement, quote, credential, receipt, buyer association and seller permission remain unchanged except approved security-attempt telemetry.

## 9. Transactional contract for price proposals and negotiated checkout

**Money:** BigDecimal, authoritative currency from offer, no implicit FX and no rounding that changes the agreed amount. current PRICE NUMERIC(19,4) and integer qty; compute BigDecimal total exactly and ensure it fits NUMERIC(19,4) before INSERT. Seller zero-price offer is possible under current schema (unit_price >= 0); R1 negotiated offer proposal defaults to >0 subject to explicit seller discount policy. Do not generalize one policy to force public price >0 without an approved product change.

**Proposals:** optimistic CAS revision, one turn at a time, one sender per revision; acceptedQuote unique by proposal+accepted revision. Buyer identity is verified global IAM, seller tenant comes from offer. Never write raw email into proposal payload. Limits on rounds/deadline/price bounds are a configuration policy requiring approved values, not hardcoded incidental choices.

**Quote checkout:** new internal orchestration, preserving existing checkout tenant/idempotency/email-safety guarantees. Quote is server-owned, buyer-bound, not a bearer discount coupon. Recheck eligibility and seller active status; quote expiry checked from server time. Update quote CONSUMED + create PLACED/order lines/status events/receipt atomically. PriceSource NEGOTIATED and accepted_quote_id stored on exact line. Buyer must affirm final unit price, qty, currency; if requested qty differs, start new negotiation.

**Do not** use request-submitted negotiatedUnitPrice as an override in AutoOrderService. If offer price changed after AGREED but offer remains eligible, quote retains agreed price until expiry according to policy, while the public card still shows current price.

## 10. FILES and geo contract — no premature claims

**Media ownership:** existing FileAttachmentService is CRM_CUSTOMER-specific. Public product gallery requires a new CATALOG_ITEM owner permission path, safe media pipeline and verified publication join. Expose only storage variants sanitized of embedded private EXIF/location and approved as AVAILABLE+CLEAN. Revoking publication must invalidate further public media reads (signed URLs and CDN TTL must have a bounded revocation policy). No public direct storage key.

**Regional availability:** exact KATO matching R1 because the KATO source snapshot is partial. Public result is a band and timestamp, not full on_hand/reserved or a commitment to deliver. Search is by seller-owned published offer and ACTIVE eligible stock of the same tenant, never merges the same SKU text across sellers. Distance and ETA depend on future GPS/routing/delivery-zone contracts; warehouse GPS at CONFIRMED is historical origin, not buyer destination.

**Services:** reusable visual QR-card design is allowed; actual SERVICE checkout, slot scheduling, provider capacity, check-in and completion are a separate accepted state machine. Any attempt to treat SERVICE SKU as a Product stock reservation must fail.

## 11. Typed error catalog and negative outcomes

All errors below are application-level errors before transport; do not implement HTTP status mapping or controllers in R1.

| Error | Domain | Outcome |
|---|---|---|
| QR_NOT_AVAILABLE | STOREFRONT | Generic missing/hidden/revoked, no enumeration |
| PRODUCT_NOT_PUBLISHED | CATALOG | No proposed offer order |
| PUBLIC_MEDIA_NOT_ALLOWED | FILES | Zero leaked bytes |
| REGION_UNKNOWN | GEO | No fabricated ancestry |
| AVAILABILITY_UNKNOWN | INVENTORY | No guaranteed stock promise |
| QUANTITY_INVALID | SALES | Reject before identity/order side effects |
| PRICE_RECONFIRM_REQUIRED | SALES | No order until buyer accepts newer price |
| PRICE_PRECISION_INVALID | SALES | No order or proposal |
| NEGOTIATION_NOT_ALLOWED | SALES/IAM | No proposal and no buyer data leak |
| PROPOSAL_REVISION_CONFLICT | SALES | Zero new events |
| PROPOSAL_EXPIRED | SALES | No accepted quote |
| QUOTE_NOT_ELIGIBLE | SALES | No consumed quote or order |
| QUOTE_ALREADY_CONSUMED | SALES | Only exact same request may read original receipt |
| IDEMPOTENCY_CONFLICT | SALES | Same key, different normalized payload: no side effects |
| ORDER_NOT_ACCESSIBLE | SALES | No foreign order existence leak |
| INSUFFICIENT_STOCK | INVENTORY | Whole confirm rollback, no partial reservation |
| WAREHOUSE_NOT_ELIGIBLE | ORG/INVENTORY | No reserve or handover |
| PICKUP_NOT_READY | SALES | No pickup issue/consume |
| PICKUP_PROOF_INVALID | SALES | Wrong type/expiry/digest/revoked; no debit |
| PICKUP_WAREHOUSE_MISMATCH | SALES | No debit |
| PICKUP_ALREADY_HANDLED | SALES | Authenticated exact retry gives same receipt only |
| IAM_ACCESS_DENIED | IAM | No business rows changed |
| RETURN_QUANTITY_EXCEEDED | SALES | No RMA stock receipt |
| RETURN_NOT_SELLABLE | INVENTORY | No sellable on_hand increment |

**Safe errors:** public missing/foreign offer should be indistinguishable. Staff after verified admission may receive more specific internal error codes in logs/UI; never echo proof, raw SQL exception or private source IDs to anonymous user. On deadlock, retry complete transaction with bounded policy, not only failing SQL statement. On network timeout after commit, resolve by idempotency receipt, not blindly replay debit.

## 12. Executable QA plan: 73 existing IDs plus stage-oriented evidence

The parent has **37 existing QR-P/QR-N/QR-O/QR-G/QR-H/QR-C/QR-R/QR-S/QR-PERF and 36 QR-X** = 73 distinct requirement IDs. These are **not executed tests**. Do not count test cases as lines of code or percentage of R3 readiness. One scenario may require several PG test methods and a scenario may depend on a future stage.

### 12.1 Proposed test-class work allocation

| Proposed JUnit test class | Methods / scenario coverage (names indicative) | Fixture/dependency |
|---|---|---|
| ProductQrAliasPostgresQualificationTest | stableAliasReprintAfterPriceChange; concurrentIssueHasOneActiveGeneration; foreignTenantCannotResolvePrivateOffer; revokedOrPausedOfferShowsNoPrivateData | STOREFRONT+CATA+IAM+PG |
| ProductQrMediaBoundaryQualificationTest | catalogItemPublicImageRequiresCleanOwner; crmCustomerAttachmentNeverPublic; revokedPublicationDeniesOriginalVariant; foreignTenantFileIdDenied | FILES+CATALOG+scanner adapter |
| RegionalQrAvailabilityQualificationTest | exactKatoSeparatesWarehouses; partialKatoTreeDoesNotGuessDescendants; reserveRaceNeverPromisesGuaranteedStock | INVENTORY+ORG/GEO+PG |
| QrCheckoutQualificationTest | priceChangedRequiresExplicitReconfirmation; sameRequestKeyOneOrder; mergedQtyAbove1000FailsEarly; issuedOrderRefDoesNotOpenPrivateOrder | AUTOORDER+SALES+IAM |
| PriceProposalPostgresQualificationTest | sellerAndBuyerCounterRevisionCAS; onlyOtherSideMayAccept; expiryDuringAcceptDenied; foreignBuyerAndSellerDenied; currencyCannotBeChangedSilently | SALES+IAM+PG |
| AcceptedQuoteCheckoutQualificationTest | concurrentCheckoutCreatesOneOrder; sellerPausedBlocksNewCheckout; acceptedPriceDoesNotChangePublicPrice; sameKeyOtherTermsConflict | SALES+STOREFRONT+IAM |
| WarehouseOriginSnapshotQualificationTest | gpsChangedAfterConfirmKeepsSnapshot; gpsUpdateRacesConfirmWithOneRevision; wrongTenantWarehouseAndClosedWarehouseDenied; legacySnapshotUnknown | ORG+SALES+INV+PG |
| PickupPreparationQualificationTest | readyRequiresAllReservations; readyKeepsOnHand; noShowDoesNotAutoRelease | SALES+INVENTORY |
| PickupProofQualificationTest | twoConcurrentReissuesKeepOneActive; expiredBetweenPreviewAndCompleteDenied; orderReferenceCannotBeUsedAsPickup; lostProofReissuesWithFreshBuyerProof | SALES+IAM+PG |
| PickupHandoverPostgresQualificationTest | twoScannersOnlyOneReceipt; eighthLineFailureRollsBackAll; revocationRaceBothCommitOrders; wrongWarehouseDenied; crashBeforeCommitSafeRetry | SALES+INVENTORY+IAM+PG |
| ReturnQrAndReceiptQualificationTest | rmaCannotExceedFulfilledQty; doubleReturnReceiptDenied; damagedReturnNeverSellable; originalCompletedUnaffected | RMA+INV+PG, deferred |
| QrPerformanceQualificationTest | mixedScanAndCheckoutP95P99; noConnectionPoolStarvation; noRawProofInTelemetry | Fixed PG populated workload, separate gate |

**Mandatory independent SQL oracle** for every case:
- source sales_order.status/row_version, immutable order line qty/unit price/currency, count of sales_order_status_event;
- inv_stock_balance on_hand/reserved, inv_stock_reservation state, inv_stock_movement count/delta/request keys and reservation events;
- proposal/event revisions, acceptedQuote CONSUMED/order linkage;
- credential active-generation count/digest, consumed_at and unique handover receipt;
- org_location current location vs immutable order origin snapshot;
- absence of cross-tenant rows and unauthorized public FILES bytes.

**Reference numerical oracle:** Given one SKU at warehouse A with on_hand=20, reserved=0, order qty=5: PLACED keeps 20/0; CONFIRMED gives 20/5 (available 15); PREPARING and READY remain 20/5; complete handover yields 15/0 (available 15) with one fulfillment movement of -5, one reservation FULFILLED and one receipt. Repeated proof/command does not change 15/0. Cancel before handover releases 5 and returns 20/0. B and other seller tenant remain unchanged.

### 12.2 Concurrency fixtures and reproducibility

Synthetic fixture A/B tenants, each with different IAM grants and published offers; same display SKU but different item IDs; A has warehouses in two exact KATO regions, B has foreign warehouse; 8-line multi-SKU order; one active reservation, one missing SKU, one inactive warehouse; customer pending-email identity and verified buyer identity; seller-staff and warehouse-staff distinct users; FILES public candidate and CRM_CUSTOMER private file. Use separate PostgreSQL connections with barriers/latches and both commit orders. Avoid timing-sensitive sleeps as assertions.

- Explicitly test QR reuse after seller price update vs after seller pause: reprint works in both, but card sellability changes.
- Explicitly test active foreign-tenant actor who is also member of both tenants with **different** permissions; no borrowed permission.
- Persist test evidence: DB schema/migration version, exact commit SHA, method/class names, Surefire reports and workflow attempt.
- Existing mandatory CI workflows remain obligatory; H2 never substitutes for PostgreSQL race/constraint proof.
- Never record QR-X35 performance target as an automatically met SLA: workload/hardware must be specified and baseline compared.

### 12.3 Scenarios by mandatory acceptance gate

| Gate | Parent IDs | Enhanced IDs | Qualifying result |
|---|---|---|---|
| QR-00 | Evidence trace rather than functionality | Lock graph and DDL registry | Explicit producer/owner/permission/transaction mapping |
| QR-01 | QR-P01..P03 | QR-X01..X05 | Stable alias, published card, tenant-safe |
| QR-02 | QR-P04/P05 | QR-X06..X10 | Clean published images, exact KATO availability |
| QR-03 | QR-P06..P08, QR-O01/O02 | QR-X11/X12 | One list-price checkout, safe order reference |
| QR-04 | QR-N01..N09 | QR-X13..X18 | CAS bargaining and once-only accepted quote checkout |
| QR-05 | QR-G01/G02, QR-H01 | QR-X19/X20 | Immutable origin and READY without stock debit |
| QR-06 | QR-H02..H09, QR-C01 | QR-X21..X29 | One-time handover and robust reissue/no-show |
| QR-07 | QR-R01..R04 | QR-X30..X33 | RMA with inspected typed return receipt |
| QR-08 | QR-S01 | QR-X34 | SERVICE remains stockless until separate qualification |
| QR-PERF | QR-PERF01 | QR-X35/X36 | Reproducible latency/recovery evidence |

Every delivered stage attaches a concrete **source method → domain invariant → exact JUnit method → independent DB observation → workflow run/SHA** chain. A staged feature flag or table existence is not verification.

## 13. Schema rollout, rollback, legacy preservation and proof of no regression

Recommended additive sequence, with QR-00 migration audit before implementing:
1. STOREFRONT offer QR public alias + stable ref uniqueness and seller-audited issuer.
2. Public CATALOG_ITEM media owner/variants and negative access tests; does not change CRM_CUSTOMER FILES owner flows.
3. SALES proposal/events/accepted quote + nullable price_source and accepted_quote_id; unchanged list-price checkout must remain pass.
4. SALES order reference, pickup credential and receipt schemas; old orders may have no QR and remain valid.
5. ORG nullable WGS84 metadata and SALES nullable origin fields. No automatic backfill of GPS from KATO or old orders. Validated owner snapshot under transaction.
6. PREPARING/READY service transitions and token/handovers **only after** dual IAM+warehouse object policy, status conflict fix and PG races.
7. RMA/quality and typed inventory return receipt only after separately approved quarantine quantity model.

For each migration: inspect existing names/types, evaluate FK/index build time on populated PG, check rollback/forward path and orphan/duplicate rows, run existing SALES/INVENTORY/FILES/ORGANIZATION/IAM regression tests, then exact-SHA manifest. After real immutable receipts exist, no destructive DROP rollback; use feature disable and forward migration.

**Legacy behavior:** existing AutoOrderService checkout and SalesInventoryCommandService.fulfill remain valid within their original contracts until migration evidence. A legacy COMPLETED without handover receipt **must not be represented as verified pickup**; distinguish LEGACY_FULFILLED from QR_HANDOVER_CONFIRMED in read/analytics. Existing CONFIRMED legacy orders without origin GPS report UNKNOWN_LEGACY; avoid restrictive CHECK until proper data classification.

**Runtime controls:** stage flags separate public QR card, bargaining, order reference and pickup; public card unavailable must not break non-QR checkout. A feature flag must not bypass publication/tenant/revocation checks.

## 14. Operational UX and failure recovery for the later adapters

No HTTP/BFF/React/Flutter implementation now. Future adapters must follow:
- QR product opens regular HTTPS page without installing an app; public card in kk/ru/en/zh-Hans; separate explicit «Заказать» and «Предложить цену».
- Seller negotiator can view latest turn, amount, qty, deadline and revision; no unrestricted buyer PII.
- Buyer order QR is not pickup credential; only buyer-owned order details after verification.
- Warehouse scanner shows limited preview, requires explicit «Выдать» button after physical check; receipt clearly says warehouse handover and does not assert PAID or courier DELIVERED.
- Loss of pickup token: authenticated reissue revokes old; network failure: query existing receipt/status before any retry, never offline debit.
- QR visual rule: white central circle and check mark, outside finder/alignment/timing patterns, with adequate quiet zone; error correction H as candidate, not universally guaranteed; printed and on-screen scan tests on low-end phones before visual standard acceptance.
- Multi-parcel and partial pickup deferred; service booking separate appointment capacity policy; wallet/offline proof not assumed.
- Screen reader semantics, readable status text (not color alone), locale and print contrast tests.

## 15. Performance, security observation and definition of done

Proposed qualification targets only, not measurements: public QR resolve/card server p95 ≤300ms, staff scan preview p95 ≤250ms, atomic handover commit p95 ≤800ms for one warehouse/1–8 lines, accepted quote checkout p95 ≤800ms, zero duplicate stock movement and zero private data leak. Require same fixed CPU/DB/storage, warmed JVM, representative tenant skew/stock and A/B baseline with explain plans, pg_stat_statements, pool wait, lock wait, p95/p99, WAL and error rate. Never introduce Kafka/Redis/ClickHouse/Rust based on an unsupported performance guess.

Secrets are not Prometheus label values; forbid raw public buyer email, raw pickup proof, external storage keys or unbounded orderId/tenantId labels. Security event/attempt logging separate from business receipts and tenant-scoped without bypass.

**Definition of Done for any coded gate:**
1. Approved owner and business decision register, no invented permission/default.
2. Services, records, SQL migrations and immutable state ownership mapped to actual code, no REST controller.
3. IAM/tenant/object policy enforced on every command, negative multi-tenant and revocation race checks.
4. Atomic and idempotent domain transaction with rollback proof and zero double movement/quote consumption.
5. Populated PostgreSQL migration & SQL oracle plus H2 smoke and required real FILES infrastructure where applicable.
6. Exact-SHA existing CI workflows and new method-level test evidence PASS on same code commit, no skipped mandatory cases.
7. Reproducible measurement/recovery/runbook as required by that gate.
8. No claim of production until separate real OIDC/transport, browser/mobile scan, privacy/legal, backup/restore and load admission.

## 16. Remaining decisions requiring explicit product approval

| Code | Proposed default | Blocking effect |
|---|---|---|
| QR-DEC-01 | One PRODUCT offer QR per seller offer, persistent until revoke | None once owner model approved |
| QR-DEC-02 | Negotiation ≤3 rounds and TTL 48h as example | Must approve before bargaining policy enters code |
| QR-DEC-03 | Offer min accepted price policy, currency and rounding; zero public price allowed by existing DB | Must approve before price floor engine |
| QR-DEC-04 | Verified buyer required for bargaining; no raw pending email quote | Verify buyer identity flow |
| QR-DEC-05 | Price reconfirm on list-price checkout, explicit accepted offer revision | Contract extension of existing checkout |
| QR-DEC-06 | One seller/order, one warehouse/order, full-lines in R1 | Accepted existing inventory invariant |
| QR-DEC-07 | Warehouse-specific employee assignment | Blocks production handover in multiple warehouses |
| QR-DEC-08 | Pickup proof TTL candidate 3m and high-value recipient proof | Must approve staff/customer policy |
| QR-DEC-09 | No-show hold period, cancellation from READY and reserve release | HOLD, existing cancel rejects READY |
| QR-DEC-10 | RMA inspection, quarantine ledger and acceptance qty | Blocks RMA stock receipt until model decided |
| QR-DEC-11 | Buyer destination and seller delivery service zones | Not implied by origin coordinates or exact KATO |
| QR-DEC-12 | SERVICE slot/provider/cancellation/check-in state machine | Service checkout remains HOLD |
| QR-DEC-13 | Courier/parcel/partial pick-up semantics | R2 only; current order-wide uniqueness unchanged |
| QR-DEC-14 | Public photo retention/thumbnail CDN revoke | Blocks FILES public owner admission |
| QR-DEC-15 | Pickup and RMA receipt retention, privacy/audit legal requirements | Blocks production, not design-only tests |
| QR-DEC-16 | QR URL domain/issuer certificate, rate limits/anti-abuse and key rotation | Blocks external/public deployment |

**QR-00 deliverable:** choose exact approved choices for each blocking decision, document any architectural difference with ADR, and produce method-level source trace and migration mapping. Do not hardcode a proposed default as though user accepted it.

**Final status:** specification-level implementation blueprint only. No claim of successful tests, delivered UI, controllers or production admission.
