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
    public Insight observe(VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query query,
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
        return new Insight(tenant,actorId,query,policyVersion,absoluteThreshold,relativeThreshold,evidence,
            result.status().name(),result.reason().name(),result.quality(),result.delta(),result.growth(),
            result.hypotheses().stream().map(Enum::name).toList(),result.sustainedTrend(),result.decisionEligibility().name());
    }
}
