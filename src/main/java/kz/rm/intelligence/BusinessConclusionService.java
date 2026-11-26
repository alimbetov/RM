package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** B5.5: read-only conclusions bound to one authorized evidence window. */
@Service
public class BusinessConclusionService {
    private final BusinessObservationService observations;
    private final BusinessFactorService factors;
    BusinessConclusionService(BusinessObservationService observations,BusinessFactorService factors){
        this.observations=observations;this.factors=factors;
    }
    public enum Lane { OBSERVATION,TREND,ANOMALY,FACTOR,CAUSE }
    public enum Disposition { CONCLUDED,ABSTAINED }
    public enum Code { INCREASE,DECREASE,NO_MATERIAL_CHANGE,UP,DOWN,FLAT,MIXED,SPIKE,DROP,NO_ANOMALY,
        VOLATILE_BASELINE,OBSERVED_FACTOR_CHANGE,AMBIGUOUS_FACTORS,UNQUALIFIED_EVIDENCE,
        INCOMPATIBLE_EVIDENCE,INCOMPARABLE_PERIOD_LENGTH,CAUSE_NOT_ESTABLISHED }
    public enum DecisionEligibility { BLOCKED }
    public enum MeasureCode { DELTA,GROWTH,SLOPE,MOVING_AVERAGE,ACTUAL,SMA,DEVIATION,FULFILLED_DELTA,STOCK_IN_DELTA,STOCK_OUT_DELTA }
    public enum Check { VERIFY_SOURCE_COVERAGE,VERIFY_STOCK_AVAILABILITY,VERIFY_UNFULFILLED_DEMAND,
        VERIFY_PRICE_AND_PROMOTION,VERIFY_SOURCE_REVISION }
    public record Measure(MeasureCode code,BigDecimal value){}
    public record Finding(Lane lane,Disposition disposition,Code code,DataQuality quality,String sourceReason,
                          List<Measure> measures,List<Check> checks){
        public Finding {measures=List.copyOf(measures);checks=List.copyOf(checks);}
    }
    public record Policy(int materialityVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold,
                         Integer anomalyVersion,BigDecimal sigmaMultiplier,BigDecimal maxBaselineCv){}
    public record Evidence(AuthorizedAnalyticalReadService.Query query,List<AuthorizedAnalyticalReadService.Result> points){
        public Evidence {points=List.copyOf(points);}
    }
    public record Conclusion(int version,UUID tenant,UUID actor,Policy policy,List<Evidence> evidence,
                             List<Finding> findings,DecisionEligibility decisionEligibility){
        public Conclusion {evidence=List.copyOf(evidence);findings=List.copyOf(findings);}
    }

    public Conclusion concludePattern(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query query,
            List<LocalDate> anchors,int policyVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold,
            int anomalyPolicyVersion,BigDecimal sigmaMultiplier,BigDecimal maxBaselineCv){
        var p=observations.observePattern(actor,tenant,query,anchors,policyVersion,absoluteThreshold,relativeThreshold,
            anomalyPolicyVersion,sigmaMultiplier,maxBaselineCv);
        var o=p.series().observation();var t=p.series().trend();var a=p.anomaly();
        var evidence=List.of(new Evidence(o.query(),o.evidence()));
        var findings=new ArrayList<Finding>();
        if(o.quality()!=DataQuality.READY || o.status().equals("CONFLICTING_EVIDENCE")){
            findings.add(abstain(Lane.OBSERVATION,o.quality(),o.reason()));
        }else if(!equalLastDuration(o.evidence())){
            findings.add(new Finding(Lane.OBSERVATION,Disposition.ABSTAINED,Code.INCOMPARABLE_PERIOD_LENGTH,
                DataQuality.NOT_MEASURABLE,"INCOMPARABLE_PERIOD_LENGTH",List.of(),List.of()));
        }else{
            var code=switch(BusinessInterpretationEngine.Status.valueOf(o.status())){
                case NO_MATERIAL_CHANGE -> Code.NO_MATERIAL_CHANGE;
                case AMBIGUOUS,OBSERVED_CHANGE -> o.delta().signum()>0?Code.INCREASE:Code.DECREASE;
                default -> throw new IllegalStateException("B55_OBSERVATION_STATUS");
            };
            findings.add(concluded(Lane.OBSERVATION,code,o.reason(),measures(
                new Measure(MeasureCode.DELTA,o.delta()),new Measure(MeasureCode.GROWTH,o.growth())),List.of()));
        }
        if(t.quality()==DataQuality.READY && !t.direction().equals("UNAVAILABLE"))
            findings.add(concluded(Lane.TREND,Code.valueOf(t.direction()),t.reason(),measures(
                new Measure(MeasureCode.SLOPE,t.slope()),new Measure(MeasureCode.MOVING_AVERAGE,t.movingAverage())),List.of()));
        else findings.add(abstain(Lane.TREND,t.quality(),t.reason()));
        if(a.quality()==DataQuality.READY && !a.pattern().equals("UNAVAILABLE"))
            findings.add(concluded(Lane.ANOMALY,Code.valueOf(a.pattern()),a.reason(),measures(
                new Measure(MeasureCode.ACTUAL,a.actual()),new Measure(MeasureCode.SMA,a.sma()),
                new Measure(MeasureCode.DEVIATION,a.deviation())),List.of()));
        else findings.add(abstain(Lane.ANOMALY,a.quality(),a.reason()));
        findings.add(cause());
        return new Conclusion(1,o.tenant(),o.actor(),new Policy(o.policyVersion(),o.absoluteThreshold(),o.relativeThreshold(),
            a.policyVersion(),a.sigmaMultiplier(),a.maxBaselineCv()),evidence,findings,DecisionEligibility.BLOCKED);
    }

