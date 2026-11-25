package kz.rm.files;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * FILES-owned policy for CATALOG_ITEM public media, distinct from CRM_CUSTOMER.
 * This is not an HTTP surface. Public binary reads are bound to currently
 * published PRODUCT offers and to a CLEAN, sanitized, seller-approved derivative.
 */
@Service
class CatalogPublicMediaPolicy {
    record Source(UUID fileId, String storageKey, String mime, long size,
                  String sha256, CatalogPublicProductMedia.Image existing) {}
    record Blob(UUID tenant, UUID fileId, String storageKey, long size, String sha256) {}
    private record Stored(String state, String scan, String classification, String mime,
                          UUID uploadedBy, long size, String sha256,
                          String storageKey, int cryptoVersion) {}
    private record Entry(UUID itemId, UUID sourceFileId, int sortOrder,
                         UUID approvedBy, String state,
                         CatalogPublicProductMedia.Image result) {}
    private record Variant(UUID itemId, UUID attachmentId, UUID derivativeFileId,
                           String state) {}

    private static final SecureRandom RANDOM=new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    CatalogPublicMediaPolicy(JdbcTemplate jdbc,TenantAuthorization authorization) {
        this.jdbc=jdbc;
        this.authorization=authorization;
    }

    @Transactional
    Source authorizeSource(VerifiedActor actor, UUID tenant, UUID itemId,
                           UUID sourceId, int sortOrder, String requestKey) {
        UUID user=admit(actor,tenant,DomainPermission.FILES_ATTACH);
        authorization.requireForCommand(user,tenant,DomainPermission.CATALOG_WRITE);
        validateInput(itemId,sourceId,sortOrder,requestKey);
        Entry prior=existing(tenant,requestKey);
        if(prior!=null) {
            verifyReplay(prior,itemId,sourceId,sortOrder,user);
            if(!"ACTIVE".equals(prior.state()))
                throw new IllegalStateException("PUBLIC_MEDIA_REVOKED_USE_NEW_KEY");
            return new Source(null,null,null,0,null,prior.result());
        }
        requireCatalogProduct(tenant,itemId,false);
        Stored file=stored(tenant,sourceId);
        requireSafeOriginal(user,file);
        requireUnattached(tenant,sourceId);
        return new Source(sourceId,file.storageKey(),file.mime(),file.size(),
                file.sha256(),null);
    }

