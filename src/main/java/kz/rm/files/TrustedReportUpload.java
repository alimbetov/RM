package kz.rm.files;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import kz.rm.intelligence.TrustedReportFile;
import kz.rm.security.kms.FileContentCryptoPort.Expected;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Owner-to-FILES admission, off by default. No raw byte API or publication. */
@Service
@ConditionalOnProperty(name="rm.files.report-upload.enabled",havingValue="true")
public final class TrustedReportUpload {
    private static final String XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final ReportEncryptedStorage storage;
    private final FileScanPort scanner;
    private final TransactionTemplate tx;
    TrustedReportUpload(JdbcTemplate jdbc,TenantAuthorization authorization,ReportEncryptedStorage storage,
                        FileScanPort scanner,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.authorization=authorization;this.storage=storage;this.scanner=scanner;
        tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(30);
    }
    private record Claim(UUID file,UUID token,String activeKey){}
    public record Receipt(UUID fileId,String sha256,long sizeBytes){}

    public Receipt upload(VerifiedActor actor,TrustedReportFile report) throws IOException {
        Objects.requireNonNull(report);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("REPORT_FILE_OWN_TRANSACTION");
        Claim claim=Objects.requireNonNull(tx.execute(s->claim(actor,report)));
        if(claim.token()==null){
            var expected=ReportEncryptedStorage.persisted(report.tenant(),claim.file(),claim.activeKey(),
                XLSX,report.size(),report.sha256());
            if(!storage.matches(expected,claim.activeKey(),false))throw new IOException("REPORT_FILE_REPLAY_INTEGRITY");
            tx.executeWithoutResult(s->{
                admit(actor,report);
                Intent i=intent(report);matchIntent(actor,report,i);
                if(!i.file().equals(claim.file()))throw new SecurityException("REPORT_FILE_INTENT");
                lockReadyPointer(report,claim.file(),claim.activeKey());
            });
            return receipt(report,claim.file());
        }
        var e=new Expected(report.tenant(),claim.file(),claim.token(),report.format(),report.size(),report.sha256());
        try {
            String stage;
            try(var in=report.open()){stage=storage.stage(e,in);}
            FileScanPort.Verdict verdict=storage.read(e,stage,true,in->{
                byte[] bytes=in.readNBytes(e.sizeBytes()+1);
                try{
                    if(bytes.length!=e.sizeBytes())throw new IOException("REPORT_SCAN_SIZE");
                    ReportContentInspection.require(e.format(),bytes);
                    return scanner.scanAuthenticated(new java.io.ByteArrayInputStream(bytes),bytes.length);
                }finally{Arrays.fill(bytes,(byte)0);}
            });
            if(verdict!=FileScanPort.Verdict.CLEAN){
                if(verdict==FileScanPort.Verdict.INFECTED)tx.executeWithoutResult(s->reject(actor,report,claim));
                throw new IOException("REPORT_SCAN_DENIED");
            }
            String finalKey=storage.promote(e,stage);
            if(!storage.matches(e,finalKey,false))throw new IOException("REPORT_FINAL_INTEGRITY");
            tx.executeWithoutResult(s->finish(actor,report,claim,finalKey));
            return receipt(report,claim.file());
        } catch(IOException|RuntimeException failure) {
            // Never delete a possibly committed object. Release only this STAGED
            // claim. Dedicated V1 garbage collection and crash replay: P4.1A.4.
            try{tx.executeWithoutResult(s->jdbc.update("""
                UPDATE crm.file_object SET processing_token=NULL,processing_started_at=NULL,
                   scan_status='NOT_SCANNED',row_version=row_version+1
                 WHERE tenant_id=? AND id=? AND state='STAGED' AND scan_status='SCANNING'
                   AND processing_token=?
                """,report.tenant(),claim.file(),claim.token()));}
            catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private static Receipt receipt(TrustedReportFile r,UUID id){return new Receipt(id,r.sha256(),r.size());}
    private void admit(VerifiedActor actor,TrustedReportFile r){
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '20s'");
        r.reauthorize(actor);
        authorization.requireForCommand(actor.userId(),r.tenant(),DomainPermission.FILES_UPLOAD);
        authorization.requireForCommand(actor.userId(),r.tenant(),DomainPermission.FILES_READ);
        var found=jdbc.query("""
            SELECT i.instance_id FROM reporting.int_report_instance i
            JOIN reporting.int_report_request_attempt l USING(tenant_id,instance_id)
            JOIN reporting.int_report_dataset_attempt d USING(tenant_id,dataset_id,staging_date)
            WHERE i.tenant_id=? AND i.instance_id=? AND l.dataset_id=? AND l.staging_date=?
              AND i.request_digest=? AND i.scope_digest=? AND i.report_code=? AND i.template_version=?
              AND l.selected_at IS NOT NULL AND l.abandoned_at IS NULL
              AND d.build_state='READY' AND d.scope_digest=i.scope_digest
              AND d.source_manifest_digest=? AND d.observed_digest=?
            FOR NO KEY UPDATE OF i
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,r.tenant(),r.instance(),r.dataset(),r.stagingDate(),
            r.requestDigest(),r.scopeDigest(),r.reportCode(),r.templateVersion(),r.sourceDigest(),r.datasetDigest());
        if(!r.instance().equals(found))throw new SecurityException("REPORT_FILE_OWNER_EVIDENCE");
        actor.userId();
    }
    private record Intent(UUID file,UUID dataset,UUID actor,String scope,String source,String digest,
                          String renderer,String sha,long size){}
    private Intent intent(TrustedReportFile r){
        return jdbc.query("""
            SELECT file_id,dataset_id,requester_id,scope_digest,source_digest,dataset_digest,
                   renderer_version,content_digest,size_bytes
              FROM reporting.int_report_file_intent WHERE tenant_id=? AND instance_id=? AND format_code='XLSX'
            """,rs->rs.next()?new Intent(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getString(6),
                rs.getString(7),rs.getString(8),rs.getLong(9)):null,r.tenant(),r.instance());
    }
    private void matchIntent(VerifiedActor actor,TrustedReportFile r,Intent i){
        if(i==null||!r.dataset().equals(i.dataset())||!actor.userId().equals(i.actor())||!r.scopeDigest().equals(i.scope())
            ||!r.sourceDigest().equals(i.source())||!r.datasetDigest().equals(i.digest())||!r.rendererVersion().equals(i.renderer())
            ||!r.sha256().equals(i.sha())||r.size()!=i.size())throw new SecurityException("REPORT_FILE_INTENT_CONFLICT");
    }
    private record FileRow(String state,String scan,String mime,long size,String sha,String classification,
                           UUID actor,UUID token){}
    private FileRow lockFile(TrustedReportFile r,UUID file){
        var row=jdbc.query("""
            SELECT state,scan_status,mime_type,size_bytes,sha256,content_class,uploaded_by,processing_token
            FROM crm.file_object WHERE tenant_id=? AND id=? FOR UPDATE
            """,rs->rs.next()?new FileRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getString(5),
                rs.getString(6),rs.getObject(7,UUID.class),rs.getObject(8,UUID.class)):null,r.tenant(),file);
        if(row==null||!XLSX.equals(row.mime())||row.size()!=r.size()||!r.sha256().equals(row.sha())
            ||!"CONFIDENTIAL".equals(row.classification()))throw new SecurityException("REPORT_FILE_METADATA");
        return row;
    }
    private Claim claim(VerifiedActor actor,TrustedReportFile r){
        admit(actor,r);
        Intent i=intent(r);
        if(i==null){
            UUID file=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO crm.file_object(tenant_id,id,mime_type,size_bytes,sha256,state,created_at,uploaded_by,
                    request_key,scan_status,content_class)
                VALUES(?,?,?,?,?,'STAGED',clock_timestamp(),?,?,'NOT_SCANNED','CONFIDENTIAL')
                """,r.tenant(),file,XLSX,r.size(),r.sha256(),actor.userId(),"report-"+r.instance()+"-XLSX");
            jdbc.update("""
                INSERT INTO reporting.int_report_file_intent(tenant_id,instance_id,dataset_id,format_code,file_id,
                    requester_id,scope_digest,source_digest,dataset_digest,renderer_version,content_digest,size_bytes)
                VALUES(?,?,?,'XLSX',?,?,?,?,?,?,?,?)
                """,r.tenant(),r.instance(),r.dataset(),file,actor.userId(),r.scopeDigest(),r.sourceDigest(),
                r.datasetDigest(),r.rendererVersion(),r.sha256(),r.size());
            i=intent(r);
        }
        matchIntent(actor,r,i);
        FileRow row=lockFile(r,i.file());
        if(!actor.userId().equals(row.actor()))throw new SecurityException("REPORT_FILE_ACTOR");
        if("AVAILABLE".equals(row.state())&&"CLEAN".equals(row.scan()))
            return new Claim(i.file(),null,lockReadyPointer(r,i.file(),null));
        if(!"STAGED".equals(row.state())||!java.util.Set.of("NOT_SCANNED","SCANNING").contains(row.scan()))
            throw new IllegalStateException("REPORT_FILE_BUSY_OR_TERMINAL");
        UUID token=UUID.randomUUID();
        if(jdbc.update("""
            UPDATE crm.file_object SET scan_status='SCANNING',processing_token=?,processing_started_at=clock_timestamp(),
               row_version=row_version+1 WHERE tenant_id=? AND id=? AND state='STAGED'
               AND (processing_started_at IS NULL OR processing_started_at+interval '15 minutes'<=clock_timestamp())
            """,token,r.tenant(),i.file())!=1)throw new IllegalStateException("REPORT_FILE_CLAIM_LOST");
        return new Claim(i.file(),token,null);
    }
    private String lockReadyPointer(TrustedReportFile r,UUID file,String expected){
        FileRow row=lockFile(r,file);
        if(!"AVAILABLE".equals(row.state())||!"CLEAN".equals(row.scan()))throw new SecurityException("REPORT_FILE_NOT_READY");
        String key=jdbc.query("""
            SELECT storage_key FROM crm.file_storage WHERE tenant_id=? AND file_id=?
             AND state='ACTIVE' AND encrypted_format_version=1 FOR UPDATE
            """,rs->rs.next()?rs.getString(1):null,r.tenant(),file);
        if(key==null||!ReportStorageKey.encrypted(key)||(expected!=null&&!expected.equals(key)))
            throw new SecurityException("REPORT_FILE_POINTER");
        return key;
    }
    private void requireClaim(VerifiedActor actor,TrustedReportFile r,Claim claim){
        admit(actor,r);
        Intent i=intent(r);matchIntent(actor,r,i);
        if(!i.file().equals(claim.file()))throw new SecurityException("REPORT_FILE_INTENT");
        FileRow row=lockFile(r,claim.file());
        if(!actor.userId().equals(row.actor())||!"STAGED".equals(row.state())||!"SCANNING".equals(row.scan())
            ||!claim.token().equals(row.token()))throw new IllegalStateException("REPORT_FILE_CLAIM_LOST");
    }
    private void reject(VerifiedActor actor,TrustedReportFile r,Claim c){
        requireClaim(actor,r,c);
        if(jdbc.update("""
            UPDATE crm.file_object SET state='REJECTED',scan_status='INFECTED',processing_token=NULL,
                processing_started_at=NULL,row_version=row_version+1
            WHERE tenant_id=? AND id=? AND processing_token=? AND state='STAGED'
                AND processing_started_at+interval '15 minutes'>clock_timestamp()
            """,r.tenant(),c.file(),c.token())!=1)throw new IllegalStateException("REPORT_FILE_CLAIM_LOST");
    }
    private void finish(VerifiedActor actor,TrustedReportFile r,Claim c,String key){
        requireClaim(actor,r,c);
        try{
            if(!c.token().equals(ReportStorageKey.attempt(key,r.tenant(),c.file(),false)))
                throw new SecurityException("REPORT_FINAL_KEY");
        }catch(IOException e){throw new SecurityException("REPORT_FINAL_KEY",e);}
        jdbc.update("""
            INSERT INTO crm.file_storage(tenant_id,file_id,provider,storage_key,state,encrypted_format_version)
            VALUES(?,?,?,?,'ACTIVE',1)
            """,r.tenant(),c.file(),key.startsWith("rustfs/")?"RUSTFS":"LOCAL",key);
        if(jdbc.update("""
            UPDATE crm.file_object SET state='AVAILABLE',scan_status='CLEAN',completed_at=clock_timestamp(),
                processing_token=NULL,processing_started_at=NULL,row_version=row_version+1
            WHERE tenant_id=? AND id=? AND processing_token=? AND state='STAGED'
                AND processing_started_at+interval '15 minutes'>clock_timestamp()
            """,r.tenant(),c.file(),c.token())!=1)throw new IllegalStateException("REPORT_FILE_CLAIM_LOST");
        actor.userId();
    }
}
