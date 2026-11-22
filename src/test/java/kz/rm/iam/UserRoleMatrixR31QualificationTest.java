package kz.rm.iam;

import kz.rm.storefront.StorefrontManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * UR-R3.1: service-only authorization matrix. No controller, MockMvc or
 * external identity provider. Test cases are linked in the UR-R3 registry.
 * Every denied mutation is checked against the authoritative store and audit.
 */
@SpringBootTest
class UserRoleMatrixR31QualificationTest {
    @Autowired TenantAuthorization authorization;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired StorefrontManagementService storefront;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;

    private record Tenant(UUID tenant, UUID organization, UUID owner,
                          Map<OrganizationMembershipEntity.Role,UUID> principals) {
        UUID actor(OrganizationMembershipEntity.Role role) {
            return principals.get(role);
        }
    }

    private UUID user(String label) {
        return users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"ur-r31-"+label+"-"+UUID.randomUUID(),label)).getId();
    }

    private VerifiedActor principal(UUID actor) {
        return new VerifiedActor(actor,Instant.now().plusSeconds(900));
    }

    private Tenant tenant() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"UR R3.1 qualification","UR R3.1","KZ"));
        var workspace=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        UUID owner=user("owner");
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        var people=new EnumMap<OrganizationMembershipEntity.Role,UUID>(
                OrganizationMembershipEntity.Role.class);
        for(var role:OrganizationMembershipEntity.Role.values()) {
            UUID staff=user(role.name().toLowerCase());
            memberships.saveAndFlush(new OrganizationMembershipEntity(
                    UUID.randomUUID(),org.getId(),staff,role));
            people.put(role,staff);
        }
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        return new Tenant(workspace.getId(),org.getId(),owner,people);
    }

    private UUID grant(Tenant t,OrganizationMembershipEntity.Role role,
                       DomainPermission permission) {
        var created=roles.createRole(principal(t.owner()),t.tenant(),
                "UR_"+UUID.randomUUID().toString().replace("-","").substring(0,14).toUpperCase(),
                "UR R3.1 limited permission");
        roles.grant(principal(t.owner()),t.tenant(),created.id(),permission);
        roles.assign(principal(t.owner()),t.tenant(),t.actor(role),created.id());
        return created.id();
    }

    private long countStorefronts(Tenant t) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM crm.store_front WHERE tenant_id=?",
                Long.class,t.tenant());
    }

    private long successAudit(Tenant t) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM crm.audit_event
                WHERE tenant_id=? AND action_code='STOREFRONT_CREATED'
                """,Long.class,t.tenant());
    }

    private void assertDeniedWithoutBusinessWrite(Tenant t,UUID actor,String slug) {
        long before=countStorefronts(t);
        long auditBefore=successAudit(t);
        assertThatThrownBy(()->storefront.createDraft(principal(actor),t.tenant(),slug))
                .isInstanceOf(SecurityException.class);
        assertThat(countStorefronts(t)).isEqualTo(before);
        assertThat(successAudit(t)).isEqualTo(auditBefore);
    }

    @Test
    void urR31AllFiveMembershipRolesHaveFallbackReadButNoImplicitWrite() {
        Tenant t=tenant();
        for(var role:OrganizationMembershipEntity.Role.values()) {
            UUID actor=t.actor(role);
            assertThat(authorization.require(actor,t.tenant(),DomainPermission.CRM_READ)
                    .permission()).isEqualTo(DomainPermission.CRM_READ);
            assertThatThrownBy(()->authorization.require(
                    actor,t.tenant(),DomainPermission.STOREFRONT_OFFER_MANAGE))
                    .as("No implicit storefront management for "+role)
                    .isInstanceOf(SecurityException.class);
            assertDeniedWithoutBusinessWrite(t,actor,
                    "denied-"+role.name().toLowerCase());
        }
        assertThat(authorization.require(t.owner(),t.tenant(),
                DomainPermission.TENANT_RBAC_MANAGE).role())
                .isEqualTo(OrganizationMembershipEntity.Role.OWNER);
        assertThat(countStorefronts(t)).isZero();
    }

    @Test
    void urR31ExplicitGrantsAuthorizeEachRoleAndRevocationFencesNextCommand() {
        for(var membership:OrganizationMembershipEntity.Role.values()) {
            Tenant t=tenant();
            UUID actor=t.actor(membership);
            UUID roleId=grant(t,membership,DomainPermission.STOREFRONT_OFFER_MANAGE);
            roles.enable(principal(t.owner()),t.tenant());
            assertThatThrownBy(()->authorization.require(actor,t.tenant(),
                    DomainPermission.SALES_ORDER_READ)).isInstanceOf(SecurityException.class);
            var created=storefront.createDraft(principal(actor),t.tenant(),
                    "allowed-"+UUID.randomUUID().toString().substring(0,12));
            assertThat(created.tenantId()).isEqualTo(t.tenant());
            assertThat(countStorefronts(t)).isEqualTo(1);
            assertThat(successAudit(t)).isEqualTo(1);
            roles.revoke(principal(t.owner()),t.tenant(),roleId,
                    DomainPermission.STOREFRONT_OFFER_MANAGE);
            assertDeniedWithoutBusinessWrite(t,actor,
                    "revoked-"+UUID.randomUUID().toString().substring(0,12));
            // Even an explicitly assigned functional role cannot delegate owner governance.
            if(membership!=OrganizationMembershipEntity.Role.OWNER)
                assertThatThrownBy(()->authorization.require(actor,t.tenant(),
                        DomainPermission.TENANT_RBAC_MANAGE))
                        .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void urR31TenantAAndBEnforceIndependentGrantsAndNoForeignWrites() {
        Tenant a=tenant(),b=tenant();
        UUID aManager=a.actor(OrganizationMembershipEntity.Role.MANAGER);
        UUID bViewer=b.actor(OrganizationMembershipEntity.Role.VIEWER);
        grant(a,OrganizationMembershipEntity.Role.MANAGER,
                DomainPermission.STOREFRONT_OFFER_MANAGE);
        grant(b,OrganizationMembershipEntity.Role.VIEWER,
                DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.enable(principal(a.owner()),a.tenant());
        roles.enable(principal(b.owner()),b.tenant());
        assertDeniedWithoutBusinessWrite(a,bViewer,"foreign-b");
        assertDeniedWithoutBusinessWrite(b,aManager,"foreign-a");
        storefront.createDraft(principal(aManager),a.tenant(),"a-storefront");
        storefront.createDraft(principal(bViewer),b.tenant(),"b-storefront");
        assertThat(countStorefronts(a)).isEqualTo(1);
        assertThat(countStorefronts(b)).isEqualTo(1);
        assertThat(successAudit(a)).isEqualTo(1);
        assertThat(successAudit(b)).isEqualTo(1);
    }

    @Test
    void urR31DualTenantMemberHasDifferentPermissionsAndRevocationIsLocal() {
        Tenant a=tenant(),b=tenant();
        UUID shared=a.actor(OrganizationMembershipEntity.Role.MEMBER);
        memberships.saveAndFlush(new OrganizationMembershipEntity(
                UUID.randomUUID(),b.organization(),shared,OrganizationMembershipEntity.Role.VIEWER));
        UUID aRole=grant(a,OrganizationMembershipEntity.Role.MEMBER,
                DomainPermission.STOREFRONT_OFFER_MANAGE);
        grant(b,OrganizationMembershipEntity.Role.MANAGER,
                DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.enable(principal(a.owner()),a.tenant());
        roles.enable(principal(b.owner()),b.tenant());
        storefront.createDraft(principal(shared),a.tenant(),"shared-a");
        assertDeniedWithoutBusinessWrite(b,shared,"shared-b-denied");
        roles.revoke(principal(a.owner()),a.tenant(),aRole,
                DomainPermission.STOREFRONT_OFFER_MANAGE);
        assertDeniedWithoutBusinessWrite(a,shared,"shared-a-revoked");
        assertDeniedWithoutBusinessWrite(b,shared,"shared-b-still-denied");
        assertThat(countStorefronts(a)).isEqualTo(1);
        assertThat(countStorefronts(b)).isZero();
    }

    @Test
    void urR31SuspendedActorAndMembershipCannotWriteDespiteExistingGrant() {
        Tenant suspendedAccount=tenant();
        UUID account=suspendedAccount.actor(OrganizationMembershipEntity.Role.MEMBER);
        grant(suspendedAccount,OrganizationMembershipEntity.Role.MEMBER,
                DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.enable(principal(suspendedAccount.owner()),suspendedAccount.tenant());
        jdbc.update("UPDATE iam_user_account SET status='SUSPENDED' WHERE id=?",account);
        assertDeniedWithoutBusinessWrite(suspendedAccount,account,"account-suspended");

        Tenant suspendedMembership=tenant();
        UUID member=suspendedMembership.actor(OrganizationMembershipEntity.Role.ADMIN);
        grant(suspendedMembership,OrganizationMembershipEntity.Role.ADMIN,
                DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.enable(principal(suspendedMembership.owner()),suspendedMembership.tenant());
        jdbc.update("""
                UPDATE iam_org_membership SET status='SUSPENDED'
                WHERE organization_id=? AND user_id=?
                """,suspendedMembership.organization(),member);
        assertDeniedWithoutBusinessWrite(suspendedMembership,member,"membership-suspended");

        Tenant suspendedTenant=tenant();
        UUID manager=suspendedTenant.actor(OrganizationMembershipEntity.Role.MANAGER);
        grant(suspendedTenant,OrganizationMembershipEntity.Role.MANAGER,
                DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.enable(principal(suspendedTenant.owner()),suspendedTenant.tenant());
        jdbc.update("UPDATE iam_workspace SET status='SUSPENDED' WHERE id=?",
                suspendedTenant.tenant());
        assertDeniedWithoutBusinessWrite(suspendedTenant,manager,"tenant-suspended");
    }

    @Test
    void urR31UnassignedStaffAndMissingActorAreDeniedWithoutSideEffects() {
        Tenant t=tenant();
        grant(t,OrganizationMembershipEntity.Role.VIEWER,
                DomainPermission.STOREFRONT_OFFER_MANAGE);
        roles.enable(principal(t.owner()),t.tenant());
        UUID unassigned=t.actor(OrganizationMembershipEntity.Role.MEMBER);
        assertDeniedWithoutBusinessWrite(t,unassigned,"unassigned");
        UUID unknown=user("outside");
        assertDeniedWithoutBusinessWrite(t,unknown,"outsider");
        assertThatThrownBy(()->storefront.createDraft(null,t.tenant(),"no-actor"))
                .isInstanceOf(SecurityException.class);
        assertThat(countStorefronts(t)).isZero();
        assertThat(successAudit(t)).isZero();
    }
}
