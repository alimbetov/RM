package kz.rm.intelligence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import static kz.rm.intelligence.AuthorizedAnalyticalReadService.*;

/** Internal, bounded source evidence candidate. Not a coverage certificate or publication permit. */
final class ReportSourceManifest {
    private final UUID tenant;
    private final String scopeDigest, sha256;
    private final Instant observedAt;
    private final long sourceRevision;
    private final byte[] scopeBytes,sourceBytes;
    private final List<Query> queries;
    private final List<List<Result>> windows;
    private ReportSourceManifest(UUID tenant,String scopeDigest,String sha256,Instant observedAt,
                                 long sourceRevision,List<Query> queries,List<List<Result>> windows,byte[] scopeBytes,byte[] sourceBytes){
        this.scopeBytes=scopeBytes.clone();this.sourceBytes=sourceBytes.clone();
        this.tenant=tenant;this.scopeDigest=scopeDigest;this.sha256=sha256;
        this.observedAt=observedAt;this.sourceRevision=sourceRevision;
        this.queries=List.copyOf(queries);
        this.windows=windows.stream().map(List::copyOf).toList();
    }
    byte[] scopeBytes(){return scopeBytes.clone();}
    byte[] sourceBytes(){return sourceBytes.clone();}
    UUID tenant(){return tenant;}
    String scopeDigest(){return scopeDigest;}
    String sha256(){return sha256;}
    Instant observedAt(){return observedAt;}
    long sourceRevision(){return sourceRevision;}
    List<Query> queries(){return queries;}
    List<List<Result>> windows(){return windows;}
    static ReportSourceManifest from(UUID tenant,List<Query> queries,List<List<Result>> windows){
        Objects.requireNonNull(tenant);
        if(queries.isEmpty()||queries.size()>3||queries.size()!=windows.size())
            throw new IllegalArgumentException("REPORT_MANIFEST_SHAPE");
        var inputQueries=List.copyOf(queries);
        var inputWindows=windows.stream().map(List::copyOf).toList();
        var order=java.util.stream.IntStream.range(0,inputQueries.size()).boxed()
            .sorted(Comparator.comparing(i->inputQueries.get(i).metric().name())).toList();
        queries=order.stream().map(inputQueries::get).toList();
        windows=order.stream().map(inputWindows::get).toList();
        var q0=queries.getFirst();
        if(queries.stream().map(Query::metric).distinct().count()!=queries.size()
            ||q0.anchor().getDayOfMonth()!=1
            ||queries.stream().anyMatch(q->q.period()!=PeriodResolver.Code.M
                ||q.calendarVersion()!=q0.calendarVersion()||!q.anchor().equals(q0.anchor())
                ||!Objects.equals(q.region(),q0.region())||!Objects.equals(q.group(),q0.group())
                ||!Objects.equals(q.sku(),q0.sku())||!q.warehouses().equals(q0.warehouses())))
            throw new IllegalArgumentException("REPORT_MANIFEST_SCOPE");
        var scope=scopeHash(tenant,queries);
        String scopeDigest=scope.finish();
        if(windows.getFirst().size()!=24)throw new IllegalArgumentException("REPORT_MANIFEST_PERIODS");
        var first=windows.getFirst().getFirst();
        Instant observed=Objects.requireNonNull(first.observedAt());
        long revision=first.sourceRevision();
        if(revision<0)throw new IllegalArgumentException("REPORT_MANIFEST_REVISION");
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
                String normalized=ReportDatasetDigest.decimal(x.value());
                if(normalized!=null&&x.unit().equals("COUNT")&&
                    (x.value().signum()<0||normalized.contains(".")))
                    throw new IllegalArgumentException("REPORT_MANIFEST_COUNT");
                String qualityReason=x.value()==null?"NO_QUALIFIED_OBSERVATION":"HISTORICAL_COVERAGE_UNQUALIFIED";
                if(!qualityReason.equals(x.qualityReason())||x.reason()==null||!x.reason().matches("[A-Z0-9_]{1,128}"))
                    throw new IllegalArgumentException("REPORT_MANIFEST_QUALITY_REASON");
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
                evidence.add(x.unit());evidence.add(x.currency());evidence.add(normalized);
                evidence.add(x.quality());evidence.add(x.qualityReason());evidence.add(x.route());evidence.add(x.reason());
                evidence.add(x.generation());evidence.add(x.proof());
            }
        }
        return new ReportSourceManifest(tenant,scopeDigest,evidence.finish(),observed,revision,queries,windows,scope.bytes(),evidence.bytes());
    }
    static String scopeDigest(UUID tenant,List<Query> queries){
        return scopeHash(tenant,List.copyOf(queries).stream().sorted(Comparator.comparing(q->q.metric().name())).toList()).finish();
    }
    private static FramedHash scopeHash(UUID tenant,List<Query> queries){
        var scope=new FramedHash("RM-ARS-SCOPE-1");
        scope.add(tenant);
        scope.add(queries.size());
        for(var q:queries){
            scope.add(q.metric());scope.add(q.calendarVersion());scope.add(q.period());scope.add(q.anchor());
            scope.add(q.region());scope.add(q.group());scope.add(q.sku());scope.add(q.currency());
            scope.add(q.warehouses().size());q.warehouses().forEach(scope::add);
        }
        return scope;
    }
    private static final class FramedHash{
        private final MessageDigest hash;
        private final java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();
        FramedHash(String version){
            try{hash=MessageDigest.getInstance("SHA-256");}
            catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            add(version);
        }
        void add(Object value){
            if(value==null){write(new byte[]{'-',':'});return;}
            byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
            write((bytes.length+":").getBytes(StandardCharsets.US_ASCII));write(bytes);
        }
        private void write(byte[] bytes){data.writeBytes(bytes);hash.update(bytes);}
        byte[] bytes(){return data.toByteArray();}
        String finish(){return HexFormat.of().formatHex(hash.digest());}
    }
}
