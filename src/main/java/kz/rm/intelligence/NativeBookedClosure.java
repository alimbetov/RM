package kz.rm.intelligence;

import java.time.*;
import java.util.*;

/** Shadow native closure, deliberately not an admitted OwnerProof or L3 READY. */
public final class NativeBookedClosure {
    private NativeBookedClosure(){}
    public enum State { CAPTURE_NOT_ACTIVE,BEFORE_CAPTURE,OPEN_RANGE,GUARDS_NOT_ACTIVE,
        UNRECONCILED_RANGE,HEADER_POPULATION_MISMATCH,MONTHLY_POPULATION_MISMATCH,
        SHADOW_QUALIFIED_VALUE,SHADOW_QUALIFIED_ZERO }
    public record HeaderCounts(long headers,long invalid){
        public HeaderCounts {if(headers<0||invalid<0||invalid>headers)throw new IllegalArgumentException("S1_NATIVE_HEADER_COUNTS");}
    }
    public record Result(BookedHistoryComposition.Snapshot snapshot,NativeBookedCaptureService.Boundary boundary,
                         BookedHistoryComposition.Interval interval,Long calendarVersion,YearMonth month,
                         State state,Long shadowValue,HeaderCounts headers,List<BookedSourceReconciliationService.Evidence> slices,
                         HistoricalCoverageService.Eligibility eligibility){
        public Result {slices=List.copyOf(slices);}
        public boolean admitted(){return false;}
    }
    static Result assess(BookedHistoryComposition.Snapshot snapshot,NativeBookedCaptureService.Boundary boundary,
                         BookedHistoryComposition.Interval interval,Long calendarVersion,YearMonth month,boolean guards,
                         HeaderCounts headers,List<BookedSourceReconciliationService.Evidence> slices,
                         HistoricalCoverageService.SourceCounts monthly){
        if(boundary!=null&&!boundary.tenant().equals(snapshot.tenant()))throw new IllegalArgumentException("S1_NATIVE_SCOPE");
        var partition=BookedHistoryComposition.assess(snapshot,BookedHistoryComposition.Mode.NATIVE_HISTORY,interval,slices);
        State state;Long value=null;
        if(boundary==null||boundary.startedAt()==null||boundary.policyVersion()!=1)state=State.CAPTURE_NOT_ACTIVE;
        else if(interval.from().isBefore(boundary.startedAt())||interval.from().isBefore(snapshot.tenantCreatedAt()))state=State.BEFORE_CAPTURE;
        else if(interval.until().isAfter(snapshot.asOf()))state=State.OPEN_RANGE;
        else if(!guards)state=State.GUARDS_NOT_ACTIVE;
        else if(!partition.exactReconciledPartition())state=State.UNRECONCILED_RANGE;
        else {
            long n=0;
            for(var e:slices)n=Math.addExact(n,e.counts().distinctValidOrders());
            if(headers.invalid()!=0||headers.headers()!=n)state=State.HEADER_POPULATION_MISMATCH;
            else if(monthly!=null&&(!monthly.identityParity()||monthly.invalidOwnerEvents()!=0||
                monthly.ownerEvents()!=monthly.distinctValidOrders()||monthly.distinctValidOrders()!=n))
                state=State.MONTHLY_POPULATION_MISMATCH;
            else {value=n;state=n==0?State.SHADOW_QUALIFIED_ZERO:State.SHADOW_QUALIFIED_VALUE;}
        }
        return new Result(snapshot,boundary,interval,calendarVersion,month,state,value,headers,slices,HistoricalCoverageService.Eligibility.BLOCKED);
    }
}
