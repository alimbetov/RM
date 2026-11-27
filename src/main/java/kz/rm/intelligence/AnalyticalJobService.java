package kz.rm.intelligence;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** R1.1 planning only; actual rebuild/activation remains R1.2. */
@Service
class AnalyticalJobService {
    record NightDue(Instant slot,LocalDate until) {}
    private final AnalyticalJobRepository repository;
    AnalyticalJobService(AnalyticalJobRepository repository){this.repository=repository;}

    static NightDue latestNight(Instant now,PeriodResolver.Policy policy){
        Objects.requireNonNull(now);Objects.requireNonNull(policy);
        LocalDate today=now.atZone(policy.zone()).toLocalDate();
        LocalDate boundary=today;
        Instant slot=boundary.atTime(0,15).atZone(policy.zone()).withEarlierOffsetAtOverlap().toInstant();
        if(slot.isAfter(now)){
            boundary=boundary.minusDays(1);
            slot=boundary.atTime(0,15).atZone(policy.zone()).withEarlierOffsetAtOverlap().toInstant();
        }
        // Reject a non-existent closed civil day instead of inventing coverage.
        var closed=PeriodResolver.resolve(PeriodResolver.Code.D,boundary.minusDays(1),policy);
        if(slot.isAfter(now)||closed.endExclusive().isAfter(now))
            throw new IllegalStateException("INT_JOB_NOT_CLOSED");
        return new NightDue(slot,boundary);
    }

    AnalyticalJobRepository.Plan planNight(UUID tenant,LocalDate approvedFrom){
        return repository.planNight(tenant,approvedFrom);
    }
}