    public Conclusion concludeFactors(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query salesQuery,
            LocalDate prior,int policyVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold){
        var f=factors.observe(actor,tenant,salesQuery,prior,policyVersion,absoluteThreshold,relativeThreshold);
        var evidence=f.evidence().stream().map(e->new Evidence(e.query(),e.observations())).toList();
        var checks=f.recommendedChecks().stream().map(Check::valueOf).toList();
        Finding finding;
        if(f.quality()!=DataQuality.READY || f.status().equals("CONFLICTING_EVIDENCE")){
            var blocked=abstain(Lane.FACTOR,f.quality(),f.reason());
            finding=new Finding(blocked.lane(),blocked.disposition(),blocked.code(),blocked.quality(),
                blocked.sourceReason(),List.of(),checks);
        }else{
            var code=switch(BusinessFactorAssessment.Status.valueOf(f.status())){
                case NO_MATERIAL_CHANGE -> Code.NO_MATERIAL_CHANGE;
                case AMBIGUOUS -> Code.AMBIGUOUS_FACTORS;
                case OBSERVED_CHANGE -> Code.OBSERVED_FACTOR_CHANGE;
                default -> throw new IllegalStateException("B55_FACTOR_STATUS");
            };
            finding=concluded(Lane.FACTOR,code,f.reason(),measures(
                new Measure(MeasureCode.FULFILLED_DELTA,f.fulfilledDelta()),new Measure(MeasureCode.STOCK_IN_DELTA,f.stockInDelta()),
                new Measure(MeasureCode.STOCK_OUT_DELTA,f.stockOutDelta())),checks);
        }
        return new Conclusion(1,f.tenant(),f.actor(),new Policy(f.policyVersion(),f.absoluteThreshold(),f.relativeThreshold(),
            null,null,null),evidence,List.of(finding,cause()),DecisionEligibility.BLOCKED);
    }
    private static boolean equalLastDuration(List<AuthorizedAnalyticalReadService.Result> points){
        if(points.size()<2)return false;
        var a=points.get(points.size()-2).period();var b=points.getLast().period();
        return Duration.between(a.startInclusive(),a.endExclusive()).equals(Duration.between(b.startInclusive(),b.endExclusive()));
    }
    private static List<Measure> measures(Measure... values){
        return Arrays.stream(values).filter(v->v.value()!=null).toList();
    }
    private static Finding concluded(Lane lane,Code code,String reason,List<Measure> measures,List<Check> checks){
        return new Finding(lane,Disposition.CONCLUDED,code,DataQuality.READY,reason,measures,checks);
    }
    private static Finding abstain(Lane lane,DataQuality quality,String reason){
        Code code=switch(reason){
            case "INCOMPATIBLE_EVIDENCE","INCOMPATIBLE_SCOPE_OR_SNAPSHOT" -> Code.INCOMPATIBLE_EVIDENCE;
            case "INCOMPARABLE_PERIOD_LENGTH" -> Code.INCOMPARABLE_PERIOD_LENGTH;
            default -> Code.UNQUALIFIED_EVIDENCE;
        };
        return new Finding(lane,Disposition.ABSTAINED,code,quality==DataQuality.READY?DataQuality.NOT_MEASURABLE:quality,
            reason,List.of(),List.of());
    }
    private static Finding cause(){
        return new Finding(Lane.CAUSE,Disposition.ABSTAINED,Code.CAUSE_NOT_ESTABLISHED,
            DataQuality.NOT_MEASURABLE,"CAUSE_NOT_ESTABLISHED",List.of(),List.of());
    }
}
