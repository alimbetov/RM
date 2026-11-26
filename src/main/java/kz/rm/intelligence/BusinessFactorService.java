package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Authorized factor evidence. No caller supplied facts, no business commands. */
@Service
public class BusinessFactorService {
    private final AuthorizedAnalyticalReadService analytical;
    BusinessFactorService(AuthorizedAnalyticalReadService analytical){this.analytical=analytical;}
    public record Evidence(AuthorizedAnalyticalReadService.Query query,List<AuthorizedAnalyticalReadService.Result> observations){
        public Evidence {observations=List.copyOf(observations);}
    }
    public record Insight(UUID tenant,UUID actor,int policyVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold,
                          List<Evidence> evidence,int factorVersion,String status,String reason,DataQuality quality,
                          BigDecimal fulfilledDelta,BigDecimal stockInDelta,BigDecimal stockOutDelta,
                          List<String> hypotheses,List<String> recommendedChecks,String decisionEligibility){
        public Insight {evidence=List.copyOf(evidence);hypotheses=List.copyOf(hypotheses);recommendedChecks=List.copyOf(recommendedChecks);}
    }
    public Insight observe(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query salesQuery,LocalDate prior,
                           int policyVersion,BigDecimal absoluteThreshold,BigDecimal relativeThreshold){
        var policy=new BusinessInterpretationEngine.Policy(policyVersion,absoluteThreshold,relativeThreshold);
        if(salesQuery.metric()!=AuthorizedAnalyticalReadService.Metric.FULFILLED_PRODUCT_UNITS||salesQuery.sku()==null
            ||salesQuery.warehouses().isEmpty()||!prior.isBefore(salesQuery.anchor()))
            throw new IllegalArgumentException("FACTOR_SKU_SCOPE");
        var queries=List.of(salesQuery,withMetric(salesQuery,AuthorizedAnalyticalReadService.Metric.STOCK_IN),
            withMetric(salesQuery,AuthorizedAnalyticalReadService.Metric.STOCK_OUT));
        var windows=analytical.readMetricWindows(actor,tenant,queries,List.of(prior,salesQuery.anchor()));
        UUID actorId=actor.userId();
        var evidence=new ArrayList<Evidence>();
        var points=new ArrayList<List<BusinessInterpretationEngine.Point>>();
        for(int i=0;i<queries.size();i++){
            var q=queries.get(i);var results=windows.get(i);
            evidence.add(new Evidence(q,results));
            points.add(results.stream().map(e->new BusinessInterpretationEngine.Point(
                new BusinessInterpretationEngine.Scope(tenant,actorId,q.metric(),q.calendarVersion(),q.period(),
                    q.region(),q.group(),q.sku(),q.warehouses(),q.currency(),e.unit(),e.methodVersion()),e)).toList());
        }
        var result=BusinessFactorAssessment.evaluate(points.get(0),points.get(1),points.get(2),policy);
        return new Insight(tenant,actorId,policyVersion,absoluteThreshold,relativeThreshold,evidence,result.version(),
            result.status().name(),result.reason().name(),result.quality(),result.fulfilledDelta(),result.stockInDelta(),
            result.stockOutDelta(),result.hypotheses().stream().map(Enum::name).toList(),
            result.checks().stream().map(Enum::name).toList(),result.decisionEligibility());
    }
    private static AuthorizedAnalyticalReadService.Query withMetric(AuthorizedAnalyticalReadService.Query q,
            AuthorizedAnalyticalReadService.Metric metric){
        return new AuthorizedAnalyticalReadService.Query(metric,q.calendarVersion(),q.period(),q.anchor(),q.region(),
            q.group(),q.sku(),q.warehouses(),null);
    }
}
