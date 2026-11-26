package kz.rm.iam;

import kz.rm.intelligence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

final class NativeBookedCaptureJourneyEvidence {
    private NativeBookedCaptureJourneyEvidence(){}
    private static Instant clock(JdbcTemplate jdbc){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static UUID run(CanonicalFactDeliveryService delivery,UUID tenant,Instant from,Instant until){
        UUID id=UUID.randomUUID();
        delivery.openBackfill(new CanonicalFactDeliveryService.Slice(tenant,id,CanonicalFactKey.Owner.SALES,
            CanonicalFactKey.Kind.ORDER_PLACED,from,until));
        delivery.backfillBatch(tenant,id,10);return id;
    }
    static void qualify(JdbcTemplate jdbc,PlatformTransactionManager manager,NativeBookedCaptureService capture,
                        BookedSourceReconciliationService reconciliation,CanonicalFactDeliveryService delivery,
                        VerifiedActor owner,VerifiedActor reader,UUID tenant,long calendar,UUID template,CanonicalFactAppender appender,Supplier<UUID> checkout){
        assertThatThrownBy(()->capture.activate(reader,tenant)).isInstanceOf(SecurityException.class);
        var boundary=checkoutFirst(jdbc,manager,capture,owner,tenant,checkout);
        assertThat(capture.activate(owner,tenant)).isEqualTo(boundary);
        Instant zeroEnd=clock(jdbc);
        assertThat(zeroEnd).isAfter(boundary.startedAt());
        UUID zeroRun=run(delivery,tenant,boundary.startedAt(),zeroEnd);
        var zero=reconciliation.inspectNativeRange(reader,tenant,zeroRun);
        assertThat(zero.state()).isEqualTo(NativeBookedClosure.State.SHADOW_QUALIFIED_ZERO);
        assertThat(zero.shadowValue()).isZero();assertThat(zero.admitted()).isFalse();
        // A committed header without event/canonical/receipt must fail at transaction commit.
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
        var eventTime=jdbc.queryForObject("SELECT occurred_at FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=? AND event_type='PLACED'",
            Timestamp.class,tenant,actual).toInstant();
        assertThat(eventTime).isAfterOrEqualTo(zeroEnd);
        Instant until=clock(jdbc);
        UUID positive=run(delivery,tenant,zeroEnd,until);
        var result=reconciliation.inspectNativeRange(reader,tenant,positive);
        assertThat(result.state()).isEqualTo(NativeBookedClosure.State.SHADOW_QUALIFIED_VALUE);
        assertThat(result.shadowValue()).isEqualTo(1L);assertThat(result.headers().headers()).isEqualTo(1);
        assertThat(result.admitted()).isFalse();
        assertThat(result.snapshot().sourceRevision()).isGreaterThan(zero.snapshot().sourceRevision());
        var freshZero=reconciliation.inspectNativeRange(reader,tenant,zeroRun);
        assertThat(freshZero.shadowValue()).isZero();
        assertThat(freshZero.snapshot().sourceRevision()).isEqualTo(result.snapshot().sourceRevision());
        // A deliberately late (but post-activation) order commits inside the previously
        // empty interval. The reader must wait on the capture guard, then see it.
        // First exercise the identical fence with a fully staged transaction that rolls back.
        // An aborted source must not advance the committed revision or poison the zero.
        lateTransaction(jdbc,manager,appender,reconciliation,delivery,reader,tenant,template,zeroRun,boundary.startedAt(),freshZero,false);
        lateTransaction(jdbc,manager,appender,reconciliation,delivery,reader,tenant,template,zeroRun,boundary.startedAt(),freshZero,true);
        assertThatThrownBy(()->jdbc.update("UPDATE crm.sales_checkout_receipt SET request_sha256=request_sha256 WHERE tenant_id=? AND order_id=?",tenant,actual))
            .hasStackTraceContaining("S1_NATIVE_CHECKOUT_RECEIPT_IMMUTABLE");
        assertThatThrownBy(()->jdbc.update("DELETE FROM crm.sales_checkout_receipt WHERE tenant_id=? AND order_id=?",tenant,actual))
            .hasStackTraceContaining("S1_NATIVE_CHECKOUT_RECEIPT_IMMUTABLE");
        assertThatThrownBy(()->jdbc.update("UPDATE crm.int_native_booked_capture SET started_at=started_at-interval '1 day' WHERE tenant_id=?",tenant))
            .hasStackTraceContaining("S1_NATIVE_CAPTURE_IMMUTABLE");
        assertThatThrownBy(()->jdbc.update("UPDATE crm.sales_order SET created_at=created_at-interval '1 day' WHERE tenant_id=? AND id=?",tenant,actual))
            .hasStackTraceContaining("S1_NATIVE_HEADER_IDENTITY");
        assertThatThrownBy(()->jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',?)",
            tenant,UUID.randomUUID(),actual,Timestamp.from(eventTime))).hasStackTraceContaining("S1_NATIVE_PLACED_IDENTITY");
        YearMonth current=YearMonth.from(until.atZone(ZoneOffset.UTC));
        var month=reconciliation.inspectNativeMonth(reader,tenant,calendar,current.minusMonths(1),List.of());
        assertThat(month.state()).isEqualTo(NativeBookedClosure.State.BEFORE_CAPTURE);
        assertThat(month.shadowValue()).isNull();
        System.out.println("S1_NATIVE_CAPTURE_PASS actual_checkout=1 closed_zero=0 rollback guard historical_boundary blocked");
    }

