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
import java.util.concurrent.TimeUnit;

/** Test-only independent process for QR07 stock posting crash and duplicate scans. */
public final class QrReturnProcessWorker {
    private QrReturnProcessWorker() {}

    public static void main(String[] args) throws Exception {
        if(args.length!=9)throw new IllegalArgumentException("Expected nine arguments");
        String mode=args[0];
        UUID tenant=UUID.fromString(args[1]),warehouse=UUID.fromString(args[2]),
             staff=UUID.fromString(args[3]),returnId=UUID.fromString(args[4]);
        long version=Long.parseLong(args[5]);
        String key=args[6];
        Path ready=Path.of(args[7]),release=Path.of(args[8]);
        try(var app=new SpringApplicationBuilder(RmApplication.class)
                .web(WebApplicationType.SERVLET).profiles("test")
                .run("--server.port=-1")){
            JdbcTemplate jdbc=app.getBean(JdbcTemplate.class);
            String subject=jdbc.queryForObject("""
                    SELECT external_subject FROM public.iam_user_account WHERE id=?
                    """,String.class,staff);
            JwtDecoder decoder=t->Jwt.withTokenValue(t).header("alg","RS256")
                    .subject(subject).issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(600)).build();
            VerifiedActor principal=new OidcActorResolver(
                    decoder,app.getBean(UserAccountRepository.class))
                    .resolve("synthetic-return-process");
            SalesReturnPhysicalService returns=app.getBean(SalesReturnPhysicalService.class);
            if(!"-".equals(ready.toString())){
                Files.writeString(ready,"READY");
                long cutoff=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
                while(!Files.exists(release)){
                    if(System.nanoTime()>cutoff)System.exit(92);
                    Thread.sleep(10);
                }
            }
            if("POST_HALT_BEFORE_COMMIT".equals(mode)){
                var outer=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
                outer.executeWithoutResult(status->{
                    returns.post(principal,tenant,warehouse,returnId,version,key);
                    Runtime.getRuntime().halt(86);
                });
            }else {
                var result=returns.post(principal,tenant,warehouse,returnId,version,key);
                if(!"RESTOCKED".equals(result.state()))
                    throw new IllegalStateException("RETURN_PROCESS_POST_INCOMPLETE");
                if("POST_HALT_AFTER_COMMIT".equals(mode))
                    Runtime.getRuntime().halt(87);
                System.out.println("QR07_R2_JVM_POST_OK");
            }
        }
    }
}
