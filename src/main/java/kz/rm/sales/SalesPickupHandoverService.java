package kz.rm.sales;

import jakarta.annotation.PostConstruct;
import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import kz.rm.iam.WarehouseStaffScopeService;
import kz.rm.inventory.OrderInventoryPort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * QR-06: a buyer-owned one-time pickup proof, not an ORDER_REFERENCE.
 * Raw secret is only returned on issuance, and NEVER persisted or logged.
 * Every new handover requires two fresh tenant IAM permissions, a warehouse
 * assignment, valid unexpired credential and all FULL ACTIVE reservations.
 *
 * Handover transaction: order lock -> credential -> inventory port movement
 * -> order COMPLETED event -> immutable receipt -> proof CONSUMED.
 * All writes share one Spring/PostgreSQL transaction, no offline success.
 */
@Service
public class SalesPickupHandoverService {
    public record Issued(String rawProof,Instant expiresAt,int generation) {}
    public record Preview(UUID orderId,UUID warehouseId,String status,int lines,
                          Instant expiresAt) {}
    public record Handover(UUID handoverId,UUID orderId,UUID warehouseId,
                           String status,int fulfilledLines,Instant completedAt) {}
    private record Locator(UUID orderId,UUID credentialId) {}
    private record Order(String state,long version) {}
    private record Credential(UUID id,UUID orderId,UUID warehouseId,String state,
                              Instant expiresAt,int generation) {}
    private record Reservation(UUID id,UUID itemId,BigDecimal quantity) {}
    private record Recorded(UUID orderId,UUID warehouseId,UUID staffId,
                            String fingerprint,Handover result) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TTL=Duration.ofMinutes(10); // UAT policy
    private final JdbcTemplate jdbc;
    private final SalesOrderQrReferenceService orderQr;
    private final SalesOrderPreparationService preparation;
    private final TenantAuthorization authorization;
    private final WarehouseStaffScopeService scopes;
    private final OrderInventoryPort inventory;
    private String nowSql;

    public SalesPickupHandoverService(JdbcTemplate jdbc,
                     SalesOrderQrReferenceService orderQr,
                     SalesOrderPreparationService preparation,
                     TenantAuthorization authorization,
                     WarehouseStaffScopeService scopes,
                     OrderInventoryPort inventory) {
        this.jdbc=jdbc;
        this.orderQr=orderQr;
        this.preparation=preparation;
        this.authorization=authorization;
        this.scopes=scopes;
        this.inventory=inventory;
    }
    @PostConstruct
    void init() {
        String database=jdbc.execute((ConnectionCallback<String>) c ->
                c.getMetaData().getDatabaseProductName());
        nowSql=(database!=null &&
                database.toLowerCase(java.util.Locale.ROOT).contains("postgresql"))
                ?"SELECT clock_timestamp()":"SELECT CURRENT_TIMESTAMP";
    }

