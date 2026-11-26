package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.intelligence.BookedSourceReconciliationService.*;

class NativeBookedClosureQualificationTest {
    private static final UUID T=new UUID(0,1),A=new UUID(0,2),R=new UUID(0,3);
    private static final Instant F=Instant.parse("2026-10-01T00:00:00Z"),U=Instant.parse("2026-11-01T00:00:00Z"),NOW=U.plusSeconds(1);
    private static final BookedHistoryComposition.Snapshot S=new BookedHistoryComposition.Snapshot(T,A,F.minusSeconds(1),7,NOW);
    private static final BookedHistoryComposition.Interval I=new BookedHistoryComposition.Interval(F,U);
    private static final NativeBookedCaptureService.Boundary B=new NativeBookedCaptureService.Boundary(T,F,1);
    private static Evidence evidence(long n){return classify(T,A,R,F,U,7,n,NOW,
        new Counts(n,n,0,0,0,0,0,0,n,0),new Digest(n,0,0,false,"a".repeat(64)));}
    private static NativeBookedClosure.Result assess(NativeBookedCaptureService.Boundary b,boolean guards,long n,long headers,
                                                      long invalid,long monthCount){
        return NativeBookedClosure.assess(S,b,I,1L,YearMonth.of(2026,10),guards,
            new NativeBookedClosure.HeaderCounts(headers,invalid),List.of(evidence(n)),
            new HistoricalCoverageService.SourceCounts(monthCount,monthCount,monthCount,0,0,0));
    }
    @Test void closedMonthPositiveAndZeroAreDistinctShadowResults(){
        for(long n:List.of(0L,2L)){
            var result=assess(B,true,n,n,0,n);
            assertThat(result.state()).isEqualTo(n==0?NativeBookedClosure.State.SHADOW_QUALIFIED_ZERO:NativeBookedClosure.State.SHADOW_QUALIFIED_VALUE);
            assertThat(result.shadowValue()).isEqualTo(n);assertThat(result.admitted()).isFalse();
            assertThat(result.eligibility()).isEqualTo(HistoricalCoverageService.Eligibility.BLOCKED);
        }
    }
    @Test void missingOrLateBoundaryAndDisabledGuardsDoNotQualify(){
        assertThat(assess(null,true,0,0,0,0).state()).isEqualTo(NativeBookedClosure.State.CAPTURE_NOT_ACTIVE);
        assertThat(assess(new NativeBookedCaptureService.Boundary(T,F.plusSeconds(1),1),true,0,0,0,0).state())
            .isEqualTo(NativeBookedClosure.State.BEFORE_CAPTURE);
        assertThat(assess(B,false,0,0,0,0).state()).isEqualTo(NativeBookedClosure.State.GUARDS_NOT_ACTIVE);
    }
    @Test void headerAndMonthlyOraclesMustAgree(){
        assertThat(assess(B,true,2,3,1,2).state()).isEqualTo(NativeBookedClosure.State.HEADER_POPULATION_MISMATCH);
        assertThat(assess(B,true,2,2,0,3).state()).isEqualTo(NativeBookedClosure.State.MONTHLY_POPULATION_MISMATCH);
        assertThat(assess(B,true,2,2,0,3).shadowValue()).isNull();
    }
}
