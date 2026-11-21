package kz.rm.autoorder;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final EmailIdentityCrypto identityCrypto;

    public AutoOrderService(JdbcTemplate jdbc, EmailIdentityCrypto identityCrypto) {
        this.jdbc=jdbc;this.identityCrypto=identityCrypto;
    }

    public record Line(UUID offerId,int quantity){}
    public record OrderReceipt(UUID orderId,String status){}
    /** This is internal verification email payload, never an HTTP checkout response. */
    public record VerificationDispatch(UUID challengeId,String email,String token){}
    public record CheckoutResult(OrderReceipt order, VerificationDispatch verification){}

    @Transactional
    public CheckoutResult checkout(String slug, String email, String idempotencyKey, List<Line> lines) {
        String normalized=identityCrypto.normalize(email);
        if(slug==null||slug.isBlank()||idempotencyKey==null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,100}")
                ||lines==null||lines.isEmpty()||lines.size()>100)
            throw new IllegalArgumentException("Invalid checkout");
        Map<UUID,Integer> basket=new TreeMap<>();
        for(Line line:lines) {
            if(line==null||line.offerId()==null||line.quantity()<1||line.quantity()>1000)
                throw new IllegalArgumentException("Invalid line");
            basket.merge(line.offerId(),line.quantity(),Math::addExact);
        }
        UUID tenant=jdbc.query("""
            SELECT tenant_id FROM crm.store_front WHERE slug=? AND state='PUBLISHED'
            """,rs->{if(!rs.next())throw new IllegalArgumentException("Store not accepting orders");
                return rs.getObject(1,UUID.class);},slug);
        byte[] requestHash=digest(normalized+"|"+basket.entrySet().stream()
                .map(e->e.getKey()+":"+e.getValue()).collect(Collectors.joining(",")));
        List<OrderReceipt> existing=jdbc.query("""
            SELECT r.order_id,r.request_sha256,o.status FROM crm.sales_checkout_receipt r
            JOIN crm.sales_order o ON o.tenant_id=r.tenant_id AND o.id=r.order_id
            WHERE r.tenant_id=? AND r.idempotency_key=?
            """,(rs,n)->{
                if(!MessageDigest.isEqual(requestHash,rs.getBytes(2)))
                    throw new IllegalArgumentException("Idempotency key reused with different request");
                return new OrderReceipt(rs.getObject(1,UUID.class),rs.getString(3));
            },tenant,idempotencyKey);
        if(!existing.isEmpty()) return new CheckoutResult(existing.get(0),null);

        // Server-owned prices and availability; no trust in client-provided title, totals or tenant.
        List<Offer> offers=new ArrayList<>();
        for(var entry:basket.entrySet()) {
            Offer offer=jdbc.query("""
                SELECT title,unit_price,currency FROM crm.store_offer
                WHERE tenant_id=? AND id=? AND state='PUBLISHED'
                """,rs->{if(!rs.next())throw new IllegalArgumentException("Offer unavailable");
                    return new Offer(entry.getKey(),rs.getString(1),rs.getBigDecimal(2),rs.getString(3),
                            entry.getValue());},tenant,entry.getKey());
            offers.add(offer);
        }
        String currency=offers.getFirst().currency();
        if(offers.stream().anyMatch(o->!currency.equals(o.currency())))
            throw new IllegalArgumentException("Mixed currencies not supported");
        BigDecimal total=offers.stream().map(o->o.unitPrice().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        Identity identity=findOrCreate(normalized);
        UUID orderId=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at)
            VALUES (?,?,?,NULL,'PLACED',?,?,?)
            """,tenant,orderId,identity.identityId(),total,currency,now);
        for(Offer o:offers) {
            jdbc.update("""
                INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency)
                VALUES (?,?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),orderId,o.offerId(),o.title(),o.quantity(),o.unitPrice(),currency);
        }
        jdbc.update("""
            INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at)
            VALUES (?,?,?,'PLACED',?)
            """,tenant,UUID.randomUUID(),orderId,now);
        jdbc.update("""
            INSERT INTO crm.sales_checkout_receipt(tenant_id,idempotency_key,request_sha256,order_id,created_at)
            VALUES (?,?,?,?,?)
            """,tenant,idempotencyKey,requestHash,orderId,now);
        // Always require fresh proof for this checkout, including an existing verified account.
        VerificationDispatch dispatch=createChallenge(identity.identityId(),normalized);
        // Trusted adapter must persist/send dispatch DURABLY before enabling public checkout.
        return new CheckoutResult(new OrderReceipt(orderId,"PLACED"),dispatch);
    }

    /**
     * Token MUST come from a challenge message delivered to the email address.
     * Do not call with an actor-supplied user ID.
     */
    @Transactional
    public UUID verifyEmail(UUID challengeId,String presentedToken) {
        if(challengeId==null || presentedToken==null || presentedToken.length()<32)
            throw new SecurityException("Invalid challenge");
        byte[] hash=identityCrypto.tokenHash(presentedToken);
        var identityId=jdbc.query("""
            SELECT email_identity_id,token_sha256,expires_at,consumed_at
            FROM iam_email_verification_challenge WHERE id=? FOR UPDATE
            """,rs->{
                if(!rs.next() || rs.getTimestamp(4)!=null
                        ||rs.getTimestamp(3).toInstant().isBefore(Instant.now())
                        ||!MessageDigest.isEqual(hash,rs.getBytes(2)))
                    throw new SecurityException("Invalid or expired challenge");
                return rs.getObject(1,UUID.class);
            },challengeId);
        jdbc.update("UPDATE iam_email_verification_challenge SET consumed_at=? WHERE id=?",
                Timestamp.from(Instant.now()),challengeId);
        jdbc.update("UPDATE iam_email_identity SET verified_at=COALESCE(verified_at,?) WHERE id=?",
                Timestamp.from(Instant.now()),identityId);
        UUID userId=jdbc.queryForObject("SELECT user_id FROM iam_email_identity WHERE id=?",
                UUID.class,identityId);
        jdbc.update("UPDATE iam_user_account SET status='ACTIVE',updated_at=? WHERE id=? AND status='PENDING'",
                Timestamp.from(Instant.now()),userId);
        jdbc.update("""
            UPDATE crm.sales_order SET buyer_user_id=? WHERE pending_email_identity_id=? AND buyer_user_id IS NULL
            """,userId,identityId);
        return userId;
    }

    /**
     * For trusted authenticated layer ONLY: must pass verified principal's stable UUID.
     * This method is not exposed through public API.
     */
    @Transactional(readOnly=true)
    public List<OrderReceipt> buyerOrders(UUID authenticatedUserId) {
        if(authenticatedUserId==null)throw new SecurityException("Authentication required");
        Integer verified=jdbc.queryForObject("""
            SELECT count(*) FROM iam_email_identity WHERE user_id=? AND verified_at IS NOT NULL
            """,Integer.class,authenticatedUserId);
        if(verified==null||verified==0)throw new SecurityException("Verified email required");
        return jdbc.query("""
            SELECT id,status FROM crm.sales_order WHERE buyer_user_id=?
            ORDER BY created_at DESC,id DESC
            """,(rs,n)->new OrderReceipt(rs.getObject(1,UUID.class),rs.getString(2)),
                authenticatedUserId);
    }

    private record Offer(UUID offerId,String title,BigDecimal unitPrice,String currency,int quantity){}
    private record Identity(UUID identityId,UUID userId,boolean verified){}

    private Identity findOrCreate(String normalized) {
        byte[] lookup=identityCrypto.lookup(normalized);
        List<Identity> found=jdbc.query("""
            SELECT id,user_id,verified_at FROM iam_email_identity WHERE lookup_hmac=?
            """,(rs,n)->new Identity(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getTimestamp(3)!=null),lookup);
        if(!found.isEmpty())return found.getFirst();
        UUID userId=UUID.randomUUID(), identityId=UUID.randomUUID();
        // Legacy IAM display_name is NOT NULL; never fabricate a person's real name.
        jdbc.update("""
            INSERT INTO iam_user_account(id,display_name,status,created_at,updated_at,row_version)
            VALUES (?,'Покупатель','PENDING',?,?,0)
            """,userId,Timestamp.from(Instant.now()),Timestamp.from(Instant.now()));
        jdbc.update("""
            INSERT INTO iam_email_identity(id,user_id,lookup_hmac,email_envelope,created_at)
            VALUES (?,?,?,?,?)
            """,identityId,userId,lookup,identityCrypto.encrypt(normalized),Timestamp.from(Instant.now()));
        return new Identity(identityId,userId,false);
    }

    private VerificationDispatch createChallenge(UUID identityId,String normalized) {
        String token=identityCrypto.token();
        UUID id=UUID.randomUUID();
        Instant now=Instant.now();
        jdbc.update("""
            INSERT INTO iam_email_verification_challenge
              (id,email_identity_id,token_sha256,token_envelope,expires_at,created_at)
            VALUES (?,?,?,?,?,?)
            """,id,identityId,identityCrypto.tokenHash(token),identityCrypto.encrypt(token),
                Timestamp.from(now.plus(15,ChronoUnit.MINUTES)),Timestamp.from(now));
        return new VerificationDispatch(id,normalized,token);
    }

    private static byte[] digest(String text){
        try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
