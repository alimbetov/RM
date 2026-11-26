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
                        UUID order,UUID warehouse,UUID sku,long calendarVersion,
                        org.springframework.context.ApplicationContext context,VerifiedActor staff,Runnable revoke,Runnable restore,VerifiedActor foreign){
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
        var l2=new L2ProjectionService(jdbc,manager,delivery);
        String l2Code=l2.open(tenant,gen);
        assertThat(l2.open(tenant,gen)).isEqualTo(l2Code);
        assertThatThrownBy(()->delivery.openConsumer(tenant,l2Code)).hasMessage("CANONICAL_RESERVED_CONSUMER");
        assertThat(delivery.consumerCoverage(tenant,l2Code).pending()).isPositive();
        assertThat(l2.advance(tenant,gen,500)).isNotEmpty();
        assertThat(delivery.consumerCoverage(tenant,l2Code).caughtUp()).isTrue();
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM crm.int_l2_contribution WHERE tenant_id=? AND generation_id=? AND stream_code='BOOKED'",BigDecimal.class,tenant,gen)).isEqualByComparingTo("375");
        assertThat(jdbc.queryForObject("SELECT sum(qty_in) FROM crm.int_l2_contribution WHERE tenant_id=? AND generation_id=? AND stream_code='STOCK'",BigDecimal.class,tenant,gen)).isEqualByComparingTo("10");
        long dimBefore=jdbc.queryForObject("SELECT dirty_revision FROM crm.int_dim_revision WHERE tenant_id=? AND generation_id=?",Long.class,tenant,gen);
        assertThat(l2.advance(tenant,gen,500)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT dirty_revision FROM crm.int_dim_revision WHERE tenant_id=? AND generation_id=?",Long.class,tenant,gen)).isEqualTo(dimBefore);
        var l3=new L3RollupService(jdbc,manager);
        LocalDate bookedDay=orderTime.atZone(ZoneOffset.UTC).toLocalDate();
        var roll=l3.refresh(tenant,gen,PeriodResolver.Code.D,bookedDay);
        assertThat(roll.cells()).isPositive();
        assertThat(roll.memberships()).isPositive();
        assertThat(roll.revision()).isEqualTo(dimBefore);
        assertThat(jdbc.queryForObject("""
            SELECT count(DISTINCT o.order_id) FROM crm.int_l3_cell_order o
            JOIN crm.int_l3_cell x USING(tenant_id,generation_id,period_code,from_day,cell_id)
            WHERE o.tenant_id=? AND o.generation_id=? AND o.period_code='D' AND o.from_day=? AND x.stream_code='BOOKED'
            """,Long.class,tenant,gen,bookedDay)).isEqualTo(jdbc.queryForObject("""
            SELECT count(DISTINCT order_id) FROM crm.int_l2_contribution
            WHERE tenant_id=? AND generation_id=? AND local_day=? AND stream_code='BOOKED'
            """,Long.class,tenant,gen,bookedDay));
        assertThatThrownBy(()->l3.refresh(tenant,gen,PeriodResolver.Code.Y,bookedDay)).hasMessage("L3_PARTIAL_PERIOD");
        assertThatThrownBy(()->l3.refresh(otherTenant,gen,PeriodResolver.Code.D,bookedDay)).hasMessage("L3_GENERATION_NOT_BUILDING");
        tx.executeWithoutResult(status->{
            assertThat(jdbc.update("DELETE FROM crm.int_l3_cell_order WHERE tenant_id=? AND generation_id=? AND period_code='D' AND from_day=?",tenant,gen,bookedDay)).isPositive();
            assertThat(jdbc.queryForObject("SELECT refreshed_revision FROM crm.int_l3_period WHERE tenant_id=? AND generation_id=? AND period_code='D' AND from_day=?",Long.class,tenant,gen,bookedDay)).isNull();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT refreshed_revision FROM crm.int_l3_period WHERE tenant_id=? AND generation_id=? AND period_code='D' AND from_day=?",Long.class,tenant,gen,bookedDay)).isEqualTo(dimBefore);
        assertThat(l3.refresh(tenant,gen,PeriodResolver.Code.D,bookedDay)).isEqualTo(roll);
        var dimProof=new MultidimensionalCoverageService(jdbc,manager);
        // B4.5C-R1: scope must resolve the exact tenant/generation even with
        // calendar, lifecycle, consumer and revision relations sharing tenant_id.
        assertThat(dimProof.status(tenant,gen,UUID.randomUUID()))
            .isEqualTo(MultidimensionalCoverageService.Status.UNPROVEN);
        assertThatThrownBy(()->dimProof.status(otherTenant,gen,UUID.randomUUID()))
            .hasMessage("INT_DIM_GENERATION_NOT_FOUND");
        assertThatThrownBy(()->dimProof.prove(tenant,UUID.randomUUID()))
            .hasMessage("INT_DIM_GENERATION_NOT_FOUND");
        var incomplete=dimProof.prove(tenant,gen);
        assertThat(incomplete.delivery()).isZero();
        assertThat(incomplete.contributions()).isZero();
        assertThat(incomplete.cells()).isZero();
        assertThat(incomplete.memberships()).isZero();
        assertThat(incomplete.periods()).isPositive();
        assertThat(dimProof.status(tenant,gen,incomplete.id())).isEqualTo(MultidimensionalCoverageService.Status.INCOMPLETE);
        // A forged zero-vector proof with exact current revisions cannot bypass
        // the live parity check. The fixture owner intentionally has INSERT rights.
        UUID forged=UUID.randomUUID();
        assertThat(jdbc.update("""
            INSERT INTO crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id,observed_at,
              source_revision,dirty_revision,receipt_revision,materialization_revision,lifecycle_revision,
              delivery_mismatches,contribution_mismatches,period_mismatches,cell_mismatches,membership_mismatches)
            SELECT tenant_id,generation_id,?,statement_timestamp(),source_revision,dirty_revision,receipt_revision,
              materialization_revision,lifecycle_revision,0,0,0,0,0 FROM crm.int_dim_coverage_proof
            WHERE tenant_id=? AND generation_id=? AND proof_id=?
            """,forged,tenant,gen,incomplete.id())).isEqualTo(1);
        assertThat(dimProof.status(tenant,gen,forged)).isEqualTo(MultidimensionalCoverageService.Status.INCOMPLETE);
        assertThatThrownBy(()->jdbc.update("UPDATE crm.int_dim_coverage_proof SET period_mismatches=0 WHERE tenant_id=? AND generation_id=? AND proof_id=?",tenant,gen,incomplete.id()))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // Complete manifest of all fully contained periods, including empty days.
        for(var code:PeriodResolver.Code.values()){
            LocalDate cursor=from;
            while(cursor.isBefore(until)){
                var interval=PeriodResolver.resolve(code,cursor,new PeriodResolver.Policy(g.zone(),calendarVersion));
                LocalDate a=interval.startInclusive().atZone(g.zone()).toLocalDate();
                LocalDate b=interval.endExclusive().atZone(g.zone()).toLocalDate();
                if(!a.isBefore(from)&&!b.isAfter(until))l3.refresh(tenant,gen,code,cursor);
                cursor=b;
            }
        }
        // Fault-injection only: simulate a storage administrator changing BOTH digests
        // without touching revision counters. Restore before the next journey assertion.
        String l2Consumer="l2:"+gen;
        UUID placedSource=jdbc.queryForObject("SELECT id FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=? AND event_type='PLACED'",UUID.class,tenant,order);
        String originalDigest=jdbc.queryForObject("SELECT digest FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED' AND source_id=?",String.class,tenant,placedSource);
        String wrongDigest="0".repeat(64).equals(originalDigest)?"1".repeat(64):"0".repeat(64);
        try {
            tx.executeWithoutResult(status->{
                jdbc.execute("ALTER TABLE crm.int_canonical_fact DISABLE TRIGGER trg_int_fact_immutable");
                jdbc.execute("ALTER TABLE crm.int_fact_consumer_receipt DISABLE TRIGGER trg_int_consumer_receipt_immutable");
                assertThat(jdbc.update("UPDATE crm.int_canonical_fact SET digest=? WHERE tenant_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED' AND source_id=?",wrongDigest,tenant,placedSource)).isEqualTo(1);
                assertThat(jdbc.update("UPDATE crm.int_fact_consumer_receipt SET digest=? WHERE tenant_id=? AND consumer_code=? AND owner_module='SALES' AND source_kind='ORDER_PLACED' AND source_id=?",wrongDigest,tenant,l2Consumer,placedSource)).isEqualTo(1);
                jdbc.execute("ALTER TABLE crm.int_fact_consumer_receipt ENABLE TRIGGER trg_int_consumer_receipt_immutable");
                jdbc.execute("ALTER TABLE crm.int_canonical_fact ENABLE TRIGGER trg_int_fact_immutable");
            });
            var corrupt=dimProof.prove(tenant,gen);
            assertThat(corrupt.delivery()).isEqualTo(1);
            assertThat(corrupt.parity()).isFalse();
            assertThat(dimProof.status(tenant,gen,corrupt.id())).isEqualTo(MultidimensionalCoverageService.Status.INCOMPLETE);
            assertThat(corrupt.contributions()).isZero();
        } finally {
            tx.executeWithoutResult(status->{
                jdbc.execute("ALTER TABLE crm.int_canonical_fact DISABLE TRIGGER trg_int_fact_immutable");
                jdbc.execute("ALTER TABLE crm.int_fact_consumer_receipt DISABLE TRIGGER trg_int_consumer_receipt_immutable");
                jdbc.update("UPDATE crm.int_canonical_fact SET digest=? WHERE tenant_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED' AND source_id=?",originalDigest,tenant,placedSource);
                jdbc.update("UPDATE crm.int_fact_consumer_receipt SET digest=? WHERE tenant_id=? AND consumer_code=? AND owner_module='SALES' AND source_kind='ORDER_PLACED' AND source_id=?",originalDigest,tenant,l2Consumer,placedSource);
                jdbc.execute("ALTER TABLE crm.int_fact_consumer_receipt ENABLE TRIGGER trg_int_consumer_receipt_immutable");
                jdbc.execute("ALTER TABLE crm.int_canonical_fact ENABLE TRIGGER trg_int_fact_immutable");
            });
        }
        var complete=dimProof.prove(tenant,gen);
        assertThat(complete.parity()).isTrue();
        assertThat(dimProof.status(tenant,gen,complete.id())).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
        tx.executeWithoutResult(status->{
            jdbc.update("DELETE FROM crm.int_l3_cell_order WHERE tenant_id=? AND generation_id=? AND period_code='D' AND from_day=?",tenant,gen,bookedDay);
            status.setRollbackOnly();
        });
        assertThat(dimProof.status(tenant,gen,complete.id())).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
        l3.refresh(tenant,gen,PeriodResolver.Code.D,bookedDay);
        assertThat(dimProof.status(tenant,gen,complete.id())).isEqualTo(MultidimensionalCoverageService.Status.STALE);
        var refreshedProof=dimProof.prove(tenant,gen);
        assertThat(refreshedProof.parity()).isTrue();
        assertThatThrownBy(()->dimProof.status(otherTenant,gen,refreshedProof.id())).hasMessage("INT_DIM_GENERATION_NOT_FOUND");
        var feature=kz.rm.intelligence.FeatureStoreJourneyEvidence.qualify(context,jdbc,manager,staff,tenant,gen,refreshedProof.id(),warehouse,sku,bookedDay.minusDays(1),revoke,restore,foreign);
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
        assertThat(dimProof.status(tenant,gen,refreshedProof.id())).isEqualTo(MultidimensionalCoverageService.Status.STALE);
        feature.run();
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
        assertThat(l2.advance(tenant,gen,500)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM crm.int_l2_contribution WHERE tenant_id=? AND generation_id=? AND stream_code='BOOKED'",BigDecimal.class,tenant,gen)).isEqualByComparingTo(remaining);
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
        assertThat(l2.advance(tenant,gen,500)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT sum(qty_in) FROM crm.int_l2_contribution WHERE tenant_id=? AND generation_id=? AND stream_code='STOCK'",BigDecimal.class,tenant,gen)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_l2_contribution WHERE tenant_id=? AND generation_id=? AND stream_code='STOCK' AND source_id=? AND group_id IS NULL AND region_code IS NULL",Long.class,tenant,gen,movement)).isEqualTo(1L);
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
        // B4.6: late proof must fail, then rebuild every affected period before activation.
        assertThatThrownBy(()->dimProof.activate(tenant,gen,refreshedProof.id(),null))
            .hasMessage("INT_ACTIVATION_PROOF_NOT_CURRENT");
        for(var code:PeriodResolver.Code.values()){
            LocalDate cursor=from;
            while(cursor.isBefore(until)){
                var interval=PeriodResolver.resolve(code,cursor,new PeriodResolver.Policy(g.zone(),calendarVersion));
                LocalDate a=interval.startInclusive().atZone(g.zone()).toLocalDate();
                LocalDate b=interval.endExclusive().atZone(g.zone()).toLocalDate();
                if(!a.isBefore(from)&&!b.isAfter(until))l3.refresh(tenant,gen,code,cursor);
                cursor=b;
            }
        }
        var activationProof=dimProof.prove(tenant,gen);
        assertThat(activationProof.parity()).isTrue();
        assertThatThrownBy(()->dimProof.activate(otherTenant,gen,activationProof.id(),null))
            .hasMessage("INT_ACTIVATION_NOT_BUILDING");
        // B4.8: compare owner-source and active L3 on identical authorized scopes.
        var reader=context.getBean(AuthorizedAnalyticalReadService.class);
        var queries=new ArrayList<AuthorizedAnalyticalReadService.Query>();
        for(var metric:AuthorizedAnalyticalReadService.Metric.values()){
            boolean booked=metric.name().startsWith("BOOKED");
            boolean stock=metric.name().startsWith("STOCK");
            queries.add(new AuthorizedAnalyticalReadService.Query(metric,calendarVersion,PeriodResolver.Code.D,
                from.plusDays(1),null,null,stock?sku:null,booked?List.of():List.of(warehouse),
                metric.name().endsWith("AMOUNT")?jdbc.queryForObject("SELECT trim(currency) FROM crm.sales_order WHERE tenant_id=? AND id=?",String.class,tenant,order):null));
        }
        // Historical region/group filters use actual captured keys, never current catalog labels.
        for(var dim:jdbc.query("""
            SELECT DISTINCT region_code,group_id,sku_id,warehouse_id FROM crm.int_l2_contribution
            WHERE tenant_id=? AND generation_id=? AND stream_code='STOCK'
              AND region_code IS NOT NULL AND group_id IS NOT NULL AND sku_id IS NOT NULL AND warehouse_id=?
            """,(rs,n)->new Object[]{rs.getString(1),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class)},tenant,gen,warehouse)){
            for(var code:PeriodResolver.Code.values())
                queries.add(new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.STOCK_IN,
                    calendarVersion,code,from,(String)dim[0],(UUID)dim[1],(UUID)dim[2],List.of((UUID)dim[3]),null));
        }
        // Always exercise all calendar types, even when no classified stock exists.
        for(var code:PeriodResolver.Code.values())
            queries.add(new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.BOOKED_ORDERS,
                calendarVersion,code,from,null,null,null,List.of(),null));
        long l0Start=System.nanoTime();
        var baseline=queries.stream().map(q->reader.read(staff,tenant,q)).toList();
        long l0Elapsed=System.nanoTime()-l0Start;
        assertThat(baseline).allSatisfy(r->assertThat(r.route()).isEqualTo(AuthorizedAnalyticalReadService.Route.L0));
        var activated=dimProof.activate(tenant,gen,activationProof.id(),null);
        long l3Start=System.nanoTime();
        for(int i=0;i<queries.size();i++){
            var actual=reader.read(staff,tenant,queries.get(i));
            var expected=baseline.get(i);
            if(actual.period().endExclusive().isAfter(actual.observedAt())
                ||actual.period().startInclusive().atZone(g.zone()).toLocalDate().isBefore(from)
                ||actual.period().endExclusive().atZone(g.zone()).toLocalDate().isAfter(until)){
                assertThat(actual.route()).isEqualTo(AuthorizedAnalyticalReadService.Route.L0);
            }else{
                assertThat(actual.route()).isEqualTo(AuthorizedAnalyticalReadService.Route.L3);
                assertThat(actual.proof()).isEqualTo(activated.activeProof());
            }
            if(expected.value()==null)assertThat(actual.value()).isNull();
            else assertThat(actual.value()).isEqualByComparingTo(expected.value());
            assertThat(actual.quality()).isEqualTo(expected.quality());
            assertThat(actual.qualityReason()).isEqualTo(expected.qualityReason());
        }
        System.out.printf("B4.8 fixture queries=%d L0ms=%.3f mixedRouteMs=%.3f%n",queries.size(),l0Elapsed/1e6,(System.nanoTime()-l3Start)/1e6);
        assertThatThrownBy(()->reader.read(foreign,tenant,queries.getFirst())).isInstanceOf(SecurityException.class);
        var stockQuery=queries.stream().filter(q->q.metric()==AuthorizedAnalyticalReadService.Metric.STOCK_IN).findFirst().orElseThrow();
        var observation=context.getBean(BusinessObservationService.class);
        var dates=List.of(from,from.plusDays(1));
        var insight=observation.observe(staff,tenant,stockQuery,dates,1,BigDecimal.ONE,new BigDecimal("0.05"));
        assertThat(insight.evidence()).hasSize(2);
        assertThat(insight.evidence().getFirst().sourceRevision()).isEqualTo(insight.evidence().getLast().sourceRevision());
        assertThat(insight.evidence().getFirst().observedAt()).isEqualTo(insight.evidence().getLast().observedAt());
        assertThat(insight.decisionEligibility()).isEqualTo("BLOCKED");
        assertThat(insight.quality()).isNotEqualTo(DataQuality.READY);
        var series=observation.observeSeries(staff,tenant,stockQuery,dates,1,BigDecimal.ONE,new BigDecimal("0.05"));
        assertThat(series.trend().direction()).isEqualTo("UNAVAILABLE");
        assertThat(series.trend().decisionEligibility()).isEqualTo("BLOCKED");
        assertThat(series.trend().slope()).isNull();
        var pattern=observation.observePattern(staff,tenant,stockQuery,dates,1,BigDecimal.ONE,new BigDecimal("0.05"),
            1,new BigDecimal("3"),new BigDecimal("0.5"));
        assertThat(pattern.anomaly().pattern()).isEqualTo("UNAVAILABLE");
        assertThat(pattern.anomaly().decisionEligibility()).isEqualTo("BLOCKED");
        assertThat(pattern.anomaly().sma()).isNull();
        assertThat(pattern.anomaly().anomalyCandidate()).isFalse();
        assertThatThrownBy(()->observation.observePattern(foreign,tenant,stockQuery,dates,1,BigDecimal.ONE,BigDecimal.ZERO,
            1,new BigDecimal("3"),new BigDecimal("0.5"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->observation.observe(foreign,tenant,stockQuery,dates,1,BigDecimal.ONE,BigDecimal.ZERO)).isInstanceOf(SecurityException.class);
        var factor=context.getBean(BusinessFactorService.class);
        var salesScope=new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.FULFILLED_PRODUCT_UNITS,
            calendarVersion,PeriodResolver.Code.D,from.plusDays(1),null,null,sku,List.of(warehouse),null);
        var factors=factor.observe(staff,tenant,salesScope,from,1,BigDecimal.ONE,new BigDecimal("0.05"));
        assertThat(factors.evidence()).hasSize(3);
        var factorEvidence=factors.evidence().stream().flatMap(x->x.observations().stream()).toList();
        assertThat(factorEvidence).hasSize(6);
        assertThat(factorEvidence.stream().map(AuthorizedAnalyticalReadService.Result::sourceRevision).distinct().count()).isEqualTo(1);
        assertThat(factorEvidence.stream().map(AuthorizedAnalyticalReadService.Result::observedAt).distinct().count()).isEqualTo(1);
        assertThat(factors.decisionEligibility()).isEqualTo("BLOCKED");
        assertThat(factors.quality()).isNotEqualTo(DataQuality.READY);
        assertThat(factors.hypotheses()).isEmpty();
        assertThatThrownBy(()->factor.observe(foreign,tenant,salesScope,from,1,BigDecimal.ONE,BigDecimal.ZERO)).isInstanceOf(SecurityException.class);
        var conclusions=context.getBean(BusinessConclusionService.class);
        var conclusion=conclusions.concludePattern(staff,tenant,stockQuery,dates,1,BigDecimal.ONE,new BigDecimal("0.05"),
            1,new BigDecimal("3"),new BigDecimal("0.5"));
        assertThat(conclusion.findings()).allSatisfy(f->{
            assertThat(f.disposition()).isEqualTo(BusinessConclusionService.Disposition.ABSTAINED);
            assertThat(f.measures()).isEmpty();
        });
        assertThat(conclusion.evidence().getFirst().points()).hasSize(2);
        assertThat(conclusion.decisionEligibility()).isEqualTo(BusinessConclusionService.DecisionEligibility.BLOCKED);
        var factorConclusion=conclusions.concludeFactors(staff,tenant,salesScope,from,1,BigDecimal.ONE,new BigDecimal("0.05"));
        assertThat(factorConclusion.findings()).allSatisfy(f->assertThat(f.disposition()).isEqualTo(BusinessConclusionService.Disposition.ABSTAINED));
        assertThat(factorConclusion.evidence().stream().flatMap(e->e.points().stream())
            .map(AuthorizedAnalyticalReadService.Result::observedAt).distinct().count()).isEqualTo(1);
        assertThatThrownBy(()->conclusions.concludePattern(foreign,tenant,stockQuery,dates,1,BigDecimal.ONE,BigDecimal.ZERO,
            1,new BigDecimal("3"),new BigDecimal("0.5"))).isInstanceOf(SecurityException.class);
        revoke.run();
        try{
            assertThatThrownBy(()->conclusions.concludeFactors(staff,tenant,salesScope,from,1,BigDecimal.ONE,BigDecimal.ZERO))
                .isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->factor.observe(staff,tenant,salesScope,from,1,BigDecimal.ONE,BigDecimal.ZERO)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->reader.read(staff,tenant,stockQuery)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->observation.observe(staff,tenant,stockQuery,dates,1,BigDecimal.ONE,BigDecimal.ZERO)).isInstanceOf(SecurityException.class);
        }finally{restore.run();}

        ConclusionConcurrencyEvidence.boundedWindow(conclusions,staff,tenant,stockQuery);
        ConclusionConcurrencyEvidence.revokeBeforeRead(jdbc,manager,conclusions,staff,tenant,stockQuery,dates,revoke,restore);
        ConclusionConcurrencyEvidence.readBeforeRevoke(jdbc,manager,conclusions,staff,tenant,stockQuery,dates,revoke,restore);
        assertThat(activated.replay()).isFalse();
        assertThat(dimProof.status(tenant,gen,activated.activeProof())).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
        assertThat(dimProof.activate(tenant,gen,activationProof.id(),null).activeProof()).isEqualTo(activated.activeProof());
        assertThat(l1.lifecycle(tenant,gen)).isEqualTo(new L1ProjectionService.Lifecycle("ACTIVE",1));
        assertThat(l1.route(tenant,calendarVersion,1,from,until).materialized()).isFalse();
        assertThatThrownBy(()->l3.refresh(tenant,gen,PeriodResolver.Code.D,from)).hasMessage("L3_GENERATION_NOT_BUILDING");
        long otherCalendar=context.getBean(CalendarPolicyService.class).append(foreign,otherTenant,"Etc/UTC",0).version();
        ActivationJourneyEvidence.qualify(jdbc,manager,l1,l2,l3,dimProof,appender,tenant,otherTenant,
            gen,activated,calendarVersion,otherCalendar,from,until,g.zone(),order,reader,staff,stockQuery,conclusions);
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
