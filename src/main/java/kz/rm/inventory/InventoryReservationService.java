package kz.rm.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal tenant-owned inventory reservation boundary.
 *
 * Order line -> exactly one reservation in R1.1; no cross-warehouse split.
 * Lock order for all mutations:
 * seller order row -> stock balance -> reservation.
 * Order row lock serializes retries on the same line; unique(tenant,order_line)
 * remains the database idempotency barrier. Cross-order SKU locks are acquired
 * in deterministic SKU order by the SALES orchestration service.
 *
 * No public endpoint: the caller must derive tenant and actor from verified IAM
 * membership and enforce SALES/INVENTORY rights before using this internal port.
 */
@Service
public class InventoryReservationService {
    private final JdbcTemplate jdbc;
    private final StockEligibility stockEligibility;
    public InventoryReservationService(JdbcTemplate jdbc,StockEligibility stockEligibility) {
        this.jdbc=jdbc;this.stockEligibility=stockEligibility;
    }

    public enum State { ACTIVE, RELEASED, FULFILLED }
    public record Reservation(UUID id, UUID tenantId, UUID orderId, UUID lineId,
                              UUID locationId, UUID itemId, BigDecimal quantity, State state) {}
    private record OrderLine(int quantity, UUID catalogItemId) {}
    private record Stock(BigDecimal onHand, BigDecimal reserved) {}

    @Transactional
    Reservation reserve(UUID tenantId, UUID orderId, UUID orderLineId,
                               UUID locationId, UUID itemId, BigDecimal quantity) {
        return reserve(tenantId,orderId,orderLineId,locationId,itemId,quantity,null);
    }

