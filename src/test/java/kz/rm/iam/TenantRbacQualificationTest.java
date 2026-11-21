package kz.rm.iam;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
}
