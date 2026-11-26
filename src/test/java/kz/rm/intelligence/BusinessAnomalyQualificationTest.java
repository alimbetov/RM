package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.intelligence.BusinessAnomalyAssessment.*;

class BusinessAnomalyQualificationTest {
    private static final UUID TENANT=new UUID(0,1),ACTOR=new UUID(0,2),SKU=new UUID(0,3),WAREHOUSE=new UUID(0,4);
    private static final Instant NOW=Instant.parse("2026-06-01T00:00:00Z");
    private static final BusinessInterpretationEngine.Policy MATERIALITY=
        new BusinessInterpretationEngine.Policy(1,new BigDecimal("5"),new BigDecimal("0.05"));
    private static final Policy POLICY=new Policy(1,new BigDecimal("3"),new BigDecimal("0.5"));
    private static List<BusinessInterpretationEngine.Point> points(PeriodResolver.Code code,DataQuality quality,String... values){
        var scope=new BusinessInterpretationEngine.Scope(TENANT,ACTOR,AuthorizedAnalyticalReadService.Metric.FULFILLED_PRODUCT_UNITS,
            1,code,null,null,SKU,List.of(WAREHOUSE),null,"PRODUCT_UNITS",1);
        var calendar=new PeriodResolver.Policy(ZoneId.of("Etc/UTC"),1);
        var list=new ArrayList<BusinessInterpretationEngine.Point>();var day=LocalDate.of(2026,1,1);
        for(String value:values){
            var p=PeriodResolver.resolve(code,day,calendar);
            var e=new AuthorizedAnalyticalReadService.Result(scope.metric(),1,p,value==null?null:new BigDecimal(value),
                quality,AuthorizedAnalyticalReadService.Route.L0,"FIXTURE","FIXTURE",scope.unit(),null,null,null,7,NOW);
            list.add(new BusinessInterpretationEngine.Point(scope,e));
            day=p.endExclusive().atZone(calendar.zone()).toLocalDate();
        }
        return List.copyOf(list);
    }
    private static Result assess(List<BusinessInterpretationEngine.Point> points){
        var r=evaluate(points,MATERIALITY,POLICY);
        assertThat(r.decisionEligibility()).isEqualTo("BLOCKED");
        return r;
    }
    private static Result daily(DataQuality quality,String... values){return assess(points(PeriodResolver.Code.D,quality,values));}
    @Test void bs005SpikeExcludesTargetFromBaseline(){
        var r=daily(DataQuality.READY,"100","100","100","160");
        assertThat(r.pattern()).isEqualTo(Pattern.SPIKE);
        assertThat(r.baselinePeriods()).isEqualTo(3);
        assertThat(r.sma()).isEqualByComparingTo("100");assertThat(r.ema()).isEqualByComparingTo("100");
        assertThat(r.stddev()).isEqualByComparingTo("0");assertThat(r.cv()).isEqualByComparingTo("0");
        assertThat(r.deviation()).isEqualByComparingTo("60");assertThat(r.standardizedDeviation()).isNull();
        assertThat(r.anomalyCandidate()).isTrue();
    }
    @Test void bs051DropAndNonconstantBaseline(){
        var r=daily(DataQuality.READY,"90","100","110","50");
        assertThat(r.pattern()).isEqualTo(Pattern.DROP);
        assertThat(r.sma()).isEqualByComparingTo("100");assertThat(r.ema()).isEqualByComparingTo("102.5");
        assertThat(r.baselineSlope()).isEqualByComparingTo("10");
        assertThat(r.stddev()).isCloseTo(new BigDecimal("8.1649658092772603"),within(new BigDecimal("0.000000000000001")));
        assertThat(r.cv()).isCloseTo(new BigDecimal("0.081649658092772603"),within(new BigDecimal("0.000000000000001")));
        assertThat(r.deviation()).isEqualByComparingTo("-50");
    }
    @Test void bs100NoiseAndBandBoundary(){
        var r=daily(DataQuality.READY,"90","100","110","120");
        assertThat(r.pattern()).isEqualTo(Pattern.NO_ANOMALY);
        assertThat(r.reason()).isEqualTo(Reason.WITHIN_BASELINE_BAND);
        var tiny=daily(DataQuality.READY,"100","100","100","104");
        assertThat(tiny.reason()).isEqualTo(Reason.IMMATERIAL_DEVIATION);
        assertThat(tiny.anomalyCandidate()).isFalse();
    }
    @Test void bs007VolatileBaselineSuppressesDirectionalCandidate(){
        var r=daily(DataQuality.READY,"0","100","200","400");
        assertThat(r.pattern()).isEqualTo(Pattern.VOLATILE_BASELINE);
        assertThat(r.anomalyCandidate()).isFalse();
    }
    @Test void bs093ZeroBaselineAndMissing(){
        var zero=daily(DataQuality.READY,"0","0","0","30");
        assertThat(zero.reason()).isEqualTo(Reason.ZERO_BASELINE);
        assertThat(zero.cv()).isNull();assertThat(zero.standardizedDeviation()).isNull();
        assertThat(zero.anomalyCandidate()).isFalse();
        var missing=daily(DataQuality.MISSING,null,null,null,null);
        assertThat(missing.pattern()).isEqualTo(Pattern.UNAVAILABLE);assertThat(missing.sma()).isNull();
    }
    @Test void bs097ShortPartialAndUnequalDuration(){
        for(var r:List.of(daily(DataQuality.READY,"100","100","160"),
            daily(DataQuality.PARTIAL,"100","100","100","160"),
            assess(points(PeriodResolver.Code.M,DataQuality.READY,"100","100","100","160")))){
            assertThat(r.pattern()).isEqualTo(Pattern.UNAVAILABLE);assertThat(r.sma()).isNull();
            assertThat(r.anomalyCandidate()).isFalse();
        }
    }
    @Test void bs112StableNoFalseAlarm(){
        var r=daily(DataQuality.READY,"100","100","100","100");
        assertThat(r.pattern()).isEqualTo(Pattern.NO_ANOMALY);assertThat(r.anomalyCandidate()).isFalse();
        assertThat(r.deviation()).isEqualByComparingTo("0");
    }
    @Test void incompatibleEvidenceAndPolicyRejected(){
        var input=new ArrayList<>(points(PeriodResolver.Code.D,DataQuality.READY,"100","100","100","160"));
        var last=input.getLast();var e=last.evidence();
        input.set(3,new BusinessInterpretationEngine.Point(last.scope(),new AuthorizedAnalyticalReadService.Result(
            e.metric(),e.methodVersion(),e.period(),e.value(),e.quality(),e.route(),e.reason(),e.qualityReason(),
            e.unit(),e.currency(),e.generation(),e.proof(),8,e.observedAt())));
        assertThat(assess(input).reason()).isEqualTo(Reason.INCOMPATIBLE_EVIDENCE);
        assertThatThrownBy(()->new Policy(1,BigDecimal.ZERO,BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    }
}
