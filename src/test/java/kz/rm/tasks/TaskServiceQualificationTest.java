package kz.rm.tasks;

import kz.rm.iam.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class TaskServiceQualificationTest {
    @Autowired TaskService tasks;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private record Tenant(UUID id, VerifiedActor manager,VerifiedActor staff,UUID orgId) {}

    private VerifiedActor verified(UUID user) {
        String subject=jdbc.queryForObject("""
            SELECT external_subject FROM public.iam_user_account WHERE id=?
            """,String.class,user);
        JwtDecoder decoder=token->Jwt.withTokenValue(token)
            .header("alg","RS256").subject(subject).issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600)).build();
        return new OidcActorResolver(decoder,users).resolve("task-test");
    }

    private Tenant tenant() {
        UUID owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-owner-"+UUID.randomUUID(),"Task owner")).getId();
        UUID manager=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-manager-"+UUID.randomUUID(),"Task manager")).getId();
        UUID staff=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-staff-"+UUID.randomUUID(),"Task employee")).getId();
        UUID org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),
            "TASK LLP","TASK workspace","KZ")).getId();
        UUID tenant=workspaces.saveAndFlush(
            WorkspaceEntity.organization(UUID.randomUUID(),org)).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),org,owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),org,manager,OrganizationMembershipEntity.Role.MANAGER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),org,staff,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        VerifiedActor a=verified(owner),b=verified(staff);
        var managerRole=roles.createRole(a,tenant,
            "TASK_M_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
            "Task manager");
        roles.grant(a,tenant,managerRole.id(),DomainPermission.TASK_MANAGE);
        roles.grant(a,tenant,managerRole.id(),DomainPermission.TASK_READ);
        roles.assign(a,tenant,manager,managerRole.id());
        var employee=roles.createRole(a,tenant,
            "TASK_E_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
            "Task employee");
        roles.grant(a,tenant,employee.id(),DomainPermission.TASK_EXECUTE);
        roles.grant(a,tenant,employee.id(),DomainPermission.TASK_READ);
        roles.assign(a,tenant,staff,employee.id());
        roles.enable(a,tenant);
        return new Tenant(tenant,verified(manager),b,org);
    }

    private static final Instant DUE=Instant.parse("2027-02-01T08:00:00Z");

    @Test void t01ManagerCreatesAssignedTaskAndEmployeeCompletesHistory() {
        var t=tenant();
        var created=tasks.create(t.manager(),t.id(),"Prepare customer call",
            t.staff().userId(),DUE,"task-create-001");
        assertThat(created.state()).isEqualTo(TaskState.OPEN);
        assertThat(created.version()).isZero();
        assertThat(tasks.create(t.manager(),t.id(),"Prepare customer call",
            t.staff().userId(),DUE,"task-create-001").id()).isEqualTo(created.id());
        assertThatThrownBy(()->tasks.create(t.manager(),t.id(),"Other task",
            t.staff().userId(),DUE,"task-create-001"))
            .hasMessage("TASK_REQUEST_REPLAY_CONFLICT");
        assertThat(tasks.listMine(t.staff(),t.id(),null,10,0))
            .extracting(TaskService.Task::id).contains(created.id());
        var started=tasks.start(t.staff(),t.id(),created.id(),0);
        assertThat(started.state()).isEqualTo(TaskState.IN_PROGRESS);
        var blocked=tasks.block(t.staff(),t.id(),created.id(),1);
        assertThat(blocked.state()).isEqualTo(TaskState.BLOCKED);
        var resumed=tasks.resume(t.staff(),t.id(),created.id(),2);
        assertThat(resumed.state()).isEqualTo(TaskState.IN_PROGRESS);
        var done=tasks.complete(t.staff(),t.id(),created.id(),3);
        assertThat(done.state()).isEqualTo(TaskState.COMPLETED);
        assertThatThrownBy(()->tasks.start(t.staff(),t.id(),created.id(),4))
            .hasMessage("TASK_TRANSITION_INVALID");
        var archived=tasks.archive(t.manager(),t.id(),created.id(),4);
        assertThat(archived.state()).isEqualTo(TaskState.ARCHIVED);
        assertThat(tasks.historyMine(t.manager(),t.id(),created.id(),10))
            .extracting(TaskService.Event::action)
            .containsExactly("ARCHIVED","COMPLETED","RESUMED","BLOCKED","STARTED","CREATED");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),created.id())).isEqualTo(6);
    }

    @Test void t02TenantIsolationAndEmployeeCannotManageOrArchive() {
        var a=tenant();var b=tenant();
        var task=tasks.create(a.manager(),a.id(),"Tenant A",
            a.staff().userId(),DUE,"task-isolation-001");
        assertThatThrownBy(()->tasks.getMine(b.manager(),b.id(),task.id()))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->tasks.start(b.staff(),b.id(),task.id(),0))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->tasks.create(a.staff(),a.id(),"Escalation",
            a.staff().userId(),DUE,"task-escalate-001"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->tasks.archive(a.staff(),a.id(),task.id(),0))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->tasks.assign(a.manager(),a.id(),task.id(),
            b.staff().userId(),0)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT row_version FROM crm.task_item WHERE tenant_id=? AND id=?
            """,Long.class,a.id(),task.id())).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_item WHERE tenant_id=? AND id=?
            """,Integer.class,b.id(),task.id())).isZero();
    }

    @Test void t03StaleVersionAndInvalidTransitionLeaveNoEvent() {
        var a=tenant();
        var task=tasks.create(a.manager(),a.id(),"Version check",
            a.staff().userId(),DUE,"task-version-001");
        assertThatThrownBy(()->tasks.complete(a.staff(),a.id(),task.id(),0))
            .hasMessage("TASK_TRANSITION_INVALID");
        assertThat(tasks.start(a.staff(),a.id(),task.id(),0).version()).isEqualTo(1);
        assertThatThrownBy(()->tasks.complete(a.staff(),a.id(),task.id(),0))
            .hasMessage("TASK_STALE_VERSION");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,a.id(),task.id())).isEqualTo(2);
    }

    @Test void t04PostgresqlConcurrentStartAllowsExactlyOneTransition() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        var created=tasks.create(t.manager(),t.id(),"Concurrent start",
            t.staff().userId(),DUE,"task-concurr-001");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                try {tasks.start(t.staff(),t.id(),created.id(),0);return "OK";}
                catch(IllegalStateException e){return e.getMessage();}
            });
            var second=pool.submit(()->{
                start.await(5,TimeUnit.SECONDS);
                try {tasks.start(t.staff(),t.id(),created.id(),0);return "OK";}
                catch(IllegalStateException e){return e.getMessage();}
            });
            start.countDown();
            assertThat(java.util.List.of(first.get(12,TimeUnit.SECONDS),
                second.get(12,TimeUnit.SECONDS))).containsExactlyInAnyOrder(
                    "OK","TASK_STALE_VERSION");
        }
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),created.id())).isEqualTo(2);
    }

    @Test void t05PostgresqlImmutableTaskHistoryRejectsTampering() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        var created=tasks.create(t.manager(),t.id(),"Immutable task history",
            t.staff().userId(),DUE,"task-immut-001");
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,t.id(),created.id())).isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),created.id())).isEqualTo(1);
    }
    @Test void t06ReassignmentHistoryCapturesBothEmployeesAndRevokesOldVisibility() {
        var t=tenant();
        var created=tasks.create(t.manager(),t.id(),"Handover work",
            t.staff().userId(),DUE,"task-reassign-001");
        UUID replacement=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-replacement-"+UUID.randomUUID(),"Replacement")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),replacement,OrganizationMembershipEntity.Role.MEMBER));
        var reassigned=tasks.assign(t.manager(),t.id(),created.id(),replacement,0);
        assertThat(reassigned.assigneeUserId()).isEqualTo(replacement);
        assertThat(reassigned.version()).isEqualTo(1);
        var events=tasks.historyMine(t.manager(),t.id(),created.id(),10);
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().action()).isEqualTo("ASSIGNED");
        assertThat(events.getFirst().previousAssigneeUserId())
            .isEqualTo(t.staff().userId());
        assertThat(events.getFirst().nextAssigneeUserId()).isEqualTo(replacement);
        assertThat(events.get(1).previousAssigneeUserId()).isNull();
        assertThat(events.get(1).nextAssigneeUserId()).isEqualTo(t.staff().userId());
        assertThatThrownBy(()->tasks.getMine(t.staff(),t.id(),created.id()))
            .hasMessage("TASK_NOT_ACCESSIBLE");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event
            WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),created.id())).isEqualTo(2);
    }

    @Test void t07SuspendedAssigneeCannotReceiveNewTaskOrReassignment() {
        var t=tenant();
        var created=tasks.create(t.manager(),t.id(),"Keep task",
            t.staff().userId(),DUE,"task-keep-001");
        UUID suspended=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-disabled-"+UUID.randomUUID(),"Disabled")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),suspended,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",suspended);
        assertThatThrownBy(()->tasks.assign(t.manager(),t.id(),created.id(),suspended,0))
            .hasMessage("TASK_ASSIGNEE_MUST_BE_ACTIVE_MEMBER");
        assertThatThrownBy(()->tasks.create(t.manager(),t.id(),"Forbidden",
            suspended,DUE,"task-blocked-001"))
            .hasMessage("TASK_ASSIGNEE_MUST_BE_ACTIVE_MEMBER");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_item WHERE tenant_id=?
            """,Integer.class,t.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),created.id())).isEqualTo(1);
    }

    @Test void t08PostgresqlSuspensionWaitsUntilAdmittedCreationCommits() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        var admitted=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var suspendStarted=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var create=pool.submit(()->tx.execute(status->{
                var task=tasks.create(t.manager(),t.id(),"Lock protected",
                    t.staff().userId(),DUE,"task-race-lock-001");
                admitted.countDown();
                try {
                    if(!release.await(10,TimeUnit.SECONDS))
                        throw new IllegalStateException("TASK_RACE_TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return task;
            }));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var suspension=pool.submit(()->{
                suspendStarted.countDown();
                return jdbc.update("""
                    UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?
                    """,t.staff().userId());
            });
            assertThat(suspendStarted.await(10,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var task=create.get(15,TimeUnit.SECONDS);
            assertThat(task.state()).isEqualTo(TaskState.OPEN);
            assertThat(suspension.get(15,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                SELECT status FROM public.iam_user_account WHERE id=?
                """,String.class,t.staff().userId())).isEqualTo("SUSPENDED");
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.task_event
                WHERE tenant_id=? AND task_id=?
                """,Integer.class,t.id(),task.id())).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

}
