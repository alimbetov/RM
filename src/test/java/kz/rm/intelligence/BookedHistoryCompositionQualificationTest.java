package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.intelligence.BookedHistoryComposition.*;
import static kz.rm.intelligence.BookedSourceReconciliationService.*;

class BookedHistoryCompositionQualificationTest {
    private static final UUID T=new UUID(0,1),A=new UUID(0,2);
    private static final Instant START=Instant.parse("2020-01-01T00:00:00Z"),END=START.plusSeconds(30),NOW=END.plusSeconds(100);
    private static final Snapshot S=new Snapshot(T,A,START.minusSeconds(1),7,NOW);
    private static final Interval REQUIRED=new Interval(START,END);
    private Evidence e(int id,int from,int until,long count){
        return classify(T,A,new UUID(0,id),START.plusSeconds(from),START.plusSeconds(until),7,count,NOW,
            new Counts(count,count,0,0,0,0,0,0,count,0),new Digest(count,0,0,false,"a".repeat(64)));
    }
    @Test void adjacentHalfOpenIntervalsComposeWithoutDoubleCounting(){
        var a=assess(S,Mode.NATIVE_HISTORY,REQUIRED,List.of(e(2,10,30,3),e(1,0,10,2)));
        assertThat(a.exactReconciledPartition()).isTrue();assertThat(a.reconciledObservedOrders()).isEqualTo(5L);
        assertThat(a.gaps()).isEmpty();assertThat(a.ownerCompletenessProven()).isFalse();
        assertThat(a.eligibility()).isEqualTo(HistoricalCoverageService.Eligibility.BLOCKED);
    }
    @Test void gapsAndOverlapsNeverProduceTotals(){
        var gap=assess(S,Mode.NATIVE_HISTORY,REQUIRED,List.of(e(1,0,10,2),e(2,11,30,3)));
        assertThat(gap.gaps()).containsExactly(new Interval(START.plusSeconds(10),START.plusSeconds(11)));
        assertThat(gap.issues()).contains(Issue.GAP);assertThat(gap.reconciledObservedOrders()).isNull();
        var overlap=assess(S,Mode.NATIVE_HISTORY,REQUIRED,List.of(e(1,0,20,2),e(2,10,30,3)));
        assertThat(overlap.gaps()).isEmpty();assertThat(overlap.issues()).containsExactly(Issue.OVERLAP);
        assertThat(overlap.reconciledObservedOrders()).isNull();
    }
    @Test void outOfRangeAndEmptyHistoryAreExplicit(){
        var outside=assess(S,Mode.NATIVE_HISTORY,REQUIRED,List.of(e(1,-1,30,2)));
        assertThat(outside.issues()).contains(Issue.OUTSIDE_REQUEST);assertThat(outside.reconciledObservedOrders()).isNull();
        var empty=assess(S,Mode.NATIVE_HISTORY,REQUIRED,List.of());
        assertThat(empty.gaps()).containsExactly(REQUIRED);
        var zero=assess(S,Mode.NATIVE_HISTORY,REQUIRED,List.of(e(1,0,30,0)));
        assertThat(zero.exactReconciledPartition()).isTrue();assertThat(zero.reconciledObservedOrders()).isNull();
    }
    @Test void nativeAndImportedBoundariesAreDifferentButNeitherAdmitted(){
        var laterTenant=new Snapshot(T,A,START.plusSeconds(1),7,NOW);
        var nativeResult=assess(laterTenant,Mode.NATIVE_HISTORY,REQUIRED,List.of(e(1,0,30,2)));
        assertThat(nativeResult.boundary()).isEqualTo(Boundary.NATIVE_BEFORE_TENANT_CREATION);
        assertThat(nativeResult.reconciledObservedOrders()).isNull();
        var imported=assess(laterTenant,Mode.IMPORTED_HISTORY,REQUIRED,List.of(e(1,0,30,2)));
        assertThat(imported.boundary()).isEqualTo(Boundary.IMPORTED_PRE_TENANT_AUTHORITY_REQUIRED);
        assertThat(imported.reconciledObservedOrders()).isEqualTo(2L);assertThat(imported.ownerCompletenessProven()).isFalse();
    }
    @Test void crossSnapshotAndDuplicateRunCannotCompose(){
        var wrong=new Snapshot(new UUID(0,99),A,S.tenantCreatedAt(),7,NOW);
        var stale=new Snapshot(T,A,S.tenantCreatedAt(),8,NOW);
        var later=new Snapshot(T,A,S.tenantCreatedAt(),7,NOW.plusSeconds(1));
        for(var x:List.of(wrong,stale,later))
            assertThatThrownBy(()->assess(x,Mode.NATIVE_HISTORY,REQUIRED,List.of(e(1,0,30,2))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("S1_COMPOSITION_SNAPSHOT");
        assertThatThrownBy(()->assess(S,Mode.NATIVE_HISTORY,REQUIRED,List.of(e(1,0,10,2),e(1,10,30,3))))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("S1_COMPOSITION_RUNS");
    }
    @Test void openOrUnreconciledIntervalsNeverYieldTotals(){
        var openTime=END.minusSeconds(1);
        var open=classify(T,A,new UUID(0,1),START,END,7,2,openTime,
            new Counts(2,2,0,0,0,0,0,0,2,0),new Digest(2,0,0,false,"a".repeat(64)));
        var result=assess(new Snapshot(T,A,S.tenantCreatedAt(),7,openTime),Mode.NATIVE_HISTORY,REQUIRED,List.of(open));
        assertThat(result.boundary()).isEqualTo(Boundary.OPEN_INTERVAL);
        assertThat(result.issues()).contains(Issue.UNRECONCILED_SLICE);
        assertThat(result.reconciledObservedOrders()).isNull();
    }
}
