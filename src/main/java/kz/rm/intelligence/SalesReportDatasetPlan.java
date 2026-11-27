package kz.rm.intelligence;

import java.util.*;
import static kz.rm.intelligence.AuthorizedAnalyticalReadService.*;

/** R02 booked-sales pilot only: one tenant, unfiltered scope, one currency.
 * No publication, historical qualification or fabricated dimension versions. */
final class SalesReportDatasetPlan {
    private final ReportDatasetDigest.Header header;
    private final List<ReportDatasetDigest.Row> rows;
    private final ReportDatasetDigest.Result expected;
    private SalesReportDatasetPlan(ReportDatasetDigest.Header header,List<ReportDatasetDigest.Row> rows,
                                   ReportDatasetDigest.Result expected){
        this.header=header;this.rows=List.copyOf(rows);this.expected=expected;
    }
    static SalesReportDatasetPlan from(ReportSourceManifest manifest,UUID dataset){
        Objects.requireNonNull(manifest);Objects.requireNonNull(dataset);
        // Only the admitted candidate BOOKED metrics. Fulfilled/warehouse scopes
        // need explicit historical dimension versions; never invent them.
        if(manifest.queries().size()!=2 || !manifest.queries().stream().map(Query::metric)
            .collect(java.util.stream.Collectors.toSet()).equals(Set.of(Metric.BOOKED_AMOUNT,Metric.BOOKED_ORDERS)))
            throw new IllegalArgumentException("R02_PILOT_METRICS");
        for(var q:manifest.queries())
            if(q.region()!=null||q.group()!=null||q.sku()!=null||!q.warehouses().isEmpty())
                throw new IllegalArgumentException("R02_PILOT_SCOPE");
        var header=new ReportDatasetDigest.Header(manifest.tenant(),dataset,manifest.scopeDigest(),manifest.sha256());
        var digest=new ReportDatasetDigest(header);
        var rows=new ArrayList<ReportDatasetDigest.Row>();
        long ordinal=0;
        for(var window:manifest.windows())for(var point:window){
            var row=new ReportDatasetDigest.Row(++ordinal,point.metric().name(),point.methodVersion(),
                point.period().startInclusive(),point.period().endExclusive(),List.of(),point.unit(),
                point.currency(),point.value(),point.quality(),point.qualityReason());
            digest.append(row);rows.add(row);
        }
        return new SalesReportDatasetPlan(header,rows,digest.finish());
    }
    ReportDatasetDigest.Header header(){return header;}
    List<ReportDatasetDigest.Row> rows(){return rows;}
    ReportDatasetDigest.Result expected(){return expected;}
}
