package kz.rm.iam;

import kz.rm.intelligence.BookedHistoryComposition;
import kz.rm.intelligence.BookedSourceReconciliationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** S1 real lock-order proof, fits four Hikari connections. */
final class BookedConcurrencyEvidence {
    private BookedConcurrencyEvidence(){}
    private static BookedHistoryComposition.Assessment read(BookedSourceReconciliationService service,VerifiedActor actor,
            UUID tenant,Instant from,Instant until,List<UUID> runs){
        return service.inspectComposition(actor,tenant,BookedHistoryComposition.Mode.IMPORTED_HISTORY,from,until,runs);
    }
    private static int waitFor(JdbcTemplate monitor,int blocker,String relation,Future<?> future) throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){
            if(future.isDone()){future.get();throw new AssertionError("S1_WORKER_FINISHED_BEFORE_LOCK");}
            Integer pid=monitor.query("""
                SELECT l.pid FROM pg_locks l WHERE l.locktype='relation' AND l.relation=CAST(? AS regclass)
                  AND NOT l.granted AND ?=ANY(pg_blocking_pids(l.pid)) ORDER BY l.pid LIMIT 1
                """,rs->rs.next()?rs.getInt(1):null,relation,blocker);
            // Row lock waits are commonly exposed as transaction-ID waits; use blocker identity
            // plus a granted RowShareLock on the expected relation in that backend.
            if(pid==null)pid=monitor.query("""
                SELECT l.pid FROM pg_locks l WHERE l.locktype='relation' AND l.relation=CAST(? AS regclass)
                  AND l.granted AND ?=ANY(pg_blocking_pids(l.pid)) ORDER BY l.pid LIMIT 1
                """,rs->rs.next()?rs.getInt(1):null,relation,blocker);
            if(pid!=null)return pid;
            Thread.sleep(25);
        }
        throw new AssertionError("S1_EXPECTED_LOCK_WAIT "+relation+" blocker="+blocker);
    }
    static void qualify(JdbcTemplate jdbc,PlatformTransactionManager manager,BookedSourceReconciliationService service,
            VerifiedActor actor,UUID tenant,Instant from,Instant until,List<UUID> runs,Runnable revoke,Runnable restore){
        var pool=Executors.newFixedThreadPool(2);
        var ready=new CompletableFuture<Integer>();
        var release=new CountDownLatch(1);
        try{
            // Revocation commits first. Reader must wait for the workspace fence and deny.
            var writer=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(st->{
                revoke.run();ready.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("S1_REVOKE_RELEASE");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }));
            int writerPid=ready.get(20,TimeUnit.SECONDS);
            var reading=pool.submit(()->read(service,actor,tenant,from,until,runs));
            try{int readerPid=waitFor(jdbc,writerPid,"public.iam_workspace",reading);
                System.out.printf("S1_REVOKE_FIRST_LOCK revoker=%d reader=%d%n",writerPid,readerPid);
            }finally{release.countDown();}
            writer.get(30,TimeUnit.SECONDS);
            assertThatThrownBy(()->reading.get(30,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(SecurityException.class);
            restore.run();
            // Read admission first. Hold snapshot at backfill run table, then revoke.
            // Reuse the fence connection to monitor locks: 1 fence + 2 read + 1 revoke.
            try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
                connection.setAutoCommit(false);
                var probe=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
                probe.setQueryTimeout(5);
                try{
                    probe.execute("LOCK TABLE crm.int_fact_backfill_run IN ACCESS EXCLUSIVE MODE");
                    int fence=probe.queryForObject("SELECT pg_backend_pid()",Integer.class);
                    var inFlight=pool.submit(()->read(service,actor,tenant,from,until,runs));
                    int snapshot=waitFor(probe,fence,"crm.int_fact_backfill_run",inFlight);
                    var revokePid=new CompletableFuture<Integer>();
                    var revoking=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(st->{
                        revokePid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));revoke.run();
                    }));
                    int revoker=revokePid.get(20,TimeUnit.SECONDS);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                    Integer admission=null;
                    while(System.nanoTime()<deadline&&admission==null){
                        if(revoking.isDone()){revoking.get();throw new AssertionError("S1_REVOKE_CROSSED_ADMISSION");}
                        admission=probe.query("""
                            SELECT b.pid FROM unnest(pg_blocking_pids(?)) b(pid) JOIN pg_locks l ON l.pid=b.pid
                            WHERE b.pid<>? AND b.pid<>? AND l.locktype='relation'
                              AND l.relation='public.iam_workspace'::regclass AND l.mode='RowShareLock' AND l.granted
                            ORDER BY b.pid LIMIT 1
                            """,rs->rs.next()?rs.getInt(1):null,revoker,fence,snapshot);
                        if(admission==null)Thread.sleep(25);
                    }
                    assertThat(admission).as("S1 expected workspace admission blocker").isNotNull();
                    System.out.printf("S1_READ_FIRST_LOCK fence=%d snapshot=%d admission=%d revoker=%d%n",fence,snapshot,admission,revoker);
                    assertThat(inFlight.isDone()).isFalse();assertThat(revoking.isDone()).isFalse();
                    connection.commit();
                    var result=inFlight.get(30,TimeUnit.SECONDS);
                    assertThat(result.snapshot().tenant()).isEqualTo(tenant);
                    assertThat(result.snapshot().actor()).isEqualTo(actor.userId());
                    assertThat(result.slices()).hasSize(runs.size()).allSatisfy(e->{
                        assertThat(e.asOf()).isEqualTo(result.snapshot().asOf());
                        assertThat(e.sourceRevision()).isEqualTo(result.snapshot().sourceRevision());
                    });
                    assertThat(result.ownerCompletenessProven()).isFalse();
                    revoking.get(30,TimeUnit.SECONDS);
                    assertThatThrownBy(()->read(service,actor,tenant,from,until,runs)).isInstanceOf(SecurityException.class);
                }finally{connection.rollback();}
            }
        }catch(Exception e){throw new AssertionError("S1_CONCURRENT_AUTHORIZATION",e);}
        finally{
            release.countDown();pool.shutdown();
            try{if(!pool.awaitTermination(60,TimeUnit.SECONDS)){pool.shutdownNow();throw new AssertionError("S1_WORKERS_NOT_TERMINATED");}}
            catch(InterruptedException e){pool.shutdownNow();Thread.currentThread().interrupt();throw new AssertionError(e);}
            finally{restore.run();}
        }
    }
}
