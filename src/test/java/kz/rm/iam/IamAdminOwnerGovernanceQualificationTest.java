package kz.rm.iam;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class IamAdminOwnerGovernanceQualificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired PlatformAdminGovernanceService governance;
    @Autowired PlatformTenantBootstrapService provisioning;
    @Autowired TenantMemberEnrollmentService enrollment;
    @Autowired TenantOwnershipTransferService transfers;
    @Autowired PlatformTenantRecoveryService recovery;
    @Autowired TenantRoleService roles;
    @Autowired TenantAccessStatusService access;
    @Autowired TenantAuthorization authorization;
    @Autowired DataSource source;

    private UUID user() {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "admin-owner-"+UUID.randomUUID(),"Verified actor")).getId();
    }

    private VerifiedActor actor(UUID id) {
        return new VerifiedActor(id,Instant.now().plusSeconds(300));
    }

    private UUID admin() {
        UUID id=user();
        jdbc.update("""
                INSERT INTO public.iam_global_role_assignment(id,user_id,role,granted_at)
                VALUES (?,?,'PLATFORM_ADMIN',?)
                """,UUID.randomUUID(),id,Timestamp.from(Instant.now()));
        return id;
    }

    private record Fixture(UUID admin,UUID owner,UUID staff,UUID tenant) {}
    private Fixture fixture() {
        UUID admin=admin(),owner=user(),staff=user(),tenant=UUID.randomUUID();
        provisioning.provision(actor(admin),tenant,owner,
                "Ownership LLP","Ownership","KZ");
        enrollment.invite(actor(owner),tenant,staff);
        enrollment.accept(actor(staff),tenant);
        return new Fixture(admin,owner,staff,tenant);
    }

    @Test @Transactional
    void firstAdminRequiresOfflineOneTimeSecretAndLocksBootstrapForever() throws Exception {
        // The bootstrap is tested in an isolated, rollback-only transaction. This
        // temporarily deactivates other synthetic admin fixtures, not actual data.
        jdbc.update("""
                DELETE FROM public.iam_global_role_assignment WHERE role='PLATFORM_ADMIN'
                """);
        UUID candidate=user();
        String secret="test-offline-setup-secret-"+UUID.randomUUID()+"-"+UUID.randomUUID();
        String digest=HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(secret.getBytes(StandardCharsets.UTF_8)));
        var enabled=new PlatformAdminGovernanceService(jdbc,true,digest);
        var disabled=new PlatformAdminGovernanceService(jdbc,false,digest);
        assertThatThrownBy(()->disabled.bootstrapFirstAdmin(actor(candidate),secret))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->enabled.bootstrapFirstAdmin(actor(candidate),"invalid"))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_global_role_assignment
                WHERE user_id=? AND role='PLATFORM_ADMIN'
                """,Integer.class,candidate)).isZero();

        enabled.bootstrapFirstAdmin(actor(candidate),secret);
        assertThat(jdbc.queryForObject("""
                SELECT bootstrapped FROM public.iam_global_admin_guard WHERE id=1
                """,Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_global_role_event
                WHERE target_user_id=? AND action_code='ADMIN_BOOTSTRAPPED'
                """,Integer.class,candidate)).isEqualTo(1);
        assertThatThrownBy(()->enabled.bootstrapFirstAdmin(actor(candidate),secret))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->enabled.bootstrapFirstAdmin(actor(user()),secret))
                .isInstanceOf(SecurityException.class);
    }

    @Test void globalAdminCanGrantRevokeAndRestoreOtherAdminWithoutTenantAccess() {
        UUID admin=admin(),other=user();
        governance.grantAdmin(actor(admin),other);
        governance.grantAdmin(actor(admin),other);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_global_role_event
                WHERE actor_user_id=? AND target_user_id=? AND action_code='ADMIN_GRANTED'
                """,Integer.class,admin,other)).isEqualTo(1);
        assertThatThrownBy(()->governance.revokeAdmin(actor(other),other))
                .isInstanceOf(SecurityException.class);
        governance.revokeAdmin(actor(admin),other);
        governance.revokeAdmin(actor(admin),other);
        assertThatThrownBy(()->governance.grantAdmin(actor(other),user()))
                .isInstanceOf(SecurityException.class);
        governance.grantAdmin(actor(admin),other);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_global_role_event
                WHERE target_user_id=? AND action_code='ADMIN_GRANTED'
                """,Integer.class,other)).isEqualTo(2);
        assertThatThrownBy(()->governance.revokeAdmin(actor(admin),admin))
                .isInstanceOf(SecurityException.class);
    }

    @Test void ownerTransferRequiresActiveTargetAcceptanceAndIsAtomic() {
        Fixture f=fixture();
        assertThatThrownBy(()->transfers.requestTransfer(actor(f.staff()),f.tenant(),f.owner()))
                .isInstanceOf(SecurityException.class);
        var pending=transfers.requestTransfer(actor(f.owner()),f.tenant(),f.staff());
        assertThat(pending.state()).isEqualTo("PENDING");
        assertThat(transfers.requestTransfer(actor(f.owner()),f.tenant(),f.staff()).id())
                .isEqualTo(pending.id());
        assertThatThrownBy(()->transfers.acceptTransfer(actor(f.owner()),f.tenant(),pending.id()))
                .isInstanceOf(SecurityException.class);
        authorization.require(f.owner(),f.tenant(),DomainPermission.TENANT_RBAC_MANAGE);
        assertThatThrownBy(()->authorization.require(f.staff(),f.tenant(),
                DomainPermission.TENANT_RBAC_MANAGE)).isInstanceOf(SecurityException.class);

        var accepted=transfers.acceptTransfer(actor(f.staff()),f.tenant(),pending.id());
        assertThat(accepted.state()).isEqualTo("ACCEPTED");
        assertThat(transfers.acceptTransfer(actor(f.staff()),f.tenant(),pending.id()).state())
                .isEqualTo("ACCEPTED");
        authorization.require(f.staff(),f.tenant(),DomainPermission.TENANT_RBAC_MANAGE);
        assertThatThrownBy(()->authorization.require(f.owner(),f.tenant(),
                DomainPermission.TENANT_RBAC_MANAGE)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_org_membership m
                JOIN public.iam_workspace w ON w.organization_id=m.organization_id
                WHERE w.id=? AND m.role='OWNER' AND m.status='ACTIVE'
                """,Integer.class,f.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='OWNER_TRANSFER_ACCEPTED'
                """,Integer.class,f.tenant())).isEqualTo(1);
        assertThatThrownBy(()->transfers.cancelTransfer(actor(f.owner()),f.tenant(),pending.id()))
                .isInstanceOf(SecurityException.class);
    }

    @Test void cancellationExpirationAndForeignTargetsNeverTransferOwnership() {
        Fixture f=fixture();
        UUID outsider=user();
        assertThatThrownBy(()->transfers.requestTransfer(actor(f.owner()),f.tenant(),outsider))
                .isInstanceOf(SecurityException.class);
        var pending=transfers.requestTransfer(actor(f.owner()),f.tenant(),f.staff());
        transfers.cancelTransfer(actor(f.owner()),f.tenant(),pending.id());
        transfers.cancelTransfer(actor(f.owner()),f.tenant(),pending.id());
        assertThatThrownBy(()->transfers.acceptTransfer(actor(f.staff()),f.tenant(),pending.id()))
                .isInstanceOf(SecurityException.class);

        var next=transfers.requestTransfer(actor(f.owner()),f.tenant(),f.staff());
        // Simulate an expired but structurally valid pending request.
        // Both timestamps must preserve the DB check expires_at > requested_at.
        jdbc.update("""
                UPDATE public.iam_owner_transfer_request
                SET requested_at=?,expires_at=?
                WHERE tenant_id=?
                """,Timestamp.from(Instant.now().minusSeconds(48*3600)),
                Timestamp.from(Instant.now().minusSeconds(24*3600)),f.tenant());
        assertThatThrownBy(()->transfers.acceptTransfer(actor(f.staff()),f.tenant(),next.id()))
                .isInstanceOf(SecurityException.class);
        authorization.require(f.owner(),f.tenant(),DomainPermission.TENANT_RBAC_MANAGE);
        assertThatThrownBy(()->authorization.require(f.staff(),f.tenant(),
                DomainPermission.TENANT_RBAC_MANAGE)).isInstanceOf(SecurityException.class);
    }

    @Test void emergencyRecoveryOnlyWithZeroActiveOwnersAndVerifiedTenantEmployee() {
        Fixture f=fixture();
        assertThatThrownBy(()->recovery.recoverOwner(actor(f.admin()),f.tenant(),f.staff(),"INCIDENT_2026_RM"))
                .isInstanceOf(SecurityException.class);
        UUID outsider=user();
        // Legacy broken/imported state is simulated only in test; normal services
        // prohibit suspension of the last OWNER or account suspension of an OWNER.
        jdbc.update("""
                UPDATE public.iam_org_membership SET status='SUSPENDED'
                WHERE user_id=? AND role='OWNER'
                """,f.owner());
        assertThatThrownBy(()->recovery.recoverOwner(actor(f.admin()),f.tenant(),outsider,"INCIDENT_2026_RM"))
                .isInstanceOf(SecurityException.class);
        recovery.recoverOwner(actor(f.admin()),f.tenant(),f.staff(),"INCIDENT_2026_RM");
        authorization.require(f.staff(),f.tenant(),DomainPermission.TENANT_RBAC_MANAGE);
        assertThatThrownBy(()->authorization.require(f.admin(),f.tenant(),
                DomainPermission.TENANT_RBAC_MANAGE)).isInstanceOf(SecurityException.class);
        access.restoreMember(actor(f.staff()),f.tenant(),f.owner());
        assertThatThrownBy(()->authorization.require(f.owner(),f.tenant(),
                DomainPermission.TENANT_RBAC_MANAGE)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_org_membership m
                JOIN public.iam_workspace w ON w.organization_id=m.organization_id
                WHERE w.id=? AND m.role='OWNER' AND m.status='ACTIVE'
                """,Integer.class,f.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='OWNER_RECOVERED'
                """,Integer.class,f.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT reason_code FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='OWNER_RECOVERED'
                """,String.class,f.tenant())).isEqualTo("INCIDENT_2026_RM");
    }

    @Test void twoConcurrentAcceptsAreIdempotentAndKeepOneOwner() throws Exception {
        Fixture f=fixture();
        var pending=transfers.requestTransfer(actor(f.owner()),f.tenant(),f.staff());
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                return transfers.acceptTransfer(actor(f.staff()),f.tenant(),pending.id()).state();
            });
            var b=executor.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                return transfers.acceptTransfer(actor(f.staff()),f.tenant(),pending.id()).state();
            });
            start.countDown();
            assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo("ACCEPTED");
            assertThat(b.get(20,TimeUnit.SECONDS)).isEqualTo("ACCEPTED");
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='OWNER_TRANSFER_ACCEPTED'
                """,Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test void postgresAuditGlobalRoleEventsAreImmutable() throws Exception {
        try(var connection=source.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        UUID admin=admin(),staff=user();
        governance.grantAdmin(actor(admin),staff);
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM public.iam_global_role_event WHERE target_user_id=?
                """,staff)).isInstanceOf(Exception.class);
    }

    @Test void successorSuspendedAfterTransferRequestCannotBecomeOwner() {
        Fixture f=fixture();
        var request=transfers.requestTransfer(actor(f.owner()),f.tenant(),f.staff());
        access.suspendMember(actor(f.owner()),f.tenant(),f.staff());
        assertThatThrownBy(()->transfers.acceptTransfer(actor(f.staff()),f.tenant(),request.id()))
                .isInstanceOf(SecurityException.class);
        authorization.require(f.owner(),f.tenant(),DomainPermission.TENANT_RBAC_MANAGE);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='OWNER_TRANSFER_ACCEPTED'
                """,Integer.class,f.tenant())).isZero();
    }

    @Test void recoveredOwnerMayRestoreSuspendedOrganization() {
        Fixture f=fixture();
        // Simulated exceptional legacy state, never reachable through normal
        // last-OWNER guard. An administrator cannot open CRM via this recovery.
        jdbc.update("""
                UPDATE public.iam_org_membership
                SET status='SUSPENDED' WHERE user_id=? AND role='OWNER'
                """,f.owner());
        jdbc.update("""
                UPDATE public.iam_workspace SET status='SUSPENDED' WHERE id=?
                """,f.tenant());
        recovery.recoverOwner(actor(f.admin()),f.tenant(),f.staff(),"INCIDENT_2026_RM");
        assertThatThrownBy(()->authorization.require(f.staff(),f.tenant(),
                DomainPermission.TENANT_RBAC_MANAGE)).isInstanceOf(SecurityException.class);
        access.restoreTenant(actor(f.staff()),f.tenant());
        authorization.require(f.staff(),f.tenant(),DomainPermission.TENANT_RBAC_MANAGE);
        assertThatThrownBy(()->authorization.require(f.admin(),f.tenant(),
                DomainPermission.CRM_READ)).isInstanceOf(SecurityException.class);
    }

    @Test void concurrentEmergencyRecoveryAdmitsOnlyOneOwner() throws Exception {
        Fixture f=fixture();
        UUID otherEmployee=user(),secondAdmin=admin();
        enrollment.invite(actor(f.owner()),f.tenant(),otherEmployee);
        enrollment.accept(actor(otherEmployee),f.tenant());
        jdbc.update("""
                UPDATE public.iam_org_membership SET status='SUSPENDED'
                WHERE user_id=? AND role='OWNER'
                """,f.owner());
        var begin=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{
                begin.await(5,TimeUnit.SECONDS);
                try { recovery.recoverOwner(actor(f.admin()),f.tenant(),f.staff(),"INCIDENT_2026_RM"); return true; }
                catch(SecurityException conflict) { return false; }
            });
            var b=pool.submit(()->{
                begin.await(5,TimeUnit.SECONDS);
                try { recovery.recoverOwner(actor(secondAdmin),f.tenant(),otherEmployee,"INCIDENT_2026_RM"); return true; }
                catch(SecurityException conflict) { return false; }
            });
            begin.countDown();
            assertThat((a.get(20,TimeUnit.SECONDS)?1:0)
                    +(b.get(20,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND action_code='OWNER_RECOVERED'
                """,Integer.class,f.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_org_membership m
                JOIN public.iam_workspace w ON w.organization_id=m.organization_id
                WHERE w.id=? AND m.status='ACTIVE' AND m.role='OWNER'
                """,Integer.class,f.tenant())).isEqualTo(1);
    }

    @Test @Transactional
    void initialAdminBootstrapRejectsPreviouslyRevokedGlobalRole() throws Exception {
        UUID candidate=user(),previous=user();
        String secret="different-offline-secret-"+UUID.randomUUID()+"-"+UUID.randomUUID();
        String digest=HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(secret.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
                INSERT INTO public.iam_global_role_assignment
                  (id,user_id,role,granted_at,revoked_at)
                VALUES (?,?,'PLATFORM_ADMIN',?,?)
                """,UUID.randomUUID(),previous,
                Timestamp.from(Instant.now().minusSeconds(7200)),
                Timestamp.from(Instant.now().minusSeconds(3600)));
        var configured=new PlatformAdminGovernanceService(jdbc,true,digest);
        assertThatThrownBy(()->configured.bootstrapFirstAdmin(actor(candidate),secret))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT bootstrapped FROM public.iam_global_admin_guard WHERE id=1
                """,Boolean.class)).isFalse();
    }


    @Test void emergencyRecoveryCannotElevatePlatformAdminToOwnTenantOwner() {
        Fixture f=fixture();
        enrollment.invite(actor(f.owner()),f.tenant(),f.admin());
        enrollment.accept(actor(f.admin()),f.tenant());
        jdbc.update("""
                UPDATE public.iam_org_membership
                SET status='SUSPENDED' WHERE user_id=? AND role='OWNER'
                """,f.owner());
        assertThatThrownBy(()->recovery.recoverOwner(actor(f.admin()),f.tenant(),
                f.admin(),"INCIDENT_2026_RM")).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT role FROM public.iam_org_membership m
                JOIN public.iam_workspace w ON w.organization_id=m.organization_id
                WHERE w.id=? AND m.user_id=?
                """,String.class,f.tenant(),f.admin())).isEqualTo("MEMBER");
    }

    @Test void recoveryCancelsAndAuditsOutstandingOwnerTransfer() {
        Fixture f=fixture();
        UUID replacement=user();
        enrollment.invite(actor(f.owner()),f.tenant(),replacement);
        enrollment.accept(actor(replacement),f.tenant());
        var requested=transfers.requestTransfer(actor(f.owner()),f.tenant(),f.staff());
        jdbc.update("""
                UPDATE public.iam_org_membership SET status='SUSPENDED'
                WHERE user_id=? AND role='OWNER'
                """,f.owner());
        recovery.recoverOwner(actor(f.admin()),f.tenant(),replacement,"INCIDENT_2026_RM");
        assertThat(jdbc.queryForObject("""
                SELECT state FROM public.iam_owner_transfer_request WHERE tenant_id=?
                """,String.class,f.tenant())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_rbac_event
                WHERE tenant_id=? AND target_user_id=? AND action_code='OWNER_TRANSFER_CANCELLED'
                  AND reason_code='INCIDENT_2026_RM'
                """,Integer.class,f.tenant(),f.staff())).isEqualTo(1);
        assertThatThrownBy(()->transfers.acceptTransfer(actor(f.staff()),f.tenant(),requested.id()))
                .isInstanceOf(SecurityException.class);
        authorization.require(replacement,f.tenant(),DomainPermission.TENANT_RBAC_MANAGE);
    }

}
