package kz.rm.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static kz.rm.intelligence.AuthorizedAnalyticalReadService.*;

/** R1.2A internal typed view of one already authorized accounting snapshot.
 * Not a READY-dataset reader, authorization boundary, historical coverage
 * certificate, renderer, or publication permit. Do not expose directly over HTTP.
 * Selection affects display only; comparisons use the original 24M series. */
final class ReportStatisticalView {
    private ReportStatisticalView() {}
    static final int PRESENTATION_VERSION=1;
    enum Range {
        M3(3), M6(6), M12(12), M24(24);
        final int months;
        Range(int months){this.months=months;}
    }
    enum Basis {
        MOM(1), YOY(12);
        final int lag;
        Basis(int lag){this.lag=lag;}
    }
    record PeriodComparison(Basis basis, PeriodResolver.Period prior,
                            PeriodResolver.Period current, DerivedMetricEngine.Comparison result) {}
    record Series(UUID tenant, String scopeDigest, String sourceDigest, Instant observedAt,
                  AccountingPolicy accountingPolicy, Query query, Range range, int presentationVersion,
                  String hintKey, List<Result> points, List<PeriodComparison> comparisons) {
        Series {
            points=List.copyOf(points);
            comparisons=List.copyOf(comparisons);
        }
    }
    static Series select(ReportSourceManifest manifest, Metric metric, Range range) {
        Objects.requireNonNull(manifest); Objects.requireNonNull(metric); Objects.requireNonNull(range);
        if(manifest.accountingPolicy()==null)
            throw new IllegalArgumentException("REPORT_ACCOUNTING_MANIFEST_REQUIRED");
        // Only the R02 pilot; new metrics need independent semantic and source admission.
        if(metric!=Metric.BOOKED_AMOUNT && metric!=Metric.BOOKED_ORDERS)
            throw new IllegalArgumentException("REPORT_STATISTICAL_METRIC_NOT_ADMITTED");
        for(int i=0;i<manifest.queries().size();i++){
            Query query=manifest.queries().get(i);
            if(query.metric()!=metric)continue;
            var full=manifest.windows().get(i);
            if(full.size()!=24)throw new IllegalArgumentException("REPORT_STATISTICAL_WINDOW");
            var current=full.getLast();
            var comparisons=java.util.Arrays.stream(Basis.values()).map(basis->{
                var prior=full.get(full.size()-1-basis.lag);
                // ReportSourceManifest has already checked all 24 period identities,
                // calendar, unit/currency, method, scope and source revision.
                return new PeriodComparison(basis,prior.period(),current.period(),
                    DerivedMetricEngine.comparePeriods(prior.value(),prior.quality(),current.value(),current.quality()));
            }).toList();
            return new Series(manifest.tenant(),manifest.scopeDigest(),manifest.sha256(),manifest.observedAt(),
                manifest.accountingPolicy(),query,range,PRESENTATION_VERSION,"metric."+metric.name()+".hint",
                full.subList(full.size()-range.months,full.size()),comparisons);
        }
        throw new IllegalArgumentException("REPORT_STATISTICAL_METRIC_NOT_FOUND");
    }
}
