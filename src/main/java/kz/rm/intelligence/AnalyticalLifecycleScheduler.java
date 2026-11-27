package kz.rm.intelligence;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in NIGHT planning and separately gated execution. One task per pod, no queue. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="rm.intelligence.scheduler",name="enabled",havingValue="true")
class AnalyticalLifecycleScheduler {
    private static final Logger log=LoggerFactory.getLogger(AnalyticalLifecycleScheduler.class);
    private final AnalyticalJobService service;
    private final List<Map.Entry<UUID,LocalDate>> pilots;
    private final Map<UUID,LocalDate> scope;
    private final boolean executionEnabled;
    private final AnalyticalJobService.Limits limits;
    private final UUID worker=UUID.randomUUID();
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),r->{Thread t=new Thread(r,"rm-intelligence-night");t.setDaemon(true);return t;});
    private int cursor;
    AnalyticalLifecycleScheduler(AnalyticalJobService service,
            @Value("${rm.intelligence.scheduler.pilot-scopes:}") String scopes,
            @Value("${rm.intelligence.scheduler.execution-enabled:false}") boolean executionEnabled,
            @Value("${rm.intelligence.scheduler.cluster-limit:2}") int cluster,
            @Value("${rm.intelligence.scheduler.lease-seconds:300}") int leaseSeconds,
            @Value("${rm.intelligence.scheduler.fact-budget:100}") int facts,
            @Value("${rm.intelligence.scheduler.period-budget:16}") int periods){
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
        scope=Map.copyOf(parsed);
        this.executionEnabled=executionEnabled;
        limits=new AnalyticalJobService.Limits(cluster,leaseSeconds,facts,periods);
    }
    @Scheduled(fixedDelay=60000,initialDelayString="#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(1000,10001)}")
    public void tick(){
        if(stopping.get())return;
        try{executor.execute(this::boundedTick);}
        catch(RejectedExecutionException ignored){/* busy or stopping: no queued claim */}
    }
    private void boundedTick(){
        if(stopping.get())return;
        var counts=new EnumMap<AnalyticalJobRepository.PlanState,Integer>(AnalyticalJobRepository.PlanState.class);
        int failures=0;
        for(int i=0;i<Math.min(50,pilots.size());i++){
            if(stopping.get())return;
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
        if(executionEnabled&&!stopping.get()){
            try{log.info("RM analytical NIGHT result={}",service.runNext(worker,scope,limits));}
            catch(RuntimeException ex){log.warn("RM analytical worker failed type={}",ex.getClass().getSimpleName());}
        }
    }
    @PreDestroy void stop(){
        stopping.set(true);
        executor.shutdown();
        // Never release a lease for a still-running step or interrupt its DB
        // transaction. A hard process stop leaves expiry/takeover to PostgreSQL.
        try{
            if(!executor.awaitTermination(10,TimeUnit.SECONDS))
                log.warn("RM analytical worker still running at shutdown; lease recovery applies");
        }catch(InterruptedException ex){Thread.currentThread().interrupt();}
    }
}
