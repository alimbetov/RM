package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DerivedMetricQualificationTest {
    private final LocalDate anchor=LocalDate.of(2026,3,1);
    private final Instant asOf=Instant.parse("2026-04-02T00:00:00Z");
    private final DerivedMetricEngine.Scope scope=new DerivedMetricEngine.Scope(
        UUID.randomUUID(),UUID.randomUUID(),"BOOKED_ORDERS",1,"ALL","TEST","COUNT","NONE",
        PeriodResolver.Code.M,new PeriodResolver.Policy(ZoneId.of("UTC"),1),1);
    private List<DerivedMetricEngine.Point> points(String... values){
        var periods=DerivedMetricEngine.closedWindow(scope,anchor,asOf,values.length);
        var result=new ArrayList<DerivedMetricEngine.Point>();
        for(int i=0;i<values.length;i++)
            result.add(new DerivedMetricEngine.Point(scope,periods.get(i),
                values[i]==null?null:new BigDecimal(values[i]),values[i]==null?DataQuality.MISSING:DataQuality.READY,asOf));
        return result;
    }
    private DerivedMetricEngine.Result calculate(DerivedMetricEngine.Operator op,List<DerivedMetricEngine.Point> points){
        return DerivedMetricEngine.calculate(scope,op,anchor,asOf,3,points);
    }
    @Test void deterministicMathematics(){
        var p=points("100","130","160");
        var expected=Map.of(
            DerivedMetricEngine.Operator.AVG,"130",DerivedMetricEngine.Operator.SMA,"130",
            DerivedMetricEngine.Operator.EMA,"137.5",DerivedMetricEngine.Operator.TREND,"30",
            DerivedMetricEngine.Operator.PERIOD_CHANGE,"30",DerivedMetricEngine.Operator.MIN,"100",
            DerivedMetricEngine.Operator.MAX,"160",DerivedMetricEngine.Operator.FORECAST_NEXT,"130");
        expected.forEach((op,v)->{
            var r=calculate(op,p);
            assertEquals(DataQuality.READY,r.quality());
            assertEquals(0,new BigDecimal(v).compareTo(r.value()));
        });
        var sd=calculate(DerivedMetricEngine.Operator.STDDEV_POP,p).value();
        assertTrue(sd.subtract(new BigDecimal("24.49489742783178098197284074705891")).abs().compareTo(new BigDecimal("1E-30"))<0);
        assertTrue(calculate(DerivedMetricEngine.Operator.CV,p).value().signum()>0);
        assertEquals(DataQuality.NOT_MEASURABLE,calculate(DerivedMetricEngine.Operator.PERIOD_GROWTH,points("100","0","160")).quality());
    }
    @Test void missingIsNotZeroAndFutureRejected(){
        var missing=calculate(DerivedMetricEngine.Operator.SMA,points("100",null,"160"));
        assertEquals(DataQuality.MISSING,missing.quality());assertNull(missing.value());
        var zero=calculate(DerivedMetricEngine.Operator.SMA,points("100","0","160"));
        assertEquals(DataQuality.READY,zero.quality());
        assertTrue(zero.value().subtract(new BigDecimal("86.66666666666666666666666666666667")).abs().compareTo(new BigDecimal("1E-30"))<0);
        assertThrows(IllegalArgumentException.class,()->DerivedMetricEngine.closedWindow(scope,anchor,Instant.parse("2026-03-15T00:00:00Z"),3));
        var p=points("100","130","160");p.set(1,p.get(0));
        assertThrows(IllegalArgumentException.class,()->calculate(DerivedMetricEngine.Operator.SMA,p));
    }
    @Test void activitySeparatesGapHistoryAndDecision(){
        for(var vals:List.of(new String[]{null,"130","160"},new String[]{"100",null,"160"},new String[]{"100","0","160"})){
            var p=points(vals);
            var observations=p.stream().map(x->new L3DerivedSeriesService.Observation(x.period(),x.value(),x.quality(),null)).toList();
            var a=MetricActivityGate.assess(scope.periodCode(),anchor,asOf,observations,calculate(DerivedMetricEngine.Operator.SMA,p));
            assertEquals(vals[0]==null?MetricActivityGate.Activity.INSUFFICIENT_HISTORY:
                vals[1]==null?MetricActivityGate.Activity.PERIOD_GAP:MetricActivityGate.Activity.ACTIVE,a.activity());
            assertEquals(MetricActivityGate.DecisionEligibility.BLOCKED,a.decisionEligibility());
        }
    }
    @Test void numericParityFailsClosed(){
        assertTrue(DerivedNumericParity.equivalent(DerivedMetricEngine.Operator.MIN,new BigDecimal("1.0"),BigDecimal.ONE));
        assertFalse(DerivedNumericParity.equivalent(DerivedMetricEngine.Operator.MIN,BigDecimal.ONE,new BigDecimal("1.000000000000001")));
        assertFalse(DerivedNumericParity.equivalent(DerivedMetricEngine.Operator.SMA,BigDecimal.ONE,null));
    }
    @Test void unsupportedMethodVersionRejected(){
        assertThrows(IllegalArgumentException.class,()->new DerivedMetricEngine.Scope(
            scope.tenant(),scope.generation(),scope.metric(),2,scope.dimensionKey(),
            scope.authorizationScopeKey(),scope.unitKey(),scope.currencyKey(),
            scope.periodCode(),scope.calendar(),scope.sourceRevision()));
    }
}
