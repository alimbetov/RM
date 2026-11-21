package kz.rm.autoorder;

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
    @Autowired EmailIdentityCrypto crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository orgs;
    @Autowired WorkspaceRepository workspaces;

    private record Store(UUID tenantId,UUID offerId,String slug){}
    private Store store() {
        var owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "store-owner-"+UUID.randomUUID(),"Seller"));
        var org=orgs.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),"Shop LLP","Shop","KZ"));
        var tenant=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        String slug="shop-"+UUID.randomUUID();
        UUID offerId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
            VALUES (?,?,?,'PUBLISHED',?)
            """,tenant.getId(),UUID.randomUUID(),slug,Timestamp.from(Instant.now()));
        jdbc.update("""
            INSERT INTO crm.store_offer(tenant_id,id,title,unit_price,currency,state)
            VALUES (?,?,?,?,'KZT','PUBLISHED')
            """,tenant.getId(),offerId,"Notebook",new BigDecimal("125.2500"));
        return new Store(tenant.getId(),offerId,slug);
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
}
