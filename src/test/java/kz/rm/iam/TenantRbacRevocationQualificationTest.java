package kz.rm.iam;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * Real two-connection PostgreSQL qualification for revoke-versus-command races.
 * Deterministic latches hold open a real transaction, not a simulated call.
 */
@SpringBootTest
class TenantRbacRevocationQualificationTest {
    @Autowired TenantAuthorization authorization;
    @Autowired TenantAccessStatusService access;
    @Autowired TenantRoleService roles;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;

    private record Fixture(UUID tenant,UUID owner,UUID staff,UUID role) {}

    private Fixture setup() {
        var org=organizations.saveAndFlush(new OrganizationEntity(
                UUID.randomUUID(),"Rbac Race LLP","RBAC Race","KZ"));
        var workspace=workspaces.saveAndFlush(
                WorkspaceEntity.organization(UUID.randomUUID(),org.getId()));
        UUID owner=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"race-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID staff=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"race-staff-"+UUID.randomUUID(),"Staff")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
                org.getId(),staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE iam_workspace SET status='ACTIVE' WHERE id=?",workspace.getId());
        var role=roles.createRole(owner,workspace.getId(),
                "WORKER_"+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(),
                "Staff");
        roles.grant(owner,workspace.getId(),role.id(),DomainPermission.INVENTORY_RECEIVE);
        roles.assign(owner,workspace.getId(),staff,role.id());
        roles.enable(owner,workspace.getId()); // single-owner activation
        return new Fixture(workspace.getId(),owner,staff,role.id());
    }

