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
            assertThat(jdbc.queryForObject("SELECT booked_snapshot_sealed FROM crm.sales_order WHERE tenant_id=? AND id=?",Boolean.class,tenant,order)).isTrue();
            assertThatThrownBy(()->copyLine(jdbc,tenant,order,order)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("SALES_BOOKED_SNAPSHOT_SEALED");
            assertThatThrownBy(()->jdbc.update("UPDATE crm.sales_order SET booked_snapshot_sealed=FALSE WHERE tenant_id=? AND id=?",tenant,order)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("SALES_BOOKED_SNAPSHOT_IMMUTABLE");
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
    /** Synthetic isolated owner source, after the BUS metric assertions. */
    static void sealingRace(JdbcTemplate jdbc,PlatformTransactionManager manager,UUID tenant,UUID templateOrder){
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
        if(!pg)return;
        var tx=new TransactionTemplate(manager);
        UUID order=UUID.randomUUID(),event=UUID.randomUUID();
        tx.executeWithoutResult(s->{
            assertThat(jdbc.update("""
                INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
                SELECT tenant_id,?,pending_email_identity_id,buyer_user_id,'PLACED',total_amount,currency,created_at,fulfillment_mode
                FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,order,tenant,templateOrder)).isEqualTo(1);
            copyLine(jdbc,tenant,templateOrder,order);
        });
        assertThat(jdbc.queryForObject("SELECT booked_snapshot_sealed FROM crm.sales_order WHERE tenant_id=? AND id=?",Boolean.class,tenant,order)).isFalse();
        var workers=Executors.newFixedThreadPool(2);
        var sealed=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var contender=new AtomicReference<Integer>();
        var holder=new AtomicReference<Integer>();
        try{
            Future<?> first=workers.submit(()->tx.executeWithoutResult(s->{
                holder.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',CURRENT_TIMESTAMP)",tenant,event,order);
                sealed.countDown();
                await(finish);
            }));
            await(sealed);
            Future<?> second=workers.submit(()->{
                assertThatThrownBy(()->tx.executeWithoutResult(s->{
                    contender.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    copyLine(jdbc,tenant,templateOrder,order);
                })).isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasStackTraceContaining("SALES_BOOKED_SNAPSHOT_SEALED");
            });
            awaitBlocked(jdbc,contender,holder);
            finish.countDown();
            first.get(30,TimeUnit.SECONDS);
            second.get(30,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?",Integer.class,tenant,order)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT booked_snapshot_sealed FROM crm.sales_order WHERE tenant_id=? AND id=?",Boolean.class,tenant,order)).isTrue();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
         catch(ExecutionException|TimeoutException e){throw new AssertionError("Concurrent source sealing failed",e);}
        finally{finish.countDown();workers.shutdownNow();}
    }

    /** Complements seal-commit/late-insert denial with the other lock orders and outcomes. */
    static void sealingPermutations(JdbcTemplate jdbc,PlatformTransactionManager manager,UUID tenant,UUID templateOrder){
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
        if(!pg)return;
        for(var scenario:SealPermutation.values())sealPermutation(jdbc,manager,tenant,templateOrder,scenario);
    }

    private enum SealPermutation {
        LINE_COMMIT_THEN_SEAL(true,false,false,2,1,true),
        LINE_ROLLBACK_THEN_SEAL(true,true,false,1,1,true),
        SEAL_ROLLBACK_THEN_LINE(false,true,true,2,0,false),
        TWO_STATUS_EVENTS(false,false,false,1,2,true);
        final boolean firstLine,firstRollback,secondLine,expectedSeal;
        final int expectedLines,expectedEvents;
        SealPermutation(boolean firstLine,boolean firstRollback,boolean secondLine,
                        int expectedLines,int expectedEvents,boolean expectedSeal){
            this.firstLine=firstLine;this.firstRollback=firstRollback;this.secondLine=secondLine;
            this.expectedLines=expectedLines;this.expectedEvents=expectedEvents;this.expectedSeal=expectedSeal;
        }
    }

    private static void sealPermutation(JdbcTemplate jdbc,PlatformTransactionManager manager,
                                        UUID tenant,UUID templateOrder,SealPermutation scenario){
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        UUID order=UUID.randomUUID(),firstEvent=UUID.randomUUID(),secondEvent=UUID.randomUUID();
        tx.executeWithoutResult(s->{
            assertThat(jdbc.update("""
                INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
                SELECT tenant_id,?,pending_email_identity_id,buyer_user_id,'PLACED',total_amount*?,currency,created_at,fulfillment_mode
                FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,order,scenario.expectedLines,tenant,templateOrder)).isEqualTo(1);
            copyLine(jdbc,tenant,templateOrder,order);
        });
        assertThat(jdbc.queryForObject("SELECT booked_snapshot_sealed FROM crm.sales_order WHERE tenant_id=? AND id=?",
            Boolean.class,tenant,order)).isFalse();
        var workers=Executors.newFixedThreadPool(2);
        var firstReady=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var holder=new AtomicReference<Integer>();
        var contender=new AtomicReference<Integer>();
        try{
            Future<?> first=workers.submit(()->tx.executeWithoutResult(s->{
                holder.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                if(scenario.firstLine)copyLine(jdbc,tenant,templateOrder,order);
                else insertSealEvent(jdbc,tenant,order,firstEvent,"PLACED");
                firstReady.countDown();
                await(finish);
                if(scenario.firstRollback)s.setRollbackOnly();
            }));
            await(firstReady);
            Future<?> second=workers.submit(()->tx.executeWithoutResult(s->{
                contender.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                if(scenario.secondLine)copyLine(jdbc,tenant,templateOrder,order);
                else{
                    String kind=scenario==SealPermutation.TWO_STATUS_EVENTS?"CONFIRMED":"PLACED";
                    insertSealEvent(jdbc,tenant,order,secondEvent,kind);
                    if(scenario==SealPermutation.TWO_STATUS_EVENTS)
                        assertThat(jdbc.update("UPDATE crm.sales_order SET status='CONFIRMED',row_version=row_version+1 WHERE tenant_id=? AND id=?",tenant,order)).isEqualTo(1);
                }
            }));
            awaitBlocked(jdbc,contender,holder);
            finish.countDown();
            first.get(30,TimeUnit.SECONDS);
            second.get(30,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?",
                Integer.class,tenant,order)).as(scenario.name()).isEqualTo(scenario.expectedLines);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=?",
                Integer.class,tenant,order)).as(scenario.name()).isEqualTo(scenario.expectedEvents);
            assertThat(jdbc.queryForObject("SELECT booked_snapshot_sealed FROM crm.sales_order WHERE tenant_id=? AND id=?",
                Boolean.class,tenant,order)).as(scenario.name()).isEqualTo(scenario.expectedSeal);
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order o WHERE o.tenant_id=? AND o.id=? AND o.total_amount=
                    (SELECT SUM(l.quantity*l.unit_price_snapshot) FROM crm.sales_order_line l
                     WHERE l.tenant_id=o.tenant_id AND l.order_id=o.id)
                """,Integer.class,tenant,order)).isEqualTo(1);
            // Complete the isolated synthetic snapshot only after proving the rollback state.
            if(!scenario.expectedSeal)tx.executeWithoutResult(s->insertSealEvent(jdbc,tenant,order,UUID.randomUUID(),"PLACED"));
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(scenario.name(),e);}
         catch(ExecutionException|TimeoutException e){throw new AssertionError("Source sealing permutation failed: "+scenario.name(),e);}
        finally{finish.countDown();workers.shutdownNow();}
    }

    private static void insertSealEvent(JdbcTemplate jdbc,UUID tenant,UUID order,UUID event,String kind){
        assertThat(jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
            tenant,event,order,kind)).isEqualTo(1);
    }

    static void concurrentRollbackOnPostgres(JdbcTemplate jdbc,PlatformTransactionManager manager,
                                             CanonicalFactAppender appender,UUID tenant,UUID order){
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
        if(!pg)return;
        UUID event=statusEvent(jdbc,tenant,order,"PLACED");
        var key=new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event);
        assertThat(count(jdbc,tenant,event)).isZero();
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var workers=Executors.newFixedThreadPool(2);
        var inserted=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var holder=new AtomicReference<Integer>();
        var contender=new AtomicReference<Integer>();
        try{
            Future<?> first=workers.submit(()->tx.executeWithoutResult(s->{
                holder.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                assertThat(appender.append(key)).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
                inserted.countDown();
                await(finish);
                s.setRollbackOnly();
            }));
            await(inserted);
            Future<CanonicalFactAppender.Outcome> second=workers.submit(()->tx.execute(s->{
                contender.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                return appender.append(key);
            }));
            awaitBlocked(jdbc,contender,holder);
            finish.countDown();
            first.get(30,TimeUnit.SECONDS);
            assertThat(second.get(30,TimeUnit.SECONDS)).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
            assertThat(count(jdbc,tenant,event)).isEqualTo(1);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
         catch(ExecutionException|TimeoutException e){throw new AssertionError("Concurrent rollback failed",e);}
        finally{finish.countDown();workers.shutdownNow();}
    }

    private static void await(CountDownLatch latch){
        try{if(!latch.await(20,TimeUnit.SECONDS))throw new AssertionError("Latch timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private static void awaitBlocked(JdbcTemplate jdbc,AtomicReference<Integer> contender,AtomicReference<Integer> holder)
            throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){
            if(contender.get()!=null && holder.get()!=null && Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT ? = ANY(pg_blocking_pids(?))",Boolean.class,holder.get(),contender.get())))return;
            Thread.sleep(50);
        }
        throw new AssertionError("Expected PostgreSQL lock dependency not observed");
    }
    private static void copyLine(JdbcTemplate jdbc,UUID tenant,UUID sourceOrder,UUID targetOrder){
        assertThat(jdbc.update("""
            INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source)
            SELECT tenant_id,?,?,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source
            FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
            """,UUID.randomUUID(),targetOrder,tenant,sourceOrder)).isEqualTo(1);
    }

    /** Invalid historical/synthetic snapshot must not be promoted to canonical truth. */
    static void rejectInconsistentSnapshots(JdbcTemplate jdbc,PlatformTransactionManager manager,
                                             CanonicalFactAppender appender,UUID tenant,UUID templateOrder){
        var tx=new TransactionTemplate(manager);
        for(String expected:List.of("CANONICAL_SNAPSHOT_EMPTY","CANONICAL_SNAPSHOT_AMOUNT_MISMATCH",
                "CANONICAL_SNAPSHOT_CURRENCY_MISMATCH")){
            UUID order=UUID.randomUUID(),event=UUID.randomUUID();
            assertThatThrownBy(()->tx.executeWithoutResult(status->{
                boolean wrongAmount=expected.endsWith("AMOUNT_MISMATCH");
                boolean wrongCurrency=expected.endsWith("CURRENCY_MISMATCH");
                assertThat(jdbc.update("""
                    INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
                    SELECT tenant_id,?,pending_email_identity_id,buyer_user_id,'PLACED',total_amount+?,
                        CASE WHEN ? THEN CASE WHEN currency='USD' THEN 'KZT' ELSE 'USD' END ELSE currency END,
                        created_at,fulfillment_mode
                    FROM crm.sales_order WHERE tenant_id=? AND id=?
                    """,order,wrongAmount?1:0,wrongCurrency,tenant,templateOrder)).isEqualTo(1);
                if(!expected.endsWith("EMPTY"))copyLine(jdbc,tenant,templateOrder,order);
                jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',CURRENT_TIMESTAMP)",
                    tenant,event,order);
                appender.append(new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event));
            })).isInstanceOf(IllegalStateException.class).hasMessage(expected);
            assertThat(count(jdbc,tenant,event)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=? AND id=?",Integer.class,tenant,order)).isZero();
        }
    }

    /** Actual distinct workspaces; synthetic tenant-owned order rows, rolled back after the probe. */
    static void tenantCollision(JdbcTemplate jdbc,PlatformTransactionManager manager,
                                CanonicalFactAppender appender,UUID tenant,UUID order,UUID otherTenant){
        UUID event=statusEvent(jdbc,tenant,order,"PLACED");
        String original=jdbc.queryForObject("""
            SELECT digest FROM crm.int_canonical_fact
            WHERE tenant_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED' AND source_id=?
            """,String.class,tenant,event);
        assertThat(original).isNotNull();
        var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(status->{
            UUID store=UUID.randomUUID(),offer=UUID.randomUUID(),otherOrder=UUID.randomUUID();
            jdbc.update("INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at) VALUES (?,?,?,'DRAFT',CURRENT_TIMESTAMP)",
                otherTenant,store,"canonical-collision-"+store);
            jdbc.update("INSERT INTO crm.store_offer(tenant_id,id,title,unit_price,currency,state) VALUES (?,?,?,19,'KZT','DRAFT')",
                otherTenant,offer,"Synthetic independent tenant offer");
            assertThat(jdbc.update("""
                INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,status,total_amount,currency,created_at,fulfillment_mode)
                SELECT ?,?,pending_email_identity_id,'PLACED',19,'KZT',CURRENT_TIMESTAMP,'STANDARD'
                FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,otherTenant,otherOrder,tenant,order)).isEqualTo(1);
            jdbc.update("""
                INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency)
                VALUES (?,?,?,?,'Synthetic independent tenant line',1,19,'KZT')
                """,otherTenant,UUID.randomUUID(),otherOrder,offer);
            jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',CURRENT_TIMESTAMP)",
                otherTenant,event,otherOrder);
            var key=new CanonicalFactKey(otherTenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event);
            assertThat(appender.append(key)).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
            assertThat(appender.append(key)).isEqualTo(CanonicalFactAppender.Outcome.ALREADY_PRESENT);
            assertThat(jdbc.queryForObject("""
                SELECT subject_id FROM crm.int_canonical_fact
                WHERE tenant_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED' AND source_id=?
                """,UUID.class,otherTenant,event)).isEqualTo(otherOrder);
            assertThat(jdbc.queryForObject("""
                SELECT digest FROM crm.int_canonical_fact
                WHERE tenant_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED' AND source_id=?
                """,String.class,otherTenant,event)).isNotEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_canonical_fact WHERE source_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED'",Integer.class,event)).isEqualTo(2);
            status.setRollbackOnly();
        });
        assertThat(count(jdbc,otherTenant,event)).isZero();
        assertThat(count(jdbc,tenant,event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT digest FROM crm.int_canonical_fact
            WHERE tenant_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED' AND source_id=?
            """,String.class,tenant,event)).isEqualTo(original);
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
