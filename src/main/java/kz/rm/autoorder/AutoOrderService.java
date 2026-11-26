package kz.rm.autoorder;

import kz.rm.iam.EmailIdentityService;
import kz.rm.intelligence.CanonicalFactAppender;
import kz.rm.intelligence.CanonicalFactKey;
import kz.rm.organization.CommerceCurrencyAdmission;
import kz.rm.sales.SalesOrderQrReferenceService;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.ConnectionCallback;
import jakarta.annotation.PostConstruct;
import java.math.RoundingMode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * First server-side AUTOORDER R1 foundation; NOT a public REST adapter.
 * Email delivery / OIDC claim admission are gated on the next integration slice.
 * Public controllers must never expose challenge tokens or accept userId as proof.
 */
@Service
public class AutoOrderService {
    private final JdbcTemplate jdbc;
    private final EmailIdentityService emailIdentities;
    private final SalesOrderQrReferenceService orderQrReferences;
    private final CommerceCurrencyAdmission currencyAdmission;
    private final CanonicalFactAppender canonicalFacts;
    private String verifiedBuyerIdentityLockSql;

    public AutoOrderService(JdbcTemplate jdbc, EmailIdentityService emailIdentities,
                            SalesOrderQrReferenceService orderQrReferences,
                            CommerceCurrencyAdmission currencyAdmission,
                            CanonicalFactAppender canonicalFacts) {
        this.jdbc=jdbc;
        this.emailIdentities=emailIdentities;
        this.orderQrReferences=orderQrReferences;
        this.currencyAdmission=currencyAdmission;
        this.canonicalFacts=Objects.requireNonNull(canonicalFacts);
    }

