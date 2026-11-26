package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.intelligence.HistoricalCoverageService.*;
import static kz.rm.intelligence.BookedSourceReconciliationService.*;

class BookedMonthlyAuthorityQualificationTest {
    private static final UUID T=new UUID(0,1),A=new UUID(0,2);
    private static final YearMonth M=YearMonth.of(2026,10);
    private static final Instant F=Instant.parse("2026-10-01T00:00:00Z"),U=Instant.parse("2026-11-01T00:00:00Z"),NOW=U.plusSeconds(1);
    private static final Manifest MANIFEST=new Manifest(T,A,1,"Etc/UTC",7,NOW,F.minusSeconds(1));
    private static final RequiredMonth MONTH=new RequiredMonth(M,Role.TARGET);
    private static final String D="a".repeat(64);
    private static HistoricalCoverageService.Assessment history(long n){
        return HistoricalCoverageService.assess(MANIFEST,List.of(MONTH),Map.of(M,new SourceCounts(n,n,n,0,0,0)),Map.of());
    }
    private static BookedMonthlyAuthorityAssessment.Slice slice(int id,Instant from,Instant until,long n,
            BookedOwnerDeclarationService.State state,BookedHistoryComposition.Mode mode){
        var e=classify(T,A,new UUID(0,id),from,until,7,n,NOW,new Counts(n,n,0,0,0,0,0,0,n,0),new Digest(n,0,0,false,D));
        var i=new BookedOwnerDeclarationService.Inspection(T,A,new UUID(1,id),e.runId(),mode,state,7,n,NOW,Eligibility.BLOCKED);
        return new BookedMonthlyAuthorityAssessment.Slice(e,i);
    }
    private static BookedMonthlyAuthorityAssessment.Slice matched(int id,Instant from,Instant until,long n){
        return slice(id,from,until,n,BookedOwnerDeclarationService.State.MATCHED_CONTRACT_NOT_ADMITTED,
            BookedHistoryComposition.Mode.NATIVE_HISTORY);
    }
    @Test void exactPositiveAndZeroRemainUnqualified(){
        for(long n:List.of(0L,2L)){
            var a=BookedMonthlyAuthorityAssessment.assess(history(n),Map.of(M,List.of(matched(1,F,U,n))));
            assertThat(a.months().getFirst().state()).isEqualTo(BookedMonthlyAuthorityAssessment.State.SOURCE_AUTHORITY_REQUIRED);
            assertThat(a.history().qualified()).isZero();assertThat(a.admitted()).isFalse();
            assertThat(a.history().intervals().getFirst().quality()).isEqualTo(n==0?DataQuality.MISSING:DataQuality.PARTIAL);
            if(n==0)assertThat(a.history().intervals().getFirst().value()).isNull();
        }
    }
    @Test void adjacentPartitionMustAgreeWithIndependentMonthlyOracle(){
        var slices=List.of(matched(1,F,F.plusSeconds(10),1),matched(2,F.plusSeconds(10),U,2));
        assertThat(BookedMonthlyAuthorityAssessment.assess(history(3),Map.of(M,slices)).months().getFirst().state())
            .isEqualTo(BookedMonthlyAuthorityAssessment.State.SOURCE_AUTHORITY_REQUIRED);
        assertThat(BookedMonthlyAuthorityAssessment.assess(history(4),Map.of(M,slices)).months().getFirst().state())
            .isEqualTo(BookedMonthlyAuthorityAssessment.State.MONTHLY_POPULATION_MISMATCH);
    }
    @Test void gapRevocationAndMixedModeAreExplicit(){
        var gap=BookedMonthlyAuthorityAssessment.assess(history(2),Map.of(M,List.of(matched(1,F,U.minusSeconds(1),2)))).months().getFirst();
        assertThat(gap.state()).isEqualTo(BookedMonthlyAuthorityAssessment.State.INVALID_PARTITION);
        assertThat(gap.gaps()).containsExactly(new BookedHistoryComposition.Interval(U.minusSeconds(1),U));
        var revoked=slice(1,F,U,2,BookedOwnerDeclarationService.State.REVOKED,BookedHistoryComposition.Mode.NATIVE_HISTORY);
        assertThat(BookedMonthlyAuthorityAssessment.assess(history(2),Map.of(M,List.of(revoked))).months().getFirst().state())
            .isEqualTo(BookedMonthlyAuthorityAssessment.State.DECLARATION_NOT_CURRENT);
        var imported=slice(2,F.plusSeconds(10),U,1,BookedOwnerDeclarationService.State.MATCHED_CONTRACT_NOT_ADMITTED,BookedHistoryComposition.Mode.IMPORTED_HISTORY);
        assertThat(BookedMonthlyAuthorityAssessment.assess(history(2),Map.of(M,List.of(matched(1,F,F.plusSeconds(10),1),imported))).months().getFirst().state())
            .isEqualTo(BookedMonthlyAuthorityAssessment.State.MIXED_HISTORY_MODE);
    }
    @Test void proofBudgetAndExactRequiredSetAreEnforced(){
        UUID id=new UUID(1,1);
        assertThatThrownBy(()->BookedMonthlyAuthorityAssessment.request(List.of(MONTH),Map.of(M.minusMonths(1),List.of(id))))
            .hasMessage("S1_MONTHLY_UNREQUESTED_PROOF");
        assertThatThrownBy(()->BookedMonthlyAuthorityAssessment.request(List.of(MONTH),Map.of(M,List.of(id,id))))
            .hasMessage("S1_MONTHLY_PROOF_BUDGET");
        assertThatThrownBy(()->BookedMonthlyAuthorityAssessment.request(List.of(MONTH),Map.of(M,
            java.util.stream.IntStream.range(0,5).mapToObj(i->new UUID(1,i)).toList()))).hasMessage("S1_MONTHLY_PROOF_BUDGET");
    }
}
