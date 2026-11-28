package kz.rm.files;

import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import kz.rm.intelligence.TrustedReportFile;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic scanner only. Actual renderer/KMS/storage/PG and owner permission path. */
public final class ReportUploadQualificationEvidence {
    private ReportUploadQualificationEvidence(){}
    public static void verify(ApplicationContext context,JdbcTemplate jdbc,PlatformTransactionManager manager,
                              VerifiedActor actor,VerifiedActor foreign,TrustedReportFile report,
                              java.util.function.Supplier<TrustedReportFile> restart,
                              Runnable grantAttach,Runnable revokeAttach)throws IOException{
        context.getBean(kz.rm.security.kms.TenantKeyService.class).provision(report.tenant());
        var encrypted=context.getBean(ReportEncryptedStorage.class);
        var scanned=new AtomicInteger();
        var deny=new java.util.concurrent.atomic.AtomicBoolean(true);
        FileScanPort scanner=new FileScanPort(){
            public Verdict scan(String key){throw new AssertionError("V0 scanner path");}
            public Verdict scanAuthenticated(InputStream in,long length){
                try(var expected=report.open()){
                    assertEquals(report.size(),length);
                    assertArrayEquals(expected.readAllBytes(),in.readAllBytes());
                    scanned.incrementAndGet();return deny.get()?Verdict.ERROR:Verdict.CLEAN;
                }catch(IOException e){throw new UncheckedIOException(e);}
            }
        };
        var upload=new TrustedReportUpload(jdbc,context.getBean(TenantAuthorization.class),encrypted,scanner,manager);
        assertThrows(SecurityException.class,()->upload.upload(foreign,report));
        assertEquals(0,scanned.get());
        assertThrows(IOException.class,()->upload.upload(actor,report));
        assertEquals(1,scanned.get());
        UUID pending=jdbc.queryForObject("SELECT file_id FROM reporting.int_report_file_intent WHERE tenant_id=? AND instance_id=?",
            UUID.class,report.tenant(),report.instance());
        assertNotNull(pending);
        var raw=context.getBean(FileStoragePort.class);
        var recovery=context.getBean(FileRecoveryService.class);
        var initialOrphans=raw.listKeys(report.tenant(),pending);
        assertFalse(initialOrphans.isEmpty());
        assertEquals("REPLAY_REQUIRED",recovery.reconcileOne(report.tenant(),pending).outcome());
        for(String orphan:initialOrphans)assertTrue(raw.exists(orphan));

        // New owner-side materializer, new capability and new FILES service;
        // persisted source evidence is the authority, not a fresh live read.
        TrustedReportFile restored=restart.get();
        assertEquals(report.instance(),restored.instance());
        assertEquals(report.dataset(),restored.dataset());
        assertEquals(report.sha256(),restored.sha256());
        try(var a=report.open();var b=restored.open()){assertArrayEquals(a.readAllBytes(),b.readAllBytes());}
        var restarted=new TrustedReportUpload(jdbc,context.getBean(TenantAuthorization.class),
            new ReportEncryptedStorage(raw,context.getBean(kz.rm.security.kms.FileContentCryptoPort.class)),scanner,manager);
        deny.set(false);

        // Real PG error at AVAILABLE update, after ciphertext promotion. The
        // pointer insert rolls back with it; do not delete the final object.
        String constraint="ck_p41a_"+pending.toString().replace("-","");
        jdbc.execute("ALTER TABLE crm.file_object ADD CONSTRAINT "+constraint+
            " CHECK (id <> '"+pending+"'::uuid OR state <> 'AVAILABLE')");
        try{
            assertThrows(org.springframework.dao.DataAccessException.class,()->restarted.upload(actor,restored));
            assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM crm.file_storage WHERE tenant_id=? AND file_id=?",
                Long.class,report.tenant(),pending));
            assertEquals("STAGED",jdbc.queryForObject("SELECT state FROM crm.file_object WHERE tenant_id=? AND id=?",
                String.class,report.tenant(),pending));
        }finally{jdbc.execute("ALTER TABLE crm.file_object DROP CONSTRAINT "+constraint);}
        var abandoned=raw.listKeys(report.tenant(),pending);
        assertTrue(abandoned.stream().anyMatch(k->k.contains("/final-v1-")));
        assertEquals("REPLAY_REQUIRED",recovery.reconcileOne(report.tenant(),pending).outcome());
        for(String orphan:abandoned)assertTrue(raw.exists(orphan));

        // Two service instances share PG and storage. Second worker must not
        // enter scanning while first owns the durable claim.
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        FileScanPort delayed=new FileScanPort(){
            public Verdict scan(String key){throw new AssertionError("V0 scanner path");}
            public Verdict scanAuthenticated(InputStream in,long length){
                entered.countDown();
                try{if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))return Verdict.ERROR;}
                catch(InterruptedException ex){Thread.currentThread().interrupt();return Verdict.ERROR;}
                return scanner.scanAuthenticated(in,length);
            }
        };
        var worker=new TrustedReportUpload(jdbc,context.getBean(TenantAuthorization.class),encrypted,delayed,manager);
        TrustedReportUpload.Receipt receipt;
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var first=pool.submit(()->worker.upload(actor,restored));
            try{
                assertTrue(entered.await(15,java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class,()->restarted.upload(actor,restored));
                // Owner test fixture advances only the lease age. The second
                // instance takes a NEW token and independently scans and commits.
                assertEquals(1,jdbc.update("""
                    UPDATE crm.file_object SET processing_started_at=clock_timestamp()-interval '16 minutes'
                    WHERE tenant_id=? AND id=? AND state='STAGED' AND scan_status='SCANNING'
                    """,report.tenant(),pending));
                receipt=restarted.upload(actor,restored);
            }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException(ex);}
            finally{release.countDown();}
            // The original worker may finish ciphertext IO but must fail its
            // stale database CAS; it cannot alter or delete the winning pointer.
            var stale=assertThrows(java.util.concurrent.ExecutionException.class,
                ()->first.get(30,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,stale.getCause());
        }
        assertEquals(pending,receipt.fileId());
        assertEquals(4,scanned.get());
        // Lost response simulation: second service reads exact durable receipt.
        assertEquals(receipt,restarted.upload(actor,restart.get()));
        assertEquals(4,scanned.get());
        UUID id=receipt.fileId();
        String key=jdbc.queryForObject("""
            SELECT s.storage_key FROM crm.file_storage s JOIN crm.file_object f
            ON f.tenant_id=s.tenant_id AND f.id=s.file_id
            WHERE f.tenant_id=? AND f.id=? AND f.state='AVAILABLE' AND f.scan_status='CLEAN'
              AND f.content_class='CONFIDENTIAL' AND s.state='ACTIVE' AND s.encrypted_format_version=1
            """,String.class,report.tenant(),id);
        assertNotNull(key);
        assertEquals("VERIFIED",recovery.reconcileOne(report.tenant(),id).outcome());
        assertTrue(raw.exists(key));
        assertEquals(java.util.List.of(key),raw.listKeys(report.tenant(),id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE crm.file_storage SET storage_key=storage_key||'-changed' WHERE tenant_id=? AND file_id=?",
            report.tenant(),id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE crm.file_object SET state='STAGED' WHERE tenant_id=? AND id=?",report.tenant(),id));
        var e=ReportEncryptedStorage.persisted(report.tenant(),id,key,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",report.size(),report.sha256());
        try(var original=report.open()){assertArrayEquals(original.readAllBytes(),encrypted.read(e,key,false,InputStream::readAllBytes));}
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM reporting.int_report_file_intent WHERE tenant_id=? AND instance_id=? AND file_id=?",Long.class,report.tenant(),report.instance(),id));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM crm.file_attachment WHERE tenant_id=? AND file_id=?",Long.class,report.tenant(),id));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM reporting.int_report_consumer WHERE tenant_id=? AND instance_id=? AND state LIKE 'PUBLISHED%'",Long.class,report.tenant(),report.instance()));
        System.out.println("RM_P4_1A_R02_PRIVATE_ENCRYPTED_FILE_REPLAY_PG_ROLLBACK_TWO_INSTANCES_GC_PASS");
        qualifyPublicationGate(context,jdbc,manager,actor,foreign,restored,id,e,key,raw,grantAttach,revokeAttach);
    }

    /** Actual owner capability, actual V1 ciphertext and current PostgreSQL
     * permissions. No publication mutation. The caller's role fixture grants
     * and revokes FILES_ATTACH through the normal IAM command service. */
    private static void qualifyPublicationGate(ApplicationContext context,JdbcTemplate jdbc,
        PlatformTransactionManager manager,VerifiedActor actor,VerifiedActor foreign,TrustedReportFile report,
        UUID id,kz.rm.security.kms.FileContentCryptoPort.Expected expected,String key,FileStoragePort raw,
        Runnable grantAttach,Runnable revokeAttach)throws IOException{
        var gate=context.getBean(ReportFilePublicationGate.class);
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(SecurityException.class,()->gate.verify(actor,report,id)); // no FILES_ATTACH
        grantAttach.run();
        try {
            assertThrows(SecurityException.class,()->gate.verify(foreign,report,id));
            assertThrows(SecurityException.class,()->gate.verify(actor,report,UUID.randomUUID()));
            assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
                try{gate.verify(actor,report,id);}
                catch(IOException ex){throw new UncheckedIOException(ex);}
            }));
            var proof=gate.verify(actor,report,id);
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->gate.admit(actor,proof));
            var ready=tx.execute(s->gate.lockVerified(gate.admit(actor,proof)));
            assertNotNull(ready);
            assertEquals(id,ready.fileId());
            assertEquals(report.sha256(),ready.sha256());
            assertEquals(report.size(),ready.sizeBytes());
            var stale=tx.execute(s->gate.admit(actor,proof));
            assertThrows(SecurityException.class,()->tx.executeWithoutResult(s->gate.lockVerified(stale)));
            assertThrows(SecurityException.class,()->tx.executeWithoutResult(s->gate.admit(foreign,proof)));

            // Evidence may not survive a change to the file row.
            assertEquals(1,jdbc.update("UPDATE crm.file_object SET row_version=row_version+1 WHERE tenant_id=? AND id=?",
                report.tenant(),id));
            assertThrows(SecurityException.class,()->tx.execute(s->gate.lockVerified(gate.admit(actor,proof))));
            var current=gate.verify(actor,report,id);
            // Revocation after successful pre-verification invalidates final admission.
            revokeAttach.run();
            assertThrows(SecurityException.class,()->tx.execute(s->gate.lockVerified(gate.admit(actor,current))));
            grantAttach.run();

            // Explicit synthetic storage fault injection, not a supported write path:
            // remove and restore the same immutable object to prove actual bytes,
            // not AVAILABLE/CLEAN metadata alone, determine fresh evidence.
            byte[] envelope;
            try(var in=raw.openEncrypted(expected,key,false)){envelope=in.readAllBytes();}
            raw.delete(key);
            try{
                assertThrows(SecurityException.class,()->gate.verify(actor,report,id));
                byte[] corrupt=envelope.clone();corrupt[corrupt.length-1]^=1;
                assertEquals(key,raw.promoteEncrypted(expected,raw.stageEncrypted(expected,corrupt)));
                assertThrows(SecurityException.class,()->gate.verify(actor,report,id));
            }finally{
                raw.delete(key);
                assertEquals(key,raw.promoteEncrypted(expected,raw.stageEncrypted(expected,envelope)));
            }
            var fresh=gate.verify(actor,report,id);
            assertEquals(id,tx.execute(s->gate.lockVerified(gate.admit(actor,fresh))).fileId());
            assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM crm.file_attachment WHERE tenant_id=? AND file_id=?",
                Long.class,report.tenant(),id));
            assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM reporting.int_report_consumer WHERE tenant_id=? AND instance_id=? AND state LIKE 'PUBLISHED%'",
                Long.class,report.tenant(),report.instance()));
            System.out.println("RM_P4_1A5_R02_PUBLICATION_GATE_POSITIVE_NEGATIVE_NO_PUBLICATION_PASS");
        }finally{revokeAttach.run();}
    }

}
