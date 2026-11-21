package kz.rm.sales;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.inventory.OrderInventoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;

/**
 * Seller-side atomic orchestration: one seller order, one warehouse per order in R1.2.
 * No public transport. Actor IDs must be provided by authenticated IAM adapter.
 *
 * Lock order for every command:
 * order row -> reservation request stripes -> stock balance -> reservation.
 * All updates and events commit in ONE database transaction.
 */
@Service
public class SalesInventoryCommandService {
    private final TenantAuthorization authorization;
    private final OrderInventoryPort inventory;
    private final JdbcTemplate jdbc;

    public SalesInventoryCommandService(TenantAuthorization authorization,
                                        OrderInventoryPort inventory,JdbcTemplate jdbc) {
        this.authorization=authorization;
        this.inventory=inventory;
        this.jdbc=jdbc;
    }

    public record CommandResult(UUID orderId,String status,int affectedLines) {}
    private record Line(UUID id,UUID itemId,BigDecimal quantity) {}

    @Transactional
    CommandResult confirm(UUID actorId,UUID tenantId,UUID orderId,UUID locationId) {
        var salesPermit=authorization.requireForCommand(actorId,tenantId,DomainPermission.SALES_ORDER_CONFIRM);
        var stockPermit=authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_RESERVE);
        if(orderId==null || locationId==null)
            throw new IllegalArgumentException("Order and warehouse required");
        String status=lockOrder(tenantId,orderId);
        if("CONFIRMED".equals(status)) {
            // A prior successful command must already have a reservation for every line.
            List<Line> lines=lines(tenantId,orderId);
            long active=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,Long.class,tenantId,orderId);
            if(active!=lines.size() || lines.isEmpty())
                throw new IllegalStateException("Confirmed order has inconsistent reservations");
            Integer wrongLocation=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND location_id<>?
                """,Integer.class,tenantId,orderId,locationId);
            if(wrongLocation!=0)throw new IllegalArgumentException("Retry location changed");
            return new CommandResult(orderId,"CONFIRMED",0);
        }
        if(!"PLACED".equals(status))throw new IllegalStateException("Order not confirmable");
        List<Line> lines=lines(tenantId,orderId);
        if(lines.isEmpty())throw new IllegalStateException("Order has no lines");

        // State change remains invisible to other transactions until commit.
        updateOrder(tenantId,orderId,"PLACED","CONFIRMED");
        for(Line line:lines) {
            inventory.reserve(salesPermit,stockPermit,orderId,line.id(),locationId,line.itemId(),line.quantity());
        }
        appendEvent(tenantId,orderId,"CONFIRMED");
        return new CommandResult(orderId,"CONFIRMED",lines.size());
    }

    @Transactional
    CommandResult cancel(UUID actorId,UUID tenantId,UUID orderId) {
        var salesPermit=authorization.requireForCommand(actorId,tenantId,DomainPermission.SALES_ORDER_CANCEL);
        var stockPermit=authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_RELEASE);
        if(orderId==null)throw new IllegalArgumentException("Order required");
        String state=lockOrder(tenantId,orderId);
        if("CANCELLED".equals(state)) {
            Integer remaining=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,Integer.class,tenantId,orderId);
            if(remaining!=0)throw new IllegalStateException("Cancelled order still has active reserves");
            return new CommandResult(orderId,state,0);
        }
        if("COMPLETED".equals(state) || "OUT_FOR_DELIVERY".equals(state)
                || "READY_FOR_PICKUP".equals(state) || "REJECTED".equals(state))
            throw new IllegalStateException("Order cannot be cancelled");
        List<UUID> active=jdbc.query("""
            SELECT id FROM crm.inv_stock_reservation
            WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
            ORDER BY location_id,item_id,id
            """,(rs,n)->rs.getObject(1,UUID.class),tenantId,orderId);
        Integer fulfilled=jdbc.queryForObject("""
            SELECT count(*) FROM crm.inv_stock_reservation
            WHERE tenant_id=? AND order_id=? AND state='FULFILLED'
            """,Integer.class,tenantId,orderId);
        if(fulfilled!=0)throw new IllegalStateException("Shipped items cannot be cancelled");
        updateOrder(tenantId,orderId,state,"CANCELLED");
        for(UUID reservation:active)inventory.release(salesPermit,stockPermit,reservation);
        appendEvent(tenantId,orderId,"CANCELLED");
        return new CommandResult(orderId,"CANCELLED",active.size());
    }

    @Transactional
    CommandResult fulfill(UUID actorId,UUID tenantId,UUID orderId) {
        var salesPermit=authorization.requireForCommand(actorId,tenantId,DomainPermission.SALES_ORDER_FULFILL);
        var stockPermit=authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_FULFILL);
        if(orderId==null)throw new IllegalArgumentException("Order required");
        String state=lockOrder(tenantId,orderId);
        if("COMPLETED".equals(state)) {
            int expected=lines(tenantId,orderId).size();
            Integer completed=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND order_id=? AND state='FULFILLED'
                """,Integer.class,tenantId,orderId);
            if(expected==0 || completed!=expected)
                throw new IllegalStateException("Completed order has inconsistent fulfillment");
            return new CommandResult(orderId,state,0);
        }
        if(!"CONFIRMED".equals(state) && !"PREPARING".equals(state))
            throw new IllegalStateException("Order not ready for fulfillment");
        int lineCount=lines(tenantId,orderId).size();
        List<UUID> active=jdbc.query("""
            SELECT id FROM crm.inv_stock_reservation
            WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
            ORDER BY location_id,item_id,id
            """,(rs,n)->rs.getObject(1,UUID.class),tenantId,orderId);
        if(active.size()!=lineCount || lineCount==0)
            throw new IllegalStateException("Order reservations incomplete");
        for(UUID reservation:active)inventory.fulfill(salesPermit,stockPermit,reservation);
        updateOrder(tenantId,orderId,state,"COMPLETED");
        appendEvent(tenantId,orderId,"COMPLETED");
        return new CommandResult(orderId,"COMPLETED",active.size());
    }

    private String lockOrder(UUID tenant,UUID order) {
        return jdbc.query("""
            SELECT status FROM crm.sales_order WHERE tenant_id=? AND id=? FOR UPDATE
            """,rs->{
                if(!rs.next())throw new IllegalArgumentException("Order not found in tenant");
                return rs.getString(1);
            },tenant,order);
    }

    private List<Line> lines(UUID tenant,UUID order) {
        return jdbc.query("""
            SELECT l.id,s.catalog_item_id,l.quantity
            FROM crm.sales_order_line l JOIN crm.store_offer s
              ON s.tenant_id=l.tenant_id AND s.id=l.offer_id
            WHERE l.tenant_id=? AND l.order_id=?
            ORDER BY s.catalog_item_id,l.id
            """,(rs,n)->new Line(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    BigDecimal.valueOf(rs.getLong(3))),tenant,order);
    }

    private void updateOrder(UUID tenant,UUID order,String from,String to) {
        int updated=jdbc.update("""
            UPDATE crm.sales_order SET status=?,row_version=row_version+1
            WHERE tenant_id=? AND id=? AND status=?
            """,to,tenant,order,from);
        if(updated!=1)throw new IllegalStateException("Concurrent order transition");
    }

    private void appendEvent(UUID tenant,UUID order,String event) {
        jdbc.update("""
            INSERT INTO crm.sales_order_status_event
              (tenant_id,id,order_id,event_type,occurred_at)
            VALUES (?,?,?,?,?)
            """,tenant,UUID.randomUUID(),order,event,Timestamp.from(Instant.now()));
    }
}
