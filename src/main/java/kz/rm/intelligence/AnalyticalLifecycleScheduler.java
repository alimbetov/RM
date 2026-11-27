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
    // Admission is serialized with shutdown, never with the compute step.
    private final Object admission=new Object();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),r->{Thread t=new Thread(r,"rm-intelligence-night");t.setDaemon(true);return t;});
    private int cursor;
    private int intradayCursor;
    private IntradayWorker intraday;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void configureIntraday(IntradayWorker worker){this.intraday=worker;}
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
        for(int i=0;i<Math.min(intraday==null?50:25,pilots.size());i++){
            if(stopping.get())return;
            var pilot=pilots.get(cursor);
            cursor=(cursor+1)%pilots.size();
            try{
                synchronized(admission){
                    if(stopping.get())return;
                    var plan=service.planNight(pilot.getKey(),pilot.getValue());
                    counts.merge(plan.state(),1,Integer::sum);
                }
            }catch(RuntimeException ex){
                failures++;
                log.warn("RM analytical planning failed tenant={} type={}",pilot.getKey(),ex.getClass().getSimpleName());
            }
        }
        if(intraday!=null){
            var tenants=intraday.tenants();
            for(int i=0;i<Math.min(25,tenants.size());i++){
                if(stopping.get())return;
                UUID tenant=tenants.get(intradayCursor);
                intradayCursor=(intradayCursor+1)%tenants.size();
                try{synchronized(admission){if(stopping.get())return;intraday.plan(tenant);}}
                catch(RuntimeException ex){log.warn("RM INTRADAY planning failed tenant={} type={}",tenant,ex.getClass().getSimpleName());}
            }
        }
        log.info("RM analytical planning outcomes={} failures={}",counts,failures);
        if(executionEnabled&&!stopping.get()){
            try{
                Optional<AnalyticalJobRepository.Claim> claimed;
                synchronized(admission){
                    if(stopping.get())return;
                    claimed=intraday==null?service.claimNext(worker,scope,limits):
                        service.claimNext(worker,scope,Set.copyOf(intraday.tenants()),limits);
                }
                if(claimed.isEmpty())return;
                var claim=claimed.get();
                if(stopping.get()){
                    service.releaseUnstarted(claim);
                    return;
                }
                long started=System.nanoTime();
                try{
                    var result="NIGHT".equals(claim.type())?service.runNight(claim,limits):
                        Objects.requireNonNull(intraday).run(claim);
                    log.info("RM analytical {} tenant={} job={} generation={} token={} slot={} result={} elapsedMs={}",
                        claim.type(),claim.tenant(),claim.job(),claim.generation(),claim.token(),claim.slot(),result,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                }catch(RuntimeException ex){
                    log.warn("RM analytical NIGHT tenant={} job={} generation={} elapsedMs={} type={}",
                        claim.tenant(),claim.job(),claim.generation(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),
                        ex.getClass().getSimpleName());
                }
            }catch(RuntimeException ex){log.warn("RM analytical worker failed type={}",ex.getClass().getSimpleName());}
        }
    }
    @PreDestroy void stop(){
        stopping.set(true);
        // Wait only for a short admitted discovery/claim to exit. A compute
        // step never holds this monitor; uncertain work keeps its lease.
        synchronized(admission){executor.shutdown();}
        // Never release a lease for a still-running step or interrupt its DB
        // transaction. A hard process stop leaves expiry/takeover to PostgreSQL.
        try{
            if(!executor.awaitTermination(10,TimeUnit.SECONDS))
                log.warn("RM analytical worker still running at shutdown; lease recovery applies");
        }catch(InterruptedException ex){Thread.currentThread().interrupt();}
    }
}
