package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static kz.rm.intelligence.AuthorizedAnalyticalReadService.*;

/** P2 diagnostic integration of source authority and the existing R02 report.
 * No external proof input, new metric calculator, persisted format or READY upgrade.
 * All comparisons are revision-relative and only valid inside the observed snapshot. */
@Service
final class NativeBookedReportEvidenceService {
    enum State { SOURCE_NOT_QUALIFIED, ACCOUNTING_CURRENCY_MISMATCH, REPORT_VALUE_MISMATCH, SNAPSHOT_MATCHED }
    record AccountingPopulation(long orders,long foreignCurrency,BigDecimal amount){
        AccountingPopulation{
            if(orders<0||foreignCurrency<0||foreignCurrency>orders||amount==null||amount.signum()<0)
                throw new IllegalArgumentException("NATIVE_ACCOUNTING_POPULATION");
        }
    }
    static final class Diagnostic {
        private final ReportSourceManifest manifest;
        private final NativeBookedClosureService.MonthlyEvidence month;
        private final AccountingPopulation population;
        private final State state;
        private Diagnostic(ReportSourceManifest manifest,NativeBookedClosureService.MonthlyEvidence month,
                           AccountingPopulation population,State state){
            this.manifest=manifest;this.month=month;this.population=population;this.state=state;
        }
        ReportSourceManifest manifest(){return manifest;}
        NativeBookedClosureService.MonthlyEvidence month(){return month;}
        AccountingPopulation population(){return population;}
        State state(){return state;}
        boolean admitted(){return false;}
        String authorityReason(){return state==State.SNAPSHOT_MATCHED?"HISTORICAL_CONTINUITY_UNQUALIFIED":state.name();}
        ReportStatisticalView.Series series(Metric metric,ReportStatisticalView.Range range){
            // Shared mathematical kernel continues to enforce PARTIAL/MISSING.
            return ReportStatisticalView.select(manifest,metric,range);
        }
    }
    private final JdbcTemplate jdbc;
    private final AuthorizedAnalyticalReadService reader;
    private final NativeBookedClosureService closure;
    NativeBookedReportEvidenceService(JdbcTemplate jdbc,AuthorizedAnalyticalReadService reader,NativeBookedClosureService closure){
        this.jdbc=jdbc;this.reader=reader;this.closure=closure;
    }
    Diagnostic inspect(VerifiedActor actor,UUID tenant,long calendar,YearMonth last,YearMonth target){
        Objects.requireNonNull(last);Objects.requireNonNull(target);
        if(calendar<1||target.isAfter(last)||target.isBefore(last.minusMonths(23)))
            throw new IllegalArgumentException("NATIVE_REPORT_TARGET");
        return closure.inspectReportSnapshot(actor,tenant,(t,asOf)->{
            var manifest=reader.readNativeAccountingReportInSnapshot(t,calendar,last.atDay(1));
            var month=closure.monthInSnapshot(t,calendar,target,asOf);
            AccountingPopulation population=null;
            if(month.snapshotQualified()){
                var source=month.source();
                population=accountingPopulation(t,source.from(),source.until(),manifest.accountingPolicy().currency());
            }
            return bind(manifest,month,population);
        },result->reader.requireAccountingPolicy(tenant,result.manifest().accountingPolicy(),true));
    }
    /** Independent aggregate of already verified native headers. No FX conversion,
     * no per-line rounding; independent digest has proved header = exact line sum. */
    private AccountingPopulation accountingPopulation(UUID tenant,Instant from,Instant until,String currency){
        return Objects.requireNonNull(jdbc.queryForObject("""
            SELECT count(*),count(*) FILTER(WHERE currency IS DISTINCT FROM ?),
                   coalesce(sum(total_amount),0)
            FROM crm.sales_order WHERE tenant_id=? AND created_at>=? AND created_at<?
            """,(rs,n)->new AccountingPopulation(rs.getLong(1),rs.getLong(2),rs.getBigDecimal(3)),
            currency,tenant,Timestamp.from(from),Timestamp.from(until)));
    }
    static Diagnostic bind(ReportSourceManifest manifest,NativeBookedClosureService.MonthlyEvidence month,
                           AccountingPopulation population){
        Objects.requireNonNull(manifest);Objects.requireNonNull(month);
        var source=month.source();
        var binding=month.calendar();
        if(manifest.accountingPolicy()==null||!manifest.tenant().equals(binding.tenant())
            ||!manifest.observedAt().equals(source.observedAt())
            ||manifest.sourceRevision()!=Math.addExact(source.revision().baseline(),source.revision().events()))
            throw new IllegalArgumentException("NATIVE_REPORT_EVIDENCE_REVISION");
        if(manifest.queries().size()!=2||manifest.queries().stream().anyMatch(q->
            q.calendarVersion()!=binding.version()||q.period()!=PeriodResolver.Code.M||q.region()!=null||
            q.group()!=null||q.sku()!=null||!q.warehouses().isEmpty())
            ||!manifest.queries().stream().map(Query::metric).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of(Metric.BOOKED_AMOUNT,Metric.BOOKED_ORDERS)))
            throw new IllegalArgumentException("NATIVE_REPORT_EVIDENCE_SCOPE");
        var period=binding.period();
        var points=manifest.windows().stream().flatMap(List::stream).filter(p->p.period().equals(period)).toList();
        if(points.size()!=2)throw new IllegalArgumentException("NATIVE_REPORT_EVIDENCE_PERIOD");
        var count=points.stream().filter(p->p.metric()==Metric.BOOKED_ORDERS).findFirst().orElseThrow();
        var amount=points.stream().filter(p->p.metric()==Metric.BOOKED_AMOUNT).findFirst().orElseThrow();
        if(!"COUNT".equals(count.unit())||count.currency()!=null||!"CURRENCY".equals(amount.unit())
            ||!manifest.accountingPolicy().currency().equals(amount.currency())||
            count.methodVersion()!=1||amount.methodVersion()!=1)
            throw new IllegalArgumentException("NATIVE_REPORT_ACCOUNTING_SCOPE");
        if(!month.snapshotQualified()){
            if(population!=null)throw new IllegalArgumentException("NATIVE_REPORT_UNQUALIFIED_POPULATION");
            return new Diagnostic(manifest,month,null,State.SOURCE_NOT_QUALIFIED);
        }
        if(population==null||population.orders()!=month.snapshotValue())
            throw new IllegalArgumentException("NATIVE_REPORT_POPULATION");
        if(population.foreignCurrency()!=0)return new Diagnostic(manifest,month,population,State.ACCOUNTING_CURRENCY_MISMATCH);
        var state=matches(population,count.value(),amount.value())?State.SNAPSHOT_MATCHED:State.REPORT_VALUE_MISMATCH;
        return new Diagnostic(manifest,month,population,state);
    }
    /** Empty report cells may match an independently observed empty set without
     * converting the actual report's MISSING quality to numeric zero. */
    static boolean matches(AccountingPopulation population,BigDecimal count,BigDecimal amount){
        if(population.foreignCurrency()!=0)return false;
        if(population.orders()==0)return population.amount().signum()==0&&count==null&&amount==null;
        return count!=null&&count.compareTo(BigDecimal.valueOf(population.orders()))==0
            &&amount!=null&&amount.compareTo(population.amount())==0;
    }
}
