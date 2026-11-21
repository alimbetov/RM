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
}
