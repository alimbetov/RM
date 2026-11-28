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
            if(requestKey.matches("report-[0-9a-f-]{36}-XLSX") || !e.mime().equals(mime) || e.size()!=declaredSize || !actor.equals(e.uploadedBy()))
                throw new IllegalArgumentException("Upload request key reused with different identity or payload");
            return new FileUploadIntent(e.id(),e.state(),e.scanStatus(),e.mime(),e.size());
        }
        if(requestKey.matches("report-[0-9a-f-]{36}-XLSX"))throw new IllegalArgumentException("Reserved report request key");
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


    private static final java.time.Duration CLAIM_TTL=java.time.Duration.ofMinutes(15);

    record UploadClaim(FileUploadIntent intent,UUID token) {
        boolean alreadyAvailable(){return token==null;}
    }
    private record Locked(String state,String scanStatus,String mime,long size,
                          UUID uploader,String checksum,UUID token,String classification) {}

    /**
     * Serializable per-file admission with a durable claim. No transaction spans IO.
     * The token is never exposed to a transport caller.
     */
    @Transactional
    UploadClaim claim(UUID actor,UUID tenant,UUID fileId) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_UPLOAD);
        if(fileId==null)throw new IllegalArgumentException("File ID required");
        var r=jdbc.query("""
            SELECT state,scan_status,mime_type,size_bytes,uploaded_by,sha256,
                   processing_token,processing_started_at,content_class
              FROM crm.file_object WHERE tenant_id=? AND id=? FOR UPDATE
            """,rs->{
                if(!rs.next())throw new IllegalArgumentException("Upload intent not found");
                return new ClaimRow(rs.getString(1),rs.getString(2),rs.getString(3),
                        rs.getLong(4),rs.getObject(5,UUID.class),rs.getString(6),
                        rs.getObject(7,UUID.class),
                        rs.getTimestamp(8)==null?null:rs.getTimestamp(8).toInstant(),rs.getString(9));
            },tenant,fileId);
        if(!actor.equals(r.uploader()))throw new SecurityException("Only original uploader may finalize");
        if("CONFIDENTIAL".equals(r.classification()))throw new SecurityException("REPORT_TRUSTED_UPLOAD_REQUIRED");
        FileUploadIntent intent=new FileUploadIntent(fileId,r.state(),r.scanStatus(),r.mime(),r.size());
        if("AVAILABLE".equals(r.state()) && "CLEAN".equals(r.scanStatus()))
            return new UploadClaim(intent,null);
        if(!"STAGED".equals(r.state())
                || (!"NOT_SCANNED".equals(r.scanStatus()) && !"SCANNING".equals(r.scanStatus())))
            throw new IllegalStateException("Upload not eligible for processing");
        Instant now=Instant.now();
        if("SCANNING".equals(r.scanStatus())
                && (r.started()==null || !r.started().isBefore(now.minus(CLAIM_TTL))))
            throw new IllegalStateException("File upload already processing");
        UUID token=UUID.randomUUID();
        int updated=jdbc.update("""
                UPDATE crm.file_object
                   SET processing_token=?,processing_started_at=?,scan_status='SCANNING',
                       row_version=row_version+1
                 WHERE tenant_id=? AND id=? AND state='STAGED'
                """,token,Timestamp.from(now),tenant,fileId);
        if(updated!=1)throw new IllegalStateException("File processing claim raced");
        return new UploadClaim(intent,token);
    }
    private record ClaimRow(String state,String scanStatus,String mime,long size,
                            UUID uploader,String checksum,UUID token,Instant started,String classification) {}

    /** Release only one's own claim. Stale workers cannot touch a newer claim. */
    @Transactional
    void release(UUID actor,UUID tenant,UUID fileId,UUID token) {
        if(token==null)return;
        jdbc.update("""
                UPDATE crm.file_object
                   SET processing_token=NULL,processing_started_at=NULL,
                       scan_status='NOT_SCANNED',row_version=row_version+1
                 WHERE tenant_id=? AND id=? AND uploaded_by=? AND state='STAGED'
                   AND scan_status='SCANNING' AND processing_token=?
                """,tenant,fileId,actor,token);
    }

    @Transactional
    void rejectInfected(UUID actor,UUID tenant,UUID fileId,UUID token) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_UPLOAD);
        int changed=jdbc.update("""
                UPDATE crm.file_object
                   SET state='REJECTED',scan_status='INFECTED',
                       processing_token=NULL,processing_started_at=NULL,
                       row_version=row_version+1
                 WHERE tenant_id=? AND id=? AND uploaded_by=? AND state='STAGED'
                   AND scan_status='SCANNING' AND processing_token=?
                """,tenant,fileId,actor,token);
        if(changed!=1)throw new IllegalStateException("Upload claim lost before malware rejection");
    }

    /** Final commit is fenced to the exact worker and includes fresh IAM admission. */
    @Transactional
    FileUploadIntent finalizeClean(UUID actor,UUID tenant,UUID fileId,UUID token,
                                   FileStoragePort.Staged staged,String finalKey) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_UPLOAD);
        var locked=jdbc.query("""
                SELECT state,scan_status,mime_type,size_bytes,uploaded_by,sha256,
                       processing_token,content_class
                  FROM crm.file_object
                 WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())throw new IllegalArgumentException("File not found");
                    return new Locked(rs.getString(1),rs.getString(2),rs.getString(3),
                            rs.getLong(4),rs.getObject(5,UUID.class),rs.getString(6),
                            rs.getObject(7,UUID.class),rs.getString(8));
                },tenant,fileId);
        if(staged.cryptoVersion()!=0 || "CONFIDENTIAL".equals(locked.classification()))
            throw new SecurityException("REPORT_TRUSTED_UPLOAD_REQUIRED");
        if(!actor.equals(locked.uploader()) || !"STAGED".equals(locked.state())
                || !"SCANNING".equals(locked.scanStatus()) || token==null
                || !token.equals(locked.token())
                || !locked.mime().equals(staged.detectedMime())
                || locked.size()!=staged.sizeBytes())
            throw new IllegalStateException("Upload claim expired or metadata changed");
        Integer previous=jdbc.queryForObject("""
            SELECT count(*) FROM crm.file_storage WHERE tenant_id=? AND file_id=?
            """,Integer.class,tenant,fileId);
        if(previous==null || previous!=0)
            throw new IllegalStateException("File storage pointer already exists");
        jdbc.update("""
            INSERT INTO crm.file_storage(tenant_id,file_id,provider,storage_key,state,encrypted_format_version)
            VALUES (?,?,?,?, 'ACTIVE',?)
            """,tenant,fileId,staged.key().startsWith("rustfs/")?"RUSTFS":"LOCAL",
                finalKey,staged.cryptoVersion());
        int changed=jdbc.update("""
            UPDATE crm.file_object
               SET state='AVAILABLE',scan_status='CLEAN',sha256=?,completed_at=?,
                   processing_token=NULL,processing_started_at=NULL,
                   row_version=row_version+1
             WHERE tenant_id=? AND id=? AND state='STAGED'
               AND scan_status='SCANNING' AND processing_token=?
            """,staged.sha256(),Timestamp.from(Instant.now()),tenant,fileId,token);
        if(changed!=1)throw new IllegalStateException("Upload claim changed during commit");
        return new FileUploadIntent(fileId,"AVAILABLE","CLEAN",locked.mime(),locked.size());
    }

    /** File is not logically available if its persisted blob is missing or corrupted. */
    @Transactional
    void quarantineIntegrityFailure(UUID tenant,UUID fileId,String activeKey) {
        jdbc.update("""
            UPDATE crm.file_object SET state='REJECTED',scan_status='ERROR',
                row_version=row_version+1
            WHERE tenant_id=? AND id=? AND state='AVAILABLE' AND scan_status='CLEAN'
              AND (?='' OR EXISTS (
                SELECT 1 FROM crm.file_storage s
                WHERE s.tenant_id=? AND s.file_id=? AND s.storage_key=?))
            """,tenant,fileId,activeKey,tenant,fileId,activeKey);
    }
    private record Existing(UUID id,String mime,long size,String state,
                            String scanStatus,UUID uploadedBy) {}
}