    // The owner source is already persisted and sealed inside the caller transaction.
    // A new source must insert a new fact; a collision is not a checkout replay.
    private void appendPlacedFact(UUID tenant,UUID eventId) {
        var outcome=canonicalFacts.appendLive(new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,
            CanonicalFactKey.Kind.ORDER_PLACED,eventId));
        if(outcome!=CanonicalFactAppender.Outcome.INSERTED)
            throw new IllegalStateException("CANONICAL_NEW_SOURCE_ALREADY_PRESENT");
    }

    private void lockCurrencyMarket(UUID tenant) {
        if(currencyAdmission!=null)currencyAdmission.lockKzMarket(tenant);
    }
    private void requireCurrency(String currency) {
        if(currencyAdmission!=null)currencyAdmission.assertKzt(currency);
    }

    public record Line(UUID offerId,int quantity){}
    public record OrderReceipt(UUID orderId,String status){}
    /** Safe to expose only after transport-level anti-abuse checks and authentication are implemented. */
    public record CheckoutResult(OrderReceipt order){}

    /**
     * QR-03 price acknowledgement is a CHECK, never an amount override.
     * The current store/offer/PRODUCT/QR state and authoritative unit price
     * are compared under the existing checkout's SQL row locks.
     */
    public record QrPriceAcknowledgement(String publicRef,UUID offerId,
                                         long offerRowVersion,
                                         BigDecimal acceptedUnitPrice,String currency) {}

    @Transactional
    public CheckoutResult checkout(String slug,String email,String idempotencyKey,List<Line> lines) {
        return checkoutInternal(slug,email,idempotencyKey,lines,null,null);
    }

    /**
     * Historical QR-03A signature retained for source compatibility only.
     * No deployment property may enable email-first private QR checkout.
     */
    @Transactional
    public CheckoutResult checkoutPublishedQr(String slug,String email,String idempotencyKey,
                                               Line line,QrPriceAcknowledgement acknowledged) {
        throw new SecurityException("QR_VERIFIED_BUYER_REQUIRED");
    }

    @Transactional
    public CheckoutResult checkoutVerifiedPublishedQr(VerifiedActor buyer,String slug,
            String idempotencyKey,Line line,QrPriceAcknowledgement acknowledged) {
        if(buyer==null)throw new SecurityException("QR_VERIFIED_BUYER_REQUIRED");
        validateQrAcknowledgement(line,acknowledged);
        if(!"KZT".equals(acknowledged.currency()))
            throw new IllegalStateException("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
        return checkoutInternal(slug,null,idempotencyKey,List.of(line),acknowledged,buyer);
    }

    private static void validateQrAcknowledgement(Line line,QrPriceAcknowledgement acknowledged) {
        if(line==null||acknowledged==null||acknowledged.offerId()==null
                ||!acknowledged.offerId().equals(line.offerId())
                ||acknowledged.offerRowVersion()<0
                ||acknowledged.publicRef()==null
                ||!acknowledged.publicRef().matches("[A-Za-z0-9_-]{32}")
                ||acknowledged.acceptedUnitPrice()==null
                ||acknowledged.acceptedUnitPrice().signum()<0
                ||acknowledged.acceptedUnitPrice().scale()>4
                ||acknowledged.acceptedUnitPrice().precision()
                        -acknowledged.acceptedUnitPrice().scale()>15
                ||acknowledged.currency()==null
                ||!acknowledged.currency().matches("[A-Z]{3}"))
            throw new IllegalArgumentException("QR_CHECKOUT_INVALID_ACKNOWLEDGEMENT");
    }

    private CheckoutResult checkoutInternal(String slug,String email,
            String idempotencyKey,List<Line> lines,
            QrPriceAcknowledgement acknowledged,VerifiedActor buyer) {
        UUID principalId=buyer==null?null:buyer.userId();
        String normalized=buyer==null?emailIdentities.normalize(email):null;
        if(slug==null||slug.isBlank()||idempotencyKey==null
                ||!idempotencyKey.matches("[A-Za-z0-9_-]{8,100}")
                ||lines==null||lines.isEmpty()||lines.size()>100)
            throw new IllegalArgumentException("Invalid checkout");
        Map<UUID,Integer> basket=new TreeMap<>();
        for(Line line:lines) {
            if(line==null||line.offerId()==null||line.quantity()<1||line.quantity()>1000)
                throw new IllegalArgumentException("Invalid line");
            // The per-line limit must also hold after duplicate offer IDs are merged.
            // Reject before reading tenant data or creating any IAM/SALES side effects.
            basket.merge(line.offerId(),line.quantity(),(current, added) -> {
                int total=Math.addExact(current,added);
                if(total>1000) throw new IllegalArgumentException("Aggregated offer quantity exceeds limit");
                return total;
            });
        }
        UUID tenant=jdbc.query("""
            SELECT tenant_id FROM crm.store_front WHERE slug=?
            """,rs->{if(!rs.next())throw new IllegalArgumentException("Store not accepting orders");
                return rs.getObject(1,UUID.class);},slug);
        // Legacy email-first checkout's fingerprint is unchanged. QR mode gets
        // an operation-aware suffix, so the shared seller checkout receipt cannot
        // be replayed across QR/non-QR modes with a misleading result.
        String qrIntent=acknowledged==null?"":"|QR_LIST_V1|"+acknowledged.publicRef()
                +"|"+acknowledged.offerId()+"|"+acknowledged.offerRowVersion()
                +"|"+acknowledged.acceptedUnitPrice().stripTrailingZeros().toPlainString()
                +"|"+acknowledged.currency();
        String buyerFingerprint=buyer==null?normalized:
                "RM/QR-VERIFIED-LIST/V1|"+tenant+"|"+principalId;
        byte[] requestHash=digest(buyerFingerprint+"|"+basket.entrySet().stream()
                .map(e->e.getKey()+":"+e.getValue()).collect(Collectors.joining(","))
                +qrIntent);
        // All competing transactions for a seller/idempotency key serialize here.
        // Always acquire checkout stripe BEFORE the global email stripe.
        lockStripe("crm.sales_checkout_lock", tenant + ":" + idempotencyKey);
        // Check current buyer state before replay, not just before new writes.
        BuyerProof buyerProof=buyer==null?null:lockVerifiedBuyer(buyer);
        List<OrderReceipt> existing=jdbc.query("""
            SELECT r.order_id,r.request_sha256,o.status,o.buyer_user_id FROM crm.sales_checkout_receipt r
            JOIN crm.sales_order o ON o.tenant_id=r.tenant_id AND o.id=r.order_id
            WHERE r.tenant_id=? AND r.idempotency_key=?
            """,(rs,n)->{
                if(!MessageDigest.isEqual(requestHash,rs.getBytes(2))
                        ||(buyerProof!=null && !buyerProof.userId().equals(rs.getObject(4,UUID.class))))
                    throw new IllegalArgumentException("Idempotency key reused with different request");
                return new OrderReceipt(rs.getObject(1,UUID.class),rs.getString(3));
            },tenant,idempotencyKey);
        if(!existing.isEmpty()) {
            if(buyer!=null)buyer.userId();
            return new CheckoutResult(existing.get(0));
        }
        // New checkout must be serialized with IAM tenant suspension.
        // Lock order: checkout stripe -> IAM workspace -> storefront -> offer -> catalogue.
        // Exact idempotent replay above remains available for previously accepted orders.
        // Unlike seller commands, buyers never need membership of the seller tenant.
        String workspaceStatus=jdbc.query("""
            SELECT status FROM public.iam_workspace
            WHERE id=? AND kind='ORGANIZATION' FOR UPDATE
            """,rs->rs.next()?rs.getString(1):null,tenant);
        if(!"ACTIVE".equals(workspaceStatus))
            throw new IllegalArgumentException("Store not accepting orders");
        lockCurrencyMarket(tenant);
        String storefrontState=jdbc.query("""
            SELECT state FROM crm.store_front WHERE tenant_id=? AND slug=? FOR UPDATE
            """,rs->rs.next()?rs.getString(1):null,tenant,slug);
        if(!"PUBLISHED".equals(storefrontState))
            throw new IllegalArgumentException("Store not accepting orders");


        // Server-owned prices and availability; no trust in client-provided title, totals or tenant.
        List<Offer> offers=new ArrayList<>();
        for(var entry:basket.entrySet()) {
            Offer offer=jdbc.query("""
                SELECT o.title,o.unit_price,o.currency,o.catalog_item_id
                FROM crm.store_offer o
                JOIN crm.cat_item c ON c.tenant_id=o.tenant_id AND c.id=o.catalog_item_id
                WHERE o.tenant_id=? AND o.id=? AND o.state='PUBLISHED'
                  AND c.kind='PRODUCT' AND c.state='PUBLISHED'
                FOR UPDATE
                """,rs->{if(!rs.next())throw new IllegalArgumentException(
                        "Offer unavailable or SKU not a published PRODUCT");
                    return new Offer(entry.getKey(),rs.getString(1),rs.getBigDecimal(2),
                            rs.getString(3),rs.getObject(4,UUID.class),entry.getValue());
                },tenant,entry.getKey());
            offers.add(offer);
        }
        String currency=offers.getFirst().currency();
        if(offers.stream().anyMatch(o->!currency.equals(o.currency())))
            throw new IllegalArgumentException("Mixed currencies not supported");
        requireCurrency(currency);
        if(buyerProof!=null && !"KZT".equals(currency))
            throw new IllegalStateException("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
        if(acknowledged!=null) {
            // The storefront/offer rows are already locked. A QR revoke or a
            // seller price update MUST wait for this checkout, or commit before
            // this check and cause a fail-closed rejection.
            if(offers.size()!=1||!offers.getFirst().offerId().equals(acknowledged.offerId()))
                throw new IllegalArgumentException("QR_CHECKOUT_SINGLE_PRODUCT_ONLY");
            Long version=jdbc.queryForObject("""
                    SELECT row_version FROM crm.store_offer
                    WHERE tenant_id=? AND id=?
                    """,Long.class,tenant,acknowledged.offerId());
            if(version==null||version!=acknowledged.offerRowVersion()
                    ||offers.getFirst().unitPrice().compareTo(
                            acknowledged.acceptedUnitPrice())!=0
                    ||!offers.getFirst().currency().equals(acknowledged.currency()))
                throw new IllegalStateException("PRICE_RECONFIRM_REQUIRED");
            Integer active=jdbc.queryForObject("""
                    SELECT count(*) FROM crm.store_offer_qr_ref
                    WHERE tenant_id=? AND offer_id=? AND public_ref=?
                      AND state='ACTIVE' AND active_offer_id=offer_id
                    """,Integer.class,tenant,acknowledged.offerId(),acknowledged.publicRef());
            if(active==null||active!=1)
                throw new IllegalStateException("QR_UNAVAILABLE");
        }
        BigDecimal total=offers.stream().map(o->o.unitPrice().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        if(buyer!=null)buyer.userId();
        // IAM alone owns global email deduplication and pending account provisioning.
        UUID identityId=buyerProof==null
                ?emailIdentities.findOrProvision(normalized).identityId()
                :buyerProof.emailIdentityId();
        UUID buyerId=buyerProof==null?null:buyerProof.userId();
        UUID orderId=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
            VALUES (?,?,?,?,'PLACED',?,?,?,?)
            """,tenant,orderId,identityId,buyerId,total,currency,now,
            acknowledged==null?"STANDARD":"PICKUP_PROOF_REQUIRED");
        for(Offer o:offers) {
            jdbc.update("""
                INSERT INTO crm.sales_order_line
                  (tenant_id,id,order_id,offer_id,title_snapshot,quantity,
                   unit_price_snapshot,currency,catalog_item_id_snapshot)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),orderId,o.offerId(),o.title(),
                    o.quantity(),o.unitPrice(),currency,o.catalogItemId());
        }
        UUID placedEventId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at)
            VALUES (?,?,?,'PLACED',?)
            """,tenant,placedEventId,orderId,now);
        appendPlacedFact(tenant,placedEventId);
        jdbc.update("""
            INSERT INTO crm.sales_checkout_receipt(tenant_id,idempotency_key,request_sha256,order_id,created_at)
            VALUES (?,?,?,?,?)
            """,tenant,idempotencyKey,requestHash,orderId,now);
        // Fresh proof is always required; token stays encrypted in an internal durable outbox.
        if(buyerProof==null)emailIdentities.queueVerification(identityId);
        // QR-03B: create a NONSECRET stable locator while the newly created
        // order, immutable lines, status event, checkout receipt and IAM
        // verification outbox share ONE transaction. It is deliberately NOT
        // returned in this unverified email-first checkout response.
        orderQrReferences.issueAtPlaced(tenant,orderId);
        return new CheckoutResult(new OrderReceipt(orderId,"PLACED"));
    }



    /**
     * QR-04B: agreed personalized price enters the SAME SALES checkout ledger,
     * checkout stripe, order line snapshot, email identity and QR issuer.
     * Never uses a typed customer email or creates a second order engine.
     *
     * Lock DAG: checkout stripe -> buyer account -> seller workspace ->
     * storefront -> offer -> catalog -> accepted quote -> order writes.
     * Proposal mutations lock buyer/seller before workspace/offer/proposal.
     * No operation ever locks proposal after the quote in this method.
     */
    public record AcceptedQuoteAck(long agreedRevision,int quantity,
                                   BigDecimal agreedUnitPrice,String currency) {}
    private record Quote(UUID id,UUID proposalId,UUID offerId,UUID buyerId,
                         long agreedRevision,int quantity,BigDecimal price,
                         String currency,String state,Instant expiresAt) {}
    private record CurrentOffer(String title,BigDecimal listPrice,String currency,
                                UUID catalogItemId,String state) {}

    @PostConstruct
    void configureQuoteLocks() {
        String db=jdbc.execute((ConnectionCallback<String>) c->
                c.getMetaData().getDatabaseProductName());
        String suffix=(db!=null&&db.toLowerCase(java.util.Locale.ROOT).contains("postgresql"))
                ?"FOR SHARE":"FOR UPDATE";
        verifiedBuyerIdentityLockSql="SELECT status,external_subject "
                +"FROM public.iam_user_account WHERE id=? "+suffix;
    }

    private record BuyerAccount(String status,String externalSubject) {}
    private record BuyerProof(UUID userId,UUID emailIdentityId) {}

    /** Checkout stripe -> buyer account -> verified email -> seller workspace. */
    private BuyerProof lockVerifiedBuyer(VerifiedActor buyer) {
        UUID user=buyer.userId();
        List<BuyerAccount> accounts=jdbc.query(verifiedBuyerIdentityLockSql,
                (rs,n)->new BuyerAccount(rs.getString(1),rs.getString(2)),user);
        if(accounts.size()!=1 || !"ACTIVE".equals(accounts.getFirst().status())
                ||accounts.getFirst().externalSubject()==null
                ||accounts.getFirst().externalSubject().isBlank())
            throw new SecurityException("VERIFIED_BUYER_REQUIRED");
        List<UUID> identities=jdbc.query("""
                SELECT id FROM public.iam_email_identity
                WHERE user_id=? AND lifecycle_state='CURRENT'
                  AND verified_at IS NOT NULL FOR UPDATE
                """,(rs,n)->rs.getObject(1,UUID.class),user);
        if(identities.size()!=1)
            throw new SecurityException("VERIFIED_EMAIL_REQUIRED");
        buyer.userId(); // fail closed if expiry elapsed while acquiring locks
        return new BuyerProof(user,identities.getFirst());
    }

    @Transactional
    public CheckoutResult checkoutAcceptedQuote(VerifiedActor buyer,UUID tenant,
                                                UUID quoteId,AcceptedQuoteAck accepted,
                                                String idempotencyKey) {
        if(buyer==null||tenant==null||quoteId==null||accepted==null
                ||accepted.agreedRevision()<1||accepted.quantity()<1
                ||accepted.quantity()>1000||accepted.agreedUnitPrice()==null
                ||accepted.agreedUnitPrice().signum()<=0
                ||accepted.agreedUnitPrice().scale()>4
                ||accepted.agreedUnitPrice().precision()
                        -accepted.agreedUnitPrice().scale()>15
                ||accepted.currency()==null||!accepted.currency().matches("[A-Z]{3}")
                ||idempotencyKey==null
                ||!idempotencyKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("QUOTE_CHECKOUT_INVALID");
        UUID user=buyer.userId(); // VERIFIED OIDC subject, never client UUID
        BigDecimal acceptedPrice=accepted.agreedUnitPrice()
                .setScale(4,RoundingMode.UNNECESSARY);
        byte[] requestHash=digest("RM/QR-04B/V1|"+tenant+"|"+user+"|"
                +quoteId+"|"+accepted.agreedRevision()+"|"+accepted.quantity()
                +"|"+acceptedPrice.stripTrailingZeros().toPlainString()
                +"|"+accepted.currency());
        // Shared seller-wide checkout namespace from QR-03A and normal lists.
        lockStripe("crm.sales_checkout_lock",tenant+":"+idempotencyKey);
        BuyerProof buyerProof=lockVerifiedBuyer(buyer);

        List<OrderReceipt> replays=jdbc.query("""
                SELECT r.order_id,r.request_sha256,o.status,o.buyer_user_id
                FROM crm.sales_checkout_receipt r
                JOIN crm.sales_order o ON o.tenant_id=r.tenant_id AND o.id=r.order_id
                WHERE r.tenant_id=? AND r.idempotency_key=?
                """,(rs,n)->{
                    if(!MessageDigest.isEqual(requestHash,rs.getBytes(2))
                            ||!buyerProof.userId().equals(rs.getObject(4,UUID.class)))
                        throw new IllegalArgumentException(
                                "Idempotency key reused with different request");
                    return new OrderReceipt(rs.getObject(1,UUID.class),
                            rs.getString(3));
                },tenant,idempotencyKey);
        if(!replays.isEmpty()) {
            buyer.userId();
            return new CheckoutResult(replays.getFirst());
        }

        String workspace=jdbc.query("""
                SELECT status FROM public.iam_workspace
                WHERE id=? AND kind='ORGANIZATION' FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant);
        if(!"ACTIVE".equals(workspace))
            throw new IllegalStateException("SELLER_INACTIVE");
        lockCurrencyMarket(tenant);
        String storefront=jdbc.query("""
                SELECT state FROM crm.store_front WHERE tenant_id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant);
        if(!"PUBLISHED".equals(storefront))
            throw new IllegalStateException("SELLER_STOREFRONT_PAUSED");

        // Preliminary lookup supplies lock target only, never sale terms.
        UUID offerId=jdbc.query("""
                SELECT offer_id FROM crm.sales_accepted_quote
                WHERE tenant_id=? AND id=?
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenant,quoteId);
        if(offerId==null)throw new SecurityException("QUOTE_NOT_ACCESSIBLE");
        CurrentOffer offer=jdbc.query("""
                SELECT title,unit_price,currency,catalog_item_id,state
                FROM crm.store_offer
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?new CurrentOffer(rs.getString(1),
                rs.getBigDecimal(2),rs.getString(3),
                rs.getObject(4,UUID.class),rs.getString(5)):null,
                tenant,offerId);
        if(offer==null||!"PUBLISHED".equals(offer.state())
                ||offer.catalogItemId()==null)
            throw new IllegalStateException("QUOTE_OFFER_UNAVAILABLE");
        String product=jdbc.query("""
                SELECT kind || ':' || state FROM crm.cat_item
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant,offer.catalogItemId());
        if(!"PRODUCT:PUBLISHED".equals(product))
            throw new IllegalStateException("QUOTE_PRODUCT_UNAVAILABLE");
        Quote quote=jdbc.query("""
                SELECT id,proposal_id,offer_id,buyer_user_id,agreed_revision,
                       quantity,agreed_unit_price,currency,state,expires_at
                FROM crm.sales_accepted_quote
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?new Quote(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getLong(5),rs.getInt(6),
                rs.getBigDecimal(7),rs.getString(8),rs.getString(9),
                rs.getTimestamp(10).toInstant()):null,tenant,quoteId);
        if(quote==null||!quote.buyerId().equals(user)||!quote.offerId().equals(offerId))
            throw new SecurityException("QUOTE_NOT_ACCESSIBLE");
        if(!"AVAILABLE".equals(quote.state()))
            throw new IllegalStateException("QUOTE_ALREADY_CONSUMED_OR_INVALID");
        if(!Instant.now().isBefore(quote.expiresAt()))
            throw new IllegalStateException("QUOTE_EXPIRED");
        if(quote.agreedRevision()!=accepted.agreedRevision()
                ||quote.quantity()!=accepted.quantity()
                ||quote.price().compareTo(acceptedPrice)!=0
                ||!quote.currency().equals(accepted.currency()))
            throw new IllegalStateException("QUOTE_RECONFIRM_REQUIRED");
        // The public STOREFRONT list price may change AFTER acceptance.
        // A negotiated price is immutable in this quote, never a client override.
        if(!quote.currency().equals(offer.currency()))
            throw new IllegalStateException("QUOTE_CURRENCY_CHANGED");
        requireCurrency(quote.currency());
        // W2 first invariant: NEW negotiated QR orders may never be foreign-currency,
        // even under a disabled staged market-policy flag. Existing receipts replay above.
        if(!"KZT".equals(quote.currency()))
            throw new IllegalStateException("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
        Integer agreed=jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_price_proposal
                WHERE tenant_id=? AND id=? AND state='AGREED' AND offer_id=?
                  AND buyer_user_id=? AND quantity=?
                """,Integer.class,tenant,quote.proposalId(),offerId,user,quote.quantity());
        if(agreed==null||agreed!=1)
            throw new IllegalStateException("QUOTE_PROPOSAL_INCONSISTENT");

        UUID orderId=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        BigDecimal total=quote.price().multiply(BigDecimal.valueOf(quote.quantity()));
        buyer.userId();
        jdbc.update("""
                INSERT INTO crm.sales_order
                  (tenant_id,id,pending_email_identity_id,buyer_user_id,status,
                   total_amount,currency,created_at,fulfillment_mode)
                VALUES (?,?,?,?,'PLACED',?,?,?,'PICKUP_PROOF_REQUIRED')
                """,tenant,orderId,buyerProof.emailIdentityId(),user,total,
                quote.currency(),now);
        jdbc.update("""
                INSERT INTO crm.sales_order_line
                  (tenant_id,id,order_id,offer_id,title_snapshot,quantity,
                   unit_price_snapshot,currency,catalog_item_id_snapshot,
                   price_source,accepted_quote_id)
                VALUES (?,?,?,?,?,?,?,?,?,'NEGOTIATED',?)
                """,tenant,UUID.randomUUID(),orderId,offerId,offer.title(),
                quote.quantity(),quote.price(),quote.currency(),
                offer.catalogItemId(),quoteId);
        UUID placedEventId=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.sales_order_status_event
                  (tenant_id,id,order_id,event_type,occurred_at)
                VALUES (?,?,?,'PLACED',?)
                """,tenant,placedEventId,orderId,now);
        appendPlacedFact(tenant,placedEventId);
        jdbc.update("""
                INSERT INTO crm.sales_checkout_receipt
                  (tenant_id,idempotency_key,request_sha256,order_id,created_at)
                VALUES (?,?,?,?,?)
                """,tenant,idempotencyKey,requestHash,orderId,now);
        int consumed=jdbc.update("""
                UPDATE crm.sales_accepted_quote
                SET state='CONSUMED',consumed_order_id=?,consumed_at=?
                WHERE tenant_id=? AND id=? AND state='AVAILABLE'
                  AND consumed_order_id IS NULL
                """,orderId,now,tenant,quoteId);
        if(consumed!=1)throw new IllegalStateException("QUOTE_CONSUME_RACE");
        orderQrReferences.issueAtPlaced(tenant,orderId);
        return new CheckoutResult(new OrderReceipt(orderId,"PLACED"));
    }

    /**
     * Token MUST come from a challenge message delivered to the email address.
     * Do not call with an actor-supplied user ID.
     */
    @Transactional
    public UUID verifyEmail(UUID challengeId,String presentedToken) {
        UUID userId=emailIdentities.verifyEmail(challengeId,presentedToken);
        // SALES owns buyer order association; IAM verification grants no HTTP session.
        jdbc.update("""
            UPDATE crm.sales_order SET buyer_user_id=?
            WHERE pending_email_identity_id IN
              (SELECT id FROM iam_email_identity
                 WHERE user_id=? AND lifecycle_state='CURRENT'
                   AND verified_at IS NOT NULL)
              AND buyer_user_id IS NULL
            """,userId,userId);
        return userId;
    }

    /**
     * For trusted authenticated layer ONLY: must pass verified principal's stable UUID.
     * This method is not exposed through public API.
     */
    @Transactional(readOnly=true)
    List<OrderReceipt> buyerOrders(UUID authenticatedUserId) {
        if(authenticatedUserId==null)throw new SecurityException("Authentication required");
        Integer verified=jdbc.queryForObject("""
            SELECT count(*) FROM iam_email_identity e JOIN iam_user_account u ON u.id=e.user_id
            WHERE e.user_id=? AND e.lifecycle_state='CURRENT'
              AND e.verified_at IS NOT NULL AND u.status='ACTIVE'
            """,Integer.class,authenticatedUserId);
        if(verified==null||verified==0)throw new SecurityException("Verified email required");
        return jdbc.query("""
            SELECT id,status FROM crm.sales_order WHERE buyer_user_id=?
            ORDER BY created_at DESC,id DESC
            """,(rs,n)->new OrderReceipt(rs.getObject(1,UUID.class),rs.getString(2)),
                authenticatedUserId);
    }

    /** Bounded history. Principal identity is taken only from AuthenticatedBuyerOrders. */
    @Transactional(readOnly=true)
    org.springframework.data.domain.Page<OrderReceipt> buyerOrdersPage(
            UUID authenticatedUserId,int page,int size) {
        if(page<0||size<1||size>100)
            throw new IllegalArgumentException("Page must be nonnegative, size 1..100");
        if(authenticatedUserId==null)throw new SecurityException("Authentication required");
        Integer verified=jdbc.queryForObject("""
            SELECT count(*) FROM iam_email_identity e JOIN iam_user_account u ON u.id=e.user_id
            WHERE e.user_id=? AND e.lifecycle_state='CURRENT'
              AND e.verified_at IS NOT NULL AND u.status='ACTIVE'
            """,Integer.class,authenticatedUserId);
        if(verified==null||verified==0)throw new SecurityException("Verified email required");
        Long count=jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_order WHERE buyer_user_id=?
            """,Long.class,authenticatedUserId);
        var orders=jdbc.query("""
            SELECT id,status FROM crm.sales_order WHERE buyer_user_id=?
            ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
            """,(rs,n)->new OrderReceipt(rs.getObject(1,UUID.class),rs.getString(2)),
                authenticatedUserId,size,(long)page*size);
        return new org.springframework.data.domain.PageImpl<>(orders,
                org.springframework.data.domain.PageRequest.of(page,size),
                count==null?0:count);
    }

    private record Offer(UUID offerId,String title,BigDecimal unitPrice,String currency,
                         UUID catalogItemId,int quantity){}
    /**
     * Database row locks are transaction-scoped and shared by pods.
     * Table names are fixed, never built from request data.
     * 256 stripes are an R1 correctness choice; throughput qualification is separate.
     */
    private void lockStripe(String table, String value) {
        if (!table.equals("crm.sales_checkout_lock"))
            throw new IllegalArgumentException("Unknown lock");
        int bucket=Math.floorMod(Arrays.hashCode(digest(value)),256);
        Integer acquired=jdbc.queryForObject("SELECT bucket FROM "+table+" WHERE bucket=? FOR UPDATE",
                Integer.class,bucket);
        if(acquired==null || acquired!=bucket)
            throw new IllegalStateException("Lock stripe missing");
    }

    private static byte[] digest(String text){
        try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