    private void pgOnly() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if(!latch.await(15,TimeUnit.SECONDS))
                throw new IllegalStateException("Concurrent transaction did not reach latch");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted",e);
        }
    }

    @Test void activeCommandCompletesBeforeConcurrentRevocationCommits() throws Exception {
        pgOnly();
        Fixture f=setup();
        var admitted=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var revokeStarted=new CountDownLatch(1);
        var transactions=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var command=pool.submit(()->transactions.execute(tx->{
                authorization.requireForCommand(f.staff(),f.tenant(),DomainPermission.INVENTORY_RECEIVE);
                admitted.countDown();
                await(finish); // keep PG workspace SHARE row lock through tx commit
                return true;
            }));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{
                revokeStarted.countDown();
                roles.revoke(f.owner(),f.tenant(),f.role(),DomainPermission.INVENTORY_RECEIVE);
                return true;
            });
            assertThat(revokeStarted.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->revoke.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                finish.countDown();
            }
            assertThat(command.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThatThrownBy(()->authorization.require(f.staff(),f.tenant(),
                DomainPermission.INVENTORY_RECEIVE)).isInstanceOf(SecurityException.class);
    }

    @Test void commandCannotProceedUntilEarlierRevocationCommits() throws Exception {
        pgOnly();
        Fixture f=setup();
        var revokeHasLock=new CountDownLatch(1);
        var finishRevoke=new CountDownLatch(1);
        var commandStarted=new CountDownLatch(1);
        var transactions=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revoke=pool.submit(()->transactions.execute(tx->{
                jdbc.queryForObject("""
                    SELECT id FROM public.iam_workspace WHERE id=? FOR UPDATE
                    """,UUID.class,f.tenant());
                roles.revoke(f.owner(),f.tenant(),f.role(),DomainPermission.INVENTORY_RECEIVE);
                revokeHasLock.countDown();
                await(finishRevoke);
                return true;
            }));
            assertThat(revokeHasLock.await(10,TimeUnit.SECONDS)).isTrue();
            var command=pool.submit(()->{
                commandStarted.countDown();
                try {
                    return transactions.execute(tx->{
                        authorization.requireForCommand(f.staff(),f.tenant(),
                                DomainPermission.INVENTORY_RECEIVE);
                        return true;
                    });
                } catch(SecurityException denied) {
                    return false;
                }
            });
            assertThat(commandStarted.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->command.get(250,TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            } finally {
                finishRevoke.countDown();
            }
            assertThat(revoke.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(command.get(20,TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test void accountSuspensionWaitsForAlreadyAdmittedCommand() throws Exception {
        pgOnly();
        Fixture f=setup();
        var admitted=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var command=pool.submit(()->tx.execute(status->{
                authorization.requireForCommand(f.staff(),f.tenant(),DomainPermission.INVENTORY_RECEIVE);
                admitted.countDown();
                await(finish);
                return true;
            }));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var suspend=pool.submit(()->tx.execute(status->{
                jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",f.staff());
                return true;
            }));
            try {
                assertThatThrownBy(()->suspend.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                finish.countDown();
            }
            assertThat(command.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(suspend.get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThatThrownBy(()->authorization.require(f.staff(),f.tenant(),
                DomainPermission.INVENTORY_RECEIVE)).isInstanceOf(SecurityException.class);
    }

    @Test void suspendedAccountIsDeniedAfterWaitingForStatusUpdate() throws Exception {
        pgOnly();
        Fixture f=setup();
        var lockHeld=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var suspend=pool.submit(()->tx.execute(status->{
                jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",f.staff());
                lockHeld.countDown();
                await(finish);
                return true;
            }));
            assertThat(lockHeld.await(10,TimeUnit.SECONDS)).isTrue();
            var command=pool.submit(()->{
                try {
                    return tx.execute(status->{
                        authorization.requireForCommand(f.staff(),f.tenant(),
                                DomainPermission.INVENTORY_RECEIVE);
                        return true;
                    });
                } catch(SecurityException denied) {
                    return false;
                }
            });
            try {
                assertThatThrownBy(()->command.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                finish.countDown();
            }
            assertThat(suspend.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(command.get(20,TimeUnit.SECONDS)).isFalse();
        }
    }


    private VerifiedActor actor(UUID user) {
        return new VerifiedActor(user,Instant.now().plusSeconds(120));
    }

    @Test void membershipSuspensionWaitsForAdmittedCommandAndDeniesLaterCommands() throws Exception {
        pgOnly();
        Fixture f=setup();
        var admitted=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var command=pool.submit(()->tx.execute(status->{
                authorization.requireForCommand(f.staff(),f.tenant(),DomainPermission.INVENTORY_RECEIVE);
                admitted.countDown();
                await(finish);
                return true;
            }));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var suspend=pool.submit(()->{
                access.suspendMember(actor(f.owner()),f.tenant(),f.staff());
                return true;
            });
            try {
                assertThatThrownBy(()->suspend.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                finish.countDown();
            }
            assertThat(command.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(suspend.get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThatThrownBy(()->authorization.require(f.staff(),f.tenant(),DomainPermission.INVENTORY_RECEIVE))
                .isInstanceOf(SecurityException.class);
    }

    @Test void membershipSuspensionCommittedFirstDeniesWaitingCommand() throws Exception {
        pgOnly();
        Fixture f=setup();
        var fenced=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var suspend=pool.submit(()->tx.execute(status->{
                access.suspendMember(actor(f.owner()),f.tenant(),f.staff());
                fenced.countDown();
                await(finish);
                return true;
            }));
            assertThat(fenced.await(10,TimeUnit.SECONDS)).isTrue();
            var command=pool.submit(()->{
                try {
                    return tx.execute(status->{
                        authorization.requireForCommand(f.staff(),f.tenant(),DomainPermission.INVENTORY_RECEIVE);
                        return true;
                    });
                } catch(SecurityException denied) {
                    return false;
                }
            });
            try {
                assertThatThrownBy(()->command.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                finish.countDown();
            }
            assertThat(suspend.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(command.get(20,TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test void tenantSuspensionWaitsForAdmittedCommandAndDeniesLaterCommands() throws Exception {
        pgOnly();
        Fixture f=setup();
        var admitted=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var command=pool.submit(()->tx.execute(status->{
                authorization.requireForCommand(f.staff(),f.tenant(),DomainPermission.INVENTORY_RECEIVE);
                admitted.countDown();
                await(finish);
                return true;
            }));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var suspend=pool.submit(()->{
                access.suspendTenant(actor(f.owner()),f.tenant());
                return true;
            });
            try {
                assertThatThrownBy(()->suspend.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                finish.countDown();
            }
            assertThat(command.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(suspend.get(20,TimeUnit.SECONDS)).isTrue();
        }
        assertThatThrownBy(()->authorization.require(f.staff(),f.tenant(),DomainPermission.INVENTORY_RECEIVE))
                .isInstanceOf(SecurityException.class);
    }

    @Test void tenantSuspensionCommittedFirstDeniesWaitingCommand() throws Exception {
        pgOnly();
        Fixture f=setup();
        var fenced=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var suspend=pool.submit(()->tx.execute(status->{
                access.suspendTenant(actor(f.owner()),f.tenant());
                fenced.countDown();
                await(finish);
                return true;
            }));
            assertThat(fenced.await(10,TimeUnit.SECONDS)).isTrue();
            var command=pool.submit(()->{
                try {
                    return tx.execute(status->{
                        authorization.requireForCommand(f.staff(),f.tenant(),DomainPermission.INVENTORY_RECEIVE);
                        return true;
                    });
                } catch(SecurityException denied) {
                    return false;
                }
            });
            try {
                assertThatThrownBy(()->command.get(250,TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                finish.countDown();
            }
            assertThat(suspend.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(command.get(20,TimeUnit.SECONDS)).isFalse();
        }
    }


    @Test void cachedJpaMembershipCannotAuthorizeAfterCommittedSuspension() throws Exception {
        pgOnly();
        Fixture f=setup();
        UUID organizationId=jdbc.queryForObject("""
                SELECT organization_id FROM public.iam_workspace WHERE id=?
                """,UUID.class,f.tenant());
        var loaded=new CountDownLatch(1);
        var proceed=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var command=pool.submit(()->{
                try {
                    return tx.execute(status->{
                var cached=memberships.findByOrganizationIdAndUserId(organizationId,f.staff())
                        .orElseThrow();
                assertThat(cached.getStatus()).isEqualTo(OrganizationMembershipEntity.Status.ACTIVE);
                loaded.countDown();
                await(proceed);
                    authorization.requireForCommand(f.staff(),f.tenant(),DomainPermission.INVENTORY_RECEIVE);
                    return true;
                    });
                } catch(SecurityException | UnexpectedRollbackException denied) {
                    return false; // rejected command transaction is rolled back
                }
            });
            assertThat(loaded.await(10,TimeUnit.SECONDS)).isTrue();
            access.suspendMember(actor(f.owner()),f.tenant(),f.staff());
            proceed.countDown();
            assertThat(command.get(20,TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test void cachedJpaWorkspaceCannotAuthorizeAfterCommittedSuspension() throws Exception {
        pgOnly();
        Fixture f=setup();
        var loaded=new CountDownLatch(1);
        var proceed=new CountDownLatch(1);
        var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var command=pool.submit(()->{
                try {
                    return tx.execute(status->{
                var cached=workspaces.findById(f.tenant()).orElseThrow();
                assertThat(cached.getStatus()).isEqualTo(WorkspaceEntity.Status.ACTIVE);
                loaded.countDown();
                await(proceed);
                    authorization.requireForCommand(f.staff(),f.tenant(),DomainPermission.INVENTORY_RECEIVE);
                    return true;
                    });
                } catch(SecurityException | UnexpectedRollbackException denied) {
                    return false; // rejected command transaction is rolled back
                }
            });
            assertThat(loaded.await(10,TimeUnit.SECONDS)).isTrue();
            access.suspendTenant(actor(f.owner()),f.tenant());
            proceed.countDown();
            assertThat(command.get(20,TimeUnit.SECONDS)).isFalse();
        }
    }

}
