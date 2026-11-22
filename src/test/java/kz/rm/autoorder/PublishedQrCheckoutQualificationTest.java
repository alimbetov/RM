package kz.rm.autoorder;

import kz.rm.iam.*;
import kz.rm.storefront.PublishedOfferQrService;
import kz.rm.storefront.StorefrontOfferService;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * QR-03: public QR -> existing email-first AUTOORDER checkout with exact
 * server-side acknowledgement of an offer revision under row locks.
 * No HTTP controller, no synthetic "buyer-is-seller" membership.
 */
@SpringBootTest
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class PublishedQrCheckoutQualificationTest {
    @Autowired PublishedQrCheckoutService qrCheckout;
    @Autowired AutoOrderService auto;
    @Autowired PublishedOfferQrService qr;
    @Autowired StorefrontOfferService sellerOffers;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private record Fixture(UUID tenant,UUID offer,UUID item,String slug,
                           String offerRef,VerifiedActor seller) {}

    private VerifiedActor verified(UUID user) {
        String subject=jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-qcheckout");
    }

    private Fixture setup() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"QR Commerce LLP","QR Commerce","KZ"));
        UUID tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID owner=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"qco-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID sellerId=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"qco-seller-"+UUID.randomUUID(),"Seller")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),sellerId,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        var admin=verified(owner);
        var role=roles.createRole(admin,tenant,
                "QCO_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "QR checkout seller");
        roles.grant(admin,tenant,role.id(),DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.assign(admin,tenant,sellerId,role.id());
        roles.enable(admin,tenant);
        UUID store=UUID.randomUUID(),item=UUID.randomUUID(),offer=UUID.randomUUID();
        String slug="qco-"+UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
                VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,item,"QCO-"+UUID.randomUUID());
        jdbc.update("""
                INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
                VALUES (?,?,?,'PUBLISHED',CURRENT_TIMESTAMP)
                """,tenant,store,slug);
        jdbc.update("""
                INSERT INTO crm.store_offer
                 (tenant_id,id,title,unit_price,currency,state,catalog_item_id,row_version)
                VALUES (?,?,'QR Commercial Item',250,'KZT','PUBLISHED',?,0)
                """,tenant,offer,item);
        VerifiedActor seller=verified(sellerId);
        String ref=qr.issueOrGet(seller,tenant,offer,0,
                "QCO_"+UUID.randomUUID().toString().replace("-","")).publicRef();
        return new Fixture(tenant,offer,item,slug,ref,seller);
    }

    private static String key() {
        return "QRBUY_"+UUID.randomUUID().toString().replace("-","");
    }
    private static String email() {
        return "qrbuyer-"+UUID.randomUUID()+"@example.kz";
    }
    private AutoOrderService.CheckoutResult buy(Fixture f,String email,int quantity,
                                                 long version,BigDecimal price,String key) {
        return qrCheckout.checkout(f.offerRef(),email,quantity,version,price,"KZT",key);
    }
    private int orders(Fixture f) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order WHERE tenant_id=?
                """,Integer.class,f.tenant());
    }

    @Test void qr03AcceptedCurrentPriceCreatesOnePlacedOrderWithEmailIdentity() {
        var f=setup();
        var placed=buy(f,email(),3,0,new BigDecimal("250.0000"),key());
        UUID id=placed.order().orderId();
        assertThat(placed.order().status()).isEqualTo("PLACED");
        assertThat(orders(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT total_amount FROM crm.sales_order
                WHERE tenant_id=? AND id=?
                """,BigDecimal.class,f.tenant(),id)).isEqualByComparingTo("750");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order
                WHERE tenant_id=? AND id=? AND buyer_user_id IS NULL
                  AND pending_email_identity_id IS NOT NULL
                """,Integer.class,f.tenant(),id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=? AND offer_id=?
                  AND quantity=3 AND unit_price_snapshot=250
                  AND catalog_item_id_snapshot=?
                """,Integer.class,f.tenant(),id,f.offer(),f.item())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=? AND event_type='PLACED'
                """,Integer.class,f.tenant(),id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation WHERE tenant_id=?
                """,Integer.class,f.tenant())).isZero();
    }

    @Test void qr03SellerPriceChangeRejectsStaleAcceptanceBeforeBuyerProvisioning() {
        var f=setup();String buyer=email(),requestKey=key();
        var updated=sellerOffers.updatePrice(f.seller(),f.tenant(),f.offer(),
                "QR Commercial Item",new BigDecimal("310"),"KZT",0L);
        assertThat(updated.rowVersion()).isEqualTo(1);
        assertThatThrownBy(()->buy(f,buyer,2,0,new BigDecimal("250"),requestKey))
                .hasMessage("PRICE_RECONFIRM_REQUIRED");
        assertThat(orders(f)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=?
                """,Integer.class,f.tenant())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity WHERE lookup_hmac=?
                """,Integer.class,crypto.lookup(buyer))).isZero();
        var accepted=buy(f,buyer,2,1,new BigDecimal("310"),requestKey);
        assertThat(accepted.order().status()).isEqualTo("PLACED");
        assertThat(orders(f)).isEqualTo(1);
    }

    @Test void qr03RevokeBlocksNewCheckoutYetExactCommittedReplayWorks() {
        var f=setup();String buyer=email(),requestKey=key();
        var first=buy(f,buyer,1,0,new BigDecimal("250"),requestKey);
        qr.revoke(f.seller(),f.tenant(),f.offer(),0,
                "QCO_"+UUID.randomUUID().toString().replace("-",""));
        assertThat(buy(f,buyer,1,0,new BigDecimal("250"),requestKey).order().orderId())
                .isEqualTo(first.order().orderId());
        assertThatThrownBy(()->buy(f,buyer,1,0,new BigDecimal("250"),key()))
                .hasMessage("QR_UNAVAILABLE");
        assertThat(orders(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=?
                """,Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void qr03CheckoutSharesSellerReceiptNamespaceWithNonQrMode() {
        var f=setup();String buyer=email(),requestKey=key();
        var first=auto.checkout(f.slug(),buyer,requestKey,
                List.of(new AutoOrderService.Line(f.offer(),1)));
        assertThatThrownBy(()->buy(f,buyer,1,0,new BigDecimal("250"),requestKey))
                .hasMessageContaining("Idempotency key reused");
        assertThat(orders(f)).isEqualTo(1);
        var another=setup();String secondBuyer=email(),secondKey=key();
        var second=buy(another,secondBuyer,1,0,new BigDecimal("250"),secondKey);
        assertThatThrownBy(()->auto.checkout(another.slug(),secondBuyer,secondKey,
                List.of(new AutoOrderService.Line(another.offer(),1))))
                .hasMessageContaining("Idempotency key reused");
        assertThat(orders(another)).isEqualTo(1);
        assertThat(first.order().orderId()).isNotEqualTo(second.order().orderId());
    }

    @Test void qr03WrongQuantityOrCurrencyOrUnpublishedOfferCannotCreateOrder() {
        var f=setup();String buyer=email();
        assertThatThrownBy(()->buy(f,buyer,0,0,new BigDecimal("250"),key()))
                .hasMessage("QTY_INVALID");
        assertThatThrownBy(()->qrCheckout.checkout(f.offerRef(),buyer,1,0,
                new BigDecimal("250"),"USD",key()))
                .hasMessage("PRICE_RECONFIRM_REQUIRED");
        jdbc.update("""
                UPDATE crm.store_offer SET state='HIDDEN',row_version=row_version+1
                WHERE tenant_id=? AND id=?
                """,f.tenant(),f.offer());
        assertThatThrownBy(()->buy(f,buyer,1,0,new BigDecimal("250"),key()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(orders(f)).isZero();
    }

    @Test void qr03ConcurrentSameKeyHasOneOrderInRealPostgres() throws Exception {
        try(var c=dataSource.getConnection()) {
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=setup();String buyer=email(),requestKey=key();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return buy(f,buyer,2,0,new BigDecimal("250"),requestKey);
            });
            var two=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return buy(f,buyer,2,0,new BigDecimal("250"),requestKey);
            });
            start.countDown();
            assertThat(one.get(30,TimeUnit.SECONDS).order().orderId())
                    .isEqualTo(two.get(30,TimeUnit.SECONDS).order().orderId());
        }
        assertThat(orders(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=?
                """,Integer.class,f.tenant())).isEqualTo(1);
    }
}
