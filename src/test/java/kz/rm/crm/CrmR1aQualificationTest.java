package kz.rm.crm;

import kz.rm.iam.*;
import kz.rm.security.kms.TenantKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={"RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class CrmR1aQualificationTest {
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired TenantKeyService keys;
    @Autowired CrmCustomerService crm;
    @Autowired JdbcTemplate jdbc;

    private record Tenant(UUID tenantId,UUID actorId){}
    private Tenant newTenant() {
        var user=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "subject-"+UUID.randomUUID(),"CRM Worker"));
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Demo LLP","Demo","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),
                user.getId(),OrganizationMembershipEntity.Role.OWNER));
        keys.provision(workspace.getId());
        return new Tenant(workspace.getId(),user.getId());
    }

    @Test void personAndContactSecretsStayEncryptedAndReloadAcrossInstance() {
        var tenant=newTenant();
        var customer=crm.createPerson(tenant.actorId(),tenant.tenantId(),
                "Иван Иванов","110000000");
        var contact=crm.addContact(tenant.actorId(),tenant.tenantId(),customer.id(),
                "Айгүл","aigul@example.kz","+77001234567");
        assertThat(crm.getCustomer(tenant.actorId(),tenant.tenantId(),customer.id()).name())
                .isEqualTo("Иван Иванов");
        var loaded=crm.getContact(tenant.actorId(),tenant.tenantId(),contact.id());
        assertThat(loaded.name()).isEqualTo("Айгүл");
        assertThat(loaded.email()).isEqualTo("aigul@example.kz");
        assertThat(loaded.phone()).isEqualTo("+77001234567");
        assertThat(customer.regionZoneId()).isEqualTo("11");
        assertThat(jdbc.queryForObject("""
            SELECT organization_label FROM crm.customer WHERE tenant_id=? AND id=?
            """,String.class,tenant.tenantId(),customer.id())).isNull();
        byte[] stored=jdbc.queryForObject("""
            SELECT email_envelope FROM crm_pii.contact_secret WHERE tenant_id=? AND contact_id=?
            """,byte[].class,tenant.tenantId(),contact.id());
        assertThat(new String(stored,java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("aigul@example.kz");
    }

    @Test void organizationIsolationBlocksOtherTenantsEvenWithKnownIds() {
        var one=newTenant();
        var two=newTenant();
        var customer=crm.createPerson(one.actorId(),one.tenantId(),"Private Client",null);
        var contact=crm.addContact(one.actorId(),one.tenantId(),customer.id(),
                "Secret","secret@example.kz",null);
        assertThatThrownBy(()->crm.getCustomer(two.actorId(),two.tenantId(),customer.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->crm.getContact(two.actorId(),two.tenantId(),contact.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->crm.addContact(two.actorId(),two.tenantId(),customer.id(),
                "Wrong",null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->crm.getCustomer(one.actorId(),two.tenantId(),customer.id()))
                .isInstanceOf(SecurityException.class);
    }

    @Test void consentIsOptInAndRevocationWins() {
        var tenant=newTenant();
        var customer=crm.createCompany(tenant.actorId(),tenant.tenantId(),"Client LLP",null);
        var contact=crm.addContact(tenant.actorId(),tenant.tenantId(),customer.id(),
                "Person","p@example.kz",null);
        assertThat(crm.hasEmailConsent(tenant.actorId(),tenant.tenantId(),contact.id(),"MARKETING")).isFalse();
        crm.recordEmailConsent(tenant.actorId(),tenant.tenantId(),contact.id(),true,
                "MARKETING","v1","WEB_FORM");
        assertThat(crm.hasEmailConsent(tenant.actorId(),tenant.tenantId(),contact.id(),"MARKETING")).isTrue();
        crm.recordEmailConsent(tenant.actorId(),tenant.tenantId(),contact.id(),false,
                "MARKETING","v1","UNSUBSCRIBE");
        assertThat(crm.hasEmailConsent(tenant.actorId(),tenant.tenantId(),contact.id(),"MARKETING")).isFalse();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.email_consent_event WHERE tenant_id=? AND contact_id=?
            """,Integer.class,tenant.tenantId(),contact.id())).isEqualTo(2);
    }

    @Test void unprovisionedTenantCannotPersistPersonPlaintext() {
        var user=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "subject-"+UUID.randomUUID(),"Owner"));
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),"NoKey LLP","NoKey","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),
                user.getId(),OrganizationMembershipEntity.Role.OWNER));
        assertThatThrownBy(()->crm.createPerson(user.getId(),workspace.getId(),"Must not persist",null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.customer WHERE tenant_id=?
            """,Integer.class,workspace.getId())).isZero();
    }

    @Test void katoRejectsSubdistrictForCustomer() {
        var t=newTenant();
        assertThatThrownBy(()->crm.createCompany(t.actorId(),t.tenantId(),"Client","433243300"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
