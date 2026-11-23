package kz.rm.organization;

import kz.rm.iam.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class TenantCurrencyPolicyQualificationTest {
    @Autowired TenantCurrencyPolicyService currencies;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;

    private record Fixture(UUID tenant,VerifiedActor owner,VerifiedActor viewer) {}

    private VerifiedActor verified(UUID id) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,id);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("cur-r1a-synthetic");
    }

    private Fixture fixture() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Tenant Currency KZ LLP","Currency tenant","KZ"));
        UUID tenant=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID owner=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"cur-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID viewer=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"cur-viewer-"+UUID.randomUUID(),"Viewer")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),viewer,OrganizationMembershipEntity.Role.VIEWER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        return new Fixture(tenant,verified(owner),verified(viewer));
    }

    @Test void cur01InitializeKztOnceAndPreserveFourPurposeRows() {
        Fixture f=fixture();
        assertThat(currencies.initialize(f.owner(),f.tenant()).publicPriceCurrency())
                .isEqualTo("KZT");
        assertThat(currencies.initialize(f.owner(),f.tenant()).rowVersion()).isZero();
        assertThat(currencies.get(f.owner(),f.tenant()).accountingCurrency()).isEqualTo("KZT");
        assertThat(currencies.listAllowed(f.owner(),f.tenant())).hasSize(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.tenant_currency_policy_event
                WHERE tenant_id=? AND action_code='INITIALIZED'
                """,Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void cur02TenantIsolationAndForeignViewerFailClosed() {
        Fixture a=fixture(),b=fixture();
        currencies.initialize(a.owner(),a.tenant());
        currencies.initialize(b.owner(),b.tenant());
        assertThatThrownBy(()->currencies.get(a.owner(),b.tenant()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->currencies.listAllowed(a.viewer(),a.tenant()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.tenant_allowed_currency
                WHERE tenant_id=? AND state='ENABLED'
                """,Integer.class,a.tenant())).isEqualTo(4);
    }

    @Test void cur03PublicKztAllowedButUsdRejectedWithoutBusinessEffects() {
        Fixture f=fixture();
        currencies.initialize(f.owner(),f.tenant());
        var tx=new TransactionTemplate(transactionManager);
        tx.execute(status->{
            currencies.requirePublicKzCurrency(f.tenant(),"KZT");
            return null;
        });
        assertThatThrownBy(()->tx.execute(status->{
            currencies.requirePublicKzCurrency(f.tenant(),"USD");
            return null;
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order WHERE tenant_id=?
                """,Integer.class,f.tenant())).isZero();
    }

    @Test void cur04DatabaseRefusesForeignPublicCurrencyRegardlessOfPurchaseUse() {
        Fixture f=fixture();
        currencies.initialize(f.owner(),f.tenant());
        assertThatThrownBy(()->jdbc.update("""
                INSERT INTO public.tenant_allowed_currency
                 (tenant_id,currency_code,purpose,state,created_at,updated_at)
                VALUES (?,'USD','PUBLIC_PRICE','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,f.tenant())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
                UPDATE public.tenant_currency_policy SET public_price_currency='USD'
                WHERE tenant_id=?
                """,f.tenant())).isInstanceOf(Exception.class);
        assertThat(currencies.get(f.owner(),f.tenant()).publicPriceCurrency()).isEqualTo("KZT");
    }


    @Test void cur06PurchaseCurrencyCanBeEnabledWithoutPublicUsdAdmission() {
        Fixture f=fixture();
        currencies.initialize(f.owner(),f.tenant());
        var p=currencies.setCurrencyEnabled(f.owner(),f.tenant(),"USD","PURCHASE",true,0);
        assertThat(p.rowVersion()).isEqualTo(1);
        assertThat(currencies.listAllowed(f.owner(),f.tenant()))
                .anyMatch(row->"USD".equals(row.currency()) &&
                        "PURCHASE".equals(row.purpose()) && "ENABLED".equals(row.state()));
        var tx=new TransactionTemplate(transactionManager);
        tx.execute(status->{
            currencies.requireCurrencyForPurpose(f.tenant(),"USD","PURCHASE");
            return null;
        });
        assertThatThrownBy(()->tx.execute(status->{
            currencies.requirePublicKzCurrency(f.tenant(),"USD");
            return null;
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("PUBLIC_MARKET_CURRENCY_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public.tenant_currency_policy_event
            WHERE tenant_id=? AND action_code='CURRENCY_ENABLED' AND revision_no=2
            """,Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void cur07CurrencyChangeCasIsolationAndIdempotency() {
        Fixture a=fixture(), b=fixture();
        currencies.initialize(a.owner(),a.tenant());
        currencies.initialize(b.owner(),b.tenant());
        assertThatThrownBy(()->currencies.setCurrencyEnabled(a.owner(),b.tenant(),
                "USD","PURCHASE",true,0)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->currencies.setCurrencyEnabled(a.viewer(),a.tenant(),
                "USD","PURCHASE",true,0)).isInstanceOf(SecurityException.class);
        var first=currencies.setCurrencyEnabled(a.owner(),a.tenant(),"USD","PURCHASE",true,0);
        assertThat(first.rowVersion()).isEqualTo(1);
        assertThat(currencies.setCurrencyEnabled(a.owner(),a.tenant(),"USD",
                "PURCHASE",true,1).rowVersion()).isEqualTo(1);
        assertThatThrownBy(()->currencies.setCurrencyEnabled(a.owner(),a.tenant(),
                "EUR","PURCHASE",true,0)).isInstanceOf(IllegalStateException.class)
                .hasMessage("TENANT_CURRENCY_STALE_VERSION");
        assertThatThrownBy(()->currencies.setCurrencyEnabled(a.owner(),a.tenant(),
                "USD","PUBLIC_PRICE",true,1)).isInstanceOf(IllegalStateException.class)
                .hasMessage("TENANT_CURRENCY_MANDATORY_KZT");
        assertThatThrownBy(()->currencies.setCurrencyEnabled(a.owner(),a.tenant(),
                "KZT","PUBLIC_PRICE",false,1)).isInstanceOf(IllegalStateException.class)
                .hasMessage("TENANT_CURRENCY_MANDATORY_KZT");
        assertThat(currencies.setCurrencyEnabled(a.owner(),a.tenant(),"USD",
                "PURCHASE",false,1).rowVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public.tenant_currency_policy_event WHERE tenant_id=?
            """,Integer.class,a.tenant())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public.tenant_currency_policy_event WHERE tenant_id=?
            """,Integer.class,b.tenant())).isEqualTo(1);
    }

    @Test void cur05PostgresCurrencyAuditIsAppendOnly() throws Exception {
        try(var c=dataSource.getConnection()) {
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        Fixture f=fixture();
        currencies.initialize(f.owner(),f.tenant());
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM public.tenant_currency_policy_event WHERE tenant_id=?
                """,f.tenant())).isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.tenant_currency_policy_event WHERE tenant_id=?
                """,Integer.class,f.tenant())).isEqualTo(1);
    }
}
