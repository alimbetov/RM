package kz.rm.sales;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
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
 * QR-08/R0B: SALES packaging offers and immutable accepted price snapshots.
 * No controller, payment, refund, reservation or stock movement.
 * Lock order: IAM -> SALES order -> service line. Concurrent terminal decisions
 * serialize on the original SALES order and service row.
 */
@Service
public class OrderPackagingService {
    public enum Choice { ACCEPT, REJECT, CANCEL }
    public record Result(UUID tenantId,UUID orderId,UUID serviceLineId,
                         String state,long revision,String serviceCode,
                         int catalogVersion,BigDecimal price,String currency,
                         BigDecimal taxRate,String policyVersion) {}
    private record Order(UUID tenant,UUID id,UUID buyer,String state,String currency) {}
    private record Head(int version,boolean active) {}
    private record Line(UUID id,UUID order,UUID buyer,String code,int version,
                        String state,long revision,BigDecimal price,String currency,
                        BigDecimal tax,String policy) {}
    private record Receipt(UUID actor,String action,String hash,UUID line,
                           String state,long revision) {}
    private final JdbcTemplate jdbc;
    private final TenantAuthorization auth;
    private final SalesOrderQrReferenceService orders;
    private final PackagingServiceCatalogService catalog;

    public OrderPackagingService(JdbcTemplate jdbc,TenantAuthorization auth,
                                 SalesOrderQrReferenceService orders,
                                 PackagingServiceCatalogService catalog) {
        this.jdbc=jdbc;this.auth=auth;this.orders=orders;this.catalog=catalog;
    }

    /** Seller may offer only for an existing eligible SALES order. */
    @Transactional
    public Result offer(VerifiedActor seller,UUID tenant,UUID orderId,String code,
                        String requestKey) {
        if(seller==null||tenant==null||orderId==null||!validCode(code)||!validKey(requestKey))
            throw new IllegalArgumentException("PACKAGING_INPUT_INVALID");
        UUID actor=seller.userId();
        auth.requireForCommand(actor,tenant,DomainPermission.SALES_PACKAGING_MANAGE);
        Order order=lockOrder(tenant,orderId);
        String hash=sha(actor,"OFFER",orderId,code,0);
        Receipt saved=receipt(tenant,requestKey);
        if(saved!=null)return replay(tenant,order,saved,actor,"OFFER",hash);
        requireMutableOrder(order);
        if(order.buyer()==null)throw new IllegalStateException("PACKAGING_BUYER_NOT_LINKED");
        Head head=jdbc.query("""
            SELECT current_version,active FROM crm.sales_packaging_catalog_head
            WHERE tenant_id=? AND service_code=?
            """,rs->rs.next()?new Head(rs.getInt(1),rs.getBoolean(2)):null,tenant,code);
        if(head==null||!head.active())throw new IllegalStateException("PACKAGING_CATALOG_INACTIVE");
        PackagingServiceCatalogService.Version version=catalog.version(tenant,code,head.version());
        if(!order.currency().equals(version.currency()))
            throw new IllegalStateException("PACKAGING_CURRENCY_MISMATCH");
        UUID lineId=UUID.randomUUID();
        Instant now=Instant.now();
        jdbc.update("""
            INSERT INTO crm.sales_order_service_line
             (tenant_id,id,order_id,service_code,catalog_version,
              name_snapshot,price_snapshot,currency_snapshot,tax_rate_snapshot,
              policy_snapshot,status,row_version,offered_by,buyer_user_id,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,'OFFERED',0,?,?,?)
            """,tenant,lineId,orderId,code,version.number(),version.name(),
            version.price(),version.currency(),version.taxRate(),version.policyVersion(),
            actor,order.buyer(),Timestamp.from(now));
        event(tenant,lineId,actor,"OFFER","OFFERED",0,now);
        writeReceipt(tenant,requestKey,actor,"OFFER",hash,lineId,"OFFERED",0,now);
        return new Result(tenant,orderId,lineId,"OFFERED",0,code,version.number(),
            version.price(),version.currency(),version.taxRate(),version.policyVersion());
    }

