package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** D06C: the remaining workbook bindings use the existing owner metric port
 * and the same owner-source view used by analytical L0. No publication/API. */
@Service
final class ReportTemplateOwnerBindings {
    record Snapshot(UUID tenant,Instant asOf,long revision){}
    record StatusRow(String metric,BigDecimal current,BigDecimal prior,DataQuality currentQuality,DataQuality priorQuality){}
    record Position(UUID sku,BigDecimal value,DataQuality quality){}
    record DistributionRow(String key,BigDecimal current,BigDecimal prior){}
    private record Orders(Snapshot snapshot,PeriodResolver.Period current,PeriodResolver.Period prior,List<StatusRow> rows){}
    private record Positions(Snapshot snapshot,List<Position> rows){}
    private record Distribution(Snapshot snapshot,PeriodResolver.Period period,PeriodResolver.Period prior,List<DistributionRow> rows){}
    private static final List<MetricRegistry.Code> STATUS=List.of(MetricRegistry.Code.PLACED_ORDERS,MetricRegistry.Code.CONFIRMED_ORDERS,
        MetricRegistry.Code.FULFILLED_ORDERS,MetricRegistry.Code.CANCELLED_ORDERS);
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final L3DerivedSeriesService admission;
    private final MetricQueryService metrics;
    ReportTemplateOwnerBindings(JdbcTemplate jdbc,PlatformTransactionManager manager,L3DerivedSeriesService admission,MetricQueryService metrics){
        this.jdbc=jdbc;this.manager=manager;this.admission=admission;this.metrics=metrics;
    }
    byte[] orders(VerifiedActor actor,UUID tenant,long calendar,YearMonth month){
        Objects.requireNonNull(month);
        var data=read(actor,tenant,snapshot->{
            var policy=calendar(tenant,calendar);
            var current=PeriodResolver.resolve(PeriodResolver.Code.M,month.atDay(1),policy);
            var prior=PeriodResolver.resolve(PeriodResolver.Code.M,month.minusMonths(1).atDay(1),policy);
            if(current.endExclusive().isAfter(snapshot.asOf()))throw new IllegalArgumentException("REPORT_MONTH_NOT_CLOSED");
            var rows=STATUS.stream().map(code->{
                var c=metrics.calculate(actor,tenant,orderQuery(code,calendar,month.atDay(1)));
                var p=metrics.calculate(actor,tenant,orderQuery(code,calendar,month.minusMonths(1).atDay(1)));
                if(!current.equals(c.period())||!prior.equals(p.period()))throw new IllegalStateException("REPORT_ORDER_PERIOD");
                return new StatusRow(code.name(),scalar(c),scalar(p),c.quality(),p.quality());
            }).toList();
            return new Orders(snapshot,current,prior,rows);
        });
        return ReportTemplateRenderer.renderOrders(data.snapshot(),data.current(),data.prior(),data.rows());
    }
    byte[] positions(VerifiedActor actor,UUID tenant,UUID warehouse,List<UUID> skus){
        Objects.requireNonNull(warehouse);
        var ids=List.copyOf(skus);
        if(ids.isEmpty()||ids.size()>4||new HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("REPORT_POSITION_CAPACITY_4");
        var data=read(actor,tenant,snapshot->{
            // The owner port checks actual warehouse assignment before stock access.
            var rows=new ArrayList<Position>();
            for(UUID sku:ids){
                var result=metrics.calculate(actor,tenant,new MetricQuery(MetricRegistry.Code.CURRENT_AVAILABLE,1,
                    new MetricSelection.LivePosition(warehouse,sku)));
                if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM crm.cat_item WHERE tenant_id=? AND id=?)",Boolean.class,tenant,sku)))
                    throw new IllegalArgumentException("REPORT_POSITION_SKU_NOT_FOUND");
                if(result.cells().stream().anyMatch(c->!warehouse.equals(c.warehouseId())||!sku.equals(c.skuId())))
                    throw new IllegalStateException("REPORT_POSITION_SCOPE");
                rows.add(new Position(sku,scalar(result),result.quality()));
            }
            return new Positions(snapshot,List.copyOf(rows));
        });
        return ReportTemplateRenderer.renderPositions(data.snapshot(),warehouse,data.rows());
    }
    /** Closed month, or the current day for descriptive INTRADAY. No incomplete
     * population is presented as a complete ranked distribution or growth. */
    byte[] distribution(VerifiedActor actor,UUID tenant,String code,long calendar,PeriodResolver.Code period,LocalDate anchor,
                        List<UUID> warehouses,String currency){
        if(!Set.of("RM-R04","RM-R05").contains(code)||!Set.of(PeriodResolver.Code.M,PeriodResolver.Code.D).contains(period)
            ||anchor==null||currency==null||!currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("REPORT_DISTRIBUTION_REQUEST");
        java.util.Currency.getInstance(currency);
        var ids=List.copyOf(warehouses);
        if(ids.isEmpty()||ids.size()>100||new HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("REPORT_DISTRIBUTION_SCOPE");
        var data=read(actor,tenant,snapshot->{
            admission.admit(actor,tenant,L3DerivedSeriesService.Metric.FULFILLED_AMOUNT,
                new L3DerivedSeriesService.Filter(null,null,null,ids,currency));
            var policy=calendar(tenant,calendar);
            var current=PeriodResolver.resolve(period,anchor,policy);
            if(!current.startInclusive().atZone(policy.zone()).toLocalDate().equals(anchor))
                throw new IllegalArgumentException("REPORT_DISTRIBUTION_ANCHOR");
            boolean open=current.endExclusive().isAfter(snapshot.asOf());
            if(open&&(period!=PeriodResolver.Code.D||current.startInclusive().isAfter(snapshot.asOf())))
                throw new IllegalArgumentException("REPORT_DISTRIBUTION_OPEN_PERIOD");
            var prior=code.equals("RM-R05")&&!open?PeriodResolver.resolve(period,
                period==PeriodResolver.Code.M?anchor.minusMonths(1):anchor.minusDays(1),policy):null;
            Instant end=open?snapshot.asOf():current.endExclusive();
            Instant from=prior==null?current.startInclusive():prior.startInclusive();
            // Fixed SQL expressions only. Preserve UNKNOWN region and never
            // combine currencies or expose unassigned warehouse data.
            String key=code.equals("RM-R04")?"sku_id::text":"coalesce(region_code,'UNKNOWN')";
            int capacity=code.equals("RM-R04")?500:4;
            String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
            var args=new ArrayList<Object>();
            args.add(Timestamp.from(current.startInclusive()));args.add(Timestamp.from(end));
            args.add(Timestamp.from(from));args.add(Timestamp.from(current.startInclusive()));
            args.add(tenant);args.add(currency);args.add(Timestamp.from(from));args.add(Timestamp.from(end));args.addAll(ids);args.add(capacity+1);
            var rows=jdbc.query("""
                SELECT %s AS scope_key,
                    sum(amount) FILTER(WHERE event_at>=? AND event_at<?),
                    sum(amount) FILTER(WHERE event_at>=? AND event_at<?)
                FROM crm.int_l2_expected_source
                WHERE tenant_id=? AND stream_code='FULFILLED' AND currency=?
                  AND event_at>=? AND event_at<? AND warehouse_id IN (%s)
                GROUP BY %s ORDER BY scope_key LIMIT ?
                """.formatted(key,marks,key),(rs,n)->new DistributionRow(rs.getString(1),rs.getBigDecimal(2),rs.getBigDecimal(3)),args.toArray());
            if(rows.size()>capacity)throw new IllegalStateException("REPORT_TEMPLATE_CAPACITY_"+capacity);
            if(rows.stream().anyMatch(row->row.key()==null))throw new IllegalStateException("REPORT_DISTRIBUTION_IDENTITY");
            return new Distribution(snapshot,current,prior,List.copyOf(rows));
        });
        return ReportTemplateRenderer.renderDistribution(code,data.snapshot(),data.period(),data.prior(),ids,currency,data.rows());
    }
    byte[] gated(VerifiedActor actor,UUID tenant,String code){
        String reason=switch(code){
            case "RM-R08" -> "METHOD_GATED: W3 ABC/XYZ";
            case "RM-R09" -> "OWNER_GATED: C1/C2 customer and PII";
            case "RM-R12" -> "OWNER_GATED: CUR-R2C report source";
            default -> throw new IllegalArgumentException("REPORT_GATED_TEMPLATE");
        };
        var snapshot=read(actor,tenant,s->s);
        return ReportTemplateRenderer.renderGated(code,snapshot,reason);
    }
    private <T> T read(VerifiedActor actor,UUID tenant,java.util.function.Function<Snapshot,T> work){
        Objects.requireNonNull(actor);Objects.requireNonNull(tenant);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("REPORT_OWNER_TRANSACTION");
        var tx=new TransactionTemplate(manager);
        // Existing owner admission uses row locks; do not set PostgreSQL readOnly.
        // Bounded operation, only source reads, renderer runs after transaction ends.
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(30);
        return Objects.requireNonNull(tx.execute(status->{
            admission.admitReport(actor,tenant);
            // Read timestamp and revision in the very same MVCC snapshot.
            Snapshot snapshot=Objects.requireNonNull(jdbc.queryForObject("""
                SELECT transaction_timestamp(),
                  coalesce((SELECT revision FROM crm.int_source_revision WHERE tenant_id=?),0)+
                  (SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?)
                """,(rs,n)->new Snapshot(tenant,rs.getTimestamp(1).toInstant(),rs.getLong(2)),tenant,tenant));
            var result=work.apply(snapshot);actor.userId();return result;
        }));
    }
    private PeriodResolver.Policy calendar(UUID tenant,long version){
        String zone=jdbc.query("SELECT zone_id FROM crm.int_calendar_policy WHERE tenant_id=? AND version=?",
            rs->rs.next()?rs.getString(1):null,tenant,version);
        if(zone==null)throw new IllegalArgumentException("CALENDAR_VERSION_NOT_FOUND");
        return new PeriodResolver.Policy(ZoneId.of(zone),version);
    }
    private static MetricQuery orderQuery(MetricRegistry.Code code,long calendar,LocalDate date){
        return new MetricQuery(code,1,new MetricSelection.OrderPeriod(new MetricSelection.PeriodChoice(PeriodResolver.Code.M,date,calendar)));
    }
    private static BigDecimal scalar(MetricResult result){
        if(result.cells().size()>1)throw new IllegalStateException("REPORT_OWNER_SCALAR");
        return result.cells().isEmpty()?null:result.cells().getFirst().value();
    }
}
