package kz.rm.storefront;

import kz.rm.iam.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * QR-01 source-owned service qualification. No HTTP route or fictitious
 * authentication token: JWT principal is synthetic only for service tests.
 */
@SpringBootTest
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class PublishedOfferQrQualificationTest {
    @Autowired PublishedOfferQrService qr;
    @Autowired PublishedOfferCardQuery cards;
    @Autowired StorefrontManagementService storefronts;
    @Autowired StorefrontOfferService offers;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private record Fixture(UUID tenant, VerifiedActor actor, UUID storeId,
                           UUID offerId, long offerVersion, UUID catalogItemId) {}

    private VerifiedActor verified(UUID userId) {
        String subject = jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class, userId);
        JwtDecoder decoder = token -> Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-qr");
    }

    private Fixture fixture(String sku, String kind) {
        var org = organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"QR Seller LLP","QR Seller","KZ"));
        UUID tenant = workspaces.saveAndFlush(WorkspaceEntity.organization(
                UUID.randomUUID(),org.getId())).getId();
        UUID owner = users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"qr-owner-"+UUID.randomUUID(),"QR owner")).getId();
        UUID staff = users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"qr-staff-"+UUID.randomUUID(),"QR seller")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        var admin = verified(owner);
        var role = roles.createRole(admin,tenant,
                "QR_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "QR seller");
        roles.grant(admin,tenant,role.id(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.grant(admin,tenant,role.id(),DomainPermission.STOREFRONT_OFFER_PUBLISH);
        roles.assign(admin,tenant,staff,role.id());
        roles.enable(admin,tenant);
        var actor = verified(staff);
        var store = storefronts.createDraft(actor,tenant,"qr-"+UUID.randomUUID());
        var publishedStore = storefronts.publish(actor,tenant,store.id(),store.rowVersion());
        UUID product = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item
                  (tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,?,'ru','PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,product,sku,kind);
        jdbc.update("""
                INSERT INTO crm.cat_item_i18n
                  (tenant_id,item_id,locale,name,description)
                VALUES (?,?,'ru','Датчик','Описание товара')
                """,tenant,product);
        if ("SERVICE".equals(kind))
            return new Fixture(tenant,actor,publishedStore.id(),null,0L,product);
        var draft = offers.createDraft(actor,tenant,product,"Offer QR",
                new BigDecimal("1250.0000"),"KZT");
        var published = offers.publish(actor,tenant,draft.id(),draft.rowVersion());
        return new Fixture(tenant,actor,publishedStore.id(),
                published.id(),published.rowVersion(),product);
    }

    private int countActive(Fixture f) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.store_offer_qr_ref
                WHERE tenant_id=? AND offer_id=? AND state='ACTIVE'
                """,Integer.class,f.tenant(),f.offerId());
    }

    @Test void qrP01StableReprintAndCurrentPublishedPriceAfterRevision() {
        var f=fixture("SENSOR-X","PRODUCT");
        var first=qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion());
        assertThat(first.generation()).isEqualTo(1);
        assertThat(first.publicRef()).hasSize(32);
        assertThat(qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion())
                .publicRef()).isEqualTo(first.publicRef());
        assertThat(cards.findPublished(first.publicRef(),"ru").unitPrice())
                .isEqualByComparingTo("1250");
        assertThat(cards.findPublished(first.publicRef(),"ru").description())
                .isEqualTo("Описание товара");
        var changed=offers.updatePrice(f.actor(),f.tenant(),f.offerId(),"Offer QR",
                new BigDecimal("1390.00"),"KZT",f.offerVersion());
        assertThat(qr.reprint(f.actor(),f.tenant(),f.offerId()).publicRef())
                .isEqualTo(first.publicRef());
        var card=cards.findPublished(first.publicRef(),"kk");
        assertThat(card.unitPrice()).isEqualByComparingTo("1390");
        assertThat(card.locale()).isEqualTo("ru"); // published source translation fallback
        assertThat(card.offerRowVersion()).isEqualTo(changed.rowVersion());
        assertThat(card.availabilityBand()).isEqualTo("UNKNOWN"); // QR-02 is not delivered
        assertThat(countActive(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.audit_event
                WHERE tenant_id=? AND entity_id=? AND action_code='PRODUCT_QR_ISSUED'
                """,Integer.class,f.tenant(),f.offerId())).isEqualTo(1);
        assertThatThrownBy(()->qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),
                f.offerVersion())).hasMessageContaining("STALE_OFFER_VERSION");
    }

    @Test void qrP03PausedHiddenAndRevokedReferencesCannotExposeCard() {
        var f=fixture("SKU-PAUSE","PRODUCT");
        var ref=qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion());
        assertThat(cards.findPublished(ref.publicRef(),"ru").sku()).isEqualTo("SKU-PAUSE");
        var hidden=offers.hide(f.actor(),f.tenant(),f.offerId(),f.offerVersion());
        assertThatThrownBy(()->cards.findPublished(ref.publicRef(),"ru"))
                .hasMessage("QR_UNAVAILABLE");
        var again=offers.publish(f.actor(),f.tenant(),f.offerId(),hidden.rowVersion());
        assertThat(cards.findPublished(ref.publicRef(),"ru").currency()).isEqualTo("KZT");
        var revoked=qr.revoke(f.actor(),f.tenant(),f.offerId(),ref.rowVersion());
        assertThat(revoked.state()).isEqualTo(PublishedOfferQrService.State.REVOKED);
        assertThatThrownBy(()->cards.findPublished(ref.publicRef(),"ru"))
                .hasMessage("QR_UNAVAILABLE");
        assertThat(countActive(f)).isZero();
        var newer=qr.reissue(f.actor(),f.tenant(),f.offerId(),again.rowVersion());
        assertThat(newer.generation()).isEqualTo(2);
        assertThat(newer.publicRef()).isNotEqualTo(ref.publicRef());
        assertThat(cards.findPublished(newer.publicRef(),"ru").sku()).isEqualTo("SKU-PAUSE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.store_offer_qr_ref WHERE tenant_id=? AND offer_id=?
                """,Integer.class,f.tenant(),f.offerId())).isEqualTo(2);
        assertThatThrownBy(()->cards.findPublished("https://evil.example","ru"))
                .hasMessage("QR_UNAVAILABLE");
    }

    @Test void qrP02SameSkuAcrossSellersAndForeignTenantMutationsRemainIsolated() {
        var a=fixture("SHARED-SKU","PRODUCT");
        var b=fixture("SHARED-SKU","PRODUCT");
        var ra=qr.issueOrGet(a.actor(),a.tenant(),a.offerId(),a.offerVersion());
        var rb=qr.issueOrGet(b.actor(),b.tenant(),b.offerId(),b.offerVersion());
        assertThat(ra.publicRef()).isNotEqualTo(rb.publicRef());
        assertThat(cards.findPublished(ra.publicRef(),"ru").sku()).isEqualTo("SHARED-SKU");
        assertThatThrownBy(()->qr.revoke(b.actor(),a.tenant(),a.offerId(),ra.rowVersion()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->qr.reprint(a.actor(),a.tenant(),b.offerId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cards.findPublished(ra.publicRef(),"ru").unitPrice())
                .isEqualByComparingTo("1250");
        assertThat(countActive(a)).isEqualTo(1);
        assertThat(countActive(b)).isEqualTo(1);
    }

    @Test void qrP03UnpublishedProductAndUnknownLocaleDenied() {
        var f=fixture("SKU-LOCALE","PRODUCT");
        var ref=qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion());
        assertThatThrownBy(()->cards.findPublished(ref.publicRef(),"xx"))
                .isInstanceOf(IllegalArgumentException.class);
        jdbc.update("""
                UPDATE crm.cat_item SET state='ARCHIVED'
                WHERE tenant_id=? AND id=?
                """,f.tenant(),f.catalogItemId());
        assertThatThrownBy(()->cards.findPublished(ref.publicRef(),"ru"))
                .hasMessage("QR_UNAVAILABLE");
        assertThatThrownBy(()->qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),
                f.offerVersion())).hasMessageContaining("QR_PRODUCT_NOT_PUBLISHED");
    }

    @Test void qrP01ConcurrentIssueOneActiveReferenceInRealPostgres() throws Exception {
        try(var c=dataSource.getConnection()) {
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture("SKU-RACE","PRODUCT");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion());
            });
            var second=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion());
            });
            start.countDown();
            assertThat(first.get(30,TimeUnit.SECONDS).publicRef())
                    .isEqualTo(second.get(30,TimeUnit.SECONDS).publicRef());
        }
        assertThat(countActive(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.store_offer_qr_ref
                WHERE tenant_id=? AND offer_id=? AND generation=1
                """,Integer.class,f.tenant(),f.offerId())).isEqualTo(1);
    }

    @Test void qr01bExactRequestReplaySurvivesPriceUpdateAndRevocation() {
        var f=fixture("REPLAY-PRICE","PRODUCT");
        String key="QR01B_"+UUID.randomUUID().toString().replace("-","");
        var issued=qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion(),key);
        var updated=offers.updatePrice(f.actor(),f.tenant(),f.offerId(),
                "Offer QR",new BigDecimal("1600.0000"),"KZT",f.offerVersion());
        assertThat(updated.rowVersion()).isGreaterThan(f.offerVersion());
        var retry=qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion(),key);
        assertThat(retry).isEqualTo(issued);
        assertThat(qr.revoke(f.actor(),f.tenant(),f.offerId(),issued.rowVersion())
                .state()).isEqualTo(PublishedOfferQrService.State.REVOKED);
        assertThat(qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion(),key))
                .isEqualTo(issued); // command-time result; public read is now unavailable
        assertThatThrownBy(()->cards.findPublished(issued.publicRef(),"ru"))
                .hasMessage("QR_UNAVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.store_offer_qr_receipt WHERE tenant_id=? AND request_key=?
                """,Integer.class,f.tenant(),key)).isEqualTo(1);
    }

    @Test void qr01bSameKeyWithOtherOperationOrOfferIsRejectedBeforeMutation() {
        var f=fixture("KEY-INTENT","PRODUCT");
        var second=fixture("KEY-INTENT","PRODUCT");
        String key="QR01B_"+UUID.randomUUID().toString().replace("-","");
        var reference=qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion(),key);
        assertThatThrownBy(()->qr.revoke(f.actor(),f.tenant(),f.offerId(),
                reference.rowVersion(),key)).hasMessage("QR_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),
                f.offerVersion()+1,key)).hasMessage("QR_IDEMPOTENCY_CONFLICT");
        assertThat(qr.reprint(f.actor(),f.tenant(),f.offerId()).state())
                .isEqualTo(PublishedOfferQrService.State.ACTIVE);
        // Same key may be reused in another seller tenant without collision.
        assertThat(qr.issueOrGet(second.actor(),second.tenant(),second.offerId(),
                second.offerVersion(),key).publicRef()).isNotEqualTo(reference.publicRef());
        assertThat(countActive(f)).isEqualTo(1);
        assertThat(countActive(second)).isEqualTo(1);
    }

    @Test void qr01bRevokeReissueAndExactRetriesProduceOneGenerationEach() {
        var f=fixture("REVOCATION-REISSUE","PRODUCT");
        String issueKey="QR01B_"+UUID.randomUUID().toString().replace("-","");
        String revokeKey="QR01B_"+UUID.randomUUID().toString().replace("-","");
        String reissueKey="QR01B_"+UUID.randomUUID().toString().replace("-","");
        var first=qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion(),issueKey);
        var revoked=qr.revoke(f.actor(),f.tenant(),f.offerId(),first.rowVersion(),revokeKey);
        assertThat(qr.revoke(f.actor(),f.tenant(),f.offerId(),
                first.rowVersion(),revokeKey)).isEqualTo(revoked);
        var next=qr.reissue(f.actor(),f.tenant(),f.offerId(),f.offerVersion(),reissueKey);
        assertThat(next.generation()).isEqualTo(2);
        assertThat(qr.reissue(f.actor(),f.tenant(),f.offerId(),
                f.offerVersion(),reissueKey)).isEqualTo(next);
        assertThatThrownBy(()->qr.reissue(f.actor(),f.tenant(),f.offerId(),
                f.offerVersion(),"QR01B_"+UUID.randomUUID().toString().replace("-","")))
                .hasMessage("QR_ALREADY_ACTIVE");
        assertThat(countActive(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.store_offer_qr_receipt
                WHERE tenant_id=? AND offer_id=?
                """,Integer.class,f.tenant(),f.offerId())).isEqualTo(3);
    }

    @Test void qr01bBadRequestKeyAndHiddenOfferNeverProduceReceipt() {
        var f=fixture("INVALID-KEY","PRODUCT");
        assertThatThrownBy(()->qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),
                f.offerVersion(),"bad")).hasMessage("QR_REQUEST_KEY_INVALID");
        var hidden=offers.hide(f.actor(),f.tenant(),f.offerId(),f.offerVersion());
        String key="QR01B_"+UUID.randomUUID().toString().replace("-","");
        assertThatThrownBy(()->qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),
                hidden.rowVersion(),key)).hasMessage("QR_OFFER_NOT_PUBLISHED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.store_offer_qr_receipt WHERE tenant_id=? AND request_key=?
                """,Integer.class,f.tenant(),key)).isZero();
        var published=offers.publish(f.actor(),f.tenant(),f.offerId(),hidden.rowVersion());
        assertThat(qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),
                published.rowVersion(),key).generation()).isEqualTo(1);
    }

    @Test void qr01bConcurrentSameRequestKeySerializedInPostgres() throws Exception {
        try (var c=dataSource.getConnection()) {
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture("RACE-IDEM","PRODUCT");
        String key="QR01B_"+UUID.randomUUID().toString().replace("-","");
        var start=new CountDownLatch(1);
        try (var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion(),key);
            });
            var second=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion(),key);
            });
            start.countDown();
            assertThat(first.get(30,TimeUnit.SECONDS)).isEqualTo(second.get(30,TimeUnit.SECONDS));
        }
        assertThat(countActive(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.store_offer_qr_receipt
                WHERE tenant_id=? AND request_key=?
                """,Integer.class,f.tenant(),key)).isEqualTo(1);
    }

    @Test void qr01bPostgresCommandReceiptCannotBeOverwrittenOrDeleted() throws Exception {
        try (var c=dataSource.getConnection()) {
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=fixture("IMMUTABLE-RCPT","PRODUCT");
        String key="QR01B_"+UUID.randomUUID().toString().replace("-","");
        qr.issueOrGet(f.actor(),f.tenant(),f.offerId(),f.offerVersion(),key);
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.store_offer_qr_receipt SET action_code='REVOKE'
                WHERE tenant_id=? AND request_key=?
                """,f.tenant(),key)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.store_offer_qr_receipt
                WHERE tenant_id=? AND request_key=?
                """,f.tenant(),key)).isInstanceOf(Exception.class);
    }
}
