package kz.rm.files;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** FILES-R1.0A: internal metadata only, no bytes, no owner attachment or public download. */
@Service
class FileMetadataService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    FileMetadataService(JdbcTemplate jdbc,TenantAuthorization authorization){
        this.jdbc=jdbc;this.authorization=authorization;
    }

    @Transactional
    FileUploadIntent begin(UUID actor,UUID tenant,String mime,long declaredSize,String requestKey) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_UPLOAD);
        if(mime==null || !List.of("image/jpeg","image/png","image/webp","application/pdf").contains(mime))
            throw new IllegalArgumentException("File MIME is not allowed");
        long max=mime.startsWith("image/")?20L*1024*1024:25L*1024*1024;
        if(declaredSize<1 || declaredSize>max)
            throw new IllegalArgumentException("File size exceeds policy");
        if(requestKey==null || !requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("Invalid upload request key");
        // Replays are only metadata-idempotent in R1.0A; concurrent races are
        // enforced by UNIQUE and are qualified for deterministic replay in R1.0B.
        var found=jdbc.query("""
            SELECT id,mime_type,size_bytes,state,scan_status,uploaded_by
            FROM crm.file_object WHERE tenant_id=? AND request_key=?
            """,(rs,n)->new Existing(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getLong(3),rs.getString(4),rs.getString(5),
                    rs.getObject(6,UUID.class)),tenant,requestKey);
        if(!found.isEmpty()){
            Existing e=found.getFirst();
            if(!e.mime().equals(mime) || e.size()!=declaredSize || !actor.equals(e.uploadedBy()))
                throw new IllegalArgumentException("Upload request key reused with different identity or payload");
            return new FileUploadIntent(e.id(),e.state(),e.scanStatus(),e.mime(),e.size());
        }
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.file_object
              (tenant_id,id,mime_type,size_bytes,state,created_at,uploaded_by,request_key,scan_status,content_class)
            VALUES (?,?,?,?,'STAGED',?,?,?,'NOT_SCANNED','INTERNAL')
            """,tenant,id,mime,declaredSize,Timestamp.from(Instant.now()),actor,requestKey);
        return new FileUploadIntent(id,"STAGED","NOT_SCANNED",mime,declaredSize);
    }

    @Transactional
    FileUploadIntent ownStaged(UUID actor,UUID tenant,UUID fileId) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_READ);
        if(fileId==null)throw new IllegalArgumentException("File ID required");
        return jdbc.query("""
            SELECT id,mime_type,size_bytes,state,scan_status
            FROM crm.file_object
            WHERE tenant_id=? AND id=? AND uploaded_by=? AND state='STAGED'
            """,rs->{
                if(!rs.next())throw new IllegalArgumentException("Draft file not found");
                return new FileUploadIntent(rs.getObject(1,UUID.class),rs.getString(4),
                        rs.getString(5),rs.getString(2),rs.getLong(3));
            },tenant,fileId,actor);
    }

    /** Short admission transaction; never lock IAM rows while streaming or scanning. */
    @Transactional
    FileUploadIntent requireUpload(UUID actor,UUID tenant,UUID fileId) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_UPLOAD);
        if(fileId==null)throw new IllegalArgumentException("File ID required");
        return jdbc.query("""
            SELECT id,state,scan_status,mime_type,size_bytes
            FROM crm.file_object WHERE tenant_id=? AND id=? AND uploaded_by=?
            """,rs->{
                if(!rs.next())throw new IllegalArgumentException("Upload intent not found");
                return new FileUploadIntent(rs.getObject(1,UUID.class),rs.getString(2),
                        rs.getString(3),rs.getString(4),rs.getLong(5));
            },tenant,fileId,actor);
    }

    /** Commit is brief and rechecks tenant IAM, uploader, file state, and size. */
    @Transactional
    FileUploadIntent finalizeClean(UUID actor,UUID tenant,UUID fileId,
                                   FileStoragePort.Staged staged,String finalKey) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_UPLOAD);
        var locked=jdbc.query("""
                SELECT state,mime_type,size_bytes,uploaded_by
                FROM crm.file_object
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())throw new IllegalArgumentException("File not found");
                    return new Locked(rs.getString(1),rs.getString(2),
                            rs.getLong(3),rs.getObject(4,UUID.class));
                },tenant,fileId);
        if(!actor.equals(locked.uploader()) || !"STAGED".equals(locked.state())
                || !locked.mime().equals(staged.detectedMime())
                || locked.size()!=staged.sizeBytes())
            throw new IllegalStateException("File upload state changed");
        if(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_storage WHERE tenant_id=? AND file_id=?
                """,Integer.class,tenant,fileId)!=0)
            throw new IllegalStateException("File already has storage location");
        jdbc.update("""
            INSERT INTO crm.file_storage(tenant_id,file_id,provider,storage_key,state)
            VALUES (?,?,'LOCAL',?,'ACTIVE')
            """,tenant,fileId,finalKey);
        jdbc.update("""
            UPDATE crm.file_object
               SET state='AVAILABLE',scan_status='CLEAN',sha256=?,completed_at=?,
                   row_version=row_version+1
             WHERE tenant_id=? AND id=? AND state='STAGED'
            """,staged.sha256(),Timestamp.from(Instant.now()),tenant,fileId);
        return new FileUploadIntent(fileId,"AVAILABLE","CLEAN",locked.mime(),locked.size());
    }
    /** Positive malware verdict is terminal. Scanner errors are retryable STAGED. */
    @Transactional
    void rejectInfected(UUID actor,UUID tenant,UUID fileId) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_UPLOAD);
        int changed=jdbc.update("""
                UPDATE crm.file_object
                   SET state='REJECTED',scan_status='INFECTED',row_version=row_version+1
                 WHERE tenant_id=? AND id=? AND uploaded_by=? AND state='STAGED'
                """,tenant,fileId,actor);
        if(changed!=1)throw new IllegalStateException("Cannot reject changed upload intent");
    }

    private record Locked(String state,String mime,long size,UUID uploader) {}

    private record Existing(UUID id,String mime,long size,String state,
                            String scanStatus,UUID uploadedBy) {}
}
