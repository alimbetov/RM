package kz.rm.intelligence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import static kz.rm.intelligence.AuthorizedAnalyticalReadService.*;

/** Internal, bounded source evidence candidate. Not a coverage certificate or publication permit. */
record ReportSourceManifest(UUID tenant, String scopeDigest, String sha256, Instant observedAt,
                            long sourceRevision, List<Query> queries, List<List<Result>> windows) {
    ReportSourceManifest {
        queries=List.copyOf(queries);
        windows=windows.stream().map(List::copyOf).toList();
    }
    static ReportSourceManifest from(UUID tenant,List<Query> queries,List<List<Result>> windows){
        Objects.requireNonNull(tenant);
        if(queries.isEmpty()||queries.size()>3||queries.size()!=windows.size())
            throw new IllegalArgumentException("REPORT_MANIFEST_SHAPE");
        var scope=new FramedHash("RM-ARS-SCOPE-1");
        scope.add(tenant);
        scope.add(queries.size());
        for(var q:queries){
            scope.add(q.metric());scope.add(q.calendarVersion());scope.add(q.period());scope.add(q.anchor());
            scope.add(q.region());scope.add(q.group());scope.add(q.sku());scope.add(q.currency());
            scope.add(q.warehouses().size());q.warehouses().forEach(scope::add);
        }
        String scopeDigest=scope.finish();
        if(windows.getFirst().size()!=24)throw new IllegalArgumentException("REPORT_MANIFEST_PERIODS");
        var first=windows.getFirst().getFirst();
        Instant observed=Objects.requireNonNull(first.observedAt());
        long revision=first.sourceRevision();
        var evidence=new FramedHash("RM-ARS-SOURCE-1");
        evidence.add(scopeDigest);evidence.add(observed);evidence.add(revision);
        UUID generation=null,proof=null;
        for(int i=0;i<queries.size();i++){
            var q=queries.get(i);var points=windows.get(i);
            if(points.size()!=24)throw new IllegalArgumentException("REPORT_MANIFEST_PERIODS");
            Instant previous=null;
            for(int j=0;j<points.size();j++){
                var x=points.get(j);var period=x.period();
                var expected=PeriodResolver.resolve(PeriodResolver.Code.M,q.anchor().minusMonths(23-j),
                    new PeriodResolver.Policy(java.time.ZoneId.of(period.zoneId()),q.calendarVersion()));
                if(x.metric()!=q.metric()||x.methodVersion()!=1||!Objects.equals(x.currency(),q.currency())
                    ||!x.unit().equals(L3DerivedSeriesService.Metric.valueOf(q.metric().name()).unit)
                    ||!period.equals(expected)||!period.zoneId().equals(first.period().zoneId())
                    ||!observed.equals(x.observedAt())||revision!=x.sourceRevision()
                    ||period.endExclusive().isAfter(observed)||previous!=null&&!previous.equals(period.startInclusive())
                    ||x.quality()!=(x.value()==null?DataQuality.MISSING:DataQuality.PARTIAL))
                    throw new IllegalArgumentException("REPORT_MANIFEST_INCONSISTENT");
                if(x.route()==Route.L3){
                    if(x.generation()==null||x.proof()==null||generation!=null&&
                        (!generation.equals(x.generation())||!proof.equals(x.proof())))
                        throw new IllegalArgumentException("REPORT_MANIFEST_GENERATION");
                    generation=x.generation();proof=x.proof();
                }else if(x.route()!=Route.L0||x.generation()!=null||x.proof()!=null)
                    throw new IllegalArgumentException("REPORT_MANIFEST_ROUTE");
                previous=period.endExclusive();
                evidence.add(x.metric());evidence.add(x.methodVersion());evidence.add(period.startInclusive());
                evidence.add(period.endExclusive());evidence.add(period.zoneId());evidence.add(period.calendarVersion());
                evidence.add(x.unit());evidence.add(x.currency());evidence.add(ReportDatasetDigest.decimal(x.value()));
                evidence.add(x.quality());evidence.add(x.qualityReason());evidence.add(x.route());evidence.add(x.reason());
                evidence.add(x.generation());evidence.add(x.proof());
            }
        }
        return new ReportSourceManifest(tenant,scopeDigest,evidence.finish(),observed,revision,queries,windows);
    }
    private static final class FramedHash{
        private final MessageDigest hash;
        FramedHash(String version){
            try{hash=MessageDigest.getInstance("SHA-256");}
            catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            add(version);
        }
        void add(Object value){
            if(value==null){hash.update((byte)'-');hash.update((byte)':');return;}
            byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
            hash.update((bytes.length+":").getBytes(StandardCharsets.US_ASCII));hash.update(bytes);
        }
        String finish(){return HexFormat.of().formatHex(hash.digest());}
    }
}
