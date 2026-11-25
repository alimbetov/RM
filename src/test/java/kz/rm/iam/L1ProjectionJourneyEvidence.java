package kz.rm.iam;

import kz.rm.intelligence.*;
import kz.rm.intelligence.CanonicalFactDeliveryService.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

final class L1ProjectionJourneyEvidence {
    private static BigDecimal total(L1ProjectionService s,UUID tenant,UUID gen,LocalDate from,LocalDate until,MetricRegistry.Code metric){
        BigDecimal sum=BigDecimal.ZERO;
        for(LocalDate day=from;day.isBefore(until);day=day.plusDays(1))
            for(var b:s.buckets(tenant,gen,day))if(b.metric()==metric)sum=sum.add(b.amount());
        return sum;
    }
    private static void expected(L1ProjectionService s,UUID tenant,UUID gen,LocalDate from,LocalDate until,String placed,String booked,String in){
        var values=Map.of(MetricRegistry.Code.PLACED_ORDERS,placed,MetricRegistry.Code.BOOKED_ORDER_AMOUNT,booked,
            MetricRegistry.Code.CONFIRMED_ORDERS,"2",MetricRegistry.Code.FULFILLED_ORDERS,"1",
            MetricRegistry.Code.CANCELLED_ORDERS,"1",MetricRegistry.Code.STOCK_IN_QTY,in,MetricRegistry.Code.STOCK_OUT_QTY,"3");
        for(var v:values.entrySet())assertThat(total(s,tenant,gen,from,until,v.getKey())).as(v.getKey().name()).isEqualByComparingTo(v.getValue());
    }
    static void qualify(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactAppender appender,
                        CanonicalFactDeliveryService delivery,L1ProjectionService l1,UUID tenant,UUID otherTenant,
                        UUID order,UUID warehouse,UUID sku,long calendarVersion){
        Instant orderTime=jdbc.queryForObject("SELECT occurred_at FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=? AND event_type='PLACED'",
            Timestamp.class,tenant,order).toInstant();
        LocalDate from=orderTime.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1),until=from.plusDays(3);
        UUID gen=UUID.randomUUID(),shadow=UUID.randomUUID();
        var g=l1.open(tenant,gen,calendarVersion,from,until);
        assertThat(l1.open(tenant,gen,calendarVersion,from,until)).isEqualTo(g);
        assertThatThrownBy(()->l1.open(tenant,gen,calendarVersion,from,until.plusDays(1))).hasMessage("L1_GENERATION_SCOPE_CONFLICT");
        assertThatThrownBy(()->l1.buckets(otherTenant,gen,from)).hasMessage("L1_GENERATION_NOT_FOUND");
        assertThatThrownBy(()->delivery.consumeBatch(tenant,g.consumerCode(),1,f->Effect.OUTSIDE_COVERAGE)).hasMessage("CANONICAL_RESERVED_CONSUMER");
        assertThatThrownBy(()->l1.advance(tenant,gen,501)).hasMessage("CANONICAL_BATCH_LIMIT");
        var initialProof=l1.prove(tenant,gen);
        assertThat(initialProof.parity()).isFalse();
        assertThat(l1.fenceStatus(tenant,gen,initialProof.id())).isEqualTo(L1ProjectionService.FenceStatus.INCOMPLETE);
        assertThat(l1.fenceStatus(tenant,gen,UUID.randomUUID())).isEqualTo(L1ProjectionService.FenceStatus.UNPROVEN);
        assertThat(l1.route(tenant,calendarVersion,1,from,until).reason()).isEqualTo("NO_ACTIVE_GENERATION");
        assertThat(l1.route(otherTenant,calendarVersion,1,from,until).materialized()).isFalse();
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.int_active_generation(tenant_id,projection_code,generation_id,proof_id,lifecycle_state)
            VALUES (?,'L1',?,?,'ACTIVE')
            """,tenant,gen,initialProof.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var tx=new TransactionTemplate(manager);
        // Inject receipt failure after the handler. Both contributions/buckets and receipt must rollback.
        String function="l1_test_fault_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION crm."+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"
            +tenant+"'::uuid AND NEW.consumer_code='"+g.consumerCode()+"' THEN RAISE EXCEPTION 'L1_RECEIPT_FAULT'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER "+function+" BEFORE INSERT ON crm.int_fact_consumer_receipt FOR EACH ROW EXECUTE FUNCTION crm."+function+"()");
        try{
            assertThatThrownBy(()->l1.advance(tenant,gen,1)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasStackTraceContaining("L1_RECEIPT_FAULT");
            for(LocalDate d=from;d.isBefore(until);d=d.plusDays(1))assertThat(l1.buckets(tenant,gen,d)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_l1_sales_contribution WHERE tenant_id=? AND generation_id=?",Long.class,tenant,gen)).isZero();
            assertThat(delivery.consumerCoverage(tenant,g.consumerCode()).pending()).isPositive();
            assertThat(l1.fenceStatus(tenant,gen,initialProof.id())).isEqualTo(L1ProjectionService.FenceStatus.INCOMPLETE);
        }finally{
            jdbc.execute("DROP TRIGGER "+function+" ON crm.int_fact_consumer_receipt");
            jdbc.execute("DROP FUNCTION crm."+function+"()");
        }
        // Actual two concurrent workers, same durable generation. P2 serializes
        // each effect+receipt and a new service instance resumes after commit.
        var pool=Executors.newFixedThreadPool(2);
        try{
            Future<?> a=pool.submit(()->l1.advance(tenant,gen,500));
            Future<?> b=pool.submit(()->new L1ProjectionService(jdbc,manager,delivery).advance(tenant,gen,500));
            a.get(60,TimeUnit.SECONDS);b.get(60,TimeUnit.SECONDS);
        }catch(Exception e){throw new AssertionError("L1 concurrent apply",e);}
        finally{pool.shutdownNow();}
        assertThat(l1.advance(tenant,gen,500)).isEmpty();
        expected(l1,tenant,gen,from,until,"2","375","10");
        var beforeLate=l1.prove(tenant,gen);
        assertThat(beforeLate.parity()).isTrue();
        assertThat(l1.fenceStatus(tenant,gen,beforeLate.id())).isEqualTo(L1ProjectionService.FenceStatus.CURRENT);
        assertThat(l1.fenceStatus(tenant,gen,initialProof.id())).isEqualTo(L1ProjectionService.FenceStatus.STALE);
        assertThat(l1.route(tenant,calendarVersion,1,from,until).materialized()).isFalse();
        tx.executeWithoutResult(status->{
            assertThat(jdbc.update("UPDATE crm.int_daily_bucket SET amount=amount+1 WHERE tenant_id=? AND generation_id=? AND metric_code='PLACED_ORDERS'",tenant,gen)).isPositive();
            status.setRollbackOnly();
        });
        assertThat(l1.fenceStatus(tenant,gen,beforeLate.id())).isEqualTo(L1ProjectionService.FenceStatus.CURRENT);

        // An out-of-window historical duplicate PLACED invalidates the in-window
        // contribution, even though the late duplicate is not in the L1 date range.
        UUID duplicate=UUID.randomUUID();
        Instant earlier=from.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
        tx.executeWithoutResult(status->{
            assertThat(jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',?)",
                tenant,duplicate,order,Timestamp.from(earlier))).isEqualTo(1);
            assertThat(appender.append(new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,duplicate)))
                .isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
        });
        assertThat(l1.fenceStatus(tenant,gen,beforeLate.id())).isEqualTo(L1ProjectionService.FenceStatus.STALE);
        var stale=l1.prove(tenant,gen);
        assertThat(stale.pendingFacts()).isEqualTo(1);
        assertThat(stale.bucketMismatches()).isPositive();
        assertThat(stale.parity()).isFalse();
        assertThat(l1.advance(tenant,gen,500)).hasSize(1);
        BigDecimal remaining=jdbc.queryForObject("""
            SELECT sum(o.total_amount) FROM crm.sales_order o WHERE o.tenant_id=? AND o.id<>?
            AND EXISTS(SELECT 1 FROM crm.sales_order_status_event e WHERE e.tenant_id=o.tenant_id AND e.order_id=o.id AND e.event_type='PLACED')
            """,BigDecimal.class,tenant,order);
        expected(l1,tenant,gen,from,until,"1",remaining.toPlainString(),"10");
        var beforeMissing=l1.prove(tenant,gen);
        assertThat(beforeMissing.parity()).isTrue();
        assertThat(l1.fenceStatus(tenant,gen,beforeMissing.id())).isEqualTo(L1ProjectionService.FenceStatus.CURRENT);

        // Historical inventory occurs outside the range but is recorded inside.
        // Default P3 run must recover by recorded_at, not occurred_at.
        UUID movement=UUID.randomUUID(),run=UUID.randomUUID();
        Instant recorded=from.plusDays(1).atStartOfDay(ZoneOffset.UTC).plusHours(1).toInstant();
        tx.executeWithoutResult(status->{
            assertThat(jdbc.update("""
                INSERT INTO crm.inv_stock_movement(tenant_id,id,location_id,item_id,request_key,delta_quantity,balance_before,balance_after,
                  occurred_at,recorded_at,reason_code,movement_kind,actor_user_id,source_type,source_id,business_reason_code,posting_version)
                SELECT tenant_id,?,location_id,item_id,?,delta_quantity,balance_before,balance_after,?,?,
                    reason_code,movement_kind,actor_user_id,source_type,source_id,business_reason_code,NULL
                FROM crm.inv_stock_movement WHERE tenant_id=? AND reason_code='RECEIPT' ORDER BY id LIMIT 1
                """,movement,"l1-legacy-"+movement,Timestamp.from(earlier),Timestamp.from(recorded),tenant)).isEqualTo(1);
        });
        assertThat(l1.fenceStatus(tenant,gen,beforeMissing.id())).isEqualTo(L1ProjectionService.FenceStatus.STALE);
        assertThat(l1.prove(tenant,gen).missingFacts()).isEqualTo(1);
        delivery.openBackfill(new Slice(tenant,run,CanonicalFactKey.Owner.INVENTORY,CanonicalFactKey.Kind.STOCK_MOVEMENT,
            from.atStartOfDay(ZoneOffset.UTC).toInstant(),until.atStartOfDay(ZoneOffset.UTC).toInstant()));
        assertThat(delivery.backfillBatch(tenant,run,500)).anySatisfy(step->{
            assertThat(step.key().sourceId()).isEqualTo(movement);assertThat(step.disposition()).isEqualTo(Disposition.INSERTED);
        });
        assertThat(delivery.coverage(tenant,run).complete()).isTrue();
        assertThat(l1.prove(tenant,gen).pendingFacts()).isEqualTo(1);
        assertThat(l1.advance(tenant,gen,500)).hasSize(1);
        expected(l1,tenant,gen,from,until,"1",remaining.toPlainString(),"20");
        assertThat(l1.prove(tenant,gen).parity()).isTrue();
        assertThat(l1.buckets(tenant,gen,from.plusDays(1))).anySatisfy(bucket->{
            assertThat(bucket.metric()).isEqualTo(MetricRegistry.Code.STOCK_IN_QTY);
            assertThat(bucket.warehouse()).isEqualTo(warehouse);
            assertThat(bucket.sku()).isEqualTo(sku);
        });

        // Replay a new generation independently; old generation remains intact.
        var beforeShadow=l1.prove(tenant,gen);
        l1.open(tenant,shadow,calendarVersion,from,until);
        l1.advance(tenant,shadow,500);
        var shadowProof=l1.prove(tenant,shadow);
        assertThat(shadowProof.parity()).isTrue();
        assertThat(l1.fenceStatus(tenant,gen,beforeShadow.id())).isEqualTo(L1ProjectionService.FenceStatus.CURRENT);
        assertThat(l1.fenceStatus(tenant,shadow,shadowProof.id())).isEqualTo(L1ProjectionService.FenceStatus.CURRENT);
        assertThat(l1.fenceStatus(tenant,gen,shadowProof.id())).isEqualTo(L1ProjectionService.FenceStatus.UNPROVEN);
        assertThatThrownBy(()->l1.fenceStatus(otherTenant,gen,beforeShadow.id())).hasMessage("L1_GENERATION_NOT_FOUND");
        for(LocalDate day=from;day.isBefore(until);day=day.plusDays(1))
            assertThat(l1.buckets(tenant,shadow,day)).isEqualTo(l1.buckets(tenant,gen,day));
        assertThatThrownBy(()->jdbc.update("UPDATE crm.int_projection_generation SET from_day=from_day WHERE tenant_id=? AND generation_id=?",tenant,gen))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // Independent oracle detects corrupted materialization; never silently READY.
        tx.executeWithoutResult(status->{
            assertThat(jdbc.update("UPDATE crm.int_daily_bucket SET amount=amount+1 WHERE tenant_id=? AND generation_id=? AND metric_code='PLACED_ORDERS'",tenant,shadow)).isPositive();
        });
        assertThat(l1.fenceStatus(tenant,shadow,shadowProof.id())).isEqualTo(L1ProjectionService.FenceStatus.STALE);
        assertThat(l1.fenceStatus(tenant,gen,beforeShadow.id())).isEqualTo(L1ProjectionService.FenceStatus.CURRENT);
        assertThat(l1.prove(tenant,shadow).bucketMismatches()).isPositive();
        assertThat(l1.prove(tenant,gen).parity()).isTrue();
        assertThat(l1.lifecycle(tenant,gen)).isEqualTo(new L1ProjectionService.Lifecycle("BUILDING",0));
        assertThat(l1.lifecycle(tenant,shadow)).isEqualTo(new L1ProjectionService.Lifecycle("BUILDING",0));
        assertThatThrownBy(()->l1.abort(otherTenant,shadow,0)).hasMessage("L1_GENERATION_NOT_FOUND");
        assertThatThrownBy(()->l1.abort(tenant,shadow,1)).hasMessage("L1_LIFECYCLE_REVISION_CONFLICT");
        assertThatThrownBy(()->jdbc.update("UPDATE crm.int_projection_lifecycle SET lifecycle_state='READY' WHERE tenant_id=? AND generation_id=?",tenant,shadow))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var saved=l1.buckets(tenant,shadow,from);
        var receipts=delivery.consumerCoverage(tenant,"l1:"+shadow);
        assertThat(l1.abort(tenant,shadow,0)).isEqualTo(new L1ProjectionService.Lifecycle("ABORTED",1));
        assertThat(l1.abort(tenant,shadow,0)).isEqualTo(new L1ProjectionService.Lifecycle("ABORTED",1));
        assertThat(l1.fenceStatus(tenant,shadow,shadowProof.id())).isEqualTo(L1ProjectionService.FenceStatus.ABORTED);
        assertThatThrownBy(()->l1.advance(tenant,shadow,1)).hasMessage("L1_GENERATION_NOT_BUILDING");
        assertThatThrownBy(()->l1.open(tenant,shadow,calendarVersion,from,until)).hasMessage("L1_GENERATION_NOT_BUILDING");
        assertThatThrownBy(()->jdbc.update("UPDATE crm.int_projection_lifecycle SET lifecycle_state='BUILDING',revision=0 WHERE tenant_id=? AND generation_id=?",tenant,shadow))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(l1.buckets(tenant,shadow,from)).isEqualTo(saved);
        assertThat(delivery.consumerCoverage(tenant,"l1:"+shadow)).isEqualTo(receipts);
        assertThat(l1.lifecycle(tenant,gen)).isEqualTo(new L1ProjectionService.Lifecycle("BUILDING",0));
        selectorPlans(jdbc,manager,tenant);
    }
    /** Bounded selector-only experiment, not end-to-end apply throughput. */
    private static void selectorPlans(JdbcTemplate jdbc,PlatformTransactionManager manager,UUID tenant){
        new TransactionTemplate(manager).executeWithoutResult(status->{
            jdbc.execute("CREATE TEMP TABLE l1_plan_fact (tenant_id uuid,owner_module varchar(16),source_kind varchar(32),source_id uuid,PRIMARY KEY(tenant_id,owner_module,source_kind,source_id)) ON COMMIT DROP");
            jdbc.execute("CREATE TEMP TABLE l1_plan_receipt (tenant_id uuid,consumer_code varchar(96),owner_module varchar(16),source_kind varchar(32),source_id uuid,PRIMARY KEY(tenant_id,consumer_code,owner_module,source_kind,source_id)) ON COMMIT DROP");
            String select="""
                SELECT f.source_id FROM l1_plan_fact f WHERE f.tenant_id=?
                  AND NOT EXISTS (SELECT 1 FROM l1_plan_receipt r WHERE r.tenant_id=f.tenant_id
                    AND r.consumer_code='plan' AND r.owner_module=f.owner_module
                    AND r.source_kind=f.source_kind AND r.source_id=f.source_id)
                ORDER BY f.owner_module,f.source_kind,f.source_id LIMIT 1
                """;
            for(int size:List.of(1000,10000,100000)){
                jdbc.execute("TRUNCATE l1_plan_receipt,l1_plan_fact");
                jdbc.update("""
                    INSERT INTO l1_plan_fact SELECT CAST(? AS uuid),'SALES','ORDER_PLACED',
                      CAST(lpad(to_hex(n),32,'0') AS uuid) FROM generate_series(1,?) n
                    """,tenant,size);
                jdbc.update("""
                    INSERT INTO l1_plan_receipt SELECT tenant_id,'plan',owner_module,source_kind,source_id
                      FROM l1_plan_fact WHERE source_id<>?
                    """,new UUID(0,size));
                jdbc.execute("ANALYZE l1_plan_fact");
                jdbc.execute("ANALYZE l1_plan_receipt");
                assertThat(jdbc.queryForObject(select,UUID.class,tenant)).isEqualTo(new UUID(0,size));
                String plan=jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+select,String.class,tenant);
                assertThat(plan).contains("Execution Time","Actual Rows");
                System.out.println("L1_SELECTOR_PLAN rows="+size+" "+plan);
            }
        });
    }
}
