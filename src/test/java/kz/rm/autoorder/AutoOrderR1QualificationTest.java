package kz.rm.autoorder;

import kz.rm.iam.EmailIdentityCrypto;

import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
        "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class AutoOrderR1QualificationTest {
    @Autowired AutoOrderService orders;
    @Autowired AuthenticatedBuyerOrders securedBuyerOrders;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource source;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository orgs;
    @Autowired WorkspaceRepository workspaces;

    private record Store(UUID tenantId,UUID offerId,String slug,UUID itemId){}
    private Store store() {
        var owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "store-owner-"+UUID.randomUUID(),"Seller"));
        var org=orgs.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),"Shop LLP","Shop","KZ"));
        var tenant=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        String slug="shop-"+UUID.randomUUID();
        UUID offerId=UUID.randomUUID(),itemId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,tenant.getId(),itemId,"autoorder-"+UUID.randomUUID());
        jdbc.update("""
            INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
            VALUES (?,?,?,'PUBLISHED',?)
            """,tenant.getId(),UUID.randomUUID(),slug,Timestamp.from(Instant.now()));
        jdbc.update("""
            INSERT INTO crm.store_offer(tenant_id,id,title,unit_price,currency,state,catalog_item_id)
            VALUES (?,?,?,?,'KZT','PUBLISHED',?)
            """,tenant.getId(),offerId,"Notebook",new BigDecimal("125.2500"),itemId);
        return new Store(tenant.getId(),offerId,slug,itemId);
    }

    private record Challenge(UUID id, String token) {}
    private Challenge challenge(String email) {
        return jdbc.query("""
            SELECT c.id,c.token_envelope FROM iam_email_verification_challenge c
            JOIN iam_email_identity e ON c.email_identity_id=e.id
            WHERE e.lookup_hmac=? ORDER BY c.created_at DESC,c.id DESC
            """,rs->{
                if(!rs.next()) throw new IllegalStateException("No queued challenge");
                return new Challenge(rs.getObject(1,UUID.class),crypto.decrypt(rs.getBytes(2)));
            },crypto.lookup(crypto.normalize(email)));
    }
    @Test void emailOnlyCheckoutCreatesPendingUserAndNoCrmProfile() {
        var store=store();
        var placed=orders.checkout(store.slug()," BUYER@Example.KZ ","key-new-001",
                List.of(new AutoOrderService.Line(store.offerId(),2)));
        UUID orderId=placed.order().orderId();
        assertThat(placed.order().status()).isEqualTo("PLACED");
        assertThat(challenge("buyer@example.kz")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM iam_email_delivery_outbox WHERE state='PENDING'",
                Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("""
            SELECT total_amount FROM crm.sales_order WHERE tenant_id=? AND id=?
            """,BigDecimal.class,store.tenantId(),orderId)).isEqualByComparingTo("250.5000");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.customer WHERE tenant_id=?
            """,Integer.class,store.tenantId())).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.contact WHERE tenant_id=?
            """,Integer.class,store.tenantId())).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_email_identity
            """,Integer.class)).isPositive();
        var emailCipher=jdbc.queryForObject("""
            SELECT email_envelope FROM iam_email_identity WHERE lookup_hmac=?
            """,byte[].class,crypto.lookup("buyer@example.kz"));
        assertThat(new String(emailCipher,java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("buyer@example.kz");
        assertThat(jdbc.queryForObject("""
            SELECT buyer_user_id FROM crm.sales_order WHERE tenant_id=? AND id=?
            """,UUID.class,store.tenantId(),orderId)).isNull();
        assertThatThrownBy(()->orders.buyerOrders(UUID.randomUUID())).isInstanceOf(SecurityException.class);
    }

    @Test void duplicateOfferLinesCannotBypassAggregatedQuantityLimit() {
        var st=store();
        String email="aggregate-"+UUID.randomUUID()+"@example.kz";
        assertThatThrownBy(()->orders.checkout(st.slug(),email,"aggregate-key-001",
                List.of(new AutoOrderService.Line(st.offerId(),600),
                        new AutoOrderService.Line(st.offerId(),600))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Aggregated offer quantity");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=?",
                Integer.class,st.tenantId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=?",
                Integer.class,st.tenantId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM iam_email_identity WHERE lookup_hmac=?",
                Integer.class,crypto.lookup(email))).isZero();
    }

    @Test void retryIsIdempotentAndChangesAreRejected() {
        var st=store();
        String email="retry-"+UUID.randomUUID()+"@example.kz";
        var a=orders.checkout(st.slug(),email,"key-retry-001",
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        var b=orders.checkout(st.slug(),email,"key-retry-001",
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        assertThat(b.order().orderId()).isEqualTo(a.order().orderId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_checkout_receipt WHERE tenant_id=?",
                Integer.class,st.tenantId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_order WHERE tenant_id=?
            """,Integer.class,st.tenantId())).isEqualTo(1);
        assertThatThrownBy(()->orders.checkout(st.slug(),email,"key-retry-001",
                List.of(new AutoOrderService.Line(st.offerId(),2)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void verificationGrantsAccessOnlyOnProofAndDoesNotRepeatAccount() {
        var st=store();
        String email="v-"+UUID.randomUUID()+"@example.kz";
        var a=orders.checkout(st.slug(),email,"key-verify-001",
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        var account=orders.verifyEmail(challenge(email).id(),challenge(email).token());
        assertThat(orders.buyerOrders(account)).extracting(AutoOrderService.OrderReceipt::orderId)
                .containsExactly(a.order().orderId());
        assertThatThrownBy(()->orders.verifyEmail(challenge(email).id(),
                challenge(email).token())).isInstanceOf(SecurityException.class);
        var b=orders.checkout(st.slug(),email,"key-verify-002",
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        assertThat(challenge(email)).isNotNull();
        assertThat(jdbc.queryForObject("""
            SELECT buyer_user_id FROM crm.sales_order WHERE tenant_id=? AND id=?
            """,UUID.class,st.tenantId(),b.order().orderId())).isNull();
        var again=orders.verifyEmail(challenge(email).id(),challenge(email).token());
        assertThat(again).isEqualTo(account);
        assertThat(orders.buyerOrders(account)).hasSize(2);
    }


    @Test void existingLegacyAccountReusesIdentityButRequiresNewEmailProof() {
        var st=store();
        String email="legacy-"+UUID.randomUUID()+"@example.kz";
        UUID existingId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO iam_user_account(id,email_normalized,display_name,status,created_at,updated_at,row_version)
            VALUES (?,?,?,'ACTIVE',?,?,0)
            """,existingId,email,"Existing User",Timestamp.from(Instant.now()),Timestamp.from(Instant.now()));
        var placed=orders.checkout(st.slug(),email,"key-legacy-001",
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        assertThat(jdbc.queryForObject("""
            SELECT user_id FROM iam_email_identity WHERE lookup_hmac=?
            """,UUID.class,crypto.lookup(email))).isEqualTo(existingId);
        assertThat(jdbc.queryForObject("""
            SELECT buyer_user_id FROM crm.sales_order WHERE tenant_id=? AND id=?
            """,UUID.class,st.tenantId(),placed.order().orderId())).isNull();
        assertThatThrownBy(()->orders.buyerOrders(existingId)).isInstanceOf(SecurityException.class);
        assertThat(orders.verifyEmail(challenge(email).id(),
                challenge(email).token())).isEqualTo(existingId);
        assertThat(orders.buyerOrders(existingId)).extracting(AutoOrderService.OrderReceipt::orderId)
                .contains(placed.order().orderId());
    }

    @Test void concurrentSameCheckoutKeyCreatesOneOrderAndOneIdentity() throws Exception {
        var st=store();
        String email="race-"+UUID.randomUUID()+"@example.kz";
        var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                release.await(5,TimeUnit.SECONDS);
                return orders.checkout(st.slug(),email,"race-key-001",
                        List.of(new AutoOrderService.Line(st.offerId(),2))).order().orderId();
            });
            var second=pool.submit(()->{
                release.await(5,TimeUnit.SECONDS);
                return orders.checkout(st.slug(),email,"race-key-001",
                        List.of(new AutoOrderService.Line(st.offerId(),2))).order().orderId();
            });
            release.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS)).isEqualTo(second.get(20,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=?",
                Integer.class,st.tenantId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM iam_email_identity WHERE lookup_hmac=?",
                Integer.class,crypto.lookup(email))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM iam_email_delivery_outbox o JOIN iam_email_identity e ON o.email_identity_id=e.id WHERE e.lookup_hmac=?",
                Integer.class,crypto.lookup(email))).isEqualTo(1);
    }

    @Test void concurrentDifferentTenantsSameEmailReuseGlobalAccount() throws Exception {
        var firstStore=store();var secondStore=store();
        String email="shared-"+UUID.randomUUID()+"@example.kz";
        var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                release.await(5,TimeUnit.SECONDS);
                return orders.checkout(firstStore.slug(),email,"shared-key-001",
                        List.of(new AutoOrderService.Line(firstStore.offerId(),1))).order().orderId();
            });
            var second=pool.submit(()->{
                release.await(5,TimeUnit.SECONDS);
                return orders.checkout(secondStore.slug(),email,"shared-key-001",
                        List.of(new AutoOrderService.Line(secondStore.offerId(),1))).order().orderId();
            });
            release.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS)).isNotEqualTo(second.get(20,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM iam_email_identity WHERE lookup_hmac=?",
                Integer.class,crypto.lookup(email))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=?",
                Integer.class,firstStore.tenantId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=?",
                Integer.class,secondStore.tenantId())).isEqualTo(1);
    }

    @Test void suspendedAccountCannotVerifyEmailOrReceiveNewOrders() {
        var st=store();
        String email="suspend-"+UUID.randomUUID()+"@example.kz";
        var placed=orders.checkout(st.slug(),email,"suspend-key-001",
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        UUID userId=jdbc.queryForObject("""
            SELECT user_id FROM iam_email_identity WHERE lookup_hmac=?
            """,UUID.class,crypto.lookup(email));
        jdbc.update("UPDATE iam_user_account SET status='SUSPENDED' WHERE id=?",userId);
        var token=challenge(email);
        assertThatThrownBy(()->orders.verifyEmail(token.id(),token.token()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT buyer_user_id FROM crm.sales_order WHERE tenant_id=? AND id=?
            """,UUID.class,st.tenantId(),placed.order().orderId())).isNull();
        assertThatThrownBy(()->orders.checkout(st.slug(),email,"suspend-key-002",
                List.of(new AutoOrderService.Line(st.offerId(),1))))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=?",
                Integer.class,st.tenantId())).isEqualTo(1);
    }

    @Test void badEmailAndForgedOfferCannotCreateOrder() {
        var st=store();
        assertThatThrownBy(()->orders.checkout(st.slug(),"no-email","key-invalid-001",
                List.of(new AutoOrderService.Line(st.offerId(),1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->orders.checkout(st.slug(),"safe@example.kz","key-invalid-002",
                List.of(new AutoOrderService.Line(UUID.randomUUID(),1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=?",
                Integer.class,st.tenantId())).isZero();
    }

    @Test void tcrud04BuyerOrdersCannotBeReadBySupplyingAnotherUsersUuid() {
        var st=store();
        String aEmail="private-buyer-a-"+UUID.randomUUID()+"@example.kz";
        String bEmail="private-buyer-b-"+UUID.randomUUID()+"@example.kz";
        var a=orders.checkout(st.slug(),aEmail,"buyer-a-"+UUID.randomUUID().toString().replace("-",""),
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        var b=orders.checkout(st.slug(),bEmail,"buyer-b-"+UUID.randomUUID().toString().replace("-",""),
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        UUID buyerA=orders.verifyEmail(challenge(aEmail).id(),challenge(aEmail).token());
        UUID buyerB=orders.verifyEmail(challenge(bEmail).id(),challenge(bEmail).token());
        // Email verification is NOT equivalent to OIDC login: provision synthetic links for
        // this test alone, then resolve both actors through the actual OidcActorResolver.
        String subjectA="tcrud04-oidc-a-"+UUID.randomUUID();
        String subjectB="tcrud04-oidc-b-"+UUID.randomUUID();
        jdbc.update("UPDATE public.iam_user_account SET status='ACTIVE',external_subject=? WHERE id=?",
                subjectA,buyerA);
        jdbc.update("UPDATE public.iam_user_account SET status='ACTIVE',external_subject=? WHERE id=?",
                subjectB,buyerB);
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                    .header("alg","RS256").subject(token.equals("buyer-a")?subjectA:subjectB)
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        var resolver=new OidcActorResolver(decoder,users);
        var actorA=resolver.resolve("buyer-a");
        var actorB=resolver.resolve("buyer-b");
        assertThat(securedBuyerOrders.mine(actorA))
                .extracting(AutoOrderService.OrderReceipt::orderId)
                .containsExactly(a.order().orderId()).doesNotContain(b.order().orderId());
        assertThat(securedBuyerOrders.mine(actorB))
                .extracting(AutoOrderService.OrderReceipt::orderId)
                .containsExactly(b.order().orderId()).doesNotContain(a.order().orderId());
        assertThatThrownBy(()->securedBuyerOrders.mine(null))
                .isInstanceOf(SecurityException.class);
    }


    @Test void sale08CheckoutCapturesImmutableSkuAndAcceptedCommercialPrice() {
        Store store=store();
        String email="snapshot-"+UUID.randomUUID()+"@example.kz";
        var created=orders.checkout(store.slug(),email,"sale08-capture-001",
                List.of(new AutoOrderService.Line(store.offerId(),2)));
        UUID orderId=created.order().orderId();
        assertThat(jdbc.queryForObject("""
                SELECT catalog_item_id_snapshot FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,UUID.class,store.tenantId(),orderId)).isEqualTo(store.itemId());
        jdbc.update("""
                UPDATE crm.store_offer
                SET title='New title',unit_price=999
                WHERE tenant_id=? AND id=?
                """,store.tenantId(),store.offerId());
        var accepted=jdbc.queryForMap("""
                SELECT title_snapshot,unit_price_snapshot,quantity,currency
                FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
                """,store.tenantId(),orderId);
        assertThat(accepted.get("title_snapshot")).isEqualTo("Notebook");
        assertThat((BigDecimal)accepted.get("unit_price_snapshot"))
                .isEqualByComparingTo("125.2500");
        assertThat(accepted.get("currency")).isEqualTo("KZT");
        assertThat(orders.checkout(store.slug(),email,"sale08-capture-001",
                List.of(new AutoOrderService.Line(store.offerId(),2)))
                .order().orderId()).isEqualTo(orderId);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                """,Integer.class,store.tenantId(),orderId)).isEqualTo(1);
    }

    @Test void sale08RejectsUnmappedAndArchivedOffersBeforeEmailOrOrderSideEffects() {
        Store store=store();
        String email="unmapped-"+UUID.randomUUID()+"@example.kz";
        jdbc.update("""
                UPDATE crm.store_offer SET catalog_item_id=NULL WHERE tenant_id=? AND id=?
                """,store.tenantId(),store.offerId());
        assertThatThrownBy(()->orders.checkout(store.slug(),email,"sale08-unmapped-001",
                List.of(new AutoOrderService.Line(store.offerId(),1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Offer unavailable");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order WHERE tenant_id=?
                """,Integer.class,store.tenantId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity WHERE lookup_hmac=?
                """,Integer.class,crypto.lookup(email))).isZero();

        jdbc.update("""
                UPDATE crm.store_offer SET catalog_item_id=? WHERE tenant_id=? AND id=?
                """,store.itemId(),store.tenantId(),store.offerId());
        jdbc.update("""
                UPDATE crm.cat_item SET state='ARCHIVED'
                WHERE tenant_id=? AND id=?
                """,store.tenantId(),store.itemId());
        assertThatThrownBy(()->orders.checkout(store.slug(),email,"sale08-archived-001",
                List.of(new AutoOrderService.Line(store.offerId(),1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order WHERE tenant_id=?
                """,Integer.class,store.tenantId())).isZero();
    }

    @Test void sale08ServiceSkuCannotEnterPhysicalStockCheckout() {
        Store store=store();
        jdbc.update("""
                UPDATE crm.cat_item SET kind='SERVICE' WHERE tenant_id=? AND id=?
                """,store.tenantId(),store.itemId());
        assertThatThrownBy(()->orders.checkout(store.slug(),
                "service-"+UUID.randomUUID()+"@example.kz","sale08-service-001",
                List.of(new AutoOrderService.Line(store.offerId(),1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("published PRODUCT");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order WHERE tenant_id=?
                """,Integer.class,store.tenantId())).isZero();
    }

    @Test void sale08CrossTenantSkuMappingIsRejectedByCompositeForeignKey() {
        Store own=store(),foreign=store();
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.store_offer SET catalog_item_id=?
                WHERE tenant_id=? AND id=?
                """,foreign.itemId(),own.tenantId(),own.offerId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT catalog_item_id FROM crm.store_offer WHERE tenant_id=? AND id=?
                """,UUID.class,own.tenantId(),own.offerId())).isEqualTo(own.itemId());
    }

    @Test void sale08PostgresConcurrentOfferPauseFencesCheckoutBeforeAnyIdentityCreation()
            throws Exception {
        try(var con=source.getConnection()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    con.getMetaData().getDatabaseProductName().toLowerCase(
                            java.util.Locale.ROOT).contains("postgresql"));
        }
        Store st=store();
        String email="pause-race-"+UUID.randomUUID()+"@example.kz";
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var pause=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(
                    transactionManager).execute(tx->{
                jdbc.queryForObject("""
                        SELECT catalog_item_id FROM crm.store_offer
                        WHERE tenant_id=? AND id=? FOR UPDATE
                        """,UUID.class,st.tenantId(),st.offerId());
                locked.countDown();
                try {
                    if(!release.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("Offer update not released");
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return jdbc.update("""
                        UPDATE crm.store_offer SET state='HIDDEN'
                        WHERE tenant_id=? AND id=?
                        """,st.tenantId(),st.offerId());
            }));
            assertThat(locked.await(15,TimeUnit.SECONDS)).isTrue();
            var checkoutAttempt=pool.submit(()->orders.checkout(
                    st.slug(),email,"sale08-pause-race-001",
                    List.of(new AutoOrderService.Line(st.offerId(),1))));
            try {
                assertThatThrownBy(()->checkoutAttempt.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {release.countDown();}
            assertThat(pause.get(15,TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(()->checkoutAttempt.get(15,TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order WHERE tenant_id=?
                """,Integer.class,st.tenantId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity WHERE lookup_hmac=?
                """,Integer.class,crypto.lookup(email))).isZero();
    }

}
