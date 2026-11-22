package kz.rm.autoorder;

import kz.rm.iam.*;
import kz.rm.storefront.PublishedOfferQrService;
import kz.rm.storefront.StorefrontOfferService;
import kz.rm.sales.SalesOrderQrReferenceService;
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
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class PublishedQrCheckoutQualificationTest {
    @Autowired PublishedQrCheckoutService qrCheckout;
    @Autowired SalesOrderQrReferenceService orderQr;
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
        roles.grant(admin,tenant,role.id(),DomainPermission.SALES_ORDER_READ);
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

    // QR-03B: durable order reference, but no buyer read without a verified
    // IAM email identity AND the SALES buyer_user_id association.
    private record EmailProof(UUID challengeId,String token) {}
    private EmailProof proof(String rawEmail) {
        String normalized=crypto.normalize(rawEmail);
        return jdbc.query("""
                SELECT c.id,c.token_envelope FROM public.iam_email_verification_challenge c
                JOIN public.iam_email_identity e ON e.id=c.email_identity_id
                WHERE e.lookup_hmac=? ORDER BY c.created_at DESC,c.id DESC
                """,rs->{
                    if(!rs.next())throw new IllegalStateException("No IAM email challenge");
                    return new EmailProof(rs.getObject(1,UUID.class),
                            crypto.decrypt(rs.getBytes(2)));
                },crypto.lookup(normalized));
    }
    private VerifiedActor verifyBuyer(String email) {
        EmailProof token=proof(email);
        UUID user=auto.verifyEmail(token.challengeId(),token.token());
        return verified(user);
    }
    private String storedOrderRef(Fixture f,UUID order) {
        return jdbc.queryForObject("""
                SELECT public_ref FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,String.class,f.tenant(),order);
    }

    @Test void qr03bPlacedOrderCreatesLocatorButEmailOnlyBuyerCannotRead() {
        var f=setup();
        String buyerEmail=email();
        var created=buy(f,buyerEmail,3,0,new BigDecimal("250"),key());
        UUID orderId=created.order().orderId();
        String ref=storedOrderRef(f,orderId);
        assertThat(ref).hasSize(32);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,Integer.class,f.tenant(),orderId)).isEqualTo(1);
        // Verified OIDC seller without an email proof is NOT this buyer.
        assertThatThrownBy(()->orderQr.readOwn(f.seller(),ref))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->orderQr.ownReference(f.seller(),f.tenant(),orderId))
                .isInstanceOf(SecurityException.class);
        VerifiedActor buyer=verifyBuyer(buyerEmail);
        var own=orderQr.ownReference(buyer,f.tenant(),orderId);
        assertThat(own.publicRef()).isEqualTo(ref);
        assertThat(orderQr.ownReference(buyer,f.tenant(),orderId)).isEqualTo(own);
        var detail=orderQr.readOwn(buyer,ref);
        assertThat(detail.orderId()).isEqualTo(orderId);
        assertThat(detail.status()).isEqualTo("PLACED");
        assertThat(detail.totalAmount()).isEqualByComparingTo("750.0000");
        assertThat(detail.currency()).isEqualTo("KZT");
        assertThat(detail.lines()).hasSize(1);
        assertThat(detail.lines().getFirst().quantity()).isEqualTo(3);
        assertThat(detail.lines().getFirst().unitPrice()).isEqualByComparingTo("250");
        assertThat(detail.events()).extracting(
                SalesOrderQrReferenceService.StatusEvent::eventType)
                .containsExactly("PLACED");
        assertThat(detail.toString()).doesNotContain(buyerEmail, "pending_email_identity_id");
    }

    @Test void qr03bCrossBuyerAndCrossTenantStaffCannotReadOrReprint() {
        var a=setup();
        var b=setup();
        String oneEmail=email(),twoEmail=email();
        var aOrder=buy(a,oneEmail,1,0,new BigDecimal("250"),key());
        buy(b,twoEmail,1,0,new BigDecimal("250"),key());
        String ref=storedOrderRef(a,aOrder.order().orderId());
        VerifiedActor owner=verifyBuyer(oneEmail);
        VerifiedActor stranger=verifyBuyer(twoEmail);
        assertThat(orderQr.readOwn(owner,ref).orderId())
                .isEqualTo(aOrder.order().orderId());
        assertThatThrownBy(()->orderQr.readOwn(stranger,ref))
                .isInstanceOf(SecurityException.class).hasMessage("ORDER_NOT_ACCESSIBLE");
        assertThatThrownBy(()->orderQr.ownReference(stranger,a.tenant(),
                aOrder.order().orderId()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->orderQr.readSeller(b.seller(),a.tenant(),ref))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->orderQr.readSeller(b.seller(),b.tenant(),ref))
                .isInstanceOf(SecurityException.class).hasMessage("ORDER_NOT_ACCESSIBLE");
        assertThat(orderQr.readSeller(a.seller(),a.tenant(),ref).orderId())
                .isEqualTo(aOrder.order().orderId());
        assertThat(orderQr.sellerReprint(a.seller(),a.tenant(),
                aOrder.order().orderId()).publicRef()).isEqualTo(ref);
    }

    @Test void qr03bRotationInvalidatesOldRefButDoesNotTouchOrderOrStock() {
        var f=setup();
        String buyerEmail=email();
        var placed=buy(f,buyerEmail,2,0,new BigDecimal("250"),key());
        VerifiedActor buyer=verifyBuyer(buyerEmail);
        UUID id=placed.order().orderId();
        var initial=orderQr.ownReference(buyer,f.tenant(),id);
        var rotated=orderQr.rotateOwned(buyer,f.tenant(),id,initial.rowVersion());
        assertThat(rotated.publicRef()).isNotEqualTo(initial.publicRef());
        assertThat(rotated.generation()).isEqualTo(2);
        assertThatThrownBy(()->orderQr.readOwn(buyer,initial.publicRef()))
                .isInstanceOf(SecurityException.class);
        assertThat(orderQr.readOwn(buyer,rotated.publicRef()).status())
                .isEqualTo("PLACED");
        assertThatThrownBy(()->orderQr.rotateOwned(buyer,f.tenant(),id,
                initial.rowVersion()))
                .hasMessage("ORDER_QR_VERSION_CONFLICT");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=? AND state='ACTIVE'
                """,Integer.class,f.tenant(),id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.audit_event
                WHERE tenant_id=? AND entity_id=? AND action_code='ORDER_QR_ROTATED'
                """,Integer.class,f.tenant(),id)).isEqualTo(1);
        assertThat(orders(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_reservation WHERE tenant_id=?
                """,Integer.class,f.tenant())).isZero();
    }

    @Test void qr03bLegacyOrderGetsOneReferenceOnlyAfterBuyerProof() {
        var f=setup();
        String buyerEmail=email();
        var placed=buy(f,buyerEmail,1,0,new BigDecimal("250"),key());
        UUID id=placed.order().orderId();
        // Simulate the pre-migration legacy row: no order QR existed then.
        jdbc.update("""
                DELETE FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=?
                """,f.tenant(),id);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,f.tenant(),id)).isZero();
        assertThatThrownBy(()->orderQr.ownReference(f.seller(),f.tenant(),id))
                .isInstanceOf(SecurityException.class);
        VerifiedActor buyer=verifyBuyer(buyerEmail);
        var generated=orderQr.ownReference(buyer,f.tenant(),id);
        assertThat(generated.generation()).isEqualTo(1);
        assertThat(orderQr.ownReference(buyer,f.tenant(),id)).isEqualTo(generated);
        assertThat(storedOrderRef(f,id)).isEqualTo(generated.publicRef());
    }

    @Test void qr03bOrderTimelineReadsHistoricalSnapshotNotCurrentOfferPrice() {
        var f=setup();
        String buyerEmail=email();
        var placed=buy(f,buyerEmail,4,0,new BigDecimal("250"),key());
        VerifiedActor buyer=verifyBuyer(buyerEmail);
        String ref=storedOrderRef(f,placed.order().orderId());
        sellerOffers.updatePrice(f.seller(),f.tenant(),f.offer(),
                "New Price",new BigDecimal("430"),"KZT",0L);
        // Synthetic progression validates the readonly timeline projection;
        // it is NOT a claim that seller confirmation/stock were tested here.
        jdbc.update("""
                UPDATE crm.sales_order SET status='CONFIRMED',row_version=row_version+1
                WHERE tenant_id=? AND id=?
                """,f.tenant(),placed.order().orderId());
        jdbc.update("""
                INSERT INTO crm.sales_order_status_event
                 (tenant_id,id,order_id,event_type,occurred_at)
                VALUES (?,?,?,'CONFIRMED',CURRENT_TIMESTAMP)
                """,f.tenant(),UUID.randomUUID(),placed.order().orderId());
        var read=orderQr.readOwn(buyer,ref);
        assertThat(read.status()).isEqualTo("CONFIRMED");
        assertThat(read.events()).extracting(
                SalesOrderQrReferenceService.StatusEvent::eventType)
                .containsExactly("PLACED","CONFIRMED");
        assertThat(read.totalAmount()).isEqualByComparingTo("1000");
        assertThat(read.lines().getFirst().unitPrice()).isEqualByComparingTo("250");
    }

    @Test void qr03bSuspendedBuyerDeniedEvenWithValidRefAndOldOidcActor() {
        var f=setup();
        String buyerEmail=email();
        var placed=buy(f,buyerEmail,1,0,new BigDecimal("250"),key());
        VerifiedActor buyer=verifyBuyer(buyerEmail);
        String ref=storedOrderRef(f,placed.order().orderId());
        jdbc.update("""
                UPDATE public.iam_user_account SET status='SUSPENDED'
                WHERE id=?
                """,buyer.userId());
        assertThatThrownBy(()->orderQr.readOwn(buyer,ref))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->orderQr.ownReference(buyer,f.tenant(),
                placed.order().orderId()))
                .isInstanceOf(SecurityException.class);
    }

    @Test void qr03bConcurrentLegacyIssuanceHasOneRefInPostgres() throws Exception {
        try(var c=dataSource.getConnection()){
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var f=setup();
        String buyerEmail=email();
        var placed=buy(f,buyerEmail,1,0,new BigDecimal("250"),key());
        UUID id=placed.order().orderId();
        VerifiedActor buyer=verifyBuyer(buyerEmail);
        jdbc.update("""
                DELETE FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=?
                """,f.tenant(),id);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var first=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return orderQr.ownReference(buyer,f.tenant(),id);
            });
            var second=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return orderQr.ownReference(buyer,f.tenant(),id);
            });
            start.countDown();
            assertThat(first.get(30,TimeUnit.SECONDS).publicRef())
                    .isEqualTo(second.get(30,TimeUnit.SECONDS).publicRef());
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_qr_reference
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,f.tenant(),id)).isEqualTo(1);
    }

    @Test void qr03bRegularNonQrCheckoutAlsoCreatesStableOrderReference() {
        var f=setup();
        String buyerEmail=email();
        var placed=auto.checkout(f.slug(),buyerEmail,key(),
                List.of(new AutoOrderService.Line(f.offer(),1)));
        UUID id=placed.order().orderId();
        String ref=storedOrderRef(f,id);
        VerifiedActor buyer=verifyBuyer(buyerEmail);
        assertThat(orderQr.readOwn(buyer,ref).orderId()).isEqualTo(id);
        assertThat(orderQr.ownReference(buyer,f.tenant(),id).publicRef())
                .isEqualTo(ref);
    }

}
