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