    // Real checkout starts while capture is inactive and retains the existing
    // workspace lock until commit. Owner activation cannot cross that checkout.
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

    // A separate fresh tenant is required: activation is irreversible.
    static void activationFirst(JdbcTemplate jdbc,PlatformTransactionManager manager,
            NativeBookedCaptureService capture,VerifiedActor owner,UUID tenant,Supplier<UUID> checkout){
        var workers=Executors.newFixedThreadPool(3);
        var ready=new CompletableFuture<Integer>();
        var release=new CountDownLatch(1);
        try{
            var holding=workers.submit(()->new TransactionTemplate(manager).executeWithoutResult(st->{
                assertThat(jdbc.queryForObject("SELECT tenant_id FROM crm.int_native_booked_capture WHERE tenant_id=? AND started_at IS NULL FOR UPDATE",UUID.class,tenant)).isEqualTo(tenant);
                ready.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                try{if(!release.await(45,TimeUnit.SECONDS))throw new AssertionError("S1_NATIVE_ACTIVATION_FIRST_RELEASE");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }));
            int guardPid=ready.get(20,TimeUnit.SECONDS);
            var activating=workers.submit(()->capture.activate(owner,tenant));
            Integer activationPid;
            Future<UUID> writing;
            try{
                activationPid=waitForBlock(jdbc,activating,guardPid,"crm.int_native_booked_capture");
                writing=workers.submit(checkout::get);
                waitForBlock(jdbc,writing,activationPid,"public.iam_workspace");
            }finally{release.countDown();}
            holding.get(30,TimeUnit.SECONDS);
            var boundary=activating.get(30,TimeUnit.SECONDS);
            UUID order=writing.get(30,TimeUnit.SECONDS);
            Instant created=jdbc.queryForObject("SELECT created_at FROM crm.sales_order WHERE tenant_id=? AND id=?",Timestamp.class,tenant,order).toInstant();
            assertThat(created).isAfterOrEqualTo(boundary.startedAt());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=? AND event_type='PLACED' AND occurred_at=?",Long.class,tenant,order,Timestamp.from(created))).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=? AND order_id=?",Long.class,tenant,order)).isEqualTo(1L);
            System.out.println("S1_NATIVE_ACTIVATION_FIRST_PASS");
        }catch(Exception e){throw new AssertionError("S1_NATIVE_ACTIVATION_FIRST",e);}
        finally{
            release.countDown();workers.shutdownNow();
            try{if(!workers.awaitTermination(60,TimeUnit.SECONDS))throw new AssertionError("S1_NATIVE_ACTIVATION_FIRST_WORKERS");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
    }
    private static int waitForBlock(JdbcTemplate jdbc,Future<?> future,int blocker,String relation) throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){
            if(future.isDone()){future.get();throw new AssertionError("S1_NATIVE_EXPECTED_BLOCK "+relation);}
            Integer pid=jdbc.query("""
                SELECT l.pid FROM pg_locks l WHERE l.locktype='relation'
                  AND l.relation=CAST(? AS regclass) AND l.mode='RowShareLock' AND l.granted
                  AND ?=ANY(pg_blocking_pids(l.pid)) ORDER BY l.pid LIMIT 1
                """,rs->rs.next()?rs.getInt(1):null,relation,blocker);
            if(pid!=null)return pid;
            Thread.sleep(25);
        }
        throw new AssertionError("S1_NATIVE_BLOCK_TIMEOUT "+relation);
    }

    private static void lateTransaction(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactAppender appender,
            BookedSourceReconciliationService reconciliation,CanonicalFactDeliveryService delivery,VerifiedActor reader,
            UUID tenant,UUID template,UUID run,Instant time,NativeBookedClosure.Result previous,boolean commit){
        var workers=Executors.newFixedThreadPool(2);
        var ready=new CompletableFuture<Integer>();
        var release=new CountDownLatch(1);
        try{
            UUID order=UUID.randomUUID(),event=UUID.randomUUID();
            var writer=workers.submit(()->new TransactionTemplate(manager).executeWithoutResult(st->{
                var at=Timestamp.from(time);
                assertThat(jdbc.update("""
                    INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
                    SELECT tenant_id,?,pending_email_identity_id,buyer_user_id,'PLACED',total_amount,currency,?,fulfillment_mode
                    FROM crm.sales_order WHERE tenant_id=? AND id=?
                    """,order,at,tenant,template)).isEqualTo(1);
                assertThat(jdbc.update("""
                    INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source,accepted_quote_id)
                    SELECT tenant_id,gen_random_uuid(),?,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source,accepted_quote_id
                    FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
                    """,order,tenant,template)).isPositive();
                jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',?)",
                    tenant,event,order,at);
                assertThat(appender.appendLive(new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,
                    CanonicalFactKey.Kind.ORDER_PLACED,event))).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
                jdbc.update("INSERT INTO crm.sales_checkout_receipt(tenant_id,idempotency_key,request_sha256,order_id,created_at) VALUES (?,?,?,?,?)",
                    tenant,"native-late-"+order,new byte[32],order,at);
                ready.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("S1_NATIVE_RELEASE");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                if(!commit)st.setRollbackOnly();
            }));
            int writerPid=ready.get(20,TimeUnit.SECONDS);
            var read=workers.submit(()->reconciliation.inspectNativeRange(reader,tenant,run));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            Integer waiter=null;
            try{
                while(System.nanoTime()<deadline&&waiter==null){
                    if(read.isDone()){read.get();throw new AssertionError("S1_NATIVE_READ_CROSSED_WRITER");}
                    waiter=jdbc.query("""
                        SELECT l.pid FROM pg_locks l WHERE l.locktype='relation'
                          AND l.relation='crm.int_native_booked_capture'::regclass AND l.mode='RowShareLock' AND l.granted
                          AND ?=ANY(pg_blocking_pids(l.pid)) ORDER BY l.pid LIMIT 1
                        """,rs->rs.next()?rs.getInt(1):null,writerPid);
                    if(waiter==null)Thread.sleep(25);
                }
                assertThat(waiter).as("native closure waits for source transaction").isNotNull();
                // This second caller must be rejected before borrowing any JDBC connection.
                // The first snapshot still owns its admission and waits on the source guard.
                assertThatThrownBy(()->reconciliation.inspectNativeRange(reader,tenant,run))
                    .isInstanceOf(IllegalStateException.class).hasMessage("S1_SNAPSHOT_ADMISSION_BUSY");
                System.out.println("S1_SNAPSHOT_ADMISSION_BUSY_PASS");
            }finally{release.countDown();}
            writer.get(30,TimeUnit.SECONDS);
            var pending=read.get(30,TimeUnit.SECONDS);
            if(!commit){
                assertThat(pending.state()).isEqualTo(NativeBookedClosure.State.SHADOW_QUALIFIED_ZERO);
                assertThat(pending.shadowValue()).isZero();
                assertThat(pending.admitted()).isFalse();
                assertThat(pending.snapshot().sourceRevision()).isEqualTo(previous.snapshot().sourceRevision());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=? AND id=?",Long.class,tenant,order)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=? AND id=?",Long.class,tenant,event)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?",Long.class,tenant,order)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=? AND order_id=?",Long.class,tenant,order)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=? AND source_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED'",Long.class,tenant,event)).isZero();
                assertThat(delivery.backfillBatch(tenant,run,10)).isEmpty();
                System.out.printf("S1_NATIVE_LATE_ROLLBACK_PASS writer=%d waiter=%d zero_preserved=true%n",writerPid,waiter);
                return;
            }
            assertThat(pending.state()).isEqualTo(NativeBookedClosure.State.UNRECONCILED_RANGE);
            assertThat(pending.shadowValue()).isNull();
            assertThat(pending.snapshot().sourceRevision()).isGreaterThan(previous.snapshot().sourceRevision());
            assertThat(delivery.backfillBatch(tenant,run,10)).hasSize(1);
            var requalified=reconciliation.inspectNativeRange(reader,tenant,run);
            assertThat(requalified.state()).isEqualTo(NativeBookedClosure.State.SHADOW_QUALIFIED_VALUE);
            assertThat(requalified.shadowValue()).isEqualTo(1L);
            assertThat(requalified.admitted()).isFalse();
            System.out.printf("S1_NATIVE_LATE_COMMIT_PASS writer=%d waiter=%d zero_to_one=true%n",writerPid,waiter);
        }catch(Exception e){throw new AssertionError(commit?"S1_NATIVE_LATE_COMMIT":"S1_NATIVE_LATE_ROLLBACK",e);}
        finally{
            release.countDown();workers.shutdownNow();
            try{if(!workers.awaitTermination(60,TimeUnit.SECONDS))throw new AssertionError("S1_NATIVE_WORKERS");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
    }
}
