package kz.rm.files;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Trusted in-process recovery only. Not a public endpoint nor a tenant user API.
 * Fences stale upload workers before removing unreferenced private blobs.
 */
@Service
class FileRecoveryService {
    private static final Duration STALE_AFTER=Duration.ofMinutes(15);
    private final JdbcTemplate jdbc;
    private final FileStoragePort storage;
    private final ReportEncryptedStorage encrypted;
    private final TransactionTemplate transactions;

    FileRecoveryService(JdbcTemplate jdbc,FileStoragePort storage,
                        PlatformTransactionManager transactionManager,ReportEncryptedStorage encrypted) {
        this.jdbc=jdbc;
        this.storage=storage;
        this.encrypted=encrypted;
        this.transactions=new TransactionTemplate(transactionManager);
    }

    record Result(String outcome,int removed) {}
    private record Snapshot(String state,String scanStatus,UUID token,Instant started,
                            String storageKey,long size,String sha256,String mime,Integer version,String classification) {}
    private record Claim(String outcome,UUID token,String activeKey,
                         long size,String sha256,String mime,Integer version,String classification) {}


    /** Caller persists the last cursor; the next run can continue without OFFSET drift. */
    record BatchResult(int examined,int cleaned,int quarantined,int busy,
                       Instant nextCreatedAt,UUID nextFileId) {}
    private record Candidate(UUID id,Instant createdAt) {}
    BatchResult reconcileBatch(UUID tenant,int maxFiles) throws IOException {
        return reconcileBatch(tenant,null,null,maxFiles);
    }
    BatchResult reconcileBatch(UUID tenant,Instant afterCreatedAt,UUID afterId,
                               int maxFiles) throws IOException {
        if(tenant==null || maxFiles<1 || maxFiles>100
                || (afterCreatedAt==null)!=(afterId==null))
            throw new IllegalArgumentException("Tenant, consistent cursor and batch size 1..100 required");
        java.util.List<Candidate> rows;
        if(afterCreatedAt==null) {
            rows=jdbc.query("""
                SELECT id,created_at FROM crm.file_object
                 WHERE tenant_id=? AND state IN ('STAGED','AVAILABLE','REJECTED')
                 ORDER BY created_at,id LIMIT ?
                """,(rs,n)->new Candidate(rs.getObject(1,UUID.class),
                    rs.getTimestamp(2).toInstant()),tenant,maxFiles);
        } else {
            rows=jdbc.query("""
                SELECT id,created_at FROM crm.file_object
                 WHERE tenant_id=? AND state IN ('STAGED','AVAILABLE','REJECTED')
                   AND (created_at>? OR (created_at=? AND id>?))
                 ORDER BY created_at,id LIMIT ?
                """,(rs,n)->new Candidate(rs.getObject(1,UUID.class),
                    rs.getTimestamp(2).toInstant()),
                    tenant,Timestamp.from(afterCreatedAt),Timestamp.from(afterCreatedAt),
                    afterId,maxFiles);
        }
        int cleaned=0,quarantined=0,busy=0;
        for(Candidate candidate:rows) {
            Result result=reconcileOne(tenant,candidate.id());
            cleaned+=result.removed();
            if("QUARANTINED".equals(result.outcome()))quarantined++;
            if("BUSY".equals(result.outcome()))busy++;
        }
        Candidate last=rows.isEmpty()?null:rows.getLast();
        return new BatchResult(rows.size(),cleaned,quarantined,busy,
                last==null?afterCreatedAt:last.createdAt(),
                last==null?afterId:last.id());
    }

