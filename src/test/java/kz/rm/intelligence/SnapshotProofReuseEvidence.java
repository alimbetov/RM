package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Uses real PostgreSQL coverage; counters belong only to this test fixture. */
public final class SnapshotProofReuseEvidence {
    private static final class CountingJdbc extends JdbcTemplate {
        int digestScans;
        CountingJdbc(JdbcTemplate jdbc){super(Objects.requireNonNull(jdbc.getDataSource()));}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){
            if(sql.contains("FROM crm.int_canonical_fact WHERE tenant_id=?"))digestScans++;
            return super.query(sql,mapper,args);
        }
    }
    private static final class CountingCoverage extends MultidimensionalCoverageService {
        final List<UUID> proofs=new ArrayList<>();
        final List<UUID> generations=new ArrayList<>();
        final List<Status> statuses=new ArrayList<>();
        CountingCoverage(JdbcTemplate jdbc,PlatformTransactionManager manager){super(jdbc,manager);}
        @Override Status statusInSnapshot(UUID tenant,UUID generation,UUID proof){
            var status=super.statusInSnapshot(tenant,generation,proof);
            proofs.add(proof);generations.add(generation);statuses.add(status);
            return status;
        }
        void reset(){proofs.clear();generations.clear();statuses.clear();}
    }
    private final CountingJdbc counter;
    private final CountingCoverage coverage;
    private final AuthorizedAnalyticalReadService reader;
    public SnapshotProofReuseEvidence(ApplicationContext context,JdbcTemplate jdbc,PlatformTransactionManager manager){
        counter=new CountingJdbc(jdbc);
        coverage=new CountingCoverage(counter,manager);
        reader=new AuthorizedAnalyticalReadService(jdbc,manager,context.getBean(L3DerivedSeriesService.class),coverage);
    }
    private static AuthorizedAnalyticalReadService.Query query(AuthorizedAnalyticalReadService.Query q,
            AuthorizedAnalyticalReadService.Metric metric,LocalDate date){
        return new AuthorizedAnalyticalReadService.Query(metric,q.calendarVersion(),q.period(),date,
            q.region(),q.group(),q.sku(),q.warehouses(),q.currency());
    }
    public void verifyReportDenied(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query q){
        var month=new AuthorizedAnalyticalReadService.Query(q.metric(),q.calendarVersion(),PeriodResolver.Code.M,
            q.anchor().withDayOfMonth(1).minusMonths(1),q.region(),q.group(),q.sku(),q.warehouses(),q.currency());
        assertThatThrownBy(()->reader.readDiagnosticMonths(actor,tenant,List.of(month))).isInstanceOf(SecurityException.class);
        var dates=java.util.stream.IntStream.range(0,24).mapToObj(i->month.anchor().minusMonths(i)).toList();
        assertThatThrownBy(()->reader.readMetricWindows(actor,tenant,List.of(month),dates))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("ANALYTICAL_WINDOW_SIZE");
    }
    private void reset(){counter.digestScans=0;coverage.reset();}
    // The first fixture day is definitely closed and inside the generation.
    // The second day can be OPEN; its existing route/quality is compared as-is.
    public void verify(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query q,UUID generation,UUID proof,
            MultidimensionalCoverageService.Status expected){
        var metrics=List.of(q,query(q,AuthorizedAnalyticalReadService.Metric.STOCK_OUT,q.anchor()));
        var dates=List.of(q.anchor(),q.anchor().plusDays(1));
        reset();
        var singles=metrics.stream().map(m->dates.stream()
            .map(d->reader.read(actor,tenant,query(m,m.metric(),d))).toList()).toList();
        int individualChecks=coverage.proofs.size(),individualScans=counter.digestScans;
        assertThat(individualChecks).isGreaterThanOrEqualTo(2);
        reset();
        var window=reader.readMetricWindows(actor,tenant,metrics,dates);
        assertThat(coverage.proofs).containsExactly(proof);
        assertThat(coverage.generations).containsExactly(generation);
        assertThat(coverage.statuses).containsExactly(expected);
        assertThat(counter.digestScans).isEqualTo(expected==MultidimensionalCoverageService.Status.CURRENT?1:0);
        if(expected==MultidimensionalCoverageService.Status.CURRENT)
            assertThat(individualScans).isEqualTo(individualChecks);
        else assertThat(individualScans).isZero();
        for(int i=0;i<metrics.size();i++){
            assertThat(window.get(i)).hasSize(dates.size());
            var first=window.get(i).getFirst();
            assertThat(first.route()).isEqualTo(expected==MultidimensionalCoverageService.Status.CURRENT
                ?AuthorizedAnalyticalReadService.Route.L3:AuthorizedAnalyticalReadService.Route.L0);
            for(int j=0;j<dates.size();j++)
                assertThat(window.get(i).get(j)).usingRecursiveComparison().ignoringFields("observedAt")
                    .isEqualTo(singles.get(i).get(j));
        }
        assertThat(window.stream().flatMap(List::stream).map(AuthorizedAnalyticalReadService.Result::observedAt).distinct().count()).isEqualTo(1);
        System.out.printf("COR01_PROOF_REUSE_PASS status=%s separate_checks=%d window_checks=%d separate_digest_scans=%d window_digest_scans=%d%n",
            expected,individualChecks,coverage.proofs.size(),individualScans,counter.digestScans);
    }
}
