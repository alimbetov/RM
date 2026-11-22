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
    @Autowired CrmHistoryService crmHistory;
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


    @Test void crmR12ConcurrentSameVersionUpdatesOnlyOneCanCommit() throws Exception {
        var t=newTenant();
        var actor=verified(t.actorId());
        var customer=secureCommands.createCompany(actor,t.tenantId(),"Concurrent customer",null);
        var category=categoryService.create(actor,t.tenantId(),"CONCURRENT","Initial");
        var today=java.time.LocalDate.of(2026,11,22);
        var contract=contractService.create(actor,t.tenantId(),customer.id(),"CONCURRENT-01",
                today,today.plusDays(90),today);
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);
            List<Callable<Boolean>> writes=List.of(
                    () -> { gate.await(); try { categoryService.rename(actor,t.tenantId(),
                            category.id(),"Name A",category.rowVersion()); return true; }
                            catch (org.springframework.orm.ObjectOptimisticLockingFailureException
                                    | IllegalStateException ex) { return false; } },
                    () -> { gate.await(); try { categoryService.rename(actor,t.tenantId(),
                            category.id(),"Name B",category.rowVersion()); return true; }
                            catch (org.springframework.orm.ObjectOptimisticLockingFailureException
                                    | IllegalStateException ex) { return false; } });
            var f1=executor.submit(writes.get(0));
            var f2=executor.submit(writes.get(1));
            gate.countDown();
            int successes=(f1.get(30,TimeUnit.SECONDS) ? 1 : 0)
                    +(f2.get(30,TimeUnit.SECONDS) ? 1 : 0);
            assertThat(successes).isEqualTo(1);

            var statusGate=new CountDownLatch(1);
            var c1=executor.submit((Callable<Boolean>)() -> {
                statusGate.await();
                try { contractService.changeStatus(actor,t.tenantId(),contract.id(),
                        CustomerContractEntity.Status.ACTIVE,contract.rowVersion(),today); return true; }
                catch (org.springframework.orm.ObjectOptimisticLockingFailureException
                        | IllegalStateException ex) { return false; }
            });
            var c2=executor.submit((Callable<Boolean>)() -> {
                statusGate.await();
                try { contractService.changeStatus(actor,t.tenantId(),contract.id(),
                        CustomerContractEntity.Status.CANCELLED,contract.rowVersion(),today); return true; }
                catch (org.springframework.orm.ObjectOptimisticLockingFailureException
                        | IllegalStateException ex) { return false; }
            });
            statusGate.countDown();
            assertThat((c1.get(30,TimeUnit.SECONDS) ? 1 : 0)
                    +(c2.get(30,TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
        assertThat(categoryService.get(actor,t.tenantId(),category.id()).rowVersion())
                .isEqualTo(category.rowVersion()+1);
        assertThat(contractService.get(actor,t.tenantId(),contract.id(),today,30).rowVersion())
                .isEqualTo(contract.rowVersion()+1);
    }


    @Test void crmR12VerifiedActorAndTenantPermissionsCannotBeBypassed() {
        var t=newTenant();
        var owner=verified(t.actorId());
        var customer=secureCommands.createCompany(owner,t.tenantId(),"RBAC customer",null);
        var category=categoryService.create(owner,t.tenantId(),"RBAC","Protected");
        var today=java.time.LocalDate.of(2026,11,22);
        var contract=contractService.create(owner,t.tenantId(),customer.id(),"RBAC-001",
                today,today.plusDays(60),today);
        UUID orgId=jdbc.queryForObject("SELECT organization_id FROM iam_workspace WHERE id=?",
                UUID.class,t.tenantId());
        var viewer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "r12-viewer-"+UUID.randomUUID(),"Viewer"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),orgId,
                viewer.getId(),OrganizationMembershipEntity.Role.VIEWER));
        var readOnlyActor=verified(viewer.getId());
        assertThat(categoryService.get(readOnlyActor,t.tenantId(),category.id()).id())
                .isEqualTo(category.id());
        assertThat(contractService.get(readOnlyActor,t.tenantId(),contract.id(),today,30).id())
                .isEqualTo(contract.id());
        assertThatThrownBy(()->categoryService.create(readOnlyActor,t.tenantId(),"X","X"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->categoryService.assignPrimary(readOnlyActor,t.tenantId(),
                customer.id(),category.id())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->contractService.changeStatus(readOnlyActor,t.tenantId(),
                contract.id(),CustomerContractEntity.Status.ACTIVE,contract.rowVersion(),today))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->contractService.create(readOnlyActor,t.tenantId(),
                customer.id(),"DENIED",today,today,today)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->categoryService.get(null,t.tenantId(),category.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->contractService.list(null,t.tenantId(),null,today,30,0,10))
                .isInstanceOf(SecurityException.class);
    }


    @Test void crmR13CustomerLifecycleEncryptionAuditAndTenantIsolation() {
        var a=newTenant(); var b=newTenant();
        var actorA=verified(a.actorId()); var actorB=verified(b.actorId());
        var person=secureCommands.createPerson(actorA,a.tenantId(),"Original private name",null);
        var company=secureCommands.createCompany(actorA,a.tenantId(),"Original business",null);
        var foreign=secureCommands.createCompany(actorB,b.tenantId(),"Foreign business",null);
        long personV=customerMetadata.findByTenantIdAndId(a.tenantId(),person.id()).orElseThrow().getRowVersion();
        secureCommands.updatePerson(actorA,a.tenantId(),person.id(),"Revised private name",null,personV);
        assertThat(secureQueries.getCustomer(actorA,a.tenantId(),person.id()).name())
                .isEqualTo("Revised private name");
        byte[] stored=jdbc.queryForObject("""
          SELECT name_envelope FROM crm_pii.customer_secret WHERE tenant_id=? AND customer_id=?
          """,byte[].class,a.tenantId(),person.id());
        assertThat(new String(stored,java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("Revised private name");
        long companyV=customerMetadata.findByTenantIdAndId(a.tenantId(),company.id()).orElseThrow().getRowVersion();
        secureCommands.updateCompany(actorA,a.tenantId(),company.id(),"Revised business",null,companyV);
        assertThat(secureQueries.getCustomer(actorA,a.tenantId(),company.id()).name())
                .isEqualTo("Revised business");
        var changes=crmHistory.customerHistory(actorA,a.tenantId(),company.id(),20);
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().actorId()).isEqualTo(a.actorId());
        assertThat(changes.getFirst().action()).isEqualTo("METADATA_UPDATED");
        assertThat(crmHistory.customerHistory(actorA,a.tenantId(),foreign.id(),20)).isEmpty();
        assertThatThrownBy(()->secureCommands.updateCompany(actorA,a.tenantId(),foreign.id(),
                "No",null,0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->secureCommands.updateCompany(actorA,b.tenantId(),foreign.id(),
                "No",null,0)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureCommands.updateCompany(actorA,a.tenantId(),company.id(),
                "Stale",null,companyV)).isInstanceOf(IllegalStateException.class);
        secureCommands.archiveCustomer(actorA,a.tenantId(),company.id(),companyV+1);
        assertThat(customerMetadata.findByTenantIdAndId(a.tenantId(),company.id()).orElseThrow()
                .getStatus()).isEqualTo(CrmCustomerEntity.Status.ARCHIVED);
        assertThat(crmHistory.customerHistory(actorA,a.tenantId(),company.id(),20))
                .extracting(CrmHistoryService.CustomerChange::action)
                .containsExactly("ARCHIVED","METADATA_UPDATED");
        assertThatThrownBy(()->secureCommands.updateCompany(actorA,a.tenantId(),company.id(),
                "No",null,companyV+2)).isInstanceOf(IllegalStateException.class);
    }

    @Test void crmR13ContractRenewalImmutableRevisionsAndArchiveGuard() {
        var a=newTenant(); var b=newTenant();
        var actor=verified(a.actorId()); var actorB=verified(b.actorId());
        var customer=secureCommands.createCompany(actor,a.tenantId(),"Term customer",null);
        var foreign=secureCommands.createCompany(actorB,b.tenantId(),"Foreign",null);
        var today=java.time.LocalDate.of(2026,11,22);
        var contract=contractService.create(actor,a.tenantId(),customer.id(),"RENEW-01",
                today,today.plusDays(30),today);
        assertThat(crmHistory.contractHistory(actor,a.tenantId(),contract.id(),20))
                .extracting(CrmHistoryService.ContractRevision::action)
                .containsExactly("CREATED");
        contract=contractService.changeStatus(actor,a.tenantId(),contract.id(),
                CustomerContractEntity.Status.ACTIVE,contract.rowVersion(),today);
        final var active=contract;
        assertThatThrownBy(()->contractService.renew(actor,a.tenantId(),active.id(),
                today.plusDays(25),active.rowVersion(),today))
                .isInstanceOf(IllegalStateException.class);
        contract=contractService.renew(actor,a.tenantId(),contract.id(),
                today.plusDays(90),contract.rowVersion(),today);
        assertThat(contract.endsOn()).isEqualTo(today.plusDays(90));
        assertThat(crmHistory.contractHistory(actor,a.tenantId(),contract.id(),20))
                .extracting(CrmHistoryService.ContractRevision::action)
                .containsExactly("RENEWED","STATUS_CHANGED","CREATED");
        var previous=crmHistory.contractHistory(actor,a.tenantId(),contract.id(),20).get(1);
        assertThat(previous.endsOn()).isEqualTo(today.plusDays(30));
        assertThat(crmHistory.contractHistory(actor,a.tenantId(),UUID.randomUUID(),20)).isEmpty();
        final var renewedContract=contract;
        assertThatThrownBy(()->crmHistory.contractHistory(actor,b.tenantId(),renewedContract.id(),20))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureCommands.archiveCustomer(actor,a.tenantId(),customer.id(),0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->contractService.create(actor,a.tenantId(),foreign.id(),
                "CROSS",today,today.plusDays(3),today)).isInstanceOf(RuntimeException.class);
        var immutable=crmHistory.contractHistory(actor,a.tenantId(),contract.id(),20).getFirst();
        boolean postgres;
        try(var connection=dataSource.getConnection()) {
            postgres=connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql");
        } catch(java.sql.SQLException ex) {
            throw new IllegalStateException("Cannot identify database",ex);
        }
        if(postgres) {
            assertThatThrownBy(()->jdbc.update("""
              DELETE FROM crm.contract_revision WHERE tenant_id=? AND id=?
              """,a.tenantId(),immutable.id())).isInstanceOf(Exception.class);
            assertThatThrownBy(()->jdbc.update("""
              UPDATE crm.contract_revision SET action_code='CREATED' WHERE tenant_id=? AND id=?
              """,a.tenantId(),immutable.id())).isInstanceOf(Exception.class);
        }
    }


    @Test void crmR13CapturesLegacyContractBaselineBeforeFirstRenewal() {
        var t=newTenant();
        var actor=verified(t.actorId());
        var customer=secureCommands.createCompany(actor,t.tenantId(),"Legacy contract customer",null);
        var today=java.time.LocalDate.of(2026,11,22);
        UUID oldId=UUID.randomUUID();
        jdbc.update("""
          INSERT INTO crm.customer_contract(tenant_id,id,customer_id,contract_code,
            starts_on,ends_on,status,created_at,updated_at)
          VALUES (?,?,?,?,?,?,'ACTIVE',now(),now())
          """,t.tenantId(),oldId,customer.id(),"LEGACY-R12",
                today.minusDays(30),today.plusDays(15));
        assertThat(crmHistory.contractHistory(actor,t.tenantId(),oldId,10)).isEmpty();
        var renewed=contractService.renew(actor,t.tenantId(),oldId,
                today.plusDays(60),0,today);
        assertThat(renewed.rowVersion()).isEqualTo(1);
        var revisions=crmHistory.contractHistory(actor,t.tenantId(),oldId,10);
        assertThat(revisions).hasSize(2);
        assertThat(revisions).extracting(CrmHistoryService.ContractRevision::action)
                .containsExactly("RENEWED","BASELINE_CAPTURED");
        assertThat(revisions.get(1).endsOn()).isEqualTo(today.plusDays(15));
        assertThat(revisions.get(0).endsOn()).isEqualTo(today.plusDays(60));
        assertThat(revisions.get(1).revisionNo()).isEqualTo(0);
    }


    @Test void crmR13CannotActivateDraftAfterCustomerIsArchived() {
        var t=newTenant();
        var actor=verified(t.actorId());
        var client=secureCommands.createCompany(actor,t.tenantId(),"Draft owner",null);
        var today=java.time.LocalDate.of(2026,11,22);
        var draft=contractService.create(actor,t.tenantId(),client.id(),"DRAFT-ARCHIVE",
                today,today.plusDays(30),today);
        secureCommands.archiveCustomer(actor,t.tenantId(),client.id(),0);
        assertThatThrownBy(()->contractService.changeStatus(actor,t.tenantId(),draft.id(),
                CustomerContractEntity.Status.ACTIVE,draft.rowVersion(),today))
                .isInstanceOf(IllegalStateException.class);
        assertThat(contractService.get(actor,t.tenantId(),draft.id(),today,30).status())
                .isEqualTo(CustomerContractEntity.Status.DRAFT);
    }


    @Test void crmR14ContactCrudEncryptionTenantIsolationAndImmutableAudit() throws Exception {
        var a=newTenant(); var b=newTenant();
        var actorA=verified(a.actorId()); var actorB=verified(b.actorId());
        var customerA=secureCommands.createCompany(actorA,a.tenantId(),"R14 customer A",null);
        var customerB=secureCommands.createCompany(actorB,b.tenantId(),"R14 customer B",null);
        var ca=secureCommands.addContact(actorA,a.tenantId(),customerA.id(),
                "Old A","old@example.kz","+77001001010");
        var cb=secureCommands.addContact(actorB,b.tenantId(),customerB.id(),
                "Old B","b@example.kz","+77002002020");
        var updated=secureCommands.updateContact(actorA,a.tenantId(),ca.id(),
                "New A","new@example.kz",null,0);
        assertThat(updated.name()).isNull();
        assertThat(updated.email()).isNull();
        assertThat(secureQueries.getContact(actorA,a.tenantId(),ca.id()).name()).isEqualTo("New A");
        var envelope=jdbc.queryForObject("""
            SELECT name_envelope FROM crm_pii.contact_secret WHERE tenant_id=? AND contact_id=?
            """,byte[].class,a.tenantId(),ca.id());
        assertThat(new String(envelope,java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("New A");
        assertThat(contactMetadata.findByTenantIdAndId(a.tenantId(),ca.id())
                .orElseThrow().getRowVersion()).isEqualTo(1);
        assertThat(crmHistory.contactHistory(actorA,a.tenantId(),ca.id(),10))
                .extracting(CrmHistoryService.ContactChange::action).containsExactly("UPDATED","CREATED");
        assertThat(crmHistory.contactHistory(actorA,a.tenantId(),cb.id(),10)).isEmpty();
        assertThatThrownBy(()->crmHistory.contactHistory(actorA,b.tenantId(),cb.id(),10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureCommands.updateContact(actorA,a.tenantId(),cb.id(),
                "X",null,null,0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->secureCommands.updateContact(actorA,b.tenantId(),cb.id(),
                "X",null,null,0)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureCommands.updateContact(actorA,a.tenantId(),ca.id(),
                "stale",null,null,0)).isInstanceOf(IllegalStateException.class);
        secureCommands.archiveContact(actorA,a.tenantId(),ca.id(),1);
        assertThat(contactMetadata.findByTenantIdAndId(a.tenantId(),ca.id())
                .orElseThrow().getStatus()).isEqualTo(CrmContactEntity.Status.ARCHIVED);
        assertThatThrownBy(()->secureQueries.getContact(actorA,a.tenantId(),ca.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->secureCommands.updateContact(actorA,a.tenantId(),ca.id(),
                "No",null,null,2)).isInstanceOf(IllegalStateException.class);
        secureCommands.restoreContact(actorA,a.tenantId(),ca.id(),2);
        assertThat(secureQueries.getContact(actorA,a.tenantId(),ca.id()).name()).isEqualTo("New A");
        assertThat(crmHistory.contactHistory(actorA,a.tenantId(),ca.id(),10))
                .extracting(CrmHistoryService.ContactChange::action)
                .containsExactly("RESTORED","ARCHIVED","UPDATED","CREATED");
        var change=crmHistory.contactHistory(actorA,a.tenantId(),ca.id(),10).getFirst();
        assertThat(change.actorId()).isEqualTo(a.actorId());
        boolean pg;
        try (var connection=dataSource.getConnection()) {
            pg=connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        }
        if(pg) {
            assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.contact_change_event WHERE tenant_id=? AND id=?
                """,a.tenantId(),change.id())).isInstanceOf(Exception.class);
        }
    }

    @Test void crmR14CustomerRestoreAndContactParentStatusGuard() {
        var t=newTenant(); var actor=verified(t.actorId());
        var customer=secureCommands.createCompany(actor,t.tenantId(),"Archived parent",null);
        var contact=secureCommands.addContact(actor,t.tenantId(),customer.id(),"Secret",null,null);
        secureCommands.archiveCustomer(actor,t.tenantId(),customer.id(),0);
        assertThatThrownBy(()->secureCommands.recordEmailConsentOnce(actor,t.tenantId(),
                contact.id(),true,"marketing","v1","R14","archivedcontact-r14"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->secureQueries.getContact(actor,t.tenantId(),contact.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->secureCommands.updateContact(actor,t.tenantId(),contact.id(),
                "No",null,null,0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->secureCommands.restoreContact(actor,t.tenantId(),contact.id(),0))
                .isInstanceOf(IllegalStateException.class);
        secureCommands.restoreCustomer(actor,t.tenantId(),customer.id(),1);
        assertThat(secureQueries.getContact(actor,t.tenantId(),contact.id()).name())
                .isEqualTo("Secret");
        assertThat(crmHistory.customerHistory(actor,t.tenantId(),customer.id(),10))
                .extracting(CrmHistoryService.CustomerChange::action)
                .containsExactly("RESTORED","ARCHIVED");
        assertThatThrownBy(()->secureCommands.restoreCustomer(actor,t.tenantId(),customer.id(),1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void crmR14TwoWritersSameContactVersionExactlyOneCommits() throws Exception {
        var t=newTenant(); var actor=verified(t.actorId());
        var customer=secureCommands.createCompany(actor,t.tenantId(),"Concurrent contact",null);
        var contact=secureCommands.addContact(actor,t.tenantId(),customer.id(),"First",null,null);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);
            Callable<Boolean> first=()->{
                gate.await();
                try { secureCommands.updateContact(actor,t.tenantId(),contact.id(),
                        "Winner A",null,null,0); return true; }
                catch(IllegalStateException ex) { return false; }
            };
            Callable<Boolean> second=()->{
                gate.await();
                try { secureCommands.updateContact(actor,t.tenantId(),contact.id(),
                        "Winner B",null,null,0); return true; }
                catch(IllegalStateException ex) { return false; }
            };
            var f1=executor.submit(first); var f2=executor.submit(second);
            gate.countDown();
            assertThat((f1.get(30,TimeUnit.SECONDS)?1:0)+(f2.get(30,TimeUnit.SECONDS)?1:0))
                    .isEqualTo(1);
        }
        assertThat(crmHistory.contactHistory(actor,t.tenantId(),contact.id(),10))
                .extracting(CrmHistoryService.ContactChange::action)
                .containsExactly("UPDATED","CREATED");
        assertThat(contactMetadata.findByTenantIdAndId(t.tenantId(),contact.id())
                .orElseThrow().getRowVersion()).isEqualTo(1);
    }


    @Test void crmR14ReadOnlyRoleCannotUpdateArchiveOrRestoreContacts() {
        var t=newTenant(); var actor=verified(t.actorId());
        var customer=secureCommands.createCompany(actor,t.tenantId(),"Role bound",null);
        var contact=secureCommands.addContact(actor,t.tenantId(),customer.id(),"Private",null,null);
        UUID orgId=jdbc.queryForObject("""
            SELECT organization_id FROM iam_workspace WHERE id=?
            """,UUID.class,t.tenantId());
        var viewer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "r14-viewer-"+UUID.randomUUID(),"Viewer"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),orgId,
                viewer.getId(),OrganizationMembershipEntity.Role.VIEWER));
        var readonly=verified(viewer.getId());
        assertThat(secureQueries.contactStatus(readonly,t.tenantId(),contact.id()))
                .isEqualTo(CrmContactEntity.Status.ACTIVE);
        assertThatThrownBy(()->secureCommands.updateContact(readonly,t.tenantId(),contact.id(),
                "No",null,null,0)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureCommands.archiveContact(readonly,t.tenantId(),contact.id(),0))
                .isInstanceOf(SecurityException.class);
        secureCommands.archiveContact(actor,t.tenantId(),contact.id(),0);
        assertThat(secureQueries.contactStatus(readonly,t.tenantId(),contact.id()))
                .isEqualTo(CrmContactEntity.Status.ARCHIVED);
        assertThatThrownBy(()->secureCommands.restoreContact(readonly,t.tenantId(),contact.id(),1))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.contactStatus(null,t.tenantId(),contact.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureCommands.restoreContact(null,t.tenantId(),contact.id(),1))
                .isInstanceOf(SecurityException.class);
        assertThat(crmHistory.contactHistory(readonly,t.tenantId(),contact.id(),10))
                .extracting(CrmHistoryService.ContactChange::action).containsExactly("ARCHIVED","CREATED");
    }


    @Test void tcrud02WriteOnlyRoleCanMutateButCannotReceiveOrReadPii() {
        var t=newTenant();
        var owner=verified(t.actorId());
        var role=roles.createRole(t.actorId(),t.tenantId(),
                "PII_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
                "CRM write only");
        roles.grant(t.actorId(),t.tenantId(),role.id(),DomainPermission.CRM_WRITE);
        UUID organization=jdbc.queryForObject("""
            SELECT organization_id FROM public.iam_workspace WHERE id=?
            """,UUID.class,t.tenantId());
        var writer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "tcrud02-writer-"+UUID.randomUUID(),"CRM limited writer"));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                organization,writer.getId(),OrganizationMembershipEntity.Role.MEMBER));
        roles.assign(t.actorId(),t.tenantId(),writer.getId(),role.id());
        roles.enable(t.actorId(),t.tenantId());
        var actor=verified(writer.getId());

        var person=secureCommands.createPerson(actor,t.tenantId(),"Private create person",null);
        assertThat(person.name()).isNull();
        assertThat(person.id()).isNotNull();
        var revised=secureCommands.updatePerson(actor,t.tenantId(),person.id(),
                "Private revised person",null,0);
        assertThat(revised.name()).isNull();

        var company=secureCommands.createCompany(actor,t.tenantId(),"Public company",null);
        // COMPANY organization_label is not personal name data.
        assertThat(company.name()).isEqualTo("Public company");
        var contact=secureCommands.addContact(actor,t.tenantId(),company.id(),
                "Secret contact","secret-write@example.kz","+77001112233");
        assertThat(contact.id()).isNotNull();
        assertThat(contact.name()).isNull();
        assertThat(contact.email()).isNull();
        assertThat(contact.phone()).isNull();
        var changed=secureCommands.updateContact(actor,t.tenantId(),contact.id(),
                "Changed secret","changed-write@example.kz",null,0);
        assertThat(changed.name()).isNull();
        assertThat(changed.email()).isNull();
        assertThat(changed.phone()).isNull();

        assertThatThrownBy(()->secureQueries.getCustomer(actor,t.tenantId(),person.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.getContact(actor,t.tenantId(),contact.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.contacts(actor,t.tenantId(),null,0,10))
                .isInstanceOf(SecurityException.class);
        byte[] stored=jdbc.queryForObject("""
            SELECT name_envelope FROM crm_pii.contact_secret WHERE tenant_id=? AND contact_id=?
            """,byte[].class,t.tenantId(),contact.id());
        assertThat(new String(stored,java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("Changed secret");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.contact_change_event
            WHERE tenant_id=? AND contact_id=?
            """,Integer.class,t.tenantId(),contact.id())).isEqualTo(2);
    }


    /** Same global actor, deliberately asymmetric grants in two tenant workspaces. */
    @Test void tcrud03MixedMembershipNeverCarriesCrmPiiOrWriteAcrossTenants() {
        var a=newTenant(); var b=newTenant();
        var ownerA=verified(a.actorId()); var ownerB=verified(b.actorId());
        var customerA=secureCommands.createCompany(ownerA,a.tenantId(),"Shared company",null);
        var customerB=secureCommands.createCompany(ownerB,b.tenantId(),"Shared company",null);
        var contactA=secureCommands.addContact(ownerA,a.tenantId(),customerA.id(),
                "Alpha","alpha@example.kz",null);
        var contactB=secureCommands.addContact(ownerB,b.tenantId(),customerB.id(),
                "Beta","beta@example.kz",null);
        var categoryA=categoryService.create(ownerA,a.tenantId(),"SHARED_CAT","A");
        var categoryB=categoryService.create(ownerB,b.tenantId(),"SHARED_CAT","B");
        var asOf=java.time.LocalDate.of(2026,11,22);
        var contractA=contractService.create(ownerA,a.tenantId(),customerA.id(),"SAME-01",
                asOf,asOf.plusDays(30),asOf);
        var contractB=contractService.create(ownerB,b.tenantId(),customerB.id(),"SAME-01",
                asOf,asOf.plusDays(30),asOf);

        UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "tcrud03-staff-"+UUID.randomUUID(),"Multi tenant staff")).getId();
        UUID orgA=jdbc.queryForObject("SELECT organization_id FROM iam_workspace WHERE id=?",
                UUID.class,a.tenantId());
        UUID orgB=jdbc.queryForObject("SELECT organization_id FROM iam_workspace WHERE id=?",
                UUID.class,b.tenantId());
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),orgA,
                staff,OrganizationMembershipEntity.Role.MEMBER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),orgB,
                staff,OrganizationMembershipEntity.Role.MEMBER));

        var roleA=roles.createRole(a.actorId(),a.tenantId(),
                "STAFF_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),"A CRM");
        roles.grant(a.actorId(),a.tenantId(),roleA.id(),DomainPermission.CRM_READ);
        roles.grant(a.actorId(),a.tenantId(),roleA.id(),DomainPermission.CRM_PII_READ);
        roles.grant(a.actorId(),a.tenantId(),roleA.id(),DomainPermission.CRM_WRITE);
        roles.assign(a.actorId(),a.tenantId(),staff,roleA.id());
        roles.enable(a.actorId(),a.tenantId());
        var roleB=roles.createRole(b.actorId(),b.tenantId(),
                "STAFF_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),"B metadata");
        roles.grant(b.actorId(),b.tenantId(),roleB.id(),DomainPermission.CRM_READ);
        roles.assign(b.actorId(),b.tenantId(),staff,roleB.id());
        roles.enable(b.actorId(),b.tenantId());

        var mixed=verified(staff);
        assertThat(secureQueries.getContact(mixed,a.tenantId(),contactA.id()).email())
                .isEqualTo("alpha@example.kz");
        var filter=new CustomerSearch("COMPANY","ACTIVE",null,"Shared company");
        assertThat(secureQueries.customers(mixed,a.tenantId(),filter,0,20).getContent())
                .extracting(CustomerSummary::id).contains(customerA.id()).doesNotContain(customerB.id());
        assertThat(secureQueries.customers(mixed,b.tenantId(),filter,0,20).getContent())
                .extracting(CustomerSummary::id).contains(customerB.id()).doesNotContain(customerA.id());
        assertThat(categoryService.get(mixed,a.tenantId(),categoryA.id()).id()).isEqualTo(categoryA.id());
        assertThat(categoryService.get(mixed,b.tenantId(),categoryB.id()).id()).isEqualTo(categoryB.id());
        assertThat(contractService.get(mixed,a.tenantId(),contractA.id(),asOf,30).id())
                .isEqualTo(contractA.id());
        assertThat(contractService.get(mixed,b.tenantId(),contractB.id(),asOf,30).id())
                .isEqualTo(contractB.id());
        assertThatThrownBy(()->secureQueries.getContact(mixed,b.tenantId(),contactB.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureQueries.contacts(mixed,b.tenantId(),null,0,10))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureCommands.updateCompany(mixed,b.tenantId(),customerB.id(),
                "Forbidden",null,0)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->categoryService.assignPrimary(mixed,b.tenantId(),
                customerB.id(),categoryB.id())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->contractService.changeDates(mixed,b.tenantId(),contractB.id(),
                asOf,asOf.plusDays(60),contractB.rowVersion(),asOf))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->secureCommands.archiveContact(mixed,a.tenantId(),contactB.id(),0))
                .isInstanceOf(IllegalStateException.class);
        assertThat(crmHistory.contactHistory(mixed,a.tenantId(),contactB.id(),10)).isEmpty();
        assertThatThrownBy(()->secureQueries.contactStatus(mixed,a.tenantId(),contactB.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
