package kz.rm.sales;

import kz.rm.RmApplication;
import kz.rm.iam.OidcActorResolver;
import kz.rm.iam.UserAccountRepository;
import kz.rm.iam.VerifiedActor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import kz.rm.iam.SignedJwtTestAuthority;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
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
        String jwks,bearer;
        try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
            jwks=input.readLine();bearer=input.readLine();
        }
        if(jwks==null||bearer==null||bearer.isBlank())
            throw new IllegalStateException("Signed worker identity input missing");
        try(var app=new SpringApplicationBuilder(RmApplication.class)
                .web(WebApplicationType.SERVLET).profiles("test")
                .run("--server.port=-1")){
            JdbcTemplate jdbc=app.getBean(JdbcTemplate.class);
            VerifiedActor principal;
            try{
                var decoder=SignedJwtTestAuthority.decoder(URI.create(jwks));
                principal=new OidcActorResolver(decoder,app.getBean(UserAccountRepository.class)).resolve(bearer);
            }catch(RuntimeException failure){
                throw new IllegalStateException("Signed worker identity denied");
            }
            if(!principal.userId().equals(staff))
                throw new SecurityException("Signed worker actor mismatch");
            System.out.println("RET04E child signed identity pid="+ProcessHandle.current().pid()+" actor="+principal.userId());
            System.out.flush();
            if(mode.startsWith("RESOLVE_")){
                var resolver=app.getBean(SalesReturnQuarantineResolutionService.class);
                var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
                boolean restock=mode.startsWith("RESOLVE_RESTOCK_");
                var action=restock?SalesReturnQuarantineResolutionService.Action.RESTOCK:
                        SalesReturnQuarantineResolutionService.Action.DISPOSE;
                String phase=mode.substring(restock?"RESOLVE_RESTOCK_".length():"RESOLVE_DISPOSE_".length());
                if(!java.util.Set.of("HALT_BEFORE_COMMIT","HALT_AFTER_COMMIT","HOLD","NORMAL").contains(phase))
                    throw new IllegalArgumentException("Unknown resolution phase");
                try {
                    var result=tx.execute(status->{
                        int pid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                        if("NORMAL".equals(phase))writeSignal(ready,Integer.toString(pid));
                        var out=resolver.resolve(principal,tenant,warehouse,returnId,version,
                                action,"QA_B_EVIDENCE",key);
                        if(!out.state().equals(restock?"RESTOCKED":"DISPOSED")||out.version()!=7
                                ||out.resolutionId()==null||restock!=(out.stockMovementId()!=null))
                            throw new IllegalStateException("RET04C_RESOLUTION_INCOMPLETE");
                        if("HALT_BEFORE_COMMIT".equals(phase)){
                            System.out.println("RET04C_PRECOMMIT pid="+pid+" "+out);
                            System.out.flush();
                            Runtime.getRuntime().halt(86);
                        }
                        if("HOLD".equals(phase)){
                            writeSignal(ready,Integer.toString(pid));
                            awaitSignal(release);
                        }
                        return out;
                    });
                    System.out.println("RET04C_COMMITTED "+result);
                    System.out.flush();
                    if("HALT_AFTER_COMMIT".equals(phase))Runtime.getRuntime().halt(87);
                }catch(IllegalStateException denied){
                    if(!"QUARANTINE_REPLAY_CONFLICT".equals(denied.getMessage())
                            ||!"NORMAL".equals(phase))throw denied;
                    System.out.println("RET04C_DENIED QUARANTINE_REPLAY_CONFLICT");
                    System.out.flush();
                    System.exit(88);
                }
                return;
            }
            if(!java.util.Set.of("POST_NORMAL","POST_HALT_BEFORE_COMMIT","POST_HALT_AFTER_COMMIT").contains(mode))
                throw new IllegalArgumentException("Unknown worker mode");
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
    private static void writeSignal(Path path,String value){
        if("-".equals(path.toString()))return;
        try{
            Path tmp=path.resolveSibling(path.getFileName()+".tmp");
            Files.writeString(tmp,value);
            Files.move(tmp,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }

    private static void awaitSignal(Path path){
        long cutoff=System.nanoTime()+TimeUnit.SECONDS.toNanos(100);
        while(!Files.exists(path)){
            if(System.nanoTime()>cutoff)throw new IllegalStateException("RET04C_COMMIT_GATE_TIMEOUT");
            try{Thread.sleep(20);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        }
    }
}
