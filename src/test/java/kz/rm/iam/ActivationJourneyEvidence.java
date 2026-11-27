package kz.rm.iam;

import kz.rm.intelligence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

final class ActivationJourneyEvidence {
    private static MultidimensionalCoverageService.Proof prepare(L1ProjectionService l1,L2ProjectionService l2,
            L3RollupService l3,MultidimensionalCoverageService coverage,UUID t,UUID g,long calendar,
            LocalDate from,LocalDate until,ZoneId zone){
        l1.open(t,g,calendar,from,until);
        l1.advance(t,g,500);l2.open(t,g);l2.advance(t,g,500);
        for(var code:PeriodResolver.Code.values()){
            LocalDate cursor=from;
            while(cursor.isBefore(until)){
                var period=PeriodResolver.resolve(code,cursor,new PeriodResolver.Policy(zone,calendar));
                LocalDate a=period.startInclusive().atZone(zone).toLocalDate(),b=period.endExclusive().atZone(zone).toLocalDate();
                if(!a.isBefore(from)&&!b.isAfter(until))l3.refresh(t,g,code,cursor);
                cursor=b;
            }
        }
        var proof=coverage.prove(t,g);assertThat(proof.parity()).isTrue();return proof;
    }
    private static UUID pointer(JdbcTemplate jdbc,UUID tenant){
        return jdbc.query("SELECT generation_id FROM crm.int_active_generation WHERE tenant_id=? AND projection_code='L3'",
            rs->rs.next()?rs.getObject(1,UUID.class):null,tenant);
    }
    private static long cells(JdbcTemplate jdbc,UUID tenant,UUID gen){
        return jdbc.queryForObject("SELECT count(*) FROM crm.int_l3_cell WHERE tenant_id=? AND generation_id=?",Long.class,tenant,gen);
    }
    private static void await(CountDownLatch l){
        try{if(!l.await(20,TimeUnit.SECONDS))throw new AssertionError("ACTIVATION_LATCH_TIMEOUT");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    static void qualify(JdbcTemplate jdbc,PlatformTransactionManager manager,L1ProjectionService l1,L2ProjectionService l2,
            L3RollupService l3,MultidimensionalCoverageService coverage,CanonicalFactAppender appender,
            UUID tenant,UUID otherTenant,UUID first,MultidimensionalCoverageService.Activation firstActivation,
            long calendar,long otherCalendar,LocalDate from,LocalDate until,ZoneId zone,UUID order,
            AuthorizedAnalyticalReadService reader,VerifiedActor staff,AuthorizedAnalyticalReadService.Query stockQuery,BusinessConclusionService conclusions,
            org.springframework.context.ApplicationContext context){
        // Select a definitely closed daily interval within the generation.
        var readQuery=new AuthorizedAnalyticalReadService.Query(stockQuery.metric(),calendar,PeriodResolver.Code.D,
            from,stockQuery.region(),stockQuery.group(),stockQuery.sku(),stockQuery.warehouses(),stockQuery.currency());
        assertThat(reader.read(staff,tenant,readQuery).route()).isEqualTo(AuthorizedAnalyticalReadService.Route.L3);
        var reuse=new SnapshotProofReuseEvidence(context,jdbc,manager);
        reuse.verify(staff,tenant,readQuery,first,firstActivation.activeProof(),MultidimensionalCoverageService.Status.CURRENT);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),foreign=UUID.randomUUID();
        var pa=prepare(l1,l2,l3,coverage,tenant,a,calendar,from,until,zone);
        var pb=prepare(l1,l2,l3,coverage,tenant,b,calendar,from,until,zone);
        var pf=prepare(l1,l2,l3,coverage,otherTenant,foreign,otherCalendar,from,until,ZoneId.of("Etc/UTC"));
        long originalCells=cells(jdbc,tenant,first);
        // Fail after lifecycle switch and proof insertion, before pointer commit.
        String fault="activation_fault_"+a.toString().replace("-","");
        jdbc.execute("CREATE FUNCTION crm."+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"
            +tenant+"'::uuid AND NEW.generation_id='"+a+"'::uuid THEN RAISE EXCEPTION 'ACTIVATION_TEST_FAULT'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER "+fault+" BEFORE INSERT ON crm.int_active_generation FOR EACH ROW EXECUTE FUNCTION crm."+fault+"()");
        try{
            assertThatThrownBy(()->coverage.activate(tenant,a,pa.id(),first)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasStackTraceContaining("ACTIVATION_TEST_FAULT");
            assertThat(pointer(jdbc,tenant)).isEqualTo(first);
            assertThat(l1.lifecycle(tenant,first)).isEqualTo(new L1ProjectionService.Lifecycle("ACTIVE",1));
            assertThat(l1.lifecycle(tenant,a)).isEqualTo(new L1ProjectionService.Lifecycle("BUILDING",0));
            assertThat(coverage.status(tenant,a,pa.id())).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_dim_coverage_proof WHERE tenant_id=? AND generation_id=? AND lifecycle_revision=1",Long.class,tenant,a)).isZero();
        }finally{
            jdbc.execute("DROP TRIGGER "+fault+" ON crm.int_active_generation");
            jdbc.execute("DROP FUNCTION crm."+fault+"()");
        }
        var pool=Executors.newFixedThreadPool(3);
        try{
            // Hold tenant A lock while B activates; B must finish before A releases.
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);
            var lock=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->{
                jdbc.queryForObject("SELECT id FROM public.iam_workspace WHERE id=? FOR UPDATE",UUID.class,tenant);
                held.countDown();await(release);
            }));
            try{
                await(held);
                var independent=pool.submit(()->coverage.activate(otherTenant,foreign,pf.id(),null));
                assertThat(independent.get(15,TimeUnit.SECONDS).generation()).isEqualTo(foreign);
            }finally{release.countDown();}
            lock.get(30,TimeUnit.SECONDS);
            assertThat(pointer(jdbc,tenant)).isEqualTo(first);
            assertThat(pointer(jdbc,otherTenant)).isEqualTo(foreign);
            // Two qualified replacements with the SAME expected predecessor: one winner.
            var start=new CyclicBarrier(2);
            var x=pool.submit(()->{start.await(15,TimeUnit.SECONDS);return compete(coverage,tenant,a,pa.id(),first);});
            var y=pool.submit(()->{start.await(15,TimeUnit.SECONDS);return compete(coverage,tenant,b,pb.id(),first);});
            var results=List.of(x.get(120,TimeUnit.SECONDS),y.get(120,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("ACTIVE","INT_ACTIVATION_POINTER_CONFLICT");
            UUID winner=pointer(jdbc,tenant),loser=winner.equals(a)?b:a;
            assertThat(winner).isIn(a,b);
            assertThat(l1.lifecycle(tenant,first)).isEqualTo(new L1ProjectionService.Lifecycle("RETIRED",2));
            assertThat(l1.lifecycle(tenant,loser)).isEqualTo(new L1ProjectionService.Lifecycle("BUILDING",0));
            assertThat(cells(jdbc,tenant,first)).isEqualTo(originalCells);
            assertThat(pointer(jdbc,otherTenant)).isEqualTo(foreign);
            // Late owner event holds FK key-share until commit. Activation must see
            // the newly committed revision, not the proof from before this event.
            var sourceWritten=new CountDownLatch(1);var commitSource=new CountDownLatch(1);
            UUID late=UUID.randomUUID();
            var source=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->{
                jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',?)",
                    tenant,late,order,Timestamp.from(from.minusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant()));
                appender.append(new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,late));
                sourceWritten.countDown();await(commitSource);
            }));
            try{
                await(sourceWritten);
                UUID proof=loser.equals(a)?pa.id():pb.id();
                var attempt=pool.submit(()->compete(coverage,tenant,loser,proof,winner));
                // Observe an actual PostgreSQL lock wait, not a sleep-based assumption.
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                boolean blocked=false;
                while(System.nanoTime()<deadline){
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                        WHERE pid<>pg_backend_pid() AND wait_event_type='Lock'
                          AND query LIKE '%iam_workspace%' AND query LIKE '%FOR UPDATE%'
                          AND cardinality(pg_blocking_pids(pid))>0)
                        """,Boolean.class));
                    if(blocked)break;
                    Thread.sleep(25);
                }
                assertThat(blocked).as("activation waits behind committed-source fence").isTrue();
                commitSource.countDown();source.get(30,TimeUnit.SECONDS);
                assertThat(attempt.get(120,TimeUnit.SECONDS)).isEqualTo("INT_ACTIVATION_PROOF_NOT_CURRENT");
                assertThat(pointer(jdbc,tenant)).isEqualTo(winner);
                assertThat(l1.lifecycle(tenant,loser)).isEqualTo(new L1ProjectionService.Lifecycle("BUILDING",0));
                UUID activeProof=jdbc.queryForObject("SELECT dim_proof_id FROM crm.int_active_generation WHERE tenant_id=? AND projection_code='L3'",UUID.class,tenant);
                assertThat(coverage.status(tenant,winner,activeProof)).isEqualTo(MultidimensionalCoverageService.Status.STALE);
                assertThat(coverage.status(otherTenant,foreign,jdbc.queryForObject("SELECT dim_proof_id FROM crm.int_active_generation WHERE tenant_id=? AND projection_code='L3'",UUID.class,otherTenant))).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
                var fallback=reader.read(staff,tenant,readQuery);
                assertThat(fallback.route()).isEqualTo(AuthorizedAnalyticalReadService.Route.L0);
                assertThat(fallback.reason()).isEqualTo("PROOF_STALE");
                assertThat(fallback.generation()).isNull();assertThat(fallback.proof()).isNull();
                reuse.verify(staff,tenant,readQuery,winner,activeProof,MultidimensionalCoverageService.Status.STALE);
                // B4.7: rebuild from a fresh generation, interrupt after a committed
                // bounded step, then resume with a new service instance.
                UUID rebuild=UUID.randomUUID();
                var request=new GenerationRebuildService.Request(tenant,rebuild,calendar,from,until);
                var delivery=new CanonicalFactDeliveryService(jdbc,manager,appender);
                var service=new GenerationRebuildService(jdbc,l1,l2,l3,delivery,coverage);
                var progress=service.step(request,1,1);
                assertThat(progress.state()).isEqualTo(GenerationRebuildService.State.REPLAY_PENDING);
                assertThat(progress.l2Steps()).isEqualTo(1);
                assertThat(pointer(jdbc,tenant)).isEqualTo(winner);
                long receiptsBefore=delivery.consumerCoverage(tenant,"l2:"+rebuild).pending();
                // Fail L2 receipt after its effect, with the first committed batch intact.
                String receiptFault="rebuild_receipt_"+rebuild.toString().replace("-","");
                jdbc.execute("CREATE FUNCTION crm."+receiptFault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"
                    +tenant+"'::uuid AND NEW.consumer_code='l2:"+rebuild+"' THEN RAISE EXCEPTION 'REBUILD_RECEIPT_FAULT'; END IF; RETURN NEW; END; $$");
                jdbc.execute("CREATE TRIGGER "+receiptFault+" BEFORE INSERT ON crm.int_fact_consumer_receipt FOR EACH ROW EXECUTE FUNCTION crm."+receiptFault+"()");
                String beforeL2=jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY stream_code,source_id),'[]'::jsonb)::text FROM crm.int_l2_contribution x WHERE tenant_id=? AND generation_id=?",String.class,tenant,rebuild);
                long beforeDirty=jdbc.queryForObject("SELECT dirty_revision FROM crm.int_dim_revision WHERE tenant_id=? AND generation_id=?",Long.class,tenant,rebuild);
                var failingService=service;
                try{
                    assertThatThrownBy(()->failingService.step(request,1,1)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("REBUILD_RECEIPT_FAULT");
                    assertThat(delivery.consumerCoverage(tenant,"l2:"+rebuild).pending()).isEqualTo(receiptsBefore);
                    assertThat(jdbc.queryForObject("SELECT dirty_revision FROM crm.int_dim_revision WHERE tenant_id=? AND generation_id=?",Long.class,tenant,rebuild)).isEqualTo(beforeDirty);
                    assertThat(jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY stream_code,source_id),'[]'::jsonb)::text FROM crm.int_l2_contribution x WHERE tenant_id=? AND generation_id=?",String.class,tenant,rebuild)).isEqualTo(beforeL2);
                }finally{
                    jdbc.execute("DROP TRIGGER "+receiptFault+" ON crm.int_fact_consumer_receipt");
                    jdbc.execute("DROP FUNCTION crm."+receiptFault+"()");
                }
                service=new GenerationRebuildService(jdbc,l1,l2,l3,delivery,coverage);
                boolean sawRollup=false;
                boolean injectedLate=false;
                long rebuildStarted=System.nanoTime();
                for(int i=0;i<200;i++){
                    progress=service.step(request,2,1);
                    assertThat(pointer(jdbc,tenant)).isEqualTo(winner);
                    if(progress.state()==GenerationRebuildService.State.ROLLUP_PENDING){
                        sawRollup=true;
                        if(!injectedLate){
                            injectedLate=true;
                            // A committed source arrives between partial rollup steps.
                            UUID during=UUID.randomUUID();
                            new TransactionTemplate(manager).executeWithoutResult(s->{
                                jdbc.update("INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at) VALUES (?,?,?,'PLACED',?)",
                                    tenant,during,order,Timestamp.from(from.minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant()));
                                appender.append(new CanonicalFactKey(tenant,CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,during));
                            });
                            assertThat(delivery.consumerCoverage(tenant,"l2:"+rebuild).pending()).isPositive();
                        }
                    }
                    if(progress.state()==GenerationRebuildService.State.READY)break;
                    assertThat(progress.state()).isIn(GenerationRebuildService.State.REPLAY_PENDING,
                        GenerationRebuildService.State.ROLLUP_PENDING);
                }
                assertThat(receiptsBefore).isPositive();
                assertThat(sawRollup).isTrue();
                assertThat(progress.state()).isEqualTo(GenerationRebuildService.State.READY);
                assertThat(delivery.consumerCoverage(tenant,"l2:"+rebuild).caughtUp()).isTrue();
                assertThat(coverage.status(tenant,rebuild,progress.proof())).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
                var replay=service.step(request,2,1);
                assertThat(replay.state()).isEqualTo(GenerationRebuildService.State.READY);
                assertThat(replay.l1Steps()).isZero();assertThat(replay.l2Steps()).isZero();assertThat(replay.refreshed()).isZero();
                assertThat(replay.proof()).isEqualTo(progress.proof());
                assertThat(injectedLate).isTrue();
                // Force a rollup transaction failure after cell deletion, before its stamp.
                String rollupFault="rebuild_rollup_"+rebuild.toString().replace("-","");
                jdbc.execute("CREATE FUNCTION crm."+rollupFault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"
                    +tenant+"'::uuid AND NEW.generation_id='"+rebuild+"'::uuid AND NEW.refreshed_revision IS NOT NULL THEN RAISE EXCEPTION 'REBUILD_ROLLUP_FAULT'; END IF; RETURN NEW; END; $$");
                jdbc.execute("CREATE TRIGGER "+rollupFault+" BEFORE UPDATE ON crm.int_l3_period FOR EACH ROW EXECUTE FUNCTION crm."+rollupFault+"()");
                long beforeCells=cells(jdbc,tenant,rebuild);
                try{
                    assertThatThrownBy(()->l3.refresh(tenant,rebuild,PeriodResolver.Code.D,from)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("REBUILD_ROLLUP_FAULT");
                    assertThat(cells(jdbc,tenant,rebuild)).isEqualTo(beforeCells);
                    assertThat(coverage.status(tenant,rebuild,replay.proof())).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
                }finally{
                    jdbc.execute("DROP TRIGGER "+rollupFault+" ON crm.int_l3_period");
                    jdbc.execute("DROP FUNCTION crm."+rollupFault+"()");
                }
                // Two callers resume the identical generation; no fresh stamp may be rewritten.
                var concurrentService=service;
                var rebuildBarrier=new CyclicBarrier(2);
                var rx=pool.submit(()->{rebuildBarrier.await(15,TimeUnit.SECONDS);return concurrentService.step(request,2,1);});
                var ry=pool.submit(()->{rebuildBarrier.await(15,TimeUnit.SECONDS);return concurrentService.step(request,2,1);});
                for(var result:List.of(rx.get(120,TimeUnit.SECONDS),ry.get(120,TimeUnit.SECONDS))){
                    assertThat(result.state()).isEqualTo(GenerationRebuildService.State.READY);
                    assertThat(result.refreshed()).isZero();
                    assertThat(result.proof()).isEqualTo(replay.proof());
                }
                System.out.printf("B4.7 rebuild fixture elapsedMs=%.3f facts=%d cells=%d%n",
                    (System.nanoTime()-rebuildStarted)/1_000_000.0,
                    delivery.consumerCoverage(tenant,"l2:"+rebuild).facts(),cells(jdbc,tenant,rebuild));
                var beforeSwitch=reader.read(staff,tenant,readQuery);
                assertThat(beforeSwitch.route()).isEqualTo(AuthorizedAnalyticalReadService.Route.L0);
                var switchBarrier=new CyclicBarrier(2);
                var switching=pool.submit(()->{switchBarrier.await(15,TimeUnit.SECONDS);return coverage.activate(tenant,rebuild,replay.proof(),winner);});
                var end=from.plusDays(1);
                var conclusionQuery=new AuthorizedAnalyticalReadService.Query(readQuery.metric(),calendar,PeriodResolver.Code.D,
                    end,readQuery.region(),readQuery.group(),readQuery.sku(),readQuery.warehouses(),readQuery.currency());
                var reading=pool.submit(()->{switchBarrier.await(15,TimeUnit.SECONDS);return conclusions.concludePattern(staff,tenant,
                    conclusionQuery,List.of(from,end),1,java.math.BigDecimal.ONE,java.math.BigDecimal.ZERO,1,new java.math.BigDecimal("3"),new java.math.BigDecimal("0.5"));});
                var recovered=switching.get(120,TimeUnit.SECONDS);
                var envelope=reading.get(120,TimeUnit.SECONDS);
                assertThat(envelope.tenant()).isEqualTo(tenant);
                var observations=envelope.evidence().getFirst().points();
                assertThat(observations).hasSize(2);
                assertThat(observations.stream().map(AuthorizedAnalyticalReadService.Result::observedAt).distinct().count()).isEqualTo(1);
                assertThat(observations.stream().map(AuthorizedAnalyticalReadService.Result::sourceRevision).distinct().count()).isEqualTo(1);
                var materialized=observations.stream().filter(e->e.route()==AuthorizedAnalyticalReadService.Route.L3).toList();
                assertThat(materialized).allSatisfy(e->{assertThat(e.generation()).isEqualTo(rebuild);assertThat(e.proof()).isEqualTo(recovered.activeProof());});
                assertThat(envelope.findings()).allSatisfy(f->assertThat(f.disposition()).isEqualTo(BusinessConclusionService.Disposition.ABSTAINED));
                var concurrentRead=observations.getFirst();
                if(concurrentRead.route()==AuthorizedAnalyticalReadService.Route.L3){
                    assertThat(concurrentRead.generation()).isEqualTo(rebuild);
                    assertThat(concurrentRead.proof()).isEqualTo(recovered.activeProof());
                }else assertThat(concurrentRead.generation()).isNull();
                var afterSwitch=reader.read(staff,tenant,readQuery);
                assertThat(afterSwitch.route()).isEqualTo(AuthorizedAnalyticalReadService.Route.L3);
                assertThat(afterSwitch.generation()).isEqualTo(rebuild);
                assertThat(afterSwitch.proof()).isEqualTo(recovered.activeProof());
                reuse.verify(staff,tenant,readQuery,rebuild,recovered.activeProof(),MultidimensionalCoverageService.Status.CURRENT);
                for(var value:List.of(concurrentRead,afterSwitch)){
                    if(beforeSwitch.value()==null)assertThat(value.value()).isNull();
                    else assertThat(value.value()).isEqualByComparingTo(beforeSwitch.value());
                }
                assertThat(pointer(jdbc,tenant)).isEqualTo(rebuild);
                assertThat(l1.lifecycle(tenant,winner)).isEqualTo(new L1ProjectionService.Lifecycle("RETIRED",2));
                assertThat(coverage.status(tenant,rebuild,recovered.activeProof())).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
                assertThat(pointer(jdbc,otherTenant)).isEqualTo(foreign);

            }finally{commitSource.countDown();}
        }catch(Exception e){throw new AssertionError("B4_ACTIVATION_JOURNEY",e);}
        finally{pool.shutdownNow();}
    }
    private static String compete(MultidimensionalCoverageService coverage,UUID t,UUID g,UUID p,UUID previous){
        try{coverage.activate(t,g,p,previous);return "ACTIVE";}
        catch(IllegalStateException e){return e.getMessage();}
    }
}
