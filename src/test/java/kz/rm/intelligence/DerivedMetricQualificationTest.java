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
    @Test void monthlySnapshotCalendarAndQualifiedZeroContract(){
        UUID tenant=UUID.randomUUID();
        var month=YearMonth.of(2024,3);
        var binding=new NativeBookedClosureService.CalendarBinding(tenant,7,"Europe/Berlin",
            Instant.parse("2024-01-01T00:00:00Z"),month);
        var period=binding.period();
        assertEquals(Instant.parse("2024-02-29T23:00:00Z"),period.startInclusive());
        assertEquals(Instant.parse("2024-03-31T22:00:00Z"),period.endExclusive());
        assertEquals(743,Duration.between(period.startInclusive(),period.endExclusive()).toHours());
        var leap=new NativeBookedClosureService.CalendarBinding(tenant,7,"Etc/UTC",binding.effectiveFrom(),YearMonth.of(2024,2));
        assertEquals(29,Duration.between(leap.period().startInclusive(),leap.period().endExclusive()).toDays());
        var boundary=new NativeBookedCaptureService.Boundary(tenant,period.startInclusive(),1);
        Instant observed=period.endExclusive().plusSeconds(1);
        var rev=new NativeBookedClosureService.Revision(0,0,0);
        var empty=new NativeBookedClosureService.Population(0,0,0,0,0,0,0);
        var evidence=new NativeBookedClosureService.Evidence(1,tenant,period.startInclusive(),period.endExclusive(),observed,
            boundary,rev,NativeBookedClosureService.State.SNAPSHOT_ZERO,empty,"a".repeat(64),0L);
        var zero=NativeBookedClosureService.bindMonth(binding,evidence);
        assertTrue(zero.snapshotQualified());assertTrue(zero.qualifiedSnapshotZero());assertEquals(0L,zero.snapshotValue().longValue());
        assertEquals("BOOKED_ORDERS",zero.metric());assertEquals(1,zero.methodVersion());
        assertFalse(zero.admitted());assertEquals(DataQuality.MISSING,zero.historicalQuality());assertNull(zero.historicalValue());
        var positiveEvidence=new NativeBookedClosureService.Evidence(1,tenant,evidence.from(),evidence.until(),observed.plusSeconds(1),
            boundary,new NativeBookedClosureService.Revision(0,4,1),NativeBookedClosureService.State.SNAPSHOT_VALUE,
            new NativeBookedClosureService.Population(1,1,1,1,0,0,0),"b".repeat(64),1L);
        var positive=NativeBookedClosureService.bindMonth(binding,positiveEvidence);
        assertEquals(1L,positive.historicalValue().longValue());assertEquals(DataQuality.PARTIAL,positive.historicalQuality());
        assertFalse(positive.qualifiedSnapshotZero());assertFalse(positive.admitted());assertFalse(zero.sameEvidence(positive));
        var bad=new NativeBookedClosureService.Evidence(1,tenant,evidence.from(),evidence.until(),observed,boundary,rev,
            NativeBookedClosureService.State.SOURCE_MISMATCH,empty,null,null);
        var missing=NativeBookedClosureService.bindMonth(binding,bad);
        assertNull(missing.snapshotValue());assertFalse(missing.qualifiedSnapshotZero());assertEquals(DataQuality.MISSING,missing.historicalQuality());
        assertThrows(IllegalArgumentException.class,()->NativeBookedClosureService.bindMonth(leap,evidence));
        var foreign=new NativeBookedClosureService.CalendarBinding(UUID.randomUUID(),7,binding.zoneId(),binding.effectiveFrom(),month);
        assertThrows(IllegalArgumentException.class,()->NativeBookedClosureService.bindMonth(foreign,evidence));
        var falseZero=new NativeBookedClosureService.Evidence(1,tenant,evidence.from(),evidence.until(),observed,boundary,
            new NativeBookedClosureService.Revision(0,4,1),NativeBookedClosureService.State.SNAPSHOT_ZERO,
            new NativeBookedClosureService.Population(1,1,1,1,0,0,0),"c".repeat(64),0L);
        assertThrows(IllegalArgumentException.class,()->NativeBookedClosureService.bindMonth(binding,falseZero));
        var beforeBoundary=new NativeBookedClosureService.Evidence(1,tenant,evidence.from(),evidence.until(),observed,
            new NativeBookedCaptureService.Boundary(tenant,evidence.from().plusSeconds(1),1),rev,
            NativeBookedClosureService.State.SNAPSHOT_ZERO,empty,"a".repeat(64),0L);
        assertThrows(IllegalArgumentException.class,()->NativeBookedClosureService.bindMonth(binding,beforeBoundary));
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
    @Test void sparseComparisonUsesOnlyRequiredQualifiedEndpoints(){
        var ready=DataQuality.READY;
        var pair=DerivedMetricEngine.comparePeriods(new BigDecimal("100"),ready,new BigDecimal("80"),ready);
        assertEquals(0,new BigDecimal("-20").compareTo(pair.change().value()));
        assertEquals(0,new BigDecimal("-0.20").compareTo(pair.growth().value()));
        var zero=DerivedMetricEngine.comparePeriods(BigDecimal.ZERO,ready,new BigDecimal("80"),ready);
        assertEquals(0,new BigDecimal("80").compareTo(zero.change().value()));
        assertEquals(DataQuality.NOT_MEASURABLE,zero.growth().quality());
        assertNull(zero.growth().value());
        assertEquals("NONPOSITIVE_BASE",zero.growth().reason());
        for(var quality:List.of(DataQuality.PARTIAL,DataQuality.MISSING,DataQuality.STALE,DataQuality.NOT_MEASURABLE)){
            var blocked=DerivedMetricEngine.comparePeriods(BigDecimal.ONE,quality,BigDecimal.TEN,ready);
            assertEquals(quality,blocked.change().quality());
            assertEquals(quality,blocked.growth().quality());
            assertNull(blocked.change().value());assertNull(blocked.growth().value());
        }
        var currentMissing=DerivedMetricEngine.comparePeriods(BigDecimal.ONE,ready,null,DataQuality.MISSING);
        assertEquals(DataQuality.MISSING,currentMissing.change().quality());
        assertNull(currentMissing.change().value());
        assertThrows(IllegalArgumentException.class,()->DerivedMetricEngine.comparePeriods(null,ready,BigDecimal.ONE,ready));
        var negative=DerivedMetricEngine.comparePeriods(new BigDecimal("-10"),ready,BigDecimal.ZERO,ready);
        assertEquals(DataQuality.NOT_MEASURABLE,negative.growth().quality());
        assertEquals(0,BigDecimal.TEN.compareTo(negative.change().value()));
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
