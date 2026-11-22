package kz.rm.sales;

import jakarta.annotation.PostConstruct;
import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * QR-03B: stable, nonsecret reference to a SALES order.
 *
 * QR is a locator, NEVER an order-read or pickup credential. Issue at PLACED
 * participates in the existing AUTOORDER transaction; buyer ownership comes
 * only from a verified global IAM user and a confirmed SALES association.
 * All authenticated reads revalidate current identity/tenant permissions.
 *
 * No controller, URL, scanner, inventory mutation or new SALES order ledger.
 */
@Service
public class SalesOrderQrReferenceService {
    public record Reference(String publicRef, int generation, long rowVersion,
                            Instant issuedAt) {}
    public record Line(String title, int quantity, BigDecimal unitPrice,
                       String currency) {}
    public record StatusEvent(String eventType, Instant occurredAt) {}
    public record OrderView(String publicRef, UUID orderId, String status,
                            BigDecimal totalAmount, String currency,
                            Instant createdAt, List<Line> lines,
                            List<StatusEvent> events) {}
    private record Ordered(UUID tenant, UUID id, String status, UUID owner) {}
    private record Locator(UUID tenant, UUID order) {}
    private record Locked(UUID id, UUID tenant, String status,
                          BigDecimal total, String currency, Instant createdAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private String buyerLockSql;

    public SalesOrderQrReferenceService(JdbcTemplate jdbc,
                                        TenantAuthorization authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @PostConstruct
    void configureLocks() {
        String product = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        buyerLockSql = "SELECT status FROM public.iam_user_account WHERE id=? "
                + (product != null && product.toLowerCase(java.util.Locale.ROOT)
                .contains("postgresql") ? "FOR SHARE" : "FOR UPDATE");
    }

    /**
     * Only trusted checkout may call this method. The order and its QR must
     * commit or roll back together, including status event and checkout receipt.
     * The method requires a caller-owned write transaction; it never exposes
     * a reference to an unauthenticated checkout response.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issueAtPlaced(UUID tenant, UUID orderId) {
        if (tenant == null || orderId == null)
            throw new IllegalArgumentException("SALES order required");
        Ordered order = lockOrder(tenant, orderId);
        if (!"PLACED".equals(order.status()))
            throw new IllegalStateException("ORDER_QR_NOT_AT_PLACED");
        if (active(tenant, orderId) != null) return; // natural-key replay
        if (lastGeneration(tenant, orderId) != 0)
            throw new IllegalStateException("ORDER_QR_REVOKED");
        insert(tenant, orderId, 1, null);
    }

    /**
     * Buyer must prove ownership through a live verified OIDC actor AND the
     * existing email verification association. No client-supplied user ID,
     * email, or bearer QR is accepted as a substitute.
     *
     * A legacy order created before QR-03B receives a generation-1 reference
     * only after that buyer proof; no invented ownership backfill.
     */
    @Transactional
    public Reference ownReference(VerifiedActor buyer, UUID tenant, UUID orderId) {
        UUID actor = admitBuyer(buyer);
        Ordered order = lockOrder(tenant, orderId);
        if (!actor.equals(order.owner()))
            throw new SecurityException("ORDER_NOT_ACCESSIBLE");
        Reference existing = active(tenant, orderId);
        if (existing != null) return existing;
        if (lastGeneration(tenant, orderId) != 0)
            throw new IllegalStateException("ORDER_QR_REVOKED");
        return insert(tenant, orderId, 1, actor);
    }

    /**
     * Buyer-controlled recovery of a shared reference. Old public refs are
     * immutable historical rows, revoked before a generation-2+ reference is
     * inserted under the SAME order lock and UNIQUE(tenant,active_order_id).
     */
    @Transactional
    public Reference rotateOwned(VerifiedActor buyer, UUID tenant, UUID orderId,
                                 long expectedReferenceVersion) {
        UUID actor = admitBuyer(buyer);
        Ordered order = lockOrder(tenant, orderId);
        if (!actor.equals(order.owner()))
            throw new SecurityException("ORDER_NOT_ACCESSIBLE");
        Reference existing = active(tenant, orderId);
        if (existing == null || existing.rowVersion() != expectedReferenceVersion)
            throw new IllegalStateException("ORDER_QR_VERSION_CONFLICT");
        int updated = jdbc.update("""
                UPDATE crm.sales_order_qr_reference
                SET state='REVOKED', active_order_id=NULL,
                    revoked_at=?, row_version=row_version+1
                WHERE tenant_id=? AND order_id=? AND public_ref=?
                  AND state='ACTIVE' AND row_version=?
                """, Timestamp.from(Instant.now()), tenant, orderId,
                existing.publicRef(), expectedReferenceVersion);
        if (updated != 1) throw new IllegalStateException("ORDER_QR_VERSION_CONFLICT");
        Reference next = insert(tenant, orderId,
                lastGeneration(tenant, orderId) + 1, actor);
        jdbc.update("""
                INSERT INTO crm.audit_event
                  (tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)
                VALUES (?,?,?,?,?,?)
                """, tenant, UUID.randomUUID(), actor, orderId,
                "ORDER_QR_ROTATED", Timestamp.from(Instant.now()));
        return next;
    }

    @Transactional
    public OrderView readOwn(VerifiedActor buyer, String publicRef) {
        UUID actor = admitBuyer(buyer);
        Locator locator = locate(publicRef);
        // Use a single consistent row-lock DAG: IAM actor -> SALES order.
        // Do NOT join FOR UPDATE with QR, which could acquire QR before order.
        Locked locked = jdbc.query("""
                SELECT id,tenant_id,status,total_amount,currency,created_at
                FROM crm.sales_order
                WHERE tenant_id=? AND id=? AND buyer_user_id=?
                FOR UPDATE
                """, rs -> rs.next() ? mapOrder(rs) : null,
                locator.tenant(), locator.order(), actor);
        if (locked == null)
            throw new SecurityException("ORDER_NOT_ACCESSIBLE");
        // A rotation may have committed between alias lookup and order lock.
        requireCurrentRef(locator, publicRef);
        return assemble(locked, publicRef);
    }

    /** A seller may inspect its OWN tenant order only with SALES_ORDER_READ.
     * It cannot use an order ref from another tenant or infer the buyer email. */
    @Transactional
    public OrderView readSeller(VerifiedActor seller, UUID tenant, String publicRef) {
        if (seller == null || tenant == null)
            throw new SecurityException("Verified seller and tenant required");
        authorization.requireForCommand(seller.userId(), tenant,
                DomainPermission.SALES_ORDER_READ);
        Locator locator = locate(publicRef);
        if (!tenant.equals(locator.tenant()))
            throw new SecurityException("ORDER_NOT_ACCESSIBLE");
        Locked locked = jdbc.query("""
                SELECT id,tenant_id,status,total_amount,currency,created_at
                FROM crm.sales_order
                WHERE tenant_id=? AND id=?
                FOR UPDATE
                """, rs -> rs.next() ? mapOrder(rs) : null, tenant, locator.order());
        if (locked == null)
            throw new SecurityException("ORDER_NOT_ACCESSIBLE");
        requireCurrentRef(locator, publicRef);
        return assemble(locked, publicRef);
    }

    private Locator locate(String publicRef) {
        validateRef(publicRef);
        Locator result = jdbc.query("""
                SELECT tenant_id,order_id
                FROM crm.sales_order_qr_reference
                WHERE public_ref=? AND state='ACTIVE'
                  AND active_order_id=order_id
                """, rs -> rs.next() ?
                new Locator(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class)) : null, publicRef);
        if (result == null)
            throw new SecurityException("ORDER_NOT_ACCESSIBLE");
        return result;
    }

    private void requireCurrentRef(Locator locator, String publicRef) {
        Reference active = active(locator.tenant(), locator.order());
        if (active == null || !active.publicRef().equals(publicRef))
            throw new SecurityException("ORDER_NOT_ACCESSIBLE");
    }

    /** Seller may reprint an EXISTING reference; cannot mint a buyer QR by
     * just possessing an order UUID. Initial and legacy issuance stay fenced. */
    @Transactional
    public Reference sellerReprint(VerifiedActor seller, UUID tenant, UUID orderId) {
        if (seller == null || tenant == null || orderId == null)
            throw new SecurityException("Verified seller required");
        authorization.requireForCommand(seller.userId(), tenant,
                DomainPermission.SALES_ORDER_READ);
        lockOrder(tenant, orderId);
        Reference result = active(tenant, orderId);
        if (result == null) throw new IllegalStateException("ORDER_QR_UNAVAILABLE");
        return result;
    }

    private Locked mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Locked(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3),
                rs.getBigDecimal(4), rs.getString(5),
                rs.getTimestamp(6).toInstant());
    }

    private OrderView assemble(Locked order, String ref) {
        List<Line> lines = jdbc.query("""
                SELECT title_snapshot,quantity,unit_price_snapshot,currency
                FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                ORDER BY id
                """, (rs, n) -> new Line(rs.getString(1), rs.getInt(2),
                rs.getBigDecimal(3), rs.getString(4)),
                order.tenant(), order.id());
        List<StatusEvent> events = jdbc.query("""
                SELECT event_type,occurred_at
                FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=?
                ORDER BY occurred_at,id
                """, (rs, n) -> new StatusEvent(rs.getString(1),
                rs.getTimestamp(2).toInstant()), order.tenant(), order.id());
        if (lines.isEmpty() || events.isEmpty())
            throw new IllegalStateException("ORDER_HISTORY_INCOMPLETE");
        return new OrderView(ref, order.id(), order.status(),
                order.total(), order.currency(), order.createdAt(),
                List.copyOf(lines), List.copyOf(events));
    }

    private Ordered lockOrder(UUID tenant, UUID orderId) {
        if (tenant == null || orderId == null)
            throw new IllegalArgumentException("SALES order required");
        Ordered result = jdbc.query("""
                SELECT tenant_id,id,status,buyer_user_id
                FROM crm.sales_order WHERE tenant_id=? AND id=?
                FOR UPDATE
                """, rs -> rs.next() ?
                new Ordered(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, UUID.class)) : null,
                tenant, orderId);
        if (result == null) throw new SecurityException("ORDER_NOT_ACCESSIBLE");
        return result;
    }

    private UUID admitBuyer(VerifiedActor buyer) {
        if (buyer == null) throw new SecurityException("VERIFIED_BUYER_REQUIRED");
        UUID user = buyer.userId();
        String status = jdbc.query(buyerLockSql, rs ->
                rs.next() ? rs.getString(1) : null, user);
        if (!"ACTIVE".equals(status))
            throw new SecurityException("VERIFIED_BUYER_REQUIRED");
        Integer verified = jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity
                WHERE user_id=? AND lifecycle_state='CURRENT'
                  AND verified_at IS NOT NULL
                """, Integer.class, user);
        if (verified == null || verified == 0)
            throw new SecurityException("VERIFIED_BUYER_REQUIRED");
        return user;
    }

    private Reference active(UUID tenant, UUID order) {
        List<Reference> refs = jdbc.query("""
                SELECT public_ref,generation,row_version,issued_at
                FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                  AND active_order_id=order_id
                """, (rs, n) -> new Reference(rs.getString(1),rs.getInt(2),
                rs.getLong(3),rs.getTimestamp(4).toInstant()),tenant,order);
        return refs.isEmpty() ? null : refs.getFirst();
    }

    private int lastGeneration(UUID tenant, UUID order) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(generation),0)
                FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=?
                """, Integer.class, tenant, order);
        return max == null ? 0 : max;
    }

    private Reference insert(UUID tenant, UUID orderId, int generation,
                             UUID issuedBy) {
        byte[] random = new byte[24]; // 192-bit opaque, not a bearer secret
        RANDOM.nextBytes(random);
        String publicRef = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(random);
        Instant now = Instant.now().truncatedTo(
                java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO crm.sales_order_qr_reference
                  (tenant_id,id,order_id,active_order_id,public_ref,generation,
                   state,row_version,issued_at,issued_by)
                VALUES (?,?,?,?,?,?,'ACTIVE',0,?,?)
                """, tenant, UUID.randomUUID(), orderId, orderId,
                publicRef, generation, Timestamp.from(now), issuedBy);
        return new Reference(publicRef, generation, 0, now);
    }

    private static void validateRef(String ref) {
        if (ref == null || !ref.matches("[A-Za-z0-9_-]{32}"))
            throw new SecurityException("ORDER_NOT_ACCESSIBLE");
    }
}
