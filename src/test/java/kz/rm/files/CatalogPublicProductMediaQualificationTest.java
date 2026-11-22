package kz.rm.files;

import kz.rm.iam.*;
import kz.rm.storefront.PublishedOfferQrService;
import kz.rm.storefront.PublishedOfferCardQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** QR-02A: real local FILES storage with a synthetic AV verdict; this suite
 * alone does NOT qualify real ClamAV, RustFS, web transport or actual phones. */
@SpringBootTest
@Import(CatalogPublicProductMediaQualificationTest.MockScannerConfiguration.class)
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class CatalogPublicProductMediaQualificationTest {
    @TestConfiguration
    static class MockScannerConfiguration {
        @Bean @Primary FileScanPort syntheticProductImageScanner() {
            return new FileScanPort() {
                @Override public Verdict scan(String stagingKey) {return Verdict.CLEAN;}
            };
        }
    }

    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthenticatedFilesCommands files;
    @Autowired FileUploadCompletionService complete;
    @Autowired CatalogPublicProductMedia media;
    @Autowired PublishedOfferQrService qr;
    @Autowired PublishedOfferCardQuery cards;

    private record Fixture(UUID tenant,VerifiedActor staff,UUID item,UUID offer,
                           String offerRef,UUID ownerId) {}

    private VerifiedActor verified(UUID user) {
        String sub=jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token).header("alg","RS256")
                .subject(sub).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-files-qr");
    }
    private Fixture setup() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Image Seller LLC","Image Seller","KZ"));
        UUID tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID owner=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"image-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID uploader=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"image-uploader-"+UUID.randomUUID(),"Seller")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),uploader,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        var admin=verified(owner);
        var role=roles.createRole(admin,tenant,
                "IMAGE_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "FILES product media");
        for(DomainPermission perm: new DomainPermission[]{
                DomainPermission.FILES_UPLOAD,DomainPermission.FILES_READ,
                DomainPermission.FILES_ATTACH,DomainPermission.FILES_DETACH,
                DomainPermission.CATALOG_WRITE,DomainPermission.STOREFRONT_OFFER_MANAGE,
                DomainPermission.STOREFRONT_OFFER_PUBLISH})
            roles.grant(admin,tenant,role.id(),perm);
        roles.assign(admin,tenant,uploader,role.id());
        roles.enable(admin,tenant);
        var staff=verified(uploader);
        UUID item=UUID.randomUUID(),store=UUID.randomUUID(),offer=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,item,"IMG-"+UUID.randomUUID());
        jdbc.update("""
                INSERT INTO crm.cat_item_i18n
                 (tenant_id,item_id,locale,name,description)
                VALUES (?,?,'ru','Camera','Public camera description')
                """,tenant,item);
        jdbc.update("""
                INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
                VALUES (?,?,?,'PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,store,"media-"+UUID.randomUUID());
        jdbc.update("""
                INSERT INTO crm.store_offer
                 (tenant_id,id,title,unit_price,currency,state,catalog_item_id,row_version)
                VALUES (?,?,'Camera Offer',900,'KZT','PUBLISHED',?,0)
                """,tenant,offer,item);
        String publicOffer=qr.issueOrGet(staff,tenant,offer,0,
                "QRIMG_"+UUID.randomUUID().toString().replace("-","")).publicRef();
        return new Fixture(tenant,staff,item,offer,publicOffer,uploader);
    }

    private static byte[] jpeg() throws IOException {
        BufferedImage img=new BufferedImage(4,3,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<3;y++)for(int x=0;x<4;x++)
            img.setRGB(x,y,0x123400+x*200+y*500);
        var out=new ByteArrayOutputStream();
        if(!ImageIO.write(img,"jpeg",out))throw new IOException("JPEG writer unavailable");
        return out.toByteArray();
    }
    private UUID upload(Fixture f,byte[] bytes,String mime) throws IOException {
        var intent=files.begin(f.staff(),f.tenant(),mime,bytes.length,
                "img-"+UUID.randomUUID());
        var result=complete.acceptAndComplete(f.staff(),f.tenant(),intent.fileId(),
                new ByteArrayInputStream(bytes));
        assertThat(result.state()).isEqualTo("AVAILABLE");
        return intent.fileId();
    }

    @Test void qr02aApprovedDerivativeIsPublicPNGButOriginalStaysPrivate() throws Exception {
        var f=setup();UUID source=upload(f,jpeg(),"image/jpeg");
        String key="IMG_"+UUID.randomUUID().toString().replace("-","");
        var image=media.publishSanitized(f.staff(),f.tenant(),f.item(),source,0,key,true);
        assertThat(image.mimeType()).isEqualTo("image/png");
        assertThat(image.width()).isEqualTo(4);
        assertThat(image.height()).isEqualTo(3);
        assertThat(media.listPublished(f.offerRef(),20)).containsExactly(image);
        var card=cards.findPublished(f.offerRef(),"ru");
        assertThat(card.title()).isEqualTo("Camera");
        var binary=new ByteArrayOutputStream();
        assertThat(media.copyPublished(image.publicRef(),binary)).isEqualTo(binary.size());
        byte[] png=binary.toByteArray();
        assertThat(png[0]).isEqualTo((byte)0x89);
        assertThat(png[1]).isEqualTo((byte)'P');
        assertThat(ImageIO.read(new ByteArrayInputStream(png)).getWidth()).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_attachment
                WHERE tenant_id=? AND owner_type='CATALOG_ITEM' AND owner_id=?
                  AND purpose='PUBLIC_PRODUCT_IMAGE' AND detached_at IS NULL
                """,Integer.class,f.tenant(),f.item())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_attachment
                WHERE tenant_id=? AND file_id=?
                """,Integer.class,f.tenant(),source)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_catalog_public_variant v
                JOIN crm.file_object f
                  ON f.tenant_id=v.tenant_id AND f.id=v.derivative_file_id
                WHERE v.tenant_id=? AND v.item_id=? AND f.mime_type='image/png'
                  AND f.scan_status='CLEAN' AND f.state='AVAILABLE'
                  AND f.content_class='INTERNAL' AND v.sanitizer_version='PNG_R1'
                """,Integer.class,f.tenant(),f.item())).isEqualTo(1);
    }

    @Test void qr02aSellerMustExplicitlyApproveAndNeverPublishCrmAttachment() throws Exception {
        var f=setup();UUID source=upload(f,jpeg(),"image/jpeg");
        String key="IMG_"+UUID.randomUUID().toString().replace("-","");
        assertThatThrownBy(()->media.publishSanitized(f.staff(),f.tenant(),f.item(),
                source,0,key,false)).isInstanceOf(SecurityException.class);
        assertThat(media.listPublished(f.offerRef(),20)).isEmpty();
        jdbc.update("""
                INSERT INTO crm.file_attachment
                 (tenant_id,id,file_id,owner_type,owner_id,purpose,created_at,created_by)
                VALUES (?,?,?,'CRM_CUSTOMER',?,'PHOTO',CURRENT_TIMESTAMP,?)
                """,f.tenant(),UUID.randomUUID(),source,UUID.randomUUID(),f.ownerId());
        assertThatThrownBy(()->media.publishSanitized(f.staff(),f.tenant(),f.item(),
                source,0,key,true)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_catalog_public_variant
                WHERE tenant_id=? AND item_id=?
                """,Integer.class,f.tenant(),f.item())).isZero();
    }

    @Test void qr02aRevocationAndSellerPauseImmediatelyBlockNewPublicReads() throws Exception {
        var f=setup();UUID source=upload(f,jpeg(),"image/jpeg");
        var image=media.publishSanitized(f.staff(),f.tenant(),f.item(),source,1,
                "IMG_"+UUID.randomUUID().toString().replace("-",""),true);
        jdbc.update("""
                UPDATE crm.store_offer SET state='HIDDEN',row_version=row_version+1
                WHERE tenant_id=? AND id=?
                """,f.tenant(),f.offer());
        assertThat(media.listPublished(f.offerRef(),20)).isEmpty();
        assertThatThrownBy(()->media.copyPublished(image.publicRef(),
                new ByteArrayOutputStream())).hasMessage("PUBLIC_MEDIA_UNAVAILABLE");
        jdbc.update("""
                UPDATE crm.store_offer SET state='PUBLISHED',row_version=row_version+1
                WHERE tenant_id=? AND id=?
                """,f.tenant(),f.offer());
        media.revoke(f.staff(),f.tenant(),image.publicRef());
        assertThat(media.listPublished(f.offerRef(),20)).isEmpty();
        assertThatThrownBy(()->media.copyPublished(image.publicRef(),
                new ByteArrayOutputStream())).hasMessage("PUBLIC_MEDIA_UNAVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_attachment
                WHERE tenant_id=? AND owner_type='CATALOG_ITEM' AND detached_at IS NULL
                """,Integer.class,f.tenant())).isZero();
    }

    @Test void qr02aSameRequestReplaysAndDifferentImageCannotOccupySameSlot() throws Exception {
        var f=setup();UUID source=upload(f,jpeg(),"image/jpeg");
        String key="IMG_"+UUID.randomUUID().toString().replace("-","");
        var first=media.publishSanitized(f.staff(),f.tenant(),f.item(),source,0,key,true);
        assertThat(media.publishSanitized(f.staff(),f.tenant(),f.item(),
                source,0,key,true)).isEqualTo(first);
        UUID another=upload(f,jpeg(),"image/jpeg");
        assertThatThrownBy(()->media.publishSanitized(f.staff(),f.tenant(),f.item(),
                another,0,key,true)).hasMessage("PUBLIC_MEDIA_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->media.publishSanitized(f.staff(),f.tenant(),f.item(),
                another,0,"IMG_"+UUID.randomUUID().toString().replace("-",""),true))
                .hasMessage("PUBLIC_MEDIA_SLOT_OCCUPIED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.file_catalog_public_variant
                WHERE tenant_id=? AND item_id=?
                """,Integer.class,f.tenant(),f.item())).isEqualTo(1);
    }

    @Test void qr02aForeignTenantAndUnavailableOriginalFailClosed() throws Exception {
        var a=setup(),b=setup();UUID source=upload(a,jpeg(),"image/jpeg");
        assertThatThrownBy(()->media.publishSanitized(b.staff(),b.tenant(),b.item(),
                source,0,"IMG_"+UUID.randomUUID().toString().replace("-",""),true))
                .isInstanceOf(SecurityException.class);
        jdbc.update("""
                UPDATE crm.file_object SET scan_status='ERROR'
                WHERE tenant_id=? AND id=?
                """,a.tenant(),source);
        assertThatThrownBy(()->media.publishSanitized(a.staff(),a.tenant(),a.item(),
                source,0,"IMG_"+UUID.randomUUID().toString().replace("-",""),true))
                .isInstanceOf(SecurityException.class);
        assertThat(media.listPublished(a.offerRef(),20)).isEmpty();
    }
}
