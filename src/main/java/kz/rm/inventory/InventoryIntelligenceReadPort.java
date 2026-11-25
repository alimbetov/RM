package kz.rm.inventory;
import kz.rm.iam.*;
import kz.rm.intelligence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** INVENTORY-owned read boundary; explicit warehouse assignment is always required. */
@Service
public class InventoryIntelligenceReadPort {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization auth;
    private final WarehouseStaffScopeService scope;
    public InventoryIntelligenceReadPort(JdbcTemplate jdbc,TenantAuthorization auth,WarehouseStaffScopeService scope){
        this.jdbc=jdbc;this.auth=auth;this.scope=scope;
    }
    @Transactional
    public List<MetricResult.Cell> read(VerifiedActor actor,UUID tenant,MetricRegistry.Code code,
            UUID warehouse,UUID sku,PeriodResolver.Period period){
        if(actor==null||tenant==null)throw new SecurityException("Verified actor and tenant required");
        UUID user=actor.userId();
        auth.requireForCommand(user,tenant,DomainPermission.INVENTORY_STOCK_READ);
        scope.requireAssigned(user,tenant,warehouse);
        if(sku==null)throw new IllegalArgumentException("SKU required");
        List<MetricResult.Cell> rows;
        if(code==MetricRegistry.Code.CURRENT_AVAILABLE){
            if(period!=null)throw new IllegalArgumentException("Live position has no period");
            rows=jdbc.query("""
                SELECT on_hand-reserved FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,(rs,n)->new MetricResult.Cell(rs.getBigDecimal(1),null,warehouse,sku),tenant,warehouse,sku);
        }else{
            if(period==null||!period.startInclusive().isBefore(period.endExclusive()))throw new IllegalArgumentException("Invalid period");
            String expr=switch(code){
                case STOCK_IN_QTY -> "SUM(CASE WHEN delta_quantity>0 THEN delta_quantity END)";
                case STOCK_OUT_QTY -> "SUM(CASE WHEN delta_quantity<0 THEN -delta_quantity END)";
                default -> throw new IllegalArgumentException("Not an inventory metric");
            };
            rows=jdbc.query("SELECT "+expr+"""
                 FROM crm.inv_stock_movement
                 WHERE tenant_id=? AND location_id=? AND item_id=? AND recorded_at>=? AND recorded_at<?
                """,(rs,n)->new MetricResult.Cell(rs.getBigDecimal(1),null,warehouse,sku),
                tenant,warehouse,sku,Timestamp.from(period.startInclusive()),Timestamp.from(period.endExclusive()));
            rows=rows.stream().filter(c->c.value()!=null).toList();
        }
        actor.userId();return List.copyOf(rows);
    }
}
