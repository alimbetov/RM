package kz.rm.intelligence;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.*;
import java.util.*;

/** Pure internal interpreter. It does not confer authorization or historical coverage. */
final class BusinessInterpretationEngine {
    private BusinessInterpretationEngine(){}
    static final int VERSION=1;
    enum Status { INSUFFICIENT_DATA, AMBIGUOUS, CONFLICTING_EVIDENCE, NO_MATERIAL_CHANGE, OBSERVED_CHANGE }
    enum Reason { INCOMPATIBLE_EVIDENCE, OPEN_PERIOD, MISSING_OBSERVATION, UNQUALIFIED_HISTORY,
        INSUFFICIENT_HISTORY, NO_MATERIAL_CHANGE, CHANGE_OBSERVED, CAUSE_UNDETERMINED }
    enum Hypothesis { DEMAND_DECLINE, STOCKOUT_CONSTRAINT }
    enum DecisionEligibility { BLOCKED }
    record Policy(int version,BigDecimal absoluteThreshold,BigDecimal relativeThreshold){
        Policy {
            Objects.requireNonNull(absoluteThreshold);Objects.requireNonNull(relativeThreshold);
            if(version!=VERSION||absoluteThreshold.signum()<0||relativeThreshold.signum()<0)
                throw new IllegalArgumentException("OBSERVATION_POLICY");
        }
    }
    record Scope(UUID tenant,UUID actor,AuthorizedAnalyticalReadService.Metric metric,
                 long calendarVersion,PeriodResolver.Code code,String region,UUID group,
                 UUID sku,List<UUID> warehouses,String currency,String unit,int methodVersion){
        Scope {
            Objects.requireNonNull(tenant);Objects.requireNonNull(actor);Objects.requireNonNull(metric);
            Objects.requireNonNull(code);Objects.requireNonNull(unit);
            warehouses=List.copyOf(warehouses);
        }
    }
    record Point(Scope scope,AuthorizedAnalyticalReadService.Result evidence){
        Point {Objects.requireNonNull(scope);Objects.requireNonNull(evidence);}
    }
    record Outcome(Status status,Reason reason,DataQuality quality,BigDecimal delta,BigDecimal growth,
                   List<Hypothesis> hypotheses,boolean sustainedTrend,DecisionEligibility decisionEligibility){
        Outcome {hypotheses=List.copyOf(hypotheses);}
    }
    static Outcome evaluate(List<Point> input,Policy policy){
        Objects.requireNonNull(policy);
        var points=List.copyOf(input);
        if(points.size()<2||points.size()>12)
            return blocked(Status.INSUFFICIENT_DATA,Reason.INSUFFICIENT_HISTORY,DataQuality.MISSING);
        var first=points.getFirst();var scope=first.scope();var base=first.evidence();
        Instant previousEnd=null;boolean partial=false;
        for(var p:points){
            var e=p.evidence();var period=e.period();
            if(!scope.equals(p.scope())||e.metric()!=scope.metric()||e.methodVersion()!=scope.methodVersion()
                ||scope.methodVersion()!=DerivedMetricEngine.METHOD_VERSION
                ||!Objects.equals(e.currency(),scope.currency())||!Objects.equals(e.unit(),scope.unit())
                ||period.calendarVersion()!=scope.calendarVersion()
                ||!Objects.equals(period.zoneId(),base.period().zoneId())
                ||e.sourceRevision()!=base.sourceRevision()||!e.observedAt().equals(base.observedAt())
                ||(e.route()==AuthorizedAnalyticalReadService.Route.L3&&(e.generation()==null||e.proof()==null))
                ||(e.route()==AuthorizedAnalyticalReadService.Route.L0&&(e.generation()!=null||e.proof()!=null)))
                return blocked(Status.CONFLICTING_EVIDENCE,Reason.INCOMPATIBLE_EVIDENCE,DataQuality.STALE);
            var calendar=new PeriodResolver.Policy(ZoneId.of(period.zoneId()),period.calendarVersion());
            if(!period.equals(PeriodResolver.resolve(scope.code(),period.startInclusive().atZone(calendar.zone()).toLocalDate(),calendar))
                ||(previousEnd!=null&&!previousEnd.equals(period.startInclusive())))
                return blocked(Status.CONFLICTING_EVIDENCE,Reason.INCOMPATIBLE_EVIDENCE,DataQuality.NOT_MEASURABLE);
            previousEnd=period.endExclusive();
            if(period.endExclusive().isAfter(e.observedAt()))
                return blocked(Status.INSUFFICIENT_DATA,Reason.OPEN_PERIOD,DataQuality.PARTIAL);
            if(e.quality()==DataQuality.STALE||e.quality()==DataQuality.NOT_MEASURABLE)
                return blocked(Status.INSUFFICIENT_DATA,Reason.UNQUALIFIED_HISTORY,e.quality());
            if(e.quality()==DataQuality.MISSING||e.value()==null)
                return blocked(Status.INSUFFICIENT_DATA,Reason.MISSING_OBSERVATION,DataQuality.MISSING);
            if(e.value().signum()<0)
                return blocked(Status.CONFLICTING_EVIDENCE,Reason.INCOMPATIBLE_EVIDENCE,DataQuality.NOT_MEASURABLE);
            partial|=e.quality()==DataQuality.PARTIAL;
        }
        var prior=points.get(points.size()-2).evidence().value();
        var last=points.getLast().evidence().value();
        // Descriptive difference only; not a second rolling-window or forecast engine.
        BigDecimal delta=last.subtract(prior);
        BigDecimal growth=prior.signum()>0?delta.divide(prior,MathContext.DECIMAL128):null;
        if(partial)return new Outcome(Status.INSUFFICIENT_DATA,Reason.UNQUALIFIED_HISTORY,DataQuality.PARTIAL,
            delta,growth,List.of(),false,DecisionEligibility.BLOCKED);
        boolean material=delta.signum()!=0&&delta.abs().compareTo(policy.absoluteThreshold())>=0
            &&(prior.signum()==0||delta.abs().compareTo(prior.multiply(policy.relativeThreshold()))>=0);
        if(!material)return new Outcome(Status.NO_MATERIAL_CHANGE,Reason.NO_MATERIAL_CHANGE,DataQuality.READY,
            delta,growth,List.of(),false,DecisionEligibility.BLOCKED);
        if(delta.signum()<0&&scope.sku()!=null&&scope.metric()==AuthorizedAnalyticalReadService.Metric.FULFILLED_PRODUCT_UNITS)
            return new Outcome(Status.AMBIGUOUS,Reason.CAUSE_UNDETERMINED,DataQuality.READY,delta,growth,
                List.of(Hypothesis.DEMAND_DECLINE,Hypothesis.STOCKOUT_CONSTRAINT),false,DecisionEligibility.BLOCKED);
        return new Outcome(Status.OBSERVED_CHANGE,Reason.CHANGE_OBSERVED,DataQuality.READY,delta,growth,
            List.of(),false,DecisionEligibility.BLOCKED);
    }
    private static Outcome blocked(Status status,Reason reason,DataQuality quality){
        return new Outcome(status,reason,quality,null,null,List.of(),false,DecisionEligibility.BLOCKED);
    }
}
