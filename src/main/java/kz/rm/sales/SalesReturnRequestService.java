package kz.rm.sales;

import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * QR-07/R0: buyer-created, immutable-source return requests.
 *
 * This service deliberately does not refund, receive, restock, cancel the
 * completed order or change the handover receipt. The completed order row is
 * locked across checking and recording every partial-return quantity.
 * Verified buyer identity is admitted through the existing QR ownership path.
 */
@Service
public class SalesReturnRequestService {
    public record Created(UUID tenantId, UUID requestId, UUID orderId,
                          UUID orderLineId, int quantity, String state) {}
    private record OrderLock(UUID tenant, String state) {}
    private record Previous(UUID id, UUID orderId, String fingerprint) {}

    private final JdbcTemplate jdbc;
    private final SalesOrderQrReferenceService reference;

    public SalesReturnRequestService(JdbcTemplate jdbc,
                                     SalesOrderQrReferenceService reference) {
        this.jdbc = jdbc;
        this.reference = reference;
    }

    @Transactional
    public Created request(VerifiedActor buyer, String orderPublicRef, UUID lineId,
                           int quantity, String reasonCode, String policyVersion,
                           String requestKey) {
        if (buyer == null || orderPublicRef == null || lineId == null)
            throw new SecurityException("RETURN_BUYER_AND_SOURCE_REQUIRED");
        if (quantity < 1)
            throw new IllegalArgumentException("RETURN_QUANTITY_INVALID");
        if (!safeToken(reasonCode, 40) || !safeToken(policyVersion, 40)
                || !safeToken(requestKey, 100))
            throw new IllegalArgumentException("RETURN_METADATA_INVALID");

        var own = reference.readOwn(buyer, orderPublicRef);
        UUID actor = buyer.userId();
        OrderLock source = jdbc.query("""
                SELECT o.tenant_id,o.status
                FROM crm.sales_order o
                JOIN crm.sales_order_qr_reference q
                  ON q.tenant_id=o.tenant_id AND q.order_id=o.id
                WHERE q.public_ref=? AND q.state='ACTIVE'
                  AND o.id=? AND o.buyer_user_id=?
                FOR UPDATE OF o
                """, rs -> rs.next()
                ? new OrderLock(rs.getObject(1, UUID.class),rs.getString(2))
                : null, orderPublicRef,own.orderId(),actor);
        if (source == null) throw new SecurityException("RETURN_ORDER_NOT_OWNED");
        UUID tenant = source.tenant();
        if (!"COMPLETED".equals(source.state()))
            throw new IllegalStateException("RETURN_SOURCE_NOT_COMPLETED");

        String fingerprint = fingerprint(actor,own.orderId(),lineId,
                quantity,reasonCode,policyVersion);
        Previous previous = jdbc.query("""
                SELECT id,order_id,request_fingerprint
                FROM crm.sales_return_request
                WHERE tenant_id=? AND request_key=?
                """, rs -> rs.next()
                ? new Previous(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getString(3)) : null,
                tenant,requestKey);
        if (previous != null) {
            if (!previous.orderId().equals(own.orderId())
                    || !previous.fingerprint().equals(fingerprint))
                throw new IllegalStateException("RETURN_IDEMPOTENCY_CONFLICT");
            return new Created(tenant,previous.id(),own.orderId(),lineId,
                    quantity,SalesReturnState.REQUESTED.name());
        }

        UUID handoverId = jdbc.query("""
                SELECT id FROM crm.sales_pickup_handover
                WHERE tenant_id=? AND order_id=?
                """,rs -> rs.next()?rs.getObject(1,UUID.class):null,
                tenant,own.orderId());
        if (handoverId == null)
            throw new IllegalStateException("RETURN_SOURCE_RECEIPT_REQUIRED");

        BigDecimal fulfilled = jdbc.query("""
                SELECT hl.quantity
                FROM crm.sales_pickup_handover_line hl
                JOIN crm.inv_stock_reservation r
                  ON r.tenant_id=hl.tenant_id AND r.id=hl.reservation_id
                WHERE hl.tenant_id=? AND hl.handover_id=?
                  AND r.order_id=? AND r.order_line_id=?
                """, rs -> rs.next()?rs.getBigDecimal(1):null,
                tenant,handoverId,own.orderId(),lineId);
        if (fulfilled == null)
            throw new IllegalStateException("RETURN_LINE_NOT_FULFILLED");

        BigDecimal alreadyAllocated = jdbc.queryForObject("""
                SELECT COALESCE(SUM(rl.quantity),0)
                FROM crm.sales_return_line rl
                JOIN crm.sales_return_request rr
                  ON rr.tenant_id=rl.tenant_id AND rr.id=rl.return_id
                WHERE rl.tenant_id=? AND rl.order_id=? AND rl.order_line_id=?
                  AND rr.state NOT IN ('REJECTED','CANCELLED_BY_BUYER')
                """,BigDecimal.class,tenant,own.orderId(),lineId);
        if (alreadyAllocated == null || alreadyAllocated.add(BigDecimal.valueOf(quantity))
                .compareTo(fulfilled)>0)
            throw new IllegalStateException("RETURN_QUANTITY_EXCEEDS_FULFILLED");

        UUID id=UUID.randomUUID();
        Instant now=Instant.now();
        jdbc.update("""
                INSERT INTO crm.sales_return_request
                 (tenant_id,id,order_id,source_handover_id,buyer_user_id,
                  request_key,request_fingerprint,state,reason_code,
                  policy_version,created_at,row_version)
                VALUES (?,?,?,?,?,?,?,'REQUESTED',?,?,?,0)
                """,tenant,id,own.orderId(),handoverId,actor,requestKey,
                fingerprint,reasonCode,policyVersion,Timestamp.from(now));
        jdbc.update("""
                INSERT INTO crm.sales_return_line
                  (tenant_id,return_id,order_id,order_line_id,quantity)
                VALUES (?,?,?,?,?)
                """,tenant,id,own.orderId(),lineId,quantity);
        jdbc.update("""
                INSERT INTO crm.sales_return_event
                  (tenant_id,id,return_id,actor_user_id,event_type,occurred_at)
                VALUES (?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),id,actor,"REQUESTED",Timestamp.from(now));
        return new Created(tenant,id,own.orderId(),lineId,quantity,
                SalesReturnState.REQUESTED.name());
    }

    private static boolean safeToken(String value,int limit) {
        return value != null && !value.isBlank() && value.length()<=limit
                && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }

    private static String fingerprint(UUID actor,UUID orderId,UUID lineId,
                                      int qty,String reason,String policy) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            String value=actor+"|"+orderId+"|"+lineId+"|"+qty+"|"+reason+"|"+policy;
            return HexFormat.of().formatHex(
                    md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA256 unavailable",e);
        }
    }
}
