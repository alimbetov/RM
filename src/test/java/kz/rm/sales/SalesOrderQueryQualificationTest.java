package kz.rm.sales;

import kz.rm.autoorder.AutoOrderService;
import kz.rm.iam.*;
import kz.rm.sales.dto.SalesOrderSearch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class SalesOrderQueryQualificationTest {
    @Autowired SalesOrderQueryService service;
    @Autowired AuthenticatedSalesQueries trustedQueries;
    @Autowired AutoOrderService checkout;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;

    private record Shop(UUID actor,UUID tenant,String slug,UUID offer) {}
    private Shop shop() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Sales Read LLP","Sales Read","KZ"));
        var workspace=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        var user=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"sales-read-"+UUID.randomUUID(),"Seller"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),user.getId(),OrganizationMembershipEntity.Role.OWNER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        UUID offer=UUID.randomUUID();
        String slug="read-"+UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
            VALUES (?,?,?,'PUBLISHED',CURRENT_TIMESTAMP)
            """,workspace.getId(),UUID.randomUUID(),slug);
        jdbc.update("""
            INSERT INTO crm.store_offer(tenant_id,id,title,unit_price,currency,state)
            VALUES (?,?,?,?,'KZT','PUBLISHED')
            """,workspace.getId(),offer,"Read Offer",new BigDecimal("100"));
        return new Shop(user.getId(),workspace.getId(),slug,offer);
    }
    private UUID order(Shop s) {
        return checkout.checkout(s.slug(),"search-"+UUID.randomUUID()+"@example.kz",
                "order-"+UUID.randomUUID(),List.of(new AutoOrderService.Line(s.offer(),1)))
                .order().orderId();
    }

    @Test void verifiedReadFacadeEnforcesActorTenantAndPagination() {
        Shop owned=shop(), other=shop();
        UUID ownOrder=order(owned);
        order(other);
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,owned.actor());
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                        .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120)).build();
        var trusted=new OidcActorResolver(decoder,users).resolve("test-only-token");
        assertThat(trustedQueries.search(trusted,owned.tenant(),null,0,20)
                .getContent()).extracting(item->item.id()).containsExactly(ownOrder);
        assertThatThrownBy(()->trustedQueries.search(null,owned.tenant(),null,0,20))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->trustedQueries.search(trusted,other.tenant(),null,0,20))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->trustedQueries.search(trusted,owned.tenant(),null,0,101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void postgresPaginationRemainsTenantIsolatedDuringConcurrentStatusChanges() throws Exception {
        Shop first=shop(),second=shop();
        List<UUID> own=new ArrayList<>();
        for(int i=0;i<12;i++) own.add(order(first));
        for(int i=0;i<4;i++) order(second);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(()->{
                if(!start.await(10,TimeUnit.SECONDS))
                    throw new IllegalStateException("Writer start timed out");
                for(int i=0;i<own.size();i++) {
                    jdbc.update("""
                        UPDATE crm.sales_order SET status=?,row_version=row_version+1
                        WHERE tenant_id=? AND id=?
                        """,i%2==0?"CONFIRMED":"PREPARING",first.tenant(),own.get(i));
                }
                return true;
            });
            var reader=pool.submit(()->{
                if(!start.await(10,TimeUnit.SECONDS))
                    throw new IllegalStateException("Reader start timed out");
                for(int attempt=0;attempt<8;attempt++) {
                    var encountered=new HashSet<UUID>();
                    for(int page=0;page<3;page++) {
                        var result=service.search(first.actor(),first.tenant(),null,page,5);
                        assertThat(result.getTotalElements()).isEqualTo(12);
                        assertThat(result.getContent().size()).isLessThanOrEqualTo(5);
                        for(var order:result.getContent()) {
                            assertThat(own).contains(order.id());
                            assertThat(encountered.add(order.id())).isTrue();
                        }
                    }
                    assertThat(encountered).hasSize(12);
                }
                return true;
            });
            start.countDown();
            assertThat(writer.get(35,TimeUnit.SECONDS)).isTrue();
            assertThat(reader.get(35,TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void orderListPaginatesFiltersAndNeverReturnsBuyerIdentity() {
        Shop first=shop(),second=shop();
        UUID one=order(first),two=order(first);
        order(second);
        jdbc.update("UPDATE crm.sales_order SET status='CONFIRMED' WHERE tenant_id=? AND id=?",
                first.tenant(),one);
        var page=service.search(first.actor(),first.tenant(),null,0,1);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
        var confirmed=service.search(first.actor(),first.tenant(),
                new SalesOrderSearch("CONFIRMED",null,null),0,10);
        assertThat(confirmed.getContent()).hasSize(1);
        assertThat(confirmed.getContent().getFirst().id()).isEqualTo(one);
        assertThat(service.search(first.actor(),first.tenant(),
                new SalesOrderSearch(null,Instant.parse("2010-01-01T00:00:00Z"),
                        Instant.parse("2011-01-01T00:00:00Z")),0,10)
                .getTotalElements()).isZero();
        assertThatThrownBy(()->service.search(first.actor(),second.tenant(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.search(first.actor(),first.tenant(),null,0,101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SalesOrderSearch("UNKNOWN",null,null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
