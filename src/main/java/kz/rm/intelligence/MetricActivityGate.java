package kz.rm.intelligence;

import java.time.*;
import java.util.*;

/** Revision-relative activity, never a reusable authorization or production permit. */
final class MetricActivityGate {
    private MetricActivityGate(){}
    static final int POLICY_VERSION=1;
    enum Activity { ACTIVE, INSUFFICIENT_HISTORY, PERIOD_GAP, PARTIAL_COVERAGE, OPEN_PERIOD,
        STALE, INVALID, NOT_APPLICABLE, DISABLED }
    enum DecisionEligibility { BLOCKED, ELIGIBLE }
    record Assessment(int policyVersion,Activity activity,DecisionEligibility decisionEligibility,
                      int requiredPeriods,int availablePeriods,List<String> reasons){
        Assessment { reasons=List.copyOf(reasons); }
    }
    // Current shadow path never supplies qualified business coverage or production admission.
    static Assessment assess(PeriodResolver.Code code,LocalDate anchor,Instant asOf,
                             List<L3DerivedSeriesService.Observation> observations,
                             DerivedMetricEngine.Result result){
        Objects.requireNonNull(code);Objects.requireNonNull(anchor);Objects.requireNonNull(asOf);
        Objects.requireNonNull(observations);Objects.requireNonNull(result);
        int required=result.window();
        if(required<2||required>120||observations.size()!=required)
            return blocked(Activity.INVALID,required,0,"WINDOW_CARDINALITY");
        int available=0;
        boolean stale=false,partial=false,gap=false,leadingMissing=false,seenValue=false,open=false,invalid=false;
        Instant previousEnd=null;
        String zone=null;long calendar=-1;
        for(var o:observations){
            var p=o.period();
            if(p==null||p.startInclusive()==null||p.endExclusive()==null||
                !p.startInclusive().isBefore(p.endExclusive()))
                return blocked(Activity.INVALID,required,available,"INVALID_PERIOD");
            if(zone==null){zone=p.zoneId();calendar=p.calendarVersion();}
            if(!Objects.equals(zone,p.zoneId())||calendar!=p.calendarVersion())
                return blocked(Activity.INVALID,required,available,"CALENDAR_MISMATCH");
            var policy=new PeriodResolver.Policy(ZoneId.of(zone),calendar);
            if(!p.equals(PeriodResolver.resolve(code,p.startInclusive().atZone(policy.zone()).toLocalDate(),policy)))
                invalid=true;
            if(previousEnd!=null&&!previousEnd.equals(p.startInclusive()))gap=true;
            previousEnd=p.endExclusive();
            if(p.endExclusive().isAfter(asOf))open=true;
            if(o.quality()==DataQuality.STALE)stale=true;
            if(o.quality()==DataQuality.PARTIAL)partial=true;
            if(o.quality()==DataQuality.READY&&o.value()==null)invalid=true;
            if(o.value()==null||o.quality()==DataQuality.MISSING){
                if(seenValue)gap=true;else leadingMissing=true;
            }else{
                seenValue=true;
                if(o.quality()==DataQuality.READY)available++;
            }
        }
        var last=observations.get(required-1).period();
        if(!last.equals(PeriodResolver.resolve(code,anchor,new PeriodResolver.Policy(ZoneId.of(zone),calendar))))invalid=true;
        if(invalid)return blocked(Activity.INVALID,required,available,"INVALID_CALENDAR_OR_VALUE");
        if(open)return blocked(Activity.OPEN_PERIOD,required,available,"OPEN_OR_FUTURE_PERIOD");
        if(stale||result.quality()==DataQuality.STALE)return blocked(Activity.STALE,required,available,"STALE_REVISION");
        if(gap)return blocked(Activity.PERIOD_GAP,required,available,"PERIOD_GAP");
        if(leadingMissing)return blocked(Activity.INSUFFICIENT_HISTORY,required,available,"INSUFFICIENT_HISTORY");
        if(result.quality()==DataQuality.NOT_MEASURABLE)
            return blocked(Activity.NOT_APPLICABLE,required,available,Objects.toString(result.reason(),"NOT_MEASURABLE"));
        if(partial||available!=required||result.quality()!=DataQuality.READY||result.value()==null)
            return blocked(Activity.PARTIAL_COVERAGE,required,available,"QUALIFIED_COVERAGE_REQUIRED");
        // Even a mathematically active result is not a business decision permit.
        return blocked(Activity.ACTIVE,required,available,"PRODUCTION_DECISION_ADMISSION_REQUIRED");
    }
    private static Assessment blocked(Activity activity,int required,int available,String reason){
        return new Assessment(POLICY_VERSION,activity,DecisionEligibility.BLOCKED,required,available,List.of(reason));
    }
}
