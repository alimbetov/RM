package kz.rm.iam;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.autoorder.AuthenticatedBuyerOrders;
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
 "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
 "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class IamEmailChangeOrderHistoryQualificationTest {
    @Autowired AutoOrderService orders;
    @Autowired AuthenticatedBuyerOrders trustedBuyerOrders;
    @Autowired EmailIdentityService emails;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;

    private record Store(UUID tenantId,String slug,UUID offerId){}
    private record Proof(UUID id,String token){}
    private VerifiedActor actor(UUID id) {
        return new VerifiedActor(id,Instant.now().plusSeconds(300));
    }
    private String email(){return "history-"+UUID.randomUUID()+"@example.kz";}
    private Store store(){
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Mail Store LLP","Mail Store","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        UUID offer=UUID.randomUUID(),item=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.cat_item(tenant_id,id,sku,kind,source_locale,state,created_at)
            VALUES (?,?,?,'PRODUCT','ru','PUBLISHED',CURRENT_TIMESTAMP)
            """,workspace.getId(),item,"mail-store-"+UUID.randomUUID());
        String slug="mail-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
            VALUES (?,?,?,'PUBLISHED',?)
            """,workspace.getId(),UUID.randomUUID(),slug,Timestamp.from(Instant.now()));
        jdbc.update("""
            INSERT INTO crm.store_offer(tenant_id,id,title,unit_price,currency,state,catalog_item_id)
            VALUES (?,?,? ,?,'KZT','PUBLISHED',?)
            """,workspace.getId(),offer,"Test",new BigDecimal("15.00"),item);
        return new Store(workspace.getId(),slug,offer);
    }
    private Proof proof(String address,String purpose){
        return jdbc.query("""
            SELECT c.id,c.token_envelope
            FROM public.iam_email_verification_challenge c
            JOIN public.iam_email_identity e ON e.id=c.email_identity_id
            WHERE e.lookup_hmac=? AND c.purpose=?
            ORDER BY c.created_at DESC,c.id DESC
            """,rs->{
                if(!rs.next())throw new IllegalStateException("Missing challenge");
                return new Proof(rs.getObject(1,UUID.class),crypto.decrypt(rs.getBytes(2)));
            },crypto.lookup(crypto.normalize(address)),purpose);
    }
    @Test void historicalBuyerOrderStaysWithOriginalUserWhenOldMailboxIsReused() {
        String oldEmail=email(),nextEmail=email();
        UUID buyer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "buyer-"+UUID.randomUUID(),"Buyer")).getId();
        emails.requestInitialVerification(actor(buyer),oldEmail);
        var first=proof(oldEmail,"REGISTRATION");
        emails.verifyRegistration(first.id(),first.token());
        UUID oldIdentity=jdbc.queryForObject("""
            SELECT id FROM public.iam_email_identity WHERE current_owner_id=?
            """,UUID.class,buyer);
        Store store=store();
        UUID oldOrder=orders.checkout(store.slug(),oldEmail,"mail-order-001",
             List.of(new AutoOrderService.Line(store.offerId(),1))).order().orderId();
        var checkoutProof=proof(oldEmail,"CHECKOUT");
        orders.verifyEmail(checkoutProof.id(),checkoutProof.token());
        assertThat(jdbc.queryForObject("""
            SELECT buyer_user_id FROM crm.sales_order WHERE id=?
            """,UUID.class,oldOrder)).isEqualTo(buyer);

        emails.requestEmailChange(actor(buyer),nextEmail);
        var change=proof(nextEmail,"CHANGE_EMAIL");
        emails.confirmEmailChange(actor(buyer),change.id(),change.token());
        assertThat(jdbc.queryForObject("""
            SELECT pending_email_identity_id FROM crm.sales_order WHERE id=?
            """,UUID.class,oldOrder)).isEqualTo(oldIdentity);

        UUID freshOrder=orders.checkout(store.slug(),oldEmail,"mail-order-002",
            List.of(new AutoOrderService.Line(store.offerId(),1))).order().orderId();
        var newCheckoutProof=proof(oldEmail,"CHECKOUT");
        UUID newBuyer=orders.verifyEmail(newCheckoutProof.id(),newCheckoutProof.token());
        assertThat(newBuyer).isNotEqualTo(buyer);
        assertThat(jdbc.queryForObject("""
            SELECT pending_email_identity_id FROM crm.sales_order WHERE id=?
            """,UUID.class,freshOrder)).isNotEqualTo(oldIdentity);
        assertThat(jdbc.queryForObject("""
            SELECT buyer_user_id FROM crm.sales_order WHERE id=?
            """,UUID.class,oldOrder)).isEqualTo(buyer);
        assertThat(jdbc.queryForObject("""
            SELECT buyer_user_id FROM crm.sales_order WHERE id=?
            """,UUID.class,freshOrder)).isEqualTo(newBuyer);
        assertThat(trustedBuyerOrders.mine(actor(buyer))).extracting(AutoOrderService.OrderReceipt::orderId)
            .contains(oldOrder).doesNotContain(freshOrder);
        assertThat(trustedBuyerOrders.mine(actor(newBuyer))).extracting(AutoOrderService.OrderReceipt::orderId)
            .contains(freshOrder).doesNotContain(oldOrder);
    }
}
