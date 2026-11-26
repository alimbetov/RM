package kz.rm.iam;

import kz.rm.intelligence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL lock-order evidence, not synthetic sleep-only concurrency. */
final class ConclusionConcurrencyEvidence {
    private ConclusionConcurrencyEvidence(){}
    private static BusinessConclusionService.Conclusion read(BusinessConclusionService service,VerifiedActor actor,UUID tenant,
            AuthorizedAnalyticalReadService.Query q,List<LocalDate> days){
        return service.concludePattern(actor,tenant,q,days,1,BigDecimal.ONE,BigDecimal.ZERO,1,new BigDecimal("3"),new BigDecimal("0.5"));
    }
    private static void await(CountDownLatch latch){
        try{if(!latch.await(30,TimeUnit.SECONDS))throw new AssertionError("B55_LATCH_TIMEOUT");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private static void blockedBy(JdbcTemplate jdbc,int pid){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",Boolean.class,pid)))return;
            try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
        throw new AssertionError("B55_EXPECTED_PG_BLOCKER:"+pid);
    }
    static void revokeBeforeRead(JdbcTemplate jdbc,PlatformTransactionManager manager,BusinessConclusionService service,
            VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query q,List<LocalDate> dates,Runnable revoke,Runnable restore){
        var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pid=new java.util.concurrent.atomic.AtomicInteger();
        try{
            var revoker=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->{
                revoke.run();pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));held.countDown();await(release);
            }));
            await(held);
            var reading=pool.submit(()->read(service,actor,tenant,q,dates));
            try{blockedBy(jdbc,pid.get());}finally{release.countDown();}
            revoker.get(30,TimeUnit.SECONDS);
            assertThatThrownBy(()->reading.get(30,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(SecurityException.class);
        }catch(Exception e){throw new AssertionError("B55_REVOKE_FIRST",e);}
        finally{release.countDown();pool.shutdownNow();restore.run();}
    }
    // pg_locks and pg_blocking_pids expose current lock-manager state, not a
    // historical MVCC snapshot. Probe from the fence transaction itself.
    // SingleConnectionDataSource suppresses close but never commits the fence.
    private static JdbcTemplate monitor(java.sql.Connection connection) throws java.sql.SQLException {
        if(connection.getAutoCommit())throw new IllegalStateException("B55_FENCE_TRANSACTION_REQUIRED");
        var monitor=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
        monitor.setQueryTimeout(5);
        return monitor;
    }
    private static int awaitSnapshotWait(JdbcTemplate monitor,int fencePid,Future<?> reading) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){
            if(reading.isDone()){reading.get();throw new AssertionError("B55_READ_FINISHED_BEFORE_FENCE");}
            Integer pid=monitor.query("""
                SELECT l.pid FROM pg_locks l
                WHERE l.locktype='relation' AND l.relation='crm.int_calendar_policy'::regclass
                  AND l.mode='AccessShareLock' AND NOT l.granted AND ? = ANY(pg_blocking_pids(l.pid))
                ORDER BY l.pid LIMIT 1
                """,rs->rs.next()?rs.getInt(1):null,fencePid);
            if(pid!=null)return pid;
            Thread.sleep(25);
        }
        throw new AssertionError("B55_SNAPSHOT_WAIT_NOT_OBSERVED fencePid="+fencePid);
    }
    private static void awaitScopeWait(JdbcTemplate monitor,int revokerPid,int fencePid,int snapshotPid,
            Future<?> reading,Future<?> revoker) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){
            if(reading.isDone()){reading.get();throw new AssertionError("B55_READ_FINISHED_BEFORE_RELEASE");}
            if(revoker.isDone()){revoker.get();throw new AssertionError("B55_REVOKE_COMMITTED_BEFORE_READ_RELEASE");}
            Integer admissionPid=monitor.query("""
                SELECT b.pid FROM unnest(pg_blocking_pids(?)) AS b(pid)
                JOIN pg_locks l ON l.pid=b.pid
                WHERE b.pid<>? AND b.pid<>? AND l.locktype='relation'
                  AND l.relation='public.iam_warehouse_staff_scope'::regclass
                  AND l.mode='RowShareLock' AND l.granted
                ORDER BY b.pid LIMIT 1
                """,rs->rs.next()?rs.getInt(1):null,revokerPid,fencePid,snapshotPid);
            if(admissionPid!=null){
                System.out.printf("B55_READ_FIRST_LOCK_PROOF fence=%d snapshot=%d admission=%d revoker=%d%n",
                    fencePid,snapshotPid,admissionPid,revokerPid);
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("B55_REVOKE_WAITS_ON_SCOPE_NOT_OBSERVED revokerPid="+revokerPid);
    }
    static void readBeforeRevoke(JdbcTemplate jdbc,PlatformTransactionManager manager,BusinessConclusionService service,
            VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query q,List<LocalDate> dates,Runnable revoke,Runnable restore){
        var pool=Executors.newFixedThreadPool(2);
        var revokerPid=new CompletableFuture<Integer>();
        var ds=Objects.requireNonNull(jdbc.getDataSource());
        // Exact budget with Domain Security Hikari max=4:
        // fence+probe (1), admission (1), REQUIRES_NEW snapshot (1), revoke (1).
        // Do not reserve a fifth connection for monitoring.
        try(var fenceConnection=ds.getConnection()){
            fenceConnection.setAutoCommit(false);
            var probe=monitor(fenceConnection);
            try{
                int fencePid;
                try(var statement=fenceConnection.createStatement()){
                    statement.setQueryTimeout(5);
                    statement.execute("LOCK TABLE crm.int_calendar_policy IN ACCESS EXCLUSIVE MODE");
                    try(var rs=statement.executeQuery("SELECT pg_backend_pid()")){
                        if(!rs.next())throw new AssertionError("B55_FENCE_PID_MISSING");
                        fencePid=rs.getInt(1);
                    }
                }
                var reading=pool.submit(()->read(service,actor,tenant,q,dates));
                int snapshotPid=awaitSnapshotWait(probe,fencePid,reading);
                var revoker=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                    revokerPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    revoke.run();
                }));
                // Do not hide a connection-admission failure in a bare future timeout.
                int revoking;
                try{revoking=revokerPid.get(20,TimeUnit.SECONDS);}
                catch(TimeoutException failure){
                    if(revoker.isDone())revoker.get();
                    throw new AssertionError("B55_REVOKER_CONNECTION_NOT_ADMITTED "+poolState(ds),failure);
                }
                awaitScopeWait(probe,revoking,fencePid,snapshotPid,reading,revoker);
                System.out.println("B55_READ_FIRST_POOL "+poolState(ds));
                assertThat(reading.isDone()).isFalse();
                assertThat(revoker.isDone()).isFalse();
                fenceConnection.commit();
                var result=reading.get(30,TimeUnit.SECONDS);
                assertThat(result.tenant()).isEqualTo(tenant);
                assertThat(result.actor()).isEqualTo(actor.userId());
                assertThat(result.evidence().getFirst().points()).hasSize(dates.size());
                assertThat(result.evidence().getFirst().points().stream()
                    .map(AuthorizedAnalyticalReadService.Result::observedAt).distinct().count()).isEqualTo(1);
                assertThat(result.decisionEligibility()).isEqualTo(BusinessConclusionService.DecisionEligibility.BLOCKED);
                revoker.get(30,TimeUnit.SECONDS);
                assertThatThrownBy(()->read(service,actor,tenant,q,dates)).isInstanceOf(SecurityException.class);
            }finally{
                // Release the database fence even if an assertion, query or worker fails.
                fenceConnection.rollback();
            }
        }catch(Exception e){throw new AssertionError("B55_READ_FIRST",e);}
        finally{
            pool.shutdown();
            try{
                if(!pool.awaitTermination(60,TimeUnit.SECONDS)){
                    pool.shutdownNow();
                    throw new AssertionError("B55_WORKERS_NOT_TERMINATED");
                }
            }catch(InterruptedException e){
                pool.shutdownNow();Thread.currentThread().interrupt();throw new AssertionError(e);
            }finally{restore.run();}
        }
    }
    private static String poolState(javax.sql.DataSource ds){
        if(ds instanceof com.zaxxer.hikari.HikariDataSource hikari){
            var pool=hikari.getHikariPoolMXBean();
            if(pool!=null)return "max="+hikari.getMaximumPoolSize()+" active="+pool.getActiveConnections()
                +" idle="+pool.getIdleConnections()+" pending="+pool.getThreadsAwaitingConnection();
        }
        return "pool_metrics_unavailable";
    }
    static void boundedWindow(BusinessConclusionService service,VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query q){
        var dates=java.util.stream.IntStream.rangeClosed(0,11).mapToObj(i->q.anchor().minusDays(11-i)).toList();
        long start=System.nanoTime();var result=read(service,actor,tenant,q,dates);
        assertThat(result.evidence().getFirst().points()).hasSize(12);
        assertThat(result.evidence().getFirst().points().stream().map(AuthorizedAnalyticalReadService.Result::observedAt).distinct().count()).isEqualTo(1);
        assertThat(result.evidence().getFirst().points().stream().map(AuthorizedAnalyticalReadService.Result::sourceRevision).distinct().count()).isEqualTo(1);
        System.out.printf("B55_MAX_WINDOW_FIXTURE elapsedMs=%.3f periods=12%n",(System.nanoTime()-start)/1e6);
        var over=new ArrayList<>(dates);over.addFirst(dates.getFirst().minusDays(1));
        assertThatThrownBy(()->read(service,actor,tenant,q,over)).isInstanceOf(IllegalArgumentException.class).hasMessage("OBSERVATION_WINDOW");
    }
    static void twoTenants(BusinessConclusionService service,VerifiedActor a,UUID ta,AuthorizedAnalyticalReadService.Query qa,
            VerifiedActor b,UUID tb,AuthorizedAnalyticalReadService.Query qb,List<LocalDate> days){
        var pool=Executors.newFixedThreadPool(2);
        try{
            var barrier=new CyclicBarrier(2);long start=System.nanoTime();
            var fa=pool.submit(()->{barrier.await(15,TimeUnit.SECONDS);return read(service,a,ta,qa,days);});
            var fb=pool.submit(()->{barrier.await(15,TimeUnit.SECONDS);return read(service,b,tb,qb,days);});
            var ca=fa.get(60,TimeUnit.SECONDS);var cb=fb.get(60,TimeUnit.SECONDS);
            assertThat(ca.tenant()).isEqualTo(ta);assertThat(cb.tenant()).isEqualTo(tb);
            assertThat(ca.actor()).isEqualTo(a.userId());assertThat(cb.actor()).isEqualTo(b.userId());
            assertThat(ca.evidence().getFirst().points()).anySatisfy(e->{assertThat(e.value()).isNotNull();assertThat(e.value()).isPositive();});
            assertThat(cb.evidence().getFirst().points()).allSatisfy(e->{assertThat(e.quality()).isEqualTo(DataQuality.MISSING);assertThat(e.value()).isNull();});
            assertThat(cb.findings()).allSatisfy(f->assertThat(f.disposition()).isEqualTo(BusinessConclusionService.Disposition.ABSTAINED));
            System.out.printf("B55_CONCURRENT_TWO_TENANT_FIXTURE elapsedMs=%.3f requests=2 periodsPerRequest=%d%n",(System.nanoTime()-start)/1e6,days.size());
        }catch(Exception e){throw new AssertionError("B55_TWO_TENANTS",e);}
        finally{pool.shutdownNow();}
    }
}
