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
        BOOKED_LINES("BOOKED","LINE","COUNT"),
        FULFILLED_LINES("FULFILLED","LINE","COUNT"),
        FULFILLED_SKUS("FULFILLED","SKU","COUNT"),
        BOOKED_AMOUNT("BOOKED","AMOUNT","CURRENCY"),
        FULFILLED_AMOUNT("FULFILLED","AMOUNT","CURRENCY"),
        BOOKED_PRODUCT_UNITS("BOOKED","PRODUCT_UNITS","ORDER_UNITS"),
        FULFILLED_PRODUCT_UNITS("FULFILLED","PRODUCT_UNITS","ORDER_UNITS"),
        STOCK_IN("STOCK","QTY_IN","SKU_QUANTITY"),
        STOCK_OUT("STOCK","QTY_OUT","SKU_QUANTITY");
        final String stream, operation, unit;
        MetricRegistry.Aggregation aggregation(){return identity()==MetricRegistry.Identity.NONE?MetricRegistry.Aggregation.ADDITIVE:MetricRegistry.Aggregation.EXACT_DISTINCT;}
        MetricRegistry.Identity identity(){return switch(operation){case "ORDER" -> MetricRegistry.Identity.ORDER; case "LINE" -> MetricRegistry.Identity.ORDER_LINE; case "SKU" -> MetricRegistry.Identity.SKU; default -> MetricRegistry.Identity.NONE;};}
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
                  Map<DerivedMetricEngine.Operator,BigDecimal> sqlCandidates,
                  PeriodResolver.Code periodCode,LocalDate anchor,Instant observedAt){}
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
            var observations=readPeriods(tenant,generation,metric,filter,code,periods,snapshot);
            var points=new ArrayList<DerivedMetricEngine.Point>();
            for(var o:observations){
                var arithmeticQuality=parity && o.quality()==DataQuality.PARTIAL && o.value()!=null ? DataQuality.READY:o.quality();
                points.add(new DerivedMetricEngine.Point(scope,o.period(),o.value(),arithmeticQuality,now));
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
            if(!sqlCandidates.isEmpty()){
                for(var op:DerivedMetricEngine.Operator.values()){
                    if(!DerivedNumericParity.equivalent(op,derived.get(op).value(),sqlCandidates.get(op)))
                        throw new IllegalStateException("DERIVED_NUMERIC_PARITY_MISMATCH:"+op);
                }
            }
            return new Series(tenant,generation,metric,filter,snapshot.revision(),List.copyOf(observations),Map.copyOf(derived),sqlCandidates,code,anchor,now);
        });
    }
    // One set-based extraction for all requested calendar periods. Missing periods stay explicit.
    private List<Observation> readPeriods(UUID tenant,UUID gen,Metric metric,Filter f,PeriodResolver.Code code,
                                          List<PeriodResolver.Period> periods,Snapshot snapshot){
        var args=new ArrayList<Object>();
        for(var period:periods){
            args.add(period.startInclusive().atZone(snapshot.calendar().zone()).toLocalDate());
            args.add(period.endExclusive().atZone(snapshot.calendar().zone()).toLocalDate());
        }
        String tuples=String.join(",",Collections.nCopies(periods.size(),"(CAST(? AS date),CAST(? AS date))"));
        StringBuilder sql=new StringBuilder("""
            WITH requested(from_day,until_day) AS (VALUES %s),
            valid AS (
              SELECT p.* FROM requested r JOIN crm.int_l3_period p
                ON p.from_day=r.from_day AND p.until_day=r.until_day
              WHERE p.tenant_id=? AND p.generation_id=? AND p.period_code=? AND p.refreshed_revision=?
            ), selected AS (
              SELECT x.* FROM crm.int_l3_cell x JOIN valid p USING(tenant_id,generation_id,period_code,from_day)
              WHERE x.stream_code=?
            """.formatted(tuples));
        args.addAll(List.of(tenant,gen,code.name(),snapshot.revision().dirty(),metric.stream));
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
        sql.append("), aggregated AS (");
        sql.append(switch(metric.operation){
            case "ORDER" -> """
                SELECT s.from_day,count(DISTINCT o.order_id)::numeric AS value FROM selected s
                JOIN crm.int_l3_cell_order o USING(tenant_id,generation_id,period_code,from_day,cell_id)
                GROUP BY s.from_day
                """;
            case "SKU" -> "SELECT from_day,count(DISTINCT sku_id)::numeric AS value FROM selected GROUP BY from_day HAVING count(sku_id)=count(*)";
            case "LINE" -> """
                SELECT s.from_day,count(DISTINCT l.line_id)::numeric AS value FROM selected s
                JOIN valid p USING(tenant_id,generation_id,period_code,from_day)
                JOIN crm.int_l2_contribution l ON l.tenant_id=s.tenant_id AND l.generation_id=s.generation_id
                  AND l.local_day>=p.from_day AND l.local_day<p.until_day
                  AND (l.stream_code,l.region_code,l.group_id,l.item_kind,l.warehouse_id,l.sku_id,l.currency)
                    IS NOT DISTINCT FROM (s.stream_code,s.region_code,s.group_id,s.item_kind,s.warehouse_id,s.sku_id,s.currency)
                GROUP BY s.from_day HAVING count(l.line_id)=count(*)
                """;
            case "AMOUNT" -> "SELECT from_day,sum(amount) AS value FROM selected GROUP BY from_day";
            case "PRODUCT_UNITS" -> "SELECT from_day,CASE WHEN count(*)=count(product_order_units) THEN sum(product_order_units) END AS value FROM selected GROUP BY from_day";
            case "QTY_IN" -> "SELECT from_day,sum(qty_in) AS value FROM selected GROUP BY from_day";
            case "QTY_OUT" -> "SELECT from_day,sum(qty_out) AS value FROM selected GROUP BY from_day";
            default -> throw new IllegalArgumentException("DERIVED_METRIC");
        });
        sql.append("""
            ) SELECT r.from_day,v.from_day IS NOT NULL,a.value FROM requested r
              LEFT JOIN valid v ON v.from_day=r.from_day
              LEFT JOIN aggregated a ON a.from_day=r.from_day ORDER BY r.from_day
            """);
        record Row(boolean current,BigDecimal value){}
        var rows=jdbc.query(sql.toString(),rs->{
            var result=new HashMap<LocalDate,Row>();
            while(rs.next())if(result.put(rs.getObject(1,LocalDate.class),new Row(rs.getBoolean(2),rs.getBigDecimal(3)))!=null)
                throw new IllegalStateException("DERIVED_DUPLICATE_PERIOD");
            return result;
        },args.toArray());
        var result=new ArrayList<Observation>();
        for(var period:periods){
            LocalDate from=period.startInclusive().atZone(snapshot.calendar().zone()).toLocalDate();
            LocalDate until=period.endExclusive().atZone(snapshot.calendar().zone()).toLocalDate();
            Row row=rows.get(from);
            if(row==null)throw new IllegalStateException("DERIVED_PERIOD_MISSING");
            if(from.isBefore(snapshot.from())||until.isAfter(snapshot.until()))
                result.add(new Observation(period,null,DataQuality.MISSING,"OUTSIDE_GENERATION"));
            else if(!row.current())result.add(new Observation(period,null,DataQuality.STALE,"L3_PERIOD_NOT_CURRENT"));
            else result.add(new Observation(period,row.value(),row.value()==null?DataQuality.MISSING:DataQuality.PARTIAL,
                row.value()==null?"NO_QUALIFIED_VALUE":"SHADOW_L3_SOURCE_ADMISSION_REQUIRED"));
        }
        return List.copyOf(result);
    }
}
