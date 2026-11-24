package kz.rm.sales;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Test-only deterministic two-transaction schedule. Proves an actual PG
 * blocking edge, not just a slow future. Unexpected exceptions fail the test. */
final class PgCommitOrderEvidence {
    private PgCommitOrderEvidence(){}
    static void run(JdbcTemplate jdbc,PlatformTransactionManager manager,
            Runnable first,Runnable second,String expectedDenial) throws Exception {
        run(jdbc,manager,first,second,SecurityException.class,expectedDenial);
    }
    static void run(JdbcTemplate jdbc,PlatformTransactionManager manager,
            Runnable first,Runnable second,Class<? extends RuntimeException> denialType,String expectedDenial) throws Exception {
        var tx=new TransactionTemplate(manager);
        var applied=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var firstPid=new CompletableFuture<Integer>();
        var secondPid=new CompletableFuture<Integer>();
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->tx.execute(status->{
                firstPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                first.run();applied.countDown();
                try{if(!release.await(20,TimeUnit.SECONDS))throw new AssertionError("Commit gate timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                return true;
            }));
            try{
                assertThat(applied.await(10,TimeUnit.SECONDS)).as("first service applied").isTrue();
                var b=pool.submit(()->{
                    try{
                        tx.executeWithoutResult(status->{
                            secondPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                            second.run();
                        });
                        return "COMMITTED";
                    }catch(RuntimeException denied){
                        if(expectedDenial==null || !denialType.isInstance(denied) || !expectedDenial.equals(denied.getMessage()))throw denied;
                        return "DENIED";
                    }
                });
                int blocker=firstPid.get(5,TimeUnit.SECONDS),waiter=secondPid.get(5,TimeUnit.SECONDS);
                assertThat(waiter).isNotEqualTo(blocker);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                boolean observed=false;
                do{
                    observed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",Boolean.class,blocker,waiter));
                    if(observed)break;
                    if(b.isDone())break;
                    Thread.sleep(25);
                }while(System.nanoTime()<deadline);
                assertThat(observed).as("actual first PID blocks second PID").isTrue();
                release.countDown();
                assertThat(a.get(20,TimeUnit.SECONDS)).isTrue();
                assertThat(b.get(20,TimeUnit.SECONDS)).isEqualTo(expectedDenial==null?"COMMITTED":"DENIED");
                System.out.printf("BSR PG schedule blocker=%d waiter=%d observed=true first=COMMITTED second=%s%n",blocker,waiter,expectedDenial==null?"COMMITTED":"DENIED");
            }finally{release.countDown();}
        }
    }
}
