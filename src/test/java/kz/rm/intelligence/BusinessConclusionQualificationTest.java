package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static kz.rm.intelligence.BusinessConclusionService.*;
import static kz.rm.intelligence.AuthorizedAnalyticalReadService.Metric.*;

class BusinessConclusionQualificationTest {
    private static final UUID T=new UUID(0,1),A=new UUID(0,2),SKU=new UUID(0,3),W=new UUID(0,4);
    private static final Instant NOW=Instant.parse("2026-06-01T00:00:00Z");
    private static final LocalDate START=LocalDate.of(2026,1,1);
    private static final BigDecimal ABS=new BigDecimal("5"),REL=new BigDecimal("0.05");
    private final AuthorizedAnalyticalReadService reader=mock(AuthorizedAnalyticalReadService.class);
    private final VerifiedActor actor=mock(VerifiedActor.class);
    private final BusinessConclusionService service=new BusinessConclusionService(new BusinessObservationService(reader),new BusinessFactorService(reader));
    private AuthorizedAnalyticalReadService.Query query(PeriodResolver.Code code,LocalDate anchor){
        return new AuthorizedAnalyticalReadService.Query(FULFILLED_PRODUCT_UNITS,1,code,anchor,null,null,SKU,List.of(W),null);
    }
    private List<AuthorizedAnalyticalReadService.Result> points(AuthorizedAnalyticalReadService.Metric metric,
            PeriodResolver.Code code,List<LocalDate> dates,DataQuality quality,String... values){
        var out=new ArrayList<AuthorizedAnalyticalReadService.Result>();
        for(int i=0;i<dates.size();i++)
            out.add(new AuthorizedAnalyticalReadService.Result(metric,1,PeriodResolver.resolve(code,dates.get(i),
                new PeriodResolver.Policy(ZoneId.of("Etc/UTC"),1)),values[i]==null?null:new BigDecimal(values[i]),
                quality,AuthorizedAnalyticalReadService.Route.L0,"FIXTURE","FIXTURE",
                metric==FULFILLED_PRODUCT_UNITS?"PRODUCT_UNITS":"SKU_QUANTITY",null,null,null,7,NOW));
        return List.copyOf(out);
    }
    private List<LocalDate> days(int n){return java.util.stream.IntStream.range(0,n).mapToObj(START::plusDays).toList();}
    private Conclusion pattern(AuthorizedAnalyticalReadService.Query q,List<LocalDate> dates){
        when(actor.userId()).thenReturn(A);
        return service.concludePattern(actor,T,q,dates,1,ABS,REL,1,new BigDecimal("3"),new BigDecimal("0.5"));
    }
    private Finding find(Conclusion c,Lane lane){return c.findings().stream().filter(f->f.lane()==lane).findFirst().orElseThrow();}
    @Test void readyDeclineIsDescriptiveNotCausal(){
        var dates=days(4);var q=query(PeriodResolver.Code.D,dates.getLast());
        var raw=points(FULFILLED_PRODUCT_UNITS,q.period(),dates,DataQuality.READY,"100","100","100","60");
        when(reader.readWindow(actor,T,q,dates)).thenReturn(raw);
        var c=pattern(q,dates);
        assertThat(c.tenant()).isEqualTo(T);assertThat(c.actor()).isEqualTo(A);
        assertThat(c.evidence().getFirst().points()).isEqualTo(raw);
        assertThat(find(c,Lane.OBSERVATION).code()).isEqualTo(Code.DECREASE);
        assertThat(find(c,Lane.OBSERVATION).measures()).contains(new Measure(MeasureCode.DELTA,new BigDecimal("-40")));
        assertThat(find(c,Lane.ANOMALY).code()).isEqualTo(Code.DROP);
        assertThat(find(c,Lane.CAUSE).disposition()).isEqualTo(Disposition.ABSTAINED);
        assertThat(c.decisionEligibility()).isEqualTo(DecisionEligibility.BLOCKED);
        assertThat(pattern(q,dates)).isEqualTo(c);
    }
    @Test void partialAndMissingNeverProduceFindings(){
        var dates=days(4);var q=query(PeriodResolver.Code.D,dates.getLast());
        for(var quality:List.of(DataQuality.PARTIAL,DataQuality.MISSING,DataQuality.STALE,DataQuality.NOT_MEASURABLE)){
            when(reader.readWindow(actor,T,q,dates)).thenReturn(points(FULFILLED_PRODUCT_UNITS,q.period(),dates,quality,"100","100","100","60"));
            var c=pattern(q,dates);
            assertThat(c.findings()).allSatisfy(f->{assertThat(f.disposition()).isEqualTo(Disposition.ABSTAINED);
                assertThat(f.measures()).isEmpty();});
        }
    }
    @Test void zeroBaselineDoesNotInventGrowth(){
        var dates=days(2);var q=query(PeriodResolver.Code.D,dates.getLast());
        when(reader.readWindow(actor,T,q,dates)).thenReturn(points(FULFILLED_PRODUCT_UNITS,q.period(),dates,DataQuality.READY,"0","10"));
        var f=find(pattern(q,dates),Lane.OBSERVATION);
        assertThat(f.code()).isEqualTo(Code.INCREASE);
        assertThat(f.measures()).containsExactly(new Measure(MeasureCode.DELTA,BigDecimal.TEN));
    }
    @Test void unequalDurationsDoNotProduceDirection(){
        var dates=List.of(START,START.plusMonths(1));var q=query(PeriodResolver.Code.M,dates.getLast());
        when(reader.readWindow(actor,T,q,dates)).thenReturn(points(FULFILLED_PRODUCT_UNITS,q.period(),dates,DataQuality.READY,"310","280"));
        assertThat(find(pattern(q,dates),Lane.OBSERVATION).code()).isEqualTo(Code.INCOMPARABLE_PERIOD_LENGTH);
    }
    @Test void revisionMismatchAbstains(){
        var dates=days(2);var q=query(PeriodResolver.Code.D,dates.getLast());
        var raw=points(FULFILLED_PRODUCT_UNITS,q.period(),dates,DataQuality.READY,"100","60");
        var e=raw.getLast();
        var changed=new AuthorizedAnalyticalReadService.Result(e.metric(),e.methodVersion(),e.period(),e.value(),e.quality(),
            e.route(),e.reason(),e.qualityReason(),e.unit(),e.currency(),e.generation(),e.proof(),8,e.observedAt());
        when(reader.readWindow(actor,T,q,dates)).thenReturn(List.of(raw.getFirst(),changed));
        assertThat(find(pattern(q,dates),Lane.OBSERVATION).code()).isEqualTo(Code.INCOMPATIBLE_EVIDENCE);
    }
    @Test void factorFlowsRemainAmbiguousAndSeparate(){
        var dates=days(2);var q=query(PeriodResolver.Code.D,dates.getLast());
        when(actor.userId()).thenReturn(A);
        when(reader.readMetricWindows(eq(actor),eq(T),anyList(),eq(dates))).thenReturn(List.of(
            points(FULFILLED_PRODUCT_UNITS,q.period(),dates,DataQuality.READY,"100","60"),
            points(STOCK_IN,q.period(),dates,DataQuality.READY,"100","0"),
            points(STOCK_OUT,q.period(),dates,DataQuality.READY,"100","60")));
        var c=service.concludeFactors(actor,T,q,dates.getFirst(),1,ABS,REL);
        assertThat(c.evidence()).hasSize(3);
        assertThat(find(c,Lane.FACTOR).code()).isEqualTo(Code.AMBIGUOUS_FACTORS);
        assertThat(find(c,Lane.FACTOR).checks()).contains(Check.VERIFY_STOCK_AVAILABILITY,Check.VERIFY_UNFULFILLED_DEMAND);
        assertThat(find(c,Lane.CAUSE).disposition()).isEqualTo(Disposition.ABSTAINED);
        assertThat(c.decisionEligibility()).isEqualTo(DecisionEligibility.BLOCKED);
        verify(reader,never()).readWindow(any(),any(),any(),anyList());
    }
    @Test void deniedReadPropagatesWithoutConclusion(){
        var dates=days(2);var q=query(PeriodResolver.Code.D,dates.getLast());
        when(reader.readWindow(actor,T,q,dates)).thenThrow(new SecurityException("DENIED"));
        assertThatThrownBy(()->pattern(q,dates)).isInstanceOf(SecurityException.class);
    }
}
