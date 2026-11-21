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
    private record Existing(UUID id,String mime,long size,String state,
                            String scanStatus,UUID uploadedBy) {}
}
