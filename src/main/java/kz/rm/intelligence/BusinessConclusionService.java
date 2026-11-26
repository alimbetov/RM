package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** B5.5R: one authorized snapshot, typed composition of existing assessments. No new arithmetic engine. */
@Service
public class BusinessConclusionService {
    static final int VERSION=2;
    private final AuthorizedAnalyticalReadService reader;
    BusinessConclusionService(AuthorizedAnalyticalReadService reader){this.reader=reader;}
    public enum Lane { OBSERVATION,TREND,ANOMALY,FACTOR,CAUSE }
    public enum Disposition { CONCLUDED,ABSTAINED }
    public enum Code { INCREASE,DECREASE,NO_MATERIAL_CHANGE,UP,DOWN,FLAT,MIXED,SPIKE,DROP,NO_ANOMALY,
        VOLATILE_BASELINE,OBSERVED_FACTOR_CHANGE,AMBIGUOUS_FACTORS,UNQUALIFIED_EVIDENCE,
        INCOMPATIBLE_EVIDENCE,INCOMPARABLE_PERIOD_LENGTH,CAUSE_NOT_ESTABLISHED,MIXED_ROUTE_UNQUALIFIED,
        METRIC_SCOPE_UNQUALIFIED,INSUFFICIENT_HISTORY,INCOMPLETE_COVERAGE,MISSING_OBSERVATION,OPEN_PERIOD,
        STALE_REVISION,NOT_MEASURABLE,ZERO_BASELINE,ZERO_DISPERSION }
    public enum DecisionEligibility { BLOCKED }
    public enum Activity { ACTIVE,INSUFFICIENT_HISTORY,PERIOD_GAP,PARTIAL_COVERAGE,OPEN_PERIOD,STALE,INVALID,NOT_APPLICABLE,DISABLED,NOT_EVALUATED }
    public enum Hypothesis { DEMAND_DECLINE,STOCKOUT_CONSTRAINT,AVAILABILITY_CONSTRAINT,OTHER_FACTORS }
    public enum MeasureCode { DELTA,GROWTH,SLOPE,MOVING_AVERAGE,ACTUAL,SMA,EMA,STDDEV,CV,BASELINE_SLOPE,
        DEVIATION,STANDARDIZED_DEVIATION,FULFILLED_DELTA,STOCK_IN_DELTA,STOCK_OUT_DELTA }
    public enum Check { VERIFY_SOURCE_COVERAGE,VERIFY_STOCK_AVAILABILITY,VERIFY_UNFULFILLED_DEMAND,
        VERIFY_PRICE_AND_PROMOTION,VERIFY_SOURCE_REVISION }
    /** Zero-based series and half-open point index range within this conclusion only. */
    public record EvidenceRef(int series,int fromInclusive,int toExclusive){
        public EvidenceRef {if(series<0||fromInclusive<0||toExclusive<=fromInclusive)throw new IllegalArgumentException("B55_EVIDENCE_REF");}
    }
    public record Measure(MeasureCode code,BigDecimal value,String unit,String currency,List<EvidenceRef> refs){
        public Measure {Objects.requireNonNull(code);Objects.requireNonNull(value);Objects.requireNonNull(unit);refs=List.copyOf(refs);}
    }
    public record UnavailableMeasure(MeasureCode code,Code reason){}
    public record Finding(Lane lane,Disposition disposition,Code code,DataQuality quality,String sourceReason,
                          int assessmentVersion,Activity activity,List<EvidenceRef> refs,List<Measure> measures,
                          List<UnavailableMeasure> unavailableMeasures,List<Hypothesis> hypotheses,List<Check> checks){
        public Finding {refs=List.copyOf(refs);measures=List.copyOf(measures);unavailableMeasures=List.copyOf(unavailableMeasures);
            hypotheses=List.copyOf(hypotheses);checks=List.copyOf(checks);
            if(disposition==Disposition.ABSTAINED&&(!measures.isEmpty()||!hypotheses.isEmpty()))throw new IllegalArgumentException("B55_ABSTENTION_PAYLOAD");}
    }
    public record Policy(int materialityVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold,
                         Integer anomalyVersion,BigDecimal sigmaMultiplier,BigDecimal maxBaselineCv){}
    public record Evidence(AuthorizedAnalyticalReadService.Query query,List<AuthorizedAnalyticalReadService.Result> points){
        public Evidence {points=List.copyOf(points);}
    }
    public record Conclusion(int version,UUID tenant,UUID actor,Policy policy,List<Evidence> evidence,
                             List<Finding> findings,DecisionEligibility decisionEligibility){
        public Conclusion {Objects.requireNonNull(tenant);Objects.requireNonNull(actor);Objects.requireNonNull(policy);
            evidence=List.copyOf(evidence);findings=List.copyOf(findings);
            if(decisionEligibility!=DecisionEligibility.BLOCKED)throw new IllegalArgumentException("B55_DECISION");
            for(var f:findings){for(var ref:f.refs())validateRef(evidence,ref);
                for(var m:f.measures())for(var ref:m.refs())validateRef(evidence,ref);}}
        private static void validateRef(List<Evidence> evidence,EvidenceRef ref){
            if(ref.series()>=evidence.size()||ref.toExclusive()>evidence.get(ref.series()).points().size())
                throw new IllegalArgumentException("B55_EVIDENCE_REF");
        }
    }
    private record Guard(Code code,DataQuality quality){}
    private static Guard conflict(){return new Guard(Code.INCOMPATIBLE_EVIDENCE,DataQuality.STALE);}
    /** Never qualify a mixed route by revision equality alone. No physical-unit inference from ORDER_UNITS. */
    private static Guard guard(List<Evidence> evidence,List<LocalDate> dates){
        AuthorizedAnalyticalReadService.Result base=null;
        var firstQuery=evidence.getFirst().query();
        for(var series:evidence){
            var q=series.query();
            if(q.calendarVersion()!=firstQuery.calendarVersion()||q.period()!=firstQuery.period()||
                !q.anchor().equals(firstQuery.anchor())||!Objects.equals(q.region(),firstQuery.region())||
                !Objects.equals(q.group(),firstQuery.group())||!Objects.equals(q.sku(),firstQuery.sku())||
                !q.warehouses().equals(firstQuery.warehouses()))return conflict();
            if(series.points().size()!=dates.size())return conflict();
            var metric=L3DerivedSeriesService.Metric.valueOf(q.metric().name());
            for(int i=0;i<dates.size();i++){
                var e=series.points().get(i);
                if(e.period()==null||e.period().zoneId()==null||e.observedAt()==null||e.route()==null||e.quality()==null||
                    e.metric()!=q.metric()||e.methodVersion()!=DerivedMetricEngine.METHOD_VERSION||
                    !metric.unit.equals(e.unit())||!Objects.equals(q.currency(),e.currency())||
                    e.period().calendarVersion()!=q.calendarVersion())return conflict();
                try{
                    var calendar=new PeriodResolver.Policy(ZoneId.of(e.period().zoneId()),q.calendarVersion());
                    if(!PeriodResolver.resolve(q.period(),dates.get(i),calendar).equals(e.period()))return conflict();
                }catch(DateTimeException|IllegalArgumentException invalidCalendar){return conflict();}
                if(e.route()==AuthorizedAnalyticalReadService.Route.L3&&(e.generation()==null||e.proof()==null)||
                    e.route()==AuthorizedAnalyticalReadService.Route.L0&&(e.generation()!=null||e.proof()!=null))return conflict();
                if(base==null)base=e;
                else {
                    if(e.sourceRevision()!=base.sourceRevision()||!e.observedAt().equals(base.observedAt())||
                        !e.period().zoneId().equals(base.period().zoneId()))return conflict();
                    if(e.route()!=base.route())return new Guard(Code.MIXED_ROUTE_UNQUALIFIED,DataQuality.NOT_MEASURABLE);
                    if(!Objects.equals(e.generation(),base.generation())||!Objects.equals(e.proof(),base.proof()))return conflict();
                }
            }
            // Until owner UOM passport qualification, do not interpret heterogeneous order-unit totals.
            if(metric.unit.equals("ORDER_UNITS")&&q.sku()==null)
                return new Guard(Code.METRIC_SCOPE_UNQUALIFIED,DataQuality.NOT_MEASURABLE);
        }
        return null;
    }
    private static List<BusinessInterpretationEngine.Point> points(UUID tenant,UUID actor,Evidence series){
        var q=series.query();
        return series.points().stream().map(e->new BusinessInterpretationEngine.Point(
            new BusinessInterpretationEngine.Scope(tenant,actor,q.metric(),q.calendarVersion(),q.period(),
                q.region(),q.group(),q.sku(),q.warehouses(),q.currency(),e.unit(),e.methodVersion()),e)).toList();
    }
    public Conclusion concludePattern(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query query,
            List<LocalDate> anchors,int policyVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold,
            int anomalyPolicyVersion,BigDecimal sigmaMultiplier,BigDecimal maxBaselineCv){
        var p=new BusinessInterpretationEngine.Policy(policyVersion,absoluteThreshold,relativeThreshold);
        var ap=new BusinessAnomalyAssessment.Policy(anomalyPolicyVersion,sigmaMultiplier,maxBaselineCv);
        var dates=List.copyOf(anchors);
        if(dates.size()<2||dates.size()>12||!dates.getLast().equals(query.anchor()))throw new IllegalArgumentException("OBSERVATION_WINDOW");
        var raw=reader.readWindow(actor,tenant,query,dates);
        var actorId=actor.userId();var evidence=List.of(new Evidence(query,raw));
        var policy=new Policy(p.version(),p.absoluteThreshold(),p.relativeThreshold(),ap.version(),ap.sigmaMultiplier(),ap.maxBaselineCv());
        var refs=refs(evidence);var findings=new ArrayList<Finding>();var g=guard(evidence,dates);
        if(g!=null){
            findings.add(block(Lane.OBSERVATION,g.code(),g.quality(),g.code().name(),BusinessInterpretationEngine.VERSION,Activity.INVALID,refs,List.of()));
            findings.add(block(Lane.TREND,g.code(),g.quality(),g.code().name(),BusinessTrendAssessment.POLICY_VERSION,Activity.INVALID,refs,List.of()));
            findings.add(block(Lane.ANOMALY,g.code(),g.quality(),g.code().name(),BusinessAnomalyAssessment.VERSION,Activity.INVALID,refs,List.of()));
        }else{
            var all=points(tenant,actorId,evidence.getFirst());
            // The two-period descriptive method has its own mandatory evidence window.
            var last=all.subList(all.size()-2,all.size());var lastRef=List.of(new EvidenceRef(0,all.size()-2,all.size()));
            var o=BusinessInterpretationEngine.evaluate(last,p);
            if(o.quality()!=DataQuality.READY||o.status()==BusinessInterpretationEngine.Status.CONFLICTING_EVIDENCE)
                findings.add(block(Lane.OBSERVATION,observationReason(o),o.quality(),o.reason().name(),BusinessInterpretationEngine.VERSION,Activity.NOT_EVALUATED,lastRef,List.of()));
            else if(!equalLastDuration(raw))
                findings.add(block(Lane.OBSERVATION,Code.INCOMPARABLE_PERIOD_LENGTH,DataQuality.NOT_MEASURABLE,"INCOMPARABLE_PERIOD_LENGTH",BusinessInterpretationEngine.VERSION,Activity.NOT_APPLICABLE,lastRef,List.of()));
            else{
                var code=switch(o.status()){
                    case NO_MATERIAL_CHANGE -> Code.NO_MATERIAL_CHANGE;
                    case AMBIGUOUS,OBSERVED_CHANGE -> o.delta().signum()>0?Code.INCREASE:Code.DECREASE;
                    case INSUFFICIENT_DATA,CONFLICTING_EVIDENCE -> throw new IllegalStateException("B55_OBSERVATION_STATUS");
                };
                var m=new ArrayList<Measure>();var unavailable=new ArrayList<UnavailableMeasure>();
                add(m,MeasureCode.DELTA,o.delta(),evidence, lastRef,false);
                if(o.growth()==null)unavailable.add(new UnavailableMeasure(MeasureCode.GROWTH,Code.ZERO_BASELINE));
                else add(m,MeasureCode.GROWTH,o.growth(),evidence,lastRef,true);
                var hypotheses=o.hypotheses().stream().map(h->switch(h){
                    case DEMAND_DECLINE -> Hypothesis.DEMAND_DECLINE;case STOCKOUT_CONSTRAINT -> Hypothesis.STOCKOUT_CONSTRAINT;
                }).toList();
                findings.add(done(Lane.OBSERVATION,code,o.reason().name(),BusinessInterpretationEngine.VERSION,Activity.NOT_EVALUATED,lastRef,m,unavailable,hypotheses,List.of()));
            }
            var full=BusinessInterpretationEngine.evaluate(all,p);
            var t=BusinessTrendAssessment.evaluate(all,p,full);
            var activity=activity(t.activity());
            if(t.direction()==BusinessTrendAssessment.Direction.UNAVAILABLE||t.quality()!=DataQuality.READY||activity!=Activity.ACTIVE){
                var reason=switch(t.reason()){
                    case WINDOW_TOO_SHORT -> Code.INSUFFICIENT_HISTORY;
                    case INCOMPARABLE_PERIOD_LENGTH -> Code.INCOMPARABLE_PERIOD_LENGTH;
                    case INCOMPATIBLE_EVIDENCE -> Code.INCOMPATIBLE_EVIDENCE;
                    case WINDOW_NOT_QUALIFIED -> full.quality()!=DataQuality.READY?observationReason(full):qualityReason(t.quality());
                    case NO_MATERIAL_CHANGE,MONOTONIC_MATERIAL_CHANGE,NON_MONOTONIC_OR_IMMATERIAL_STEPS -> Code.NOT_MEASURABLE;
                };
                findings.add(block(Lane.TREND,reason,t.quality(),t.reason().name(),t.policyVersion(),activity,refs,List.of()));
            }else{
                var code=switch(t.direction()){
                    case UP -> Code.UP;case DOWN -> Code.DOWN;case FLAT -> Code.FLAT;case MIXED -> Code.MIXED;
                    case UNAVAILABLE -> throw new IllegalStateException("B55_TREND");
                };
                var m=new ArrayList<Measure>();add(m,MeasureCode.SLOPE,t.slope(),evidence,refs,false);add(m,MeasureCode.MOVING_AVERAGE,t.movingAverage(),evidence,refs,false);
                findings.add(done(Lane.TREND,code,t.reason().name(),t.policyVersion(),activity,refs,m,List.of(),List.of(),List.of()));
            }
            var a=BusinessAnomalyAssessment.evaluate(all,p,ap);
            if(a.pattern()==BusinessAnomalyAssessment.Pattern.UNAVAILABLE||a.quality()!=DataQuality.READY){
                var reason=switch(a.reason()){
                    case WINDOW_TOO_SHORT -> Code.INSUFFICIENT_HISTORY;
                    case INCOMPARABLE_PERIOD_LENGTH -> Code.INCOMPARABLE_PERIOD_LENGTH;
                    case INCOMPATIBLE_EVIDENCE -> Code.INCOMPATIBLE_EVIDENCE;
                    case ZERO_BASELINE -> Code.ZERO_BASELINE;
                    case WINDOW_NOT_QUALIFIED -> full.quality()!=DataQuality.READY?observationReason(full):qualityReason(a.quality());
                    case HIGH_BASELINE_VOLATILITY,IMMATERIAL_DEVIATION,WITHIN_BASELINE_BAND,OUTSIDE_BASELINE_BAND -> Code.NOT_MEASURABLE;
                };
                findings.add(block(Lane.ANOMALY,reason,a.quality(),a.reason().name(),a.policyVersion(),Activity.NOT_EVALUATED,refs,List.of()));
            }else{
                var code=switch(a.pattern()){
                    case SPIKE -> Code.SPIKE;case DROP -> Code.DROP;case VOLATILE_BASELINE -> Code.VOLATILE_BASELINE;
                    case NO_ANOMALY -> Code.NO_ANOMALY;case UNAVAILABLE -> throw new IllegalStateException("B55_ANOMALY");
                };
                var m=new ArrayList<Measure>();var unavailable=new ArrayList<UnavailableMeasure>();
                var history=List.of(new EvidenceRef(0,0,all.size()-1));var target=List.of(new EvidenceRef(0,all.size()-1,all.size()));
                add(m,MeasureCode.ACTUAL,a.actual(),evidence,target,false);add(m,MeasureCode.SMA,a.sma(),evidence,history,false);
                add(m,MeasureCode.EMA,a.ema(),evidence,history,false);add(m,MeasureCode.STDDEV,a.stddev(),evidence,history,false);
                add(m,MeasureCode.CV,a.cv(),evidence,history,true);add(m,MeasureCode.BASELINE_SLOPE,a.baselineSlope(),evidence,history,false);
                add(m,MeasureCode.DEVIATION,a.deviation(),evidence,refs,false);
                if(a.standardizedDeviation()==null)unavailable.add(new UnavailableMeasure(MeasureCode.STANDARDIZED_DEVIATION,Code.ZERO_DISPERSION));
                else add(m,MeasureCode.STANDARDIZED_DEVIATION,a.standardizedDeviation(),evidence,refs,true);
                findings.add(done(Lane.ANOMALY,code,a.reason().name(),a.policyVersion(),Activity.NOT_EVALUATED,refs,m,unavailable,List.of(),List.of()));
            }
        }
        findings.add(cause(refs));actor.userId();
        return new Conclusion(VERSION,tenant,actorId,policy,evidence,findings,DecisionEligibility.BLOCKED);
    }
    public Conclusion concludeFactors(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query q,
            LocalDate prior,int policyVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold){
        var p=new BusinessInterpretationEngine.Policy(policyVersion,absoluteThreshold,relativeThreshold);
        if(q.metric()!=AuthorizedAnalyticalReadService.Metric.FULFILLED_PRODUCT_UNITS||q.sku()==null||
            q.warehouses().isEmpty()||!prior.isBefore(q.anchor()))throw new IllegalArgumentException("FACTOR_SKU_SCOPE");
        var qs=List.of(q,withMetric(q,AuthorizedAnalyticalReadService.Metric.STOCK_IN),withMetric(q,AuthorizedAnalyticalReadService.Metric.STOCK_OUT));
        var dates=List.of(prior,q.anchor());var windows=reader.readMetricWindows(actor,tenant,qs,dates);var actorId=actor.userId();
        if(windows.size()!=qs.size())throw new IllegalStateException("B55_WINDOW_CARDINALITY");
        var evidence=new ArrayList<Evidence>();for(int i=0;i<qs.size();i++)evidence.add(new Evidence(qs.get(i),windows.get(i)));
        var refs=refs(evidence);var g=guard(evidence,dates);Finding finding;
        if(g!=null)finding=block(Lane.FACTOR,g.code(),g.quality(),g.code().name(),BusinessFactorAssessment.VERSION,Activity.INVALID,refs,List.of(Check.VERIFY_SOURCE_REVISION));
        else{
            var f=BusinessFactorAssessment.evaluate(points(tenant,actorId,evidence.get(0)),points(tenant,actorId,evidence.get(1)),points(tenant,actorId,evidence.get(2)),p);
            var checks=f.checks().stream().map(c->switch(c){
                case VERIFY_SOURCE_COVERAGE -> Check.VERIFY_SOURCE_COVERAGE;case VERIFY_STOCK_AVAILABILITY -> Check.VERIFY_STOCK_AVAILABILITY;
                case VERIFY_UNFULFILLED_DEMAND -> Check.VERIFY_UNFULFILLED_DEMAND;case VERIFY_PRICE_AND_PROMOTION -> Check.VERIFY_PRICE_AND_PROMOTION;
                case VERIFY_SOURCE_REVISION -> Check.VERIFY_SOURCE_REVISION;
            }).toList();
            if(f.quality()!=DataQuality.READY||f.status()==BusinessFactorAssessment.Status.CONFLICTING_EVIDENCE){
                var reason=switch(f.reason()){
                    case INCOMPATIBLE_SCOPE_OR_SNAPSHOT -> Code.INCOMPATIBLE_EVIDENCE;
                    case INCOMPARABLE_PERIOD_LENGTH -> Code.INCOMPARABLE_PERIOD_LENGTH;
                    case UNQUALIFIED_EVIDENCE -> evidence.stream().flatMap(e->e.points().stream())
                        .anyMatch(e->e.period().endExclusive().isAfter(e.observedAt()))?Code.OPEN_PERIOD:qualityReason(f.quality());
                    case NO_MATERIAL_CHANGE,FLOW_IS_NOT_AVAILABILITY,CAUSE_NOT_ESTABLISHED -> Code.NOT_MEASURABLE;
                };
                finding=block(Lane.FACTOR,reason,f.quality(),f.reason().name(),f.version(),Activity.NOT_EVALUATED,refs,checks);
            }else{
                var code=switch(f.status()){
                    case NO_MATERIAL_CHANGE -> Code.NO_MATERIAL_CHANGE;case AMBIGUOUS -> Code.AMBIGUOUS_FACTORS;
                    case OBSERVED_CHANGE -> Code.OBSERVED_FACTOR_CHANGE;
                    case INSUFFICIENT_DATA,CONFLICTING_EVIDENCE -> throw new IllegalStateException("B55_FACTOR_STATUS");
                };
                var m=new ArrayList<Measure>();add(m,MeasureCode.FULFILLED_DELTA,f.fulfilledDelta(),evidence,List.of(new EvidenceRef(0,0,2)),false);
                add(m,MeasureCode.STOCK_IN_DELTA,f.stockInDelta(),evidence,List.of(new EvidenceRef(1,0,2)),false);
                add(m,MeasureCode.STOCK_OUT_DELTA,f.stockOutDelta(),evidence,List.of(new EvidenceRef(2,0,2)),false);
                var hypotheses=f.hypotheses().stream().map(h->switch(h){
                    case DEMAND_DECLINE -> Hypothesis.DEMAND_DECLINE;case AVAILABILITY_CONSTRAINT -> Hypothesis.AVAILABILITY_CONSTRAINT;case OTHER_FACTORS -> Hypothesis.OTHER_FACTORS;
                }).toList();
                finding=done(Lane.FACTOR,code,f.reason().name(),f.version(),Activity.NOT_EVALUATED,refs,m,List.of(),hypotheses,checks);
            }
        }
        actor.userId();return new Conclusion(VERSION,tenant,actorId,new Policy(p.version(),p.absoluteThreshold(),p.relativeThreshold(),null,null,null),
            evidence,List.of(finding,cause(refs)),DecisionEligibility.BLOCKED);
    }
    private static AuthorizedAnalyticalReadService.Query withMetric(AuthorizedAnalyticalReadService.Query q,AuthorizedAnalyticalReadService.Metric m){
        return new AuthorizedAnalyticalReadService.Query(m,q.calendarVersion(),q.period(),q.anchor(),q.region(),q.group(),q.sku(),q.warehouses(),null);
    }
    private static List<EvidenceRef> refs(List<Evidence> evidence){
        var out=new ArrayList<EvidenceRef>();for(int i=0;i<evidence.size();i++)if(!evidence.get(i).points().isEmpty())out.add(new EvidenceRef(i,0,evidence.get(i).points().size()));
        return List.copyOf(out);
    }
    private static void add(List<Measure> out,MeasureCode code,BigDecimal value,List<Evidence> evidence,List<EvidenceRef> refs,boolean ratio){
        if(value==null)return;
        var e=evidence.get(refs.getFirst().series()).points().get(refs.getFirst().fromInclusive());
        String unit=ratio?"RATIO":switch(code){case SLOPE,BASELINE_SLOPE -> e.unit()+"_PER_PERIOD";default -> e.unit();};
        out.add(new Measure(code,value,unit,ratio?null:e.currency(),refs));
    }
    private static boolean equalLastDuration(List<AuthorizedAnalyticalReadService.Result> points){
        var a=points.get(points.size()-2).period();var b=points.getLast().period();
        return Duration.between(a.startInclusive(),a.endExclusive()).equals(Duration.between(b.startInclusive(),b.endExclusive()));
    }
    private static Code qualityReason(DataQuality q){return switch(q){
        case MISSING -> Code.MISSING_OBSERVATION;case PARTIAL -> Code.INCOMPLETE_COVERAGE;case STALE -> Code.STALE_REVISION;
        case NOT_MEASURABLE,READY -> Code.NOT_MEASURABLE;
    };}
    private static Code observationReason(BusinessInterpretationEngine.Outcome o){return switch(o.reason()){
        case INCOMPATIBLE_EVIDENCE -> Code.INCOMPATIBLE_EVIDENCE;case OPEN_PERIOD -> Code.OPEN_PERIOD;
        case MISSING_OBSERVATION -> Code.MISSING_OBSERVATION;case INSUFFICIENT_HISTORY -> Code.INSUFFICIENT_HISTORY;
        case UNQUALIFIED_HISTORY -> qualityReason(o.quality());
        case NO_MATERIAL_CHANGE,CHANGE_OBSERVED,CAUSE_UNDETERMINED -> Code.NOT_MEASURABLE;
    };}
    private static Activity activity(String a){return switch(a){
        case "ACTIVE" -> Activity.ACTIVE;case "INSUFFICIENT_HISTORY" -> Activity.INSUFFICIENT_HISTORY;
        case "PERIOD_GAP" -> Activity.PERIOD_GAP;case "PARTIAL_COVERAGE" -> Activity.PARTIAL_COVERAGE;
        case "OPEN_PERIOD" -> Activity.OPEN_PERIOD;case "STALE" -> Activity.STALE;case "INVALID" -> Activity.INVALID;
        case "NOT_APPLICABLE" -> Activity.NOT_APPLICABLE;case "DISABLED" -> Activity.DISABLED;
        default -> Activity.NOT_EVALUATED;
    };}
    private static Finding done(Lane lane,Code code,String source,int version,Activity activity,List<EvidenceRef> refs,
            List<Measure> measures,List<UnavailableMeasure> unavailable,List<Hypothesis> hypotheses,List<Check> checks){
        return new Finding(lane,Disposition.CONCLUDED,code,DataQuality.READY,source,version,activity,refs,measures,unavailable,hypotheses,checks);
    }
    private static Finding block(Lane lane,Code code,DataQuality q,String source,int version,Activity activity,List<EvidenceRef> refs,List<Check> checks){
        return new Finding(lane,Disposition.ABSTAINED,code,q==DataQuality.READY?DataQuality.NOT_MEASURABLE:q,source,version,activity,refs,List.of(),List.of(),List.of(),checks);
    }
    private static Finding cause(List<EvidenceRef> refs){
        return block(Lane.CAUSE,Code.CAUSE_NOT_ESTABLISHED,DataQuality.NOT_MEASURABLE,"CAUSE_NOT_ESTABLISHED",VERSION,Activity.NOT_EVALUATED,refs,List.of());
    }
}
