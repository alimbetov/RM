package kz.rm.iam;

import kz.rm.intelligence.CanonicalFactAppender;
import kz.rm.intelligence.CanonicalFactKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.transaction.TransactionDefinition;
import static org.assertj.core.api.Assertions.*;

/** Real committed owner sources, isolated fixture; this does not claim producer wiring. */
final class CanonicalFactJourneyEvidence {
    static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactAppender appender,
                       UUID tenant,UUID order,UUID foreignTenant){
        var tx=new TransactionTemplate(manager);
        UUID event=jdbc.queryForObject("""
            SELECT id FROM crm.sales_order_status_event
            WHERE tenant_id=? AND order_id=? AND event_type='PLACED'
            """,UUID.class,tenant,order);
        var key=new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event);
        assertThatThrownBy(()->appender.append(key)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        tx.executeWithoutResult(s->{assertThat(appender.append(key)).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);s.setRollbackOnly();});
        assertThat(count(jdbc,tenant,event)).isZero();
        CanonicalFactAppender.Outcome inserted=tx.execute(s->appender.append(key));
        assertThat(inserted).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
        CanonicalFactAppender.Outcome replay=tx.execute(s->appender.append(key));
        assertThat(replay).isEqualTo(CanonicalFactAppender.Outcome.ALREADY_PRESENT);
        assertThat(count(jdbc,tenant,event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.iam_workspace WHERE id=?",Integer.class,foreignTenant)).isEqualTo(1);
        assertThat(foreignTenant).isNotEqualTo(tenant);
        var wrongKind=new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_CONFIRMED,event);
        assertThatThrownBy(()->tx.execute(s->appender.append(wrongKind))).hasMessage("CANONICAL_SOURCE_NOT_FOUND");
        var wrong=new CanonicalFactKey(foreignTenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event);
        assertThatThrownBy(()->tx.execute(s->appender.append(wrong))).hasMessage("CANONICAL_SOURCE_NOT_FOUND");
        assertThat(count(jdbc,foreignTenant,event)).isZero();
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
        if(pg){
            assertThatThrownBy(()->jdbc.update("UPDATE crm.sales_order_status_event SET occurred_at=occurred_at WHERE tenant_id=? AND id=?",tenant,event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE crm.sales_order SET total_amount=total_amount+1 WHERE tenant_id=? AND id=?",tenant,order)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE crm.sales_order_line SET quantity=quantity WHERE tenant_id=? AND order_id=?",tenant,order)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM crm.sales_order_status_event WHERE tenant_id=? AND id=?",tenant,event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM crm.int_canonical_fact WHERE tenant_id=? AND source_id=?",tenant,event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
    static void ownerRollback(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactAppender appender,
                              UUID tenant,String requestKey,Supplier<UUID> createOrder){
        var tx=new TransactionTemplate(manager);
        var order=new AtomicReference<UUID>();
        var event=new AtomicReference<UUID>();
        tx.executeWithoutResult(status->{
            order.set(createOrder.get());
            event.set(statusEvent(jdbc,tenant,order.get(),"PLACED"));
            var key=new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event.get());
            assertThat(appender.append(key)).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
            assertThat(count(jdbc,tenant,event.get())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=? AND idempotency_key=?",Integer.class,tenant,requestKey)).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(order.get()).isNotNull();
        assertThat(event.get()).isNotNull();
        assertThat(count(jdbc,tenant,event.get())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=? AND id=?",Integer.class,tenant,order.get())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?",Integer.class,tenant,order.get())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=?",Integer.class,tenant,order.get())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=? AND idempotency_key=?",Integer.class,tenant,requestKey)).isZero();
    }

    static void concurrentReplayOnPostgres(JdbcTemplate jdbc,PlatformTransactionManager manager,
                                           CanonicalFactAppender appender,UUID tenant,UUID order,UUID cancelledOrder){
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
        if(!pg)return; // H2 is not concurrency qualification; the strict journey workflow uses PostgreSQL.
        for(var scenario:List.of(
                new Race(statusEvent(jdbc,tenant,order,"CONFIRMED"),CanonicalFactKey.Kind.ORDER_CONFIRMED,2),
                new Race(statusEvent(jdbc,tenant,order,"COMPLETED"),CanonicalFactKey.Kind.ORDER_COMPLETED,8),
                new Race(statusEvent(jdbc,tenant,cancelledOrder,"CANCELLED"),CanonicalFactKey.Kind.ORDER_CANCELLED,16))){
            var key=new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,scenario.kind(),scenario.event());
            assertThat(count(jdbc,tenant,scenario.event())).isZero();
            race(jdbc,manager,appender,key,scenario.calls());
            assertThat(count(jdbc,tenant,scenario.event())).isEqualTo(1);
        }
        UUID movement=jdbc.queryForObject("""
            SELECT id FROM crm.inv_stock_movement WHERE tenant_id=? AND source_type='SALES_ORDER'
                AND source_id=? AND movement_kind='FULFILLMENT'
            """,UUID.class,tenant,order);
        var key=new CanonicalFactKey(tenant,CanonicalFactKey.Owner.INVENTORY,CanonicalFactKey.Kind.STOCK_MOVEMENT,movement);
        race(jdbc,manager,appender,key,2);
        assertThat(count(jdbc,tenant,movement)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.int_canonical_fact f JOIN crm.inv_stock_movement m
              ON m.tenant_id=f.tenant_id AND m.id=f.source_id
            WHERE f.tenant_id=? AND f.source_id=? AND f.schema_version=2
              AND f.source_version=m.posting_version
            """,Integer.class,tenant,movement)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("UPDATE crm.inv_stock_movement SET delta_quantity=delta_quantity WHERE tenant_id=? AND id=?",tenant,movement))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    private record Race(UUID event,CanonicalFactKey.Kind kind,int calls){}
    private record Attempt(int backend,CanonicalFactAppender.Outcome outcome){}
    private static void race(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactAppender appender,
                             CanonicalFactKey key,int calls){
        var workers=Executors.newFixedThreadPool(2);
        var barrier=new CyclicBarrier(2);
        var futures=new ArrayList<Future<Attempt>>();
        try {
            for(int i=0;i<calls;i++)futures.add(workers.submit(()->{
                var tx=new TransactionTemplate(manager);
                tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                return tx.execute(status->{
                    int backend=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                    try{barrier.await(15,TimeUnit.SECONDS);}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    catch(BrokenBarrierException|TimeoutException e){throw new IllegalStateException(e);}
                    return new Attempt(backend,appender.append(key));
                });
            }));
            var results=new ArrayList<Attempt>();
            for(var f:futures)results.add(f.get(45,TimeUnit.SECONDS));
            assertThat(results.stream().map(Attempt::backend).distinct().count()).isGreaterThanOrEqualTo(2L);
            assertThat(results.stream().filter(a->a.outcome()==CanonicalFactAppender.Outcome.INSERTED).count()).isEqualTo(1L);
            assertThat(results.stream().filter(a->a.outcome()==CanonicalFactAppender.Outcome.ALREADY_PRESENT).count()).isEqualTo((long)calls-1);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError("Concurrent canonical append interrupted",e);}
         catch(ExecutionException|TimeoutException e){throw new AssertionError("Concurrent canonical append failed",e);}
        finally{workers.shutdownNow();}
    }
    private static UUID statusEvent(JdbcTemplate jdbc,UUID tenant,UUID order,String event){
        return jdbc.queryForObject("SELECT id FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=? AND event_type=?",
            UUID.class,tenant,order,event);
    }
    private static int count(JdbcTemplate jdbc,UUID tenant,UUID event){
        return jdbc.queryForObject("SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=? AND source_id=?",Integer.class,tenant,event);
    }
}
