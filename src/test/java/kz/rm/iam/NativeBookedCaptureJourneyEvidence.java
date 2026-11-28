package kz.rm.iam;

import kz.rm.intelligence.NativeBookedCaptureService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL subcase of the existing mandatory owner/rollback journey.
 * Native capture only: not historical completeness or statistical admission. */
final class NativeBookedCaptureJourneyEvidence {
    private NativeBookedCaptureJourneyEvidence(){}
    static void qualify(JdbcTemplate jdbc,PlatformTransactionManager manager,
            NativeBookedCaptureService capture,VerifiedActor owner,VerifiedActor staff,
            VerifiedActor foreignOwner,UUID tenant,UUID otherTenant,UUID template,Supplier<UUID> checkout){
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_native_booked_capture WHERE tenant_id IN (?,?) AND started_at IS NULL",Long.class,tenant,otherTenant)).isEqualTo(2L);
        assertThatThrownBy(()->capture.activate(staff,tenant)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->capture.activate(foreignOwner,tenant)).isInstanceOf(SecurityException.class);
        var before=measure(jdbc,tenant,checkout);
        var boundary=checkoutFirst(jdbc,manager,capture,owner,tenant,checkout);
        assertThat(boundary.tenant()).isEqualTo(tenant);
        assertThat(boundary.startedAt()).isNotNull();
        assertThat(boundary.policyVersion()).isEqualTo(1);
        assertThat(capture.activate(owner,tenant)).isEqualTo(boundary);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_native_booked_capture WHERE tenant_id=? AND started_at IS NULL",Long.class,otherTenant)).isEqualTo(1L);
        UUID broken=UUID.randomUUID();
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(st->{
            assertThat(jdbc.update("""
                INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
                SELECT tenant_id,?,pending_email_identity_id,buyer_user_id,'PLACED',total_amount,currency,clock_timestamp(),fulfillment_mode
                FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,broken,tenant,template)).isEqualTo(1);
        })).hasStackTraceContaining("S1_NATIVE_ORDER_TRANSACTION_INCOMPLETE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=? AND id=?",Long.class,tenant,broken)).isZero();
        UUID actual=checkout.get();
        Instant created=jdbc.queryForObject("SELECT created_at FROM crm.sales_order WHERE tenant_id=? AND id=?",Timestamp.class,tenant,actual).toInstant();
        assertThat(created).isAfterOrEqualTo(boundary.startedAt());
        UUID event=CheckoutCanonicalEvidence.assertPlaced(jdbc,tenant,actual);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM crm.sales_order_status_event WHERE tenant_id=? AND id=?",Timestamp.class,tenant,event).toInstant()).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=? AND order_id=?",Long.class,tenant,actual)).isEqualTo(1L);
        assertThatThrownBy(()->jdbc.update("UPDATE crm.sales_checkout_receipt SET request_sha256=request_sha256 WHERE tenant_id=? AND order_id=?",tenant,actual)).hasStackTraceContaining("S1_NATIVE_CHECKOUT_RECEIPT_IMMUTABLE");
        assertThatThrownBy(()->jdbc.update("UPDATE crm.int_native_booked_capture SET started_at=started_at-interval '1 day' WHERE tenant_id=?",tenant)).hasStackTraceContaining("S1_NATIVE_CAPTURE_IMMUTABLE");
        assertThatThrownBy(()->jdbc.update("UPDATE crm.sales_order SET created_at=created_at-interval '1 day' WHERE tenant_id=? AND id=?",tenant,actual)).hasStackTraceContaining("S1_NATIVE_HEADER_IDENTITY");
        assertThatThrownBy(()->jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',?)",tenant,UUID.randomUUID(),actual,Timestamp.from(created))).hasStackTraceContaining("S1_NATIVE_PLACED_IDENTITY");
        long revision=jdbc.queryForObject("SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?",Long.class,tenant);
        UUID rolledBack=new TransactionTemplate(manager).execute(st->{UUID id=checkout.get();st.setRollbackOnly();return id;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=? AND id=?",Long.class,tenant,rolledBack)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=? AND order_id=?",Long.class,tenant,rolledBack)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?",Long.class,tenant)).isEqualTo(revision);
        var after=measure(jdbc,tenant,checkout);
        System.out.printf(java.util.Locale.ROOT,"RM_ARS_NATIVE_CAPTURE_DIAGNOSTIC samples=12 inactive_p50_ms=%.3f inactive_p95_ms=%.3f active_p50_ms=%.3f active_p95_ms=%.3f%n",before[0],before[1],after[0],after[1]);
        System.out.println("RM_ARS_NATIVE_CAPTURE_TRANSACTIONAL_ORACLE_PASS");
    }
    private static double[] measure(JdbcTemplate jdbc,UUID tenant,Supplier<UUID> checkout){
        for(int i=0;i<3;i++)checkout.get();
        long[] elapsed=new long[12];
        for(int i=0;i<elapsed.length;i++){
            long start=System.nanoTime();
            UUID id=checkout.get();
            elapsed[i]=System.nanoTime()-start;
            CheckoutCanonicalEvidence.assertPlaced(jdbc,tenant,id);
        }
        Arrays.sort(elapsed);
        return new double[]{elapsed[5]/1e6,elapsed[11]/1e6};
    }

    private static NativeBookedCaptureService.Boundary checkoutFirst(JdbcTemplate jdbc,PlatformTransactionManager manager,
            NativeBookedCaptureService capture,VerifiedActor owner,UUID tenant,Supplier<UUID> checkout){
        var workers=Executors.newFixedThreadPool(2);
        var staged=new CompletableFuture<Integer>();
        var release=new CountDownLatch(1);
        try{
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_native_booked_capture WHERE tenant_id=? AND started_at IS NULL",Long.class,tenant)).isEqualTo(1L);
            var writing=workers.submit(()->new TransactionTemplate(manager).execute(st->{
                UUID order=checkout.get();
                staged.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("S1_NATIVE_ACTIVATION_RELEASE");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                return order;
            }));
            int writerPid=staged.get(20,TimeUnit.SECONDS);
            var activating=workers.submit(()->capture.activate(owner,tenant));
            Integer waiter=null;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            try{
                while(System.nanoTime()<deadline&&waiter==null){
                    if(activating.isDone()){activating.get();throw new AssertionError("S1_ACTIVATION_CROSSED_CHECKOUT");}
                    waiter=jdbc.query("""
                        SELECT l.pid FROM pg_locks l WHERE l.locktype='relation'
                          AND l.relation='public.iam_workspace'::regclass AND l.mode='RowShareLock' AND l.granted
                          AND ?=ANY(pg_blocking_pids(l.pid)) ORDER BY l.pid LIMIT 1
                        """,rs->rs.next()?rs.getInt(1):null,writerPid);
                    if(waiter==null)Thread.sleep(25);
                }
                assertThat(waiter).as("activation waits for real checkout commit").isNotNull();
            }finally{release.countDown();}
            UUID order=writing.get(30,TimeUnit.SECONDS);
            var boundary=activating.get(30,TimeUnit.SECONDS);
            Instant created=jdbc.queryForObject("SELECT created_at FROM crm.sales_order WHERE tenant_id=? AND id=?",Timestamp.class,tenant,order).toInstant();
            assertThat(created).isBefore(boundary.startedAt());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=? AND order_id=?",Long.class,tenant,order)).isEqualTo(1L);
            System.out.printf("S1_NATIVE_CHECKOUT_FIRST_PASS writer=%d waiter=%d%n",writerPid,waiter);
            return boundary;
        }catch(Exception e){throw new AssertionError("S1_NATIVE_CHECKOUT_FIRST",e);}
        finally{
            release.countDown();workers.shutdownNow();
            try{if(!workers.awaitTermination(60,TimeUnit.SECONDS))throw new AssertionError("S1_NATIVE_ACTIVATION_WORKERS");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
    }

}