    /**
     * Buyer ownership is validated through existing live QR ownership path.
     * A QR is never a permission by itself; verified OIDC identity is required.
     */
    @Transactional
    public Result decide(VerifiedActor buyer,String orderPublicRef,UUID serviceLineId,
                         long expectedRevision,Choice choice,String requestKey) {
        if(buyer==null||orderPublicRef==null||serviceLineId==null
           ||expectedRevision<0||choice==null||!validKey(requestKey))
            throw new IllegalArgumentException("PACKAGING_INPUT_INVALID");
        var owned=orders.readOwn(buyer,orderPublicRef);
        UUID actor=buyer.userId();
        Order order=jdbc.query("""
            SELECT tenant_id,id,buyer_user_id,status,currency
            FROM crm.sales_order WHERE id=? AND buyer_user_id=?
            """,rs->{
                if(!rs.next())throw new SecurityException("PACKAGING_ORDER_NOT_ACCESSIBLE");
                Order found=mapOrder(rs);
                if(rs.next())throw new SecurityException("PACKAGING_ORDER_AMBIGUOUS");
                return found;
            },owned.orderId(),actor);
        // The QR ownership service already locks exactly this SALES order row.
        String action=choice.name();
        String hash=sha(actor,action,serviceLineId,null,expectedRevision);
        Receipt previous=receipt(order.tenant(),requestKey);
        if(previous!=null)return replay(order.tenant(),order,previous,actor,action,hash);
        Line line=lockLine(order.tenant(),serviceLineId);
        if(line==null||!line.order().equals(order.id())||!line.buyer().equals(actor))
            throw new SecurityException("PACKAGING_NOT_ACCESSIBLE");
        requireMutableOrder(order);
        if(line.revision()!=expectedRevision)
            throw new IllegalStateException("PACKAGING_VERSION_CONFLICT");
        if(!"OFFERED".equals(line.state()))
            throw new IllegalStateException("PACKAGING_DECISION_TERMINAL");
        String state=switch(choice) {
            case ACCEPT -> "ACCEPTED";
            case REJECT -> "REJECTED";
            case CANCEL -> "CANCELLED";
        };
        int updated=jdbc.update("""
            UPDATE crm.sales_order_service_line
            SET status=?,row_version=row_version+1,decided_at=?
            WHERE tenant_id=? AND id=? AND status='OFFERED' AND row_version=?
            """,state,Timestamp.from(Instant.now()),
            order.tenant(),serviceLineId,expectedRevision);
        if(updated!=1)throw new IllegalStateException("PACKAGING_VERSION_CONFLICT");
        Instant now=Instant.now();
        event(order.tenant(),serviceLineId,actor,action,state,expectedRevision+1,now);
        writeReceipt(order.tenant(),requestKey,actor,action,hash,serviceLineId,
                     state,expectedRevision+1,now);
        return result(order.tenant(),line,state,expectedRevision+1);
    }

    private Result replay(UUID tenant,Order order,Receipt saved,UUID actor,
                          String action,String hash) {
        if(!actor.equals(saved.actor())||!action.equals(saved.action())
           ||!hash.equals(saved.hash()))
            throw new IllegalStateException("PACKAGING_IDEMPOTENCY_CONFLICT");
        Line line=lockLine(tenant,saved.line());
        if(line==null||!line.order().equals(order.id()))
            throw new IllegalStateException("PACKAGING_IDEMPOTENCY_CONFLICT");
        return result(tenant,line,saved.state(),saved.revision());
    }

    private Result result(UUID tenant,Line line,String state,long revision) {
        return new Result(tenant,line.order(),line.id(),state,revision,line.code(),
            line.version(),line.price(),line.currency(),line.tax(),line.policy());
    }

    private Order lockOrder(UUID tenant,UUID id) {
        return jdbc.query("""
            SELECT tenant_id,id,buyer_user_id,status,currency
            FROM crm.sales_order WHERE tenant_id=? AND id=? FOR UPDATE
            """,rs->{
                if(!rs.next())throw new SecurityException("PACKAGING_ORDER_NOT_ACCESSIBLE");
                return mapOrder(rs);
            },tenant,id);
    }
    private Order mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Order(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
            rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5));
    }
    private Line lockLine(UUID tenant,UUID id) {
        return jdbc.query("""
            SELECT id,order_id,buyer_user_id,service_code,catalog_version,status,
                   row_version,price_snapshot,currency_snapshot,tax_rate_snapshot,policy_snapshot
            FROM crm.sales_order_service_line WHERE tenant_id=? AND id=? FOR UPDATE
            """,rs->rs.next()?new Line(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
            rs.getObject(3,UUID.class),rs.getString(4),rs.getInt(5),rs.getString(6),
            rs.getLong(7),rs.getBigDecimal(8),rs.getString(9),rs.getBigDecimal(10),
            rs.getString(11)):null,tenant,id);
    }
    private Receipt receipt(UUID tenant,String key) {
        return jdbc.query("""
            SELECT actor_user_id,action_code,request_sha256,service_line_id,
                   result_state,result_revision
            FROM crm.sales_order_service_receipt WHERE tenant_id=? AND request_key=?
            """,rs->rs.next()?new Receipt(rs.getObject(1,UUID.class),rs.getString(2),
            rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5),
            rs.getLong(6)):null,tenant,key);
    }
    private void event(UUID tenant,UUID line,UUID actor,String action,String state,
                       long revision,Instant now) {
        jdbc.update("""
            INSERT INTO crm.sales_order_service_event
             (tenant_id,id,service_line_id,actor_user_id,event_type,to_state,revision,occurred_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,tenant,UUID.randomUUID(),line,actor,action,state,revision,Timestamp.from(now));
    }
    private void writeReceipt(UUID tenant,String key,UUID actor,String action,
                              String hash,UUID line,String state,long rev,Instant now) {
        jdbc.update("""
            INSERT INTO crm.sales_order_service_receipt
             (tenant_id,request_key,actor_user_id,action_code,request_sha256,
              service_line_id,result_state,result_revision,created_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,tenant,key,actor,action,hash,line,state,rev,Timestamp.from(now));
    }
    private static boolean validKey(String value) {
        return value!=null&&value.matches("[A-Za-z0-9_-]{8,100}");
    }
    private static boolean validCode(String value) {
        return value!=null&&value.matches("[A-Z][A-Z0-9_]{1,39}");
    }
    private static void requireMutableOrder(Order order) {
        if(!"PLACED".equals(order.state())&&!"CONFIRMED".equals(order.state()))
            throw new IllegalStateException("PACKAGING_ORDER_STATE_FORBIDDEN");
    }
    private static String sha(UUID actor,String action,UUID subject,String code,long rev) {
        try {
            String source="RM/QR08/R0B|"+actor+"|"+action+"|"+subject+"|"+code+"|"+rev;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
