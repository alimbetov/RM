package kz.rm.sales;

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
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Synthetic service-level identity only; real OIDC and scanner admission remain HOLD. */
@SpringBootTest
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class PackagingServiceCatalogQualificationTest {
    @Autowired PackagingServiceCatalogService catalogue;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired JdbcTemplate jdbc;

    private record Fixture(UUID tenant,VerifiedActor staff) {}

    private VerifiedActor verified(UUID user) {
        String sub=jdbc.queryForObject("""
            SELECT external_subject FROM public.iam_user_account WHERE id=?
            """,String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
            .header("alg","RS256").subject(sub).issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-qr08");
    }

    private Fixture fixture(boolean permission) {
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
            "Packaging Seller Ltd","Packaging","KZ"));
        UUID tenant=workspaces.saveAndFlush(
            WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "pack-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "pack-staff-"+UUID.randomUUID(),"Staff")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
            org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
            org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        var admin=verified(owner);
        var role=roles.createRole(admin,tenant,
            "PKG_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
            "Packaging manager");
        if(permission)roles.grant(admin,tenant,role.id(),DomainPermission.SALES_PACKAGING_MANAGE);
        roles.assign(admin,tenant,staff,role.id());
        roles.enable(admin,tenant);
        return new Fixture(tenant,verified(staff));
    }

    private PackagingServiceCatalogService.Head publish(Fixture f,long rev,String price) {
        return catalogue.publish(f.staff(),f.tenant(),"GIFT_WRAP","Gift wrapping",
            new BigDecimal(price),"KZT",new BigDecimal("0.1200"),"UAT_1",rev);
    }

    @Test void publishNewVersionPreservesOldImmutableSnapshot() {
        Fixture f=fixture(true);
        var v1=publish(f,0,"500.0000");
        var v2=publish(f,1,"800.0000");
        assertThat(v1.version().number()).isEqualTo(1);
        assertThat(v2.version().number()).isEqualTo(2);
        assertThat(catalogue.version(f.tenant(),"GIFT_WRAP",1).price())
            .isEqualByComparingTo("500.0000");
        assertThat(catalogue.current(f.staff(),f.tenant(),"GIFT_WRAP").version().price())
            .isEqualByComparingTo("800.0000");
    }

    @Test void staleVersionAndInvalidPriceDoNotAddPublishedVersion() {
        Fixture f=fixture(true);
        publish(f,0,"500");
        assertThatThrownBy(()->publish(f,0,"700"))
            .hasMessage("PACKAGING_VERSION_CONFLICT");
        assertThatThrownBy(()->publish(f,1,"10.00001"))
            .hasMessage("PACKAGING_INPUT_INVALID");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_packaging_catalog_version
            WHERE tenant_id=? AND service_code='GIFT_WRAP'
            """,Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void deactivateChangesHeadButNotPublishedPrice() {
        Fixture f=fixture(true);
        var p=publish(f,0,"500");
        var inactive=catalogue.deactivate(f.staff(),f.tenant(),"GIFT_WRAP",1,p.rowVersion());
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.version().price()).isEqualByComparingTo("500");
        assertThatThrownBy(()->catalogue.deactivate(f.staff(),f.tenant(),
            "GIFT_WRAP",1,inactive.rowVersion()))
            .hasMessage("PACKAGING_ALREADY_INACTIVE");
    }

    @Test void lackOfPermissionAndForeignTenantFailClosed() {
        Fixture allowed=fixture(true),denied=fixture(false);
        assertThatThrownBy(()->publish(denied,0,"500"))
            .isInstanceOf(SecurityException.class);
        publish(allowed,0,"500");
        assertThatThrownBy(()->catalogue.current(denied.staff(),denied.tenant(),"GIFT_WRAP"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->catalogue.current(allowed.staff(),denied.tenant(),"GIFT_WRAP"))
            .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.sales_packaging_catalog_head WHERE tenant_id=?
            """,Integer.class,denied.tenant())).isZero();
    }
}
