package kz.rm.autoorder;

import kz.rm.iam.EmailIdentityService;

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

    public AutoOrderService(JdbcTemplate jdbc, EmailIdentityService emailIdentities) {
        this.jdbc=jdbc;this.emailIdentities=emailIdentities;
    }

    public record Line(UUID offerId,int quantity){}
    public record OrderReceipt(UUID orderId,String status){}
    /** Safe to expose only after transport-level anti-abuse checks and authentication are implemented. */
    public record CheckoutResult(OrderReceipt order){}

    @Transactional
    public CheckoutResult checkout(String slug, String email, String idempotencyKey, List<Line> lines) {
        String normalized=emailIdentities.normalize(email);
        if(slug==null||slug.isBlank()||idempotencyKey==null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,100}")
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
        byte[] requestHash=digest(normalized+"|"+basket.entrySet().stream()
                .map(e->e.getKey()+":"+e.getValue()).collect(Collectors.joining(",")));
        // All competing transactions for a seller/idempotency key serialize here.
        // Always acquire checkout stripe BEFORE the global email stripe.
        lockStripe("crm.sales_checkout_lock", tenant + ":" + idempotencyKey);
        List<OrderReceipt> existing=jdbc.query("""
            SELECT r.order_id,r.request_sha256,o.status FROM crm.sales_checkout_receipt r
            JOIN crm.sales_order o ON o.tenant_id=r.tenant_id AND o.id=r.order_id
            WHERE r.tenant_id=? AND r.idempotency_key=?
            """,(rs,n)->{
                if(!MessageDigest.isEqual(requestHash,rs.getBytes(2)))
                    throw new IllegalArgumentException("Idempotency key reused with different request");
                return new OrderReceipt(rs.getObject(1,UUID.class),rs.getString(3));
            },tenant,idempotencyKey);
        if(!existing.isEmpty()) return new CheckoutResult(existing.get(0));
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
        BigDecimal total=offers.stream().map(o->o.unitPrice().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        // IAM alone owns global email deduplication and pending account provisioning.
        EmailIdentityService.Identity identity=emailIdentities.findOrProvision(normalized);
        UUID orderId=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at)
            VALUES (?,?,?,NULL,'PLACED',?,?,?)
            """,tenant,orderId,identity.identityId(),total,currency,now);
        for(Offer o:offers) {
            jdbc.update("""
                INSERT INTO crm.sales_order_line
                  (tenant_id,id,order_id,offer_id,title_snapshot,quantity,
                   unit_price_snapshot,currency,catalog_item_id_snapshot)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),orderId,o.offerId(),o.title(),
                    o.quantity(),o.unitPrice(),currency,o.catalogItemId());
        }
        jdbc.update("""
            INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at)
            VALUES (?,?,?,'PLACED',?)
            """,tenant,UUID.randomUUID(),orderId,now);
        jdbc.update("""
            INSERT INTO crm.sales_checkout_receipt(tenant_id,idempotency_key,request_sha256,order_id,created_at)
            VALUES (?,?,?,?,?)
            """,tenant,idempotencyKey,requestHash,orderId,now);
        // Fresh proof is always required; token stays encrypted in an internal durable outbox.
        emailIdentities.queueVerification(identity.identityId());
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
