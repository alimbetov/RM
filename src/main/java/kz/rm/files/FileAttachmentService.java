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

/**
 * Synthetic-only CRM_CUSTOMER attachment metadata. No content streaming until
 * authenticated binary encryption and real scanner are proven.
 */
@Service
class FileAttachmentService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final FileOwnerAccessPort owner;

    FileAttachmentService(JdbcTemplate jdbc,TenantAuthorization authorization,
                          FileOwnerAccessPort owner) {
        this.jdbc=jdbc;this.authorization=authorization;this.owner=owner;
    }

    @Transactional
    UUID attachCustomer(UUID actor,UUID tenant,UUID fileId,UUID customerId,String purpose) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_ATTACH);
        if(fileId==null || customerId==null || purpose==null
                || !purpose.matches("[A-Z][A-Z0-9_]{2,47}"))
            throw new IllegalArgumentException("Invalid attachment parameters");
        owner.requireCustomerModify(actor,tenant,customerId);
        // FILES file lock serializes concurrent retries and detach with attach.
        var file=jdbc.query("""
                SELECT state,scan_status,content_class
                  FROM crm.file_object
                 WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())throw new IllegalArgumentException("File not found");
                    return new FileState(rs.getString(1),rs.getString(2),rs.getString(3));
                },tenant,fileId);
        if(!"AVAILABLE".equals(file.state()) || !"CLEAN".equals(file.scan())
                || !"INTERNAL".equals(file.classification()))
            throw new SecurityException("File unavailable for restricted attachment");
        Integer storage=jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_storage
                WHERE tenant_id=? AND file_id=? AND state='ACTIVE'
                """,Integer.class,tenant,fileId);
        if(storage==null || storage!=1)throw new IllegalStateException("Active blob missing");
        // Multiple owners are disallowed until a verified per-attachment
        // classification policy is in place. Prevent cross-object disclosure.
        Integer foreignLinks=jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_attachment
                 WHERE tenant_id=? AND file_id=? AND detached_at IS NULL
                   AND (owner_type<>'CRM_CUSTOMER' OR owner_id<>?)
                """,Integer.class,tenant,fileId,customerId);
        if(foreignLinks!=null && foreignLinks>0)
            throw new SecurityException("File already bound to another owner");
        List<UUID> existing=jdbc.query("""
                SELECT id FROM crm.file_attachment
                WHERE tenant_id=? AND file_id=? AND owner_type='CRM_CUSTOMER'
                      AND owner_id=? AND purpose=? AND detached_at IS NULL
                """,(rs,n)->rs.getObject(1,UUID.class),tenant,fileId,customerId,purpose);
        if(!existing.isEmpty())return existing.getFirst();
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.file_attachment
                 (tenant_id,id,file_id,owner_type,owner_id,purpose,created_at,created_by)
                VALUES (?,?,?,'CRM_CUSTOMER',?,?,?,?)
                """,tenant,id,fileId,customerId,purpose,Timestamp.from(Instant.now()),actor);
        return id;
    }

    @Transactional
    void detachCustomer(UUID actor,UUID tenant,UUID attachmentId) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_DETACH);
        if(attachmentId==null)throw new IllegalArgumentException("Attachment ID required");
        var link=jdbc.query("""
                SELECT owner_type,owner_id,file_id FROM crm.file_attachment
                 WHERE tenant_id=? AND id=?
                """,rs->{
                    if(!rs.next())throw new IllegalArgumentException("Attachment not found");
                    return new OwnerLink(rs.getString(1),rs.getObject(2,UUID.class),
                            rs.getObject(3,UUID.class));
                },tenant,attachmentId);
        if(!"CRM_CUSTOMER".equals(link.type()))
            throw new SecurityException("Attachment belongs to a different domain");
        owner.requireCustomerModify(actor,tenant,link.ownerId());
        jdbc.queryForObject("""
                SELECT id FROM crm.file_object WHERE tenant_id=? AND id=? FOR UPDATE
                """,UUID.class,tenant,link.fileId());
        jdbc.update("""
                UPDATE crm.file_attachment
                   SET detached_at=?,detached_by=?
                 WHERE tenant_id=? AND id=? AND detached_at IS NULL
                """,Timestamp.from(Instant.now()),actor,tenant,attachmentId);
    }

    @Transactional
    List<FileAttachmentSummary> listCustomer(UUID actor,UUID tenant,UUID customerId,int limit) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_READ);
        if(customerId==null || limit<1 || limit>100)
            throw new IllegalArgumentException("Customer and limit 1..100 required");
        owner.requireCustomerRead(actor,tenant,customerId);
        return jdbc.query("""
                SELECT a.id,a.file_id,a.purpose,f.mime_type,f.size_bytes,a.created_at
                  FROM crm.file_attachment a
                  JOIN crm.file_object f ON f.tenant_id=a.tenant_id AND f.id=a.file_id
                 WHERE a.tenant_id=? AND a.owner_type='CRM_CUSTOMER'
                   AND a.owner_id=? AND a.detached_at IS NULL
                   AND f.state='AVAILABLE' AND f.scan_status='CLEAN'
                   AND f.content_class='INTERNAL'
                 ORDER BY a.created_at DESC,a.id DESC LIMIT ?
                """,(rs,n)->new FileAttachmentSummary(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),customerId,rs.getString(3),
                    rs.getString(4),rs.getLong(5),rs.getTimestamp(6).toInstant()),
                    tenant,customerId,limit);
    }

    record VerifiedRead(UUID fileId,String key,long size,String sha256) {}

    @Transactional
    VerifiedRead authorizeCustomerBinaryRead(UUID actor,UUID tenant,UUID attachmentId) {
        authorization.requireForCommand(actor,tenant,DomainPermission.FILES_READ);
        if(attachmentId==null)throw new IllegalArgumentException("Attachment required");
        var record=jdbc.query("""
                SELECT a.owner_type,a.owner_id,f.id,f.size_bytes,f.sha256,
                       f.state,f.scan_status,f.content_class,
                       s.storage_key,s.encrypted_format_version
                FROM crm.file_attachment a
                JOIN crm.file_object f ON f.tenant_id=a.tenant_id AND f.id=a.file_id
                JOIN crm.file_storage s ON s.tenant_id=f.tenant_id AND s.file_id=f.id
                WHERE a.tenant_id=? AND a.id=? AND a.detached_at IS NULL
                  AND s.state='ACTIVE'
                """,rs->{
                    if(!rs.next())throw new IllegalArgumentException("Attachment unavailable");
                    return new ContentLink(rs.getString(1),rs.getObject(2,UUID.class),
                            rs.getObject(3,UUID.class),rs.getLong(4),rs.getString(5),
                            rs.getString(6),rs.getString(7),rs.getString(8),
                            rs.getString(9),rs.getInt(10));
                },tenant,attachmentId);
        if(!"CRM_CUSTOMER".equals(record.ownerType()))
            throw new SecurityException("Owner type not permitted");
        owner.requireCustomerRead(actor,tenant,record.ownerId());
        if(!"AVAILABLE".equals(record.state()) || !"CLEAN".equals(record.scan())
                || !"INTERNAL".equals(record.classification()) || record.cryptoVersion()!=0
                || record.sha()==null)
            throw new SecurityException("Unavailable or non-plaintext binary content");
        return new VerifiedRead(record.fileId(),record.key(),record.size(),record.sha());
    }
    private record ContentLink(String ownerType,UUID ownerId,UUID fileId,
                               long size,String sha,String state,String scan,
                               String classification,String key,int cryptoVersion) {}

    private record FileState(String state,String scan,String classification) {}
    private record OwnerLink(String type,UUID ownerId,UUID fileId) {}
}
