package kz.rm.iam;

import kz.rm.intelligence.BookedOwnerDeclarationService;
import kz.rm.intelligence.BookedSourceReconciliationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Actual P2 declaration lifecycle; no artificial mutation of IAM membership rows. */
final class BookedDeclarationLifecycleEvidence {
    private BookedDeclarationLifecycleEvidence(){}
    private static BookedOwnerDeclarationService.Draft copy(BookedOwnerDeclarationService.Draft d,UUID id,String ref){
        return new BookedOwnerDeclarationService.Draft(id,d.runId(),d.mode(),ref,d.provenanceDigest(),
            d.sourceRevision(),d.receiptRevision(),d.expectedOrders(),d.expectedPopulationDigest());
    }
    private static <T> T get(Future<T> f) throws Exception{return f.get(40,TimeUnit.SECONDS);}
    private static long count(JdbcTemplate jdbc,String table,UUID tenant,UUID id){
        // table is a private test constant, never supplied by application input.
        return jdbc.queryForObject("SELECT count(*) FROM crm."+table+" WHERE tenant_id=? AND proof_id=?",Long.class,tenant,id);
    }
    static void qualify(JdbcTemplate jdbc,BookedOwnerDeclarationService declarations,BookedSourceReconciliationService reconciliation,
                        TenantOwnershipTransferService transfer,VerifiedActor owner,VerifiedActor successor,
                        UUID tenant,BookedOwnerDeclarationService.Draft base){
        var executor=Executors.newFixedThreadPool(2);
        try{
            UUID id=UUID.randomUUID();
            var draft=copy(base,id,"CONCURRENT_IDENTICAL");
            var start=new CyclicBarrier(2);
            var one=executor.submit(()->{start.await(15,TimeUnit.SECONDS);return declarations.declare(owner,tenant,draft);});
            var two=executor.submit(()->{start.await(15,TimeUnit.SECONDS);return declarations.declare(owner,tenant,draft);});
            assertThat(get(one)).isEqualTo(id);assertThat(get(two)).isEqualTo(id);
            assertThat(count(jdbc,"int_booked_owner_declaration",tenant,id)).isEqualTo(1);
            UUID conflictId=UUID.randomUUID();
            var a=copy(base,conflictId,"CONCURRENT_A");var b=copy(base,conflictId,"CONCURRENT_B");
            var conflictStart=new CyclicBarrier(2);
            var ca=executor.submit(()->attempt(declarations,owner,tenant,a,conflictStart));
            var cb=executor.submit(()->attempt(declarations,owner,tenant,b,conflictStart));
            assertThat(List.of(get(ca),get(cb))).containsExactlyInAnyOrder("INSERTED_OR_REPLAY","S1_DECLARATION_ID_CONFLICT");
            assertThat(count(jdbc,"int_booked_owner_declaration",tenant,conflictId)).isEqualTo(1);
            var revokeStart=new CyclicBarrier(2);
            var r1=executor.submit(()->{revokeStart.await(15,TimeUnit.SECONDS);declarations.revoke(owner,tenant,conflictId);return true;});
            var r2=executor.submit(()->{revokeStart.await(15,TimeUnit.SECONDS);declarations.revoke(owner,tenant,conflictId);return true;});
            assertThat(get(r1)).isTrue();assertThat(get(r2)).isTrue();
            assertThat(count(jdbc,"int_booked_owner_revocation",tenant,conflictId)).isEqualTo(1);
            // Inspect loads declaration in RR before waiting at the backfill run.
            // Revocation commits independently; the already-open diagnostic snapshot
            // must remain old and the next read must be revoked. Four connections max.
            try(var conn=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
                conn.setAutoCommit(false);
                var monitor=new JdbcTemplate(new SingleConnectionDataSource(conn,true));monitor.setQueryTimeout(5);
                try{
                    monitor.execute("LOCK TABLE crm.int_fact_backfill_run IN ACCESS EXCLUSIVE MODE");
                    int fence=monitor.queryForObject("SELECT pg_backend_pid()",Integer.class);
                    var reading=executor.submit(()->reconciliation.inspectAuthority(successor,tenant,id));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);Integer snapshot=null;
                    while(System.nanoTime()<deadline&&snapshot==null){
                        if(reading.isDone()){get(reading);throw new AssertionError("S1_AUTH_INSPECT_FINISHED_BEFORE_FENCE");}
                        snapshot=monitor.query("""
                            SELECT pid FROM pg_locks WHERE locktype='relation' AND relation='crm.int_fact_backfill_run'::regclass
                              AND NOT granted AND ?=ANY(pg_blocking_pids(pid)) ORDER BY pid LIMIT 1
                            """,rs->rs.next()?rs.getInt(1):null,fence);
                        if(snapshot==null)Thread.sleep(25);
                    }
                    assertThat(snapshot).as("authority snapshot blocked on run").isNotNull();
                    get(executor.submit(()->{declarations.revoke(owner,tenant,id);return true;}));
                    assertThat(reading.isDone()).isFalse();
                    conn.commit();
                    var old=get(reading);
                    assertThat(old.state()).isEqualTo(BookedOwnerDeclarationService.State.MATCHED_CONTRACT_NOT_ADMITTED);
                    assertThat(old.admitted()).isFalse();
                    assertThat(reconciliation.inspectAuthority(successor,tenant,id).state()).isEqualTo(BookedOwnerDeclarationService.State.REVOKED);
                    System.out.printf("S1_AUTH_INSPECT_REVOKE fence=%d snapshot=%d%n",fence,snapshot);
                }finally{conn.rollback();}
            }
            // Reuse the actual two-person transfer. Historical issuer is retained,
            // but only the current owner may register/revoke after ownership changes.
            UUID transferId=transfer.requestTransfer(owner,tenant,successor.userId()).id();
            assertThat(transfer.acceptTransfer(successor,tenant,transferId).state()).isEqualTo("ACCEPTED");
            try{
                var newDraft=copy(base,UUID.randomUUID(),"SUCCESSOR_DECLARATION");
                assertThatThrownBy(()->declarations.declare(owner,tenant,newDraft)).isInstanceOf(SecurityException.class);
                assertThatThrownBy(()->declarations.revoke(owner,tenant,id)).isInstanceOf(SecurityException.class);
                assertThatThrownBy(()->declarations.declare(successor,tenant,draft)).hasMessage("S1_DECLARATION_ID_CONFLICT");
                assertThat(declarations.declare(successor,tenant,newDraft)).isEqualTo(newDraft.proofId());
                declarations.revoke(successor,tenant,newDraft.proofId());
                assertThat(reconciliation.inspectAuthority(successor,tenant,newDraft.proofId()).state())
                    .isEqualTo(BookedOwnerDeclarationService.State.REVOKED);
            }finally{
                var back=transfer.requestTransfer(successor,tenant,owner.userId());
                assertThat(transfer.acceptTransfer(owner,tenant,back.id()).state()).isEqualTo("ACCEPTED");
            }
            assertThatThrownBy(()->declarations.revoke(successor,tenant,id)).isInstanceOf(SecurityException.class);
            System.out.println("S1_AUTH_LIFECYCLE_PASS concurrent_declare conflict revoke snapshot owner_transfer");
        }catch(Exception e){throw new AssertionError("S1_AUTH_LIFECYCLE",e);}
        finally{
            executor.shutdownNow();
            try{if(!executor.awaitTermination(60,TimeUnit.SECONDS))throw new AssertionError("S1_AUTH_WORKERS");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
    }
    private static String attempt(BookedOwnerDeclarationService declarations,VerifiedActor owner,UUID tenant,
                                  BookedOwnerDeclarationService.Draft draft,CyclicBarrier barrier) throws Exception{
        barrier.await(15,TimeUnit.SECONDS);
        try{declarations.declare(owner,tenant,draft);return "INSERTED_OR_REPLAY";}
        catch(IllegalArgumentException e){return e.getMessage();}
    }
}
