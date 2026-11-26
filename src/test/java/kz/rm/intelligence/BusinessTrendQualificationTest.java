package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BusinessTrendQualificationTest {
    private static final UUID TENANT=new UUID(0,1),ACTOR=new UUID(0,2),SKU=new UUID(0,3),WAREHOUSE=new UUID(0,4);
    private static final Instant NOW=Instant.parse("2026-06-01T00:00:00Z");
    private static final BusinessInterpretationEngine.Policy POLICY=new BusinessInterpretationEngine.Policy(1,new BigDecimal("5"),new BigDecimal("0.05"));
    private static List<BusinessInterpretationEngine.Point> points(PeriodResolver.Code code,LocalDate start,DataQuality quality,String... values){
        var scope=new BusinessInterpretationEngine.Scope(TENANT,ACTOR,AuthorizedAnalyticalReadService.Metric.FULFILLED_PRODUCT_UNITS,
            1,code,null,null,SKU,List.of(WAREHOUSE),null,"PRODUCT_UNITS",1);
        var calendar=new PeriodResolver.Policy(ZoneId.of("Etc/UTC"),1);
        var list=new ArrayList<BusinessInterpretationEngine.Point>();
        var date=start;
        for(String value:values){
            var period=PeriodResolver.resolve(code,date,calendar);
            var e=new AuthorizedAnalyticalReadService.Result(scope.metric(),1,period,value==null?null:new BigDecimal(value),
                quality,AuthorizedAnalyticalReadService.Route.L0,"FIXTURE","FIXTURE",scope.unit(),null,null,null,7,NOW);
            list.add(new BusinessInterpretationEngine.Point(scope,e));
            date=period.endExclusive().atZone(calendar.zone()).toLocalDate();
        }
        return List.copyOf(list);
    }
    private static BusinessTrendAssessment.Result evaluate(List<BusinessInterpretationEngine.Point> points){
        var actual=BusinessTrendAssessment.evaluate(points,POLICY,BusinessInterpretationEngine.evaluate(points,POLICY));
        assertThat(actual.decisionEligibility()).isEqualTo("BLOCKED");
        return actual;
    }
    private static BusinessTrendAssessment.Result daily(DataQuality quality,String... values){
        return evaluate(points(PeriodResolver.Code.D,LocalDate.of(2026,1,1),quality,values));
    }
    @Test void bs001StableAndCumulativeDrift(){
        var stable=daily(DataQuality.READY,"100","100","100");
        assertThat(stable.direction()).isEqualTo(BusinessTrendAssessment.Direction.FLAT);
        assertThat(stable.slope()).isEqualByComparingTo("0");
        assertThat(stable.movingAverage()).isEqualByComparingTo("100");
        assertThat(stable.sustainedTrend()).isFalse();
        var drift=daily(DataQuality.READY,"100","104","108");
        assertThat(drift.direction()).isEqualTo(BusinessTrendAssessment.Direction.MIXED);
        assertThat(drift.sustainedTrend()).isFalse();
    }
    @Test void bs002MonotonicObservedGrowth(){
        var o=daily(DataQuality.READY,"100","120","140");
        assertThat(o.direction()).isEqualTo(BusinessTrendAssessment.Direction.UP);
        assertThat(o.slope()).isEqualByComparingTo("20");
        assertThat(o.movingAverage()).isEqualByComparingTo("120");
        assertThat(o.activity()).isEqualTo("ACTIVE");
        assertThat(o.sustainedTrend()).isTrue();
    }
    @Test void bs003MonotonicObservedDecline(){
        var o=daily(DataQuality.READY,"140","120","100");
        assertThat(o.direction()).isEqualTo(BusinessTrendAssessment.Direction.DOWN);
        assertThat(o.slope()).isEqualByComparingTo("-20");
        assertThat(o.sustainedTrend()).isTrue();
    }
    @Test void bs006SingleShockNotSustained(){
        var o=daily(DataQuality.READY,"100","100","40");
        assertThat(o.direction()).isEqualTo(BusinessTrendAssessment.Direction.MIXED);
        assertThat(o.slope()).isEqualByComparingTo("-30");
        assertThat(o.sustainedTrend()).isFalse();
    }
    @Test void bs097ShortMissingAndPartialWindow(){
        for(var o:List.of(daily(DataQuality.READY,"100","140"),daily(DataQuality.PARTIAL,"100","120","140"),
            daily(DataQuality.MISSING,null,null,null))){
            assertThat(o.direction()).isEqualTo(BusinessTrendAssessment.Direction.UNAVAILABLE);
            assertThat(o.slope()).isNull();assertThat(o.movingAverage()).isNull();assertThat(o.sustainedTrend()).isFalse();
        }
    }
    @Test void bs102ReboundIsNotRecovery(){
        var o=daily(DataQuality.READY,"100","10","20");
        assertThat(o.direction()).isEqualTo(BusinessTrendAssessment.Direction.MIXED);
        assertThat(o.slope()).isEqualByComparingTo("-40");
        assertThat(o.sustainedTrend()).isFalse();
    }
    @Test void unequalCalendarDurationAbstains(){
        var o=evaluate(points(PeriodResolver.Code.M,LocalDate.of(2026,1,1),DataQuality.READY,"100","120","140"));
        assertThat(o.reason()).isEqualTo(BusinessTrendAssessment.Reason.INCOMPARABLE_PERIOD_LENGTH);
        assertThat(o.slope()).isNull();
    }
}
