package kz.rm.crm;

import kz.rm.iam.*;
import kz.rm.security.kms.TenantKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Two-tenant lifecycle and idempotency tests; no free-text/PII stored in interaction rows. */
@SpringBootTest
@TestPropertySource(properties={"RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class CrmInteractionQualificationTest {
    @Autowired CrmInteractionService interactions;
    @Autowired AuthenticatedCrmCommands customers;
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
