package kz.rm.storefront;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class SellerOfferQualificationTest {
    @Autowired SellerOfferService offers;
    @Autowired AutoOrderService checkout;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;

    private record Context(UUID tenant, UUID owner, UUID seller, UUID outsider,
                           UUID sku, UUID storefront, String slug) {}

    private UUID user(String name) {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            name+"-"+UUID.randomUUID(), name)).getId();
    }

    private VerifiedActor verified(UUID userId) {
        String subject=jdbc.queryForObject(
            "SELECT external_subject FROM iam_user_account WHERE id=?", String.class,userId);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
            .header("alg","RS256").subject(subject).issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-test-actor");
    }

    private Context context() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
            UUID.randomUUID(),"Offers Qualification LLP","Offers","KZ"));
        var tenant=workspaces.saveAndFlush(
            WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID owner=user("offer-owner"),seller=user("offer-seller"),outsider=user("offer-outsider");
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),org.getId(),seller,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        UUID sku=UUID.randomUUID(),storefront=UUID.randomUUID();
        String slug="seller-offer-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,tenant,sku,"seller-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
            VALUES (?,?,?,'PUBLISHED',CURRENT_TIMESTAMP)
            """,tenant,storefront,slug);
        return new Context(tenant,owner,seller,outsider,sku,storefront,slug);
    }

    private void grant(Context c, DomainPermission... permissions) {
        var r=roles.createRole(verified(c.owner()),c.tenant(),
            "OFFER_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
            "Seller offer operations");
        for(var permission:permissions) roles.grant(verified(c.owner()),c.tenant(),r.id(),permission);
        roles.assign(verified(c.owner()),c.tenant(),c.seller(),r.id());
        roles.enable(verified(c.owner()),c.tenant());
    }

    @Test void permissionsAreTenantLocalEvenForDualTenantMember() {
        Context a=context(),b=context();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),
            jdbc.queryForObject("SELECT organization_id FROM iam_workspace WHERE id=?",
                UUID.class,b.tenant()),
            a.seller(),OrganizationMembershipEntity.Role.MEMBER));
        grant(a,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        assertThatThrownBy(()->offers.createDraft(verified(a.seller()),b.tenant(),b.sku(),
            "Denied",BigDecimal.TEN,"KZT")).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM crm.store_offer WHERE tenant_id=?",
            Integer.class,b.tenant())).isZero();
    }

    @Test void sellerPermissionRevocationFencesNextCommand() {
        Context c=context();
        var role=roles.createRole(verified(c.owner()),c.tenant(),
            "OFFER_REVOKE_"+UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase(),
            "Revocable offers role");
        roles.grant(verified(c.owner()),c.tenant(),role.id(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.assign(verified(c.owner()),c.tenant(),c.seller(),role.id());
        roles.enable(verified(c.owner()),c.tenant());
        var actor=verified(c.seller());
        var draft=offers.createDraft(actor,c.tenant(),c.sku(),"Revocable",BigDecimal.TEN,"KZT");
        roles.revoke(verified(c.owner()),c.tenant(),role.id(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        assertThatThrownBy(()->offers.changePrice(actor,c.tenant(),draft.id(),0,
            BigDecimal.valueOf(20),"KZT")).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject(
            "SELECT row_version FROM crm.store_offer WHERE tenant_id=? AND id=?",
            Long.class,c.tenant(),draft.id())).isZero();
    }

    @Test void postgresOfferCatalogIdentityCannotBeRemapped() throws Exception {
        requirePostgres();
        Context c=context();
        grant(c,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var actor=verified(c.seller());
        var draft=offers.createDraft(actor,c.tenant(),c.sku(),"SKU invariant",BigDecimal.TEN,"KZT");
        offers.publish(actor,c.tenant(),draft.id(),0);
        UUID replacement=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,c.tenant(),replacement,"remap-"+UUID.randomUUID());
        assertThatThrownBy(()->jdbc.update("""
            UPDATE crm.store_offer SET catalog_item_id=? WHERE tenant_id=? AND id=?
            """,replacement,c.tenant(),draft.id())).isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject(
            "SELECT catalog_item_id FROM crm.store_offer WHERE tenant_id=? AND id=?",
            UUID.class,c.tenant(),draft.id())).isEqualTo(c.sku());
    }

    @Test void foreignOfferAndMissingOfferAreIndistinguishableToTenantSeller() {
        Context a=context(),b=context();
        grant(a,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        grant(b,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var foreign=offers.createDraft(verified(b.seller()),b.tenant(),b.sku(),
            "Foreign",BigDecimal.TEN,"KZT");
        var aActor=verified(a.seller());
        assertThatThrownBy(()->offers.publish(aActor,a.tenant(),foreign.id(),0))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Offer unavailable");
        assertThatThrownBy(()->offers.publish(aActor,a.tenant(),UUID.randomUUID(),0))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Offer unavailable");
    }

    @Test void invalidPriceCurrencyAndNonStockableSkuFailBeforeOfferCreation() {
        Context c=context();
        grant(c,DomainPermission.STOREFRONT_OFFER_MANAGE);
        var actor=verified(c.seller());
        assertThatThrownBy(()->offers.createDraft(actor,c.tenant(),c.sku(),"Free",
            BigDecimal.ZERO,"KZT")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->offers.createDraft(actor,c.tenant(),c.sku(),"Unknown currency",
            BigDecimal.ONE,"ZZZ")).isInstanceOf(IllegalArgumentException.class);
        UUID service=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'SERVICE','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,c.tenant(),service,"service-"+UUID.randomUUID());
        assertThatThrownBy(()->offers.createDraft(actor,c.tenant(),service,"Service",
            BigDecimal.ONE,"KZT")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OFFER_SKU_NOT_SELLABLE");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM crm.store_offer WHERE tenant_id=?",
            Integer.class,c.tenant())).isZero();
    }

    @Test void offerPublishRequiresPublishedProductAndPublishedStorefront() {
        Context c=context();
        grant(c,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var actor=verified(c.seller());
        var draft=offers.createDraft(actor,c.tenant(),c.sku(),"Guarded",BigDecimal.TEN,"KZT");
        offers.changeStorefrontState(actor,c.tenant(),0,"PAUSED");
        assertThatThrownBy(()->offers.publish(actor,c.tenant(),draft.id(),0))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("STOREFRONT_UNAVAILABLE");
        offers.changeStorefrontState(actor,c.tenant(),1,"PUBLISHED");
        jdbc.update("UPDATE crm.cat_item SET state='ARCHIVED' WHERE tenant_id=? AND id=?",
            c.tenant(),c.sku());
        assertThatThrownBy(()->offers.publish(actor,c.tenant(),draft.id(),0))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFER_SKU_NOT_SELLABLE");
        assertThat(jdbc.queryForObject(
            "SELECT state FROM crm.store_offer WHERE tenant_id=? AND id=?",
            String.class,c.tenant(),draft.id())).isEqualTo("DRAFT");
    }

    @Test void tenantAuthorizedStorefrontDraftCreationAndStateCas() {
        Context c=context();
        grant(c,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        // Existing tenant storefront cannot be silently replaced.
        assertThatThrownBy(()->offers.createStorefrontDraft(verified(c.seller()),
            c.tenant(),"different-store")).isInstanceOf(IllegalStateException.class);
        var actor=verified(c.seller());
        assertThat(offers.changeStorefrontState(actor,c.tenant(),0,"PAUSED").state())
            .isEqualTo("PAUSED");
        assertThatThrownBy(()->offers.changeStorefrontState(actor,c.tenant(),0,"PUBLISHED"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OFFER_VERSION_CONFLICT");
        assertThat(offers.changeStorefrontState(actor,c.tenant(),1,"PUBLISHED").state())
            .isEqualTo("PUBLISHED");
    }

    private void requirePostgres() throws Exception {
        try (var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
    }

    @Test void postgresConcurrentPriceCasExactlyOneWriterWins() throws Exception {
        requirePostgres();
        Context c=context();
        grant(c,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var actor=verified(c.seller());
        var draft=offers.createDraft(actor,c.tenant(),c.sku(),"Race",BigDecimal.TEN,"KZT");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                try {offers.changePrice(actor,c.tenant(),draft.id(),0,
                    new BigDecimal("20"),"KZT");return true;}
                catch(IllegalStateException e) {
                    if(!e.getMessage().contains("OFFER_VERSION_CONFLICT"))throw e;
                    return false;
                }
            });
            var second=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                try {offers.changePrice(actor,c.tenant(),draft.id(),0,
                    new BigDecimal("30"),"KZT");return true;}
                catch(IllegalStateException e) {
                    if(!e.getMessage().contains("OFFER_VERSION_CONFLICT"))throw e;
                    return false;
                }
            });
            start.countDown();
            assertThat((first.get(30,TimeUnit.SECONDS)?1:0)
                +(second.get(30,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
            "SELECT row_version FROM crm.store_offer WHERE tenant_id=? AND id=?",
            Long.class,c.tenant(),draft.id())).isEqualTo(1L);
    }

    @Test void postgresCheckoutPauseSerializesWithoutPartialOrders() throws Exception {
        requirePostgres();
        Context c=context();
        grant(c,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var actor=verified(c.seller());
        var draft=offers.createDraft(actor,c.tenant(),c.sku(),"Race checkout",BigDecimal.TEN,"KZT");
        offers.publish(actor,c.tenant(),draft.id(),0);
        String email="buyer-"+UUID.randomUUID()+"@example.kz";
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var buyer=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                try {return checkout.checkout(c.slug(),email,"checkout-"+UUID.randomUUID(),
                    List.of(new AutoOrderService.Line(draft.id(),1))).order().orderId();}
                catch(IllegalArgumentException e) {return null;}
            });
            var pause=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                return offers.pause(actor,c.tenant(),draft.id(),1);
            });
            start.countDown();
            UUID accepted=buyer.get(30,TimeUnit.SECONDS);
            assertThat(pause.get(30,TimeUnit.SECONDS).state()).isEqualTo("PAUSED");
            Integer orders=jdbc.queryForObject(
                "SELECT count(*) FROM crm.sales_order WHERE tenant_id=?",
                Integer.class,c.tenant());
            assertThat(orders).isEqualTo(accepted==null?0:1);
            Integer events=jdbc.queryForObject(
                "SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=?",
                Integer.class,c.tenant());
            assertThat(events).isEqualTo(orders);
        }
    }

    @Test void draftPublicationPauseResumeAndCas() {
        Context c=context();
        grant(c,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var actor=verified(c.seller());
        var draft=offers.createDraft(actor,c.tenant(),c.sku(),"Published product",
            new BigDecimal("100"),"KZT");
        assertThat(draft.state()).isEqualTo("DRAFT");
        assertThat(draft.rowVersion()).isZero();
        var published=offers.publish(actor,c.tenant(),draft.id(),0);
        assertThat(published.state()).isEqualTo("PUBLISHED");
        assertThat(published.rowVersion()).isEqualTo(1);
        assertThatThrownBy(()->offers.pause(actor,c.tenant(),draft.id(),0))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFER_VERSION_CONFLICT");
        var priced=offers.changePrice(actor,c.tenant(),draft.id(),1,new BigDecimal("125"),"KZT");
        assertThat(priced.rowVersion()).isEqualTo(2);
        assertThat(priced.price()).isEqualByComparingTo("125");
        var paused=offers.pause(actor,c.tenant(),draft.id(),2);
        assertThat(paused.state()).isEqualTo("PAUSED");
        assertThat(offers.resume(actor,c.tenant(),draft.id(),3).state()).isEqualTo("PUBLISHED");
    }

    @Test void denialAndForeignTenantAreFailClosed() {
        Context a=context(),b=context();
        grant(a,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var aa=verified(a.seller());
        assertThatThrownBy(()->offers.createDraft(verified(a.outsider()),a.tenant(),a.sku(),
            "X",BigDecimal.ONE,"KZT")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->offers.createDraft(aa,b.tenant(),b.sku(),
            "X",BigDecimal.ONE,"KZT")).isInstanceOf(SecurityException.class);
        var own=offers.createDraft(aa,a.tenant(),a.sku(),"Allowed",BigDecimal.TEN,"KZT");
        assertThatThrownBy(()->offers.publish(aa,a.tenant(),UUID.randomUUID(),0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Offer unavailable");
        assertThatThrownBy(()->offers.publish(aa,a.tenant(),own.id(),1))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFER_VERSION_CONFLICT");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM crm.store_offer WHERE tenant_id=? AND id=?",
            Integer.class,a.tenant(),own.id())).isEqualTo(1);
    }

    @Test void acceptedOrderPriceAndSkuSurviveSellerPriceRevision() {
        Context c=context();
        grant(c,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var actor=verified(c.seller());
        var draft=offers.createDraft(actor,c.tenant(),c.sku(),"Accepted",new BigDecimal("100"),"KZT");
        offers.publish(actor,c.tenant(),draft.id(),0);
        String key="checkout-"+UUID.randomUUID();
        String email="buyer-"+UUID.randomUUID()+"@example.kz";
        var first=checkout.checkout(c.slug(),email,
            key,List.of(new AutoOrderService.Line(draft.id(),2)));
        offers.changePrice(actor,c.tenant(),draft.id(),1,new BigDecimal("150"),"KZT");
        var snapshot=jdbc.queryForMap("""
            SELECT catalog_item_id_snapshot,unit_price_snapshot,currency
            FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
            """,c.tenant(),first.order().orderId());
        assertThat(snapshot.get("catalog_item_id_snapshot")).isEqualTo(c.sku());
        assertThat((BigDecimal)snapshot.get("unit_price_snapshot")).isEqualByComparingTo("100");
        assertThat(snapshot.get("currency")).isEqualTo("KZT");
        assertThat(checkout.checkout(c.slug(),email,key,
            List.of(new AutoOrderService.Line(draft.id(),2))).order().orderId())
            .isEqualTo(first.order().orderId());
    }

    @Test void pausedStorefrontDeniesNewCheckoutAndExactRetryRemainsReadable() {
        Context c=context();
        grant(c,DomainPermission.STOREFRONT_OFFER_MANAGE,DomainPermission.STOREFRONT_OFFER_PUBLISH);
        var actor=verified(c.seller());
        var draft=offers.createDraft(actor,c.tenant(),c.sku(),"Paused",BigDecimal.TEN,"KZT");
        offers.publish(actor,c.tenant(),draft.id(),0);
        String email="buyer-"+UUID.randomUUID()+"@example.kz",key="checkout-"+UUID.randomUUID();
        UUID accepted=checkout.checkout(c.slug(),email,key,
            List.of(new AutoOrderService.Line(draft.id(),1))).order().orderId();
        offers.changeStorefrontState(actor,c.tenant(),0,"PAUSED");
        assertThatThrownBy(()->checkout.checkout(c.slug(),"new-"+UUID.randomUUID()+"@example.kz",
            "checkout-"+UUID.randomUUID(),List.of(new AutoOrderService.Line(draft.id(),1))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(checkout.checkout(c.slug(),email,key,
            List.of(new AutoOrderService.Line(draft.id(),1))).order().orderId()).isEqualTo(accepted);
    }
}
