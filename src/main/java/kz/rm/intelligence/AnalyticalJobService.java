package kz.rm.intelligence;

import java.time.*;
import java.util.*;
import java.sql.SQLException;
import org.springframework.dao.TransientDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Internal one-step orchestration; existing generation engine owns every compute transaction. */
@Service
class AnalyticalJobService {
    record NightDue(Instant slot,LocalDate until) {}
    private static final Logger log=LoggerFactory.getLogger(AnalyticalJobService.class);
    private final AnalyticalJobRepository repository;
    private final GenerationRebuildService rebuild;
    private final MultidimensionalCoverageService coverage;
    AnalyticalJobService(AnalyticalJobRepository repository,GenerationRebuildService rebuild,
                         MultidimensionalCoverageService coverage){
        this.repository=repository;this.rebuild=rebuild;this.coverage=coverage;
    }
    enum RunResult { NO_WORK, YIELDED, RETRY, BLOCKED, COMPLETED, LEASE_LOST }
    record Limits(int cluster,int leaseSeconds,int facts,int periods){
        Limits{
            if(cluster<1||cluster>4||leaseSeconds<130||leaseSeconds>900||facts<1||facts>500||periods<1||periods>128)
                throw new IllegalArgumentException("INT_JOB_EXECUTION_LIMIT");
        }
    }

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
    RunResult runNext(UUID worker,Map<UUID,LocalDate> pilot,Limits limits){
        var claimed=repository.claimNext(worker,pilot,limits.cluster(),limits.leaseSeconds());
        if(claimed.isEmpty())return RunResult.NO_WORK;
        var c=claimed.get();
        return runNight(c,limits);
    }

    RunResult runNight(AnalyticalJobRepository.Claim c,Limits limits){
        try{
            if(repository.renew(c,limits.leaseSeconds()).isEmpty())return RunResult.LEASE_LOST;
            var progress=rebuild.step(new GenerationRebuildService.Request(
                c.tenant(),c.generation(),c.calendar(),c.from(),c.until()),limits.facts(),limits.periods());
            // A lost attempt cannot begin another step or publish. A bounded
            // BUILDING step already in flight may finish; receipts are durable.
            if(repository.renew(c,limits.leaseSeconds()).isEmpty())return RunResult.LEASE_LOST;
            return switch(progress.state()){
                case REPLAY_PENDING,ROLLUP_PENDING -> repository.yieldProgress(c)?RunResult.YIELDED:RunResult.LEASE_LOST;
                case STALE -> repository.retry(c,AnalyticalJobRepository.Failure.STALE_PROOF)?RunResult.RETRY:RunResult.LEASE_LOST;
                case SOURCE_INCOMPLETE -> repository.block(c,AnalyticalJobRepository.Failure.SOURCE_INCOMPLETE)?RunResult.BLOCKED:RunResult.LEASE_LOST;
                case READY -> {
                    coverage.activateScheduled(c,Objects.requireNonNull(progress.proof()),repository);
                    yield RunResult.COMPLETED;
                }
            };
        }catch(RuntimeException ex){
            // All compute/activation transactions have already unwound.
            // Unknown failures are blocked, not retried forever.
            String code=ex.getMessage();
            log.warn("RM NIGHT step failed tenant={} job={} type={}",c.tenant(),c.job(),ex.getClass().getSimpleName());
            if("INT_JOB_LEASE_LOST".equals(code)||"INT_JOB_COMPLETION_FENCE".equals(code))
                return RunResult.LEASE_LOST;
            if("INT_ACTIVATION_PROOF_NOT_CURRENT".equals(code)||"INT_ACTIVATION_PROOF_STALE".equals(code)
                    ||"INT_ACTIVATION_POSTCHECK".equals(code))
                return repository.retry(c,AnalyticalJobRepository.Failure.STALE_PROOF)?RunResult.RETRY:RunResult.LEASE_LOST;
            if(transientFailure(ex))
                return repository.retry(c,AnalyticalJobRepository.Failure.TRANSIENT)?RunResult.RETRY:RunResult.LEASE_LOST;
            return repository.block(c,AnalyticalJobRepository.Failure.EXECUTION_REJECTED)?RunResult.BLOCKED:RunResult.LEASE_LOST;
        }
    }
    private static boolean transientFailure(RuntimeException ex){
        if(ex instanceof TransientDataAccessException)return true;
        for(Throwable t=ex;t!=null;t=t.getCause()){
            if(t instanceof SQLException sql){
                String state=sql.getSQLState();
                if(state!=null&&(state.startsWith("08")||Set.of("55P03","40001","40P01","57014").contains(state)))return true;
            }
        }
        return false;
    }

}
