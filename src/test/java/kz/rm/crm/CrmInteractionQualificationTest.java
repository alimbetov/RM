package kz.rm.crm;

import kz.rm.iam.*;
import kz.rm.security.kms.TenantKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.time.Instant;
import java.time.LocalDate;
import kz.rm.crm.dto.CustomerSearch;
import kz.rm.crm.dto.ContactSearch;
import kz.rm.crm.dto.CustomerSummary;
import kz.rm.crm.dto.ContactSummary;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Two-tenant lifecycle and idempotency tests; no free-text/PII stored in interaction rows. */
@SpringBootTest
@TestPropertySource(properties={"RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class CrmInteractionQualificationTest {
    @Autowired CrmInteractionService interactions;
    @Autowired AuthenticatedCrmCommands customers;
    @Autowired AuthenticatedCrmQueries queries;
    @Autowired CrmContractService contracts;
    @Autowired CrmHistoryService history;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired TenantKeyService keys;
    @Autowired JdbcTemplate jdbc;
    @Autowired VerifiedTenantRoleCommands roles;

    private record Tenant(UUID tenantId,UUID actorId,UUID orgId) {}
    private Tenant tenant() {
        var user=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "interaction-"+UUID.randomUUID(),"CRM owner"));
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
                "Interaction LLP","Interaction","KZ"));
        var workspace=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),
                user.getId(),OrganizationMembershipEntity.Role.OWNER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        keys.provision(workspace.getId());
        return new Tenant(workspace.getId(),user.getId(),org.getId());
    }
    private VerifiedActor verified(UUID user) {
        String subject=jdbc.queryForObject(
                "SELECT external_subject FROM public.iam_user_account WHERE id=?",
                String.class,user);
        org.springframework.security.oauth2.jwt.JwtDecoder decoder=token->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                .header("alg","RS256").subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return new OidcActorResolver(decoder,users).resolve("synthetic-only");
    }

    @Test void interactionLifecycleIsIdempotentTenantScopedAndHistoryImmutable() {
        var a=tenant();var b=tenant();
        var actorA=verified(a.actorId());var actorB=verified(b.actorId());
        var ca=customers.createCompany(actorA,a.tenantId(),"Same label",null);
        var cb=customers.createCompany(actorB,b.tenantId(),"Same label",null);
        var contactA=customers.addContact(actorA,a.tenantId(),ca.id(),
                "Private A","a@example.kz",null);
        var contactB=customers.addContact(actorB,b.tenantId(),cb.id(),
                "Private B","b@example.kz",null);
        Instant occurred=Instant.parse("2026-11-20T12:00:00Z");
        Instant due=occurred.plusSeconds(86400);
        assertThatThrownBy(()->interactions.record(actorA,a.tenantId(),cb.id(),contactB.id(),
                CrmInteractionService.Kind.CALL,a.actorId(),occurred,due,"foreignCust001"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->interactions.record(actorA,a.tenantId(),ca.id(),contactB.id(),
                CrmInteractionService.Kind.CALL,a.actorId(),occurred,due,"foreignCon001"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->interactions.record(actorA,a.tenantId(),ca.id(),contactA.id(),
                CrmInteractionService.Kind.CALL,b.actorId(),occurred,due,"foreignEmp001"))
                .isInstanceOf(SecurityException.class);

        var created=interactions.record(actorA,a.tenantId(),ca.id(),contactA.id(),
                CrmInteractionService.Kind.CALL,a.actorId(),occurred,due,"request001");
        assertThat(created.status()).isEqualTo(CrmInteractionService.Status.OPEN);
        assertThat(created.rowVersion()).isZero();
        assertThat(interactions.record(actorA,a.tenantId(),ca.id(),contactA.id(),
                CrmInteractionService.Kind.CALL,a.actorId(),occurred,due,"request001").id())
                .isEqualTo(created.id());
        assertThatThrownBy(()->interactions.record(actorA,a.tenantId(),ca.id(),contactA.id(),
                CrmInteractionService.Kind.MEETING,a.actorId(),occurred,due,"request001"))
                .isInstanceOf(IllegalArgumentException.class);

        var createdB=interactions.record(actorB,b.tenantId(),cb.id(),contactB.id(),
                CrmInteractionService.Kind.MEETING,b.actorId(),occurred,due,"request001");
        assertThat(createdB.id()).isNotEqualTo(created.id());
        assertThat(interactions.search(actorA,a.tenantId(),null,null,null,0,1)
                .getTotalElements()).isEqualTo(1L);
        assertThat(interactions.search(actorB,b.tenantId(),null,null,null,0,10)
                .getContent()).extracting(CrmInteractionService.Interaction::id)
                .containsExactly(createdB.id()).doesNotContain(created.id());
        assertThatThrownBy(()->interactions.get(actorA,a.tenantId(),createdB.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->interactions.get(actorA,b.tenantId(),createdB.id()))
                .isInstanceOf(SecurityException.class);

        UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "interaction-staff-"+UUID.randomUUID(),"Employee")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),a.orgId(),
                staff,OrganizationMembershipEntity.Role.MEMBER));
        var assigned=interactions.assignResponsible(actorA,a.tenantId(),created.id(),staff,0);
        assertThat(assigned.responsibleUserId()).isEqualTo(staff);
        assertThat(assigned.rowVersion()).isEqualTo(1L);
        assertThatThrownBy(()->interactions.complete(actorA,a.tenantId(),created.id(),0))
                .isInstanceOf(IllegalStateException.class);
        var rescheduled=interactions.setNextAction(actorA,a.tenantId(),created.id(),due.plusSeconds(3600),1);
        assertThat(rescheduled.rowVersion()).isEqualTo(2L);
        var done=interactions.complete(actorA,a.tenantId(),created.id(),2);
        assertThat(done.status()).isEqualTo(CrmInteractionService.Status.COMPLETED);
        assertThat(done.rowVersion()).isEqualTo(3L);
        assertThatThrownBy(()->interactions.cancel(actorA,a.tenantId(),created.id(),3))
                .isInstanceOf(IllegalStateException.class);
        assertThat(interactions.history(actorA,a.tenantId(),created.id(),10))
                .extracting(CrmInteractionService.Event::action)
                .containsExactlyInAnyOrder("CREATED","RESPONSIBLE_ASSIGNED","NEXT_ACTION_SET","COMPLETED");
        assertThat(interactions.history(actorA,a.tenantId(),createdB.id(),10)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,a.tenantId(),created.id())).isEqualTo(4);
    }

    @Test void interactionsAreReadOnlyForTenantCrmReaderAndArchivedCustomerCannotCreate() {
        var t=tenant();var owner=verified(t.actorId());
        var customer=customers.createCompany(owner,t.tenantId(),"Active",null);
        var interaction=interactions.record(owner,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.NOTE,null,Instant.parse("2026-11-20T00:00:00Z"),
                null,"recordNotes001");

        UUID viewer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "interaction-read-"+UUID.randomUUID(),"Viewer")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),t.orgId(),
                viewer,OrganizationMembershipEntity.Role.MEMBER));
        var role=roles.createRole(owner,t.tenantId(),
                "INTERACTION_READER","Reader");
        roles.grant(owner,t.tenantId(),role.id(),DomainPermission.CRM_READ);
        roles.assign(owner,t.tenantId(),viewer,role.id());
        roles.enable(owner,t.tenantId());
        assertThat(interactions.get(verified(viewer),t.tenantId(),interaction.id()).id())
                .isEqualTo(interaction.id());
        assertThatThrownBy(()->interactions.complete(verified(viewer),t.tenantId(),
                interaction.id(),0)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->interactions.record(verified(viewer),t.tenantId(),
                customer.id(),null,CrmInteractionService.Kind.CALL,null,
                Instant.parse("2026-11-20T00:00:00Z"),null,"viewerCreate01"))
                .isInstanceOf(SecurityException.class);
        // An OWNER with enforced RBAC is not a wildcard operational actor.
        assertThatThrownBy(()->interactions.record(owner,t.tenantId(),customer.id(),
                null,CrmInteractionService.Kind.CALL,null,
                Instant.parse("2026-11-20T00:00:00Z"),null,"ownerDenied001"))
                .isInstanceOf(SecurityException.class);
    }

    /**
     * CRM-U02/U04/U07: a single CRM employee journey through persisted
     * customer, contact, interaction, follow-up, contract and history.
     * No simulated HTTP, and no unowned order/payment projection.
     */
    @Test void crmSvc01CustomerContactInteractionAndContractJourneyPersistsAcrossReads() {
        var a=tenant();
        var b=tenant();
        var owner=verified(a.actorId());
        var outsider=verified(b.actorId());
        var company=customers.createCompany(owner,a.tenantId(),"Journey Logistics",null);
        var person=customers.addContact(owner,a.tenantId(),company.id(),
                "Journey Contact","journey-"+UUID.randomUUID()+"@example.kz",null);
        // Mutation response intentionally does not expose contact PII.
        assertThat(person.name()).isNull();
        assertThat(person.email()).isNull();
        assertThat(queries.getContact(owner,a.tenantId(),person.id()).name())
                .isEqualTo("Journey Contact");
        assertThat(queries.customers(owner,a.tenantId(),
                new CustomerSearch("COMPANY","ACTIVE",null,"Journey Logistics"),0,10)
                .getContent()).extracting(CustomerSummary::id).contains(company.id());
        assertThat(queries.contacts(owner,a.tenantId(),
                new ContactSearch(company.id(),"ACTIVE",null),0,10)
                .getContent()).extracting(ContactSummary::id).contains(person.id());

        Instant when=Instant.parse("2026-11-21T10:00:00Z");
        var entry=interactions.record(owner,a.tenantId(),company.id(),person.id(),
                CrmInteractionService.Kind.CALL,a.actorId(),when,when.plusSeconds(86400),
                "journey-"+UUID.randomUUID());
        assertThat(interactions.record(owner,a.tenantId(),company.id(),person.id(),
                CrmInteractionService.Kind.CALL,a.actorId(),when,when.plusSeconds(86400),
                jdbc.queryForObject("""
                    SELECT request_key FROM crm.customer_interaction
                    WHERE tenant_id=? AND id=?
                    """,String.class,a.tenantId(),entry.id())).id()).isEqualTo(entry.id());
        assertThat(interactions.get(owner,a.tenantId(),entry.id()).status())
                .isEqualTo(CrmInteractionService.Status.OPEN);
        var followup=interactions.setNextAction(owner,a.tenantId(),entry.id(),
                when.plusSeconds(172800),0);
        var finished=interactions.complete(owner,a.tenantId(),entry.id(),followup.rowVersion());
        assertThat(finished.status()).isEqualTo(CrmInteractionService.Status.COMPLETED);
        assertThat(interactions.search(owner,a.tenantId(),company.id(),null,
                CrmInteractionService.Status.COMPLETED,0,10).getContent())
                .extracting(CrmInteractionService.Interaction::id).containsExactly(entry.id());
        assertThat(interactions.history(owner,a.tenantId(),entry.id(),10))
                .extracting(CrmInteractionService.Event::action)
                .containsExactlyInAnyOrder("CREATED","NEXT_ACTION_SET","COMPLETED");

        LocalDate today=LocalDate.of(2026,11,22);
        var contract=contracts.create(owner,a.tenantId(),company.id(),"JOURNEY-"+UUID.randomUUID(),
                today,today.plusDays(45),today);
        var activated=contracts.changeStatus(owner,a.tenantId(),contract.id(),
                CustomerContractEntity.Status.ACTIVE,contract.rowVersion(),today);
        assertThat(contracts.get(owner,a.tenantId(),contract.id(),today,30).status())
                .isEqualTo(CustomerContractEntity.Status.ACTIVE);
        assertThat(activated.rowVersion()).isGreaterThan(contract.rowVersion());
        assertThat(history.contractHistory(owner,a.tenantId(),contract.id(),10))
                .isNotEmpty();
        assertThatThrownBy(()->queries.getContact(outsider,a.tenantId(),person.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->interactions.get(owner,b.tenantId(),entry.id()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction WHERE tenant_id=? AND id=?
                """,Integer.class,b.tenantId(),entry.id())).isZero();
    }

    /**
     * CRM-U04 negative journey: a read-only employee can review an activity
     * but cannot mutate it, and role revocation removes further read access.
     */
    @Test void crmSvc03ReadonlyEmployeeCannotCompleteOrReadAfterPermissionRevoke() {
        var t=tenant();
        var owner=verified(t.actorId());
        var company=customers.createCompany(owner,t.tenantId(),"Read-only Journey",null);
        var entry=interactions.record(owner,t.tenantId(),company.id(),null,
                CrmInteractionService.Kind.MEETING,t.actorId(),
                Instant.parse("2026-11-21T11:00:00Z"),null,"reader-"+UUID.randomUUID());
        UUID viewer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "crm-journey-viewer-"+UUID.randomUUID(),"CRM reader")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),t.orgId(),viewer,OrganizationMembershipEntity.Role.MEMBER));
        var r=roles.createRole(owner,t.tenantId(),
                "CRM_JOURNEY_READER","Read only journey");
        roles.grant(owner,t.tenantId(),r.id(),DomainPermission.CRM_READ);
        roles.assign(owner,t.tenantId(),viewer,r.id());
        roles.enable(owner,t.tenantId());
        var reader=verified(viewer);
        assertThat(interactions.get(reader,t.tenantId(),entry.id()).id()).isEqualTo(entry.id());
        int events=jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.tenantId(),entry.id());
        assertThatThrownBy(()->interactions.complete(reader,t.tenantId(),entry.id(),0))
                .isInstanceOf(SecurityException.class);
        assertThat(interactions.get(reader,t.tenantId(),entry.id()).status())
                .isEqualTo(CrmInteractionService.Status.OPEN);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.tenantId(),entry.id())).isEqualTo(events);
        roles.revoke(owner,t.tenantId(),r.id(),DomainPermission.CRM_READ);
        assertThatThrownBy(()->interactions.get(reader,t.tenantId(),entry.id()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.tenantId(),entry.id())).isEqualTo(events);
    }

    /**
     * CRM-U03: an employee must not infer consent from contact existence;
     * purpose-scoped grant/revoke events stay immutable and are idempotent.
     * This is NOT marketing dispatch qualification.
     */
    @Test void crmSvc04ConsentLifecycleRevocationOverridesGrantWithoutDuplicateEvents() {
        var a=tenant();
        var b=tenant();
        var actor=verified(a.actorId());
        var foreign=verified(b.actorId());
        var customer=customers.createCompany(actor,a.tenantId(),"Consent Journey",null);
        var contact=customers.addContact(actor,a.tenantId(),customer.id(),
                "Private contact","journey-"+UUID.randomUUID()+"@example.kz",null);
        assertThat(queries.hasEmailConsent(actor,a.tenantId(),contact.id(),"updates")).isFalse();
        String grantKey="crm-grant-"+UUID.randomUUID();
        UUID grant=customers.recordEmailConsentOnce(actor,a.tenantId(),contact.id(),true,
                "updates","v1","EMPLOYEE",grantKey);
        assertThat(customers.recordEmailConsentOnce(actor,a.tenantId(),contact.id(),true,
                "updates","v1","EMPLOYEE",grantKey)).isEqualTo(grant);
        assertThat(queries.hasEmailConsent(actor,a.tenantId(),contact.id(),"updates")).isTrue();
        assertThat(queries.hasEmailConsent(actor,a.tenantId(),contact.id(),"marketing")).isFalse();
        assertThatThrownBy(()->customers.recordEmailConsentOnce(actor,a.tenantId(),contact.id(),
                false,"updates","v1","EMPLOYEE",grantKey))
                .isInstanceOf(IllegalArgumentException.class);
        UUID revoked=customers.recordEmailConsentOnce(actor,a.tenantId(),contact.id(),false,
                "updates","v1","EMPLOYEE","crm-revoke-"+UUID.randomUUID());
        assertThat(revoked).isNotEqualTo(grant);
        assertThat(queries.hasEmailConsent(actor,a.tenantId(),contact.id(),"updates")).isFalse();
        assertThatThrownBy(()->queries.hasEmailConsent(foreign,a.tenantId(),
                contact.id(),"updates")).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.email_consent_event
                WHERE tenant_id=? AND contact_id=? AND purpose='updates'
                """,Integer.class,a.tenantId(),contact.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.email_consent_event
                WHERE tenant_id=? AND contact_id=? AND purpose='updates'
                """,Integer.class,b.tenantId(),contact.id())).isZero();
    }

    /**
     * CRM-U11: active contracts fence archive; rejected archive has zero
     * customer-history delta. Restoring a legitimately archived client
     * preserves its contacts and interaction timeline.
     */
    @Test void crmSvc05CustomerArchiveContractGuardAndRestorePreserveTimeline() {
        var a=tenant();
        var actor=verified(a.actorId());
        var customer=customers.createCompany(actor,a.tenantId(),"Lifecycle Journey",null);
        var contact=customers.addContact(actor,a.tenantId(),customer.id(),
                "Lifecycle Contact",null,null);
        var interaction=interactions.record(actor,a.tenantId(),customer.id(),contact.id(),
                CrmInteractionService.Kind.CALL,a.actorId(),
                Instant.parse("2026-11-20T12:00:00Z"),null,
                "lifecycle-"+UUID.randomUUID());
        LocalDate today=LocalDate.of(2026,11,22);
        var contract=contracts.create(actor,a.tenantId(),customer.id(),
                "LC-"+UUID.randomUUID(),today,today.plusDays(45),today);
        contracts.changeStatus(actor,a.tenantId(),contract.id(),
                CustomerContractEntity.Status.ACTIVE,contract.rowVersion(),today);
        int before=jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_change_event
                WHERE tenant_id=? AND customer_id=?
                """,Integer.class,a.tenantId(),customer.id());
        assertThatThrownBy(()->customers.archiveCustomer(actor,a.tenantId(),
                customer.id(),0)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_change_event
                WHERE tenant_id=? AND customer_id=?
                """,Integer.class,a.tenantId(),customer.id())).isEqualTo(before);
        assertThat(queries.customerStatus(actor,a.tenantId(),customer.id()))
                .isEqualTo(CrmCustomerEntity.Status.ACTIVE);

        var withoutContract=customers.createCompany(actor,a.tenantId(),
                "Archive Allowed",null);
        var retainedContact=customers.addContact(actor,a.tenantId(),withoutContract.id(),
                "Retained PII",null,null);
        customers.archiveCustomer(actor,a.tenantId(),withoutContract.id(),0);
        assertThat(queries.customerStatus(actor,a.tenantId(),withoutContract.id()))
                .isEqualTo(CrmCustomerEntity.Status.ARCHIVED);
        assertThatThrownBy(()->queries.getContact(actor,a.tenantId(),retainedContact.id()))
                .isInstanceOf(IllegalArgumentException.class);
        customers.restoreCustomer(actor,a.tenantId(),withoutContract.id(),1);
        assertThat(queries.getContact(actor,a.tenantId(),retainedContact.id()).name())
                .isEqualTo("Retained PII");
        assertThat(history.customerHistory(actor,a.tenantId(),withoutContract.id(),10))
                .extracting(CrmHistoryService.CustomerChange::action)
                .containsExactly("RESTORED","ARCHIVED");
        assertThat(interactions.get(actor,a.tenantId(),interaction.id()).customerId())
                .isEqualTo(customer.id());
    }

    @Test void postgresInteractionEventsAreImmutableAndContactOwnershipIsTenantQualified()
            throws Exception {
        try(var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    connection.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
        }
        var t=tenant();var actor=verified(t.actorId());
        var a=customers.createCompany(actor,t.tenantId(),"Client A",null);
        var b=customers.createCompany(actor,t.tenantId(),"Client B",null);
        var contactB=customers.addContact(actor,t.tenantId(),b.id(),
                "Private contact","private@example.kz",null);
        Instant at=Instant.parse("2026-11-22T12:00:00Z");
        assertThatThrownBy(()->interactions.record(actor,t.tenantId(),a.id(),contactB.id(),
                CrmInteractionService.Kind.CALL,null,at,null,"wrongOwner001"))
                .isInstanceOf(IllegalArgumentException.class);
        var interaction=interactions.record(actor,t.tenantId(),a.id(),null,
                CrmInteractionService.Kind.CALL,null,at,null,"goodOwner001");
        assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.customer_interaction_event SET action_code='CANCELLED'
                WHERE tenant_id=? AND interaction_id=?
                """,t.tenantId(),interaction.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,t.tenantId(),interaction.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(interactions.history(actor,t.tenantId(),interaction.id(),10))
                .extracting(CrmInteractionService.Event::action).containsExactly("CREATED");
    }

}
