package kz.rm.iam;

import kz.rm.intelligence.CanonicalFactAppender;
import kz.rm.intelligence.CanonicalFactDeliveryService;
import kz.rm.intelligence.CanonicalFactDeliveryService.*;
import kz.rm.intelligence.CanonicalFactKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** P2 qualification: real BUS owner fixture + isolated synthetic historical sources. */
final class CanonicalDeliveryJourneyEvidence {
    private static final Instant START=Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant END=START.plus(Duration.ofDays(1));
    private static CanonicalFactKey key(UUID tenant,UUID event){
        return new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event);
    }
    private static Slice slice(UUID tenant,UUID run,Instant from,Instant until){
        return new Slice(tenant,run,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,from,until);
    }
    private static UUID source(JdbcTemplate jdbc,UUID tenant,UUID template,UUID event,Instant time,boolean invalid){
        UUID order=UUID.randomUUID();
        assertThat(jdbc.update("""
            INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
            SELECT tenant_id,?,pending_email_identity_id,buyer_user_id,'PLACED',total_amount+?,currency,?,fulfillment_mode
            FROM crm.sales_order WHERE tenant_id=? AND id=?
            """,order,invalid?1:0,Timestamp.from(time),tenant,template)).isEqualTo(1);
        assertThat(jdbc.update("""
            INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source)
            SELECT tenant_id,?,?,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source
            FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
            """,UUID.randomUUID(),order,tenant,template)).isEqualTo(1);
        assertThat(jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',?)",
            tenant,event,order,Timestamp.from(time))).isEqualTo(1);
        return order;
    }
    private static String legacy(UUID tenant,UUID event,UUID order,Instant time){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            String.join("|","RM-FACT-V1",tenant.toString(),"SALES","ORDER_PLACED",event.toString(),
                order.toString(),time.toString(),"NULL","PLACED").getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static void seedFact(JdbcTemplate jdbc,UUID tenant,UUID event,UUID order,Instant time,String digest){
        assertThat(jdbc.update("""
            INSERT INTO crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id,subject_id,occurred_at,schema_version,digest)
            VALUES (?,'SALES','ORDER_PLACED',?,?,?,1,?)
            """,tenant,event,order,Timestamp.from(time),digest)).isEqualTo(1);
    }
    private static void await(CountDownLatch latch){
        try{if(!latch.await(25,TimeUnit.SECONDS))throw new AssertionError("P2 latch timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private static <T> T result(Future<T> f){
        try{return f.get(40,TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        catch(ExecutionException|TimeoutException e){throw new AssertionError(e);}
    }
    private static long count(JdbcTemplate jdbc,String sql,Object...args){
        return jdbc.queryForObject(sql,Long.class,args);
    }
    private static final String CONSUMER="p2-projection-epoch-1";
    private static DatabaseHandler handler(JdbcTemplate jdbc,String table,String code){
        return f->{
            var k=f.key();
            assertThat(jdbc.update("INSERT INTO crm."+table+" (tenant_id,consumer_code,owner_module,source_kind,source_id,digest) VALUES (?,?,?,?,?,?)",
                k.tenantId(),code,k.owner().name(),k.kind().name(),k.sourceId(),f.digest())).isEqualTo(1);
            return Effect.APPLIED;
        };
    }
    private static List<Step> drain(CanonicalFactDeliveryService service,UUID tenant,String code,DatabaseHandler h){
        return service.consumeBatch(tenant,code,500,h);
    }
    static void qualify(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactAppender appender,
                        CanonicalFactDeliveryService service,UUID tenant,UUID otherTenant,UUID template){
        var tx=new TransactionTemplate(manager);
        String table="p2_test_effect_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE TABLE crm."+table+" (tenant_id UUID NOT NULL,consumer_code VARCHAR(96) NOT NULL,"
            +"owner_module VARCHAR(16) NOT NULL,source_kind VARCHAR(32) NOT NULL,source_id UUID NOT NULL,digest VARCHAR(64) NOT NULL,"
            +"PRIMARY KEY(tenant_id,consumer_code,owner_module,source_kind,source_id))");
        var pool=Executors.newFixedThreadPool(3);
        var release=new CountDownLatch(1);
        try{
            UUID run=UUID.randomUUID();
            Slice range=slice(tenant,run,START,END);
            service.openBackfill(range);service.openBackfill(range);
            assertThatThrownBy(()->service.openBackfill(slice(tenant,run,START,END.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("BACKFILL_RUN_SCOPE_CONFLICT");
            assertThatThrownBy(()->service.backfillNext(otherTenant,run)).hasMessage("BACKFILL_RUN_NOT_FOUND");
            service.openBackfill(slice(otherTenant,run,START,END));
            assertThat(service.coverage(otherTenant,run).sources()).isZero();
            service.openConsumer(tenant,CONSUMER);
            service.openConsumer(otherTenant,CONSUMER);
            assertThat(service.consumeNext(otherTenant,CONSUMER,handler(jdbc,table,CONSUMER))).isEmpty();
            assertThatThrownBy(()->service.consumeBatch(tenant,CONSUMER,501,handler(jdbc,table,CONSUMER)))
                .hasMessage("CANONICAL_BATCH_LIMIT");

            // Simulate process death before receipt COMMIT: DB effect is written,
            // then the handler fails. Both effect and receipt must disappear.
            assertThatThrownBy(()->service.consumeNext(tenant,CONSUMER,f->{
                handler(jdbc,table,CONSUMER).apply(f);
                throw new IllegalStateException("P2_EFFECT_ABORT");
            })).hasMessage("P2_EFFECT_ABORT");
            assertThat(count(jdbc,"SELECT count(*) FROM crm."+table+" WHERE tenant_id=?",tenant)).isZero();
            assertThat(count(jdbc,"SELECT count(*) FROM crm.int_fact_consumer_receipt WHERE tenant_id=? AND consumer_code=?",tenant,CONSUMER)).isZero();

            // Lower UUID / earlier timestamp commits AFTER the higher one was
            // discovered and delivered. Both discovery and backfill must see it.
            UUID low=new UUID(0,1),high=new UUID(0,100);
            var inserted=new CountDownLatch(1);
            Future<?> late=pool.submit(()->tx.executeWithoutResult(status->{
                source(jdbc,tenant,template,low,START.plusSeconds(1),false);
                assertThat(appender.append(key(tenant,low))).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
                inserted.countDown();await(release);
            }));
            await(inserted);
            tx.executeWithoutResult(status->{
                source(jdbc,tenant,template,high,START.plusSeconds(2),false);
                assertThat(appender.append(key(tenant,high))).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
            });
            assertThat(service.backfillBatch(tenant,run,10)).extracting(Step::key).containsExactly(key(tenant,high));
            assertThat(service.coverage(tenant,run).complete()).isTrue();
            drain(service,tenant,CONSUMER,handler(jdbc,table,CONSUMER));
            assertThat(service.consumerCoverage(tenant,CONSUMER).caughtUp()).isTrue();
            assertThat(count(jdbc,"SELECT count(*) FROM crm."+table+" WHERE tenant_id=? AND source_id=?",tenant,low)).isZero();
            release.countDown();result(late);
            // Simulate a fresh process using durable database state, no in-memory cursor.
            var restarted=new CanonicalFactDeliveryService(jdbc,manager,appender);
            assertThat(restarted.coverage(tenant,run).unprocessed()).isEqualTo(1);
            assertThat(restarted.backfillNext(tenant,run).orElseThrow().key()).isEqualTo(key(tenant,low));
            assertThat(restarted.coverage(tenant,run).complete()).isTrue();
            assertThat(restarted.consumeNext(tenant,CONSUMER,handler(jdbc,table,CONSUMER)).orElseThrow().key()).isEqualTo(key(tenant,low));
            assertThat(restarted.consumeNext(tenant,CONSUMER,handler(jdbc,table,CONSUMER))).isEmpty();

            UUID missing=new UUID(0,11),invalid=new UUID(0,12),conflict=new UUID(0,13),
                v1=new UUID(0,14),orphan=new UUID(0,15),validAfter=new UUID(0,16);
            String[] oldDigest=new String[1];
            tx.executeWithoutResult(status->{
                source(jdbc,tenant,template,missing,START.plusSeconds(3),false);
                source(jdbc,tenant,template,invalid,START.plusSeconds(4),true);
                UUID bad=source(jdbc,tenant,template,conflict,START.plusSeconds(5),false);
                seedFact(jdbc,tenant,conflict,bad,START.plusSeconds(5),"b".repeat(64));
                UUID legacy=source(jdbc,tenant,template,v1,START.plusSeconds(6),false);
                oldDigest[0]=legacy(tenant,v1,legacy,START.plusSeconds(6));
                seedFact(jdbc,tenant,v1,legacy,START.plusSeconds(6),oldDigest[0]);
                seedFact(jdbc,tenant,orphan,template,START.plusSeconds(7),"c".repeat(64));
                source(jdbc,tenant,template,validAfter,START.plusSeconds(8),false);
            });
            assertThat(restarted.backfillBatch(tenant,run,2)).extracting(Step::disposition)
                .containsExactly(Disposition.INSERTED,Disposition.INVALID);
            var f1=pool.submit(()->restarted.backfillBatch(tenant,run,10));
            var f2=pool.submit(()->service.backfillBatch(tenant,run,10));
            result(f1);result(f2);
            Coverage coverage=service.coverage(tenant,run);
            assertThat(coverage.sources()).isEqualTo(7);
            assertThat(coverage.unprocessed()).isZero();
            assertThat(coverage.missing()).isEqualTo(1);
            assertThat(coverage.rejected()).isEqualTo(2);
            assertThat(coverage.orphanFacts()).isEqualTo(1);
            assertThat(coverage.receiptMismatches()).isZero();
            assertThat(coverage.complete()).isFalse();
            assertThat(count(jdbc,"SELECT count(*) FROM crm.int_fact_backfill_receipt WHERE tenant_id=? AND run_id=?",tenant,run)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT error_code FROM crm.int_fact_backfill_receipt WHERE tenant_id=? AND run_id=? AND source_id=?",
                String.class,tenant,run,invalid)).isEqualTo("CANONICAL_SNAPSHOT_AMOUNT_MISMATCH");
            assertThat(jdbc.queryForObject("SELECT error_code FROM crm.int_fact_backfill_receipt WHERE tenant_id=? AND run_id=? AND source_id=?",
                String.class,tenant,run,conflict)).isEqualTo("CANONICAL_FACT_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT digest FROM crm.int_canonical_fact WHERE tenant_id=? AND source_id=?",String.class,tenant,v1)).isEqualTo(oldDigest[0]);
            assertThat(jdbc.queryForObject("SELECT schema_version FROM crm.int_canonical_fact WHERE tenant_id=? AND source_id=?",Integer.class,tenant,v1)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT digest FROM crm.int_canonical_fact WHERE tenant_id=? AND source_id=?",String.class,tenant,conflict)).isEqualTo("b".repeat(64));
            assertThat(restarted.backfillBatch(tenant,run,10)).isEmpty();

            // Legacy inventory source, including NULL posting_version, in its own
            // time slice. Synthetic ledger copy does not mutate live stock.
            UUID movement=UUID.randomUUID(),inventoryRun=UUID.randomUUID();
            tx.executeWithoutResult(status->{
                assertThat(jdbc.update("""
                    INSERT INTO crm.inv_stock_movement(tenant_id,id,location_id,item_id,request_key,delta_quantity,
                      balance_before,balance_after,occurred_at,recorded_at,reason_code,movement_kind,actor_user_id,
                      source_type,source_id,business_reason_code,posting_version)
                    SELECT tenant_id,?,location_id,item_id,?,delta_quantity,balance_before,balance_after,?,?,
                      reason_code,movement_kind,actor_user_id,source_type,source_id,business_reason_code,NULL
                    FROM crm.inv_stock_movement WHERE tenant_id=? AND reason_code='RECEIPT' ORDER BY id LIMIT 1
                    """,movement,"p2-legacy-"+movement,Timestamp.from(START),Timestamp.from(START),tenant)).isEqualTo(1);
            });
            service.openBackfill(new Slice(tenant,inventoryRun,CanonicalFactKey.Owner.INVENTORY,
                CanonicalFactKey.Kind.STOCK_MOVEMENT,START,END));
            assertThat(service.backfillNext(tenant,inventoryRun).orElseThrow().disposition()).isEqualTo(Disposition.INSERTED);
            assertThat(service.coverage(tenant,inventoryRun).complete()).isTrue();
            assertThat(count(jdbc,"SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=? AND source_id=? AND source_version IS NULL",tenant,movement)).isEqualTo(1);

            // Race actual concurrent appender and backfill for a committed owner source:
            // assert a database lock wait, then exercise commit and rollback orders.
            for(boolean rollback:List.of(false,true))overlap(jdbc,manager,appender,service,pool,tenant,template,rollback);

            // Concurrent workers with the same durable consumer identity.
            var c1=pool.submit(()->drain(service,tenant,CONSUMER,handler(jdbc,table,CONSUMER)));
            var c2=pool.submit(()->drain(restarted,tenant,CONSUMER,handler(jdbc,table,CONSUMER)));
            result(c1);result(c2);
            ConsumerCoverage end=service.consumerCoverage(tenant,CONSUMER);
            assertThat(end.pending()).isZero();
            assertThat(end.rejected()).isEqualTo(2);
            assertThat(end.receiptMismatches()).isZero();
            assertThat(end.caughtUp()).isFalse();
            assertThat(count(jdbc,"SELECT count(*) FROM crm."+table+" WHERE tenant_id=? AND consumer_code=?",tenant,CONSUMER)).isEqualTo(end.facts()-2);
            assertThat(restarted.consumeNext(tenant,CONSUMER,handler(jdbc,table,CONSUMER))).isEmpty();
            assertThat(count(jdbc,"SELECT count(*) FROM crm."+table+" WHERE tenant_id=?",otherTenant)).isZero();
            assertThat(service.coverage(otherTenant,run).sources()).isZero();
            String replay="p2-projection-epoch-2";
            restarted.openConsumer(tenant,replay);
            drain(restarted,tenant,replay,handler(jdbc,table,replay));
            assertThat(count(jdbc,"SELECT count(*) FROM crm."+table+" WHERE tenant_id=? AND consumer_code=?",tenant,replay)).isEqualTo(end.facts()-2);
            assertThatThrownBy(()->jdbc.update("DELETE FROM crm.int_fact_consumer_receipt WHERE tenant_id=? AND consumer_code=?",tenant,CONSUMER))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE crm.int_fact_backfill_receipt SET disposition=disposition WHERE tenant_id=? AND run_id=?",tenant,run))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }finally{
            release.countDown();pool.shutdownNow();
            try{if(!pool.awaitTermination(30,TimeUnit.SECONDS))throw new AssertionError("P2 pool did not stop");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            jdbc.execute("DROP TABLE crm."+table);
        }
    }
    private static void overlap(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactAppender appender,
                                CanonicalFactDeliveryService service,ExecutorService pool,UUID tenant,UUID template,boolean rollback){
        var tx=new TransactionTemplate(manager);
        Instant start=START.plus(Duration.ofDays(rollback?3:2));
        UUID run=UUID.randomUUID(),event=UUID.randomUUID();
        service.openBackfill(slice(tenant,run,start,start.plusSeconds(60)));
        tx.executeWithoutResult(status->source(jdbc,tenant,template,event,start.plusSeconds(1),false));
        var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
        var holder=new java.util.concurrent.atomic.AtomicInteger();
        try{
            Future<?> first=pool.submit(()->tx.executeWithoutResult(status->{
                holder.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                assertThat(appender.append(key(tenant,event))).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
                ready.countDown();await(release);
                if(rollback)status.setRollbackOnly();
            }));
            await(ready);
            Future<Step> second=pool.submit(()->service.backfillNext(tenant,run).orElseThrow());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            boolean blocked=false;
            while(System.nanoTime()<deadline){
                if(count(jdbc,"SELECT count(*) FROM pg_stat_activity WHERE pid<>? AND ?=ANY(pg_blocking_pids(pid))",holder.get(),holder.get())>0){
                    blocked=true;break;
                }
                try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }
            assertThat(blocked).as("backfill must wait for in-flight appender").isTrue();
            release.countDown();result(first);
            assertThat(result(second).disposition()).isEqualTo(rollback?Disposition.INSERTED:Disposition.VERIFIED);
            assertThat(service.coverage(tenant,run).complete()).isTrue();
        }finally{release.countDown();}
    }
}