    @Transactional
    Reservation reserve(UUID tenantId, UUID orderId, UUID orderLineId,
                    UUID locationId, UUID itemId, BigDecimal quantity, UUID actorId) {
        requireIds(tenantId,orderId,orderLineId,locationId,itemId);
        positiveQty(quantity);
        // The seller order FOR UPDATE protects this line from concurrent retries.
        requireOrderStatus(tenantId,orderId,true);
        OrderLine line=jdbc.query("""
            SELECT l.quantity,l.catalog_item_id_snapshot
            FROM crm.sales_order_line l
            WHERE l.tenant_id=? AND l.order_id=? AND l.id=?
            """,rs->{
                if(!rs.next()) throw new IllegalArgumentException("Order line not found");
                return new OrderLine(rs.getInt(1),rs.getObject(2,UUID.class));
            },tenantId,orderId,orderLineId);
        if(line.catalogItemId()==null)
            throw new IllegalStateException("UNMAPPED_LEGACY_ORDER: immutable SKU missing");
        if(!line.catalogItemId().equals(itemId))
            throw new IllegalArgumentException("Requested SKU differs from order-line snapshot");
        // Full-line reservation only in R1.1. Split locations belong to a later version.
        if(quantity.compareTo(BigDecimal.valueOf(line.quantity()))!=0)
            throw new IllegalArgumentException("Reservation must cover full order line");
        // Seller order FOR UPDATE already serializes an idempotent line retry.
        List<Reservation> existing=loadByLine(tenantId,orderLineId);
        if(!existing.isEmpty()) {
            Reservation old=existing.getFirst();
            if(old.orderId().equals(orderId) && old.locationId().equals(locationId)
                    && old.itemId().equals(itemId) && old.quantity().compareTo(quantity)==0
                    && old.state()==State.ACTIVE) return old;
            throw new IllegalStateException("Reservation already exists or has terminal state");
        }
        stockEligibility.requireReservable(tenantId,locationId,itemId);
        Stock stock=lockStock(tenantId,locationId,itemId);
        if(stock.onHand().subtract(stock.reserved()).compareTo(quantity)<0)
            throw new IllegalStateException("Insufficient available inventory");
        int updated=jdbc.update("""
            UPDATE crm.inv_stock_balance SET reserved=reserved+?,row_version=row_version+1
            WHERE tenant_id=? AND location_id=? AND item_id=?
            """,quantity,tenantId,locationId,itemId);
        if(updated!=1) throw new IllegalStateException("Stock balance disappeared");
        UUID id=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO crm.inv_stock_reservation
              (tenant_id,id,order_id,order_line_id,location_id,item_id,quantity,state,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?)
            """,tenantId,id,orderId,orderLineId,locationId,itemId,quantity,now,now);
        insertEvent(tenantId,id,"RESERVED",now,actorId);
        return new Reservation(id,tenantId,orderId,orderLineId,locationId,itemId,quantity,State.ACTIVE);
    }

    @Transactional
    Reservation release(UUID tenantId, UUID reservationId) {
        return transition(tenantId,reservationId,State.RELEASED,null);
    }

    /** Fulfillment converts the reservation into a single immutable negative stock movement. */
    @Transactional
    Reservation fulfill(UUID tenantId, UUID reservationId) {
        return transition(tenantId,reservationId,State.FULFILLED,null);
    }

    @Transactional
    Reservation release(UUID tenantId, UUID reservationId,UUID actorId) {
        return transition(tenantId,reservationId,State.RELEASED,actorId);
    }
    @Transactional
    Reservation fulfill(UUID tenantId, UUID reservationId,UUID actorId) {
        return transition(tenantId,reservationId,State.FULFILLED,actorId);
    }

    private Reservation transition(UUID tenantId,UUID reservationId,State target,UUID actorId) {
        if(tenantId==null || reservationId==null) throw new IllegalArgumentException("Reservation required");
        Reservation peek=loadById(tenantId,reservationId,false);
        String orderStatus=requireOrderStatus(tenantId,peek.orderId(),false);
        Stock stock=lockStock(tenantId,peek.locationId(),peek.itemId());
        Reservation current=loadById(tenantId,reservationId,true);
        if(current.state()==target) return current; // terminal replay survives later order status changes
        if(current.state()!=State.ACTIVE)
            throw new IllegalStateException("Cannot change a terminal reservation");
        if(target==State.FULFILLED && !("CONFIRMED".equals(orderStatus)
                || "PREPARING".equals(orderStatus) || "READY_FOR_PICKUP".equals(orderStatus)
                || "OUT_FOR_DELIVERY".equals(orderStatus)))
            throw new IllegalStateException("Order cannot be fulfilled");
        if(stock.reserved().compareTo(current.quantity())<0)
            throw new IllegalStateException("Reserved balance inconsistent");
        BigDecimal afterOnHand=stock.onHand();
        if(target==State.FULFILLED) {
            afterOnHand=stock.onHand().subtract(current.quantity());
            if(afterOnHand.signum()<0 || afterOnHand.compareTo(stock.reserved().subtract(current.quantity()))<0)
                throw new IllegalStateException("Fulfillment would oversell inventory");
        }
        int count=jdbc.update("""
            UPDATE crm.inv_stock_balance SET on_hand=?,reserved=reserved-?,row_version=row_version+1
            WHERE tenant_id=? AND location_id=? AND item_id=?
            """,afterOnHand,current.quantity(),tenantId,current.locationId(),current.itemId());
        if(count!=1)throw new IllegalStateException("Stock balance disappeared");
        Timestamp now=Timestamp.from(Instant.now());
        count=jdbc.update("""
            UPDATE crm.inv_stock_reservation
            SET state=?,updated_at=?,row_version=row_version+1
            WHERE tenant_id=? AND id=? AND state='ACTIVE'
            """,target.name(),now,tenantId,reservationId);
        if(count!=1)throw new IllegalStateException("Reservation transition conflicted");
        insertEvent(tenantId,reservationId,target==State.RELEASED?"RELEASED":"FULFILLED",now,actorId);
        if(target==State.FULFILLED) {
            jdbc.update("""
                INSERT INTO crm.inv_stock_movement
                  (tenant_id,id,location_id,item_id,request_key,delta_quantity,
                   balance_before,balance_after,occurred_at,recorded_at,
                   movement_kind,reason_code,reservation_id,actor_user_id,source_type,source_id,posting_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,'FULFILLMENT','ORDER_FULFILLMENT',?,?,?,?,?)
                """,tenantId,UUID.randomUUID(),current.locationId(),current.itemId(),
                    "fulfill-"+reservationId,current.quantity().negate(),
                    stock.onHand(),afterOnHand,now,now,reservationId,
                    actorId,"SALES_ORDER",current.orderId(),
                    InventoryPostingVersion.afterBalanceUpdate(jdbc,tenantId,current.locationId(),current.itemId()));
        }
        return new Reservation(current.id(),tenantId,current.orderId(),current.lineId(),
                current.locationId(),current.itemId(),current.quantity(),target);
    }

    private String requireOrderStatus(UUID tenant,UUID order,boolean forReserve) {
        String status=jdbc.query("""
            SELECT status FROM crm.sales_order WHERE tenant_id=? AND id=? FOR UPDATE
            """,rs->{
                if(!rs.next())throw new IllegalArgumentException("Seller order not found");
                return rs.getString(1);
            },tenant,order);
        if(forReserve && !("CONFIRMED".equals(status) || "PREPARING".equals(status)))
            throw new IllegalStateException("Seller must confirm order before reservation");
        return status;
    }

    private Stock lockStock(UUID tenant,UUID location,UUID item) {
        return jdbc.query("""
            SELECT on_hand,reserved FROM crm.inv_stock_balance
            WHERE tenant_id=? AND location_id=? AND item_id=? FOR UPDATE
            """,rs->{
                if(!rs.next())throw new IllegalArgumentException("Stock balance not initialized");
                return new Stock(rs.getBigDecimal(1),rs.getBigDecimal(2));
            },tenant,location,item);
    }

    private List<Reservation> loadByLine(UUID tenant,UUID line) {
        return jdbc.query("""
            SELECT id,order_id,order_line_id,location_id,item_id,quantity,state
            FROM crm.inv_stock_reservation WHERE tenant_id=? AND order_line_id=?
            """,(rs,n)->mapReservation(rs,tenant),tenant,line);
    }

    private Reservation loadById(UUID tenant,UUID id,boolean lock) {
        String sql="""
            SELECT id,order_id,order_line_id,location_id,item_id,quantity,state
            FROM crm.inv_stock_reservation WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":"");
        return jdbc.query(sql,rs->{
            if(!rs.next())throw new IllegalArgumentException("Reservation not found");
            return mapReservation(rs,tenant);
        },tenant,id);
    }

    private static Reservation mapReservation(java.sql.ResultSet rs,UUID tenant)
            throws java.sql.SQLException {
        return new Reservation(rs.getObject(1,UUID.class),tenant,
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),
                rs.getBigDecimal(6),State.valueOf(rs.getString(7)));
    }

    private void insertEvent(UUID tenant,UUID id,String type,Timestamp now,UUID actorId) {
        jdbc.update("""
            INSERT INTO crm.inv_reservation_event
              (tenant_id,id,reservation_id,event_type,occurred_at,actor_user_id)
            VALUES (?,?,?,?,?,?)
            """,tenant,UUID.randomUUID(),id,type,now,actorId);
    }

    private static void positiveQty(BigDecimal value) {
        if(value==null || value.signum()<=0 || value.scale()>4 || value.precision()>19)
            throw new IllegalArgumentException("Positive precision-limited quantity required");
    }

    private static void requireIds(UUID... ids) {
        for(UUID id:ids)if(id==null)throw new IllegalArgumentException("Identity required");
    }
}
