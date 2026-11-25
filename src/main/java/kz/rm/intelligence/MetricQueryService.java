package kz.rm.intelligence;
import kz.rm.iam.*;
import kz.rm.sales.SalesIntelligenceReadPort;
import kz.rm.inventory.InventoryIntelligenceReadPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;
import java.util.UUID;

/** L0 orchestration. Historical coverage is not yet qualified, so period values are provisional. */
@Service
public class MetricQueryService {
    private final CalendarPolicyRepository calendars;
    private final TenantAuthorization auth;
    private final WarehouseStaffScopeService scope;
    private final SalesIntelligenceReadPort sales;
    private final InventoryIntelligenceReadPort inventory;
    private final Clock clock;
    MetricQueryService(CalendarPolicyRepository calendars,TenantAuthorization auth,WarehouseStaffScopeService scope,
            SalesIntelligenceReadPort sales,InventoryIntelligenceReadPort inventory,Clock clock){
        this.calendars=calendars;this.auth=auth;this.scope=scope;this.sales=sales;this.inventory=inventory;this.clock=clock;
    }
    @Transactional
    public MetricResult calculate(VerifiedActor actor,UUID tenant,MetricQuery query){
        if(actor==null||tenant==null)throw new SecurityException("Verified actor and tenant required");
        if(query==null)throw new IllegalArgumentException("Metric query required");
        var def=MetricRegistry.require(query.code(),query.methodVersion(),query.selection().kind());
        UUID user=actor.userId();
        auth.requireForCommand(user,tenant,DomainPermission.valueOf(def.permission()));
        PeriodResolver.Period period=null;
        List<MetricResult.Cell> cells;
        switch(query.selection()){
            case MetricSelection.OrderPeriod o -> {
                period=resolve(tenant,o.period());
                cells=sales.read(actor,tenant,query.code(),period);
            }
            case MetricSelection.MovementPeriod m -> {
                scope.requireAssigned(user,tenant,m.warehouseId());
                period=resolve(tenant,m.period());
                cells=inventory.read(actor,tenant,query.code(),m.warehouseId(),m.skuId(),period);
            }
            case MetricSelection.LivePosition l -> {
                cells=inventory.read(actor,tenant,query.code(),l.warehouseId(),l.skuId(),null);
            }
        }
        boolean live=def.basis()==MetricRegistry.Basis.LIVE_POSITION;
        DataQuality quality=cells.isEmpty()?DataQuality.MISSING:live?DataQuality.READY:DataQuality.PARTIAL;
        List<String> reasons=live?(cells.isEmpty()?List.of("POSITION_NOT_FOUND"):List.of()):List.of("HISTORICAL_COVERAGE_UNQUALIFIED");
        actor.userId();
        return new MetricResult(query.code(),query.methodVersion(),def.basis(),clock.instant(),period,quality,cells,reasons);
    }
    private PeriodResolver.Period resolve(UUID tenant,MetricSelection.PeriodChoice choice){
        var c=calendars.findByTenantIdAndVersion(tenant,choice.calendarVersion())
            .orElseThrow(()->new IllegalArgumentException("CALENDAR_VERSION_NOT_FOUND"));
        return PeriodResolver.resolve(choice.code(),choice.anchor(),new PeriodResolver.Policy(ZoneId.of(c.getZoneId()),c.getVersion()));
    }
}
