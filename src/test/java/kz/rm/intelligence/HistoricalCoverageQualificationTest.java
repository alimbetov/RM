package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.intelligence.HistoricalCoverageService.*;

class HistoricalCoverageQualificationTest {
    private static final UUID TENANT=new UUID(0,1),ACTOR=new UUID(0,2);
    private static final ZoneId ZONE=ZoneId.of("Asia/Almaty");
    private static final Instant AS_OF=ZonedDateTime.of(2026,11,26,12,0,0,0,ZONE).toInstant();
    private static final YearMonth LAST=YearMonth.of(2026,10);
    private static Manifest manifest(int history){
        return new Manifest(TENANT,ACTOR,1,ZONE.getId(),7,AS_OF,
            LAST.minusMonths(history-1L).atDay(1).atStartOfDay(ZONE).toInstant());
    }
    private static PeriodResolver.Period interval(Manifest m,YearMonth month){
        return PeriodResolver.resolve(PeriodResolver.Code.M,month.atDay(1),new PeriodResolver.Policy(ZONE,m.calendarVersion()));
    }
    private static OwnerProof proof(Manifest m,YearMonth month,long count){
        var p=interval(m,month);return new OwnerProof(m,p,m.tenantCreatedAt(),AS_OF,"SYNTHETIC_OWNER_AUTHORITY",count,count==0);
    }
    private static List<RequiredMonth> months(int n){
        return java.util.stream.IntStream.range(0,n).mapToObj(i->new RequiredMonth(LAST.minusMonths(i),i<12?Role.TARGET:Role.BASELINE)).toList();
    }
    private static Assessment synthetic(int history,List<RequiredMonth> req){
        var m=manifest(history);var c=new HashMap<YearMonth,SourceCounts>();var p=new HashMap<YearMonth,OwnerProof>();
        for(var r:req){
            if(r.month().isBefore(LAST.minusMonths(history-1L)))c.put(r.month(),new SourceCounts(0,0,0,0,0,0));
            else{c.put(r.month(),new SourceCounts(3,3,3,0,0,0));p.put(r.month(),proof(m,r.month(),3));}
        }
        return assess(m,req,c,p);
    }
    @Test void fourHistoricalProfilesHaveExactRequiredCoverage(){
        for(int history:List.of(25,15,10,6)){
            var full=synthetic(history,months(24));
            assertThat(full.required()).isEqualTo(24);assertThat(full.qualified()).isEqualTo(Math.min(24,history));
            assertThat(full.allRequiredQualified()).isEqualTo(history>=24);
            assertThat(full.intervals().getFirst().requirement().month()).isEqualTo(LAST.minusMonths(23));
            assertThat(synthetic(history,months(2)).allRequiredQualified()).isTrue();
            assertThat(synthetic(history,months(12)).allRequiredQualified()).isEqualTo(history>=12);
            var sparse=List.of(new RequiredMonth(LAST,Role.TARGET),new RequiredMonth(LAST.minusYears(1),Role.BASELINE));
            assertThat(synthetic(history,sparse).allRequiredQualified()).isEqualTo(history>=13);
            assertThat(full.decisionEligibility()).isEqualTo(Eligibility.BLOCKED);
        }
    }
    @Test void emptySourceIsNotZeroWithoutOwnerAuthority(){
        var m=manifest(25);var req=List.of(new RequiredMonth(LAST,Role.TARGET));
        var counts=Map.of(LAST,new SourceCounts(0,0,0,0,0,0));
        var missing=assess(m,req,counts,Map.of());
        assertThat(missing.intervals().getFirst().value()).isNull();
        assertThat(missing.intervals().getFirst().quality()).isEqualTo(DataQuality.MISSING);
        assertThat(missing.qualified()).isZero();
        var zero=assess(m,req,counts,Map.of(LAST,proof(m,LAST,0)));
        assertThat(zero.intervals().getFirst().value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.intervals().getFirst().reasons()).containsExactly(Reason.QUALIFIED_ZERO);
        assertThat(zero.allRequiredQualified()).isTrue();
    }
    @Test void sourceParityAloneCannotPromoteReady(){
        var m=manifest(25);var req=List.of(new RequiredMonth(LAST,Role.TARGET));
        var counts=Map.of(LAST,new SourceCounts(3,3,3,0,0,0));
        var result=assess(m,req,counts,Map.of());
        assertThat(result.intervals().getFirst().quality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(result.intervals().getFirst().value()).isEqualByComparingTo("3");
        assertThat(result.qualified()).isZero();
        var mismatch=assess(m,req,Map.of(LAST,new SourceCounts(3,2,3,0,1,0)),Map.of(LAST,proof(m,LAST,3)));
        assertThat(mismatch.allRequiredQualified()).isFalse();
        assertThat(mismatch.intervals().getFirst().value()).isNull();
    }
    @Test void lateRevisionAndForeignProofAreNeverReusable(){
        var m=manifest(25);var req=List.of(new RequiredMonth(LAST,Role.TARGET));
        var counts=Map.of(LAST,new SourceCounts(3,3,3,0,0,0));
        var later=new Manifest(m.tenant(),m.actor(),1,m.zoneId(),8,m.asOf().plusSeconds(1),m.tenantCreatedAt());
        var foreign=new Manifest(new UUID(0,99),m.actor(),1,m.zoneId(),7,m.asOf(),m.tenantCreatedAt());
        for(var x:List.of(later,foreign)){
            var a=assess(x,req,counts,Map.of(LAST,proof(m,LAST,3)));
            assertThat(a.intervals().getFirst().quality()).isEqualTo(DataQuality.STALE);
            assertThat(a.intervals().getFirst().value()).isNull();
        }
    }
    @Test void requiredGapAndOptionalMonthAreDistinct(){
        var m=manifest(25);var prior=LAST.minusYears(1);
        var req=List.of(new RequiredMonth(LAST,Role.TARGET),new RequiredMonth(prior,Role.BASELINE));
        var counts=Map.of(LAST,new SourceCounts(3,3,3,0,0,0),prior,new SourceCounts(3,3,3,0,0,0));
        assertThat(assess(m,req,counts,Map.of(LAST,proof(m,LAST,3))).coverageRatio()).isEqualByComparingTo("0.5");
        assertThat(assess(m,req,counts,Map.of(LAST,proof(m,LAST,3),prior,proof(m,prior,3))).allRequiredQualified()).isTrue();
        assertThat(assess(m,List.of(),Map.of(),Map.of()).coverageRatio()).isNull();
        assertThatThrownBy(()->assess(m,req,Map.of(LAST,counts.get(LAST)),Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void boundaryAndOpenPeriodRemainUnqualified(){
        var m=manifest(6);var before=LAST.minusMonths(6);var open=LAST.plusMonths(1);
        var req=List.of(new RequiredMonth(before,Role.BASELINE),new RequiredMonth(open,Role.TARGET));
        var c=new SourceCounts(0,0,0,0,0,0);
        var a=assess(m,req,Map.of(before,c,open,c),Map.of());
        assertThat(a.intervals().getFirst().reasons()).contains(Reason.PRE_ONBOARDING_OR_BOUNDARY);
        assertThat(a.intervals().getLast().reasons()).contains(Reason.OPEN_PERIOD);
        assertThat(a.intervals()).allSatisfy(e->assertThat(e.value()).isNull());
    }
}
