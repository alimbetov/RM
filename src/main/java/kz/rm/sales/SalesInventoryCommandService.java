package kz.rm.sales;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.WarehouseStaffScopeService;
import org.springframework.beans.factory.annotation.Value;
import kz.rm.inventory.OrderInventoryPort;
import kz.rm.intelligence.CanonicalFactAppender;
import kz.rm.intelligence.CanonicalFactKey;
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
    private final WarehouseStaffScopeService warehouseStaff;
    private final CanonicalFactAppender canonicalFacts;

    @Value("${rm.mvp.service-mode.enabled:false}")
    private boolean strictMvpServiceMode;

    public SalesInventoryCommandService(TenantAuthorization authorization,
                                        OrderInventoryPort inventory,JdbcTemplate jdbc,
                                        WarehouseStaffScopeService warehouseStaff,
                                        CanonicalFactAppender canonicalFacts) {
        this.authorization=authorization;
        this.inventory=inventory;
        this.jdbc=jdbc;
        this.warehouseStaff=warehouseStaff;
        this.canonicalFacts=java.util.Objects.requireNonNull(canonicalFacts);
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
        if(strictMvpServiceMode)
            warehouseStaff.requireAssigned(actorId,tenantId,locationId);
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
        // Historical lines cannot reconstruct a buyer-accepted SKU from a live offer.
        if(lines.stream().anyMatch(line->line.itemId()==null))
            throw new IllegalStateException("UNMAPPED_LEGACY_ORDER: immutable SKU missing");

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
        // FC-40/DEC-SAL-02 conservative policy: physical pick evidence is append-only.
        // Do not release stock or cancel an order after any line has been picked.
        // A future audited reverse-pick workflow requires independent approval.
        Integer picked=jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_order_pick_confirmation
            WHERE tenant_id=? AND order_id=?
            """,Integer.class,tenantId,orderId);
        if(picked!=null && picked>0)
            throw new IllegalStateException("ORDER_CANCEL_REQUIRES_REVERSE_PICK");
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
        // Do not release warehouse stock merely because the actor has tenant grants.
        // On a PLACED order there are no reservations and no warehouse effects.
        if(strictMvpServiceMode && !active.isEmpty())
            requireStockWarehouseScope(actorId,tenantId,orderId);
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
        // DEC-FUL-01: refuse generic entry point even for a replay against
        // an already handover-completed order. Route authority is immutable.
        String fulfillmentMode=jdbc.queryForObject("""
            SELECT fulfillment_mode FROM crm.sales_order
            WHERE tenant_id=? AND id=?
            """,String.class,tenantId,orderId);
        if(!"STANDARD".equals(fulfillmentMode))
            throw new IllegalStateException("ORDER_FULFILL_REQUIRES_PICKUP_PROOF");
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
        // FC-40 interim safety fence: once physical picking has begun,
        // the generic completion path cannot bypass the secure handover.
        // Full channel classification (DEC-FUL-01) remains a separate admission.
        Integer picked=jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_order_pick_confirmation
            WHERE tenant_id=? AND order_id=?
            """,Integer.class,tenantId,orderId);
        if(picked!=null && picked>0)
            throw new IllegalStateException("ORDER_FULFILL_REQUIRES_PICKUP_PROOF");
        int lineCount=lines(tenantId,orderId).size();
        List<UUID> active=jdbc.query("""
            SELECT id FROM crm.inv_stock_reservation
            WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
            ORDER BY location_id,item_id,id
            """,(rs,n)->rs.getObject(1,UUID.class),tenantId,orderId);
        if(active.size()!=lineCount || lineCount==0)
            throw new IllegalStateException("Order reservations incomplete");
        if(strictMvpServiceMode)
            requireStockWarehouseScope(actorId,tenantId,orderId);
        for(UUID reservation:active)inventory.fulfill(salesPermit,stockPermit,reservation);
        updateOrder(tenantId,orderId,state,"COMPLETED");
        appendEvent(tenantId,orderId,"COMPLETED");
        return new CommandResult(orderId,"COMPLETED",active.size());
    }

    /** The order row is already locked. Resolve stock scope from persisted
     * reservations, never an actor- or client-selected warehouse identifier.
     * QR pickup has its separate mandatory warehouse fence in all modes.
     */
    private void requireStockWarehouseScope(UUID actor,UUID tenant,UUID order) {
        List<UUID> warehouses=jdbc.query("""
            SELECT DISTINCT location_id FROM crm.inv_stock_reservation
            WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
            ORDER BY location_id
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,order);
        if(warehouses.size()!=1)
            throw new SecurityException("WAREHOUSE_SCOPE_AMBIGUOUS");
        warehouseStaff.requireAssigned(actor,tenant,warehouses.getFirst());
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
            SELECT l.id,l.catalog_item_id_snapshot,l.quantity
            FROM crm.sales_order_line l
            WHERE l.tenant_id=? AND l.order_id=?
            ORDER BY l.catalog_item_id_snapshot,l.id
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
        var kind=switch(event){
            case "CONFIRMED" -> CanonicalFactKey.Kind.ORDER_CONFIRMED;
            case "CANCELLED" -> CanonicalFactKey.Kind.ORDER_CANCELLED;
            case "COMPLETED" -> CanonicalFactKey.Kind.ORDER_COMPLETED;
            default -> throw new IllegalArgumentException("UNSUPPORTED_CANONICAL_SALES_EVENT");
        };
        UUID eventId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.sales_order_status_event
              (tenant_id,id,order_id,event_type,occurred_at)
            VALUES (?,?,?,?,?)
            """,tenant,eventId,order,event,Timestamp.from(Instant.now()));
        if(canonicalFacts.append(new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,kind,eventId))
                !=CanonicalFactAppender.Outcome.INSERTED)
            throw new IllegalStateException("CANONICAL_NEW_SOURCE_ALREADY_PRESENT");
    }
}