    /**
     * An issue request with an existing requestKey never returns a cached raw
     * proof: it fails as RECOVERY_REQUIRED. Buyer can present a NEW key to
     * revoke the old secret and issue another generation. No secret replay.
     */
    @Transactional
    public Issued issue(VerifiedActor buyer,String orderPublicRef,String requestKey){
        requireKey(requestKey);
        var verifiedOrder=orderQr.readOwn(buyer,orderPublicRef);
        UUID orderId=verifiedOrder.orderId();
        UUID tenant=jdbc.query("""
                SELECT r.tenant_id FROM crm.sales_order_qr_reference r
                WHERE r.public_ref=? AND r.order_id=? AND r.state='ACTIVE'
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,
                orderPublicRef,orderId);
        if(tenant==null)throw new SecurityException("ORDER_NOT_ACCESSIBLE");
        Order order=lockOrder(tenant,orderId);
        if(!"READY_FOR_PICKUP".equals(order.state()))
            throw new IllegalStateException("ORDER_NOT_READY");
        List<UUID> warehouses=jdbc.query("""
                SELECT DISTINCT location_id FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                ORDER BY location_id
                """,(rs,n)->rs.getObject(1,UUID.class),tenant,orderId);
        if(warehouses.size()!=1)throw new IllegalStateException("WAREHOUSE_NOT_UNIQUE");
        UUID warehouse=warehouses.getFirst();
        preparation.assertReadyPickedAndReserved(tenant,orderId,warehouse);
        Integer reused=jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_pickup_credential
                WHERE tenant_id=? AND request_key=?
                """,Integer.class,tenant,requestKey);
        if(reused!=null&&reused!=0)
            throw new IllegalStateException("PICKUP_PROOF_RECOVERY_REQUIRED");
        Integer max=jdbc.queryForObject("""
                SELECT COALESCE(MAX(generation),0)
                FROM crm.sales_pickup_credential
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,tenant,orderId);
        int generation=(max==null?0:max)+1;
        Instant now=serverNow();
        jdbc.update("""
                UPDATE crm.sales_pickup_credential
                SET state='REVOKED',active_order_id=NULL,revoked_at=?,
                    row_version=row_version+1
                WHERE tenant_id=? AND order_id=? AND state='ISSUED'
                """,Timestamp.from(now),tenant,orderId);
        byte[] bytes=new byte[32];
        RANDOM.nextBytes(bytes);
        String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        byte[] digest=digest(tenant,warehouse,raw);
        jdbc.update("""
                INSERT INTO crm.sales_pickup_credential
                  (tenant_id,id,order_id,warehouse_id,active_order_id,
                   credential_digest,generation,state,issued_at,expires_at,
                   issued_by,request_key)
                VALUES (?,?,?,?,?,?,?,'ISSUED',?,?,?,?)
                """,tenant,UUID.randomUUID(),orderId,warehouse,orderId,
                digest,generation,Timestamp.from(now),
                Timestamp.from(now.plus(TTL)),buyer.userId(),requestKey);
        audit(tenant,buyer.userId(),orderId,"PICKUP_CREDENTIAL_ISSUED");
        return new Issued(raw,now.plus(TTL),generation);
    }

    /**
     * Preview never marks a credential consumed and never mutates the order,
     * reservation or inventory ledger. It is not physical handover.
     */
    @Transactional
    public Preview preview(VerifiedActor staff,UUID tenant,UUID warehouse,
                           String rawProof) {
        requireProof(rawProof);
        if(staff==null||tenant==null||warehouse==null)
            throw new SecurityException("WAREHOUSE_STAFF_REQUIRED");
        UUID actor=staff.userId();
        authorization.requireForCommand(actor,tenant,DomainPermission.SALES_ORDER_READ);
        authorization.requireForCommand(actor,tenant,DomainPermission.INVENTORY_STOCK_READ);
        Locator target=lookup(tenant,warehouse,rawProof);
        if(target==null)throw new SecurityException("PICKUP_CREDENTIAL_UNAVAILABLE");
        Order order=lockOrder(tenant,target.orderId());
        scopes.requireAssigned(actor,tenant,warehouse);
        Credential proof=lockCredential(tenant,target.credentialId());
        validateProof(proof,target.orderId(),warehouse);
        if(!"READY_FOR_PICKUP".equals(order.state()))
            throw new IllegalStateException("ORDER_NOT_READY");
        int count=preparation.assertReadyPickedAndReserved(tenant,target.orderId(),
                warehouse).size();
        return new Preview(target.orderId(),warehouse,order.state(),count,
                proof.expiresAt());
    }

    @Transactional
    public Handover complete(VerifiedActor staff,UUID tenant,UUID warehouse,
                             String rawProof,boolean explicitConfirm,
                             long expectedOrderVersion,String requestKey) {
        if(!explicitConfirm)throw new SecurityException("EXPLICIT_HANDOVER_CONFIRM_REQUIRED");
        requireKey(requestKey);
        requireProof(rawProof);
        if(staff==null||tenant==null||warehouse==null||expectedOrderVersion<0)
            throw new SecurityException("WAREHOUSE_STAFF_REQUIRED");
        UUID actor=staff.userId();
        var sales=authorization.requireForCommand(actor,tenant,
                DomainPermission.SALES_ORDER_FULFILL);
        var stock=authorization.requireForCommand(actor,tenant,
                DomainPermission.INVENTORY_FULFILL);
        Locator target=lookup(tenant,warehouse,rawProof);
        if(target==null)throw new SecurityException("PICKUP_CREDENTIAL_UNAVAILABLE");
        Order order=lockOrder(tenant,target.orderId());
        scopes.requireAssigned(actor,tenant,warehouse);
        String fingerprint=fingerprint(actor,target.orderId(),warehouse,
                rawProof,expectedOrderVersion);
        Recorded prior=jdbc.query("""
                SELECT order_id,warehouse_id,staff_actor_id,request_sha256,
                       id,happened_at FROM crm.sales_pickup_handover
                WHERE tenant_id=? AND request_key=?
                """,rs->rs.next()?new Recorded(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getString(4),new Handover(rs.getObject(5,UUID.class),
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                "COMPLETED",0,rs.getTimestamp(6).toInstant())):null,tenant,requestKey);
        if(prior!=null){
            if(!prior.orderId().equals(target.orderId())
                    ||!prior.warehouseId().equals(warehouse)
                    ||!prior.staffId().equals(actor)
                    ||!prior.fingerprint().equals(fingerprint))
                throw new IllegalStateException("HANDOVER_IDEMPOTENCY_CONFLICT");
            Integer lineCount=jdbc.queryForObject("""
                    SELECT count(*) FROM crm.sales_pickup_handover_line
                    WHERE tenant_id=? AND handover_id=?
                    """,Integer.class,tenant,prior.result().handoverId());
            if(lineCount==null||lineCount<1)
                throw new IllegalStateException("HANDOVER_RECEIPT_INCONSISTENT");
            return new Handover(prior.result().handoverId(),target.orderId(),warehouse,
                    "COMPLETED",lineCount,prior.result().completedAt());
        }

        if(!"READY_FOR_PICKUP".equals(order.state())
                ||order.version()!=expectedOrderVersion)
            throw new IllegalStateException("ORDER_NOT_READY_OR_VERSION_CHANGED");
        Credential proof=lockCredential(tenant,target.credentialId());
        validateProof(proof,target.orderId(),warehouse);
        List<SalesOrderPreparationService.PickLine> lines=
                preparation.assertReadyPickedAndReserved(tenant,target.orderId(),warehouse);
        List<Reservation> reservations=jdbc.query("""
                SELECT r.id,r.item_id,r.quantity
                FROM crm.inv_stock_reservation r
                JOIN crm.sales_order_line l
                 ON l.tenant_id=r.tenant_id AND l.id=r.order_line_id
                WHERE r.tenant_id=? AND r.order_id=? AND r.state='ACTIVE'
                  AND r.location_id=?
                ORDER BY l.catalog_item_id_snapshot,l.id
                """,(rs,n)->new Reservation(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getBigDecimal(3)),
                tenant,target.orderId(),warehouse);
        if(reservations.size()!=lines.size()||reservations.isEmpty())
            throw new IllegalStateException("ORDER_FULL_RESERVATION_REQUIRED");
        // All reservations are posted via the authoritative INVENTORY port.
        // The same transaction rolls back ALL preceding movements if the final
        // line fails, including the order status, handover and credential.
        for(Reservation reservation:reservations)
            inventory.fulfill(sales,stock,reservation.id());

        Timestamp now=Timestamp.from(serverNow());
        int updated=jdbc.update("""
                UPDATE crm.sales_order SET status='COMPLETED',row_version=row_version+1
                WHERE tenant_id=? AND id=? AND status='READY_FOR_PICKUP'
                  AND row_version=?
                """,tenant,target.orderId(),expectedOrderVersion);
        if(updated!=1)throw new IllegalStateException("ORDER_HANDOVER_VERSION_CONFLICT");
        UUID event=UUID.randomUUID(),handover=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.sales_order_status_event
                 (tenant_id,id,order_id,event_type,occurred_at)
                VALUES (?,?,?,'COMPLETED',?)
                """,tenant,event,target.orderId(),now);
        jdbc.update("""
                INSERT INTO crm.sales_pickup_handover
                 (tenant_id,id,order_id,warehouse_id,credential_id,staff_actor_id,
                  request_key,request_sha256,status_event_id,happened_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,tenant,handover,target.orderId(),warehouse,target.credentialId(),
                actor,requestKey,fingerprint,event,now);
        for(Reservation reservation:reservations){
            UUID movement=jdbc.queryForObject("""
                    SELECT id FROM crm.inv_stock_movement
                    WHERE tenant_id=? AND reservation_id=?
                      AND movement_kind='FULFILLMENT'
                    """,UUID.class,tenant,reservation.id());
            if(movement==null)throw new IllegalStateException("MOVEMENT_PROOF_MISSING");
            jdbc.update("""
                    INSERT INTO crm.sales_pickup_handover_line
                     (tenant_id,handover_id,reservation_id,movement_id,quantity)
                    VALUES (?,?,?,?,?)
                    """,tenant,handover,reservation.id(),movement,reservation.quantity());
        }
        updated=jdbc.update("""
                UPDATE crm.sales_pickup_credential
                SET state='CONSUMED',active_order_id=NULL,consumed_at=?,
                    row_version=row_version+1
                WHERE tenant_id=? AND id=? AND state='ISSUED'
                  AND active_order_id=?
                """,now,tenant,target.credentialId(),target.orderId());
        if(updated!=1)throw new IllegalStateException("PICKUP_CREDENTIAL_RACE");
        audit(tenant,actor,target.orderId(),"PICKUP_HANDOVER_COMPLETED");
        return new Handover(handover,target.orderId(),warehouse,"COMPLETED",
                reservations.size(),now.toInstant());
    }

    private Locator lookup(UUID tenant,UUID warehouse,String raw){
        byte[] hash=digest(tenant,warehouse,raw);
        return jdbc.query("""
                SELECT order_id,id FROM crm.sales_pickup_credential
                WHERE tenant_id=? AND warehouse_id=? AND credential_digest=?
                """,rs->rs.next()?new Locator(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class)):null,tenant,warehouse,hash);
    }

    private Credential lockCredential(UUID tenant,UUID id){
        return jdbc.query("""
                SELECT id,order_id,warehouse_id,state,expires_at,generation
                FROM crm.sales_pickup_credential
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?new Credential(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getInt(6)):null,
                tenant,id);
    }

