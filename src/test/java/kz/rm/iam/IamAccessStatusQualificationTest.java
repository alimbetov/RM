package kz.rm.iam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class IamAccessStatusQualificationTest {
    @Autowired TenantAccessStatusService access;
    @Autowired GlobalAccountStatusService accounts;
    @Autowired TenantAuthorization authorization;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;

    private record Fixture(UUID tenant, UUID owner, UUID coOwner, UUID staff, UUID admin) {}
    private UUID user(String name) {
        return users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"access-"+name+"-"+UUID.randomUUID(),name)).getId();
    }
    private VerifiedActor actor(UUID id) {
        return new VerifiedActor(id,Instant.now().plusSeconds(120));
    }
    private Fixture createFixture() {
        var organization=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Access Qualification LLP","Access Qualification","KZ"));
        var workspace=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),organization.getId()));
        UUID owner=user("owner"),coOwner=user("coowner"),staff=user("staff"),admin=user("admin");
        members.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                organization.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        members.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                organization.getId(),coOwner,OrganizationMembershipEntity.Role.OWNER));
        members.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                organization.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        jdbc.update("""
                INSERT INTO public.iam_global_role_assignment
                  (id,user_id,role,granted_at)
                VALUES (?,?,?,?)
                """,UUID.randomUUID(),admin,"PLATFORM_ADMIN",Timestamp.from(Instant.now()));
        return new Fixture(workspace.getId(),owner,coOwner,staff,admin);
    }

    @Test void memberSuspendRestoreAreEffectiveIdempotentAndAudited() {
        Fixture f=createFixture();
        authorization.require(f.staff(),f.tenant(),DomainPermission.CRM_READ);
        access.suspendMember(actor(f.owner()),f.tenant(),f.staff());
        access.suspendMember(actor(f.owner()),f.tenant(),f.staff());
        assertThatThrownBy(()->authorization.require(f.staff(),f.tenant(),DomainPermission.CRM_READ))
                .isInstanceOf(SecurityException.class);
        access.restoreMember(actor(f.owner()),f.tenant(),f.staff());
        access.restoreMember(actor(f.owner()),f.tenant(),f.staff());
        authorization.require(f.staff(),f.tenant(),DomainPermission.CRM_READ);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND target_user_id=?
                  AND action_code IN ('MEMBER_SUSPENDED','MEMBER_RESTORED')
                """,Integer.class,f.tenant(),f.staff())).isEqualTo(2);
    }

    @Test void tenantSuspendRestoreAllowOwnerRecoveryButDenyNormalCommands() {
        Fixture f=createFixture();
        access.suspendTenant(actor(f.owner()),f.tenant());
        assertThatThrownBy(()->authorization.require(f.staff(),f.tenant(),DomainPermission.CRM_READ))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->access.suspendTenant(actor(f.owner()),f.tenant()))
                .isInstanceOf(SecurityException.class); // restore is the only suspended-tenant command
    }

    @Test void tenantOwnerCanRestoreSuspendedTenant() {
        Fixture f=createFixture();
        access.suspendTenant(actor(f.owner()),f.tenant());
        access.restoreTenant(actor(f.owner()),f.tenant());
        access.restoreTenant(actor(f.owner()),f.tenant());
        authorization.require(f.staff(),f.tenant(),DomainPermission.CRM_READ);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code IN ('TENANT_SUSPENDED','TENANT_RESTORED')
                """,Integer.class,f.tenant())).isEqualTo(2);
    }

    @Test void cannotChangeMembershipOutsideTenantOrSuspendSelf() {
        Fixture first=createFixture(),other=createFixture();
        assertThatThrownBy(()->access.suspendMember(actor(first.owner()),first.tenant(),first.owner()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->access.suspendMember(actor(first.owner()),first.tenant(),other.staff()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->access.suspendMember(actor(first.staff()),first.tenant(),first.coOwner()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->access.suspendTenant(actor(first.owner()),other.tenant()))
                .isInstanceOf(SecurityException.class);
    }

    @Test void accountChangesNeedGlobalAdminProtectOwnerAndWriteOneEventPerTransition() {
        Fixture f=createFixture();
        assertThatThrownBy(()->accounts.suspendAccount(actor(f.owner()),f.staff()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->accounts.suspendAccount(actor(f.admin()),f.owner()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->accounts.suspendAccount(actor(f.admin()),f.admin()))
                .isInstanceOf(SecurityException.class);
        accounts.suspendAccount(actor(f.admin()),f.staff());
        accounts.suspendAccount(actor(f.admin()),f.staff());
        assertThatThrownBy(()->authorization.require(f.staff(),f.tenant(),DomainPermission.CRM_READ))
                .isInstanceOf(SecurityException.class);
        accounts.restoreAccount(actor(f.admin()),f.staff());
        accounts.restoreAccount(actor(f.admin()),f.staff());
        authorization.require(f.staff(),f.tenant(),DomainPermission.CRM_READ);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_account_status_event WHERE target_user_id=?
                """,Integer.class,f.staff())).isEqualTo(2);
    }

    @Test void cannotRestoreMembershipWhileAccountInactive() {
        Fixture f=createFixture();
        access.suspendMember(actor(f.owner()),f.tenant(),f.staff());
        accounts.suspendAccount(actor(f.admin()),f.staff());
        assertThatThrownBy(()->access.restoreMember(actor(f.owner()),f.tenant(),f.staff()))
                .isInstanceOf(SecurityException.class);
    }

    @Test void activePlatformAdministratorCannotBeSuspendedWithoutRoleRevocation() {
        Fixture f=createFixture();
        UUID secondAdmin=user("second-admin");
        jdbc.update("""
                INSERT INTO public.iam_global_role_assignment (id,user_id,role,granted_at)
                VALUES (?,?,?,?)
                """,UUID.randomUUID(),secondAdmin,"PLATFORM_ADMIN",Timestamp.from(Instant.now()));
        assertThatThrownBy(()->accounts.suspendAccount(actor(f.admin()),secondAdmin))
                .isInstanceOf(SecurityException.class);
        jdbc.update("""
                UPDATE public.iam_global_role_assignment SET revoked_at=? 
                WHERE user_id=? AND role='PLATFORM_ADMIN'
                """,Timestamp.from(Instant.now()),secondAdmin);
        accounts.suspendAccount(actor(f.admin()),secondAdmin);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM public.iam_user_account WHERE id=?
                """,String.class,secondAdmin)).isEqualTo("SUSPENDED");
    }

    @Test void postgresGlobalAccountHistoryIsImmutable() throws Exception {
        try(var connection=source.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        Fixture f=createFixture();
        accounts.suspendAccount(actor(f.admin()),f.staff());
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM public.iam_account_status_event WHERE target_user_id=?
                """,f.staff())).isInstanceOf(Exception.class);
    }


    @Test void accountSuspensionCommittedFirstBlocksConcurrentMembershipRestore() throws Exception {
        try(var connection=source.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        Fixture f=createFixture();
        access.suspendMember(actor(f.owner()),f.tenant(),f.staff());
        var accountStatusLocked=new CountDownLatch(1);
        var allowSuspendCommit=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var suspension=pool.submit(()->tx.execute(status->{
                accounts.suspendAccount(actor(f.admin()),f.staff());
                accountStatusLocked.countDown();
                try {
                    if(!allowSuspendCommit.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("Timed out awaiting suspension commit");
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            }));
            assertThat(accountStatusLocked.await(10,TimeUnit.SECONDS)).isTrue();
            var restoration=pool.submit(()->{
                try {
                    access.restoreMember(actor(f.owner()),f.tenant(),f.staff());
                    return true;
                } catch(SecurityException denied) {
                    return false;
                }
            });
            try {
                assertThatThrownBy(()->restoration.get(300,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                allowSuspendCommit.countDown();
            }
            assertThat(suspension.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(restoration.get(20,TimeUnit.SECONDS)).isFalse();
        }
        assertThat(jdbc.queryForObject("""
                SELECT m.status FROM public.iam_org_membership m
                JOIN public.iam_workspace w ON w.organization_id=m.organization_id
                WHERE w.id=? AND m.user_id=?
                """,String.class,f.tenant(),f.staff())).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='MEMBER_RESTORED'
                  AND target_user_id=?
                """,Integer.class,f.tenant(),f.staff())).isZero();
    }

}
