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
    private final TransactionTemplate transactions;

    FileRecoveryService(JdbcTemplate jdbc,FileStoragePort storage,
                        PlatformTransactionManager transactionManager) {
        this.jdbc=jdbc;
        this.storage=storage;
        this.transactions=new TransactionTemplate(transactionManager);
    }

    record Result(String outcome,int removed) {}
    private record Snapshot(String state,String scanStatus,UUID token,Instant started,
                            String storageKey,long size,String sha256) {}
    private record Claim(String outcome,UUID token,String activeKey,
                         long size,String sha256) {}

    Result reconcileOne(UUID tenant,UUID fileId) throws IOException {
        if(tenant==null || fileId==null)throw new IllegalArgumentException("Tenant/file required");
        // The narrow PG transaction fences stale workers; filesystem IO happens later.
        Claim claim=transactions.execute(status-> {
            Snapshot snap=jdbc.query("""
                SELECT f.state,f.scan_status,f.processing_token,f.processing_started_at,
                       s.storage_key,f.size_bytes,f.sha256
                  FROM crm.file_object f
                  LEFT JOIN crm.file_storage s ON s.tenant_id=f.tenant_id
                       AND s.file_id=f.id AND s.state='ACTIVE'
                 WHERE f.tenant_id=? AND f.id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())return null;
                    Timestamp start=rs.getTimestamp(4);
                    return new Snapshot(rs.getString(1),rs.getString(2),
                            rs.getObject(3,UUID.class),start==null?null:start.toInstant(),
                            rs.getString(5),rs.getLong(6),rs.getString(7));
                },tenant,fileId);
            if(snap==null)return new Claim("ABSENT",null,null,0,null);
            if("AVAILABLE".equals(snap.state()))
                return new Claim("VERIFY",null,snap.storageKey(),snap.size(),snap.sha256());
            if("STAGED".equals(snap.state())) {
                if("SCANNING".equals(snap.scanStatus())
                    && (snap.started()==null ||
                        !snap.started().isBefore(Instant.now().minus(STALE_AFTER))))
                    return new Claim("BUSY",null,null,0,null);
                UUID token=UUID.randomUUID();
                jdbc.update("""
                    UPDATE crm.file_object
                       SET processing_token=?,processing_started_at=?,
                           scan_status='SCANNING',row_version=row_version+1
                     WHERE tenant_id=? AND id=? AND state='STAGED'
                    """,token,Timestamp.from(Instant.now()),tenant,fileId);
                return new Claim("CLEANUP",token,null,0,null);
            }
            // REJECTED or DELETED remain inaccessible; physical blobs are unreferenced
            // in R1.0C only when no active pointer exists.
            if("REJECTED".equals(snap.state()) && snap.storageKey()==null)
                return new Claim("CLEANUP",null,null,0,null);
            return new Claim("SKIPPED",null,null,0,null);
        });
        if(claim==null || "ABSENT".equals(claim.outcome()) || "BUSY".equals(claim.outcome())
                || "SKIPPED".equals(claim.outcome()))
            return new Result(claim==null?"ABSENT":claim.outcome(),0);
        try {
            if("VERIFY".equals(claim.outcome())) {
                boolean valid=claim.activeKey()!=null
                        && storage.matches(claim.activeKey(),claim.size(),claim.sha256());
                if(!valid) {
                    transactions.executeWithoutResult(status->jdbc.update("""
                        UPDATE crm.file_object SET state='REJECTED',scan_status='ERROR',
                            row_version=row_version+1
                         WHERE tenant_id=? AND id=? AND state='AVAILABLE' AND scan_status='CLEAN'
                        """,tenant,fileId));
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
