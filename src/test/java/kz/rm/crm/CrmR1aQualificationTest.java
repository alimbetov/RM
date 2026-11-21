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
    @Autowired CrmCustomerRepository customerMetadata;
    @Autowired CrmContactRepository contactMetadata;
    @Autowired CrmCategoryService categoryService;
    @Autowired CrmContractService contractService;
    @Autowired CustomerCategoryRepository categoryMetadata;
    @Autowired CustomerContractRepository contractMetadata;
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

    @Test void tenantIsoR1CustomersContactsJPAFiltersCountsAndAllPagesRemainScoped() {
        var a=newTenant();
        var b=newTenant();
        var aActor=verified(a.actorId());
        var bActor=verified(b.actorId());
        var a1=secureCommands.createCompany(aActor,a.tenantId(),"Shared Logistics",null);
        var a2=secureCommands.createCompany(aActor,a.tenantId(),"Shared Logistics",null);
        var b1=secureCommands.createCompany(bActor,b.tenantId(),"Shared Logistics",null);
        var b2=secureCommands.createPerson(bActor,b.tenantId(),"Private B",null);
        var contactA1=secureCommands.addContact(aActor,a.tenantId(),a1.id(),
                "Tenant A private","a-private@example.kz",null);
        var contactA2=secureCommands.addContact(aActor,a.tenantId(),a2.id(),
                "Tenant A second","a-second@example.kz",null);
        var contactB=secureCommands.addContact(bActor,b.tenantId(),b1.id(),
                "Tenant B private","b-private@example.kz",null);

        // Shared timestamps and identical company labels exercise the stable ID tie-break.
        var sharedTime=java.sql.Timestamp.from(Instant.parse("2026-01-02T03:04:05Z"));
        jdbc.update("UPDATE crm.customer SET created_at=? WHERE tenant_id=? AND id IN (?,?)",
                sharedTime,a.tenantId(),a1.id(),a2.id());
        jdbc.update("UPDATE crm.customer SET created_at=? WHERE tenant_id=? AND id IN (?,?)",
                sharedTime,b.tenantId(),b1.id(),b2.id());

        var filter=new CustomerSearch("COMPANY","ACTIVE",null,"Shared");
        var first=secureQueries.customers(aActor,a.tenantId(),filter,0,1);
        var second=secureQueries.customers(aActor,a.tenantId(),filter,1,1);
        var third=secureQueries.customers(aActor,a.tenantId(),filter,2,1);
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(second.getTotalElements()).isEqualTo(2);
        assertThat(third.getContent()).isEmpty();
        assertThat(java.util.List.of(first.getContent().getFirst().id(),
                        second.getContent().getFirst().id()))
                .containsExactlyInAnyOrder(a1.id(),a2.id()).doesNotContain(b1.id());
        assertThat(secureQueries.customers(aActor,a.tenantId(),
                new CustomerSearch("PERSON","ACTIVE",null,null),0,20)
                .getTotalElements()).isZero();
        assertThat(secureQueries.customers(bActor,b.tenantId(),filter,0,20)
                .getContent()).extracting(CustomerSummary::id).containsExactly(b1.id());

        assertThat(customerMetadata.findByTenantIdAndId(a.tenantId(),b1.id())).isEmpty();
        assertThat(customerMetadata.findByTenantIdAndId(b.tenantId(),a1.id())).isEmpty();
        assertThat(contactMetadata.findByTenantIdAndId(a.tenantId(),contactB.id())).isEmpty();
        assertThat(contactMetadata.findByTenantIdAndCustomerId(a.tenantId(),b1.id())).isEmpty();
        assertThat(contactMetadata.findByTenantIdAndCustomerId(a.tenantId(),a1.id()))
                .extracting(CrmContactEntity::getId).containsExactly(contactA1.id());
        assertThat(contactMetadata.findByTenantIdAndCustomerId(a.tenantId(),a2.id()))
                .extracting(CrmContactEntity::getId).containsExactly(contactA2.id());
        assertThat(secureQueries.contacts(aActor,a.tenantId(),
                new ContactSearch(b1.id(),"ACTIVE",null),0,10).getTotalElements()).isZero();
        assertThat(secureQueries.contacts(aActor,a.tenantId(),
                new ContactSearch(null,"ACTIVE",false),0,1).getTotalElements()).isEqualTo(2);
        assertThat(secureQueries.contacts(aActor,a.tenantId(),
                new ContactSearch(null,"ACTIVE",true),0,10).getTotalElements()).isZero();
        assertThat(secureQueries.contacts(aActor,a.tenantId(),
                new ContactSearch(a1.id(),"ACTIVE",null),0,10).getContent())
                .extracting(ContactSummary::id).containsExactly(contactA1.id());
        assertThatThrownBy(()->secureQueries.customers(aActor,b.tenantId(),filter,0,1))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.contacts(aActor,b.tenantId(),
                new ContactSearch(null,null,null),0,1))
                .isInstanceOf(SecurityException.class);
    }

    @Test void contactJpaMetadataIsTenantBoundAndHasNoPlaintextPiiFields() {
        var tenantA = newTenant();
        var tenantB = newTenant();
        var customerA = secureCommands.createPerson(verified(tenantA.actorId()),
                tenantA.tenantId(), "Customer A private", null);
        var customerB = secureCommands.createCompany(verified(tenantB.actorId()),
                tenantB.tenantId(), "Company B", null);
        var contactA = secureCommands.addContact(verified(tenantA.actorId()),
                tenantA.tenantId(), customerA.id(),
                "Sensitive Contact A", "secret-a@example.kz", "+77000000001");
        var contactB = secureCommands.addContact(verified(tenantB.actorId()),
                tenantB.tenantId(), customerB.id(),
                "Sensitive Contact B", "secret-b@example.kz", "+77000000002");

        var metadataA = contactMetadata.findByTenantIdAndId(tenantA.tenantId(), contactA.id());
        assertThat(metadataA).isPresent();
        assertThat(metadataA.orElseThrow().getTenantId()).isEqualTo(tenantA.tenantId());
        assertThat(metadataA.orElseThrow().getCustomerId()).isEqualTo(customerA.id());
        assertThat(metadataA.orElseThrow().getStatus()).isEqualTo(CrmContactEntity.Status.ACTIVE);
        assertThat(metadataA.orElseThrow().isPrimary()).isFalse();

        assertThat(contactMetadata.findByTenantIdAndId(tenantB.tenantId(), contactA.id()))
                .isEmpty();
        assertThat(contactMetadata.findByTenantIdAndId(tenantA.tenantId(), contactB.id()))
                .isEmpty();
        assertThat(contactMetadata.findById(
                new CrmContactKey(tenantB.tenantId(), contactA.id()))).isEmpty();
        assertThat(contactMetadata.findByTenantIdAndCustomerId(
                tenantA.tenantId(), customerB.id())).isEmpty();
        assertThat(contactMetadata.findByTenantIdAndCustomerId(
                tenantA.tenantId(), customerA.id()))
                .extracting(CrmContactEntity::getId).containsExactly(contactA.id());

        // Guard the entity mapping itself against accidental plaintext PII columns.
        var mappedFields = java.util.Arrays.stream(CrmContactEntity.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList();
        assertThat(mappedFields).doesNotContain(
                "name", "email", "phone", "nameEnvelope", "emailEnvelope", "phoneEnvelope");
        assertThat(jdbc.queryForObject("""
                SELECT name_envelope FROM crm_pii.contact_secret
                WHERE tenant_id=? AND contact_id=?
                """,byte[].class,tenantA.tenantId(),contactA.id()))
                .isNotNull();
        assertThat(secureQueries.getContact(
                verified(tenantA.actorId()),tenantA.tenantId(),contactA.id()).email())
                .isEqualTo("secret-a@example.kz");
        assertThatThrownBy(() -> secureQueries.getContact(
                verified(tenantB.actorId()),tenantB.tenantId(),contactA.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void metadataJpaRepositoryUsesCompositeTenantKeyAndNeverExposesPersonSecret() {
        var one = newTenant();
        var two = newTenant();
        var person = secureCommands.createPerson(verified(one.actorId()), one.tenantId(),
                "Sensitive Person Name", null);
        var company = secureCommands.createCompany(verified(two.actorId()), two.tenantId(),
                "Independent Tenant LLC", null);

        // The same UUID must never resolve outside its tenant, even in the
        // persistence layer, and metadata must not contain decrypted PERSON names.
        var onePerson = customerMetadata.findByTenantIdAndId(one.tenantId(), person.id());
        assertThat(onePerson).isPresent();
        assertThat(onePerson.orElseThrow().getCustomerType())
                .isEqualTo(CrmCustomerEntity.Type.PERSON);
        assertThat(onePerson.orElseThrow().getOrganizationLabel()).isNull();
        assertThat(onePerson.orElseThrow().getTenantId()).isEqualTo(one.tenantId());
        assertThat(customerMetadata.findByTenantIdAndId(two.tenantId(), person.id()))
                .isEmpty();
        assertThat(customerMetadata.findByTenantIdAndId(one.tenantId(), company.id()))
                .isEmpty();
        assertThat(customerMetadata.findByTenantIdAndId(two.tenantId(), company.id()))
                .hasValueSatisfying(row -> {
                    assertThat(row.getOrganizationLabel()).isEqualTo("Independent Tenant LLC");
                    assertThat(row.getStatus()).isEqualTo(CrmCustomerEntity.Status.ACTIVE);
                });
        assertThat(customerMetadata.findById(new CrmCustomerKey(one.tenantId(), person.id())))
                .isPresent();
        assertThat(customerMetadata.findById(new CrmCustomerKey(two.tenantId(), person.id())))
                .isEmpty();
        assertThat(customerMetadata.findById(new CrmCustomerKey(one.tenantId(), company.id())))
                .isEmpty();
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
        var t=newTenant();
        var other=newTenant();
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
        var t=newTenant();
        var other=newTenant();
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
        // A previously valid request key is not an authorization capability.
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",
                t.actorId());
        assertThatThrownBy(()->secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                contact.id(),true,"MARKETING","v1","WEB_FORM",request))
                .isInstanceOf(SecurityException.class);
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


    @Test void consentKeyDoesNotSurviveRollbackAndCanBeRetried() {
        var t=newTenant();
        var actor=verified(t.actorId());
        var company=secureCommands.createCompany(actor,t.tenantId(),"Rollback consent",null);
        var contact=secureCommands.addContact(actor,t.tenantId(),company.id(),
                "Consent user",null,null);
        String key="rollback-"+UUID.randomUUID();
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{
            secureCommands.recordEmailConsentOnce(actor,t.tenantId(),contact.id(),
                    true,"MARKETING","v1","FORM",key);
            throw new IllegalStateException("injected before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.email_consent_event WHERE tenant_id=? AND request_key=?
                """,Integer.class,t.tenantId(),key)).isZero();
        UUID replay=secureCommands.recordEmailConsentOnce(actor,t.tenantId(),contact.id(),
                true,"MARKETING","v1","FORM",key);
        assertThat(secureCommands.recordEmailConsentOnce(actor,t.tenantId(),contact.id(),
                true,"MARKETING","v1","FORM",key)).isEqualTo(replay);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.email_consent_event WHERE tenant_id=? AND request_key=?
                """,Integer.class,t.tenantId(),key)).isEqualTo(1);
    }

    @Test void customerNameFilterEscapesSqlWildcardsAsLiteralText() {
        var t=newTenant();
        var actor=verified(t.actorId());
        secureCommands.createCompany(actor,t.tenantId(),"Company_100%",null);
        secureCommands.createCompany(actor,t.tenantId(),"CompanyX1000",null);
        assertThat(secureQueries.customers(actor,t.tenantId(),
                new CustomerSearch("COMPANY",null,null,"_100%"),0,10)
                .getTotalElements()).isEqualTo(1);
        assertThat(secureQueries.customers(actor,t.tenantId(),
                new CustomerSearch("COMPANY",null,null,"\\\\"),
                0,10).getTotalElements()).isZero();
        assertThat(secureQueries.customers(actor,t.tenantId(),
                new CustomerSearch("COMPANY",null,null,"company"),0,1)
                .getTotalElements()).isEqualTo(2);
    }

    @Test void contactCiphertextSwapFailsClosedUnderPerRecordAad() {
        var t=newTenant();
        var actor=verified(t.actorId());
        var company=secureCommands.createCompany(actor,t.tenantId(),"AAD test",null);
        var first=secureCommands.addContact(actor,t.tenantId(),company.id(),
                "First secret","first@example.kz",null);
        var second=secureCommands.addContact(actor,t.tenantId(),company.id(),
                "Second secret","second@example.kz",null);
        byte[] stolen=jdbc.queryForObject("""
                SELECT email_envelope FROM crm_pii.contact_secret WHERE tenant_id=? AND contact_id=?
                """,byte[].class,t.tenantId(),first.id());
        jdbc.update("""
                UPDATE crm_pii.contact_secret SET email_envelope=? WHERE tenant_id=? AND contact_id=?
                """,stolen,t.tenantId(),second.id());
        assertThatThrownBy(()->secureQueries.getContact(actor,t.tenantId(),second.id()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->secureQueries.contacts(actor,t.tenantId(),null,0,10))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void postgresConsentEvidenceRejectsDirectSqlUpdateAndDelete() throws Exception {
        try(var connection=dataSource.getConnection()){
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        var t=newTenant();
        var actor=verified(t.actorId());
        var company=secureCommands.createCompany(actor,t.tenantId(),"Audit history",null);
        var contact=secureCommands.addContact(actor,t.tenantId(),company.id(),
                "Audit",null,null);
        UUID event=secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                contact.id(),true,"MARKETING","v1","FORM","immutable-"+UUID.randomUUID());
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.email_consent_event SET granted=FALSE WHERE tenant_id=? AND id=?
                """,t.tenantId(),event)).isInstanceOf(Exception.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.email_consent_event WHERE tenant_id=? AND id=?
                """,t.tenantId(),event)).isInstanceOf(Exception.class)
                .hasMessageContaining("immutable");
        assertThat(jdbc.queryForObject("""
                SELECT granted FROM crm.email_consent_event WHERE tenant_id=? AND id=?
                """,Boolean.class,t.tenantId(),event)).isTrue();
    }

    @Test void katoRejectsSubdistrictForCustomer() {
        var t=newTenant();
        assertThatThrownBy(()->crm.createCompany(t.actorId(),t.tenantId(),"Client","433243300"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void tenantIsoR12CategoriesAndContractsScopedPaginationAndCrossTenantFks() {
        var a=newTenant();
        var b=newTenant();
        var actorA=verified(a.actorId());
        var actorB=verified(b.actorId());
        var customerA=secureCommands.createCompany(actorA,a.tenantId(),"A client",null);
        var customerB=secureCommands.createCompany(actorB,b.tenantId(),"B client",null);
        var categoryA=categoryService.create(actorA,a.tenantId(),"WHOLESALE","Wholesale");
        var categoryB=categoryService.create(actorB,b.tenantId(),"WHOLESALE","Wholesale B");
        assertThatThrownBy(()->categoryService.create(actorA,a.tenantId(),"WHOLESALE","Duplicate"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        categoryService.assignPrimary(actorA,a.tenantId(),customerA.id(),categoryA.id());
        assertThat(customerMetadata.findByTenantIdAndId(a.tenantId(),customerA.id())
                .orElseThrow().getPrimaryCategoryId()).isEqualTo(categoryA.id());
        assertThat(categoryService.list(actorA,a.tenantId(),true,0,1).getTotalElements()).isEqualTo(1);
        assertThat(categoryService.list(actorB,b.tenantId(),true,0,1).getContent())
                .extracting(CrmCategoryService.Category::id).containsExactly(categoryB.id());
        assertThat(categoryMetadata.findByTenantIdAndId(a.tenantId(),categoryB.id())).isEmpty();
        assertThatThrownBy(()->categoryService.assignPrimary(actorA,a.tenantId(),customerA.id(),categoryB.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->categoryService.assignPrimary(actorA,a.tenantId(),customerB.id(),categoryA.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->categoryService.list(actorA,b.tenantId(),true,0,10))
                .isInstanceOf(SecurityException.class);

        var today=java.time.LocalDate.of(2026,11,22);
        var first=contractService.create(actorA,a.tenantId(),customerA.id(),"CN-01",
                today.minusDays(20),today.plusDays(10),today);
        var second=contractService.create(actorA,a.tenantId(),customerA.id(),"CN-02",
                today.minusDays(20),today.plusDays(60),today);
        assertThatThrownBy(()->contractService.create(actorA,a.tenantId(),customerA.id(),
                "CN-01",today,today.plusDays(2),today))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var foreign=contractService.create(actorB,b.tenantId(),customerB.id(),"CN-01",
                today.minusDays(20),today.plusDays(3),today);
        assertThat(first.expiryIndicator()).isEqualTo(ContractExpiryPolicy.Indicator.DRAFT);
        first=contractService.changeStatus(actorA,a.tenantId(),first.id(),
                CustomerContractEntity.Status.ACTIVE,first.rowVersion(),today);
        assertThat(first.expiryIndicator()).isEqualTo(ContractExpiryPolicy.Indicator.EXPIRING_SOON);
        assertThat(contractService.get(actorA,a.tenantId(),first.id(),today.plusDays(11),30)
                .expiryIndicator()).isEqualTo(ContractExpiryPolicy.Indicator.EXPIRED);
        assertThat(contractService.list(actorA,a.tenantId(),customerA.id(),today,30,0,1)
                .getTotalElements()).isEqualTo(2);
        assertThat(contractService.list(actorA,a.tenantId(),customerA.id(),today,30,1,1)
                .getContent()).hasSize(1);
        assertThat(contractService.list(actorA,a.tenantId(),customerA.id(),today,30,2,1)
                .getContent()).isEmpty();
        assertThat(contractMetadata.findByTenantIdAndId(a.tenantId(),foreign.id())).isEmpty();
        assertThat(contractService.list(actorA,a.tenantId(),customerB.id(),today,30,0,10)
                .getTotalElements()).isZero();
        assertThatThrownBy(()->contractService.create(actorA,a.tenantId(),customerB.id(),
                "FORBIDDEN",today,today.plusDays(1),today)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->contractService.get(actorA,b.tenantId(),foreign.id(),today,30))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->contractService.changeDates(actorA,a.tenantId(),foreign.id(),
                today,today.plusDays(3),0,today)).isInstanceOf(IllegalArgumentException.class);

        // Neither service validation nor JPA can replace these database-level composite FKs.
        assertThatThrownBy(()->jdbc.update(
                "UPDATE crm.customer SET primary_category_id=? WHERE tenant_id=? AND id=?",
                categoryB.id(),a.tenantId(),customerA.id())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
                INSERT INTO crm.customer_contract(tenant_id,id,customer_id,contract_code,starts_on,
                  status,created_at,updated_at) VALUES (?,?,?,'ILLEGAL',?,'DRAFT',now(),now())
                """,a.tenantId(),UUID.randomUUID(),customerB.id(),today)).isInstanceOf(Exception.class);
    }

    @Test void crmR12LifecycleVersionAndDateEdgeCases() {
        var t=newTenant();
        var actor=verified(t.actorId());
        var customer=secureCommands.createCompany(actor,t.tenantId(),"Versioned",null);
        var cat=categoryService.create(actor,t.tenantId(),"ENTERPRISE","Enterprise");
        var originalCat=cat;
        assertThatThrownBy(()->categoryService.rename(actor,t.tenantId(),originalCat.id(),"Changed",99))
                .isInstanceOf(IllegalStateException.class);
        cat=categoryService.archive(actor,t.tenantId(),cat.id(),cat.rowVersion());
        var archivedCat=cat;
        assertThatThrownBy(()->categoryService.assignPrimary(actor,t.tenantId(),customer.id(),archivedCat.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(categoryService.list(actor,t.tenantId(),true,0,10).getTotalElements()).isZero();

        var today=java.time.LocalDate.of(2026,11,22);
        assertThatThrownBy(()->contractService.create(actor,t.tenantId(),customer.id(),
                "BAD-DATES",today,today.minusDays(1),today)).isInstanceOf(IllegalArgumentException.class);
        var contract=contractService.create(actor,t.tenantId(),customer.id(),"OPEN-01",
                today.plusDays(1),null,today);
        final var draftContract=contract;
        assertThatThrownBy(()->contractService.changeStatus(actor,t.tenantId(),draftContract.id(),
                CustomerContractEntity.Status.CLOSED,draftContract.rowVersion(),today))
                .isInstanceOf(IllegalStateException.class);
        contract=contractService.changeStatus(actor,t.tenantId(),contract.id(),
                CustomerContractEntity.Status.ACTIVE,contract.rowVersion(),today);
        assertThat(contract.expiryIndicator()).isEqualTo(ContractExpiryPolicy.Indicator.NOT_STARTED);
        assertThat(contractService.get(actor,t.tenantId(),contract.id(),today.plusDays(1),30)
                .expiryIndicator()).isEqualTo(ContractExpiryPolicy.Indicator.OPEN_ENDED);
        final var stale=contract;
        assertThatThrownBy(()->contractService.changeDates(actor,t.tenantId(),stale.id(),
                today,today,stale.rowVersion()-1,today)).isInstanceOf(IllegalStateException.class);
        contract=contractService.changeStatus(actor,t.tenantId(),contract.id(),
                CustomerContractEntity.Status.CLOSED,contract.rowVersion(),today);
        assertThat(contract.expiryIndicator()).isEqualTo(ContractExpiryPolicy.Indicator.CLOSED);
        final var terminal=contract;
        assertThatThrownBy(()->contractService.changeDates(actor,t.tenantId(),terminal.id(),
                today,today,terminal.rowVersion(),today)).isInstanceOf(IllegalStateException.class);
        assertThat(ContractExpiryPolicy.evaluate(CustomerContractEntity.Status.ACTIVE,
                today.minusDays(1),today,today,0))
                .isEqualTo(ContractExpiryPolicy.Indicator.EXPIRING_SOON);
    }

}
