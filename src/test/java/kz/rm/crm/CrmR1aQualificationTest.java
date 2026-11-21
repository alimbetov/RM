package kz.rm.crm;

import kz.rm.iam.*;
import kz.rm.security.kms.TenantKeyService;
import kz.rm.crm.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.time.Instant;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
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
    @Autowired TenantRoleService roles;
    @Autowired DataSource dataSource;
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


    @Test void postgresCrmPiiReadFencesConcurrentPermissionRevocation() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var t=newTenant();
        UUID person=crm.createPerson(t.actorId(),t.tenantId(),"Private Person",null).id();
        var role=roles.createRole(t.actorId(),t.tenantId(),
                "CRM_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "CRM reader");
        roles.grant(t.actorId(),t.tenantId(),role.id(),DomainPermission.CRM_READ);
        roles.grant(t.actorId(),t.tenantId(),role.id(),DomainPermission.CRM_PII_READ);

        UUID orgId=jdbc.queryForObject(
                "SELECT organization_id FROM public.iam_workspace WHERE id=?",
                UUID.class,t.tenantId());
        var staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "crm-reader-"+UUID.randomUUID(),"CRM reader"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                orgId,staff.getId(),OrganizationMembershipEntity.Role.MEMBER));
        roles.assign(t.actorId(),t.tenantId(),staff.getId(),role.id());
        roles.enable(t.actorId(),t.tenantId());
        var admitted=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var tx=new TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reading=pool.submit(()->tx.execute(s->{
                assertThat(secureQueries.getCustomer(verified(staff.getId()),
                        t.tenantId(),person).name()).isEqualTo("Private Person");
                admitted.countDown();
                try {
                    if(!release.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("Read release timeout");
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return true;
            }));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{
                roles.revoke(t.actorId(),t.tenantId(),role.id(),DomainPermission.CRM_PII_READ);
                return true;
            });
            try {
                assertThatThrownBy(()->revoke.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(reading.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThatThrownBy(()->secureQueries.getCustomer(verified(staff.getId()),
                t.tenantId(),person)).isInstanceOf(SecurityException.class);
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


    @Test void customerAndContactSearchAreBoundedTenantScopedAndPrivacyMinimized() {
        var t=newTenant(),other=newTenant();
        var person=secureCommands.createPerson(verified(t.actorId()),t.tenantId(),
                "Private Person",null);
        var company=secureCommands.createCompany(verified(t.actorId()),t.tenantId(),
                "North Logistics LLP",null);
        secureCommands.createCompany(verified(other.actorId()),other.tenantId(),
                "Foreign Logistics",null);
        var contact=secureCommands.addContact(verified(t.actorId()),t.tenantId(),
                company.id(),"Private Contact","internal@example.kz","+77001234567");
        var contactTwo=secureCommands.addContact(verified(t.actorId()),t.tenantId(),
                person.id(),"Person Contact","person@example.kz",null);
        var actor=verified(t.actorId());
        var first=secureQueries.customers(actor,t.tenantId(),null,0,1);
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(1);
        var second=secureQueries.customers(actor,t.tenantId(),null,1,1);
        assertThat(second.getContent()).hasSize(1);
        assertThat(first.getContent().getFirst().id())
                .isNotEqualTo(second.getContent().getFirst().id());
        var people=secureQueries.customers(actor,t.tenantId(),
                new CustomerSearch("PERSON","ACTIVE",null,null),0,10);
        assertThat(people.getTotalElements()).isEqualTo(1);
        assertThat(people.getContent().getFirst().companyLabel()).isNull();
        assertThat(people.getContent().getFirst().id()).isEqualTo(person.id());
        var companies=secureQueries.customers(actor,t.tenantId(),
                new CustomerSearch("COMPANY",null,null,"logistics"),0,10);
        assertThat(companies.getContent()).hasSize(1);
        assertThat(companies.getContent().getFirst().id()).isEqualTo(company.id());
        assertThat(secureQueries.customers(actor,t.tenantId(),
                new CustomerSearch(null,null,"43",null),0,10).getTotalElements()).isZero();

        var contactList=secureQueries.contacts(actor,t.tenantId(),
                new ContactSearch(company.id(),"ACTIVE",null),0,10);
        assertThat(contactList.getTotalElements()).isEqualTo(1);
        assertThat(contactList.getContent().getFirst().id()).isEqualTo(contact.id());
        assertThat(contactList.getContent().getFirst().email()).isEqualTo("internal@example.kz");
        assertThat(secureQueries.contacts(actor,t.tenantId(),
                new ContactSearch(null,null,true),0,10).getTotalElements()).isZero();
        assertThat(secureQueries.contacts(actor,t.tenantId(),null,0,1).getTotalElements()).isEqualTo(2);
        assertThat(secureQueries.contacts(actor,t.tenantId(),
                new ContactSearch(person.id(),null,null),0,10)
                .getContent().getFirst().id()).isEqualTo(contactTwo.id());

        assertThatThrownBy(()->secureQueries.customers(null,t.tenantId(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.contacts(null,t.tenantId(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.customers(actor,other.tenantId(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.contacts(actor,other.tenantId(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.contacts(actor,t.tenantId(),null,0,101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->secureQueries.customers(actor,t.tenantId(),null,-1,10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new CustomerSearch("UNKNOWN",null,null,null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ContactSearch(null,"UNKNOWN",null))
                .isInstanceOf(IllegalArgumentException.class);

        UUID organizationId=jdbc.queryForObject("""
                SELECT organization_id FROM public.iam_workspace WHERE id=?
                """,UUID.class,t.tenantId());
        var viewer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "crm-search-viewer-"+UUID.randomUUID(),"Viewer"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                organizationId,viewer.getId(),OrganizationMembershipEntity.Role.VIEWER));
        var viewerActor=verified(viewer.getId());
        assertThat(secureQueries.customers(viewerActor,t.tenantId(),null,0,10).getTotalElements())
                .isEqualTo(2);
        assertThatThrownBy(()->secureQueries.contacts(viewerActor,t.tenantId(),null,0,10))
                .isInstanceOf(SecurityException.class);
    }

    @Test void consentRequestKeyIsIdempotentAndRejectsConflictingPayload() {
        var t=newTenant(),other=newTenant();
        var actor=verified(t.actorId());
        var customer=secureCommands.createCompany(actor,t.tenantId(),"Consent Customer",null);
        var contact=secureCommands.addContact(actor,t.tenantId(),customer.id(),
                "Consent","consent@example.kz",null);
        var another=secureCommands.addContact(actor,t.tenantId(),customer.id(),
                "Other","other@example.kz",null);
        var foreignCustomer=secureCommands.createCompany(verified(other.actorId()),
                other.tenantId(),"Other Tenant",null);
        var foreignContact=secureCommands.addContact(verified(other.actorId()),other.tenantId(),
                foreignCustomer.id(),"Foreign","foreign@example.kz",null);
        String request="consent-"+UUID.randomUUID();
        UUID first=secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                contact.id(),true,"MARKETING","v1","WEB_FORM",request);
        assertThat(secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                contact.id(),true,"MARKETING","v1","WEB_FORM",request)).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.email_consent_event
                WHERE tenant_id=? AND request_key=?
                """,Integer.class,t.tenantId(),request)).isEqualTo(1);
        assertThatThrownBy(()->secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                contact.id(),false,"MARKETING","v1","WEB_FORM",request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                another.id(),true,"MARKETING","v1","WEB_FORM",request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                foreignContact.id(),true,"MARKETING","v1","WEB_FORM","foreign-"+UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->secureCommands.recordEmailConsentOnce(null,t.tenantId(),
                contact.id(),true,"MARKETING","v1","FORM","validkey001"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                contact.id(),true,"MARKETING","v1","WEB_FORM","bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(secureQueries.hasEmailConsent(actor,t.tenantId(),contact.id(),"MARKETING"))
                .isTrue();
        UUID revoke=secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                contact.id(),false,"MARKETING","v1","UNSUBSCRIBE","revoke-"+UUID.randomUUID());
        assertThat(revoke).isNotEqualTo(first);
        assertThat(secureQueries.hasEmailConsent(actor,t.tenantId(),contact.id(),"MARKETING"))
                .isFalse();
    }

    @Test void postgresConcurrentConsentSubmissionCreatesExactlyOneEvent() throws Exception {
        try(var connection=dataSource.getConnection()){
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var t=newTenant();
        var actor=verified(t.actorId());
        var customer=secureCommands.createCompany(actor,t.tenantId(),"Concurrent Consent",null);
        var contact=secureCommands.addContact(actor,t.tenantId(),customer.id(),
                "Contact","a@example.kz",null);
        var ready=new CountDownLatch(1);
        String key="concurrent-"+UUID.randomUUID();
        try(var executor=Executors.newFixedThreadPool(6)){
            List<Future<UUID>> results=new ArrayList<>();
            for(int i=0;i<12;i++) {
                results.add(executor.submit(()->{
                    if(!ready.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Start");
                    return secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                            contact.id(),true,"MARKETING","v1","WEB_FORM",key);
                }));
            }
            ready.countDown();
            UUID expected=results.getFirst().get(30,TimeUnit.SECONDS);
            for(Future<UUID> result:results)assertThat(result.get(30,TimeUnit.SECONDS))
                    .isEqualTo(expected);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.email_consent_event
                WHERE tenant_id=? AND request_key=?
                """,Integer.class,t.tenantId(),key)).isEqualTo(1);
    }

    @Test void katoRejectsSubdistrictForCustomer() {
        var t=newTenant();
        assertThatThrownBy(()->crm.createCompany(t.actorId(),t.tenantId(),"Client","433243300"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
