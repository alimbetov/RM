package kz.rm.intelligence;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** B5.2: descriptive series evidence only; not a demand or forecast admission. */
final class BusinessTrendAssessment {
    private BusinessTrendAssessment(){}
    static final int POLICY_VERSION=1;
    enum Direction { UP, DOWN, FLAT, MIXED, UNAVAILABLE }
    enum Reason { WINDOW_TOO_SHORT, WINDOW_NOT_QUALIFIED, INCOMPATIBLE_EVIDENCE,
        INCOMPARABLE_PERIOD_LENGTH, NO_MATERIAL_CHANGE, MONOTONIC_MATERIAL_CHANGE,
        NON_MONOTONIC_OR_IMMATERIAL_STEPS }
    record Result(int policyVersion,Direction direction,Reason reason,DataQuality quality,
                  String activity,int periods,int readyPeriods,BigDecimal slope,BigDecimal movingAverage,
                  boolean sustainedTrend,String decisionEligibility){}
    static Result evaluate(List<BusinessInterpretationEngine.Point> input,
            BusinessInterpretationEngine.Policy policy,BusinessInterpretationEngine.Outcome observation){
        var points=List.copyOf(input);
        int n=points.size();
        int ready=(int)points.stream().filter(p->p.evidence().quality()==DataQuality.READY&&p.evidence().value()!=null).count();
        if(observation.status()==BusinessInterpretationEngine.Status.CONFLICTING_EVIDENCE)
            return unavailable(Reason.INCOMPATIBLE_EVIDENCE,observation.quality(),"INVALID",n,ready);
        if(n<3||n>12)
            return unavailable(Reason.WINDOW_TOO_SHORT,DataQuality.NOT_MEASURABLE,"INSUFFICIENT_HISTORY",n,ready);
        if(observation.quality()!=DataQuality.READY||ready!=n)
            return unavailable(Reason.WINDOW_NOT_QUALIFIED,observation.quality(),
                observation.quality()==DataQuality.STALE?"STALE":
                observation.reason()==BusinessInterpretationEngine.Reason.OPEN_PERIOD?"OPEN_PERIOD":
                observation.quality()==DataQuality.MISSING?"INSUFFICIENT_HISTORY":"PARTIAL_COVERAGE",n,ready);
        var first=points.getFirst();var last=points.getLast();var scope=first.scope();
        var calendar=new PeriodResolver.Policy(ZoneId.of(first.evidence().period().zoneId()),scope.calendarVersion());
        // Totals in months/decades of unequal duration are not a like-for-like
        // trend. This version also abstains on DST duration differences.
        var duration=Duration.between(first.evidence().period().startInclusive(),first.evidence().period().endExclusive());
        if(points.stream().anyMatch(p->!duration.equals(Duration.between(p.evidence().period().startInclusive(),p.evidence().period().endExclusive()))))
            return unavailable(Reason.INCOMPARABLE_PERIOD_LENGTH,DataQuality.NOT_MEASURABLE,"NOT_APPLICABLE",n,ready);
        var generation=points.stream().map(p->p.evidence().generation()).filter(Objects::nonNull).findFirst()
            .orElseGet(()->UUID.nameUUIDFromBytes(("B5-L0:"+scope.tenant()+":"+first.evidence().sourceRevision()).getBytes(StandardCharsets.UTF_8)));
        // This internal key is an arithmetic scope only; it is not a projection proof.
        var derivedScope=new DerivedMetricEngine.Scope(scope.tenant(),generation,scope.metric().name(),
            scope.methodVersion(),scope.toString(),scope.actor().toString(),scope.unit(),
            Objects.toString(scope.currency(),"NO_CURRENCY"),scope.code(),calendar,first.evidence().sourceRevision());
        var derived=points.stream().map(p->new DerivedMetricEngine.Point(derivedScope,p.evidence().period(),
            p.evidence().value(),p.evidence().quality(),p.evidence().observedAt())).toList();
        var observations=points.stream().map(p->new L3DerivedSeriesService.Observation(
            p.evidence().period(),p.evidence().value(),p.evidence().quality(),p.evidence().qualityReason())).toList();
        var anchor=last.evidence().period().startInclusive().atZone(calendar.zone()).toLocalDate();
        var asOf=last.evidence().observedAt();
        var slope=DerivedMetricEngine.calculate(derivedScope,DerivedMetricEngine.Operator.TREND,anchor,asOf,n,derived);
        var mean=DerivedMetricEngine.calculate(derivedScope,DerivedMetricEngine.Operator.SMA,anchor,asOf,n,derived);
        var activity=MetricActivityGate.assess(scope.code(),anchor,asOf,observations,slope);
        if(activity.activity()!=MetricActivityGate.Activity.ACTIVE||mean.quality()!=DataQuality.READY)
            return unavailable(Reason.WINDOW_NOT_QUALIFIED,slope.quality(),activity.activity().name(),n,ready);
        boolean allUp=true,allDown=true,anyMaterial=false;
        for(int i=1;i<n;i++){
            var before=points.get(i-1).evidence().value();
            var delta=points.get(i).evidence().value().subtract(before);
            boolean material=delta.signum()!=0&&delta.abs().compareTo(policy.absoluteThreshold())>=0
                &&(before.signum()==0||delta.abs().compareTo(before.multiply(policy.relativeThreshold()))>=0);
            anyMaterial|=material;
            allUp&=material&&delta.signum()>0;
            allDown&=material&&delta.signum()<0;
        }
        // FLAT requires both adjacent steps and the entire span to be immaterial.
        // Many small increases must not be labelled stable.
        var firstValue=first.evidence().value();
        var total=last.evidence().value().subtract(firstValue);
        boolean spanMaterial=total.signum()!=0&&total.abs().compareTo(policy.absoluteThreshold())>=0
            &&(firstValue.signum()==0||total.abs().compareTo(firstValue.multiply(policy.relativeThreshold()))>=0);
        Direction direction=allUp&&slope.value().signum()>0?Direction.UP:
            allDown&&slope.value().signum()<0?Direction.DOWN:
            !anyMaterial&&!spanMaterial?Direction.FLAT:Direction.MIXED;
        boolean sustained=direction==Direction.UP||direction==Direction.DOWN;
        return new Result(POLICY_VERSION,direction,sustained?Reason.MONOTONIC_MATERIAL_CHANGE:
            direction==Direction.FLAT?Reason.NO_MATERIAL_CHANGE:Reason.NON_MONOTONIC_OR_IMMATERIAL_STEPS,
            DataQuality.READY,activity.activity().name(),n,ready,slope.value(),mean.value(),sustained,"BLOCKED");
    }
    private static Result unavailable(Reason reason,DataQuality quality,String activity,int n,int ready){
        return new Result(POLICY_VERSION,Direction.UNAVAILABLE,reason,quality,activity,n,ready,null,null,false,"BLOCKED");
    }
}
