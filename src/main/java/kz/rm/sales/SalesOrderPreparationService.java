package kz.rm.sales;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import kz.rm.iam.WarehouseStaffScopeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * QR-05 explicit pick and READY state machine, without stock movement.
 *
 * Every staff operation uses two fresh IAM domain gates + explicit warehouse
 * assignment. Existing SALES order lock serializes pick confirmations and
 * status transitions. A fully reserved order MUST be picked line-by-line
 * before READY; no unverified "all picked" flag is trusted.
 */
@Service
public class SalesOrderPreparationService {
    public record Receipt(UUID orderId,UUID warehouseId,String status,
                          long rowVersion) {}
    public record PickLine(UUID orderLineId,UUID itemId,int quantity) {}
    private record Order(String state,long version) {}
    private record Reservation(UUID orderLineId,UUID itemId,int quantity,
                               UUID warehouseId,String state) {}
    private record Stored(String hash,UUID actorId,String action,
                          Receipt result) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final WarehouseStaffScopeService warehouseScopes;

    public SalesOrderPreparationService(JdbcTemplate jdbc,
                TenantAuthorization authorization,
                WarehouseStaffScopeService warehouseScopes){
        this.jdbc=jdbc;
        this.authorization=authorization;
        this.warehouseScopes=warehouseScopes;
    }

    @Transactional
    public Receipt startPreparing(VerifiedActor staff,UUID tenant,UUID orderId,
                                  UUID warehouseId,long expectedVersion,
                                  String requestKey) {
        return execute(staff,tenant,orderId,warehouseId,
                expectedVersion,requestKey,"START",null,0);
    }

    @Transactional
    public Receipt confirmPicked(VerifiedActor staff,UUID tenant,UUID orderId,
                                 UUID warehouseId,UUID lineId,int quantity,
                                 long expectedVersion,String requestKey) {
        if(lineId==null||quantity<1||quantity>1000)
            throw new IllegalArgumentException("PICK_LINE_INVALID");
        return execute(staff,tenant,orderId,warehouseId,
                expectedVersion,requestKey,"PICK",lineId,quantity);
    }

    @Transactional
    public Receipt markReadyForPickup(VerifiedActor staff,UUID tenant,UUID orderId,
                                      UUID warehouseId,long expectedVersion,
                                      String requestKey) {
        return execute(staff,tenant,orderId,warehouseId,
                expectedVersion,requestKey,"READY",null,0);
    }

