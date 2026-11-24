package kz.rm.tasks;

import kz.rm.iam.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.tasks.TaskCalendarQueryService.*;
import static kz.rm.tasks.TaskTimingSnapshot.Timeliness.*;

@SpringBootTest
@Import(TaskTimingCompletionQualificationTest.ClockConfig.class)
class TaskCalendarPgConcurrencyQualificationTest {
    private static final Instant BASE=Instant.parse("2026-11-01T00:00:00Z");
    @Autowired TaskTimingCompletionQualificationTest.MutableClock clock;
    @Autowired TaskService tasks;
    @Autowired TaskCalendarQueryService calendar;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
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




    @BeforeEach void pgOnly()throws Exception{
        clock.at(BASE);
        try(var connection=dataSource.getConnection()){
            boolean pg="PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
            if(Boolean.getBoolean("rm.radar.f2.races")){
                assertThat(pg).isTrue();
                assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            }else Assumptions.assumeTrue(pg,"PG-only calendar concurrency");
        }
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

    private Filter filter(DateBasis basis,boolean archived){
        return new Filter(null,archived?EnumSet.allOf(TaskState.class):null,null,basis,
            LocalDate.of(2026,11,1),LocalDate.of(2026,12,1),"UTC",false);
    }
    private Page page(Tenant t,DateBasis basis,boolean archived){
        return calendar.listMine(t.staff(),t.id(),filter(basis,archived),100,null);
    }
    private TaskTimingSnapshot only(Tenant t,DateBasis basis,boolean archived){
        var items=page(t,basis,archived).items();
        assertThat(items).hasSize(1);return items.getFirst().timing();
    }
    private record Ledger(List<Map<String,Object>> tasks,List<Map<String,Object>> events){}
    private Ledger ledger(Tenant t){return new Ledger(
        jdbc.queryForList("SELECT * FROM crm.task_item WHERE tenant_id=? ORDER BY id",t.id()),
        jdbc.queryForList("SELECT * FROM crm.task_event WHERE tenant_id=? ORDER BY task_id,new_version",t.id()));}
    private UUID role(Tenant t){return jdbc.queryForObject("""
        SELECT a.role_id FROM public.iam_tenant_user_role a JOIN public.iam_tenant_role_permission p
        ON p.tenant_id=a.tenant_id AND p.role_id=a.role_id
        WHERE a.tenant_id=? AND a.user_id=? AND p.permission_code='TASK_READ'
        """,UUID.class,t.id(),t.staff().userId());}
    private void completion(boolean readerFirst,boolean rollback)throws Exception{
        var t=tenant();var x=create(t,BASE.plusSeconds(100));
        tasks.start(t.staff(),t.id(),x.id(),0);clock.at(BASE.plusSeconds(40));
        var before=only(t,DateBasis.DUE,false);var old=ledger(t);
        Runnable reader=()->{
            assertThat(only(t,DateBasis.DUE,false)).isEqualTo(before);
            assertThat(page(t,DateBasis.COMPLETED,false).items()).isEmpty();
        };
        Runnable writer=()->tasks.complete(t.staff(),t.id(),x.id(),1);
        if(readerFirst)nonblockingSchedule(reader,writer,false);else nonblockingSchedule(writer,reader,rollback);
        if(rollback){reader.run();assertThat(ledger(t)).isEqualTo(old);return;}
        var after=only(t,DateBasis.COMPLETED,false);
        assertThat(after.taskId()).isEqualTo(x.id());assertThat(after.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(after.sourceVersion()).isEqualTo(2);assertThat(after.completedAt()).isEqualTo(BASE.plusSeconds(40));
        assertThat(after.timeliness()).isEqualTo(EARLY_50);
        assertThat(only(t,DateBasis.DUE,false)).isEqualTo(after);
    }
    @Test void f201CompletionBothOrdersAndRollback()throws Exception{
        completion(true,false);clock.at(BASE);completion(false,false);clock.at(BASE);completion(false,true);
    }
    private void archive(boolean readerFirst)throws Exception{
        var t=tenant();var x=create(t,BASE.plusSeconds(100));tasks.start(t.staff(),t.id(),x.id(),0);
        clock.at(BASE.plusSeconds(40));tasks.complete(t.staff(),t.id(),x.id(),1);clock.at(BASE.plusSeconds(80));
        var before=only(t,DateBasis.COMPLETED,false);
        Runnable reader=()->assertThat(only(t,DateBasis.COMPLETED,false)).isEqualTo(before);
        Runnable writer=()->tasks.archive(t.manager(),t.id(),x.id(),2);
        if(readerFirst)nonblockingSchedule(reader,writer,false);else nonblockingSchedule(writer,reader,false);
        assertThat(page(t,DateBasis.COMPLETED,false).items()).isEmpty();
        var after=only(t,DateBasis.COMPLETED,true);
        assertThat(after.state()).isEqualTo(TaskState.ARCHIVED);assertThat(after.sourceVersion()).isEqualTo(3);
        assertThat(after.completedAt()).isEqualTo(before.completedAt());assertThat(after.timeliness()).isEqualTo(before.timeliness());
    }
    @Test void f202ArchiveBothOrdersRetainsCompletion()throws Exception{archive(true);clock.at(BASE);archive(false);}
    private void reschedule(boolean readerFirst,boolean rollback)throws Exception{
        var t=tenant();var due=BASE.plusSeconds(100);var x=create(t,due);
        clock.at(BASE.plusSeconds(40));var next=BASE.plusSeconds(200);var before=only(t,DateBasis.DUE,false);var old=ledger(t);
        Runnable reader=()->assertThat(only(t,DateBasis.DUE,false)).isEqualTo(before);
        Runnable writer=()->tasks.reschedule(t.manager(),t.id(),x.id(),next,0,"f2-due-"+UUID.randomUUID());
        if(readerFirst)nonblockingSchedule(reader,writer,false);else nonblockingSchedule(writer,reader,rollback);
        if(rollback){reader.run();assertThat(ledger(t)).isEqualTo(old);return;}
        var after=only(t,DateBasis.DUE,false);
        assertThat(after.sourceVersion()).isEqualTo(1);assertThat(after.effectiveDueAt()).isEqualTo(next);
        assertThat(after.originalDueAt()).isEqualTo(due);assertThat(after.deadlineChanged()).isTrue();
    }
    @Test void f203RescheduleBothOrdersAndRollback()throws Exception{
        reschedule(true,false);clock.at(BASE);reschedule(false,false);clock.at(BASE);reschedule(false,true);
    }
    private void reassign(boolean readerFirst)throws Exception{
        var t=tenant();var x=create(t,BASE.plusSeconds(100));
        var before=only(t,DateBasis.DUE,false);
        Runnable reader=()->assertThat(only(t,DateBasis.DUE,false)).isEqualTo(before);
        // Manager is an active member and the original creator.
        Runnable writer=()->tasks.assign(t.manager(),t.id(),x.id(),t.manager().userId(),0);
        if(readerFirst)nonblockingSchedule(reader,writer,false);else nonblockingSchedule(writer,reader,false);
        assertThat(page(t,DateBasis.DUE,false).items()).isEmpty();
        var visible=calendar.listMine(t.manager(),t.id(),filter(DateBasis.DUE,false),100,null);
        assertThat(visible.items()).singleElement().satisfies(i->{
            assertThat(i.timing().taskId()).isEqualTo(x.id());assertThat(i.timing().sourceVersion()).isEqualTo(1);
        });
    }
    @Test void f204ReassignmentRemovesOldAssigneeBothOrders()throws Exception{reassign(true);clock.at(BASE);reassign(false);}
    private void revoke(boolean readerFirst)throws Exception{
        var t=tenant();var x=create(t,BASE.plusSeconds(100));UUID role=role(t);var before=ledger(t);
        Runnable reader=()->assertThat(only(t,DateBasis.DUE,false).taskId()).isEqualTo(x.id());
        Runnable writer=()->roles.revoke(t.owner(),t.id(),role,DomainPermission.TASK_READ);
        if(readerFirst)blockedSchedule(reader,writer,null);
        else blockedSchedule(writer,reader,"Tenant RBAC permission denied");
        assertThatThrownBy(reader::run).isInstanceOf(SecurityException.class).hasMessage("Tenant RBAC permission denied");
        assertThat(ledger(t)).isEqualTo(before);
    }
    @Test void f205RoleRevokeBothOrdersProvesBlockingEdge()throws Exception{revoke(true);clock.at(BASE);revoke(false);}
}
