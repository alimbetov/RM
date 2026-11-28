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
                              java.util.function.Supplier<TrustedReportFile> restart)throws IOException{
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
            }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException(ex);}
            finally{release.countDown();}
            try{receipt=first.get(30,java.util.concurrent.TimeUnit.SECONDS);}
            catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException(ex);}
            catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException ex){throw new IOException(ex);}
        }
        assertEquals(pending,receipt.fileId());
        assertEquals(3,scanned.get());
        // Lost response simulation: second service reads exact durable receipt.
        assertEquals(receipt,restarted.upload(actor,restart.get()));
        assertEquals(3,scanned.get());
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
    }
}
