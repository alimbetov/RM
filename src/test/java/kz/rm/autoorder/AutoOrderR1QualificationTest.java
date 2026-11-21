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

    @Test void emailOnlyCheckoutCreatesPendingUserAndNoCrmProfile() {
        var store=store();
        var placed=orders.checkout(store.slug()," BUYER@Example.KZ ","key-new-001",
                List.of(new AutoOrderService.Line(store.offerId(),2)));
        UUID orderId=placed.order().orderId();
        assertThat(placed.order().status()).isEqualTo("PLACED");
        assertThat(placed.verification()).isNotNull();
        assertThat(placed.verification().email()).isEqualTo("buyer@example.kz");
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
        var a=orders.checkout(st.slug(),"retry-"+UUID.randomUUID()+"@example.kz","key-retry-001",
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        var b=orders.checkout(st.slug(),a.verification().email(),"key-retry-001",
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        assertThat(b.order().orderId()).isEqualTo(a.order().orderId());
        assertThat(b.verification()).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_order WHERE tenant_id=?
            """,Integer.class,st.tenantId())).isEqualTo(1);
        assertThatThrownBy(()->orders.checkout(st.slug(),a.verification().email(),"key-retry-001",
                List.of(new AutoOrderService.Line(st.offerId(),2)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void verificationGrantsAccessOnlyOnProofAndDoesNotRepeatAccount() {
        var st=store();
        String email="v-"+UUID.randomUUID()+"@example.kz";
        var a=orders.checkout(st.slug(),email,"key-verify-001",
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        var account=orders.verifyEmail(a.verification().challengeId(),a.verification().token());
        assertThat(orders.buyerOrders(account)).extracting(AutoOrderService.OrderReceipt::orderId)
                .containsExactly(a.order().orderId());
        assertThatThrownBy(()->orders.verifyEmail(a.verification().challengeId(),
                a.verification().token())).isInstanceOf(SecurityException.class);
        var b=orders.checkout(st.slug(),email,"key-verify-002",
                List.of(new AutoOrderService.Line(st.offerId(),1)));
        assertThat(b.verification()).isNotNull();
        assertThat(jdbc.queryForObject("""
            SELECT buyer_user_id FROM crm.sales_order WHERE tenant_id=? AND id=?
            """,UUID.class,st.tenantId(),b.order().orderId())).isNull();
        var again=orders.verifyEmail(b.verification().challengeId(),b.verification().token());
        assertThat(again).isEqualTo(account);
        assertThat(orders.buyerOrders(account)).hasSize(2);
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
