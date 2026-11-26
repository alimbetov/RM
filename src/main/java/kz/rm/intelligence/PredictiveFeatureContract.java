package kz.rm.intelligence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.List;

/** Internal candidate feature envelope. Deliberately cannot admit training or production inference.
 * A current materialized snapshot is not evidence of historical availability. */
final class PredictiveFeatureContract {
    private PredictiveFeatureContract(){}
    static final int VERSION=1;
    enum Availability { CURRENT_SNAPSHOT_ONLY, HISTORICAL_AVAILABILITY_UNPROVEN }
    record Candidate(int contractVersion,int numericPolicyVersion,int derivedMethodVersion,
                     UUID tenant,UUID generation,L3DerivedSeriesService.Metric metric,
                     L3DerivedSeriesService.Filter filter,L3DerivedSeriesService.Revision revision,
                     PeriodResolver.Code periodCode,LocalDate anchor,int window,
                     Instant observedAt,Availability availability,DataQuality quality,
                     List<L3DerivedSeriesService.Observation> inputs,
                     DerivedMetricEngine.Operator operator,BigDecimal value,MetricActivityGate.Assessment activity){
        Candidate {
            Objects.requireNonNull(tenant);Objects.requireNonNull(generation);
            Objects.requireNonNull(metric);Objects.requireNonNull(filter);Objects.requireNonNull(revision);
            Objects.requireNonNull(periodCode);Objects.requireNonNull(anchor);Objects.requireNonNull(observedAt);
            Objects.requireNonNull(availability);Objects.requireNonNull(quality);Objects.requireNonNull(operator);Objects.requireNonNull(activity);
            inputs=List.copyOf(inputs);
            if(contractVersion!=VERSION || numericPolicyVersion!=DerivedNumericParity.VERSION ||
                derivedMethodVersion!=DerivedMetricEngine.METHOD_VERSION || window<2 || window>120 ||
                quality==DataQuality.READY || inputs.size()!=window ||
                activity.policyVersion()!=MetricActivityGate.POLICY_VERSION || activity.requiredPeriods()!=window ||
                activity.decisionEligibility()!=MetricActivityGate.DecisionEligibility.BLOCKED)
                throw new IllegalArgumentException("PREDICTIVE_CANDIDATE_CONTRACT");
            if(inputs.stream().anyMatch(p->p.period().endExclusive().isAfter(observedAt)))
                throw new IllegalArgumentException("PREDICTIVE_FUTURE_PERIOD");
        }
        boolean eligibleForHistoricalTraining(){return false;}
        boolean eligibleForProductionInference(){return false;}
    }
    static Candidate candidate(L3DerivedSeriesService.Series series,DerivedMetricEngine.Operator operator){
        Objects.requireNonNull(series);
        var result=Objects.requireNonNull(series.derived().get(operator));
        return new Candidate(VERSION,DerivedNumericParity.VERSION,result.methodVersion(),
            series.tenant(),series.generation(),series.metric(),series.filter(),series.revision(),
            series.periodCode(),series.anchor(),result.window(),series.observedAt(),Availability.CURRENT_SNAPSHOT_ONLY,
            result.quality(),series.facts(),operator,result.value(),Objects.requireNonNull(series.activity().get(operator)));
    }
}