    private void validateProof(Credential proof,UUID order,UUID warehouse){
        if(proof==null||!proof.orderId().equals(order)
                ||!proof.warehouseId().equals(warehouse)
                ||!"ISSUED".equals(proof.state()))
            throw new SecurityException("PICKUP_CREDENTIAL_UNAVAILABLE");
        // PostgreSQL clock_timestamp() is the actual database current time,
        // NOT the transaction start timestamp or scan/preview time.
        if(!serverNow().isBefore(proof.expiresAt()))
            throw new SecurityException("PICKUP_CREDENTIAL_EXPIRED");
    }

    private Order lockOrder(UUID tenant,UUID order){
        return jdbc.query("""
                SELECT status,row_version FROM crm.sales_order
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())throw new SecurityException("ORDER_NOT_ACCESSIBLE");
                    return new Order(rs.getString(1),rs.getLong(2));
                },tenant,order);
    }

    private Instant serverNow(){
        return jdbc.queryForObject(nowSql,Timestamp.class).toInstant()
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static byte[] digest(UUID tenant,UUID warehouse,String raw){
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(("RM/PICKUP/QR-06/V1|"+tenant+"|"+warehouse+"|"+raw)
                        .getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){
            throw new IllegalStateException("SHA-256 unavailable",e);
        }
    }
    private static String fingerprint(UUID actor,UUID order,UUID wh,
                                      String raw,long version){
        String canonical="RM/HANDOVER/V1|"+actor+"|"+order+"|"+wh+"|"
                +HexFormat.of().formatHex(digestFromProof(raw))+"|"+version;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){
            throw new IllegalStateException("SHA-256 unavailable",e);
        }
    }
    private static byte[] digestFromProof(String proof){
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(proof.getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){
            throw new IllegalStateException("SHA-256 unavailable",e);
        }
    }
    private static void requireKey(String key){
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("PICKUP_REQUEST_KEY_INVALID");
    }
    private static void requireProof(String proof){
        if(proof==null||!proof.matches("[A-Za-z0-9_-]{43}"))
            throw new SecurityException("PICKUP_CREDENTIAL_UNAVAILABLE");
    }
    private void audit(UUID tenant,UUID actor,UUID order,String action){
        jdbc.update("""
                INSERT INTO crm.audit_event
                 (tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)
                VALUES (?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),actor,order,action,
                Timestamp.from(Instant.now()));
    }
}
