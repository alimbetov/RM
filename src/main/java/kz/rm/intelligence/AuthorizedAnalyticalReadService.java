package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** B4.8 authenticated application port. No HTTP or historical/ML admission. */
@Service
public class AuthorizedAnalyticalReadService {
    public enum Metric {
        BOOKED_ORDERS,FULFILLED_PRODUCT_ORDERS,BOOKED_SKUS,BOOKED_LINES,FULFILLED_LINES,FULFILLED_SKUS,
        BOOKED_AMOUNT,FULFILLED_AMOUNT,BOOKED_PRODUCT_UNITS,FULFILLED_PRODUCT_UNITS,STOCK_IN,STOCK_OUT
    }
    public enum Route { L3,L0 }
    public record Query(Metric metric,long calendarVersion,PeriodResolver.Code period,LocalDate anchor,
                        String region,UUID group,UUID sku,List<UUID> warehouses,String currency){
        public Query{
            Objects.requireNonNull(metric);Objects.requireNonNull(period);Objects.requireNonNull(anchor);
            warehouses=List.copyOf(warehouses);
            if(calendarVersion<1||warehouses.size()>100||new HashSet<>(warehouses).size()!=warehouses.size())
                throw new IllegalArgumentException("ANALYTICAL_SCOPE");
            warehouses=warehouses.stream().sorted().toList();
            if(region!=null&&!region.matches("[0-9]{9}"))throw new IllegalArgumentException("ANALYTICAL_REGION");
            if(currency!=null&&!currency.matches("[A-Z]{3}"))throw new IllegalArgumentException("ANALYTICAL_CURRENCY");
            var m=L3DerivedSeriesService.Metric.valueOf(metric.name());
            if(m.stream.equals("BOOKED")&&(region!=null||!warehouses.isEmpty()))
                throw new IllegalArgumentException("ANALYTICAL_BOOKED_GEOGRAPHY_UNAVAILABLE");
            if(!m.stream.equals("BOOKED")&&warehouses.isEmpty())
                throw new IllegalArgumentException("ANALYTICAL_EXPLICIT_WAREHOUSES_REQUIRED");
            if(m.unit.equals("CURRENCY")!=(currency!=null))
                throw new IllegalArgumentException("ANALYTICAL_CURRENCY_SCOPE");
            if(m.unit.equals("SKU_QUANTITY")&&sku==null)
                throw new IllegalArgumentException("ANALYTICAL_SKU_REQUIRED");
        }
    }
    public record Result(Metric metric,int methodVersion,PeriodResolver.Period period,BigDecimal value,
                         DataQuality quality,Route route,String reason,String qualityReason,String unit,String currency,
                         UUID generation,UUID proof,long sourceRevision,Instant observedAt){}
    private record Pointer(UUID generation,UUID proof,L3DerivedSeriesService.Snapshot snapshot){}
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final L3DerivedSeriesService l3;
    private final MultidimensionalCoverageService coverage;
    AuthorizedAnalyticalReadService(JdbcTemplate jdbc,PlatformTransactionManager manager,
            L3DerivedSeriesService l3,MultidimensionalCoverageService coverage){
        this.jdbc=jdbc;this.manager=manager;this.l3=l3;this.coverage=coverage;
    }
    public Result read(VerifiedActor actor,UUID tenant,Query query){
        return readWindow(actor,tenant,query,List.of(query.anchor())).getFirst();
    }
    /** One authorized scope and one MVCC snapshot for a bounded calendar window. */
    List<Result> readWindow(VerifiedActor actor,UUID tenant,Query query,List<LocalDate> anchors){
        Objects.requireNonNull(actor);Objects.requireNonNull(tenant);Objects.requireNonNull(query);
        var dates=List.copyOf(anchors);
        if(dates.isEmpty()||dates.size()>12||new HashSet<>(dates).size()!=dates.size())
            throw new IllegalArgumentException("ANALYTICAL_WINDOW_SIZE");
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("ANALYTICAL_OWN_TRANSACTION");
        var m=L3DerivedSeriesService.Metric.valueOf(query.metric().name());
        var f=new L3DerivedSeriesService.Filter(query.region(),query.group(),query.sku(),query.warehouses(),query.currency());
        var admission=new TransactionTemplate(manager);
        admission.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);admission.setTimeout(180);
        return admission.execute(outer->{
            l3.admit(actor,tenant,m,f);
            var snapshot=new TransactionTemplate(manager);
            snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            snapshot.setReadOnly(true);snapshot.setTimeout(120);
            List<Result> result=snapshot.execute(inner->dates.stream().map(anchor->
                readSnapshot(tenant,new Query(query.metric(),query.calendarVersion(),query.period(),anchor,
                    query.region(),query.group(),query.sku(),query.warehouses(),query.currency()),m,f)).toList());
            actor.userId();
            return result;
        });
    }
    private Result readSnapshot(UUID tenant,Query q,L3DerivedSeriesService.Metric m,L3DerivedSeriesService.Filter f){
        if(!Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql"))))
            throw new IllegalStateException("ANALYTICAL_POSTGRESQL_REQUIRED");
        String zone=jdbc.query("SELECT zone_id FROM crm.int_calendar_policy WHERE tenant_id=? AND version=?",
            rs->rs.next()?rs.getString(1):null,tenant,q.calendarVersion());
        if(zone==null)throw new IllegalArgumentException("CALENDAR_VERSION_NOT_FOUND");
        if(q.group()!=null&&!Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM crm.cat_group_node WHERE tenant_id=? AND id=?)",Boolean.class,tenant,q.group())))
            throw new IllegalArgumentException("ANALYTICAL_GROUP_NOT_FOUND");
        if(q.sku()!=null&&!Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM crm.cat_item WHERE tenant_id=? AND id=?)",Boolean.class,tenant,q.sku())))
            throw new IllegalArgumentException("ANALYTICAL_SKU_NOT_FOUND");
        var policy=new PeriodResolver.Policy(ZoneId.of(zone),q.calendarVersion());
        var period=PeriodResolver.resolve(q.period(),q.anchor(),policy);
        Instant now=Objects.requireNonNull(jdbc.queryForObject("SELECT transaction_timestamp()",Timestamp.class)).toInstant();
        long revision=Objects.requireNonNull(jdbc.queryForObject("""
            SELECT coalesce((SELECT revision FROM crm.int_source_revision WHERE tenant_id=?),0)
              +(SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?)
            """,Long.class,tenant,tenant));
        if(period.startInclusive().isAfter(now)||period.startInclusive().equals(now))
            return result(q,m,period,null,Route.L0,"FUTURE_PERIOD",null,null,revision,now);
        Pointer pointer=jdbc.query("""
            SELECT a.generation_id,a.dim_proof_id,g.from_day,g.until_day,
              d.dirty_revision,d.receipt_revision,d.materialization_revision,l.revision
            FROM crm.int_active_generation a
            JOIN crm.int_projection_generation g USING(tenant_id,generation_id)
            JOIN crm.int_projection_lifecycle l USING(tenant_id,generation_id)
            JOIN crm.int_dim_revision d USING(tenant_id,generation_id)
            WHERE a.tenant_id=? AND a.projection_code='L3' AND l.lifecycle_state='ACTIVE'
              AND g.calendar_version=? AND g.method_version=1
            """,rs->rs.next()?new Pointer(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                new L3DerivedSeriesService.Snapshot(rs.getObject(3,LocalDate.class),rs.getObject(4,LocalDate.class),
                    policy,new L3DerivedSeriesService.Revision(revision,rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8)))):null,
            tenant,q.calendarVersion());
        String reason=period.endExclusive().isAfter(now)?"OPEN_PERIOD":"NO_MATCHING_ACTIVE_GENERATION";
        LocalDate from=period.startInclusive().atZone(policy.zone()).toLocalDate();
        LocalDate until=period.endExclusive().atZone(policy.zone()).toLocalDate();
        if(pointer!=null){
            if(from.isBefore(pointer.snapshot().from())||until.isAfter(pointer.snapshot().until()))
                reason="OUTSIDE_GENERATION";
            else if(period.endExclusive().isAfter(now))reason="OPEN_PERIOD";
            else{
                var status=coverage.statusInSnapshot(tenant,pointer.generation(),pointer.proof());
                reason="PROOF_"+status.name();
                if(status==MultidimensionalCoverageService.Status.CURRENT){
                    var obs=l3.readPeriods(tenant,pointer.generation(),m,f,q.period(),List.of(period),pointer.snapshot()).getFirst();
                    if(obs.quality()!=DataQuality.STALE)
                        return result(q,m,period,obs.value(),Route.L3,"CURRENT_SOURCE_PARITY",
                            pointer.generation(),pointer.proof(),revision,now);
                    reason="PERIOD_STAMP_STALE";
                }
            }
        }
        // L0 reads owner source rows, not a retired or stale materialization.
        // Same historical keys, filters and grain as L3; never sum distinct counts.
        BigDecimal value=ownerValue(tenant,m,f,period,now);
        return result(q,m,period,value,Route.L0,reason,null,null,revision,now);
    }
    private static Result result(Query q,L3DerivedSeriesService.Metric m,PeriodResolver.Period period,
            BigDecimal value,Route route,String reason,UUID gen,UUID proof,long revision,Instant now){
        // Source parity is not historical completeness; preserve the existing PARTIAL contract.
        String qualityReason=value==null?"NO_QUALIFIED_OBSERVATION":
            period.endExclusive().isAfter(now)?"PERIOD_NOT_CLOSED":"HISTORICAL_COVERAGE_UNQUALIFIED";
        return new Result(q.metric(),1,period,value,value==null?DataQuality.MISSING:DataQuality.PARTIAL,
            route,reason,qualityReason,m.unit,q.currency(),gen,proof,revision,now);
    }
    private BigDecimal ownerValue(UUID tenant,L3DerivedSeriesService.Metric m,L3DerivedSeriesService.Filter f,
            PeriodResolver.Period period,Instant now){
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(m.stream);args.add(Timestamp.from(period.startInclusive()));
        args.add(Timestamp.from(period.endExclusive().isAfter(now)?now:period.endExclusive()));
        StringBuilder sql=new StringBuilder("""
            SELECT %s FROM crm.int_l2_expected_source
            WHERE tenant_id=? AND stream_code=? AND event_at>=? AND event_at<?
            """.formatted(switch(m.operation){
                case "ORDER" -> "CASE WHEN count(*)>0 THEN count(DISTINCT order_id)::numeric END";
                case "SKU" -> "CASE WHEN count(*)>0 AND count(sku_id)=count(*) THEN count(DISTINCT sku_id)::numeric END";
                case "LINE" -> "CASE WHEN count(*)>0 AND count(line_id)=count(*) THEN count(DISTINCT line_id)::numeric END";
                case "AMOUNT" -> "sum(amount)";
                case "PRODUCT_UNITS" -> "CASE WHEN count(*)=count(product_order_units) THEN sum(product_order_units) END";
                case "QTY_IN" -> "sum(qty_in)";
                case "QTY_OUT" -> "sum(qty_out)";
                default -> throw new IllegalArgumentException("ANALYTICAL_METRIC");
            }));
        if(f.region()!=null){sql.append(" AND region_code=?");args.add(f.region());}
        if(f.group()!=null){
            sql.append("""
                AND group_id IN (
                    WITH RECURSIVE descendants(id) AS (
                        SELECT id FROM crm.cat_group_node WHERE tenant_id=? AND id=?
                        UNION SELECT n.id FROM crm.cat_group_node n JOIN descendants d ON n.parent_id=d.id WHERE n.tenant_id=?
                    ) SELECT id FROM descendants)
                """);
            args.add(tenant);args.add(f.group());args.add(tenant);
        }
        if(f.sku()!=null){sql.append(" AND sku_id=?");args.add(f.sku());}
        if(f.currency()!=null){sql.append(" AND currency=?");args.add(f.currency());}
        if(!f.warehouses().isEmpty()){
            sql.append(" AND warehouse_id IN (").append(String.join(",",Collections.nCopies(f.warehouses().size(),"?"))).append(")");
            args.addAll(f.warehouses());
        }
        if(m==L3DerivedSeriesService.Metric.FULFILLED_PRODUCT_ORDERS||m.operation.equals("PRODUCT_UNITS"))
            sql.append(" AND item_kind='PRODUCT'");
        return jdbc.queryForObject(sql.toString(),BigDecimal.class,args.toArray());
    }
}
