package kz.rm.iam;

import kz.rm.intelligence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Native diagnostic closure: independent owner/fact/receipt evidence, late write
 * invalidation and a real PostgreSQL scan fence that must not stop checkout. */
final class NativeBookedClosureJourneyEvidence {
    private NativeBookedClosureJourneyEvidence(){}
    static void qualify(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactAppender appender,
            NativeBookedClosureService closure,VerifiedActor reader,VerifiedActor foreign,UUID tenant,UUID other,
            UUID template,long calendarVersion,Supplier<UUID> checkout){
        Instant from=jdbc.queryForObject("SELECT started_at FROM crm.int_native_booked_capture WHERE tenant_id=?",Timestamp.class,tenant).toInstant();
        Instant first=jdbc.queryForObject("SELECT min(created_at) FROM crm.sales_order WHERE tenant_id=? AND created_at>=?",
            Timestamp.class,tenant,Timestamp.from(from)).toInstant();
        assertThat(first).isAfter(from);
        var zone=ZoneId.of("Asia/Almaty");
        YearMonth month=YearMonth.from(from.atZone(zone));
        var previous=closure.inspectMonth(reader,tenant,calendarVersion,month.minusMonths(1));
        assertThat(previous.calendar().tenant()).isEqualTo(tenant);
        assertThat(previous.calendar().zoneId()).isEqualTo(zone.getId());
        assertThat(previous.calendar().version()).isEqualTo(calendarVersion);
        assertThat(previous.source().from()).isEqualTo(month.minusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
        assertThat(previous.source().until()).isEqualTo(month.atDay(1).atStartOfDay(zone).toInstant());
        assertThat(previous.source().state()).isEqualTo(NativeBookedClosureService.State.BEFORE_BOUNDARY);
        assertThat(previous.historicalQuality()).isEqualTo(DataQuality.MISSING);
        assertThat(previous.snapshotValue()).isNull();assertThat(previous.historicalValue()).isNull();
        assertThat(previous.qualifiedSnapshotZero()).isFalse();assertThat(previous.admitted()).isFalse();
        var future=closure.inspectMonth(reader,tenant,calendarVersion,month.plusMonths(1));
        assertThat(future.source().state()).isEqualTo(NativeBookedClosureService.State.OPEN_RANGE);
        assertThat(future.historicalQuality()).isEqualTo(DataQuality.MISSING);
        assertThat(future.snapshotValue()).isNull();assertThat(future.historicalValue()).isNull();
        assertThatThrownBy(()->closure.inspectMonth(reader,tenant,Long.MAX_VALUE,month)).hasMessage("CALENDAR_VERSION_NOT_FOUND");
        assertThatThrownBy(()->closure.inspectMonth(foreign,tenant,calendarVersion,month)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->closure.inspectMonth(reader,other,calendarVersion,month)).isInstanceOf(SecurityException.class);
        System.out.println("RM_ARS_P1_MONTH_CALENDAR_TENANT_ORACLE_PASS");
        var zero=closure.inspect(reader,tenant,from,first);
        assertThat(zero.state()).isEqualTo(NativeBookedClosureService.State.SNAPSHOT_ZERO);
        assertThat(zero.shadowValue()).isZero();assertThat(zero.admitted()).isFalse();
        assertThat(zero.sameEvidence(closure.inspect(reader,tenant,from,first))).isTrue();
        assertThat(closure.inspect(reader,tenant,from.minusNanos(1000),first).state())
            .isEqualTo(NativeBookedClosureService.State.BEFORE_BOUNDARY);
        assertThat(closure.inspect(reader,tenant,from,clock(jdbc).plusSeconds(60)).state())
            .isEqualTo(NativeBookedClosureService.State.OPEN_RANGE);
        assertThatThrownBy(()->closure.inspect(foreign,tenant,from,first)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->closure.inspect(reader,other,from,first)).isInstanceOf(SecurityException.class);
        // A complete late source committed into a previously observed empty interval.
        var late=UUID.randomUUID();
        var event=UUID.randomUUID();
        var t=Timestamp.from(from);
        new TransactionTemplate(manager).executeWithoutResult(st->{
            jdbc.update("""
                INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
                SELECT tenant_id,?,pending_email_identity_id,buyer_user_id,'PLACED',total_amount,currency,?,fulfillment_mode
                FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,late,t,tenant,template);
            jdbc.update("""
                INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source,accepted_quote_id)
                SELECT tenant_id,gen_random_uuid(),?,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source,accepted_quote_id
                FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
                """,late,tenant,template);
            jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',?)",tenant,event,late,t);
            assertThat(appender.appendLive(new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event)))
                .isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
            jdbc.update("INSERT INTO crm.sales_checkout_receipt(tenant_id,idempotency_key,request_sha256,order_id,created_at) VALUES (?,?,?,?,?)",
                tenant,"closure-late-"+late,new byte[32],late,t);
        });
        var positive=closure.inspect(reader,tenant,from,first);
        assertThat(positive.state()).isEqualTo(NativeBookedClosureService.State.SNAPSHOT_VALUE);
        assertThat(positive.shadowValue()).isEqualTo(1L);assertThat(positive.admitted()).isFalse();
        assertThat(positive.revision().events()).isGreaterThan(zero.revision().events());
        assertThat(zero.sameEvidence(positive)).isFalse();
        // A receipt-only append must also invalidate the candidate even though the
        // existing source revision journal has not changed.
        jdbc.update("INSERT INTO crm.sales_checkout_receipt(tenant_id,idempotency_key,request_sha256,order_id,created_at) VALUES (?,?,?,?,?)",
            tenant,"closure-duplicate-"+late,new byte[32],late,t);
        var bad=closure.inspect(reader,tenant,from,first);
        assertThat(bad.state()).isEqualTo(NativeBookedClosureService.State.SOURCE_MISMATCH);
        assertThat(bad.shadowValue()).isNull();
        assertThat(bad.revision().events()).isEqualTo(positive.revision().events());
        assertThat(bad.revision().scopedCheckoutReceipts()).isEqualTo(2L);
        assertThat(positive.sameEvidence(bad)).isFalse();
        nonBlocking(jdbc,manager,closure,reader,tenant,first,checkout);
        System.out.println("RM_ARS_NATIVE_CLOSURE_REVISION_ORACLE_PASS late_commit=true receipt_only=true production_admitted=false");
    }
    private static Instant clock(JdbcTemplate jdbc){
        return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
    }
    private static void nonBlocking(JdbcTemplate jdbc,PlatformTransactionManager manager,NativeBookedClosureService closure,
                                    VerifiedActor reader,UUID tenant,Instant from,Supplier<UUID> checkout){
        Instant until=clock(jdbc);
        var workers=Executors.newFixedThreadPool(3);
        var ready=new CompletableFuture<Integer>();
        var release=new CountDownLatch(1);
        try{
            var fence=workers.submit(()->new TransactionTemplate(manager).executeWithoutResult(st->{
                // Frozen baseline is only read by historical inspection; source
                // writes append revision events and do not lock this relation.
                jdbc.execute("LOCK TABLE crm.int_source_revision IN ACCESS EXCLUSIVE MODE");
                ready.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("NATIVE_SCAN_FENCE_TIMEOUT");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }));
            int blocker=ready.get(10,TimeUnit.SECONDS);
            var scan=workers.submit(()->closure.inspect(reader,tenant,from,until));
            try{
                Integer waiter=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(waiter==null&&System.nanoTime()<deadline){
                    if(scan.isDone()){scan.get();throw new AssertionError("NATIVE_SCAN_DID_NOT_WAIT");}
                    waiter=jdbc.query("""
                        SELECT pid FROM pg_locks WHERE locktype='relation'
                        AND relation='crm.int_source_revision'::regclass AND NOT granted
                        AND ?=ANY(pg_blocking_pids(pid)) LIMIT 1
                        """,rs->rs.next()?rs.getInt(1):null,blocker);
                    if(waiter==null)Thread.sleep(25);
                }
                assertThat(waiter).isNotNull();
                assertThatThrownBy(()->closure.inspect(reader,tenant,from,until)).hasMessage("NATIVE_CLOSURE_BUSY");
                UUID committed=workers.submit(checkout::get).get(10,TimeUnit.SECONDS);
                assertThat(scan.isDone()).isFalse();
                CheckoutCanonicalEvidence.assertPlaced(jdbc,tenant,committed);
                System.out.printf("RM_ARS_NATIVE_CLOSURE_CHECKOUT_PROGRESS_PASS blocked_reader=%d checkout_committed=%s%n",waiter,committed);
            }finally{release.countDown();}
            fence.get(10,TimeUnit.SECONDS);
            var old=scan.get(20,TimeUnit.SECONDS);
            var fresh=closure.inspect(reader,tenant,from,until);
            assertThat(old.state()).isEqualTo(NativeBookedClosureService.State.SNAPSHOT_VALUE);
            assertThat(fresh.state()).isEqualTo(old.state());
            assertThat(fresh.shadowValue()).isEqualTo(old.shadowValue());
            assertThat(fresh.revision().events()).isGreaterThan(old.revision().events());
            assertThat(old.sameEvidence(fresh)).isFalse();
        }catch(Exception e){throw new AssertionError("NATIVE_CLOSURE_NON_BLOCKING",e);}
        finally{release.countDown();workers.shutdownNow();
            try{if(!workers.awaitTermination(40,TimeUnit.SECONDS))throw new AssertionError("NATIVE_CLOSURE_WORKERS");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
    }
}
