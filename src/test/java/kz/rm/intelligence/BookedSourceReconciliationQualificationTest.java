package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.intelligence.BookedSourceReconciliationService.*;

class BookedSourceReconciliationQualificationTest {
    private static final UUID TENANT=new UUID(0,1),ACTOR=new UUID(0,2),RUN=new UUID(0,3);
    private static final Instant FROM=Instant.parse("2026-10-01T00:00:00Z"),UNTIL=Instant.parse("2026-11-01T00:00:00Z");
    private static final Counts GOOD=new Counts(2,2,0,0,0,0,0,0,2,0);
    private static Digest digest(long checked,long mismatches,long legacy,boolean exhausted){
        return new Digest(checked,mismatches,legacy,exhausted,exhausted?null:"a".repeat(64));
    }
    private Evidence audit(Counts c,Digest d,Instant now){return classify(TENANT,ACTOR,RUN,FROM,UNTIL,7,c.owners()-c.unprocessed()+c.orphanReceipts(),now,c,d);}
    @Test void identityAndDigestParityDoNotProveCompleteness(){
        for(long n:List.of(0L,2L)){
            var result=audit(new Counts(n,n,0,0,0,0,0,0,n,0),digest(n,0,0,false),UNTIL);
            assertThat(result.state()).isEqualTo(State.RECONCILED_PRESENT_SOURCE);
            assertThat(result.ownerCompletenessProven()).isFalse();
            assertThat(result.eligibility()).isEqualTo(HistoricalCoverageService.Eligibility.BLOCKED);
            assertThat(result.limitations()).containsExactly(Limitation.OWNER_AUTHORITY_MISSING);
        }
    }
    @Test void missingReceiptAndOpenRangeDoNotReconcile(){
        var result=audit(new Counts(2,2,0,0,1,0,0,0,2,0),digest(2,0,0,false),UNTIL.minusSeconds(1));
        assertThat(result.state()).isEqualTo(State.INCOMPLETE);
        assertThat(result.limitations()).contains(Limitation.MISSING_RECEIPTS,Limitation.OPEN_RANGE);
    }
    @Test void digestBudgetAndLegacyAreExplicit(){
        assertThat(audit(GOOD,digest(0,0,0,true),UNTIL).state()).isEqualTo(State.BUDGET_EXHAUSTED);
        var old=audit(GOOD,digest(2,0,1,false),UNTIL);
        assertThat(old.state()).isEqualTo(State.INCOMPLETE);
        assertThat(old.limitations()).contains(Limitation.LEGACY_DIGEST);
    }
    @Test void identityReceiptAndDigestFailuresRemainConflict(){
        for(var c:List.of(new Counts(2,2,1,1,0,0,0,0,2,0),new Counts(2,2,0,0,0,1,0,0,2,0),
            new Counts(2,2,0,0,0,0,1,0,2,0),new Counts(2,2,0,0,0,0,0,1,2,0)))
            assertThat(audit(c,digest(2,0,0,false),UNTIL).state()).isEqualTo(State.CONFLICT);
        assertThat(audit(GOOD,digest(2,1,0,false),UNTIL).state()).isEqualTo(State.CONFLICT);
        assertThat(audit(GOOD,digest(1,0,0,false),UNTIL).state()).isEqualTo(State.CONFLICT);
    }
    @Test void matchingEventIdentitiesCannotHideInvalidOrderPopulation(){
        var result=audit(new Counts(2,2,0,0,0,0,0,0,0,2),digest(2,0,0,false),UNTIL);
        assertThat(result.state()).isEqualTo(State.CONFLICT);
        assertThat(result.limitations()).contains(Limitation.OWNER_POPULATION_INVALID);
        assertThat(result.ownerCompletenessProven()).isFalse();
        assertThatThrownBy(()->new Counts(2,2,0,0,0,0,0,0,2,1)).isInstanceOf(IllegalArgumentException.class);
    }
}
