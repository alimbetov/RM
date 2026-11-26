package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import kz.rm.iam.WarehouseStaffScopeService;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Internal shadow-only L3 series reader. No actor-facing API or authorization grant.
 * Outputs are observed candidates, NOT qualified historical features. */
@Service
class L3DerivedSeriesService {
    enum Metric {
        BOOKED_ORDERS("BOOKED","ORDER", "COUNT"),
        FULFILLED_PRODUCT_ORDERS("FULFILLED","ORDER","COUNT"),
        BOOKED_SKUS("BOOKED","SKU","COUNT"),
        FULFILLED_SKUS("FULFILLED","SKU","COUNT"),
        BOOKED_AMOUNT("BOOKED","AMOUNT","CURRENCY"),
        FULFILLED_AMOUNT("FULFILLED","AMOUNT","CURRENCY"),
        BOOKED_PRODUCT_UNITS("BOOKED","PRODUCT_UNITS","ORDER_UNITS"),
        FULFILLED_PRODUCT_UNITS("FULFILLED","PRODUCT_UNITS","ORDER_UNITS"),
        STOCK_IN("STOCK","QTY_IN","SKU_QUANTITY"),
        STOCK_OUT("STOCK","QTY_OUT","SKU_QUANTITY");
        final String stream, operation, unit;
        Metric(String stream,String operation,String unit){this.stream=stream;this.operation=operation;this.unit=unit;}
    }
    // Historical node IDs; group selects the node and its historical descendants. Null means no filter.
    record Filter(String region, UUID group, UUID sku, List<UUID> warehouses, String currency) {
        Filter {
            warehouses=List.copyOf(warehouses);
            if(warehouses.size()>100 || new HashSet<>(warehouses).size()!=warehouses.size())
                throw new IllegalArgumentException("DERIVED_WAREHOUSE_SCOPE");
            if(currency!=null&&!currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("DERIVED_CURRENCY");
        }
    }
    record Revision(long source,long dirty,long receipt,long materialization,long lifecycle){}
    record Snapshot(LocalDate from,LocalDate until,PeriodResolver.Policy calendar,Revision revision){}
    record Observation(PeriodResolver.Period period,BigDecimal value,DataQuality quality,String reason){}
    record Series(UUID tenant,UUID generation,Metric metric,Filter filter,Revision revision,
                  List<Observation> facts,Map<DerivedMetricEngine.Operator,DerivedMetricEngine.Result> derived,
                  Map<DerivedMetricEngine.Operator,BigDecimal> sqlCandidates){}
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final MultidimensionalCoverageService coverage;
    private final TenantAuthorization authorization;
    private final WarehouseStaffScopeService warehouseScope;
    L3DerivedSeriesService(JdbcTemplate jdbc,PlatformTransactionManager manager,MultidimensionalCoverageService coverage,
                           TenantAuthorization authorization,WarehouseStaffScopeService warehouseScope){
        this.jdbc=jdbc;this.manager=manager;this.coverage=coverage;this.authorization=authorization;this.warehouseScope=warehouseScope;
    }
    // Fresh RC authorization fences remain held while the nested RR snapshot reads evidence.
    // Internal Java API only. No HTTP admission or historical authorization claim.
    Series readAuthorized(VerifiedActor actor,UUID tenant,UUID generation,UUID proofId,Metric metric,Filter filter,
                          PeriodResolver.Code code,LocalDate anchor,int window){
        Objects.requireNonNull(actor);Objects.requireNonNull(metric);Objects.requireNonNull(filter);Objects.requireNonNull(proofId);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("DERIVED_REQUIRES_OWN_TRANSACTION");
        var admission=new TransactionTemplate(manager);
        admission.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);admission.setTimeout(180);
        return admission.execute(status->{
            if(!metric.stream.equals("STOCK"))authorization.requireForCommand(actor.userId(),tenant,DomainPermission.SALES_ORDER_READ);
            if(!metric.stream.equals("BOOKED")){
                authorization.requireForCommand(actor.userId(),tenant,DomainPermission.INVENTORY_STOCK_READ);
                for(UUID warehouse:filter.warehouses().stream().sorted().toList())warehouseScope.requireAssigned(actor.userId(),tenant,warehouse);
            }
            return readSnapshot(tenant,generation,metric,filter,code,anchor,window,proofId,actor.userId().toString());
        });
    }
    Series read(UUID tenant,UUID generation,Metric metric,Filter filter,PeriodResolver.Code code,LocalDate anchor,int window){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("DERIVED_REQUIRES_OWN_TRANSACTION");
        return readSnapshot(tenant,generation,metric,filter,code,anchor,window,null,"INTERNAL_SHADOW_ONLY");
    }
    private Series readSnapshot(UUID tenant,UUID generation,Metric metric,Filter filter,PeriodResolver.Code code,LocalDate anchor,int window,UUID proofId,String actorKey){
        Objects.requireNonNull(tenant);Objects.requireNonNull(generation);Objects.requireNonNull(metric);
        Objects.requireNonNull(filter);Objects.requireNonNull(code);Objects.requireNonNull(anchor);
        if(metric.stream.equals("BOOKED") && (filter.region()!=null || !filter.warehouses().isEmpty()))
            throw new IllegalArgumentException("DERIVED_BOOKED_GEOGRAPHY_UNAVAILABLE");
        if(!metric.stream.equals("BOOKED") && filter.warehouses().isEmpty())
            throw new IllegalArgumentException("DERIVED_EXPLICIT_WAREHOUSES_REQUIRED");
        if(metric.unit.equals("CURRENCY") != (filter.currency()!=null))
            throw new IllegalArgumentException("DERIVED_CURRENCY_SCOPE");
        if(metric.unit.equals("SKU_QUANTITY") && filter.sku()==null)
            throw new IllegalArgumentException("DERIVED_SKU_REQUIRED");
        var tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(120);
        return tx.execute(status->{
            if(!Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c->
                c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql"))))
                throw new IllegalStateException("DERIVED_POSTGRESQL_REQUIRED");
            Snapshot snapshot=jdbc.query("""
                SELECT g.from_day,g.until_day,p.zone_id,g.calendar_version,
                  coalesce(r.revision,0)+(SELECT count(*) FROM crm.int_source_revision_event e WHERE e.tenant_id=g.tenant_id),
                  d.dirty_revision,d.receipt_revision,d.materialization_revision,l.revision
                FROM crm.int_projection_generation g
                JOIN crm.int_projection_lifecycle l USING(tenant_id,generation_id)
                JOIN crm.int_dim_revision d USING(tenant_id,generation_id)
                JOIN crm.int_calendar_policy p ON p.tenant_id=g.tenant_id AND p.version=g.calendar_version
                LEFT JOIN crm.int_source_revision r ON r.tenant_id=g.tenant_id
                WHERE g.tenant_id=? AND g.generation_id=? AND l.lifecycle_state='BUILDING'
                """,rs->rs.next()?new Snapshot(rs.getObject(1,LocalDate.class),rs.getObject(2,LocalDate.class),
                    new PeriodResolver.Policy(ZoneId.of(rs.getString(3)),rs.getLong(4)),
                    new Revision(rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8),rs.getLong(9))):null,tenant,generation);
            if(snapshot==null)throw new IllegalArgumentException("DERIVED_BUILDING_GENERATION_REQUIRED");
            boolean parity=proofId!=null && coverage.statusInSnapshot(tenant,generation,proofId)==MultidimensionalCoverageService.Status.CURRENT;
            Instant now=Objects.requireNonNull(jdbc.queryForObject("SELECT transaction_timestamp()",java.sql.Timestamp.class)).toInstant();
            var scope=new DerivedMetricEngine.Scope(tenant,generation,metric.name(),1,filter.toString(),
                actorKey,metric.unit,Objects.toString(filter.currency(),"NONE"),code,snapshot.calendar(),snapshot.revision().source());
            var periods=DerivedMetricEngine.closedWindow(scope,anchor,now,window);
            var observations=new ArrayList<Observation>();
            var points=new ArrayList<DerivedMetricEngine.Point>();
            for(var period:periods){
                LocalDate from=period.startInclusive().atZone(snapshot.calendar().zone()).toLocalDate();
                LocalDate until=period.endExclusive().atZone(snapshot.calendar().zone()).toLocalDate();
                Observation o;
                if(from.isBefore(snapshot.from())||until.isAfter(snapshot.until()))
                    o=new Observation(period,null,DataQuality.MISSING,"OUTSIDE_GENERATION");
                else o=readPeriod(tenant,generation,metric,filter,code,period,from,until,snapshot.revision().dirty());
                observations.add(o);
                // Source parity permits candidate arithmetic, not a historical completeness or activation claim.
                var arithmeticQuality=parity && o.quality()==DataQuality.PARTIAL && o.value()!=null ? DataQuality.READY:o.quality();
                points.add(new DerivedMetricEngine.Point(scope,period,o.value(),arithmeticQuality,now));
            }
            var derived=new EnumMap<DerivedMetricEngine.Operator,DerivedMetricEngine.Result>(DerivedMetricEngine.Operator.class);
            for(var op:DerivedMetricEngine.Operator.values()){
                var result=DerivedMetricEngine.calculate(scope,op,anchor,now,window,points);
                if(result.quality()==DataQuality.READY)
                    result=new DerivedMetricEngine.Result(op,result.methodVersion(),window,DataQuality.PARTIAL,result.value(),
                        "SOURCE_PARITY_ONLY_HISTORICAL_ADMISSION_REQUIRED");
                derived.put(op,result);
            }
            var sqlCandidates=parity && points.stream().allMatch(p->p.quality()==DataQuality.READY)
                ?PostgresDerivedWindows.latest(jdbc,points.stream().map(DerivedMetricEngine.Point::value).toList())
                :Map.<DerivedMetricEngine.Operator,BigDecimal>of();
            return new Series(tenant,generation,metric,filter,snapshot.revision(),List.copyOf(observations),Map.copyOf(derived),sqlCandidates);
        });
    }
    private Observation readPeriod(UUID tenant,UUID gen,Metric metric,Filter f,PeriodResolver.Code code,
                                   PeriodResolver.Period period,LocalDate from,LocalDate until,long dirty){
        Long stamp=jdbc.query("""
            SELECT refreshed_revision FROM crm.int_l3_period
            WHERE tenant_id=? AND generation_id=? AND period_code=? AND from_day=? AND until_day=?
            """,rs->rs.next()?rs.getObject(1,Long.class):null,tenant,gen,code.name(),from,until);
        if(stamp==null||stamp!=dirty)return new Observation(period,null,DataQuality.STALE,"L3_PERIOD_NOT_CURRENT");
        var args=new ArrayList<Object>(List.of(tenant,gen,code.name(),from,metric.stream));
        StringBuilder sql=new StringBuilder("""
            WITH selected AS (
              SELECT * FROM crm.int_l3_cell WHERE tenant_id=? AND generation_id=? AND period_code=? AND from_day=? AND stream_code=?
            """);
        if(f.region()!=null){sql.append(" AND region_code=?");args.add(f.region());}
        if(f.group()!=null){
            sql.append("""
                AND group_id IN (
                    WITH RECURSIVE descendants(id) AS (
                        SELECT id FROM crm.cat_group_node WHERE tenant_id=? AND id=?
                        UNION
                        SELECT n.id FROM crm.cat_group_node n JOIN descendants d ON n.parent_id=d.id
                        WHERE n.tenant_id=?
                    ) SELECT id FROM descendants
                )
                """);
            args.add(tenant);args.add(f.group());args.add(tenant);
        }
        if(f.sku()!=null){sql.append(" AND sku_id=?");args.add(f.sku());}
        if(f.currency()!=null){sql.append(" AND currency=?");args.add(f.currency());}
        if(!f.warehouses().isEmpty()){
            sql.append(" AND warehouse_id IN (").append(String.join(",",Collections.nCopies(f.warehouses().size(),"?"))).append(")");
            args.addAll(f.warehouses());
        }
        if(metric==Metric.FULFILLED_PRODUCT_ORDERS || metric.operation.equals("PRODUCT_UNITS"))sql.append(" AND item_kind='PRODUCT'");
        sql.append(") ");
        sql.append(switch(metric.operation){
            case "ORDER" -> """
                SELECT count(DISTINCT o.order_id)::numeric FROM selected s JOIN crm.int_l3_cell_order o
                  USING(tenant_id,generation_id,period_code,from_day,cell_id) HAVING count(*)>0
                """;
            case "SKU" -> "SELECT count(DISTINCT sku_id)::numeric FROM selected HAVING count(*)>0 AND count(sku_id)=count(*)";
            case "AMOUNT" -> "SELECT sum(amount) FROM selected";
            case "PRODUCT_UNITS" -> "SELECT CASE WHEN count(*)=count(product_order_units) THEN sum(product_order_units) END FROM selected";
            case "QTY_IN" -> "SELECT sum(qty_in) FROM selected";
            case "QTY_OUT" -> "SELECT sum(qty_out) FROM selected";
            default -> throw new IllegalArgumentException("DERIVED_METRIC");
        });
        BigDecimal value=jdbc.query(sql.toString(),rs->rs.next()?rs.getBigDecimal(1):null,args.toArray());
        return new Observation(period,value,value==null?DataQuality.MISSING:DataQuality.PARTIAL,
            value==null?"NO_QUALIFIED_VALUE":"SHADOW_L3_SOURCE_ADMISSION_REQUIRED");
    }
}
