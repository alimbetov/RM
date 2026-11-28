package kz.rm.intelligence;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.*;

/** B5.3: descriptive outlier screening against preceding observed periods, not a prediction. */
final class BusinessAnomalyAssessment {
    private BusinessAnomalyAssessment(){}
    static final int VERSION=1;
    enum Pattern { SPIKE, DROP, VOLATILE_BASELINE, NO_ANOMALY, UNAVAILABLE }
    enum Reason { WINDOW_TOO_SHORT, WINDOW_NOT_QUALIFIED, INCOMPATIBLE_EVIDENCE,
        INCOMPARABLE_PERIOD_LENGTH, ZERO_BASELINE, HIGH_BASELINE_VOLATILITY,
        IMMATERIAL_DEVIATION, WITHIN_BASELINE_BAND, OUTSIDE_BASELINE_BAND }
    record Policy(int version,BigDecimal sigmaMultiplier,BigDecimal maxBaselineCv){
        Policy {
            Objects.requireNonNull(sigmaMultiplier);Objects.requireNonNull(maxBaselineCv);
            if(version!=VERSION||sigmaMultiplier.signum()<=0||maxBaselineCv.signum()<0)
                throw new IllegalArgumentException("ANOMALY_POLICY");
        }
    }
    record Result(int policyVersion,Pattern pattern,Reason reason,DataQuality quality,int baselinePeriods,
                  BigDecimal actual,BigDecimal sma,BigDecimal ema,BigDecimal stddev,BigDecimal cv,
                  BigDecimal baselineSlope,BigDecimal deviation,BigDecimal standardizedDeviation,
                  boolean anomalyCandidate,String decisionEligibility){}
    record Band(BigDecimal lower,BigDecimal upper){}
    /** Same sigma multiplier as the admitted anomaly test; never a confidence interval. */
    static Band band(Result result,Policy policy){
        Objects.requireNonNull(result);Objects.requireNonNull(policy);
        if(result.policyVersion()!=policy.version()||result.quality()!=DataQuality.READY
            ||result.sma()==null||result.stddev()==null)return new Band(null,null);
        var width=result.stddev().multiply(policy.sigmaMultiplier());
        return new Band(result.sma().subtract(width),result.sma().add(width));
    }
    static Result evaluate(List<BusinessInterpretationEngine.Point> input,
                           BusinessInterpretationEngine.Policy materiality,Policy policy){
        Objects.requireNonNull(materiality);Objects.requireNonNull(policy);
        var points=List.copyOf(input);
        int n=points.size()-1;
        var observation=BusinessInterpretationEngine.evaluate(points,materiality);
        if(observation.status()==BusinessInterpretationEngine.Status.CONFLICTING_EVIDENCE)
            return unavailable(Reason.INCOMPATIBLE_EVIDENCE,observation.quality(),Math.max(0,n));
        if(n<3||n>11)return unavailable(Reason.WINDOW_TOO_SHORT,DataQuality.NOT_MEASURABLE,Math.max(0,n));
        if(observation.quality()!=DataQuality.READY)
            return unavailable(Reason.WINDOW_NOT_QUALIFIED,observation.quality(),n);
        var duration=Duration.between(points.getFirst().evidence().period().startInclusive(),
            points.getFirst().evidence().period().endExclusive());
        if(points.stream().anyMatch(p->!duration.equals(Duration.between(p.evidence().period().startInclusive(),p.evidence().period().endExclusive()))))
            return unavailable(Reason.INCOMPARABLE_PERIOD_LENGTH,DataQuality.NOT_MEASURABLE,n);
        var history=List.copyOf(points.subList(0,n));
        var actual=points.getLast().evidence().value();
        var first=history.getFirst();var last=history.getLast();var scope=first.scope();
        var calendar=new PeriodResolver.Policy(java.time.ZoneId.of(first.evidence().period().zoneId()),scope.calendarVersion());
        var generation=history.stream().map(p->p.evidence().generation()).filter(Objects::nonNull).findFirst()
            .orElseGet(()->UUID.nameUUIDFromBytes(("B5-L0:"+scope.tenant()+":"+first.evidence().sourceRevision())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var derivedScope=new DerivedMetricEngine.Scope(scope.tenant(),generation,scope.metric().name(),scope.methodVersion(),
            scope.toString(),scope.actor().toString(),scope.unit(),Objects.toString(scope.currency(),"NO_CURRENCY"),
            scope.code(),calendar,first.evidence().sourceRevision());
        var derived=history.stream().map(p->new DerivedMetricEngine.Point(derivedScope,p.evidence().period(),
            p.evidence().value(),p.evidence().quality(),p.evidence().observedAt())).toList();
        var observations=history.stream().map(p->new L3DerivedSeriesService.Observation(
            p.evidence().period(),p.evidence().value(),p.evidence().quality(),p.evidence().qualityReason())).toList();
        var anchor=last.evidence().period().startInclusive().atZone(calendar.zone()).toLocalDate();
        var asOf=last.evidence().observedAt();
        var metrics=new EnumMap<DerivedMetricEngine.Operator,DerivedMetricEngine.Result>(DerivedMetricEngine.Operator.class);
        for(var op:List.of(DerivedMetricEngine.Operator.SMA,DerivedMetricEngine.Operator.EMA,
            DerivedMetricEngine.Operator.STDDEV_POP,DerivedMetricEngine.Operator.CV,DerivedMetricEngine.Operator.TREND))
            metrics.put(op,DerivedMetricEngine.calculate(derivedScope,op,anchor,asOf,n,derived));
        var mean=metrics.get(DerivedMetricEngine.Operator.SMA);
        if(MetricActivityGate.assess(scope.code(),anchor,asOf,observations,mean).activity()!=MetricActivityGate.Activity.ACTIVE)
            return unavailable(Reason.WINDOW_NOT_QUALIFIED,mean.quality(),n);
        var sma=mean.value();
        var ema=metrics.get(DerivedMetricEngine.Operator.EMA).value();
        var sd=metrics.get(DerivedMetricEngine.Operator.STDDEV_POP).value();
        var cv=metrics.get(DerivedMetricEngine.Operator.CV).value();
        var slope=metrics.get(DerivedMetricEngine.Operator.TREND).value();
        var delta=actual.subtract(sma);
        var standardized=sd.signum()==0?null:delta.divide(sd,MathContext.DECIMAL128);
        if(sma.signum()==0)
            return result(Pattern.UNAVAILABLE,Reason.ZERO_BASELINE,DataQuality.NOT_MEASURABLE,n,actual,sma,ema,sd,cv,slope,delta,standardized);
        if(cv.compareTo(policy.maxBaselineCv())>0)
            return result(Pattern.VOLATILE_BASELINE,Reason.HIGH_BASELINE_VOLATILITY,DataQuality.READY,n,actual,sma,ema,sd,cv,slope,delta,standardized);
        boolean material=delta.signum()!=0&&delta.abs().compareTo(materiality.absoluteThreshold())>=0
            &&delta.abs().compareTo(sma.multiply(materiality.relativeThreshold()))>=0;
        if(!material)return result(Pattern.NO_ANOMALY,Reason.IMMATERIAL_DEVIATION,DataQuality.READY,n,actual,sma,ema,sd,cv,slope,delta,standardized);
        // Compare in original units instead of a rounded standardized ratio. With a constant
        // positive baseline the band has width zero; materiality is still mandatory.
        if(delta.abs().compareTo(sd.multiply(policy.sigmaMultiplier()))<=0)
            return result(Pattern.NO_ANOMALY,Reason.WITHIN_BASELINE_BAND,DataQuality.READY,n,actual,sma,ema,sd,cv,slope,delta,standardized);
        return result(delta.signum()>0?Pattern.SPIKE:Pattern.DROP,Reason.OUTSIDE_BASELINE_BAND,DataQuality.READY,
            n,actual,sma,ema,sd,cv,slope,delta,standardized);
    }
    private static Result unavailable(Reason reason,DataQuality quality,int n){
        return result(Pattern.UNAVAILABLE,reason,quality,n,null,null,null,null,null,null,null,null);
    }
    private static Result result(Pattern pattern,Reason reason,DataQuality quality,int n,BigDecimal actual,
                                 BigDecimal sma,BigDecimal ema,BigDecimal sd,BigDecimal cv,BigDecimal slope,
                                 BigDecimal delta,BigDecimal standardized){
        return new Result(VERSION,pattern,reason,quality,n,actual,sma,ema,sd,cv,slope,delta,standardized,
            pattern==Pattern.SPIKE||pattern==Pattern.DROP,"BLOCKED");
    }
}
