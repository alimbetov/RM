package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** B5.1 internal application port. Only current authorized evidence, no user-supplied observations. */
@Service
public class BusinessObservationService {
    private final AuthorizedAnalyticalReadService analytical;
    BusinessObservationService(AuthorizedAnalyticalReadService analytical){this.analytical=analytical;}
    public record Insight(UUID tenant,UUID actor,AuthorizedAnalyticalReadService.Query query,
                          int policyVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold,
                          List<AuthorizedAnalyticalReadService.Result> evidence,String status,String reason,
                          DataQuality quality,BigDecimal delta,BigDecimal growth,List<String> hypotheses,
                          boolean sustainedTrend,String decisionEligibility){
        public Insight {evidence=List.copyOf(evidence);hypotheses=List.copyOf(hypotheses);}
    }
    public record Trend(int policyVersion,String direction,String reason,DataQuality quality,String activity,
                        int periods,int readyPeriods,BigDecimal slope,BigDecimal movingAverage,
                        boolean sustainedTrend,String decisionEligibility){}
    public record SeriesInsight(Insight observation,Trend trend){}
    public record Anomaly(int policyVersion,BigDecimal sigmaMultiplier,BigDecimal maxBaselineCv,
                          String pattern,String reason,DataQuality quality,int baselinePeriods,
                          BigDecimal actual,BigDecimal sma,BigDecimal ema,BigDecimal stddev,BigDecimal cv,
                          BigDecimal baselineSlope,BigDecimal deviation,BigDecimal standardizedDeviation,
                          boolean anomalyCandidate,String decisionEligibility){}
    public record PatternInsight(SeriesInsight series,Anomaly anomaly){}
    public PatternInsight observePattern(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query query,
                           List<LocalDate> anchors,int policyVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold,
                           int anomalyPolicyVersion,BigDecimal sigmaMultiplier,BigDecimal maxBaselineCv){
        var anomalyPolicy=new BusinessAnomalyAssessment.Policy(anomalyPolicyVersion,sigmaMultiplier,maxBaselineCv);
        var series=observeSeries(actor,tenant,query,anchors,policyVersion,absoluteThreshold,relativeThreshold);
        var observation=series.observation();
        var points=observation.evidence().stream().map(e->new BusinessInterpretationEngine.Point(
            new BusinessInterpretationEngine.Scope(tenant,observation.actor(),query.metric(),query.calendarVersion(),query.period(),
                query.region(),query.group(),query.sku(),query.warehouses(),query.currency(),e.unit(),e.methodVersion()),e)).toList();
        var result=BusinessAnomalyAssessment.evaluate(points,new BusinessInterpretationEngine.Policy(
            policyVersion,absoluteThreshold,relativeThreshold),anomalyPolicy);
        return new PatternInsight(series,new Anomaly(result.policyVersion(),sigmaMultiplier,maxBaselineCv,
            result.pattern().name(),result.reason().name(),result.quality(),result.baselinePeriods(),result.actual(),
            result.sma(),result.ema(),result.stddev(),result.cv(),result.baselineSlope(),result.deviation(),
            result.standardizedDeviation(),result.anomalyCandidate(),result.decisionEligibility()));
    }
    public Insight observe(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query query,
                           List<LocalDate> anchors,int policyVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold){
        return observeSeries(actor,tenant,query,anchors,policyVersion,absoluteThreshold,relativeThreshold).observation();
    }
    public SeriesInsight observeSeries(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query query,
                           List<LocalDate> anchors,int policyVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold){
        var policy=new BusinessInterpretationEngine.Policy(policyVersion,absoluteThreshold,relativeThreshold);
        var dates=List.copyOf(anchors);
        if(dates.size()<2||dates.size()>12||!dates.getLast().equals(query.anchor()))
            throw new IllegalArgumentException("OBSERVATION_WINDOW");
        var evidence=analytical.readWindow(actor,tenant,query,dates);
        UUID actorId=actor.userId();
        var points=evidence.stream().map(e->new BusinessInterpretationEngine.Point(
            new BusinessInterpretationEngine.Scope(tenant,actorId,query.metric(),query.calendarVersion(),query.period(),
                query.region(),query.group(),query.sku(),query.warehouses(),query.currency(),e.unit(),e.methodVersion()),e)).toList();
        var result=BusinessInterpretationEngine.evaluate(points,policy);
        var observation=new Insight(tenant,actorId,query,policyVersion,absoluteThreshold,relativeThreshold,evidence,
            result.status().name(),result.reason().name(),result.quality(),result.delta(),result.growth(),
            result.hypotheses().stream().map(Enum::name).toList(),result.sustainedTrend(),result.decisionEligibility().name());
        var trend=BusinessTrendAssessment.evaluate(points,policy,result);
        return new SeriesInsight(observation,new Trend(trend.policyVersion(),trend.direction().name(),trend.reason().name(),
            trend.quality(),trend.activity(),trend.periods(),trend.readyPeriods(),trend.slope(),trend.movingAverage(),
            trend.sustainedTrend(),trend.decisionEligibility()));
    }
}
