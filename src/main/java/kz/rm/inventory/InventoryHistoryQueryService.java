package kz.rm.inventory;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.inventory.dto.StockBalanceDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Protected and bounded read model for physical movements and reservation events.
 * Account, membership and selected-tenant authorization are fenced before SQL.
 * Historic null actors are deliberately reported as unknown.
 */
@Service
class InventoryHistoryQueryService {
    public record MovementSearch(UUID locationId, UUID itemId, String reasonCode,
                                 Instant from, Instant before) {}
    public record MovementRow(UUID id, UUID tenantId, UUID locationId, UUID itemId,
                              BigDecimal delta, BigDecimal beforeQuantity,
                              BigDecimal afterQuantity, String movementKind,
                              String reasonCode, String businessReasonCode,
                              UUID actorUserId, String sourceType, UUID sourceId,
                              UUID reservationId, Instant recordedAt) {}
    public record ReservationEvent(UUID id,UUID reservationId,String eventType,
                                   UUID actorUserId,Instant occurredAt) {}

    private final TenantAuthorization authorization;
    private final JdbcTemplate jdbc;
    InventoryHistoryQueryService(TenantAuthorization authorization,JdbcTemplate jdbc) {
        this.authorization=authorization;this.jdbc=jdbc;
    }

    private void admit(UUID actor,UUID tenant) {
        authorization.requireForCommand(actor,tenant,DomainPermission.INVENTORY_STOCK_READ);
    }

    @Transactional
    StockBalanceDto getPosition(UUID actor,UUID tenant,UUID location,UUID item) {
        admit(actor,tenant);
        if(location==null || item==null)
            throw new IllegalArgumentException("Warehouse/SKU required");
        return jdbc.query("""
                SELECT tenant_id,location_id,item_id,on_hand,reserved,row_version
                FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,rs->{
                    if(!rs.next())throw new IllegalArgumentException("Position not found in tenant");
                    BigDecimal hand=rs.getBigDecimal(4),reserved=rs.getBigDecimal(5);
                    return new StockBalanceDto(rs.getObject(1,UUID.class),
                            rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                            hand,reserved,hand.subtract(reserved),rs.getLong(6));
                },tenant,location,item);
    }

    @Transactional
    Page<MovementRow> searchMovements(UUID actor,UUID tenant,MovementSearch search,
                                      int page,int size) {
        admit(actor,tenant);
        page(page,size);
        MovementSearch f=search==null?new MovementSearch(null,null,null,null,null):search;
        if(f.from()!=null && f.before()!=null && !f.from().isBefore(f.before()))
            throw new IllegalArgumentException("Movement interval must be half-open and increasing");
        if(f.reasonCode()!=null && (f.reasonCode().isBlank() ||
                f.reasonCode().length()>64 || !f.reasonCode().matches("[A-Z_]+")))
            throw new IllegalArgumentException("Invalid reason code");
        StringBuilder predicate=new StringBuilder(" WHERE tenant_id=?");
        List<Object> args=new ArrayList<>();args.add(tenant);
        if(f.locationId()!=null){predicate.append(" AND location_id=?");args.add(f.locationId());}
        if(f.itemId()!=null){predicate.append(" AND item_id=?");args.add(f.itemId());}
        if(f.reasonCode()!=null){predicate.append(" AND reason_code=?");args.add(f.reasonCode());}
        if(f.from()!=null){predicate.append(" AND recorded_at>=?");args.add(Timestamp.from(f.from()));}
        if(f.before()!=null){predicate.append(" AND recorded_at<?");args.add(Timestamp.from(f.before()));}
        Long total=jdbc.queryForObject("SELECT count(*) FROM crm.inv_stock_movement"+
                predicate,Long.class,args.toArray());
        var paged=new ArrayList<>(args);paged.add(size);paged.add((long)page*size);
        var rows=jdbc.query("""
                SELECT id,tenant_id,location_id,item_id,delta_quantity,balance_before,
                       balance_after,movement_kind,reason_code,business_reason_code,
                       actor_user_id,source_type,source_id,reservation_id,recorded_at
                FROM crm.inv_stock_movement
                """+predicate+"""
                 ORDER BY recorded_at DESC,id DESC LIMIT ? OFFSET ?
                """,(rs,n)->new MovementRow(rs.getObject(1,UUID.class),
                  rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                  rs.getObject(4,UUID.class),rs.getBigDecimal(5),rs.getBigDecimal(6),
                  rs.getBigDecimal(7),rs.getString(8),rs.getString(9),rs.getString(10),
                  rs.getObject(11,UUID.class),rs.getString(12),
                  rs.getObject(13,UUID.class),rs.getObject(14,UUID.class),
                  rs.getTimestamp(15).toInstant()),paged.toArray());
        return new PageImpl<>(rows,PageRequest.of(page,size),total==null?0:total);
    }

    @Transactional
    Page<ReservationEvent> reservationHistory(UUID actor,UUID tenant,UUID reservationId,
                                               int page,int size) {
        admit(actor,tenant);
        page(page,size);
        if(reservationId==null)throw new IllegalArgumentException("Reservation required");
        Long total=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_reservation_event
                WHERE tenant_id=? AND reservation_id=?
                """,Long.class,tenant,reservationId);
        var rows=jdbc.query("""
                SELECT e.id,e.reservation_id,e.event_type,e.actor_user_id,e.occurred_at
                FROM crm.inv_reservation_event e
                WHERE e.tenant_id=? AND e.reservation_id=?
                ORDER BY e.occurred_at DESC,e.id DESC LIMIT ? OFFSET ?
                """,(rs,n)->new ReservationEvent(rs.getObject(1,UUID.class),
                  rs.getObject(2,UUID.class),rs.getString(3),
                  rs.getObject(4,UUID.class),rs.getTimestamp(5).toInstant()),
                  tenant,reservationId,size,(long)page*size);
        return new PageImpl<>(rows,PageRequest.of(page,size),total==null?0:total);
    }

    private static void page(int page,int size) {
        if(page<0 || size<1 || size>100)
            throw new IllegalArgumentException("Page must be >=0 and size 1..100");
    }
}
