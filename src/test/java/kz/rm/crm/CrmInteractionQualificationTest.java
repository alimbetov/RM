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

    /**
     * CRM-99 P0 consent safety: a previously consented contact or parent
     * cannot remain eligible for communications after archival.
     */
    @Test void crm99ArchivedContactAndParentNeverRemainCommunicationEligible() {
        var t=tenant();
        var owner=verified(t.actorId());
        var customer=customers.createCompany(owner,t.tenantId(),
                "Archive Consent Safety",null);
        var contact=customers.addContact(owner,t.tenantId(),customer.id(),
                "Consent Subject",null,null);
        UUID grant=customers.recordEmailConsentOnce(owner,t.tenantId(),contact.id(),
                true,"transactional","v1","CUSTOMER_FORM",
                "c99-grant-"+UUID.randomUUID());
        assertThat(grant).isNotNull();
        assertThat(queries.hasEmailConsent(owner,t.tenantId(),
                contact.id(),"transactional")).isTrue();
        customers.archiveContact(owner,t.tenantId(),contact.id(),0);
        assertThat(queries.hasEmailConsent(owner,t.tenantId(),
                contact.id(),"transactional")).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.email_consent_event
                WHERE tenant_id=? AND contact_id=?
                """,Integer.class,t.tenantId(),contact.id())).isEqualTo(1);

        var second=customers.addContact(owner,t.tenantId(),customer.id(),
                "Second Subject",null,null);
        customers.recordEmailConsentOnce(owner,t.tenantId(),second.id(),true,
                "transactional","v1","CUSTOMER_FORM",
                "c99-parent-"+UUID.randomUUID());
        assertThat(queries.hasEmailConsent(owner,t.tenantId(),
                second.id(),"transactional")).isTrue();
        customers.archiveCustomer(owner,t.tenantId(),customer.id(),0);
        assertThat(queries.hasEmailConsent(owner,t.tenantId(),
                second.id(),"transactional")).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.email_consent_event
                WHERE tenant_id=? AND contact_id=?
                """,Integer.class,t.tenantId(),second.id())).isEqualTo(1);
    }

    /**
     * CRM-99 / human behavior: an operator may not assign responsibility
     * to a suspended or foreign employee, and a failed assignment must not
     * advance revision or create an immutable audit event.
     */
    @Test void crm99ForeignAndSuspendedAssigneeProduceZeroInteractionSideEffects() {
        var a=tenant();
        var b=tenant();
        var owner=verified(a.actorId());
        var company=customers.createCompany(owner,a.tenantId(),"Assignment Safety",null);
        var interaction=interactions.record(owner,a.tenantId(),company.id(),null,
                CrmInteractionService.Kind.MEETING,a.actorId(),
                Instant.parse("2026-11-22T09:00:00Z"),null,
                "c99-assign-"+UUID.randomUUID());
        int before=jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,a.tenantId(),interaction.id());
        assertThatThrownBy(()->interactions.assignResponsible(owner,a.tenantId(),
                interaction.id(),b.actorId(),0)).isInstanceOf(SecurityException.class);

        UUID member=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "c99-inactive-"+UUID.randomUUID(),"Suspended agent")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                a.orgId(),member,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",member);
        assertThatThrownBy(()->interactions.assignResponsible(owner,a.tenantId(),
                interaction.id(),member,0)).isInstanceOf(SecurityException.class);
        var persisted=interactions.get(owner,a.tenantId(),interaction.id());
        assertThat(persisted.rowVersion()).isZero();
        assertThat(persisted.responsibleUserId()).isEqualTo(a.actorId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,a.tenantId(),interaction.id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,b.tenantId(),interaction.id())).isZero();
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

    /**
     * CRM-R2-A03/A07: archived parent denies new work and rescheduling,
     * while an already committed identical request remains replayable.
     * Failed commands must preserve row version and immutable event count.
     */
    @Test void crmR2ArchivedCustomerFencesNewAndChangedInteractionsButKeepsReplay() {
        var t=tenant();
        var actor=verified(t.actorId());
        var customer=customers.createCompany(actor,t.tenantId(),"R2 Parent Archive",null);
        Instant at=Instant.parse("2026-11-21T11:00:00Z");
        Instant followup=at.plusSeconds(86400);
        String key="r2-parent-"+UUID.randomUUID();
        var created=interactions.record(actor,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.CALL,t.actorId(),at,followup,key);
        int before=jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.tenantId(),created.id());

        customers.archiveCustomer(actor,t.tenantId(),customer.id(),0);
        assertThat(interactions.record(actor,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.CALL,t.actorId(),at,followup,key).id())
                .isEqualTo(created.id());
        assertThatThrownBy(()->interactions.record(actor,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.CALL,t.actorId(),at,followup,
                "r2-new-"+UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->interactions.assignResponsible(actor,t.tenantId(),
                created.id(),t.actorId(),0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->interactions.setNextAction(actor,t.tenantId(),
                created.id(),followup.plusSeconds(3600),0))
                .isInstanceOf(IllegalArgumentException.class);

        var persisted=interactions.get(actor,t.tenantId(),created.id());
        assertThat(persisted.rowVersion()).isZero();
        assertThat(persisted.status()).isEqualTo(CrmInteractionService.Status.OPEN);
        assertThat(persisted.nextActionAt()).isEqualTo(followup);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.tenantId(),created.id())).isEqualTo(before);
    }

    /** CRM-R2-D02: a duplicate idempotency key with changed intent must not mutate state. */
    @Test void crmR2ChangedReplayPayloadKeepsSingleInteractionAndEvent() {
        var t=tenant();
        var actor=verified(t.actorId());
        var customer=customers.createCompany(actor,t.tenantId(),"Replay Boundary",null);
        Instant at=Instant.parse("2026-11-21T10:00:00Z");
        String key="r2-conflict-"+UUID.randomUUID();
        var original=interactions.record(actor,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.CALL,t.actorId(),at,null,key);
        assertThatThrownBy(()->interactions.record(actor,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.EMAIL,t.actorId(),at,null,key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(interactions.record(actor,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.CALL,t.actorId(),at,null,key).id())
                .isEqualTo(original.id());
        assertThat(interactions.get(actor,t.tenantId(),original.id()).rowVersion()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction
                WHERE tenant_id=? AND request_key=?
                """,Integer.class,t.tenantId(),key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.tenantId(),original.id())).isEqualTo(1);
    }

    /** CRM-R2-D04/D05: final states are mutually exclusive and cannot add events on retry. */
    @Test void crmR2TerminalTransitionRejectsOppositeAndDuplicateWithoutSideEffects() {
        var t=tenant();
        var actor=verified(t.actorId());
        var customer=customers.createCompany(actor,t.tenantId(),"Terminal Boundary",null);
        var interaction=interactions.record(actor,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.MEETING,t.actorId(),
                Instant.parse("2026-11-21T09:00:00Z"),null,
                "r2-terminal-"+UUID.randomUUID());
        var cancelled=interactions.cancel(actor,t.tenantId(),interaction.id(),0);
        assertThat(cancelled.status()).isEqualTo(CrmInteractionService.Status.CANCELLED);
        assertThat(cancelled.rowVersion()).isEqualTo(1);
        int before=jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.tenantId(),interaction.id());
        assertThatThrownBy(()->interactions.complete(actor,t.tenantId(),interaction.id(),1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->interactions.cancel(actor,t.tenantId(),interaction.id(),1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(interactions.get(actor,t.tenantId(),interaction.id()).rowVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.tenantId(),interaction.id())).isEqualTo(before);
    }

    /** CRM-R2-D06/D07: invalid revision and stale follow-up do not change deadline or audit. */
    @Test void crmR2InvalidAndStaleMutationPreservesFollowupAndAudit() {
        var t=tenant();
        var actor=verified(t.actorId());
        var customer=customers.createCompany(actor,t.tenantId(),"Stale Boundary",null);
        Instant at=Instant.parse("2026-11-20T09:00:00Z");
        Instant due=at.plusSeconds(86400);
        var interaction=interactions.record(actor,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.CALL,t.actorId(),at,due,
                "r2-stale-"+UUID.randomUUID());
        var moved=interactions.setNextAction(actor,t.tenantId(),interaction.id(),
                due.plusSeconds(3600),0);
        int before=jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.tenantId(),interaction.id());
        assertThatThrownBy(()->interactions.assignResponsible(actor,t.tenantId(),
                interaction.id(),t.actorId(),-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->interactions.setNextAction(actor,t.tenantId(),
                interaction.id(),due.plusSeconds(7200),0))
                .isInstanceOf(IllegalStateException.class);
        var latest=interactions.get(actor,t.tenantId(),interaction.id());
        assertThat(latest.rowVersion()).isEqualTo(moved.rowVersion());
        assertThat(latest.nextActionAt()).isEqualTo(moved.nextActionAt());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.tenantId(),interaction.id())).isEqualTo(before);
    }

    /**
     * CRM-R2.1B / D04: real PostgreSQL threads compete for the same row version.
     * Exactly one terminal command commits, with exactly one additional audit event.
     */
    @Test void crmR21bPostgresConcurrentCompleteAndCancelAreSingleWinner() throws Exception {
        try (var cn=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    cn.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
        }
        var t=tenant(); var actor=verified(t.actorId());
        var customer=customers.createCompany(actor,t.tenantId(),"Parallel Terminal",null);
        var created=interactions.record(actor,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.CALL,t.actorId(),
                Instant.parse("2026-11-21T10:00:00Z"),null,
                "parallel-terminal-"+UUID.randomUUID());
        var begin=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> complete=()->{
                if(!begin.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Concurrent barrier timeout");
                try { interactions.complete(actor,t.tenantId(),created.id(),0); return true; }
                catch(IllegalStateException expected) { return false; }
            };
            java.util.concurrent.Callable<Boolean> cancel=()->{
                if(!begin.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Concurrent barrier timeout");
                try { interactions.cancel(actor,t.tenantId(),created.id(),0); return true; }
                catch(IllegalStateException expected) { return false; }
            };
            var first=pool.submit(complete);
            var second=pool.submit(cancel);
            begin.countDown();
            boolean a=first.get(20,java.util.concurrent.TimeUnit.SECONDS);
            boolean b=second.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a ^ b).isTrue();
            var persisted=interactions.get(actor,t.tenantId(),created.id());
            assertThat(persisted.rowVersion()).isEqualTo(1);
            assertThat(persisted.status()).isIn(
                    CrmInteractionService.Status.COMPLETED,
                    CrmInteractionService.Status.CANCELLED);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.customer_interaction_event
                    WHERE tenant_id=? AND interaction_id=?
                    """,Integer.class,t.tenantId(),created.id())).isEqualTo(2);
            assertThat(interactions.history(actor,t.tenantId(),created.id(),10))
                    .extracting(CrmInteractionService.Event::action)
                    .containsExactlyInAnyOrder("CREATED",
                            persisted.status()==CrmInteractionService.Status.COMPLETED
                                    ?"COMPLETED":"CANCELLED");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * CRM-R2.1B / A07: archive and creation run in distinct PostgreSQL
     * transactions. The archive must commit; a concurrent record can either
     * precede it or fail, but subsequent new interactions always fail.
     */
    @Test void crmR21bPostgresConcurrentArchiveAndRecordFenceNewActivity() throws Exception {
        try (var cn=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    cn.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
        }
        var t=tenant();var actor=verified(t.actorId());
        var customer=customers.createCompany(actor,t.tenantId(),"Archive Race",null);
        var started=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        String requestKey="parallel-archive-"+UUID.randomUUID();
        try {
            var archive=pool.submit(()->{
                if(!started.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Concurrent barrier timeout");
                customers.archiveCustomer(actor,t.tenantId(),customer.id(),0);
                return true;
            });
            var record=pool.submit(()->{
                if(!started.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Concurrent barrier timeout");
                try {
                    return interactions.record(actor,t.tenantId(),customer.id(),null,
                            CrmInteractionService.Kind.CALL,t.actorId(),
                            Instant.parse("2026-11-21T12:00:00Z"),null,requestKey).id();
                } catch(IllegalArgumentException expected) {
                    return (UUID)null;
                }
            });
            started.countDown();
            assertThat(archive.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            UUID created=record.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(queries.customerStatus(actor,t.tenantId(),customer.id()))
                    .isEqualTo(CrmCustomerEntity.Status.ARCHIVED);
            int count=jdbc.queryForObject("""
                    SELECT count(*) FROM crm.customer_interaction
                    WHERE tenant_id=? AND customer_id=? AND request_key=?
                    """,Integer.class,t.tenantId(),customer.id(),requestKey);
            assertThat(count).isEqualTo(created==null?0:1);
            assertThatThrownBy(()->interactions.record(actor,t.tenantId(),customer.id(),null,
                    CrmInteractionService.Kind.CALL,t.actorId(),
                    Instant.parse("2026-11-21T12:00:00Z"),null,
                    "new-after-archive-"+UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.customer_interaction_event e
                    JOIN crm.customer_interaction i
                      ON i.tenant_id=e.tenant_id AND i.id=e.interaction_id
                    WHERE i.tenant_id=? AND i.customer_id=?
                    """,Integer.class,t.tenantId(),customer.id())).isEqualTo(count);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * CRM-R2.1B / E03: concurrent membership suspension and assignment.
     * This smoke establishes admissible outcomes and post-revocation denial.
     * It does NOT by itself prove strict admission/commit ordering; a controlled
     * two-transaction fencing test remains mandatory for full qualification.
     */
    @Test void crmR21bPostgresConcurrentAssignAndMemberSuspendRejectsSubsequentAssignment()
            throws Exception {
        try (var cn=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    cn.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
        }
        var t=tenant();var actor=verified(t.actorId());
        UUID employee=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "parallel-staff-"+UUID.randomUUID(),"Parallel employee")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                t.orgId(),employee,OrganizationMembershipEntity.Role.MEMBER));
        var customer=customers.createCompany(actor,t.tenantId(),"Revoke Race",null);
        var created=interactions.record(actor,t.tenantId(),customer.id(),null,
                CrmInteractionService.Kind.CALL,t.actorId(),
                Instant.parse("2026-11-21T08:00:00Z"),null,
                "parallel-revoke-"+UUID.randomUUID());
        var begin=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var suspend=pool.submit(()->{
                if(!begin.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Concurrent barrier timeout");
                return jdbc.update("""
                        UPDATE public.iam_org_membership
                        SET status='SUSPENDED', row_version=row_version+1
                        WHERE organization_id=? AND user_id=?
                        """,t.orgId(),employee);
            });
            var assign=pool.submit(()->{
                if(!begin.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Concurrent barrier timeout");
                try {
                    interactions.assignResponsible(actor,t.tenantId(),
                            created.id(),employee,0);
                    return true;
                } catch(SecurityException expected) {
                    return false;
                }
            });
            begin.countDown();
            assertThat(suspend.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
            boolean assigned=assign.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var persisted=interactions.get(actor,t.tenantId(),created.id());
            assertThat(persisted.rowVersion()).isEqualTo(assigned?1:0);
            assertThat(persisted.responsibleUserId()).isEqualTo(
                    assigned?employee:t.actorId());
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crm.customer_interaction_event
                    WHERE tenant_id=? AND interaction_id=?
                    """,Integer.class,t.tenantId(),created.id())).isEqualTo(assigned?2:1);
            assertThatThrownBy(()->interactions.assignResponsible(actor,t.tenantId(),
                    created.id(),employee,persisted.rowVersion()))
                    .isInstanceOf(SecurityException.class);
        } finally {
            pool.shutdownNow();
        }
    }

}
