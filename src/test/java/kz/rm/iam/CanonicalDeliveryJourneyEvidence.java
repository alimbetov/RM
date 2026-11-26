package kz.rm.iam;

import kz.rm.intelligence.CanonicalFactAppender;
import kz.rm.intelligence.CanonicalFactDeliveryService;
import kz.rm.intelligence.CanonicalFactDeliveryService.*;
import kz.rm.intelligence.CanonicalFactKey;
import kz.rm.intelligence.BookedSourceReconciliationService;
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
                        CanonicalFactDeliveryService service,UUID tenant,UUID otherTenant,UUID template,
                        BookedSourceReconciliationService reconciliation,VerifiedActor staff,VerifiedActor otherOwner,
                        VerifiedActor otherReader,VerifiedActor owner,kz.rm.intelligence.BookedOwnerDeclarationService declarations,
                        Runnable revokeReader,Runnable restoreReader,TenantOwnershipTransferService transfer,
                        long monthlyCalendar,long otherMonthlyCalendar){
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
            // The reader of B is authorized in B but cannot resolve A's run before B has its own.
            assertThatThrownBy(()->reconciliation.inspect(otherReader,otherTenant,run))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("S1_BOOKED_RUN_NOT_FOUND");
            service.openBackfill(slice(otherTenant,run,START,END));
            assertThat(service.coverage(otherTenant,run).sources()).isZero();
            // A real existing run still must not be readable by an ungranted owner.
            assertThatThrownBy(()->reconciliation.inspect(otherOwner,otherTenant,run))
                .isInstanceOf(SecurityException.class).hasMessage("Tenant RBAC permission denied");
            var empty=reconciliation.inspect(otherReader,otherTenant,run);
            assertThat(empty.tenant()).isEqualTo(otherTenant);
            assertThat(empty.actor()).isEqualTo(otherReader.userId());
            assertThat(empty.runId()).isEqualTo(run);
            assertThat(empty.counts().owners()).isZero();
            assertThat(empty.state()).isEqualTo(BookedSourceReconciliationService.State.RECONCILED_PRESENT_SOURCE);
            assertThat(empty.ownerCompletenessProven()).isFalse();
            UUID declarationId=UUID.randomUUID();
            var emptyDraft=new kz.rm.intelligence.BookedOwnerDeclarationService.Draft(declarationId,run,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,"SYNTHETIC_IMPORT_MANIFEST","b".repeat(64),
                empty.sourceRevision(),empty.receiptRevision(),0,empty.digest().populationDigest());
            assertThatThrownBy(()->declarations.declare(otherReader,otherTenant,emptyDraft)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->declarations.declare(otherOwner,tenant,emptyDraft)).isInstanceOf(SecurityException.class);
            assertThat(declarations.declare(otherOwner,otherTenant,emptyDraft)).isEqualTo(declarationId);
            assertThat(declarations.declare(otherOwner,otherTenant,emptyDraft)).isEqualTo(declarationId);
            var candidate=reconciliation.inspectAuthority(otherReader,otherTenant,declarationId);
            assertThat(candidate.state()).isEqualTo(kz.rm.intelligence.BookedOwnerDeclarationService.State.MATCHED_CONTRACT_NOT_ADMITTED);
            assertThat(candidate.admitted()).isFalse();
            assertThatThrownBy(()->reconciliation.inspectAuthority(staff,tenant,declarationId)).hasMessage("S1_DECLARATION_NOT_FOUND");
            assertThatThrownBy(()->reconciliation.inspectAuthority(otherOwner,otherTenant,declarationId)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->declarations.revoke(otherReader,otherTenant,declarationId)).isInstanceOf(SecurityException.class);
            var conflicting=new kz.rm.intelligence.BookedOwnerDeclarationService.Draft(declarationId,run,
                emptyDraft.mode(),"DIFFERENT_MANIFEST",emptyDraft.provenanceDigest(),emptyDraft.sourceRevision(),emptyDraft.receiptRevision(),0,
                emptyDraft.expectedPopulationDigest());
            assertThatThrownBy(()->declarations.declare(otherOwner,otherTenant,conflicting)).hasMessage("S1_DECLARATION_ID_CONFLICT");
            declarations.revoke(otherOwner,otherTenant,declarationId);declarations.revoke(otherOwner,otherTenant,declarationId);
            assertThat(reconciliation.inspectAuthority(otherReader,otherTenant,declarationId).state())
                .isEqualTo(kz.rm.intelligence.BookedOwnerDeclarationService.State.REVOKED);
            assertThat(declarations.declare(otherOwner,otherTenant,emptyDraft)).isEqualTo(declarationId);
            assertThat(reconciliation.inspectAuthority(otherReader,otherTenant,declarationId).state())
                .isEqualTo(kz.rm.intelligence.BookedOwnerDeclarationService.State.REVOKED);
            assertThatThrownBy(()->jdbc.update("UPDATE crm.int_booked_owner_declaration SET provenance_ref=provenance_ref WHERE tenant_id=? AND proof_id=?",otherTenant,declarationId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM crm.int_booked_owner_revocation WHERE tenant_id=? AND proof_id=?",otherTenant,declarationId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            BookedDeclarationLifecycleEvidence.qualify(jdbc,declarations,reconciliation,transfer,otherOwner,otherReader,otherTenant,emptyDraft);
            assertThatThrownBy(()->reconciliation.inspect(otherOwner,tenant,run)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->reconciliation.inspect(otherReader,tenant,run)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->reconciliation.inspect(staff,otherTenant,run)).isInstanceOf(SecurityException.class);
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
            long revisionBefore=count(jdbc,"SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?",tenant);
            long foreignBefore=count(jdbc,"SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?",otherTenant);
            var inserted=new CountDownLatch(1);
            Future<?> late=pool.submit(()->tx.executeWithoutResult(status->{
                source(jdbc,tenant,template,low,START.plusSeconds(1),false);
                assertThat(appender.append(key(tenant,low))).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
                inserted.countDown();await(release);
            }));
            await(inserted);
            assertThat(count(jdbc,"SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?",tenant))
                .isEqualTo(revisionBefore);
            tx.executeWithoutResult(status->{
                source(jdbc,tenant,template,high,START.plusSeconds(2),false);
                assertThat(appender.append(key(tenant,high))).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
            });
            long afterHigh=count(jdbc,"SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?",tenant);
            assertThat(afterHigh).isEqualTo(revisionBefore+3);
            assertThat(service.backfillBatch(tenant,run,10)).extracting(Step::key).containsExactly(key(tenant,high));
            assertThat(service.coverage(tenant,run).complete()).isTrue();
            var beforeLate=reconciliation.inspect(staff,tenant,run);
            assertThat(beforeLate.state()).isEqualTo(BookedSourceReconciliationService.State.RECONCILED_PRESENT_SOURCE);
            assertThat(beforeLate.digest().checked()).isEqualTo(1);
            assertThat(beforeLate.counts().distinctValidOrders()).isEqualTo(1);
            assertThat(beforeLate.counts().invalidOwnerEvents()).isZero();
            assertThat(beforeLate.ownerCompletenessProven()).isFalse();
            var nativeDraft=new kz.rm.intelligence.BookedOwnerDeclarationService.Draft(declarationId,run,
                kz.rm.intelligence.BookedHistoryComposition.Mode.NATIVE_HISTORY,"SYNTHETIC_NATIVE_BOUNDARY","c".repeat(64),
                beforeLate.sourceRevision(),beforeLate.receiptRevision(),1,beforeLate.digest().populationDigest());
            assertThatThrownBy(()->declarations.declare(owner,tenant,nativeDraft)).hasMessage("S1_NATIVE_BOUNDARY");
            var importedDraft=new kz.rm.intelligence.BookedOwnerDeclarationService.Draft(declarationId,run,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,"SYNTHETIC_IMPORT_MANIFEST","c".repeat(64),
                beforeLate.sourceRevision(),beforeLate.receiptRevision(),1,beforeLate.digest().populationDigest());
            assertThat(declarations.declare(owner,tenant,importedDraft)).isEqualTo(declarationId);
            assertThat(reconciliation.inspectAuthority(staff,tenant,declarationId).state())
                .isEqualTo(kz.rm.intelligence.BookedOwnerDeclarationService.State.MATCHED_CONTRACT_NOT_ADMITTED);
            drain(service,tenant,CONSUMER,handler(jdbc,table,CONSUMER));
            assertThat(service.consumerCoverage(tenant,CONSUMER).caughtUp()).isTrue();
            assertThat(count(jdbc,"SELECT count(*) FROM crm."+table+" WHERE tenant_id=? AND source_id=?",tenant,low)).isZero();
            release.countDown();result(late);
            assertThat(count(jdbc,"SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?",tenant))
                .isEqualTo(afterHigh+3);
            assertThat(count(jdbc,"SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?",otherTenant))
                .isEqualTo(foreignBefore);
            // The next identity can be allocated and rolled back; cardinality
            // must not change and identity gaps must not invalidate evidence.
            tx.executeWithoutResult(status->{
                UUID rolled=UUID.randomUUID();
                source(jdbc,tenant,template,rolled,START.plusSeconds(9),false);
                assertThat(appender.append(key(tenant,rolled))).isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
                assertThat(count(jdbc,"SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?",tenant))
                    .isEqualTo(afterHigh+6);
                status.setRollbackOnly();
            });
            assertThat(count(jdbc,"SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?",tenant))
                .isEqualTo(afterHigh+3);
            assertThatThrownBy(()->jdbc.update("DELETE FROM crm.int_source_revision_event WHERE tenant_id=?",tenant))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            // Simulate a fresh process using durable database state, no in-memory cursor.
            var restarted=new CanonicalFactDeliveryService(jdbc,manager,appender);
            assertThat(restarted.coverage(tenant,run).unprocessed()).isEqualTo(1);
            var afterLate=reconciliation.inspect(staff,tenant,run);
            assertThat(afterLate.sourceRevision()).isGreaterThan(beforeLate.sourceRevision());
            assertThat(afterLate.counts().unprocessed()).isEqualTo(1);
            assertThat(afterLate.counts().distinctValidOrders()).isEqualTo(2);
            assertThat(afterLate.state()).isEqualTo(BookedSourceReconciliationService.State.INCOMPLETE);
            assertThat(reconciliation.inspectAuthority(staff,tenant,declarationId).state())
                .isEqualTo(kz.rm.intelligence.BookedOwnerDeclarationService.State.STALE_REVISION);
            assertThat(reconciliation.inspectAuthority(otherReader,otherTenant,declarationId).state())
                .isEqualTo(kz.rm.intelligence.BookedOwnerDeclarationService.State.REVOKED);
            UUID receiptBoundId=UUID.randomUUID();
            var receiptBoundDraft=new kz.rm.intelligence.BookedOwnerDeclarationService.Draft(receiptBoundId,run,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,"SYNTHETIC_IMPORT_MANIFEST_2","d".repeat(64),
                afterLate.sourceRevision(),afterLate.receiptRevision(),2,afterLate.digest().populationDigest());
            assertThat(declarations.declare(owner,tenant,receiptBoundDraft)).isEqualTo(receiptBoundId);
            assertThat(reconciliation.inspectAuthority(staff,tenant,receiptBoundId).state())
                .isEqualTo(kz.rm.intelligence.BookedOwnerDeclarationService.State.UNRECONCILED_SOURCE);
            assertThat(restarted.backfillNext(tenant,run).orElseThrow().key()).isEqualTo(key(tenant,low));
            assertThat(restarted.coverage(tenant,run).complete()).isTrue();
            var afterReceipt=reconciliation.inspect(staff,tenant,run);
            assertThat(afterReceipt.state()).isEqualTo(BookedSourceReconciliationService.State.RECONCILED_PRESENT_SOURCE);
            // A receipt-only commit changes reconciliation without changing the source journal.
            // This is evidence for the separate receipt revision requirement, not a READY proof.
            assertThat(afterReceipt.sourceRevision()).isEqualTo(afterLate.sourceRevision());
            assertThat(afterReceipt.receiptRevision()).isEqualTo(afterLate.receiptRevision()+1);
            assertThat(reconciliation.inspectAuthority(staff,tenant,receiptBoundId).state())
                .isEqualTo(kz.rm.intelligence.BookedOwnerDeclarationService.State.STALE_REVISION);
            assertThat(afterReceipt.revisionBinding()).isNotEqualTo(afterLate.revisionBinding());
            assertThat(afterReceipt.digest().populationDigest()).isEqualTo(afterLate.digest().populationDigest());
            assertThat(afterLate.digest().populationDigest()).isNotEqualTo(beforeLate.digest().populationDigest());
            assertThat(afterReceipt.counts().unprocessed()).isZero();
            assertThat(afterReceipt.ownerCompletenessProven()).isFalse();
            var stillEmpty=reconciliation.inspect(otherReader,otherTenant,run);
            assertThat(stillEmpty.tenant()).isEqualTo(otherTenant);
            assertThat(stillEmpty.sourceRevision()).isEqualTo(empty.sourceRevision());
            assertThat(stillEmpty.revisionBinding()).isEqualTo(empty.revisionBinding());
            assertThat(stillEmpty.digest().populationDigest()).isEqualTo(empty.digest().populationDigest());
            assertThat(stillEmpty.counts().owners()).isZero();
            assertThat(stillEmpty.counts().facts()).isZero();
            assertThat(stillEmpty.ownerCompletenessProven()).isFalse();
            // Split at high's timestamp: the left half owns low, the right half high.
            UUID left=UUID.randomUUID(),right=UUID.randomUUID();
            Instant split=START.plusSeconds(2);
            service.openBackfill(slice(tenant,left,START,split));
            service.openBackfill(slice(tenant,right,split,END));
            assertThat(service.backfillBatch(tenant,left,10)).hasSize(1);
            assertThat(service.backfillBatch(tenant,right,10)).hasSize(1);
            var composed=reconciliation.inspectComposition(staff,tenant,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,START,END,List.of(right,left));
            assertThat(composed.exactReconciledPartition()).isTrue();
            assertThat(composed.reconciledObservedOrders()).isEqualTo(2L);
            assertThat(composed.boundary()).isEqualTo(kz.rm.intelligence.BookedHistoryComposition.Boundary.IMPORTED_PRE_TENANT_AUTHORITY_REQUIRED);
            assertThat(composed.ownerCompletenessProven()).isFalse();
            kz.rm.intelligence.BookedDigestPgEvidence.parity(jdbc,manager,tenant,START,END,2,0,0);
            BookedMonthlyJourneyEvidence.qualify(service,reconciliation,declarations,owner,staff,tenant,monthlyCalendar,
                otherOwner,otherReader,otherTenant,otherMonthlyCalendar,START,run);
            BookedConcurrencyEvidence.qualify(jdbc,manager,reconciliation,otherReader,otherTenant,START,END,
                List.of(run),revokeReader,restoreReader);
            var barrier=new CyclicBarrier(2);
            var aRead=pool.submit(()->{barrier.await(15,TimeUnit.SECONDS);return reconciliation.inspectComposition(staff,tenant,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,START,END,List.of(left,right));});
            var bRead=pool.submit(()->{barrier.await(15,TimeUnit.SECONDS);return reconciliation.inspectComposition(otherReader,otherTenant,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,START,END,List.of(run));});
            assertThat(result(aRead).reconciledObservedOrders()).isEqualTo(2L);
            var concurrentB=result(bRead);
            assertThat(concurrentB.snapshot().tenant()).isEqualTo(otherTenant);
            assertThat(concurrentB.reconciledObservedOrders()).isNull();
            assertThat(concurrentB.slices().getFirst().counts().owners()).isZero();
            assertThatThrownBy(()->reconciliation.inspectComposition(staff,tenant,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,START,END,List.of(run,left,right,UUID.randomUUID(),UUID.randomUUID())))
                .hasMessage("S1_COMPOSITION_RUNS");
            assertThatThrownBy(()->reconciliation.inspectComposition(staff,tenant,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,START,START.plus(Duration.ofDays(125)),List.of()))
                .hasMessage("S1_COMPOSITION_RANGE");
            assertThat(composed.slices()).allSatisfy(e->{
                assertThat(e.asOf()).isEqualTo(composed.snapshot().asOf());
                assertThat(e.sourceRevision()).isEqualTo(composed.snapshot().sourceRevision());
                assertThat(e.receiptRevision()).isEqualTo(1);
            });
            var nativeHistory=reconciliation.inspectComposition(staff,tenant,
                kz.rm.intelligence.BookedHistoryComposition.Mode.NATIVE_HISTORY,START,END,List.of(left,right));
            assertThat(nativeHistory.reconciledObservedOrders()).isNull();
            assertThat(nativeHistory.boundary()).isEqualTo(kz.rm.intelligence.BookedHistoryComposition.Boundary.NATIVE_BEFORE_TENANT_CREATION);
            var overlap=reconciliation.inspectComposition(staff,tenant,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,START,END,List.of(left,run));
            assertThat(overlap.issues()).contains(kz.rm.intelligence.BookedHistoryComposition.Issue.OVERLAP);
            assertThat(overlap.reconciledObservedOrders()).isNull();
            assertThatThrownBy(()->reconciliation.inspectComposition(otherReader,otherTenant,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,START,END,List.of(left,right)))
                .hasMessage("S1_BOOKED_RUN_NOT_FOUND");
            assertThatThrownBy(()->reconciliation.inspectComposition(otherReader,tenant,
                kz.rm.intelligence.BookedHistoryComposition.Mode.IMPORTED_HISTORY,START,END,List.of(left,right)))
                .isInstanceOf(SecurityException.class);
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
            var corrupted=reconciliation.inspect(staff,tenant,run);
            assertThat(corrupted.state()).isEqualTo(BookedSourceReconciliationService.State.CONFLICT);
            assertThat(corrupted.digest().mismatches()).isEqualTo(2);
            assertThat(corrupted.digest().legacy()).isEqualTo(3);
            assertThat(corrupted.counts().missing()).isEqualTo(1);
            assertThat(corrupted.counts().extra()).isEqualTo(1);
            assertThat(corrupted.counts().rejected()).isEqualTo(2);
            assertThat(corrupted.ownerCompletenessProven()).isFalse();
            kz.rm.intelligence.BookedDigestPgEvidence.parity(jdbc,manager,tenant,START,END,
                7,2,3);
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
            kz.rm.intelligence.BookedDigestPgEvidence.limits(jdbc,manager,tenant,template,START.plus(Duration.ofDays(10)));
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
