package kz.rm.sales;

import kz.rm.RmApplication;
import kz.rm.iam.OidcActorResolver;
import kz.rm.iam.UserAccountRepository;
import kz.rm.iam.VerifiedActor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Test-only separate JVM worker for real process exit / multi-JVM QR-06B.
 * Never packaged into production: src/test/java.
 * Arguments: mode tenant warehouse staff orderVersion proofFile requestKey readyPath gatePath.
 * No raw credential or PII is written to logs or artifacts.
 */
public final class QrHandoverProcessWorker {
    private QrHandoverProcessWorker() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 9) throw new IllegalArgumentException("expected nine arguments");
        String mode=args[0];
        UUID tenant=UUID.fromString(args[1]),warehouse=UUID.fromString(args[2]),
                staffId=UUID.fromString(args[3]);
        long version=Long.parseLong(args[4]);
        String proof=Files.readString(Path.of(args[5])).trim(), requestKey=args[6];
        Path ready=Path.of(args[7]),gate=Path.of(args[8]);

        try(var context=new SpringApplicationBuilder(RmApplication.class)
                .web(WebApplicationType.SERVLET).profiles("test")
                .run("--server.port=-1")) {
            JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);
            String subject=jdbc.queryForObject(
                    "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                    String.class,staffId);
            JwtDecoder decoder=token->Jwt.withTokenValue(token)
                    .header("alg","RS256").subject(subject)
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
            VerifiedActor staff=new OidcActorResolver(
                    decoder,context.getBean(UserAccountRepository.class))
                    .resolve("synthetic-qr06b-process");
            SalesPickupHandoverService pickup=context.getBean(SalesPickupHandoverService.class);
            if(!"-".equals(ready)) {
                Files.writeString(ready,"READY");
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                while(!Files.exists(gate)) {
                    if(System.nanoTime()>deadline) System.exit(92);
                    Thread.sleep(10);
                }
            }
            // B3.4: step-load arrival schedule provided by a private manifest.
            // One task per uniquely fulfillable order, stage-specific outcomes
            // and Hikari active/idle/waiting pool snapshots during workload.
            if("B34_STEPPED".equals(mode)) {
                record Case(String proof,long version,String key,long dueMs,String phase) {}
                var cases=new java.util.ArrayList<Case>();
                for(String line:Files.readAllLines(Path.of(args[5]))) {
                    String[] fields=line.split("\\|",-1);
                    if(fields.length!=5)throw new IllegalArgumentException("B34 manifest line");
                    cases.add(new Case(fields[0],Long.parseLong(fields[1]),fields[2],
                            Long.parseLong(fields[3]),fields[4]));
                }
                if(cases.isEmpty()||cases.size()>250)
                    throw new IllegalArgumentException("B34 workload size");
                var hikari=context.getBean(com.zaxxer.hikari.HikariDataSource.class);
                var bean=hikari.getHikariPoolMXBean();
                var results=new java.util.concurrent.ConcurrentLinkedQueue<String>();
                var failures=new java.util.concurrent.atomic.AtomicInteger();
                var readyToRun=new java.util.concurrent.CountDownLatch(cases.size());
                var start=new java.util.concurrent.CountDownLatch(1);
                var peakActive=new java.util.concurrent.atomic.AtomicInteger();
                var peakWaiting=new java.util.concurrent.atomic.AtomicInteger();
                var peakConnections=new java.util.concurrent.atomic.AtomicInteger();
                long wall;
                try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
                    var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                    for(var item:cases) futures.add(executor.submit(()->{
                        readyToRun.countDown();
                        if(!start.await(20,java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("B34 worker barrier");
                        long until=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(item.dueMs());
                        while(System.nanoTime()<until)
                            java.util.concurrent.locks.LockSupport.parkNanos(
                                    Math.min(5_000_000L,Math.max(1,until-System.nanoTime())));
                        long begin=System.nanoTime();
                        String outcome="COMPLETED";
                        try {
                            var done=pickup.complete(staff,tenant,warehouse,item.proof(),true,
                                    item.version(),item.key());
                            if(!"COMPLETED".equals(done.status()))
                                throw new IllegalStateException("Noncompleted B34 response");
                        }catch(IllegalStateException | SecurityException business){
                            outcome="BUSINESS_REJECTED";
                        }catch(Exception technical){
                            outcome="TECHNICAL_FAILURE";
                            failures.incrementAndGet();
                        }
                        results.add(item.phase()+","+outcome+","+
                                java.util.concurrent.TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-begin));
                        return null;
                    }));
                    if(!readyToRun.await(30,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("B34 all clients not ready");
                    long begin=System.nanoTime();
                    start.countDown();
                    long lastDue=cases.stream().mapToLong(Case::dueMs).max().orElseThrow();
                    long monitorDeadline=begin+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(lastDue+2500);
                    while(System.nanoTime()<monitorDeadline){
                        if(bean!=null){
                            peakActive.accumulateAndGet(bean.getActiveConnections(),Math::max);
                            peakWaiting.accumulateAndGet(bean.getThreadsAwaitingConnection(),Math::max);
                            peakConnections.accumulateAndGet(bean.getTotalConnections(),Math::max);
                        }
                        Thread.sleep(20);
                    }
                    for(var f:futures)f.get(90,java.util.concurrent.TimeUnit.SECONDS);
                    wall=System.nanoTime()-begin;
                }finally{
                    start.countDown();
                }
                var groups=new java.util.TreeMap<String,java.util.List<Long>>();
                for(String value:results) {
                    String[] fields=value.split(",",-1);
                    groups.computeIfAbsent(fields[0]+":"+fields[1],ignored->new java.util.ArrayList<>())
                            .add(Long.parseLong(fields[2]));
                }
                for(var group:groups.entrySet()){
                    var measured=group.getValue();
                    measured.sort(Long::compareTo);
                    long p95=measured.get(Math.max(0,(int)Math.ceil(measured.size()*.95)-1));
                    long p99=measured.get(Math.max(0,(int)Math.ceil(measured.size()*.99)-1));
                    System.out.printf(java.util.Locale.ROOT,
                            "QR06B_B34_CHILD tenant=%s phase_outcome=%s count=%d "+
                            "p95_us=%d p99_us=%d%n",
                            tenant,group.getKey(),measured.size(),p95,p99);
                }
                System.out.printf(java.util.Locale.ROOT,
                        "QR06B_B34_POOL tenant=%s total=%d technical_failures=%d "+
                        "duration_ms=%d peak_active=%d peak_waiting=%d peak_connections=%d%n",
                        tenant,results.size(),failures.get(),
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(wall),
                        peakActive.get(),peakWaiting.get(),peakConnections.get());
                if(failures.get()!=0||results.size()!=cases.size())System.exit(93);
                System.exit(0);
            }
            // B3.3: a fixed-duration pace with independent successful orders
            // loaded from a private test-only manifest. No HTTP/controller.
            if("B33_PACED".equals(mode)) {
                var lines=Files.readAllLines(Path.of(args[5]));
                if(lines.isEmpty()||lines.size()>100)
                    throw new IllegalArgumentException("Invalid B33 manifest");
                long first=System.nanoTime();
                var latencies=new java.util.ArrayList<Long>();
                int success=0;
                for(int i=0;i<lines.size();i++) {
                    long due=first+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(i*1500L);
                    while(System.nanoTime()<due)
                        java.util.concurrent.locks.LockSupport.parkNanos(
                                Math.min(5_000_000L,Math.max(1,due-System.nanoTime())));
                    String[] fields=lines.get(i).split("\\|",-1);
                    if(fields.length!=3)
                        throw new IllegalArgumentException("Malformed B33 manifest record");
                    long began=System.nanoTime();
                    var completed=pickup.complete(staff,tenant,warehouse,fields[0],true,
                            Long.parseLong(fields[1]),fields[2]);
                    if(!"COMPLETED".equals(completed.status()))
                        throw new IllegalStateException("B33_HANDOVER_NOT_COMPLETED");
                    latencies.add(System.nanoTime()-began);
                    success++;
                }
                latencies.sort(Long::compareTo);
                long elapsedMs=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime()-first);
                int p95Index=Math.max(0,(int)Math.ceil(.95*latencies.size())-1);
                int p99Index=Math.max(0,(int)Math.ceil(.99*latencies.size())-1);
                System.out.printf(java.util.Locale.ROOT,
                        "QR06B_B33_CHILD tenant=%s success=%d technical_errors=0 duration_ms=%d "+
                        "p95_success_ms=%d p99_success_ms=%d%n",
                        tenant,success,elapsedMs,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(latencies.get(p95Index)),
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(latencies.get(p99Index)));
                System.exit(0);
            }
            // B3.1 distributed contention: each worker is an independent JVM and
            // releases virtual clients only after the shared inter-process gate.
            if(mode.startsWith("LOAD_")) {
                int clients=Integer.parseInt(mode.substring(5));
                if(clients<1||clients>125)throw new IllegalArgumentException("Invalid JVM load");
                java.util.concurrent.CountDownLatch allReady=new java.util.concurrent.CountDownLatch(clients);
                java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
                long started;
                int success=0,rejected=0;
                try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
                    for(int n=0;n<clients;n++) {
                        final String localKey=requestKey+"_"+n;
                        futures.add(pool.submit(()->{
                            allReady.countDown();
                            if(!start.await(25,java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("LOAD_START_TIMEOUT");
                            try {
                                pickup.complete(staff,tenant,warehouse,proof,true,version,localKey);
                                return true;
                            }catch(IllegalStateException | SecurityException denied) {
                                return false;
                            }
                        }));
                    }
                    if(!allReady.await(25,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("LOAD_ALL_READY_TIMEOUT");
                    started=System.nanoTime();
                    start.countDown();
                    for(var future:futures) {
                        if(future.get(90,java.util.concurrent.TimeUnit.SECONDS)) success++;
                        else rejected++;
                    }
                }finally {
                    start.countDown();
                }
                if(success>1||success+rejected!=clients)
                    throw new IllegalStateException("LOAD_INVARIANT_FAILED");
                System.out.printf(java.util.Locale.ROOT,
                        "QR06B_B31_JVM worker_pid=%d clients=%d success=%d rejected=%d elapsed_ms=%d%n",
                        ProcessHandle.current().pid(),clients,success,rejected,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                System.exit(0);
            }
            switch(mode) {
                case "HALT_BEFORE_COMMIT" -> {
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                            .execute(tx->{
                                pickup.complete(staff,tenant,warehouse,proof,true,version,requestKey);
                                // Intentional hard exit while DB transaction remains uncommitted.
                                Runtime.getRuntime().halt(86);
                                return null;
                            });
                    throw new IllegalStateException("hard exit did not occur");
                }
                case "HALT_AFTER_COMMIT" -> {
                    // complete() owns its transaction: return implies successful COMMIT.
                    pickup.complete(staff,tenant,warehouse,proof,true,version,requestKey);
                    // Simulate caller never receiving a successful response.
                    Runtime.getRuntime().halt(87);
                }
                case "COMPETE" -> {
                    try {
                        pickup.complete(staff,tenant,warehouse,proof,true,version,requestKey);
                        System.exit(0);
                    } catch(IllegalStateException | SecurityException denied) {
                        System.exit(2);
                    }
                }
                default -> throw new IllegalArgumentException("unsupported mode");
            }
        }
    }
}
