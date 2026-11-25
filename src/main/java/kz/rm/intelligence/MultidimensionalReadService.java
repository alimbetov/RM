package kz.rm.intelligence;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import kz.rm.iam.WarehouseStaffScopeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authorized SQL oracle for B4 multidimensional rollups; no public HTTP or active generation route. */
@Service
public class MultidimensionalReadService {
    public record Group(UUID id,String code,Long revision,String kind){}
    // NULL group or region explicitly denotes UNKNOWN, never today's master data.
    public record Booked(Group group,String currency,BigDecimal amount,long distinctOrders){}
    public record Fulfilled(String region,Group group,String currency,BigDecimal amount,long distinctOrders){}
    public record Stock(String region,Group group,UUID sku,BigDecimal in,BigDecimal out){}
    public record StockCoverage(long sourceMovements,long missingSnapshots,long unknownRegion,long resolvedRegion){
        public boolean allClassified(){return missingSnapshots==0&&unknownRegion==0;}
    }
    /** Source classification coverage is not a national directory certificate. */
    @Transactional
    public StockCoverage stockCoverage(VerifiedActor actor,UUID tenant,List<UUID> warehouseIds,Instant from,Instant until){
        admit(actor,tenant,DomainPermission.INVENTORY_STOCK_READ,from,until,0);
        var allowed=warehouses(actor,tenant,warehouseIds);
        var args=new java.util.ArrayList<Object>(List.of(tenant,Timestamp.from(from),Timestamp.from(until)));
        args.addAll(allowed);
        return jdbc.queryForObject("""
            SELECT COUNT(*),
              COUNT(CASE WHEN d.movement_id IS NULL THEN 1 END),
              COUNT(CASE WHEN d.movement_id IS NOT NULL AND d.geo_quality='UNKNOWN' THEN 1 END),
              COUNT(CASE WHEN d.geo_quality='RESOLVED' THEN 1 END)
            FROM crm.inv_stock_movement m LEFT JOIN crm.int_movement_dimension d
              ON d.tenant_id=m.tenant_id AND d.movement_id=m.id
            WHERE m.tenant_id=? AND m.recorded_at>=? AND m.recorded_at<? AND m.location_id IN ("""+placeholders(allowed.size())+")",
            (rs,n)->new StockCoverage(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)),args.toArray());
    }
    private final JdbcTemplate jdbc;
    private final TenantAuthorization auth;
    private final WarehouseStaffScopeService scope;
    public MultidimensionalReadService(JdbcTemplate jdbc,TenantAuthorization auth,WarehouseStaffScopeService scope){
        this.jdbc=jdbc;this.auth=auth;this.scope=scope;
    }
    private void admit(VerifiedActor actor,UUID tenant,DomainPermission permission,Instant from,Instant until,int ancestorSteps){
        if(actor==null||tenant==null)throw new SecurityException("Verified actor and tenant required");
        auth.requireForCommand(actor.userId(),tenant,permission);
        if(from==null||until==null||!from.isBefore(until)||ancestorSteps<0||ancestorSteps>31)
            throw new IllegalArgumentException("INT_DIMENSION_QUERY_SCOPE");
    }
    private List<UUID> warehouses(VerifiedActor actor,UUID tenant,List<UUID> input){
        if(input==null||input.isEmpty()||input.size()>100||input.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("INT_DIMENSION_WAREHOUSE_SCOPE");
        var result=input.stream().distinct().sorted().toList();
        for(UUID warehouse:result)scope.requireAssigned(actor.userId(),tenant,warehouse);
        return result;
    }
    private static String placeholders(int n){return String.join(",",java.util.Collections.nCopies(n,"?"));}
    // Pin exact revision IDs, not mutable code or text. Each leaf selects ONE ancestor.
    // 0=assigned leaf, 31=root (depth bounded by catalog write service).
    private static final String GROUPS="""
        WITH RECURSIVE ancestry(leaf,node_id,parent_id,distance) AS (
            SELECT id,id,parent_id,0 FROM crm.cat_group_node WHERE tenant_id=?
            UNION ALL
            SELECT a.leaf,n.id,n.parent_id,a.distance+1 FROM ancestry a
            JOIN crm.cat_group_node n ON n.tenant_id=? AND n.id=a.parent_id
            WHERE a.distance<?
        ), ranked AS (
            SELECT leaf,node_id,ROW_NUMBER() OVER(PARTITION BY leaf ORDER BY distance DESC) AS rank_no FROM ancestry
        ), group_choice AS (
            SELECT r.leaf,n.id,n.group_code,n.revision,n.kind FROM ranked r
            JOIN crm.cat_group_node n ON n.tenant_id=? AND n.id=r.node_id WHERE r.rank_no=1
        )
        """;
    private static Group group(java.sql.ResultSet rs,int start)throws java.sql.SQLException{
        return new Group(rs.getObject(start,UUID.class),rs.getString(start+1),rs.getObject(start+2,Long.class),rs.getString(start+3));
    }
    @Transactional
    public List<Booked> booked(VerifiedActor actor,UUID tenant,Instant from,Instant until,int ancestorSteps){
        admit(actor,tenant,DomainPermission.SALES_ORDER_READ,from,until,ancestorSteps);
        // Booking has no trustworthy fulfillment geography. Monetary amounts are
        // line-based, order counts DISTINCT at the selected group, not additive.
        return jdbc.query(GROUPS+"""
            , eligible AS (
                SELECT order_id,MIN(occurred_at) event_at FROM crm.sales_order_status_event
                WHERE tenant_id=? AND event_type='PLACED' GROUP BY order_id HAVING COUNT(*)=1
            )
            SELECT g.id,g.group_code,g.revision,d.item_kind,l.currency,
                SUM(l.quantity*l.unit_price_snapshot),COUNT(DISTINCT l.order_id)
            FROM eligible e JOIN crm.sales_order_line l ON l.tenant_id=? AND l.order_id=e.order_id
            LEFT JOIN crm.int_sales_line_dimension d ON d.tenant_id=l.tenant_id AND d.line_id=l.id
            LEFT JOIN group_choice g ON g.leaf=d.group_id
            WHERE e.event_at>=? AND e.event_at<?
            GROUP BY g.id,g.group_code,g.revision,d.item_kind,l.currency
            ORDER BY g.group_code,g.revision,d.item_kind,l.currency
            """,(rs,n)->new Booked(group(rs,1),rs.getString(5).trim(),rs.getBigDecimal(6),rs.getLong(7)),
            tenant,tenant,ancestorSteps,tenant,tenant,tenant,Timestamp.from(from),Timestamp.from(until));
    }
    @Transactional
    public List<Fulfilled> fulfilled(VerifiedActor actor,UUID tenant,List<UUID> warehouseIds,Instant from,Instant until,int ancestorSteps){
        admit(actor,tenant,DomainPermission.SALES_ORDER_READ,from,until,ancestorSteps);
        auth.requireForCommand(actor.userId(),tenant,DomainPermission.INVENTORY_STOCK_READ);
        List<UUID> allowed=warehouses(actor,tenant,warehouseIds);
        var args=new java.util.ArrayList<Object>(List.of(tenant,tenant,ancestorSteps,tenant,tenant,tenant,Timestamp.from(from),Timestamp.from(until)));
        args.addAll(allowed);
        // Region from actual fulfillment movement, product grouping from original
        // booked line. Extra service lines require their own future source contract.
        return jdbc.query(GROUPS+"""
            , eligible AS (
                SELECT order_id,MIN(occurred_at) event_at FROM crm.sales_order_status_event
                WHERE tenant_id=? AND event_type='COMPLETED' GROUP BY order_id HAVING COUNT(*)=1
            )
            SELECT md.region_code,g.id,g.group_code,g.revision,d.item_kind,l.currency,
                SUM((-m.delta_quantity)*l.unit_price_snapshot),COUNT(DISTINCT l.order_id)
            FROM eligible e JOIN crm.sales_order_line l ON l.tenant_id=? AND l.order_id=e.order_id
            JOIN crm.inv_stock_reservation r ON r.tenant_id=l.tenant_id AND r.order_line_id=l.id AND r.order_id=l.order_id
            JOIN crm.inv_stock_movement m ON m.tenant_id=r.tenant_id AND m.reservation_id=r.id
                AND m.location_id=r.location_id AND m.item_id=r.item_id AND m.movement_kind='FULFILLMENT'
            LEFT JOIN crm.int_movement_dimension md ON md.tenant_id=m.tenant_id AND md.movement_id=m.id
            LEFT JOIN crm.int_sales_line_dimension d ON d.tenant_id=l.tenant_id AND d.line_id=l.id
            LEFT JOIN group_choice g ON g.leaf=d.group_id
            WHERE e.event_at>=? AND e.event_at<? AND m.location_id IN ("""+placeholders(allowed.size())+"""
            )
            GROUP BY md.region_code,g.id,g.group_code,g.revision,d.item_kind,l.currency
            ORDER BY md.region_code,g.group_code,g.revision,d.item_kind,l.currency
            """,(rs,n)->new Fulfilled(rs.getString(1),group(rs,2),rs.getString(6).trim(),rs.getBigDecimal(7),rs.getLong(8)),args.toArray());
    }
    @Transactional
    public List<Stock> stock(VerifiedActor actor,UUID tenant,List<UUID> warehouseIds,Instant from,Instant until,int ancestorSteps){
        admit(actor,tenant,DomainPermission.INVENTORY_STOCK_READ,from,until,ancestorSteps);
        List<UUID> allowed=warehouses(actor,tenant,warehouseIds);
        var args=new java.util.ArrayList<Object>(List.of(tenant,tenant,ancestorSteps,tenant,tenant,Timestamp.from(from),Timestamp.from(until)));
        args.addAll(allowed);
        // Retain SKU to avoid summing incompatible units. No cross-tenant or
        // unassigned-warehouse aggregate even at the oblast level.
        return jdbc.query(GROUPS+"""
            SELECT d.region_code,g.id,g.group_code,g.revision,d.item_kind,m.item_id,
                SUM(CASE WHEN m.delta_quantity>0 THEN m.delta_quantity ELSE 0 END),
                SUM(CASE WHEN m.delta_quantity<0 THEN -m.delta_quantity ELSE 0 END)
            FROM crm.inv_stock_movement m
            LEFT JOIN crm.int_movement_dimension d ON d.tenant_id=m.tenant_id AND d.movement_id=m.id
            LEFT JOIN group_choice g ON g.leaf=d.group_id
            WHERE m.tenant_id=? AND m.recorded_at>=? AND m.recorded_at<? AND m.location_id IN ("""+placeholders(allowed.size())+"""
            )
            GROUP BY d.region_code,g.id,g.group_code,g.revision,d.item_kind,m.item_id
            ORDER BY d.region_code,g.group_code,g.revision,d.item_kind,m.item_id
            """,(rs,n)->new Stock(rs.getString(1),group(rs,2),rs.getObject(6,UUID.class),rs.getBigDecimal(7),rs.getBigDecimal(8)),args.toArray());
    }
}