    Result reconcileOne(UUID tenant,UUID fileId) throws IOException {
        if(tenant==null || fileId==null)throw new IllegalArgumentException("Tenant/file required");
        // The narrow PG transaction fences stale workers; filesystem IO happens later.
        Claim claim=transactions.execute(status-> {
            Snapshot snap=jdbc.query("""
                SELECT f.state,f.scan_status,f.processing_token,f.processing_started_at,
                       (SELECT s.storage_key FROM crm.file_storage s
                         WHERE s.tenant_id=f.tenant_id AND s.file_id=f.id AND s.state='ACTIVE'),
                       f.size_bytes,f.sha256,f.mime_type,
                       (SELECT s.encrypted_format_version FROM crm.file_storage s
                         WHERE s.tenant_id=f.tenant_id AND s.file_id=f.id AND s.state='ACTIVE'),f.content_class
                  FROM crm.file_object f
                 WHERE f.tenant_id=? AND f.id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())return null;
                    Timestamp start=rs.getTimestamp(4);
                    return new Snapshot(rs.getString(1),rs.getString(2),
                            rs.getObject(3,UUID.class),start==null?null:start.toInstant(),
                            rs.getString(5),rs.getLong(6),rs.getString(7),rs.getString(8),rs.getObject(9,Integer.class),rs.getString(10));
                },tenant,fileId);
            if(snap==null)return new Claim("ABSENT",null,null,0,null,null,null,null);
            if("AVAILABLE".equals(snap.state()))
                return new Claim("VERIFY",null,snap.storageKey(),snap.size(),snap.sha256(),snap.mime(),snap.version(),snap.classification());
            if("STAGED".equals(snap.state())) {
                if("SCANNING".equals(snap.scanStatus())
                    && (snap.started()==null ||
                        !snap.started().isBefore(Instant.now().minus(STALE_AFTER))))
                    return new Claim("BUSY",null,null,0,null,null,null,null);
                UUID token=UUID.randomUUID();
                jdbc.update("""
                    UPDATE crm.file_object
                       SET processing_token=?,processing_started_at=?,
                           scan_status='SCANNING',row_version=row_version+1
                     WHERE tenant_id=? AND id=? AND state='STAGED'
                    """,token,Timestamp.from(Instant.now()),tenant,fileId);
                return new Claim("CLEANUP",token,null,0,null,null,null,null);
            }
            // REJECTED or DELETED remain inaccessible; physical blobs are unreferenced
            // in R1.0C only when no active pointer exists.
            if("REJECTED".equals(snap.state()) && snap.storageKey()==null)
                return new Claim("CLEANUP",null,null,0,null,null,null,null);
            return new Claim("SKIPPED",null,null,0,null,null,null,null);
        });
        if(claim==null || "ABSENT".equals(claim.outcome()) || "BUSY".equals(claim.outcome())
                || "SKIPPED".equals(claim.outcome()))
            return new Result(claim==null?"ABSENT":claim.outcome(),0);
        try {
            if("VERIFY".equals(claim.outcome())) {
                boolean valid;
                if(claim.activeKey()==null)valid=false;
                else if(Integer.valueOf(1).equals(claim.version())) {
                    if(!"CONFIDENTIAL".equals(claim.classification()))
                        throw new IOException("REPORT_STORAGE_CLASSIFICATION");
                    var expected=ReportEncryptedStorage.persisted(tenant,fileId,claim.activeKey(),
                        claim.mime(),claim.size(),claim.sha256());
                    valid=encrypted.matches(expected,claim.activeKey(),false);
                } else if(Integer.valueOf(0).equals(claim.version())&&!ReportStorageKey.encrypted(claim.activeKey())
                           && !"CONFIDENTIAL".equals(claim.classification()))
                    valid=storage.matches(claim.activeKey(),claim.size(),claim.sha256());
                else throw new IOException("REPORT_STORAGE_VERSION_UNSUPPORTED");
                if(!valid) {
                    transactions.executeWithoutResult(status->jdbc.update("""
                        UPDATE crm.file_object SET state='REJECTED',scan_status='ERROR',
                            row_version=row_version+1
                         WHERE tenant_id=? AND id=? AND state='AVAILABLE' AND scan_status='CLEAN'
                           AND ((CAST(? AS varchar) IS NULL AND NOT EXISTS (SELECT 1 FROM crm.file_storage s WHERE s.tenant_id=? AND s.file_id=? AND s.state='ACTIVE'))
                             OR EXISTS (SELECT 1 FROM crm.file_storage s WHERE s.tenant_id=? AND s.file_id=? AND s.state='ACTIVE' AND s.storage_key=?))
                        """,tenant,fileId,claim.activeKey(),tenant,fileId,tenant,fileId,claim.activeKey()));
                    return new Result("QUARANTINED",0);
                }
            }
            int removed=0;
            // An active immutable pointer is never deleted. Old stage/final orphan
            // files are cleaned only after stale processing has been fenced.
            for(String key:storage.listKeys(tenant,fileId)) {
                if(key.equals(claim.activeKey()))continue;
                storage.delete(key);
                removed++;
            }
            return new Result("VERIFY".equals(claim.outcome())?"VERIFIED":"CLEANED",removed);
        } finally {
            if(claim.token()!=null) {
                transactions.executeWithoutResult(status->jdbc.update("""
                    UPDATE crm.file_object SET processing_token=NULL,
                        processing_started_at=NULL,scan_status='NOT_SCANNED',
                        row_version=row_version+1
                    WHERE tenant_id=? AND id=? AND processing_token=?
                      AND state='STAGED' AND scan_status='SCANNING'
                    """,tenant,fileId,claim.token()));
            }
        }
    }
}
