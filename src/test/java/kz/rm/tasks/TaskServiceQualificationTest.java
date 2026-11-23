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
    @Autowired TaskCrmFollowupService followups;
    @Autowired TaskCrmReconciliationService reconciliation;
    @Autowired TaskReconciliationSnapshotService snapshots;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private record Tenant(UUID id, VerifiedActor manager,VerifiedActor staff,UUID orgId,VerifiedActor owner) {}

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
        roles.grant(a,tenant,managerRole.id(),DomainPermission.CRM_READ);
        roles.assign(a,tenant,manager,managerRole.id());
        var employee=roles.createRole(a,tenant,
            "TASK_E_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
            "Task employee");
        roles.grant(a,tenant,employee.id(),DomainPermission.TASK_EXECUTE);
        roles.grant(a,tenant,employee.id(),DomainPermission.TASK_READ);
        roles.assign(a,tenant,staff,employee.id());
        roles.enable(a,tenant);
        return new Tenant(tenant,verified(manager),b,org,a);
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
        assertThatThrownBy(()->tasks.create(t.manager(),t.id(),"Review order",
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
        var task=tasks.create(a.manager(),a.id(),"General operations",
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
        var task=tasks.create(a.manager(),a.id(),"Review document",
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
        var created=tasks.create(t.manager(),t.id(),"Check inventory",
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
        var created=tasks.create(t.manager(),t.id(),"Review order",
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
        var created=tasks.create(t.manager(),t.id(),"General operations",
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
        var created=tasks.create(t.manager(),t.id(),"Review document",
            t.staff().userId(),DUE,"task-keep-001");
        UUID suspended=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-disabled-"+UUID.randomUUID(),"Disabled")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),suspended,OrganizationMembershipEntity.Role.MEMBER));
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",suspended);
        assertThatThrownBy(()->tasks.assign(t.manager(),t.id(),created.id(),suspended,0))
            .hasMessage("TASK_ASSIGNEE_MUST_BE_ACTIVE_MEMBER");
        assertThatThrownBy(()->tasks.create(t.manager(),t.id(),"Review order",
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
                var task=tasks.create(t.manager(),t.id(),"Check inventory",
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

    @Test void t09LegacyNullAssigneeCanBeAssignedWithoutLosingAudit() {
        var t=tenant();
        UUID legacy=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.task_item
              (tenant_id,id,assignee_user_id,status,due_at,created_at,row_version,title)
            VALUES (?,?,NULL,'OPEN',?,?,0,?)
            """,t.id(),legacy,java.sql.Timestamp.from(DUE),
            java.sql.Timestamp.from(Instant.now()),"Migrated task");
        var assigned=tasks.assign(t.manager(),t.id(),legacy,t.staff().userId(),0);
        assertThat(assigned.assigneeUserId()).isEqualTo(t.staff().userId());
        assertThat(assigned.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event
            WHERE tenant_id=? AND task_id=? AND action_code='ASSIGNED'
              AND previous_assignee_user_id IS NULL AND next_assignee_user_id=?
            """,Integer.class,t.id(),legacy,t.staff().userId())).isEqualTo(1);
        assertThat(tasks.getMine(t.staff(),t.id(),legacy).id()).isEqualTo(legacy);
    }

    @Test void t10TitleVocabularyRejectsSensitiveFreeTextWithoutWrites() {
        var t=tenant();
        assertThatThrownBy(()->tasks.create(t.manager(),t.id(),
            "Customer alice@example.com",t.staff().userId(),DUE,"task-pii-001"))
            .hasMessage("TASK_TITLE_NOT_APPROVED");
        assertThatThrownBy(()->tasks.create(t.manager(),t.id(),
            "Call +77011234567",t.staff().userId(),DUE,"task-pii-002"))
            .hasMessage("TASK_TITLE_NOT_APPROVED");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_item WHERE tenant_id=?
            """,Integer.class,t.id())).isZero();
    }

    private UUID staffExecuteRole(Tenant t) {
        return jdbc.queryForObject("""
            SELECT a.role_id FROM public.iam_tenant_user_role a
            JOIN public.iam_tenant_role_permission p
              ON p.tenant_id=a.tenant_id AND p.role_id=a.role_id
            WHERE a.tenant_id=? AND a.user_id=? AND p.permission_code='TASK_EXECUTE'
            """,UUID.class,t.id(),t.staff().userId());
    }

    @Test void t11LostResponseRetryReturnsSameTaskWithoutDuplicateTransition() {
        var t=tenant();
        var created=tasks.create(t.manager(),t.id(),"Review order",
            t.staff().userId(),DUE,"task-retry-create-001");
        assertThat(tasks.create(t.manager(),t.id(),"Review order",
            t.staff().userId(),DUE,"task-retry-create-001").id()).isEqualTo(created.id());
        var started=tasks.start(t.staff(),t.id(),created.id(),0,"task-execute-retry-001");
        var replay=tasks.start(t.staff(),t.id(),created.id(),0,"task-execute-retry-001");
        assertThat(replay).isEqualTo(started);
        assertThatThrownBy(()->tasks.block(t.staff(),t.id(),created.id(),1,
            "task-execute-retry-001")).hasMessage("TASK_REQUEST_REPLAY_CONFLICT");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),created.id())).isEqualTo(2);
        tasks.block(t.staff(),t.id(),created.id(),1,"task-execute-block-001");
        assertThatThrownBy(()->tasks.start(t.staff(),t.id(),created.id(),0,
            "task-execute-retry-001")).hasMessage("TASK_RECEIPT_SUPERSEDED");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),created.id())).isEqualTo(3);
    }

    @Test void t12EarlierTaskPermissionRevocationDeniesExecutionWithoutWrites() {
        var t=tenant();
        var task=tasks.create(t.manager(),t.id(),"Check inventory",
            t.staff().userId(),DUE,"task-revoke-early-001");
        roles.revoke(t.owner(),t.id(),staffExecuteRole(t),DomainPermission.TASK_EXECUTE);
        assertThatThrownBy(()->tasks.start(t.staff(),t.id(),task.id(),0,
            "task-revoked-exec-001")).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(1);
    }

    @Test void t13EarlierMembershipRevocationDeniesAssigneeCreation() {
        var t=tenant();
        jdbc.update("""
            UPDATE public.iam_org_membership SET status='SUSPENDED'
            WHERE organization_id=? AND user_id=?
            """,t.orgId(),t.staff().userId());
        assertThatThrownBy(()->tasks.create(t.manager(),t.id(),"Check inventory",
            t.staff().userId(),DUE,"task-member-deny-001"))
            .hasMessage("TASK_ASSIGNEE_MUST_BE_ACTIVE_MEMBER");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_item WHERE tenant_id=?
            """,Integer.class,t.id())).isZero();
    }

    @Test void t14PostgresqlRoleRevocationWaitsForAdmittedExecutionCommit() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        var task=tasks.create(t.manager(),t.id(),"Review order",
            t.staff().userId(),DUE,"task-perm-race-001");
        UUID role=staffExecuteRole(t);
        var admitted=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var startedRevoke=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var execution=pool.submit(()->tx.execute(status->{
                var result=tasks.start(t.staff(),t.id(),task.id(),0,
                    "task-perm-race-key-001");
                admitted.countDown();
                try {
                    if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("TIMEOUT");
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();throw new IllegalStateException(e);
                }
                return result;
            }));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{
                startedRevoke.countDown();
                roles.revoke(t.owner(),t.id(),role,DomainPermission.TASK_EXECUTE);
                return true;
            });
            assertThat(startedRevoke.await(10,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(execution.get(15,TimeUnit.SECONDS).state())
                .isEqualTo(TaskState.IN_PROGRESS);
            assertThat(revoke.get(15,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->tasks.start(t.staff(),t.id(),task.id(),0,
                "task-perm-race-key-001")).isInstanceOf(SecurityException.class);
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
                """,Integer.class,t.id(),task.id())).isEqualTo(2);
        } finally {release.countDown();}
    }

    @Test void t15PostgresqlMembershipRevocationWaitsForTaskAssignmentCommit() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        var admitted=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var revocationRequested=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var creation=pool.submit(()->tx.execute(status->{
                var task=tasks.create(t.manager(),t.id(),"General operations",
                    t.staff().userId(),DUE,"task-membership-race-001");
                admitted.countDown();
                try {
                    if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();throw new IllegalStateException(e);
                }
                return task;
            }));
            assertThat(admitted.await(10,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->tx.execute(status->{
                revocationRequested.countDown();
                jdbc.queryForObject("""
                    SELECT id FROM public.iam_workspace WHERE id=? FOR UPDATE
                    """,UUID.class,t.id());
                return jdbc.update("""
                    UPDATE public.iam_org_membership SET status='SUSPENDED'
                    WHERE organization_id=? AND user_id=?
                    """,t.orgId(),t.staff().userId());
            }));
            assertThat(revocationRequested.await(10,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var task=creation.get(15,TimeUnit.SECONDS);
            assertThat(revoke.get(15,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.task_event
                WHERE tenant_id=? AND task_id=?
                """,Integer.class,t.id(),task.id())).isEqualTo(1);
            assertThatThrownBy(()->tasks.start(t.staff(),t.id(),task.id(),0,
                "task-membership-after-001")).isInstanceOf(SecurityException.class);
        } finally {release.countDown();}
    }

    @Test void t16LostResponseAssignmentRetryUsesOneImmutableEvent() {
        var t=tenant();
        var task=tasks.create(t.manager(),t.id(),"Review order",
            t.staff().userId(),DUE,"task-assign-create-001");
        UUID replacement=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-retry-target-"+UUID.randomUUID(),"Replacement")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),replacement,OrganizationMembershipEntity.Role.MEMBER));
        var first=tasks.assign(t.manager(),t.id(),task.id(),replacement,0,
            "task-assignment-key-001");
        var retry=tasks.assign(t.manager(),t.id(),task.id(),replacement,0,
            "task-assignment-key-001");
        assertThat(retry).isEqualTo(first);
        assertThatThrownBy(()->tasks.assign(t.manager(),t.id(),task.id(),
            t.staff().userId(),0,"task-assignment-key-001"))
            .hasMessage("TASK_REQUEST_REPLAY_CONFLICT");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(2);
    }

    @Test void t17ArchiveRetryAndCrossTaskKeyCollisionFailClosed() {
        var t=tenant();
        var first=tasks.create(t.manager(),t.id(),"Review order",
            t.staff().userId(),DUE,"task-archive-create-001");
        var second=tasks.create(t.manager(),t.id(),"Check inventory",
            t.staff().userId(),DUE,"task-archive-create-002");
        tasks.start(t.staff(),t.id(),first.id(),0,"task-first-start-001");
        tasks.complete(t.staff(),t.id(),first.id(),1,"task-first-done-001");
        tasks.start(t.staff(),t.id(),second.id(),0,"task-second-start-001");
        tasks.complete(t.staff(),t.id(),second.id(),1,"task-second-done-001");
        var archived=tasks.archive(t.manager(),t.id(),first.id(),2,
            "task-archive-key-001");
        assertThat(tasks.archive(t.manager(),t.id(),first.id(),2,
            "task-archive-key-001")).isEqualTo(archived);
        assertThatThrownBy(()->tasks.archive(t.manager(),t.id(),second.id(),2,
            "task-archive-key-001")).hasMessage("TASK_REQUEST_REPLAY_CONFLICT");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event
            WHERE tenant_id=? AND task_id=? AND action_code='ARCHIVED'
            """,Integer.class,t.id(),first.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event
            WHERE tenant_id=? AND task_id=? AND action_code='ARCHIVED'
            """,Integer.class,t.id(),second.id())).isZero();
    }

    private UUID crmInteraction(Tenant t,String state) {
        UUID customer=UUID.randomUUID();
        UUID interaction=UUID.randomUUID();
        var now=java.sql.Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO crm.customer
              (tenant_id,id,customer_type,organization_label,status,created_at,row_version)
            VALUES (?,?,'COMPANY','Synthetic CRM customer','ACTIVE',?,0)
            """,t.id(),customer,now);
        jdbc.update("""
            INSERT INTO crm.customer_interaction
              (tenant_id,id,customer_id,kind,status,responsible_user_id,
               occurred_at,request_key,request_sha256,created_by,created_at,updated_at,row_version)
            VALUES (?,?,?,'CALL',?,?,?, ?,?,?,?, ?,0)
            """,t.id(),interaction,customer,state,t.manager().userId(),now,
            "crmtest_"+UUID.randomUUID().toString().replace("-",""),
            new byte[32],t.manager().userId(),now,now);
        return interaction;
    }

    @Test void t18CrmFollowupCreationRetryAndScopedLinkRead() {
        var t=tenant();
        UUID interaction=crmInteraction(t,"OPEN");
        var task=followups.create(t.manager(),t.id(),interaction,"Prepare customer call",
            t.staff().userId(),DUE,"task-crm-followup-001");
        assertThat(followups.create(t.manager(),t.id(),interaction,"Prepare customer call",
            t.staff().userId(),DUE,"task-crm-followup-001").id()).isEqualTo(task.id());
        var link=followups.getMine(t.manager(),t.id(),task.id());
        assertThat(link.interactionId()).isEqualTo(interaction);
        assertThat(link.taskId()).isEqualTo(task.id());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_crm_followup
            WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event
            WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(1);
        assertThatThrownBy(()->followups.getMine(t.staff(),t.id(),task.id()))
            .isInstanceOf(SecurityException.class);
    }

    @Test void t19CrmFollowupRejectsForeignAndClosedInteractionWithNoTasks() {
        var a=tenant();
        var b=tenant();
        UUID foreign=crmInteraction(b,"OPEN");
        UUID cancelled=crmInteraction(a,"CANCELLED");
        assertThatThrownBy(()->followups.create(a.manager(),a.id(),foreign,
            "Prepare customer call",a.staff().userId(),DUE,"task-crm-foreign-001"))
            .hasMessage("TASK_CRM_INTERACTION_NOT_ACTIVE");
        assertThatThrownBy(()->followups.create(a.manager(),a.id(),cancelled,
            "Prepare customer call",a.staff().userId(),DUE,"task-crm-closed-001"))
            .hasMessage("TASK_CRM_INTERACTION_NOT_ACTIVE");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_item WHERE tenant_id=?
            """,Integer.class,a.id())).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_crm_followup WHERE tenant_id=?
            """,Integer.class,a.id())).isZero();
    }

    @Test void t20CrmFollowupCannotReuseTaskRequestForAnotherInteraction() {
        var t=tenant();
        UUID first=crmInteraction(t,"OPEN");
        UUID second=crmInteraction(t,"OPEN");
        var task=followups.create(t.manager(),t.id(),first,"Review order",
            t.staff().userId(),DUE,"task-crm-conflict-001");
        assertThatThrownBy(()->followups.create(t.manager(),t.id(),second,"Review order",
            t.staff().userId(),DUE,"task-crm-conflict-001"))
            .hasMessage("TASK_CRM_LINK_REPLAY_CONFLICT");
        assertThat(followups.getMine(t.manager(),t.id(),task.id()).interactionId())
            .isEqualTo(first);
    }

    @Test void t21PostgresqlCrmLinkIsImmutableAndCompositeTenantFkIsEnforced() throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var a=tenant();
        var b=tenant();
        UUID interaction=crmInteraction(a,"OPEN");
        UUID foreign=crmInteraction(b,"OPEN");
        var task=followups.create(a.manager(),a.id(),interaction,"Review order",
            a.staff().userId(),DUE,"task-crm-pg-fk-001");
        assertThatThrownBy(()->jdbc.update("""
            UPDATE crm.task_crm_followup SET interaction_id=?
            WHERE tenant_id=? AND task_id=?
            """,foreign,a.id(),task.id())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM crm.task_crm_followup WHERE tenant_id=? AND task_id=?
            """,a.id(),task.id())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.task_crm_followup
              (tenant_id,task_id,interaction_id,created_by,linked_at)
            VALUES (?,?,?,?,?)
            """,a.id(),UUID.randomUUID(),foreign,a.manager().userId(),
            java.sql.Timestamp.from(Instant.now()))).isInstanceOf(Exception.class);
        assertThat(followups.getMine(a.manager(),a.id(),task.id()).interactionId())
            .isEqualTo(interaction);
    }

    @Test void t22CrmFollowupRetryAfterSourceClosureReturnsOriginalTask() {
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        var original=followups.create(t.manager(),t.id(),source,"Review order",
            t.staff().userId(),DUE,"task-crm-closed-retry-001");
        jdbc.update("""
            UPDATE crm.customer_interaction
            SET status='COMPLETED',row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var retried=followups.create(t.manager(),t.id(),source,"Review order",
            t.staff().userId(),DUE,"task-crm-closed-retry-001");
        assertThat(retried.id()).isEqualTo(original.id());
        assertThatThrownBy(()->followups.create(t.manager(),t.id(),source,"Review order",
            t.staff().userId(),DUE,"task-crm-closed-new-001"))
            .hasMessage("TASK_CRM_INTERACTION_NOT_ACTIVE");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_crm_followup
            WHERE tenant_id=? AND interaction_id=?
            """,Integer.class,t.id(),source)).isEqualTo(1);
    }

    @Test void t23ManagerReschedulePreservesDueEvidenceAndKeyedReplay() {
        var t=tenant();
        var created=tasks.create(t.manager(),t.id(),"Review order",
            t.staff().userId(),DUE,"task-due-create-001");
        Instant next=DUE.plusSeconds(172800);
        var changed=tasks.reschedule(t.manager(),t.id(),created.id(),next,0,
            "task-due-change-001");
        assertThat(changed.dueAt()).isEqualTo(next);
        assertThat(changed.version()).isEqualTo(1);
        assertThat(tasks.reschedule(t.manager(),t.id(),created.id(),next,0,
            "task-due-change-001")).isEqualTo(changed);
        assertThatThrownBy(()->tasks.reschedule(t.manager(),t.id(),created.id(),
            next.plusSeconds(3600),0,"task-due-change-001"))
            .hasMessage("TASK_REQUEST_REPLAY_CONFLICT");
        var events=tasks.historyMine(t.manager(),t.id(),created.id(),10);
        assertThat(events.getFirst().action()).isEqualTo("DUE_DATE_CHANGED");
        assertThat(events.getFirst().previousDueAt()).isEqualTo(DUE);
        assertThat(events.getFirst().nextDueAt()).isEqualTo(next);
        assertThat(events).hasSize(2);
        assertThatThrownBy(()->tasks.reschedule(t.staff(),t.id(),created.id(),
            next.plusSeconds(1),1,"task-due-employee-001"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->tasks.reschedule(t.manager(),t.id(),created.id(),
            next.plusSeconds(1),0,"task-due-stale-001"))
            .hasMessage("TASK_STALE_VERSION");
    }

    @Test void t24TerminalTaskCannotRescheduleAndReplayedOldDueReceiptIsSuperseded() {
        var t=tenant();
        var task=tasks.create(t.manager(),t.id(),"Check inventory",
            t.staff().userId(),DUE,"task-due-term-001");
        Instant next=DUE.plusSeconds(3600);
        tasks.reschedule(t.manager(),t.id(),task.id(),next,0,"task-due-term-key-001");
        tasks.start(t.staff(),t.id(),task.id(),1,"task-due-start-001");
        tasks.complete(t.staff(),t.id(),task.id(),2,"task-due-done-001");
        assertThatThrownBy(()->tasks.reschedule(t.manager(),t.id(),task.id(),
            next.plusSeconds(3600),3,"task-due-after-done-001"))
            .hasMessage("TASK_RESCHEDULE_TERMINAL");
        assertThatThrownBy(()->tasks.reschedule(t.manager(),t.id(),task.id(),
            next,0,"task-due-term-key-001"))
            .hasMessage("TASK_RECEIPT_SUPERSEDED");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(4);
    }

    @Test void t25ClosedCrmSourcePreservesLinkedTaskAndHistoryButRejectsNewLink() {
        var t=tenant();
        UUID interaction=crmInteraction(t,"OPEN");
        var task=followups.create(t.manager(),t.id(),interaction,"Prepare customer call",
            t.staff().userId(),DUE,"task-source-cancel-001");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),interaction);
        assertThat(followups.getMine(t.manager(),t.id(),task.id()).interactionId())
            .isEqualTo(interaction);
        assertThat(tasks.start(t.staff(),t.id(),task.id(),0).state())
            .isEqualTo(TaskState.IN_PROGRESS);
        assertThat(tasks.complete(t.staff(),t.id(),task.id(),1).state())
            .isEqualTo(TaskState.COMPLETED);
        assertThatThrownBy(()->followups.create(t.manager(),t.id(),interaction,
            "Prepare customer call",t.staff().userId(),DUE,"task-source-cancel-new-001"))
            .hasMessage("TASK_CRM_INTERACTION_NOT_ACTIVE");
        assertThat(tasks.historyMine(t.manager(),t.id(),task.id(),10)).hasSize(3);
    }

    @Test void t26RollbackBeforeFollowupCommitAllowsExactRequestRetry() {
        var t=tenant();
        UUID interaction=crmInteraction(t,"OPEN");
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{
            followups.create(t.manager(),t.id(),interaction,"Review order",
                t.staff().userId(),DUE,"task-rollback-followup-001");
            throw new IllegalStateException("TEST_CRASH_BEFORE_COMMIT");
        })).hasMessage("TEST_CRASH_BEFORE_COMMIT");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_item WHERE tenant_id=? AND created_request_key=?
            """,Integer.class,t.id(),"task-rollback-followup-001")).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_crm_followup WHERE tenant_id=?
            """,Integer.class,t.id())).isZero();
        var task=followups.create(t.manager(),t.id(),interaction,"Review order",
            t.staff().userId(),DUE,"task-rollback-followup-001");
        assertThat(followups.getMine(t.manager(),t.id(),task.id()).interactionId())
            .isEqualTo(interaction);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_item WHERE tenant_id=? AND created_request_key=?
            """,Integer.class,t.id(),"task-rollback-followup-001")).isEqualTo(1);
    }

    @Test void t27PostgresqlConcurrentRescheduleAndReassignmentHaveSingleWinner()
             throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        UUID replacement=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-race-target-"+UUID.randomUUID(),"Other assignee")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),replacement,OrganizationMembershipEntity.Role.MEMBER));
        var task=tasks.create(t.manager(),t.id(),"General operations",
            t.staff().userId(),DUE,"task-r2-race-create-001");
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var due=pool.submit(()->{
                gate.await(5,TimeUnit.SECONDS);
                try {tasks.reschedule(t.manager(),t.id(),task.id(),DUE.plusSeconds(7200),
                      0,"task-r2-race-due-001");return "OK";}
                catch(IllegalStateException e){return e.getMessage();}
            });
            var assignment=pool.submit(()->{
                gate.await(5,TimeUnit.SECONDS);
                try {tasks.assign(t.manager(),t.id(),task.id(),replacement,
                      0,"task-r2-race-assign-001");return "OK";}
                catch(IllegalStateException e){return e.getMessage();}
            });
            gate.countDown();
            assertThat(java.util.List.of(due.get(20,TimeUnit.SECONDS),
                assignment.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(
                    "OK","TASK_STALE_VERSION");
        }
        assertThat(jdbc.queryForObject("""
            SELECT row_version FROM crm.task_item WHERE tenant_id=? AND id=?
            """,Long.class,t.id(),task.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(2);
    }

    @Test void t28PostgresqlTwoManagersConcurrentReassignmentExactlyOneWins()
             throws Exception {
        try(var connection=dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        UUID manager2=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-manager-two-"+UUID.randomUUID(),"Secondary manager")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),manager2,OrganizationMembershipEntity.Role.MANAGER));
        var role=roles.createRole(t.owner(),t.id(),
            "TASK_SECOND_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),
            "Second manager");
        roles.grant(t.owner(),t.id(),role.id(),DomainPermission.TASK_MANAGE);
        roles.assign(t.owner(),t.id(),manager2,role.id());
        VerifiedActor actor2=verified(manager2);
        UUID target1=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-new-one-"+UUID.randomUUID(),"Assignee one")).getId();
        UUID target2=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-new-two-"+UUID.randomUUID(),"Assignee two")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),target1,OrganizationMembershipEntity.Role.MEMBER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),target2,OrganizationMembershipEntity.Role.MEMBER));
        var task=tasks.create(t.manager(),t.id(),"General operations",
            t.staff().userId(),DUE,"task-double-manager-001");
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                gate.await(5,TimeUnit.SECONDS);
                try {tasks.assign(t.manager(),t.id(),task.id(),target1,0,
                    "task-double-manager-key1");return "OK";}
                catch(IllegalStateException e){return e.getMessage();}
            });
            var second=pool.submit(()->{
                gate.await(5,TimeUnit.SECONDS);
                try {tasks.assign(actor2,t.id(),task.id(),target2,0,
                    "task-double-manager-key2");return "OK";}
                catch(IllegalStateException e){return e.getMessage();}
            });
            gate.countDown();
            assertThat(java.util.List.of(first.get(20,TimeUnit.SECONDS),
                second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(
                    "OK","TASK_STALE_VERSION");
        }
        assertThat(jdbc.queryForObject("""
            SELECT row_version FROM crm.task_item WHERE tenant_id=? AND id=?
            """,Long.class,t.id(),task.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event
            WHERE tenant_id=? AND task_id=? AND action_code='ASSIGNED'
            """,Integer.class,t.id(),task.id())).isEqualTo(1);
    }

    @Test void t29ManagerCancellationIsIdempotentAndNotArchival() {
        var t=tenant();
        var task=tasks.create(t.manager(),t.id(),"Review order",t.staff().userId(),
            DUE,"task-r3-cancel-create-001");
        var cancelled=tasks.cancel(t.manager(),t.id(),task.id(),0,"task-r3-cancel-key-001");
        assertThat(cancelled.state()).isEqualTo(TaskState.CANCELLED);
        assertThat(cancelled.version()).isEqualTo(1);
        assertThat(tasks.cancel(t.manager(),t.id(),task.id(),0,
            "task-r3-cancel-key-001")).isEqualTo(cancelled);
        assertThatThrownBy(()->tasks.archive(t.manager(),t.id(),task.id(),1))
            .hasMessage("TASK_ARCHIVE_REQUIRES_COMPLETED");
        assertThatThrownBy(()->tasks.start(t.staff(),t.id(),task.id(),1))
            .hasMessage("TASK_TRANSITION_INVALID");
        assertThat(tasks.historyMine(t.manager(),t.id(),task.id(),10).getFirst()
            .reasonCode()).isEqualTo("MANAGER_CANCELLED");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(2);
    }

    @Test void t30InProgressCancellationForbidsCompletionAndPreservesEvidence() {
        var t=tenant();
        var task=tasks.create(t.manager(),t.id(),"Review order",t.staff().userId(),
            DUE,"task-r3-progress-create");
        tasks.start(t.staff(),t.id(),task.id(),0,"task-r3-progress-start");
        var cancelled=tasks.cancel(t.manager(),t.id(),task.id(),1,"task-r3-progress-cancel");
        assertThat(cancelled.state()).isEqualTo(TaskState.CANCELLED);
        assertThatThrownBy(()->tasks.complete(t.staff(),t.id(),task.id(),2))
            .hasMessage("TASK_TRANSITION_INVALID");
        assertThatThrownBy(()->tasks.requeue(t.manager(),t.id(),task.id(),2,
            "task-r3-progress-requeue")).hasMessage("TASK_REQUEUE_STATE_INVALID");
        assertThat(tasks.historyMine(t.manager(),t.id(),task.id(),10))
            .extracting(TaskService.Event::action)
            .containsExactly("CANCELLED","STARTED","CREATED");
    }

    @Test void t31RequeueRemovesAssignmentAndManagerMayClaimForNewStaff() {
        var t=tenant();
        var task=tasks.create(t.manager(),t.id(),"General operations",t.staff().userId(),
            DUE,"task-r3-queue-create-001");
        var queued=tasks.requeue(t.manager(),t.id(),task.id(),0,"task-r3-queue-key-001");
        assertThat(queued.state()).isEqualTo(TaskState.QUEUED);
        assertThat(queued.assigneeUserId()).isNull();
        assertThat(tasks.requeue(t.manager(),t.id(),task.id(),0,
            "task-r3-queue-key-001")).isEqualTo(queued);
        assertThatThrownBy(()->tasks.getMine(t.staff(),t.id(),task.id()))
            .hasMessage("TASK_NOT_ACCESSIBLE");
        UUID replacement=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "r3-replacement-"+UUID.randomUUID(),"Replacement")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),replacement,OrganizationMembershipEntity.Role.MEMBER));
        var assigned=tasks.assign(t.manager(),t.id(),task.id(),replacement,1,
            "task-r3-queue-assign-001");
        assertThat(assigned.state()).isEqualTo(TaskState.OPEN);
        assertThat(assigned.assigneeUserId()).isEqualTo(replacement);
        assertThat(tasks.historyMine(t.manager(),t.id(),task.id(),10).getFirst()
            .from()).isEqualTo(TaskState.QUEUED);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(3);
    }

    @Test void t32RequeueRequiresStoppedWorkAndCannotBeStartedUnassigned() {
        var t=tenant();
        var task=tasks.create(t.manager(),t.id(),"Review document",t.staff().userId(),
            DUE,"task-r3-block-create-001");
        tasks.start(t.staff(),t.id(),task.id(),0,"task-r3-block-start-001");
        assertThatThrownBy(()->tasks.requeue(t.manager(),t.id(),task.id(),1,
            "task-r3-not-blocked-001")).hasMessage("TASK_REQUEUE_STATE_INVALID");
        tasks.block(t.staff(),t.id(),task.id(),1,"task-r3-block-now-001");
        var queued=tasks.requeue(t.manager(),t.id(),task.id(),2,
            "task-r3-block-to-queue");
        assertThat(queued.state()).isEqualTo(TaskState.QUEUED);
        assertThatThrownBy(()->tasks.start(t.staff(),t.id(),task.id(),3))
            .hasMessage("TASK_ASSIGNEE_ONLY");
        assertThatThrownBy(()->tasks.reschedule(t.staff(),t.id(),task.id(),DUE,3,
            "task-r3-staff-edit-001")).isInstanceOf(SecurityException.class);
    }

    @Test void t33CancelledCrmInteractionRequiresExplicitScopedReconciliation() {
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        var open=followups.create(t.manager(),t.id(),source,"Review order",
            t.staff().userId(),DUE,"task-r3-crm-open-001");
        var done=followups.create(t.manager(),t.id(),source,"Check inventory",
            t.staff().userId(),DUE,"task-r3-crm-done-001");
        tasks.start(t.staff(),t.id(),done.id(),0,"task-r3-done-start-001");
        tasks.complete(t.staff(),t.id(),done.id(),1,"task-r3-done-complete-001");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
                row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var pending=reconciliation.pending(t.manager(),t.id(),source,100);
        assertThat(pending).extracting(TaskCrmReconciliationService.Pending::taskId)
            .containsExactly(open.id());
        assertThat(tasks.getMine(t.manager(),t.id(),open.id()).state())
            .isEqualTo(TaskState.OPEN);
        var cancelled=reconciliation.reconcileCancelled(t.manager(),t.id(),source,open.id(),
            0,"task-r3-reconcile-001");
        assertThat(cancelled.state()).isEqualTo(TaskState.CANCELLED);
        assertThat(reconciliation.reconcileCancelled(t.manager(),t.id(),source,open.id(),
            0,"task-r3-reconcile-001")).isEqualTo(cancelled);
        assertThat(reconciliation.pending(t.manager(),t.id(),source,100)).isEmpty();
        assertThat(tasks.getMine(t.manager(),t.id(),done.id()).state())
            .isEqualTo(TaskState.COMPLETED);
        assertThat(tasks.historyMine(t.manager(),t.id(),open.id(),10).getFirst()
            .reasonCode()).isEqualTo("SOURCE_CANCELLED");
    }

    @Test void t34ReconciliationAndQueueRollbackLeaveNoPartialEvents() {
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        var task=followups.create(t.manager(),t.id(),source,"General operations",
            t.staff().userId(),DUE,"task-r3-rollback-create");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
                row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{
            reconciliation.reconcileCancelled(t.manager(),t.id(),source,task.id(),0,
                "task-r3-rollback-key");
            throw new IllegalStateException("TASK_TEST_CRASH_BEFORE_COMMIT");
        })).hasMessage("TASK_TEST_CRASH_BEFORE_COMMIT");
        assertThat(tasks.getMine(t.manager(),t.id(),task.id()).state())
            .isEqualTo(TaskState.OPEN);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(1);
        assertThat(reconciliation.reconcileCancelled(t.manager(),t.id(),source,task.id(),0,
            "task-r3-rollback-key").state()).isEqualTo(TaskState.CANCELLED);
    }

    @Test void t35PostgresqlCancelRacesWithEmployeeCompletionExactlyOneWins() throws Exception {
        try(var conn=dataSource.getConnection()) {
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        var task=tasks.create(t.manager(),t.id(),"Review order",t.staff().userId(),
            DUE,"task-r3-cancel-race-create");
        tasks.start(t.staff(),t.id(),task.id(),0,"task-r3-cancel-race-start");
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{
                gate.await(5,TimeUnit.SECONDS);
                try {tasks.cancel(t.manager(),t.id(),task.id(),1,
                    "task-r3-cancel-race-key");return "OK";}
                catch(IllegalStateException e){return e.getMessage();}
            });
            var b=pool.submit(()->{
                gate.await(5,TimeUnit.SECONDS);
                try {tasks.complete(t.staff(),t.id(),task.id(),1,
                    "task-r3-complete-race-key");return "OK";}
                catch(IllegalStateException e){return e.getMessage();}
            });
            gate.countDown();
            assertThat(java.util.List.of(a.get(20,TimeUnit.SECONDS),
                b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(
                    "OK","TASK_STALE_VERSION");
        }
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(3);
    }

    @Test void t36PostgresqlRequeueRacesWithReassignmentExactlyOneWins() throws Exception {
        try(var conn=dataSource.getConnection()) {
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        UUID target=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "r3-race-target-"+UUID.randomUUID(),"Replacement")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),target,OrganizationMembershipEntity.Role.MEMBER));
        var task=tasks.create(t.manager(),t.id(),"General operations",t.staff().userId(),
            DUE,"task-r3-queue-race-create");
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{
                gate.await(5,TimeUnit.SECONDS);
                try {tasks.requeue(t.manager(),t.id(),task.id(),0,
                    "task-r3-queue-race-key");return "OK";}
                catch(IllegalStateException e){return e.getMessage();}
            });
            var b=pool.submit(()->{
                gate.await(5,TimeUnit.SECONDS);
                try {tasks.assign(t.manager(),t.id(),task.id(),target,0,
                    "task-r3-assign-race-key");return "OK";}
                catch(IllegalStateException e){return e.getMessage();}
            });
            gate.countDown();
            assertThat(java.util.List.of(a.get(20,TimeUnit.SECONDS),
                b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(
                    "OK","TASK_STALE_VERSION");
        }
        assertThat(jdbc.queryForObject("""
            SELECT row_version FROM crm.task_item WHERE tenant_id=? AND id=?
            """,Long.class,t.id(),task.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(2);
    }

    @Test void t37ReconciliationRejectsUnrelatedSourceAndForeignTenantWithoutTaskMutation() {
        var a=tenant();var b=tenant();
        UUID aSource=crmInteraction(a,"OPEN");
        UUID otherSource=crmInteraction(a,"OPEN");
        UUID foreignSource=crmInteraction(b,"OPEN");
        var task=followups.create(a.manager(),a.id(),aSource,"Review order",
            a.staff().userId(),DUE,"task-review-reconcile-source-001");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,a.id(),aSource);
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,a.id(),otherSource);
        assertThatThrownBy(()->reconciliation.reconcileCancelled(a.manager(),a.id(),
            otherSource,task.id(),0,"task-review-wrong-source-001"))
            .hasMessage("TASK_CRM_SOURCE_NOT_CANCELLED");
        assertThatThrownBy(()->reconciliation.reconcileCancelled(a.manager(),a.id(),
            foreignSource,task.id(),0,"task-review-foreign-source-001"))
            .hasMessage("TASK_CRM_SOURCE_NOT_CANCELLED");
        assertThatThrownBy(()->reconciliation.reconcileCancelled(b.manager(),b.id(),
            foreignSource,task.id(),0,"task-review-foreign-tenant-001"))
            .hasMessage("TASK_CRM_SOURCE_NOT_CANCELLED");
        assertThat(tasks.getMine(a.manager(),a.id(),task.id()).state())
            .isEqualTo(TaskState.OPEN);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,a.id(),task.id())).isEqualTo(1);
    }

    @Test void t38PostgresqlSourceCancellationSerializesAgainstFollowupCreation()
             throws Exception {
        try(var conn=dataSource.getConnection()) {
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        var created=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var closureStarted=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->tx.execute(status->{
                var task=followups.create(t.manager(),t.id(),source,"Review order",
                    t.staff().userId(),DUE,"task-review-source-race-001");
                created.countDown();
                try {
                    if(!release.await(15,TimeUnit.SECONDS))
                        throw new IllegalStateException("TASK_SOURCE_RACE_TIMEOUT");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return task;
            }));
            assertThat(created.await(15,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->{
                closureStarted.countDown();
                return jdbc.update("""
                    UPDATE crm.customer_interaction SET status='CANCELLED',
                    row_version=row_version+1 WHERE tenant_id=? AND id=?
                    """,t.id(),source);
            });
            assertThat(closureStarted.await(10,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var task=first.get(25,TimeUnit.SECONDS);
            assertThat(second.get(25,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(followups.getMine(t.manager(),t.id(),task.id()).interactionId())
                .isEqualTo(source);
            assertThatThrownBy(()->followups.create(t.manager(),t.id(),source,
                "Review order",t.staff().userId(),DUE,"task-review-source-race-new-001"))
                .hasMessage("TASK_CRM_INTERACTION_NOT_ACTIVE");
            assertThat(followups.create(t.manager(),t.id(),source,"Review order",
                t.staff().userId(),DUE,"task-review-source-race-001").id())
                .isEqualTo(task.id());
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.task_crm_followup
                WHERE tenant_id=? AND interaction_id=?
                """,Integer.class,t.id(),source)).isEqualTo(1);
        } finally {release.countDown();}
    }

    @Test void t39ManagerQueuePaginatesAndHidesUnassignedTasksFromEmployee() {
        var t=tenant();
        var created=new java.util.ArrayList<TaskService.Task>();
        for(int i=0;i<5;i++) {
            var task=tasks.create(t.manager(),t.id(),"General operations",
                t.staff().userId(),DUE,"task-r31-queue-"+i+"-001");
            tasks.requeue(t.manager(),t.id(),task.id(),0,"task-r31-requeue-"+i+"-001");
            created.add(task);
        }
        var seen=new java.util.HashSet<UUID>();
        UUID cursor=null;
        do {
            var page=tasks.managerQueue(t.manager(),t.id(),2,cursor);
            assertThat(page.items().size()).isBetween(1,2);
            for(var task:page.items()){
                assertThat(task.state()).isEqualTo(TaskState.QUEUED);
                assertThat(task.assigneeUserId()).isNull();
                assertThat(seen.add(task.id())).isTrue();
            }
            cursor=page.nextAfterId();
        }while(cursor!=null);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(
            created.stream().map(TaskService.Task::id).toList());
        assertThatThrownBy(()->tasks.managerQueue(t.staff(),t.id(),2,null))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->tasks.managerQueue(t.manager(),t.id(),101,null))
            .hasMessage("TASK_QUEUE_PAGE_INVALID");
        for(var task:created) {
            assertThatThrownBy(()->tasks.getMine(t.staff(),t.id(),task.id()))
                .hasMessage("TASK_NOT_ACCESSIBLE");
        }
    }

    @Test void t40ManagerQueueCannotExposeAnotherTenantOrUnauthorizedRole() {
        var a=tenant();
        var b=tenant();
        var task=tasks.create(a.manager(),a.id(),"General operations",
            a.staff().userId(),DUE,"task-r31-isolate-001");
        tasks.requeue(a.manager(),a.id(),task.id(),0,"task-r31-isolate-queue-001");
        assertThat(tasks.managerQueue(b.manager(),b.id(),20,null).items())
            .extracting(TaskService.Task::id).doesNotContain(task.id());
        assertThat(tasks.managerQueue(a.manager(),a.id(),20,null).items())
            .extracting(TaskService.Task::id).contains(task.id());
        assertThatThrownBy(()->tasks.managerQueue(b.staff(),a.id(),20,null))
            .isInstanceOf(SecurityException.class);
    }

    @Test void t41CancelledCrmReconciliationPageAdvancesWithoutRepeatingTasks() {
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        var created=new java.util.ArrayList<UUID>();
        for(int i=0;i<5;i++){
            var task=followups.create(t.manager(),t.id(),source,"Review order",
                t.staff().userId(),DUE,"task-r31-crm-page-"+i+"-001");
            created.add(task.id());
        }
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var first=reconciliation.pendingPage(t.manager(),t.id(),source,null,2);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextAfterTaskId()).isNotNull();
        var selected=first.items().getFirst();
        reconciliation.reconcileCancelled(t.manager(),t.id(),source,
            selected.taskId(),selected.version(),"task-r31-page-reconcile-001");
        var all=new java.util.HashSet<UUID>();
        for(var item:first.items())assertThat(all.add(item.taskId())).isTrue();
        UUID cursor=first.nextAfterTaskId();
        while(cursor!=null){
            var page=reconciliation.pendingPage(t.manager(),t.id(),source,cursor,2);
            for(var item:page.items())assertThat(all.add(item.taskId())).isTrue();
            cursor=page.nextAfterTaskId();
        }
        assertThat(all).containsExactlyInAnyOrderElementsOf(created);
        assertThat(reconciliation.pendingPage(t.manager(),t.id(),source,null,100).items())
            .extracting(TaskCrmReconciliationService.Pending::taskId)
            .doesNotContain(selected.taskId());
        assertThatThrownBy(()->reconciliation.pendingPage(t.staff(),t.id(),source,null,2))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->reconciliation.pendingPage(t.manager(),t.id(),source,null,0))
            .hasMessage("TASK_RECONCILE_QUERY_INVALID");
    }

    @Test void t42PostgresqlQueuePageDoesNotResurrectConcurrentlyClaimedTask() throws Exception {
        try(var conn=dataSource.getConnection()){
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        UUID replacement=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "task-r31-claim-"+UUID.randomUUID(),"Queue claim")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(
            UUID.randomUUID(),t.orgId(),replacement,OrganizationMembershipEntity.Role.MEMBER));
        var task=tasks.create(t.manager(),t.id(),"Review order",
            t.staff().userId(),DUE,"task-r31-claim-create-001");
        tasks.requeue(t.manager(),t.id(),task.id(),0,"task-r31-claim-requeue-001");
        var page=tasks.managerQueue(t.manager(),t.id(),10,null);
        assertThat(page.items()).extracting(TaskService.Task::id).contains(task.id());
        var claim=tasks.assign(t.manager(),t.id(),task.id(),replacement,1,
            "task-r31-claim-assign-001");
        assertThat(claim.state()).isEqualTo(TaskState.OPEN);
        assertThat(tasks.managerQueue(t.manager(),t.id(),10,null).items())
            .extracting(TaskService.Task::id).doesNotContain(task.id());
        assertThatThrownBy(()->tasks.assign(t.manager(),t.id(),task.id(),
            t.staff().userId(),1,"task-r31-stale-claim-001"))
            .hasMessage("TASK_STALE_VERSION");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?
            """,Integer.class,t.id(),task.id())).isEqualTo(3);
    }

    @Test void t43FrozenReconciliationTraversalSurvivesConcurrentTaskCancellation() {
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        var created=new java.util.ArrayList<UUID>();
        for(int i=0;i<5;i++) {
            var task=followups.create(t.manager(),t.id(),source,"General operations",
                t.staff().userId(),DUE,"task-r32-snapshot-"+i+"-001");
            created.add(task.id());
        }
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
                row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var snapshot=snapshots.capture(t.manager(),t.id(),source,
            "task-r32-capture-request-001");
        assertThat(snapshot.itemCount()).isEqualTo(5);
        assertThat(snapshots.capture(t.manager(),t.id(),source,
            "task-r32-capture-request-001").id()).isEqualTo(snapshot.id());
        var first=snapshots.page(t.manager(),t.id(),snapshot.id(),null,2);
        assertThat(first.items()).hasSize(2);
        var changed=first.items().getFirst();
        reconciliation.reconcileCancelled(t.manager(),t.id(),source,changed.taskId(),
            changed.capturedVersion(),"task-r32-reconcile-changed-001");
        var collected=new java.util.HashSet<UUID>();
        first.items().forEach(x->assertThat(collected.add(x.taskId())).isTrue());
        UUID cursor=first.nextAfterTaskId();
        while(cursor!=null) {
            var page=snapshots.page(t.manager(),t.id(),snapshot.id(),cursor,2);
            page.items().forEach(x->assertThat(collected.add(x.taskId())).isTrue());
            cursor=page.nextAfterTaskId();
        }
        assertThat(collected).containsExactlyInAnyOrderElementsOf(created);
        assertThat(snapshots.page(t.manager(),t.id(),snapshot.id(),null,100).items())
            .hasSize(5);
        assertThat(reconciliation.pending(t.manager(),t.id(),source,100)).hasSize(4);
        assertThatThrownBy(()->reconciliation.reconcileCancelled(
            t.manager(),t.id(),source,changed.taskId(),changed.capturedVersion(),
            "task-r32-stale-reconcile-001")).hasMessage("TASK_STALE_VERSION");
    }

    @Test void t44SnapshotTenantAndActorPrivacyRequestReplayAndInvalidSource() {
        var a=tenant();var b=tenant();
        UUID source=crmInteraction(a,"OPEN");
        assertThatThrownBy(()->snapshots.capture(a.manager(),a.id(),source,
            "task-r32-not-cancelled-001"))
            .hasMessage("TASK_SNAPSHOT_SOURCE_NOT_CANCELLED");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
                row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,a.id(),source);
        var snap=snapshots.capture(a.manager(),a.id(),source,"task-r32-private-001");
        assertThat(snap.itemCount()).isZero();
        assertThatThrownBy(()->snapshots.page(b.manager(),b.id(),snap.id(),null,10))
            .hasMessage("TASK_SNAPSHOT_NOT_ACCESSIBLE");
        assertThatThrownBy(()->snapshots.page(a.staff(),a.id(),snap.id(),null,10))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->snapshots.page(a.manager(),a.id(),snap.id(),null,101))
            .hasMessage("TASK_SNAPSHOT_PAGE_INVALID");
        UUID unrelated=crmInteraction(a,"OPEN");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
                row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,a.id(),unrelated);
        assertThatThrownBy(()->snapshots.capture(a.manager(),a.id(),unrelated,
            "task-r32-private-001")).hasMessage("TASK_SNAPSHOT_REQUEST_CONFLICT");
    }

    @Test void t45SnapshotRollbackBeforeCommitKeepsNoManifestAndRetryWorks() {
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        followups.create(t.manager(),t.id(),source,"Review order",
            t.staff().userId(),DUE,"task-r32-rollback-source-001");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
                row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{
            snapshots.capture(t.manager(),t.id(),source,"task-r32-snapshot-crash-001");
            throw new IllegalStateException("TASK_SNAPSHOT_SIMULATED_CRASH");
        })).hasMessage("TASK_SNAPSHOT_SIMULATED_CRASH");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_reconciliation_snapshot
            WHERE tenant_id=? AND request_key=?
            """,Integer.class,t.id(),"task-r32-snapshot-crash-001")).isZero();
        var retried=snapshots.capture(t.manager(),t.id(),source,
            "task-r32-snapshot-crash-001");
        assertThat(retried.itemCount()).isEqualTo(1);
        assertThat(snapshots.page(t.manager(),t.id(),retried.id(),null,5).items())
            .hasSize(1);
    }

    @Test void t46PostgresqlSnapshotManifestAndEntriesAreImmutable() throws Exception {
        try(var conn=dataSource.getConnection()) {
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        var task=followups.create(t.manager(),t.id(),source,"Review order",
            t.staff().userId(),DUE,"task-r32-immutable-source-001");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
                row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var snap=snapshots.capture(t.manager(),t.id(),source,
            "task-r32-immutable-capture-001");
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM crm.task_reconciliation_snapshot_item
            WHERE tenant_id=? AND snapshot_id=? AND task_id=?
            """,t.id(),snap.id(),task.id())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            UPDATE crm.task_reconciliation_snapshot SET item_count=0
            WHERE tenant_id=? AND id=?
            """,t.id(),snap.id())).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO crm.task_reconciliation_snapshot_item
            (tenant_id,snapshot_id,task_id,captured_state,captured_version)
            VALUES (?,?,?,'OPEN',0)
            """,t.id(),snap.id(),UUID.randomUUID())).isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_reconciliation_snapshot_seal
            WHERE tenant_id=? AND snapshot_id=? AND length(items_sha256)=64
            """,Integer.class,t.id(),snap.id())).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM crm.task_reconciliation_snapshot_seal
            WHERE tenant_id=? AND snapshot_id=?
            """,t.id(),snap.id())).isInstanceOf(Exception.class);
        assertThat(snapshots.page(t.manager(),t.id(),snap.id(),null,10).items())
            .hasSize(1);
    }

    @Test void t47PostgresqlConcurrentSnapshotSameRequestCommitsSingleSealedManifest()
            throws Exception {
        try(var conn=dataSource.getConnection()) {
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        followups.create(t.manager(),t.id(),source,"Review order",
            t.staff().userId(),DUE,"task-r32-samekey-source-001");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{
                gate.await(10,TimeUnit.SECONDS);
                return snapshots.capture(t.manager(),t.id(),source,"task-r32-samekey-capture-001");
            });
            var b=pool.submit(()->{
                gate.await(10,TimeUnit.SECONDS);
                return snapshots.capture(t.manager(),t.id(),source,"task-r32-samekey-capture-001");
            });
            gate.countDown();
            var first=a.get(30,TimeUnit.SECONDS);
            var second=b.get(30,TimeUnit.SECONDS);
            assertThat(first.id()).isEqualTo(second.id());
            assertThat(first.itemCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.task_reconciliation_snapshot
                WHERE tenant_id=? AND request_key=?
                """,Integer.class,t.id(),"task-r32-samekey-capture-001")).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.task_reconciliation_snapshot_seal
                WHERE tenant_id=? AND snapshot_id=?
                """,Integer.class,t.id(),first.id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.task_reconciliation_snapshot_item
                WHERE tenant_id=? AND snapshot_id=?
                """,Integer.class,t.id(),first.id())).isEqualTo(1);
        }
    }

    @Test void t48PostgresqlSnapshotOverCapacityRejectsWithoutPartialManifest()
            throws Exception {
        try(var conn=dataSource.getConnection()) {
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        jdbc.update("""
            INSERT INTO crm.task_item(tenant_id,id,status,created_at,row_version)
            SELECT ?,md5('task-r32-cap-' || generate_series::text)::uuid,
                   'OPEN',CURRENT_TIMESTAMP,0
            FROM generate_series(1,10001)
            """,t.id());
        jdbc.update("""
            INSERT INTO crm.task_crm_followup
              (tenant_id,task_id,interaction_id,created_by,linked_at)
            SELECT tenant_id,id,?,?,CURRENT_TIMESTAMP FROM crm.task_item
            WHERE tenant_id=? AND created_by IS NULL
            """,source,t.manager().userId(),t.id());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_crm_followup
            WHERE tenant_id=? AND interaction_id=?
            """,Integer.class,t.id(),source)).isEqualTo(10001);
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        assertThatThrownBy(()->snapshots.capture(t.manager(),t.id(),source,
            "task-r32-cap-overflow-001"))
            .hasMessage("TASK_SNAPSHOT_CAPACITY_EXCEEDED");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_reconciliation_snapshot WHERE tenant_id=?
            """,Integer.class,t.id())).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_reconciliation_snapshot_seal WHERE tenant_id=?
            """,Integer.class,t.id())).isZero();
    }

    @Test void t49CommittedSnapshotReplaysAfterStatusChangeAndLostResponse() {
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        followups.create(t.manager(),t.id(),source,"Review order",
            t.staff().userId(),DUE,"task-r32-postcommit-source-001");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var original=tx.execute(status->snapshots.capture(t.manager(),t.id(),source,
            "task-r32-postcommit-key-001"));
        // A caller losing the response after commit cannot distinguish success
        // from a failure. The same request must return the committed receipt.
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='OPEN',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var replay=snapshots.capture(t.manager(),t.id(),source,
            "task-r32-postcommit-key-001");
        assertThat(replay.id()).isEqualTo(original.id());
        assertThat(replay.itemCount()).isEqualTo(original.itemCount());
        assertThatThrownBy(()->snapshots.capture(t.manager(),t.id(),source,
            "task-r32-new-after-reopen-001"))
            .hasMessage("TASK_SNAPSHOT_SOURCE_NOT_CANCELLED");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_reconciliation_snapshot
            WHERE tenant_id=? AND request_key=?
            """,Integer.class,t.id(),"task-r32-postcommit-key-001")).isEqualTo(1);
        assertThat(snapshots.verifyIntegrity(t.manager(),t.id(),original.id())).isTrue();
    }

    @Test void t50SnapshotIntegrityVerifiesPersistedRowsAndDeniesForeignActor() {
        var t=tenant();
        var other=tenant();
        UUID source=crmInteraction(t,"OPEN");
        followups.create(t.manager(),t.id(),source,"Review order",
            t.staff().userId(),DUE,"task-r32-digest-source-001");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var snap=snapshots.capture(t.manager(),t.id(),source,
            "task-r32-digest-capture-001");
        assertThat(snapshots.verifyIntegrity(t.manager(),t.id(),snap.id())).isTrue();
        assertThatThrownBy(()->snapshots.verifyIntegrity(t.staff(),t.id(),snap.id()))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->snapshots.verifyIntegrity(other.manager(),other.id(),snap.id()))
            .isInstanceOf(SecurityException.class);
    }

    @Test void t51PostgresqlExactlyTenThousandTasksCaptureAndPageWithoutLoss()
            throws Exception {
        try(var conn=dataSource.getConnection()) {
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        jdbc.update("""
            INSERT INTO crm.task_item(tenant_id,id,status,created_at,row_version)
            SELECT ?,md5('task-r32-exact-' || generate_series::text)::uuid,
                   'OPEN',CURRENT_TIMESTAMP,0
            FROM generate_series(1,10000)
            """,t.id());
        jdbc.update("""
            INSERT INTO crm.task_crm_followup
              (tenant_id,task_id,interaction_id,created_by,linked_at)
            SELECT tenant_id,id,?,?,CURRENT_TIMESTAMP FROM crm.task_item
            WHERE tenant_id=? AND created_by IS NULL
            """,source,t.manager().userId(),t.id());
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var snap=snapshots.capture(t.manager(),t.id(),source,
            "task-r32-exact-boundary-001");
        assertThat(snap.itemCount()).isEqualTo(10000);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_reconciliation_snapshot_item
            WHERE tenant_id=? AND snapshot_id=?
            """,Integer.class,t.id(),snap.id())).isEqualTo(10000);
        assertThat(snapshots.verifyIntegrity(t.manager(),t.id(),snap.id())).isTrue();
        int count=0;UUID cursor=null;
        do {
            var page=snapshots.page(t.manager(),t.id(),snap.id(),cursor,100);
            assertThat(page.items()).isNotEmpty();
            count+=page.items().size();
            cursor=page.nextAfterTaskId();
        }while(cursor!=null);
        assertThat(count).isEqualTo(10000);
    }

    @Test void t52PostgresqlCrmStatusRaceSerializesWithSnapshotCapture()
            throws Exception {
        try(var conn=dataSource.getConnection()) {
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        var t=tenant();
        UUID source=crmInteraction(t,"OPEN");
        followups.create(t.manager(),t.id(),source,"Review order",
            t.staff().userId(),DUE,"task-r32-source-race-001");
        jdbc.update("""
            UPDATE crm.customer_interaction SET status='CANCELLED',
            row_version=row_version+1 WHERE tenant_id=? AND id=?
            """,t.id(),source);
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var captureStarted=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var statusChange=pool.submit(()->tx.execute(status->{
                jdbc.queryForObject("""
                    SELECT id FROM crm.customer_interaction
                    WHERE tenant_id=? AND id=? FOR UPDATE
                    """,UUID.class,t.id(),source);
                jdbc.update("""
                    UPDATE crm.customer_interaction SET status='OPEN',
                    row_version=row_version+1 WHERE tenant_id=? AND id=?
                    """,t.id(),source);
                locked.countDown();
                try {
                    if(!release.await(20,TimeUnit.SECONDS))
                        throw new IllegalStateException("TASK_SNAPSHOT_RACE_TIMEOUT");
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertThat(locked.await(20,TimeUnit.SECONDS)).isTrue();
            var capture=pool.submit(()->{
                captureStarted.countDown();
                try {
                    snapshots.capture(t.manager(),t.id(),source,"task-r32-race-denied-001");
                    return "UNEXPECTED_SUCCESS";
                }catch(IllegalStateException e){return e.getMessage();}
            });
            assertThat(captureStarted.await(10,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(statusChange.get(30,TimeUnit.SECONDS)).isTrue();
            assertThat(capture.get(30,TimeUnit.SECONDS))
                .isEqualTo("TASK_SNAPSHOT_SOURCE_NOT_CANCELLED");
        } finally {release.countDown();}
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.task_reconciliation_snapshot
            WHERE tenant_id=?
            """,Integer.class,t.id())).isZero();
    }

}
