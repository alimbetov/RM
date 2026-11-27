package kz.rm.intelligence;

import java.time.LocalDate;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in bounded NIGHT planning. No automatic claim, replay or activation until R1.2 admission. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="rm.intelligence.scheduler",name="enabled",havingValue="true")
class AnalyticalLifecycleScheduler {
    private static final Logger log=LoggerFactory.getLogger(AnalyticalLifecycleScheduler.class);
    private final AnalyticalJobService service;
    private final List<Map.Entry<UUID,LocalDate>> pilots;
    private int cursor;
    AnalyticalLifecycleScheduler(AnalyticalJobService service,
            @Value("${rm.intelligence.scheduler.pilot-scopes:}") String scopes){
        this.service=service;
        var parsed=new TreeMap<UUID,LocalDate>();
        // Explicit tenant UUID=approved start date; no implicit tenant discovery.
        if(scopes.isBlank())throw new IllegalArgumentException("INT_JOB_PILOT_REQUIRED");
        for(String item:scopes.split(",",-1)){
            String[] fields=item.strip().split("=",-1);
            if(fields.length!=2||parsed.putIfAbsent(UUID.fromString(fields[0].strip()),
                LocalDate.parse(fields[1].strip()))!=null)
                throw new IllegalArgumentException("INT_JOB_PILOT_INVALID");
        }
        if(parsed.size()>1000)throw new IllegalArgumentException("INT_JOB_PILOT_LIMIT");
        pilots=List.copyOf(parsed.entrySet());
    }
    @Scheduled(fixedDelay=60000,initialDelayString="#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(1000,10001)}")
    public synchronized void tick(){
        var counts=new EnumMap<AnalyticalJobRepository.PlanState,Integer>(AnalyticalJobRepository.PlanState.class);
        int failures=0;
        for(int i=0;i<Math.min(50,pilots.size());i++){
            var pilot=pilots.get(cursor);
            cursor=(cursor+1)%pilots.size();
            try{
                var plan=service.planNight(pilot.getKey(),pilot.getValue());
                counts.merge(plan.state(),1,Integer::sum);
            }catch(RuntimeException ex){
                failures++;
                log.warn("RM analytical planning failed tenant={} type={}",pilot.getKey(),ex.getClass().getSimpleName());
            }
        }
        log.info("RM analytical planning outcomes={} failures={}",counts,failures);
    }
}
