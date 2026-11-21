package kz.rm.crm;

import kz.rm.iam.*;
import kz.rm.security.kms.TenantKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.time.Instant;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired AuthenticatedCrmCommands secureCommands;
    @Autowired AuthenticatedCrmQueries secureQueries;
    @Autowired PlatformTransactionManager transactions;
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
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
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
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        assertThatThrownBy(()->crm.createPerson(user.getId(),workspace.getId(),"Must not persist",null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.customer WHERE tenant_id=?
            """,Integer.class,workspace.getId())).isZero();
    }

    @Test void suspendedActorAndWorkspaceCannotReadOrWriteCrm() {
        var t=newTenant();
        var customer=crm.createCompany(t.actorId(),t.tenantId(),"Before suspension",null);
        jdbc.update("UPDATE iam_user_account SET status='SUSPENDED' WHERE id=?",t.actorId());
        assertThatThrownBy(()->crm.getCustomer(t.actorId(),t.tenantId(),customer.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->crm.createCompany(t.actorId(),t.tenantId(),"Blocked",null))
                .isInstanceOf(SecurityException.class);
        jdbc.update("UPDATE iam_user_account SET status='ACTIVE' WHERE id=?",t.actorId());
        jdbc.update("UPDATE iam_workspace SET status='SUSPENDED' WHERE id=?",t.tenantId());
        assertThatThrownBy(()->crm.getCustomer(t.actorId(),t.tenantId(),customer.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->crm.createCompany(t.actorId(),t.tenantId(),"Blocked",null))
                .isInstanceOf(SecurityException.class);
    }

    @Test void memberAndViewerCannotWriteCrmButMayRead() {
        var t=newTenant();
        var customer=crm.createCompany(t.actorId(),t.tenantId(),"Permission check",null);
        UUID organizationId=jdbc.queryForObject("""
            SELECT organization_id FROM iam_workspace WHERE id=?
            """,UUID.class,t.tenantId());
        var member=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"crm-member-"+UUID.randomUUID(),"Member"));
        var viewer=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"crm-viewer-"+UUID.randomUUID(),"Viewer"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),organizationId,member.getId(),OrganizationMembershipEntity.Role.MEMBER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),organizationId,viewer.getId(),OrganizationMembershipEntity.Role.VIEWER));
        assertThat(crm.getCustomer(member.getId(),t.tenantId(),customer.id()).id()).isEqualTo(customer.id());
        assertThat(crm.getCustomer(viewer.getId(),t.tenantId(),customer.id()).id()).isEqualTo(customer.id());
        var personal=crm.createPerson(t.actorId(),t.tenantId(),"Restricted Person",null);
        var contact=crm.addContact(t.actorId(),t.tenantId(),customer.id(),
                "Restricted Contact","private@example.kz",null);
        assertThatThrownBy(()->crm.getCustomer(member.getId(),t.tenantId(),personal.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->crm.getContact(viewer.getId(),t.tenantId(),contact.id()))
                .isInstanceOf(SecurityException.class);
        assertThat(crm.getCustomer(t.actorId(),t.tenantId(),personal.id()).name())
                .isEqualTo("Restricted Person");
        assertThatThrownBy(()->crm.createCompany(member.getId(),t.tenantId(),"Forbidden",null))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->crm.createCompany(viewer.getId(),t.tenantId(),"Forbidden",null))
                .isInstanceOf(SecurityException.class);
        jdbc.update("""
            UPDATE iam_org_membership SET status='SUSPENDED'
            WHERE organization_id=? AND user_id=?
            """,organizationId,member.getId());
        assertThatThrownBy(()->crm.getCustomer(member.getId(),t.tenantId(),customer.id()))
                .isInstanceOf(SecurityException.class);
    }


    private VerifiedActor verified(UUID id) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,id);
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-only");
    }

    @Test void secureCrmFacadeRejectsUnverifiedActorsAndForeignTenants() {
        var a=newTenant();
        var b=newTenant();
        var trusted=verified(a.actorId());
        var company=secureCommands.createCompany(trusted,a.tenantId(),"Verified Company",null);
        assertThat(secureQueries.getCustomer(trusted,a.tenantId(),company.id()).id())
                .isEqualTo(company.id());
        var contact=secureCommands.addContact(trusted,a.tenantId(),company.id(),
                "Contact","contact@example.kz",null);
        assertThat(secureQueries.getContact(trusted,a.tenantId(),contact.id()).email())
                .isEqualTo("contact@example.kz");
        assertThatThrownBy(()->secureCommands.createCompany(null,a.tenantId(),"Blocked",null))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.getCustomer(null,a.tenantId(),company.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.getContact(trusted,b.tenantId(),contact.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureCommands.addContact(trusted,b.tenantId(),company.id(),
                "Blocked",null,null)).isInstanceOf(SecurityException.class);
    }

    @Test void crmCreateAndConsentEvidenceRollbackAsOneTransaction() {
        var t=newTenant();
        var tx=new TransactionTemplate(transactions);
        UUID customer=crm.createCompany(t.actorId(),t.tenantId(),"Existing",null).id();
        UUID contact=crm.addContact(t.actorId(),t.tenantId(),customer,
                "Consent Subject","consent@example.kz",null).id();
        assertThatThrownBy(()->tx.execute(s->{
            var created=secureCommands.createPerson(verified(t.actorId()),t.tenantId(),
                    "Should Roll Back",null);
            secureCommands.addContact(verified(t.actorId()),t.tenantId(),created.id(),
                    "Nested Secret","never@example.kz",null);
            secureCommands.recordEmailConsent(verified(t.actorId()),t.tenantId(),
                    contact,true,"MARKETING","v1","TEST");
            throw new IllegalStateException("synthetic failure before CRM commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer WHERE tenant_id=?
                """,Integer.class,t.tenantId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.contact WHERE tenant_id=?
                """,Integer.class,t.tenantId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.email_consent_event WHERE tenant_id=?
                """,Integer.class,t.tenantId())).isZero();
        assertThat(secureQueries.hasEmailConsent(verified(t.actorId()),
                t.tenantId(),contact,"MARKETING")).isFalse();
        var accepted=secureCommands.createPerson(verified(t.actorId()),t.tenantId(),
                "Accepted",null);
        assertThat(secureQueries.getCustomer(verified(t.actorId()),
                t.tenantId(),accepted.id()).name()).isEqualTo("Accepted");
    }

    @Test void invalidCrmInputNeverLeaksContactOrPersonPlaintext() {
        var t=newTenant();
        var company=crm.createCompany(t.actorId(),t.tenantId(),"Company",null);
        assertThatThrownBy(()->secureCommands.createPerson(
                verified(t.actorId()),t.tenantId(),"  ",null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->secureCommands.addContact(
                verified(t.actorId()),t.tenantId(),UUID.randomUUID(),
                "Orphan","secret@example.kz",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->secureCommands.recordEmailConsent(
                verified(t.actorId()),t.tenantId(),UUID.randomUUID(),true,
                "MARKETING","v1","FORM")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.contact WHERE tenant_id=?
                """,Integer.class,t.tenantId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm_pii.contact_secret WHERE tenant_id=?
                """,Integer.class,t.tenantId())).isZero();
        assertThat(secureQueries.getCustomer(verified(t.actorId()),t.tenantId(),
                company.id()).name()).isEqualTo("Company");
    }

    @Test void katoRejectsSubdistrictForCustomer() {
        var t=newTenant();
        assertThatThrownBy(()->crm.createCompany(t.actorId(),t.tenantId(),"Client","433243300"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
