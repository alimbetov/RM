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
                              VerifiedActor actor,VerifiedActor foreign,TrustedReportFile report)throws IOException{
        context.getBean(kz.rm.security.kms.TenantKeyService.class).provision(report.tenant());
        var encrypted=context.getBean(ReportEncryptedStorage.class);
        var scanned=new AtomicInteger();
        FileScanPort scanner=new FileScanPort(){
            public Verdict scan(String key){throw new AssertionError("V0 scanner path");}
            public Verdict scanAuthenticated(InputStream in,long length){
                try(var expected=report.open()){
                    assertEquals(report.size(),length);
                    assertArrayEquals(expected.readAllBytes(),in.readAllBytes());
                    scanned.incrementAndGet();return Verdict.CLEAN;
                }catch(IOException e){throw new UncheckedIOException(e);}
            }
        };
        var upload=new TrustedReportUpload(jdbc,context.getBean(TenantAuthorization.class),encrypted,scanner,manager);
        assertThrows(SecurityException.class,()->upload.upload(foreign,report));
        assertEquals(0,scanned.get());
        var receipt=upload.upload(actor,report);
        assertEquals(1,scanned.get());
        assertEquals(receipt,upload.upload(actor,report));
        assertEquals(1,scanned.get());
        UUID id=receipt.fileId();
        String key=jdbc.queryForObject("""
            SELECT s.storage_key FROM crm.file_storage s JOIN crm.file_object f
            ON f.tenant_id=s.tenant_id AND f.id=s.file_id
            WHERE f.tenant_id=? AND f.id=? AND f.state='AVAILABLE' AND f.scan_status='CLEAN'
              AND f.content_class='CONFIDENTIAL' AND s.state='ACTIVE' AND s.encrypted_format_version=1
            """,String.class,report.tenant(),id);
        assertNotNull(key);
        var e=ReportEncryptedStorage.persisted(report.tenant(),id,key,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",report.size(),report.sha256());
        try(var original=report.open()){assertArrayEquals(original.readAllBytes(),encrypted.read(e,key,false,InputStream::readAllBytes));}
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM reporting.int_report_file_intent WHERE tenant_id=? AND instance_id=? AND file_id=?",Long.class,report.tenant(),report.instance(),id));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM crm.file_attachment WHERE tenant_id=? AND file_id=?",Long.class,report.tenant(),id));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM reporting.int_report_consumer WHERE tenant_id=? AND instance_id=? AND state LIKE 'PUBLISHED%'",Long.class,report.tenant(),report.instance()));
        System.out.println("RM_P4_1A_R02_PRIVATE_ENCRYPTED_FILE_SYNTHETIC_SCAN_PASS");
    }
}
