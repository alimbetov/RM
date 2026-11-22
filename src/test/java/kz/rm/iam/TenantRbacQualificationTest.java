package kz.rm.iam;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class TenantRbacQualificationTest {
    @Autowired TenantAuthorization authorization;
    @Autowired TenantRoleService roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactionManager;

    private record Staff(UUID owner1,UUID owner2,UUID manager,UUID outsider,UUID tenant) {}
    private Staff createTenant() {
        var org=organizations.saveAndFlush(
            new OrganizationEntity(UUID.randomUUID(),"RBAC Test LLP","RBAC Test","KZ"));
        var workspace=workspaces.saveAndFlush(
            WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        UUID first=newUser("owner1");
        UUID second=newUser("owner2");
        UUID manager=newUser("manager");
        UUID outside=newUser("outsider");
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
            org.getId(),first,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
            org.getId(),second,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
            org.getId(),manager,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        return new Staff(first,second,manager,outside,workspace.getId());
    }
    private UUID newUser(String label) {
        return users.saveAndFlush(new UserAccountEntity(
            UUID.randomUUID(),label+"-"+UUID.randomUUID(),label)).getId();
    }
    @Test void functionalRoleIsIsolatedAndActivatedOnlyAfterApproval() {
        var t=createTenant();
        // Before opt-in, existing SEC-R1.0 reads continue to work.
        authorization.require(t.manager(),t.tenant(),DomainPermission.SALES_ORDER_READ);
        assertThatThrownBy(()->authorization.require(t.manager(),t.tenant(),
                DomainPermission.INVENTORY_RECEIVE)).isInstanceOf(SecurityException.class);

        var warehouse=roles.createRole(t.owner1(),t.tenant(),"WAREHOUSE_OPERATOR","Склад");
        roles.grant(t.owner1(),t.tenant(),warehouse.id(),DomainPermission.INVENTORY_STOCK_READ);
        roles.grant(t.owner1(),t.tenant(),warehouse.id(),DomainPermission.INVENTORY_RECEIVE);
        roles.assign(t.owner1(),t.tenant(),t.manager(),warehouse.id());
        roles.assign(t.owner1(),t.tenant(),t.owner2(),warehouse.id());
        roles.enable(t.owner1(),t.tenant());
        roles.enable(t.owner1(),t.tenant()); // idempotent
        authorization.require(t.manager(),t.tenant(),DomainPermission.INVENTORY_RECEIVE);
        authorization.require(t.manager(),t.tenant(),DomainPermission.INVENTORY_STOCK_READ);
        assertThatThrownBy(()->authorization.require(t.manager(),t.tenant(),
                DomainPermission.SALES_ORDER_READ)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->authorization.require(t.owner1(),t.tenant(),
                DomainPermission.INVENTORY_RECEIVE)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_tenant_rbac_event
            WHERE tenant_id=? AND action_code='RBAC_ENABLED'
            """,Integer.class,t.tenant())).isEqualTo(1);
        roles.revoke(t.owner1(),t.tenant(),warehouse.id(),DomainPermission.INVENTORY_RECEIVE);
        assertThatThrownBy(()->authorization.require(t.manager(),t.tenant(),
                DomainPermission.INVENTORY_RECEIVE)).isInstanceOf(SecurityException.class);
        roles.unassign(t.owner1(),t.tenant(),t.manager(),warehouse.id());
        assertThatThrownBy(()->authorization.require(t.manager(),t.tenant(),
                DomainPermission.INVENTORY_STOCK_READ)).isInstanceOf(SecurityException.class);
    }

    @Test void actorCannotGrantToSelfOrCrossTenantOrDelegateAdmin() {
        var t=createTenant();
        var other=createTenant();
        var role=roles.createRole(t.owner1(),t.tenant(),"SALES_MANAGER","Sales");
        assertThatThrownBy(()->roles.assign(t.owner1(),t.tenant(),t.owner1(),role.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->roles.assign(t.manager(),t.tenant(),t.owner2(),role.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->roles.assign(t.owner1(),t.tenant(),t.outsider(),role.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->roles.grant(t.owner1(),t.tenant(),role.id(),
                DomainPermission.TENANT_RBAC_MANAGE)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->roles.assign(other.owner1(),other.tenant(),other.manager(),role.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->roles.grant(other.owner1(),other.tenant(),role.id(),
                DomainPermission.CRM_READ)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->roles.enable(t.owner1(),t.tenant()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void suspendedMemberCannotUseGrantedPermission() {
        var t=createTenant();
        var r=roles.createRole(t.owner1(),t.tenant(),"CUSTOMER_TEAM","CRM");
        roles.grant(t.owner1(),t.tenant(),r.id(),DomainPermission.CRM_READ);
        roles.assign(t.owner1(),t.tenant(),t.manager(),r.id());
        roles.assign(t.owner1(),t.tenant(),t.owner2(),r.id());
        roles.enable(t.owner1(),t.tenant());
        jdbc.update("UPDATE iam_user_account SET status='SUSPENDED' WHERE id=?",t.manager());
        assertThatThrownBy(()->authorization.require(t.manager(),t.tenant(),
                DomainPermission.CRM_READ)).isInstanceOf(SecurityException.class);
    }

    @Test void soleOwnerCanEnableRbacAfterAssigningAnActiveEmployee() {
        var org=organizations.saveAndFlush(
            new OrganizationEntity(UUID.randomUUID(),"One Owner LLP","One Owner","KZ"));
        var tenant=workspaces.saveAndFlush(
            WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        UUID owner=newUser("sole-owner"),staff=newUser("sole-staff");
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",tenant.getId());
        var cashier=roles.createRole(owner,tenant.getId(),"SHOP_CASHIER","Sales cashier");
        roles.grant(owner,tenant.getId(),cashier.id(),DomainPermission.SALES_ORDER_READ);
        assertThatThrownBy(()->roles.enable(owner,tenant.getId()))
            .isInstanceOf(IllegalStateException.class);
        roles.assign(owner,tenant.getId(),staff,cashier.id());
        roles.enable(owner,tenant.getId());
        roles.enable(owner,tenant.getId());
        authorization.require(staff,tenant.getId(),DomainPermission.SALES_ORDER_READ);
        authorization.require(owner,tenant.getId(),DomainPermission.TENANT_RBAC_MANAGE);
        assertThatThrownBy(()->authorization.require(owner,tenant.getId(),
            DomainPermission.SALES_ORDER_READ)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_tenant_rbac_event
            WHERE tenant_id=? AND action_code='RBAC_ENABLED'
            """,Integer.class,tenant.getId())).isEqualTo(1);
    }

    @Test void roleSuspensionAndRestorationImmediatelyChangeAccessAndAreAudited() {
        var t=createTenant();
        var role=roles.createRole(t.owner1(),t.tenant(),"INVENTORY_CLERK","Warehouse clerk");
        roles.grant(t.owner1(),t.tenant(),role.id(),DomainPermission.INVENTORY_STOCK_READ);
        roles.assign(t.owner1(),t.tenant(),t.manager(),role.id());
        roles.enable(t.owner1(),t.tenant());
        assertThat(roles.listGrants(t.owner1(),t.tenant(),role.id()))
            .containsExactly(DomainPermission.INVENTORY_STOCK_READ);
        assertThat(roles.listAssignments(t.owner1(),t.tenant()))
            .extracting(TenantRoleService.RoleAssignment::userId).contains(t.manager());
        assertThatThrownBy(()->roles.listAssignments(t.manager(),t.tenant()))
            .isInstanceOf(SecurityException.class);
        authorization.require(t.manager(),t.tenant(),DomainPermission.INVENTORY_STOCK_READ);
        roles.setRoleActive(t.owner1(),t.tenant(),role.id(),false);
        roles.setRoleActive(t.owner1(),t.tenant(),role.id(),false); // replay is no-op
        assertThatThrownBy(()->authorization.require(t.manager(),t.tenant(),
            DomainPermission.INVENTORY_STOCK_READ)).isInstanceOf(SecurityException.class);
        roles.setRoleActive(t.owner1(),t.tenant(),role.id(),true);
        authorization.require(t.manager(),t.tenant(),DomainPermission.INVENTORY_STOCK_READ);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_tenant_rbac_event
            WHERE tenant_id=? AND role_id=? AND action_code='ROLE_DEACTIVATED'
            """,Integer.class,t.tenant(),role.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_tenant_rbac_event
            WHERE tenant_id=? AND role_id=? AND action_code='ROLE_ACTIVATED'
            """,Integer.class,t.tenant(),role.id())).isEqualTo(1);
        assertThatThrownBy(()->roles.setRoleActive(t.manager(),t.tenant(),role.id(),false))
            .isInstanceOf(SecurityException.class);
    }

    @Test void commandPermissionCannotBeReplayedOutsideItsTransaction() {
        var t=createTenant();
        var role=roles.createRole(t.owner1(),t.tenant(),"TX_BOUND","Transaction scoped");
        roles.grant(t.owner1(),t.tenant(),role.id(),DomainPermission.INVENTORY_RECEIVE);
        roles.assign(t.owner1(),t.tenant(),t.manager(),role.id());
        roles.enable(t.owner1(),t.tenant());
        var template=new TransactionTemplate(transactionManager);
        var issued=template.execute(tx->{
            var permit=authorization.requireForCommand(
                t.manager(),t.tenant(),DomainPermission.INVENTORY_RECEIVE);
            assertThat(permit.validForCurrentTransaction()).isTrue();
            return permit;
        });
        assertThat(issued).isNotNull();
        assertThat(issued.validForCurrentTransaction()).isFalse();
        template.execute(tx->{
            assertThat(issued.validForCurrentTransaction()).isFalse();
            var fresh=authorization.requireForCommand(
                t.manager(),t.tenant(),DomainPermission.INVENTORY_RECEIVE);
            assertThat(fresh.validForCurrentTransaction()).isTrue();
            return null;
        });
    }

    @Test void postgresRoleEvidenceCannotBeMutated() throws Exception {
        try(var connection=source.getConnection()){
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=createTenant();
        roles.createRole(t.owner1(),t.tenant(),"ACCOUNTING","Finance");
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM iam_tenant_rbac_event WHERE tenant_id=?
            """,t.tenant())).isInstanceOf(Exception.class);
    }

    @Autowired VerifiedTenantRoleCommands trustedRoles;

    @Test void tcrud04VerifiedOwnerCanAdministerButForeignActorCannotMutateRbac() {
        var a=createTenant();
        var b=createTenant();
        var owner=new VerifiedActor(a.owner1(),java.time.Instant.now().plusSeconds(300));
        var foreign=new VerifiedActor(b.owner1(),java.time.Instant.now().plusSeconds(300));
        var employee=new VerifiedActor(a.manager(),java.time.Instant.now().plusSeconds(300));
        assertThatThrownBy(()->trustedRoles.createRole(null,a.tenant(),"DENIED","Denied"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->trustedRoles.createRole(foreign,a.tenant(),"DENIED","Denied"))
                .isInstanceOf(SecurityException.class);
        var role=trustedRoles.createRole(owner,a.tenant(),"TCRUD04_ROLE","Scoped staff");
        trustedRoles.grant(owner,a.tenant(),role.id(),DomainPermission.INVENTORY_STOCK_READ);
        assertThat(trustedRoles.listGrants(owner,a.tenant(),role.id()))
                .containsExactly(DomainPermission.INVENTORY_STOCK_READ);
        trustedRoles.assign(owner,a.tenant(),a.manager(),role.id());
        assertThat(trustedRoles.listAssignments(owner,a.tenant()))
                .anySatisfy(row->{
                    assertThat(row.userId()).isEqualTo(a.manager());
                    assertThat(row.roleId()).isEqualTo(role.id());
                });
        trustedRoles.enable(owner,a.tenant());
        authorization.require(a.manager(),a.tenant(),DomainPermission.INVENTORY_STOCK_READ);
        assertThatThrownBy(()->trustedRoles.revoke(employee,a.tenant(),role.id(),
                DomainPermission.INVENTORY_STOCK_READ)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->trustedRoles.revoke(foreign,a.tenant(),role.id(),
                DomainPermission.INVENTORY_STOCK_READ)).isInstanceOf(SecurityException.class);
        trustedRoles.revoke(owner,a.tenant(),role.id(),DomainPermission.INVENTORY_STOCK_READ);
        assertThatThrownBy(()->authorization.require(a.manager(),a.tenant(),
                DomainPermission.INVENTORY_STOCK_READ)).isInstanceOf(SecurityException.class);
    }

}
