package kz.rm.iam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class IamUserOnboardingProcessQualificationTest {
    @Autowired PlatformTenantBootstrapService provisioning;
    @Autowired TenantMemberEnrollmentService enrollment;
    @Autowired VerifiedTenantRoleCommands roleCommands;
    @Autowired TenantAuthorization authorization;
    @Autowired GlobalAccountStatusService accounts;
    @Autowired TenantAccessStatusService access;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID user(String name) {
        return users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"onboarding-"+UUID.randomUUID(),name)).getId();
    }

    private VerifiedActor actor(UUID userId) {
        return new VerifiedActor(userId,Instant.now().plusSeconds(300));
    }

    private UUID admin() {
        UUID id=user("Platform administrator");
        jdbc.update("""
                INSERT INTO public.iam_global_role_assignment
                  (id,user_id,role,granted_at)
                VALUES (?,?,?,?)
                """,UUID.randomUUID(),id,"PLATFORM_ADMIN",Timestamp.from(Instant.now()));
        return id;
    }

    @Test void completePlatformAdminToOwnerToEmployeeRoleAndReadmission() {
        UUID admin=admin(),owner=user("Owner"),staff=user("Staff");
        UUID tenant=UUID.randomUUID();
        var provisioned=provisioning.provision(actor(admin),tenant,owner,
                "CRM Operator LLP","CRM Operator","KZ");
        assertThat(provisioned.tenantId()).isEqualTo(tenant);
        assertThat(provisioning.provision(actor(admin),tenant,owner,
                "CRM Operator LLP","CRM Operator","KZ").organizationId())
                .isEqualTo(provisioned.organizationId());

        // Tenant RBAC is enabled at bootstrap: no accidental legacy read grants.
        assertThat(jdbc.queryForObject("""
                SELECT enforced FROM public.iam_tenant_rbac_config WHERE tenant_id=?
                """,Boolean.class,tenant)).isTrue();
        assertThatThrownBy(()->authorization.require(owner,tenant,DomainPermission.CRM_READ))
                .isInstanceOf(SecurityException.class);
        authorization.require(owner,tenant,DomainPermission.TENANT_RBAC_MANAGE);

        var role=roleCommands.createRole(actor(owner),tenant,"SALES_OPERATOR","Sales employee");
        roleCommands.grant(actor(owner),tenant,role.id(),DomainPermission.SALES_ORDER_READ);
        var invitation=enrollment.invite(actor(owner),tenant,staff);
        assertThat(invitation.status()).isEqualTo("INVITED");
        assertThat(enrollment.invite(actor(owner),tenant,staff).status()).isEqualTo("INVITED");
        assertThatThrownBy(()->roleCommands.assign(actor(owner),tenant,staff,role.id()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->authorization.require(staff,tenant,DomainPermission.SALES_ORDER_READ))
                .isInstanceOf(SecurityException.class);

        assertThat(enrollment.accept(actor(staff),tenant).status()).isEqualTo("ACTIVE");
        assertThat(enrollment.accept(actor(staff),tenant).status()).isEqualTo("ACTIVE");
        assertThatThrownBy(()->authorization.require(staff,tenant,DomainPermission.SALES_ORDER_READ))
                .isInstanceOf(SecurityException.class);
        roleCommands.assign(actor(owner),tenant,staff,role.id());
        authorization.require(staff,tenant,DomainPermission.SALES_ORDER_READ);
        assertThatThrownBy(()->authorization.require(staff,tenant,DomainPermission.CRM_PII_READ))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->enrollment.invite(actor(staff),tenant,owner))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->roleCommands.grant(actor(staff),tenant,role.id(),DomainPermission.CRM_PII_READ))
                .isInstanceOf(SecurityException.class);

        assertThat(enrollment.list(actor(owner),tenant))
                .extracting(TenantMemberEnrollmentService.Member::userId)
                .contains(owner,staff);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='TENANT_PROVISIONED'
                """,Integer.class,tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='MEMBER_INVITED'
                """,Integer.class,tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='MEMBER_JOINED'
                """,Integer.class,tenant)).isEqualTo(1);

        access.suspendMember(actor(owner),tenant,staff);
        assertThatThrownBy(()->authorization.require(staff,tenant,DomainPermission.SALES_ORDER_READ))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->enrollment.accept(actor(staff),tenant))
                .isInstanceOf(SecurityException.class);
    }

    @Test void tenantAndRoleManagementAreNotGlobalAdminBusinessPrivileges() {
        UUID admin=admin(),owner=user("Owner"),outsider=user("Outsider"),staff=user("Worker");
        UUID tenant=UUID.randomUUID(),otherTenant=UUID.randomUUID();
        provisioning.provision(actor(admin),tenant,owner,"One LLP","One","KZ");
        provisioning.provision(actor(admin),otherTenant,outsider,"Two LLP","Two","KZ");
        assertThatThrownBy(()->authorization.require(admin,tenant,DomainPermission.CRM_READ))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->enrollment.invite(actor(outsider),tenant,staff))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->enrollment.accept(actor(staff),otherTenant))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->enrollment.list(actor(outsider),tenant))
                .isInstanceOf(SecurityException.class);

        var role=roleCommands.createRole(actor(owner),tenant,"WAREHOUSE_STAFF","Warehouse");
        roleCommands.grant(actor(owner),tenant,role.id(),DomainPermission.INVENTORY_STOCK_READ);
        enrollment.invite(actor(owner),tenant,staff);
        enrollment.accept(actor(staff),tenant);
        roleCommands.assign(actor(owner),tenant,staff,role.id());
        authorization.require(staff,tenant,DomainPermission.INVENTORY_STOCK_READ);
        assertThatThrownBy(()->authorization.require(staff,otherTenant,
                DomainPermission.INVENTORY_STOCK_READ)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->roleCommands.grant(actor(outsider),tenant,role.id(),
                DomainPermission.INVENTORY_RECEIVE)).isInstanceOf(SecurityException.class);
    }

    @Test void platformProvisioningIsFailClosedAndRetryHasPayloadProtection() {
        UUID admin=admin(),owner=user("Owner"),outsider=user("Non admin");
        UUID tenant=UUID.randomUUID();
        assertThatThrownBy(()->provisioning.provision(actor(outsider),tenant,owner,
                "Agency LLP","Agency","KZ")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->provisioning.provision(actor(admin),tenant,owner,
                "Agency LLP","Agency","kz")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_workspace WHERE id=?
                """,Integer.class,tenant)).isZero();
        provisioning.provision(actor(admin),tenant,owner,"Agency LLP","Agency","KZ");
        assertThatThrownBy(()->provisioning.provision(actor(admin),tenant,owner,
                "Changed LLP","Agency","KZ")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_workspace WHERE id=?
                """,Integer.class,tenant)).isEqualTo(1);
        accounts.suspendAccount(actor(admin),outsider);
        assertThatThrownBy(()->provisioning.provision(actor(admin),UUID.randomUUID(),outsider,
                "Another LLP","Another","KZ")).isInstanceOf(SecurityException.class);
    }

    @Test void concurrentInvitationAcceptanceCreatesOneJoinEvent() throws Exception {
        UUID admin=admin(),owner=user("Owner"),staff=user("Staff");
        UUID tenant=UUID.randomUUID();
        provisioning.provision(actor(admin),tenant,owner,"Concurrent LLP","Concurrent","KZ");
        enrollment.invite(actor(owner),tenant,staff);
        var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                release.await(5,TimeUnit.SECONDS);
                return enrollment.accept(actor(staff),tenant).status();
            });
            var second=pool.submit(()->{
                release.await(5,TimeUnit.SECONDS);
                return enrollment.accept(actor(staff),tenant).status();
            });
            release.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS)).isEqualTo("ACTIVE");
            assertThat(second.get(20,TimeUnit.SECONDS)).isEqualTo("ACTIVE");
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='MEMBER_JOINED'
                """,Integer.class,tenant)).isEqualTo(1);
    }

    @Test void verifiedStatusWithoutOidcLinkCannotBecomeOwnerOrEmployee() {
        UUID admin=admin(),owner=user("Trusted owner");
        UUID emailOnly=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO public.iam_user_account
                  (id,display_name,status,created_at,updated_at,row_version)
                VALUES (?,?,'ACTIVE',?,?,0)
                """,emailOnly,"Email-only identity",now,now);

        assertThatThrownBy(()->provisioning.provision(actor(admin),UUID.randomUUID(),
                emailOnly,"Unlinked LLP","Unlinked","KZ"))
                .isInstanceOf(SecurityException.class);
        UUID tenant=UUID.randomUUID();
        provisioning.provision(actor(admin),tenant,owner,
                "Trusted LLP","Trusted","KZ");
        assertThatThrownBy(()->enrollment.invite(actor(owner),tenant,emailOnly))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_org_membership m
                JOIN public.iam_workspace w ON w.organization_id=m.organization_id
                WHERE w.id=? AND m.user_id=?
                """,Integer.class,tenant,emailOnly)).isZero();
    }

}
