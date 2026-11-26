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
    static void readBeforeRevoke(JdbcTemplate jdbc,PlatformTransactionManager manager,BusinessConclusionService service,
            VerifiedActor actor,UUID tenant,AuthorizedAnalyticalReadService.Query q,List<LocalDate> dates,Runnable revoke,Runnable restore){
        var pool=Executors.newFixedThreadPool(3);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pid=new java.util.concurrent.atomic.AtomicInteger();
        try{
            // Pause the nested snapshot before its first business SELECT, after IAM admission.
            var fence=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->{
                jdbc.execute("LOCK TABLE crm.int_calendar_policy IN ACCESS EXCLUSIVE MODE");
                pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));held.countDown();await(release);
            }));
            await(held);
            var reading=pool.submit(()->read(service,actor,tenant,q,dates));
            try{
                blockedBy(jdbc,pid.get());
                var revoker=pool.submit(revoke);
                // Verify a lock chain: snapshot waits on calendar, revoker on another
                // backend (the outer admission transaction), not on the calendar lock.
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean observed=false;
                while(System.nanoTime()<deadline){
                    observed=Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                        WHERE cardinality(pg_blocking_pids(pid))>0 AND query LIKE '%UPDATE public.iam_warehouse_staff_scope%')
                        """,Boolean.class));
                    if(observed)break;
                    Thread.sleep(25);
                }
                assertThat(observed).as("B55_REVOKE_WAITS_ON_SCOPE").isTrue();
                release.countDown();fence.get(30,TimeUnit.SECONDS);
                assertThat(reading.get(30,TimeUnit.SECONDS).tenant()).isEqualTo(tenant);
                revoker.get(30,TimeUnit.SECONDS);
                assertThatThrownBy(()->read(service,actor,tenant,q,dates)).isInstanceOf(SecurityException.class);
            }finally{release.countDown();}
        }catch(Exception e){throw new AssertionError("B55_READ_FIRST",e);}
        finally{release.countDown();pool.shutdownNow();restore.run();}
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
