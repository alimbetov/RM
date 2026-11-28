package kz.rm.intelligence;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static kz.rm.intelligence.AuthorizedAnalyticalReadService.*;

/** Inverse of the immutable RM-ARS-SOURCE-3 framed evidence.
 * Only R02's bounded 48 cells are admitted. Re-encoding must match byte-for-byte;
 * never fetch live facts to reconstruct an already selected dataset. */
final class ReportSourceReplay {
    private ReportSourceReplay(){}
    static ReportSourceManifest restore(UUID tenant,List<Query> queries,AccountingPolicy policy,
        byte[] scope,byte[] source,SalesReportMaterializer.Receipt receipt){
        try{
            if(scope==null||scope.length<1||scope.length>65536||source==null||source.length<1||source.length>1048576)
                throw bad();
            var f=new Frames(source);
            if(!"RM-ARS-SOURCE-3".equals(f.next())||!receipt.scopeDigest().equals(f.next()))throw bad();
            Instant observed=Instant.parse(f.next());
            long revision=Long.parseLong(f.next());
            var snapshot=new ReportSourceManifest.SnapshotEvidence(Long.parseLong(f.next()),Long.parseLong(f.next()),
                uuid(f.next()),uuid(f.next()),date(f.next()),date(f.next()),number(f.next()),number(f.next()),
                number(f.next()),number(f.next()),f.next());
            var persisted=new AccountingPolicy(f.next(),f.next(),Long.parseLong(f.next()));
            if(!policy.equals(persisted)||!"ACCOUNTING_NATIVE_ONLY".equals(f.next()))throw bad();
            var ordered=queries.stream().sorted(Comparator.comparing(q->q.metric().name())).toList();
            if(ordered.size()!=2)throw bad();
            List<List<Result>> windows=new ArrayList<>();
            for(var q:ordered){
                List<Result> rows=new ArrayList<>();
                for(int i=0;i<24;i++){
                    var metric=Metric.valueOf(f.next());
                    int method=Integer.parseInt(f.next());
                    var period=new PeriodResolver.Period(Instant.parse(f.next()),Instant.parse(f.next()),f.next(),Long.parseLong(f.next()));
                    String unit=f.next(),currency=f.next(),value=f.next();
                    var quality=DataQuality.valueOf(f.next());String qualityReason=f.next();
                    var route=Route.valueOf(f.next());String reason=f.next();
                    rows.add(new Result(metric,method,period,value==null?null:new BigDecimal(value),quality,
                        route,reason,qualityReason,unit,currency,uuid(f.next()),uuid(f.next()),revision,observed));
                }
                windows.add(rows);
            }
            if(!f.done())throw bad();
            var restored=ReportSourceManifest.from(tenant,ordered,windows,snapshot,persisted);
            if(!tenant.equals(receipt.tenant())||!receipt.scopeDigest().equals(restored.scopeDigest())
                ||!receipt.sourceDigest().equals(restored.sha256())
                ||!Arrays.equals(scope,restored.scopeBytes())||!Arrays.equals(source,restored.sourceBytes())
                ||!receipt.expected().equals(receipt.observed())
                ||!receipt.observed().equals(SalesReportDatasetPlan.from(restored,receipt.dataset()).expected()))throw bad();
            return restored;
        }catch(RuntimeException ex){throw bad();}
    }
    private static IllegalStateException bad(){return new IllegalStateException("REPORT_SEALED_REPLAY_EVIDENCE");}
    private static UUID uuid(String s){return s==null?null:UUID.fromString(s);}
    private static LocalDate date(String s){return s==null?null:LocalDate.parse(s);}
    private static Long number(String s){return s==null?null:Long.valueOf(s);}
    private static final class Frames{
        private final byte[] bytes;private int p;
        Frames(byte[] bytes){this.bytes=bytes;}
        boolean done(){return p==bytes.length;}
        String next(){
            if(p+2>bytes.length)throw bad();
            if(bytes[p]=='-'&&bytes[p+1]==':'){p+=2;return null;}
            int start=p,n=0;
            while(p<bytes.length&&bytes[p]!=':'){
                int digit=bytes[p++]-'0';
                if(digit<0||digit>9||p-start>7)throw bad();
                n=Math.addExact(Math.multiplyExact(n,10),digit);
            }
            if(p==start||p>=bytes.length||(p-start>1&&bytes[start]=='0')||n>bytes.length-p-1)throw bad();
            p++;String s=new String(bytes,p,n,StandardCharsets.UTF_8);p+=n;return s;
        }
    }
}
