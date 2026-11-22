package kz.rm.crm;

import kz.rm.iam.*;
import kz.rm.security.kms.TenantKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={"RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class CrmBuyerLinkQualificationTest {
    @Autowired CrmBuyerLinkService links;
    @Autowired AuthenticatedCrmCommands customers;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired TenantKeyService keys;
    @Autowired JdbcTemplate jdbc;

    record Seller(UUID tenant, UUID owner) {}
    Seller seller() {
        var user=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"crm-link-owner-"+UUID.randomUUID(),"CRM owner"));
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Link LLP","Link","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(
                UUID.randomUUID(),org.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),user.getId(),OrganizationMembershipEntity.Role.OWNER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        keys.provision(workspace.getId());
        return new Seller(workspace.getId(),user.getId());
    }
    UUID buyer() {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "crm-link-buyer-"+UUID.randomUUID(),"Buyer")).getId();
    }
    VerifiedActor verified(UUID id) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,id);
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-only");
    }
    UUID seedSalesOrder(UUID tenant,UUID buyer) {
        jdbc.update("""
                INSERT INTO crm.store_front
                (tenant_id,id,slug,state,created_at,row_version)
                VALUES (?,?,?,'DRAFT',?,0)
                """,tenant,UUID.randomUUID(),"link-"+UUID.randomUUID(),java.sql.Timestamp.from(Instant.now()));
        UUID identity=jdbc.query("""
                SELECT id FROM public.iam_email_identity WHERE user_id=?
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,buyer);
        if(identity==null) {
            identity=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO public.iam_email_identity
                    (id,user_id,lookup_hmac,email_envelope,verified_at,created_at)
                    VALUES (?,?,?,?,?,?)
                    """,identity,buyer,UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    new byte[]{1,2,3},java.sql.Timestamp.from(Instant.now()),
                    java.sql.Timestamp.from(Instant.now()));
        }
        UUID order=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.sales_order
                (tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,row_version)
                VALUES (?,?,?,?,'PLACED',0,'KZT',?,0)
                """,tenant,order,identity,buyer,java.sql.Timestamp.from(Instant.now()));
        return order;
    }

    @Test void sellerProposalIsIdempotentAndRequiresExplicitBuyerConfirmation() {
        var s=seller(); UUID b=buyer();var owner=verified(s.owner());
        var c=customers.createCompany(owner,s.tenant(),"Unlinked customer",null);
        String key="buyer-propose-"+UUID.randomUUID();
        var proposed=links.propose(owner,s.tenant(),c.id(),b,key);
        assertThat(proposed.state()).isEqualTo(CrmBuyerLinkService.State.PENDING);
        assertThat(links.propose(owner,s.tenant(),c.id(),b,key).id()).isEqualTo(proposed.id());
        assertThat(links.orderHistory(owner,s.tenant(),c.id(),20)).isEmpty();
        assertThatThrownBy(()->links.propose(owner,s.tenant(),c.id(),buyer(),key))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->links.confirm(owner,s.tenant(),proposed.id(),0))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->links.confirm(verified(b),s.tenant(),proposed.id(),0))
                .isInstanceOf(SecurityException.class); // no buyer-owned order
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_buyer_link_event
                WHERE tenant_id=? AND link_id=?
                """,Integer.class,s.tenant(),proposed.id())).isEqualTo(1);
        assertThat(links.linkForCustomer(owner,s.tenant(),c.id()).rowVersion()).isZero();
    }

    @Test void verifiedBuyerConfirmsOrderAndSellerSeesOnlyTenantOrders() {
        var first=seller(); var second=seller(); UUID b=buyer();
        var owner=verified(first.owner());
        var c=customers.createCompany(owner,first.tenant(),"Qualified buyer",null);
        var linked=links.propose(owner,first.tenant(),c.id(),b,
                "buyer-link-"+UUID.randomUUID());
        UUID firstOrder=seedSalesOrder(first.tenant(),b);
        seedSalesOrder(second.tenant(),b);
        var confirmed=links.confirm(verified(b),first.tenant(),linked.id(),0);
        assertThat(confirmed.state()).isEqualTo(CrmBuyerLinkService.State.CONFIRMED);
        assertThat(confirmed.rowVersion()).isEqualTo(1);
        assertThat(links.confirm(verified(b),first.tenant(),linked.id(),1).id())
                .isEqualTo(linked.id()); // same confirmed version is harmless replay
        assertThatThrownBy(()->links.confirm(verified(b),first.tenant(),linked.id(),0))
                .isInstanceOf(IllegalStateException.class);
        assertThat(links.orderHistory(owner,first.tenant(),c.id(),10))
                .extracting(CrmBuyerLinkService.BuyerOrder::orderId).containsExactly(firstOrder);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_buyer_link_event
                WHERE tenant_id=? AND link_id=? AND action_code='CONFIRMED'
                """,Integer.class,first.tenant(),linked.id())).isEqualTo(1);
    }

    @Test void tenantSeparationAndOneToOneConstraintsPreventSilentIdentityMerge() {
        var a=seller();var b=seller(); UUID buyer=buyer();
        var ownerA=verified(a.owner());var ownerB=verified(b.owner());
        var ca=customers.createCompany(ownerA,a.tenant(),"Tenant A",null);
        var cb=customers.createCompany(ownerB,b.tenant(),"Tenant B",null);
        var aLink=links.propose(ownerA,a.tenant(),ca.id(),buyer,
                "link-a-"+UUID.randomUUID());
        assertThat(links.linkForCustomer(ownerB,b.tenant(),ca.id())).isNull();
        assertThatThrownBy(()->links.propose(ownerB,b.tenant(),ca.id(),buyer,
                "foreign-"+UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->links.confirm(verified(buyer),b.tenant(),aLink.id(),0))
                .isInstanceOf(SecurityException.class);
        var other=customers.createCompany(ownerA,a.tenant(),"Other same-tenant customer",null);
        assertThatThrownBy(()->links.propose(ownerA,a.tenant(),other.id(),buyer,
                "collision-"+UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(links.propose(ownerB,b.tenant(),cb.id(),buyer,
                "link-b-"+UUID.randomUUID()).state())
                .isEqualTo(CrmBuyerLinkService.State.PENDING);
        assertThat(links.orderHistory(ownerA,a.tenant(),ca.id(),10)).isEmpty();
    }

    @Test void archivedCustomerCannotBeLinkedOrExposeOrderHistory() {
        var s=seller();var owner=verified(s.owner()); UUID b=buyer();
        var c=customers.createCompany(owner,s.tenant(),"Archive boundary",null);
        var link=links.propose(owner,s.tenant(),c.id(),b,
                "archive-link-"+UUID.randomUUID());
        seedSalesOrder(s.tenant(),b);
        customers.archiveCustomer(owner,s.tenant(),c.id(),0);
        assertThatThrownBy(()->links.confirm(verified(b),s.tenant(),link.id(),0))
                .isInstanceOf(IllegalStateException.class);
        assertThat(links.orderHistory(owner,s.tenant(),c.id(),10)).isEmpty();
        assertThat(links.linkForCustomer(owner,s.tenant(),c.id()).rowVersion()).isZero();
    }
}
