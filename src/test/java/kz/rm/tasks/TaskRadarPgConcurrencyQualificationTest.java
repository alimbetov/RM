package kz.rm.tasks;

import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class TaskRadarPgConcurrencyQualificationTest {
    @Autowired TaskService tasks;
    @Autowired TaskOverdueQueryService radar;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired GlobalAccountStatusService accounts;
    @Autowired TenantAccessStatusService access;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    private SignedJwtTestAuthority authority;

    @BeforeEach void startAuthority() throws Exception { authority=SignedJwtTestAuthority.start(); }
    @AfterEach void closeAuthority() { if(authority!=null)authority.close(); }

    private record Tenant(UUID id, VerifiedActor manager,VerifiedActor staff,UUID orgId,VerifiedActor owner) {}
    private VerifiedActor verified(UUID user) throws Exception {
        String subject=jdbc.queryForObject("SELECT external_subject FROM public.iam_user_account WHERE id=?",String.class,user);
        return new OidcActorResolver(authority.decoder(),users).resolve(authority.access(subject));
    }
    private Tenant tenant() throws Exception {
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


    private TaskService.Task create(Tenant t,Instant due) {
        return tasks.create(t.manager(),t.id(),"Review order",t.staff().userId(),due,
            "radar-"+UUID.randomUUID());
    }
    private List<UUID> ids(TaskOverdueQueryService.OverdueSignalPage p) {
        return p.items().stream().map(TaskOverdueQueryService.OverdueSignal::taskId).toList();
    }


    private UUID role(Tenant t,VerifiedActor actor,DomainPermission permission){
        return jdbc.queryForObject("""
            SELECT a.role_id FROM public.iam_tenant_user_role a
            JOIN public.iam_tenant_role_permission p ON p.tenant_id=a.tenant_id AND p.role_id=a.role_id
            WHERE a.tenant_id=? AND a.user_id=? AND p.permission_code=?
            """,UUID.class,t.id(),actor.userId(),permission.name());
    }

    private VerifiedActor additionalStaff(Tenant t) throws Exception {
        UUID id=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "radar-c-staff-"+UUID.randomUUID(),"Radar staff")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),
            t.orgId(),id,OrganizationMembershipEntity.Role.MEMBER));
        roles.assign(t.owner(),t.id(),id,role(t,t.staff(),DomainPermission.TASK_READ));
        return verified(id);
    }


    @BeforeEach void requirePostgres() throws Exception {
        try(var c=dataSource.getConnection()){
            boolean pg="PostgreSQL".equals(c.getMetaData().getDatabaseProductName());
            if("postgresql".equals(System.getProperty("rm.radar.c2.expectedDb"))){
                assertThat(pg).as("dedicated PG gate must not fall back").isTrue();
                assertThat(c.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            }else Assumptions.assumeTrue(pg,"C2 requires real PostgreSQL");
        }
    }

    private VerifiedActor admin() throws Exception {
        UUID id=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "radar-c2-admin-"+UUID.randomUUID(),"Radar admin")).getId();
        jdbc.update("INSERT INTO public.iam_global_role_assignment(id,user_id,role,granted_at) VALUES (?,?,?,?)",
            UUID.randomUUID(),id,"PLATFORM_ADMIN",Timestamp.from(Instant.now()));
        return verified(id);
    }
    private static Instant past(){
        return Instant.now().minusSeconds(7200).truncatedTo(ChronoUnit.MICROS);
    }
    private int pid(){return jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);}
    private void bounded(CountDownLatch latch) {
        try{assertThat(latch.await(35,TimeUnit.SECONDS)).as("bounded release").isTrue();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private void observeBlock(int blocker,int waiter,Future<?> work) throws Exception {
        assertThat(waiter).isNotEqualTo(blocker);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        boolean observed=false;
        do{
            observed=Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT ? = ANY(pg_blocking_pids(?))",Boolean.class,blocker,waiter));
            if(observed||work.isDone())break;
            Thread.sleep(25);
        }while(System.nanoTime()<deadline);
        assertThat(observed).as("actual PostgreSQL blocking edge").isTrue();
        System.out.printf("RADAR C2 actual blocker=%d waiter=%d%n",blocker,waiter);
    }
    private void blockedSchedule(Runnable first,Runnable second,String expectedDenial) throws Exception {
        var tx=new TransactionTemplate(transactions);
        var applied=new CountDownLatch(1);var release=new CountDownLatch(1);
        var firstPid=new CompletableFuture<Integer>();var secondPid=new CompletableFuture<Integer>();
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->tx.execute(status->{
                firstPid.complete(pid()); first.run();applied.countDown();bounded(release);return "COMMITTED";
            }));
            try{
                assertThat(applied.await(15,TimeUnit.SECONDS)).as("first operation applied").isTrue();
                var b=pool.submit(()->{
                    try{
                        tx.executeWithoutResult(status->{secondPid.complete(pid());second.run();});
                        return "COMMITTED";
                    }catch(SecurityException e){
                        if(expectedDenial==null||!expectedDenial.equals(e.getMessage()))throw e;
                        return "DENIED";
                    }
                });
                observeBlock(firstPid.get(5,TimeUnit.SECONDS),secondPid.get(5,TimeUnit.SECONDS),b);
                release.countDown();
                assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo("COMMITTED");
                assertThat(b.get(20,TimeUnit.SECONDS)).isEqualTo(expectedDenial==null?"COMMITTED":"DENIED");
            }finally{release.countDown();}
        }
    }
    private record Ledger(List<java.util.Map<String,Object>> tasks,List<java.util.Map<String,Object>> events){}
    private Ledger ledger(Tenant t){
        return new Ledger(jdbc.queryForList("SELECT * FROM crm.task_item WHERE tenant_id=? ORDER BY id",t.id()),
            jdbc.queryForList("SELECT * FROM crm.task_event WHERE tenant_id=? ORDER BY task_id,new_version",t.id()));
    }
    private void readOne(Tenant t,UUID id){
        assertThat(ids(radar.listMine(t.staff(),t.id(),10,null))).containsExactly(id);
    }
    private enum Fence { ROLE, ACCOUNT, MEMBERSHIP, WORKSPACE }
    private void fence(Fence type,boolean readFirst) throws Exception {
        var t=tenant();var task=create(t,past());var before=ledger(t);
        UUID staff=t.staff().userId();
        UUID roleId=role(t,t.staff(),DomainPermission.TASK_READ);
        VerifiedActor administrator=type==Fence.ACCOUNT?admin():null;
        Runnable write=switch(type){
            case ROLE -> ()->roles.revoke(t.owner(),t.id(),roleId,DomainPermission.TASK_READ);
            case ACCOUNT -> ()->accounts.suspendAccount(administrator,staff);
            case MEMBERSHIP -> ()->access.suspendMember(t.owner(),t.id(),staff);
            case WORKSPACE -> ()->access.suspendTenant(t.owner(),t.id());
        };
        String denial=switch(type){
            case ROLE -> "Tenant RBAC permission denied";
            case ACCOUNT -> "Actor inactive or unknown";
            case MEMBERSHIP,WORKSPACE -> "Active organization membership required";
        };
        Runnable read=()->readOne(t,task.id());
        if(readFirst)blockedSchedule(read,write,null);
        else blockedSchedule(write,read,denial);
        assertThatThrownBy(()->radar.listMine(t.staff(),t.id(),10,null))
            .isInstanceOf(SecurityException.class).hasMessage(denial);
        assertThat(ledger(t)).isEqualTo(before);
        switch(type){
            case ROLE -> assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_tenant_role_permission
                WHERE tenant_id=? AND role_id=? AND permission_code='TASK_READ'
                """,Integer.class,t.id(),roleId)).isZero();
            case ACCOUNT -> {
                assertThat(jdbc.queryForObject("SELECT status FROM public.iam_user_account WHERE id=?",String.class,staff)).isEqualTo("SUSPENDED");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM public.iam_account_status_event WHERE target_user_id=?",Integer.class,staff)).isEqualTo(1);
            }
            case MEMBERSHIP -> {
                assertThat(jdbc.queryForObject("SELECT status FROM public.iam_org_membership WHERE organization_id=? AND user_id=?",String.class,t.orgId(),staff)).isEqualTo("SUSPENDED");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM public.iam_tenant_rbac_event WHERE tenant_id=? AND action_code='MEMBER_SUSPENDED' AND target_user_id=?",Integer.class,t.id(),staff)).isEqualTo(1);
            }
            case WORKSPACE -> {
                assertThat(jdbc.queryForObject("SELECT status FROM public.iam_workspace WHERE id=?",String.class,t.id())).isEqualTo("SUSPENDED");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM public.iam_tenant_rbac_event WHERE tenant_id=? AND action_code='TENANT_SUSPENDED'",Integer.class,t.id())).isEqualTo(1);
            }
        }
    }
    @Test void c201RoleRevocationBothCommitOrders() throws Exception {fence(Fence.ROLE,true);fence(Fence.ROLE,false);}
    @Test void c202AccountSuspensionBothCommitOrders() throws Exception {fence(Fence.ACCOUNT,true);fence(Fence.ACCOUNT,false);}
    @Test void c203MembershipSuspensionBothCommitOrders() throws Exception {fence(Fence.MEMBERSHIP,true);fence(Fence.MEMBERSHIP,false);}
    @Test void c204WorkspaceSuspensionBothCommitOrders() throws Exception {fence(Fence.WORKSPACE,true);fence(Fence.WORKSPACE,false);}

    // For TASK writers the assertion is the opposite: the second transaction
    // must FINISH while the first remains open. Use distinct backend PIDs and
    // bounded futures; no assertion based solely on elapsed sleep.
    private void nonblockingSchedule(Runnable first,Runnable second,boolean rollbackFirst) throws Exception {
        var tx=new TransactionTemplate(transactions);
        var applied=new CountDownLatch(1);var release=new CountDownLatch(1);
        var firstPid=new CompletableFuture<Integer>();var secondPid=new CompletableFuture<Integer>();
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->tx.execute(status->{
                firstPid.complete(pid());first.run();applied.countDown();bounded(release);
                if(rollbackFirst)status.setRollbackOnly();return rollbackFirst?"ROLLED_BACK":"COMMITTED";
            }));
            try{
                assertThat(applied.await(15,TimeUnit.SECONDS)).as("first operation applied").isTrue();
                var b=pool.submit(()->tx.execute(status->{secondPid.complete(pid());second.run();return "COMMITTED";}));
                assertThat(secondPid.get(5,TimeUnit.SECONDS)).isNotEqualTo(firstPid.get(5,TimeUnit.SECONDS));
                assertThat(b.get(15,TimeUnit.SECONDS)).as("second commits before first is released").isEqualTo("COMMITTED");
                assertThat(a.isDone()).isFalse();
                release.countDown();
                assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(rollbackFirst?"ROLLED_BACK":"COMMITTED");
                System.out.printf("RADAR C2 nonblocking first=%d second=%d firstRollback=%s%n",
                    firstPid.get(),secondPid.get(),rollbackFirst);
            }finally{release.countDown();}
        }
    }
    private enum Mutation { COMPLETE,CANCEL,RESCHEDULE,REASSIGN,REQUEUE }
    private void lifecycle(Mutation mutation,boolean readFirst,boolean rollback) throws Exception {
        var t=tenant();var task=create(t,past());var replacement=mutation==Mutation.REASSIGN?additionalStaff(t):null;
        if(mutation==Mutation.COMPLETE)tasks.start(t.staff(),t.id(),task.id(),0);
        long v=mutation==Mutation.COMPLETE?1:0;
        TaskState oldState=mutation==Mutation.COMPLETE?TaskState.IN_PROGRESS:TaskState.OPEN;
        var before=ledger(t);
        Instant future=Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.MICROS);
        Runnable writer=switch(mutation){
            case COMPLETE -> ()->tasks.complete(t.staff(),t.id(),task.id(),v);
            case CANCEL -> ()->tasks.cancel(t.manager(),t.id(),task.id(),v,"c2-cancel-"+UUID.randomUUID());
            case RESCHEDULE -> ()->tasks.reschedule(t.manager(),t.id(),task.id(),future,v,"c2-due-"+UUID.randomUUID());
            case REASSIGN -> ()->tasks.assign(t.manager(),t.id(),task.id(),replacement.userId(),v);
            case REQUEUE -> ()->tasks.requeue(t.manager(),t.id(),task.id(),v,"c2-queue-"+UUID.randomUUID());
        };
        Runnable reader=()->{
            var page=radar.listMine(t.staff(),t.id(),10,null);
            assertThat(page.items()).singleElement().satisfies(x->{
                assertThat(x.taskId()).isEqualTo(task.id());assertThat(x.state()).isEqualTo(oldState);
                assertThat(x.sourceVersion()).isEqualTo(v);assertThat(x.dueAt()).isEqualTo(task.dueAt());
            });
        };
        if(readFirst)nonblockingSchedule(reader,writer,false);
        else nonblockingSchedule(writer,reader,rollback);
        if(rollback){
            assertThat(ledger(t)).isEqualTo(before);reader.run();return;
        }
        assertThat(radar.listMine(t.staff(),t.id(),10,null).items()).isEmpty();
        var current=tasks.getMine(t.manager(),t.id(),task.id());
        assertThat(current.version()).isEqualTo(v+1);
        assertThat(current.state()).isEqualTo(switch(mutation){
            case COMPLETE -> TaskState.COMPLETED;case CANCEL -> TaskState.CANCELLED;
            case REQUEUE -> TaskState.QUEUED;default -> TaskState.OPEN;
        });
        if(mutation==Mutation.RESCHEDULE)assertThat(current.dueAt()).isEqualTo(future);
        if(mutation==Mutation.REASSIGN){
            assertThat(current.assigneeUserId()).isEqualTo(replacement.userId());
            assertThat(ids(radar.listMine(replacement,t.id(),10,null))).containsExactly(task.id());
            assertThat(ids(radar.listMine(t.manager(),t.id(),10,null))).containsExactly(task.id());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.task_event WHERE tenant_id=? AND task_id=?",Integer.class,t.id(),task.id()))
            .isEqualTo((int)v+2);
    }
    @Test void c205CompletionBothOrdersNoTaskLock() throws Exception {lifecycle(Mutation.COMPLETE,true,false);lifecycle(Mutation.COMPLETE,false,false);}
    @Test void c206CancellationBothOrdersNoTaskLock() throws Exception {lifecycle(Mutation.CANCEL,true,false);lifecycle(Mutation.CANCEL,false,false);}
    @Test void c207RescheduleBothOrdersNoTaskLock() throws Exception {lifecycle(Mutation.RESCHEDULE,true,false);lifecycle(Mutation.RESCHEDULE,false,false);}
    @Test void c208ReassignmentBothOrdersNoTaskLock() throws Exception {lifecycle(Mutation.REASSIGN,true,false);lifecycle(Mutation.REASSIGN,false,false);}
    @Test void c209RequeueBothOrdersNoTaskLock() throws Exception {lifecycle(Mutation.REQUEUE,true,false);lifecycle(Mutation.REQUEUE,false,false);}
    @Test void c210RollbackStatusAndDeadlineWhileReadCommits() throws Exception {lifecycle(Mutation.CANCEL,false,true);lifecycle(Mutation.RESCHEDULE,false,true);}
    @Test void c211LiveKeysetRefreshAfterCommittedMutation() throws Exception {
        var t=tenant();Instant due=past();var a=create(t,due);var b=create(t,due.plusSeconds(1));var c=create(t,due.plusSeconds(2));
        var first=radar.listMine(t.staff(),t.id(),1,null);
        assertThat(ids(first)).containsExactly(a.id());assertThat(first.nextCursor()).isNotNull();
        tasks.reschedule(t.manager(),t.id(),c.id(),due.minusSeconds(1),0,"c2-live-"+UUID.randomUUID());
        var next=radar.listMine(t.staff(),t.id(),1,first.nextCursor());
        assertThat(ids(next)).containsExactly(b.id());assertThat(next.nextCursor()).isNull();
        assertThat(ids(radar.listMine(t.staff(),t.id(),100,null))).containsExactly(c.id(),a.id(),b.id());
    }
}