    @Transactional
    CatalogPublicProductMedia.Image approve(VerifiedActor actor, UUID tenant,
                     UUID itemId, UUID originalId, UUID derivativeId,
                     int sortOrder, String requestKey, int width, int height) {
        UUID user=admit(actor,tenant,DomainPermission.FILES_ATTACH);
        authorization.requireForCommand(user,tenant,DomainPermission.CATALOG_WRITE);
        validateInput(itemId,originalId,sortOrder,requestKey);
        if(derivativeId==null || derivativeId.equals(originalId) || width<1 || width>4096
                || height<1 || height>4096 || (long)width*height>4_000_000)
            throw new IllegalArgumentException("Invalid sanitized derivative");
        // Product -> source file -> derivative file. This transaction does not
        // span image decode/upload or remote storage IO.
        requireCatalogProduct(tenant,itemId,true);
        Entry prior=existing(tenant,requestKey);
        if(prior!=null) {
            verifyReplay(prior,itemId,originalId,sortOrder,user);
            if(!"ACTIVE".equals(prior.state()))
                throw new IllegalStateException("PUBLIC_MEDIA_REVOKED_USE_NEW_KEY");
            return prior.result();
        }
        Integer occupied=jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_catalog_public_variant
                WHERE tenant_id=? AND item_id=? AND active_slot=?
                """,Integer.class,tenant,itemId,sortOrder);
        if(occupied!=null && occupied>0)
            throw new IllegalStateException("PUBLIC_MEDIA_SLOT_OCCUPIED");

        lockFile(tenant,originalId);
        Stored original=stored(tenant,originalId);
        requireSafeOriginal(user,original);
        requireUnattached(tenant,originalId);
        lockFile(tenant,derivativeId);
        Stored derivative=stored(tenant,derivativeId);
        if(!"AVAILABLE".equals(derivative.state())
                || !"CLEAN".equals(derivative.scan())
                || !"INTERNAL".equals(derivative.classification())
                || !"image/png".equals(derivative.mime())
                || derivative.cryptoVersion()!=0
                || derivative.size()<1 || derivative.size()>20L*1024*1024
                || derivative.sha256()==null || derivative.sha256().length()!=64
                || !user.equals(derivative.uploadedBy()))
            throw new SecurityException("Sanitized product derivative not eligible");
        requireUnattached(tenant,derivativeId);

        UUID id=UUID.randomUUID();
        UUID attachmentId=UUID.randomUUID();
        Instant now=Instant.now();
        byte[] bytes=new byte[24]; // global unguessable PUBLIC (non-bearer) reference
        RANDOM.nextBytes(bytes);
        String publicRef=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
                INSERT INTO crm.file_attachment
                  (tenant_id,id,file_id,owner_type,owner_id,purpose,created_at,created_by)
                VALUES (?,?,?,'CATALOG_ITEM',?,'PUBLIC_PRODUCT_IMAGE',?,?)
                """,tenant,attachmentId,derivativeId,itemId,Timestamp.from(now),user);
        jdbc.update("""
                INSERT INTO crm.file_catalog_public_variant
                  (tenant_id,id,item_id,original_file_id,derivative_file_id,
                   attachment_id,public_ref,sort_order,active_slot,request_key,state,
                   sanitizer_version,width_pixels,height_pixels,approved_by,approved_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE','PNG_R1',?,?,?,?)
                """,tenant,id,itemId,originalId,derivativeId,attachmentId,publicRef,
                sortOrder,sortOrder,requestKey,width,height,user,Timestamp.from(now));
        audit(tenant,user,id,"PRODUCT_MEDIA_APPROVED");
        return new CatalogPublicProductMedia.Image(publicRef,"image/png",width,height,sortOrder);
    }

    @Transactional
    void revoke(VerifiedActor actor, UUID tenant, String publicRef) {
        UUID user=admit(actor,tenant,DomainPermission.FILES_DETACH);
        authorization.requireForCommand(user,tenant,DomainPermission.CATALOG_WRITE);
        if(publicRef==null || !publicRef.matches("[A-Za-z0-9_-]{32}"))
            throw new IllegalArgumentException("PUBLIC_MEDIA_NOT_FOUND");
        Variant info=jdbc.query("""
                SELECT item_id,attachment_id,derivative_file_id,state
                FROM crm.file_catalog_public_variant
                WHERE tenant_id=? AND public_ref=?
                """,rs->rs.next()?new Variant(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4)):null,
                tenant,publicRef);
        if(info==null)throw new IllegalArgumentException("PUBLIC_MEDIA_NOT_FOUND");
        requireCatalogProduct(tenant,info.itemId(),true);
        // Lock derivative, serializing a future FILES attachment. Public reads
        // are separately checked by current variant state on every request.
        lockFile(tenant,info.derivativeFileId());
        int updated=jdbc.update("""
                UPDATE crm.file_catalog_public_variant
                SET state='REVOKED',active_slot=NULL,revoked_by=?,revoked_at=?
                WHERE tenant_id=? AND public_ref=? AND state='ACTIVE'
                """,user,Timestamp.from(Instant.now()),tenant,publicRef);
        if(updated==0)return; // safe idempotent already-revoked operation
        jdbc.update("""
                UPDATE crm.file_attachment
                SET detached_at=?,detached_by=?
                WHERE tenant_id=? AND id=? AND owner_type='CATALOG_ITEM'
                  AND owner_id=? AND detached_at IS NULL
                """,Timestamp.from(Instant.now()),user,tenant,info.attachmentId(),info.itemId());
        audit(tenant,user,info.attachmentId(),"PRODUCT_MEDIA_REVOKED");
    }

    /** Only metadata for an active published PRODUCT offer, never internal IDs. */
    @Transactional(readOnly=true)
    List<CatalogPublicProductMedia.Image> listPublished(String offerPublicRef,int limit) {
        if(offerPublicRef==null || !offerPublicRef.matches("[A-Za-z0-9_-]{32}")
                || limit<1 || limit>20)
            throw new IllegalArgumentException("Invalid public media query");
        return jdbc.query("""
                SELECT v.public_ref,v.width_pixels,v.height_pixels,v.sort_order
                FROM crm.store_offer_qr_ref q
                JOIN crm.store_offer o ON o.tenant_id=q.tenant_id AND o.id=q.offer_id
                JOIN crm.store_front sf ON sf.tenant_id=o.tenant_id
                JOIN public.iam_workspace w ON w.id=q.tenant_id
                JOIN crm.cat_item c ON c.tenant_id=o.tenant_id AND c.id=o.catalog_item_id
                JOIN crm.file_catalog_public_variant v
                  ON v.tenant_id=c.tenant_id AND v.item_id=c.id
                JOIN crm.file_attachment a
                  ON a.tenant_id=v.tenant_id AND a.id=v.attachment_id
                JOIN crm.file_object f
                  ON f.tenant_id=v.tenant_id AND f.id=v.derivative_file_id
                JOIN crm.file_object src
                  ON src.tenant_id=v.tenant_id AND src.id=v.original_file_id
                JOIN crm.file_storage s
                  ON s.tenant_id=f.tenant_id AND s.file_id=f.id
                WHERE q.public_ref=? AND q.state='ACTIVE'
                  AND q.active_offer_id=q.offer_id
                  AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                  AND sf.state='PUBLISHED' AND o.state='PUBLISHED'
                  AND c.kind='PRODUCT' AND c.state='PUBLISHED'
                  AND v.state='ACTIVE' AND v.active_slot=v.sort_order
                  AND v.sanitizer_version='PNG_R1'
                  AND a.owner_type='CATALOG_ITEM' AND a.owner_id=c.id
                  AND a.detached_at IS NULL
                  AND f.state='AVAILABLE' AND f.scan_status='CLEAN'
                  AND f.content_class='INTERNAL' AND f.mime_type='image/png'
                  AND src.state='AVAILABLE' AND src.scan_status='CLEAN'
                  AND s.state='ACTIVE' AND s.encrypted_format_version=0
                ORDER BY v.sort_order ASC,v.id ASC LIMIT ?
                """,(rs,n)->new CatalogPublicProductMedia.Image(rs.getString(1),
                "image/png",rs.getInt(2),rs.getInt(3),rs.getInt(4)),
                offerPublicRef,limit);
    }

    /** A media reference is not an authorization grant. Verify publication NOW. */
    @Transactional(readOnly=true)
    Blob authorizePublishedBinary(String publicMediaRef) {
        if(publicMediaRef==null || !publicMediaRef.matches("[A-Za-z0-9_-]{32}"))
            throw new IllegalArgumentException("PUBLIC_MEDIA_UNAVAILABLE");
        List<Blob> blobs=jdbc.query("""
                SELECT v.tenant_id,f.id,s.storage_key,f.size_bytes,f.sha256
                FROM crm.file_catalog_public_variant v
                JOIN crm.file_attachment a
                  ON a.tenant_id=v.tenant_id AND a.id=v.attachment_id
                JOIN crm.file_object f
                  ON f.tenant_id=v.tenant_id AND f.id=v.derivative_file_id
                JOIN crm.file_object src
                  ON src.tenant_id=v.tenant_id AND src.id=v.original_file_id
                JOIN crm.file_storage s
                  ON s.tenant_id=f.tenant_id AND s.file_id=f.id
                JOIN crm.cat_item c
                  ON c.tenant_id=v.tenant_id AND c.id=v.item_id
                JOIN public.iam_workspace w ON w.id=v.tenant_id
                WHERE v.public_ref=? AND v.state='ACTIVE'
                  AND v.active_slot=v.sort_order AND v.sanitizer_version='PNG_R1'
                  AND a.owner_type='CATALOG_ITEM' AND a.owner_id=c.id
                  AND a.detached_at IS NULL
                  AND c.kind='PRODUCT' AND c.state='PUBLISHED'
                  AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                  AND f.state='AVAILABLE' AND f.scan_status='CLEAN'
                  AND f.mime_type='image/png' AND f.content_class='INTERNAL'
                  AND src.state='AVAILABLE' AND src.scan_status='CLEAN'
                  AND s.state='ACTIVE' AND s.encrypted_format_version=0
                  AND EXISTS (
                     SELECT 1 FROM crm.store_offer o
                     JOIN crm.store_front sf ON sf.tenant_id=o.tenant_id
                     JOIN crm.store_offer_qr_ref q
                       ON q.tenant_id=o.tenant_id AND q.offer_id=o.id
                     WHERE o.tenant_id=v.tenant_id AND o.catalog_item_id=v.item_id
                       AND o.state='PUBLISHED' AND sf.state='PUBLISHED'
                       AND q.state='ACTIVE' AND q.active_offer_id=q.offer_id
                  )
                """,(rs,n)->new Blob(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getString(3),rs.getLong(4),rs.getString(5)),publicMediaRef);
        if(blobs.isEmpty())throw new IllegalArgumentException("PUBLIC_MEDIA_UNAVAILABLE");
        Blob b=blobs.getFirst();
        if(b.sha256()==null || b.sha256().length()!=64 || b.size()<1)
            throw new SecurityException("PUBLIC_MEDIA_UNAVAILABLE");
        return b;
    }

    private Entry existing(UUID tenant,String key) {
        List<Entry> rows=jdbc.query("""
                SELECT v.item_id,v.original_file_id,v.sort_order,v.approved_by,v.state,
                       v.public_ref,v.width_pixels,v.height_pixels
                FROM crm.file_catalog_public_variant v
                WHERE v.tenant_id=? AND v.request_key=?
                """,(rs,n)->new Entry(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getInt(3),rs.getObject(4,UUID.class),
                rs.getString(5),new CatalogPublicProductMedia.Image(
                rs.getString(6),"image/png",rs.getInt(7),rs.getInt(8),rs.getInt(3))),
                tenant,key);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static void verifyReplay(Entry e,UUID item,UUID file,int slot,UUID actor) {
        if(!e.itemId().equals(item)||!e.sourceFileId().equals(file)
                ||e.sortOrder()!=slot||!e.approvedBy().equals(actor))
            throw new IllegalStateException("PUBLIC_MEDIA_IDEMPOTENCY_CONFLICT");
    }
    private UUID admit(VerifiedActor actor,UUID tenant,DomainPermission permission) {
        if(actor==null||tenant==null)throw new SecurityException("Seller identity required");
        authorization.requireForCommand(actor.userId(),tenant,permission);
        return actor.userId();
    }
    private static void validateInput(UUID item,UUID original,int slot,String key) {
        if(item==null||original==null||slot<0||slot>19
                ||key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("Invalid public media parameters");
    }
    private void requireCatalogProduct(UUID tenant,UUID item,boolean lock) {
        String status=jdbc.query("""
                SELECT kind || ':' || state FROM crm.cat_item
                WHERE tenant_id=? AND id=?
                """+(lock?" FOR UPDATE":""),rs->rs.next()?rs.getString(1):null,tenant,item);
        if(!"PRODUCT:DRAFT".equals(status)&&!"PRODUCT:PUBLISHED".equals(status))
            throw new IllegalArgumentException("Active tenant PRODUCT required");
    }
    private void lockFile(UUID tenant,UUID file) {
        UUID found=jdbc.query("""
                SELECT id FROM crm.file_object WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenant,file);
        if(found==null)throw new SecurityException("Owned source FILES object unavailable");
    }
    private Stored stored(UUID tenant,UUID file) {
        List<Stored> rows=jdbc.query("""
                SELECT f.state,f.scan_status,f.content_class,f.mime_type,
                       f.uploaded_by,f.size_bytes,f.sha256,s.storage_key,
                       s.encrypted_format_version
                FROM crm.file_object f JOIN crm.file_storage s
                  ON s.tenant_id=f.tenant_id AND s.file_id=f.id
                WHERE f.tenant_id=? AND f.id=? AND s.state='ACTIVE'
                """,(rs,n)->new Stored(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getObject(5,UUID.class),rs.getLong(6),
                rs.getString(7),rs.getString(8),rs.getInt(9)),tenant,file);
        if(rows.isEmpty())throw new SecurityException("Owned source FILES object unavailable");
        return rows.getFirst();
    }
    private static void requireSafeOriginal(UUID user,Stored file) {
        if(!"AVAILABLE".equals(file.state())||!"CLEAN".equals(file.scan())
                ||!"INTERNAL".equals(file.classification())
                ||!(file.mime().equals("image/png")||file.mime().equals("image/jpeg"))
                ||file.cryptoVersion()!=0 || file.size()<1 || file.size()>20L*1024*1024
                ||file.sha256()==null || file.sha256().length()!=64
                ||!user.equals(file.uploadedBy()))
            throw new SecurityException("Public source candidate not eligible");
    }
    private void requireUnattached(UUID tenant,UUID file) {
        Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_attachment
                WHERE tenant_id=? AND file_id=? AND detached_at IS NULL
                """,Integer.class,tenant,file);
        if(count!=null&&count>0)
            throw new SecurityException("Private/foreign FILES attachment cannot become public");
    }
    private void audit(UUID tenant,UUID actor,UUID id,String action) {
        jdbc.update("""
                INSERT INTO crm.audit_event
                  (tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)
                VALUES (?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),actor,id,action,
                Timestamp.from(Instant.now()));
    }
}