    private Receipt execute(VerifiedActor staff,UUID tenant,UUID orderId,
                            UUID warehouseId,long expectedVersion,String requestKey,
                            String action,UUID line,int quantity) {
        if(staff==null||tenant==null||orderId==null||warehouseId==null
                ||expectedVersion<0||requestKey==null
                ||!requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("PREPARATION_INPUT_INVALID");

        UUID actor=staff.userId();
        // For command all IAM suspension, membership and permission fences
        // are transaction-scoped. Warehouse-specific assignment is additional.
        authorization.requireForCommand(actor,tenant,
                DomainPermission.SALES_ORDER_CONFIRM);
        authorization.requireForCommand(actor,tenant,
                DomainPermission.INVENTORY_STOCK_READ);
        Order order=lockOrder(tenant,orderId);
        warehouseScopes.requireAssigned(actor,tenant,warehouseId);
        String hash=hash(actor,orderId,warehouseId,action,expectedVersion,
                line,quantity);
        Stored replay=jdbc.query("""
                SELECT request_sha256,actor_user_id,action_code,
                       result_status,result_row_version
                FROM crm.sales_preparation_receipt
                WHERE tenant_id=? AND request_key=?
                """,rs->rs.next()?new Stored(rs.getString(1),
                rs.getObject(2,UUID.class),rs.getString(3),
                new Receipt(orderId,warehouseId,rs.getString(4),
                        rs.getLong(5))):null,tenant,requestKey);
        if(replay!=null){
            if(!hash.equals(replay.hash())||!actor.equals(replay.actorId())
                    ||!action.equals(replay.action()))
                throw new IllegalStateException("PREPARATION_IDEMPOTENCY_CONFLICT");
            return replay.result();
        }

        if(order.version()!=expectedVersion)
            throw new IllegalStateException("ORDER_VERSION_CONFLICT");
        List<Reservation> reservations=validateFullReserves(tenant,orderId,warehouseId);
        String next;
        if("START".equals(action)){
            if(!"CONFIRMED".equals(order.state()))
                throw new IllegalStateException("ORDER_NOT_CONFIRMED");
            next="PREPARING";
        }else if("PICK".equals(action)){
            if(!"PREPARING".equals(order.state()))
                throw new IllegalStateException("ORDER_NOT_PREPARING");
            Reservation r=reservations.stream()
                    .filter(x->x.orderLineId().equals(line)).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("PICK_LINE_NOT_FOUND"));
            if(r.quantity()!=quantity)
                throw new IllegalArgumentException("PICK_QUANTITY_MISMATCH");
            Integer already=jdbc.queryForObject("""
                    SELECT count(*) FROM crm.sales_order_pick_confirmation
                    WHERE tenant_id=? AND order_line_id=?
                    """,Integer.class,tenant,line);
            if(already!=null&&already>0)
                throw new IllegalStateException("PICK_LINE_ALREADY_CONFIRMED");
            jdbc.update("""
                    INSERT INTO crm.sales_order_pick_confirmation
                      (tenant_id,id,order_id,order_line_id,warehouse_id,item_id,
                       quantity,picked_by,picked_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,tenant,UUID.randomUUID(),orderId,line,warehouseId,r.itemId(),
                    quantity,actor,Timestamp.from(Instant.now()));
            next="PREPARING";
        }else if("READY".equals(action)){
            if(!"PREPARING".equals(order.state()))
                throw new IllegalStateException("ORDER_NOT_PREPARING");
            int picked=jdbc.queryForObject("""
                    SELECT count(*) FROM crm.sales_order_pick_confirmation p
                    JOIN crm.sales_order_line l
                      ON l.tenant_id=p.tenant_id AND l.id=p.order_line_id
                     AND l.order_id=p.order_id
                    WHERE p.tenant_id=? AND p.order_id=? AND p.warehouse_id=?
                      AND p.quantity=l.quantity
                      AND p.item_id=l.catalog_item_id_snapshot
                    """,Integer.class,tenant,orderId,warehouseId);
            if(picked!=reservations.size())
                throw new IllegalStateException("PICKING_INCOMPLETE");
            next="READY_FOR_PICKUP";
        }else throw new IllegalArgumentException("PREPARATION_ACTION_INVALID");

        int changed=jdbc.update("""
                UPDATE crm.sales_order SET status=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND status=? AND row_version=?
                """,next,tenant,orderId,order.state(),expectedVersion);
        if(changed!=1)throw new IllegalStateException("ORDER_VERSION_CONFLICT");
        if(!next.equals(order.state())){
            jdbc.update("""
                    INSERT INTO crm.sales_order_status_event
                       (tenant_id,id,order_id,event_type,occurred_at)
                    VALUES (?,?,?,?,?)
                    """,tenant,UUID.randomUUID(),orderId,next,
                    Timestamp.from(Instant.now()));
        }
        Receipt result=new Receipt(orderId,warehouseId,next,expectedVersion+1);
        jdbc.update("""
                INSERT INTO crm.sales_preparation_receipt
                  (tenant_id,request_key,actor_user_id,action_code,order_id,
                   warehouse_id,line_id,request_sha256,result_status,
                   result_row_version,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,requestKey,actor,action,orderId,warehouseId,line,
                hash,next,result.rowVersion(),Timestamp.from(Instant.now()));
        return result;
    }

    /** Used inside QR-06 after order lock. Never equate count of reservations
     * to count of lines without checking each exact item, quantity and location. */
    @Transactional(readOnly=true)
    List<PickLine> assertReadyPickedAndReserved(UUID tenant,UUID orderId,
                                                       UUID warehouseId) {
        List<Reservation> reserved=validateFullReserves(tenant,orderId,warehouseId);
        int picked=jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_pick_confirmation p
                JOIN crm.sales_order_line l
                  ON l.tenant_id=p.tenant_id AND l.order_id=p.order_id
                 AND l.id=p.order_line_id
                WHERE p.tenant_id=? AND p.order_id=? AND p.warehouse_id=?
                  AND p.item_id=l.catalog_item_id_snapshot
                  AND p.quantity=l.quantity
                """,Integer.class,tenant,orderId,warehouseId);
        if(picked!=reserved.size())throw new IllegalStateException("PICKING_INCOMPLETE");
        return reserved.stream()
                .map(r->new PickLine(r.orderLineId(),r.itemId(),r.quantity()))
                .toList();
    }

    private List<Reservation> validateFullReserves(UUID tenant,UUID orderId,
                                                    UUID warehouseId) {
        List<Reservation> reservations=jdbc.query("""
                SELECT l.id,l.catalog_item_id_snapshot,l.quantity,
                       r.location_id,r.state,r.quantity,r.item_id
                FROM crm.sales_order_line l
                LEFT JOIN crm.inv_stock_reservation r
                  ON r.tenant_id=l.tenant_id AND r.order_line_id=l.id
                 AND r.order_id=l.order_id
                WHERE l.tenant_id=? AND l.order_id=?
                ORDER BY l.catalog_item_id_snapshot,l.id
                """,(rs,n)->{
                    UUID item=rs.getObject(2,UUID.class);
                    UUID wh=rs.getObject(4,UUID.class);
                    String state=rs.getString(5);
                    BigDecimal qty=rs.getBigDecimal(6);
                    int ordered=rs.getInt(3);
                    if(item==null||wh==null||!"ACTIVE".equals(state)
                            ||!item.equals(rs.getObject(7,UUID.class))
                            ||!warehouseId.equals(wh)
                            ||qty==null||qty.compareTo(BigDecimal.valueOf(ordered))!=0)
                        throw new IllegalStateException("ORDER_FULL_RESERVATION_REQUIRED");
                    return new Reservation(rs.getObject(1,UUID.class),
                            item,ordered,wh,state);
                },tenant,orderId);
        if(reservations.isEmpty())
            throw new IllegalStateException("ORDER_FULL_RESERVATION_REQUIRED");
        Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                  AND location_id=?
                """,Integer.class,tenant,orderId,warehouseId);
        if(count==null||count!=reservations.size())
            throw new IllegalStateException("ORDER_FULL_RESERVATION_REQUIRED");
        return reservations;
    }

    private Order lockOrder(UUID tenant,UUID order) {
        return jdbc.query("""
                SELECT status,row_version FROM crm.sales_order
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())throw new SecurityException("ORDER_NOT_ACCESSIBLE");
                    return new Order(rs.getString(1),rs.getLong(2));
                },tenant,order);
    }

    private static String hash(UUID actor,UUID order,UUID warehouse,String action,
                               long version,UUID line,int qty){
        String canonical="RM/QR-05/V1|"+actor+"|"+order+"|"+warehouse
                +"|"+action+"|"+version+"|"+line+"|"+qty;
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception exception){
            throw new IllegalStateException("SHA-256 required",exception);
        }
    }
}
