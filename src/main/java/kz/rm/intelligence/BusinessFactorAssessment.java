package kz.rm.intelligence;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** B5.4: compare observed fulfilled SKU quantity and stock movement; flows are not availability. */
final class BusinessFactorAssessment {
    private BusinessFactorAssessment(){}
    static final int VERSION=1;
    enum Status { INSUFFICIENT_DATA, CONFLICTING_EVIDENCE, NO_MATERIAL_CHANGE, AMBIGUOUS, OBSERVED_CHANGE }
    enum Reason { UNQUALIFIED_EVIDENCE, INCOMPATIBLE_SCOPE_OR_SNAPSHOT, INCOMPARABLE_PERIOD_LENGTH,
        NO_MATERIAL_CHANGE, FLOW_IS_NOT_AVAILABILITY, CAUSE_NOT_ESTABLISHED }
    enum Hypothesis { DEMAND_DECLINE, AVAILABILITY_CONSTRAINT, OTHER_FACTORS }
    enum Check { VERIFY_SOURCE_COVERAGE, VERIFY_STOCK_AVAILABILITY, VERIFY_UNFULFILLED_DEMAND,
        VERIFY_PRICE_AND_PROMOTION, VERIFY_SOURCE_REVISION }
    record Result(int version,Status status,Reason reason,DataQuality quality,BigDecimal fulfilledDelta,
                  BigDecimal stockInDelta,BigDecimal stockOutDelta,List<Hypothesis> hypotheses,
                  List<Check> checks,String decisionEligibility){
        Result {hypotheses=List.copyOf(hypotheses);checks=List.copyOf(checks);}
    }
    static Result evaluate(List<BusinessInterpretationEngine.Point> sales,List<BusinessInterpretationEngine.Point> stockIn,
                           List<BusinessInterpretationEngine.Point> stockOut,BusinessInterpretationEngine.Policy policy){
        var series=List.of(List.copyOf(sales),List.copyOf(stockIn),List.copyOf(stockOut));
        var metrics=List.of(AuthorizedAnalyticalReadService.Metric.FULFILLED_PRODUCT_UNITS,
            AuthorizedAnalyticalReadService.Metric.STOCK_IN,AuthorizedAnalyticalReadService.Metric.STOCK_OUT);
        if(series.stream().anyMatch(s->s.size()!=2))
            return blocked(Status.INSUFFICIENT_DATA,Reason.UNQUALIFIED_EVIDENCE,DataQuality.MISSING,Check.VERIFY_SOURCE_COVERAGE);
        var base=series.getFirst().getFirst();
        UUID generation=null,proof=null;
        for(int i=0;i<3;i++){
            var points=series.get(i);
            for(int k=0;k<2;k++){
                var p=points.get(k);var s=p.scope();var e=p.evidence();var b=base.scope();
                var corresponding=series.getFirst().get(k).evidence();
                if(s.metric()!=metrics.get(i)||e.metric()!=metrics.get(i)||b.sku()==null||b.warehouses().isEmpty()
                    ||!s.tenant().equals(b.tenant())||!s.actor().equals(b.actor())
                    ||s.calendarVersion()!=b.calendarVersion()||s.code()!=b.code()
                    ||s.methodVersion()!=b.methodVersion()||!Objects.equals(s.region(),b.region())
                    ||!Objects.equals(s.group(),b.group())||!Objects.equals(s.sku(),b.sku())
                    ||!s.warehouses().equals(b.warehouses())||s.currency()!=null||e.currency()!=null
                    ||!e.period().equals(corresponding.period())||e.sourceRevision()!=base.evidence().sourceRevision()
                    ||!e.observedAt().equals(base.evidence().observedAt()))
                    return blocked(Status.CONFLICTING_EVIDENCE,Reason.INCOMPATIBLE_SCOPE_OR_SNAPSHOT,DataQuality.STALE,Check.VERIFY_SOURCE_REVISION);
                if(e.route()==AuthorizedAnalyticalReadService.Route.L3){
                    if(e.generation()==null||e.proof()==null||generation!=null&&(!generation.equals(e.generation())||!proof.equals(e.proof())))
                        return blocked(Status.CONFLICTING_EVIDENCE,Reason.INCOMPATIBLE_SCOPE_OR_SNAPSHOT,DataQuality.STALE,Check.VERIFY_SOURCE_REVISION);
                    generation=e.generation();proof=e.proof();
                }
            }
        }
        var first=base.evidence().period();var second=sales.getLast().evidence().period();
        if(!Duration.between(first.startInclusive(),first.endExclusive()).equals(Duration.between(second.startInclusive(),second.endExclusive())))
            return blocked(Status.INSUFFICIENT_DATA,Reason.INCOMPARABLE_PERIOD_LENGTH,DataQuality.NOT_MEASURABLE,Check.VERIFY_SOURCE_COVERAGE);
        var outcomes=series.stream().map(s->BusinessInterpretationEngine.evaluate(s,policy)).toList();
        if(outcomes.stream().anyMatch(o->o.status()==BusinessInterpretationEngine.Status.CONFLICTING_EVIDENCE))
            return blocked(Status.CONFLICTING_EVIDENCE,Reason.INCOMPATIBLE_SCOPE_OR_SNAPSHOT,DataQuality.STALE,Check.VERIFY_SOURCE_REVISION);
        for(var o:outcomes)if(o.quality()!=DataQuality.READY)
            return blocked(Status.INSUFFICIENT_DATA,Reason.UNQUALIFIED_EVIDENCE,o.quality(),Check.VERIFY_SOURCE_COVERAGE);
        var sale=outcomes.getFirst();var in=outcomes.get(1).delta();var out=outcomes.get(2).delta();
        if(sale.status()==BusinessInterpretationEngine.Status.NO_MATERIAL_CHANGE)
            return new Result(VERSION,Status.NO_MATERIAL_CHANGE,Reason.NO_MATERIAL_CHANGE,DataQuality.READY,
                sale.delta(),in,out,List.of(),List.of(),"BLOCKED");
        if(sale.delta().signum()<0)
            return new Result(VERSION,Status.AMBIGUOUS,Reason.FLOW_IS_NOT_AVAILABILITY,DataQuality.READY,
                sale.delta(),in,out,List.of(Hypothesis.DEMAND_DECLINE,Hypothesis.AVAILABILITY_CONSTRAINT,Hypothesis.OTHER_FACTORS),
                List.of(Check.VERIFY_STOCK_AVAILABILITY,Check.VERIFY_UNFULFILLED_DEMAND,Check.VERIFY_PRICE_AND_PROMOTION),"BLOCKED");
        return new Result(VERSION,Status.OBSERVED_CHANGE,Reason.CAUSE_NOT_ESTABLISHED,DataQuality.READY,
            sale.delta(),in,out,List.of(),List.of(Check.VERIFY_PRICE_AND_PROMOTION),"BLOCKED");
    }
    private static Result blocked(Status status,Reason reason,DataQuality quality,Check check){
        return new Result(VERSION,status,reason,quality,null,null,null,List.of(),List.of(check),"BLOCKED");
    }
}
