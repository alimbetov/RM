package kz.rm.tasks;

import kz.rm.iam.*;
import kz.rm.crm.CrmInteractionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(TaskRadarSqlFailureQualificationTest.ProbeConfig.class)
class TaskRadarSqlFailureQualificationTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class ProbeConfig {
        @Bean static BeanPostProcessor radarProbeDataSource(){
            return new BeanPostProcessor(){
                @Override public Object postProcessAfterInitialization(Object bean,String name){
                    if("dataSource".equals(name)&&bean instanceof DataSource ds)
                        return new RadarSqlProbe.Wrapped(ds);
                    return bean;
                }
            };
        }
    }
    @Autowired TaskService tasks;
    @Autowired TaskOverdueQueryService radar;
    @Autowired TaskCrmFollowupService followups;
    @Autowired CrmInteractionService crm;
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
        roles.grant(a,tenant,managerRole.id(),DomainPermission.CRM_WRITE);
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


    private boolean pg()throws Exception{
        try(var c=dataSource.getConnection()){
            boolean pg="PostgreSQL".equals(c.getMetaData().getDatabaseProductName());
            String expected=System.getProperty("rm.radar.c2b.expectedDb");
            if(expected!=null){
                assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo(expected.equals("postgresql")?"PostgreSQL":"H2");
                if(pg)assertThat(c.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            }
            return pg;
        }
    }
    @BeforeEach void requireProbeAndDb()throws Exception{
        assertThat(dataSource).isInstanceOf(RadarSqlProbe.Wrapped.class);pg();
    }
    private Instant past(){return Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private record Ledger(List<Map<String,Object>> rows,List<Map<String,Object>> events){}
    private Ledger ledger(Tenant t){
        return new Ledger(jdbc.queryForList("SELECT * FROM crm.task_item WHERE tenant_id=? ORDER BY id",t.id()),
            jdbc.queryForList("SELECT * FROM crm.task_event WHERE tenant_id=? ORDER BY task_id,new_version",t.id()));
    }
    private void readOnly(RadarSqlProbe.Trace trace,long taskSelects){
        assertThat(trace.taskSelects()).isEqualTo(taskSelects);
        assertThat(trace.businessStatements()).isEqualTo(taskSelects);
        assertThat(trace.entries()).isNotEmpty().allSatisfy(e->{
            assertThat(e.select()).as("no non-SELECT execution").isTrue();
            assertThat(e.batch()).as("no batch").isFalse();
        });
    }
    private void sqlState(Throwable failure,String state){
        boolean found=false;
        for(Throwable e=failure;e!=null;e=e.getCause()){
            if(e instanceof SQLException sql&&state.equals(sql.getSQLState())){found=true;break;}
        }
        assertThat(found).as("expected SQLState "+state).isTrue();
    }
    private void injected(Tenant t){
        try(var trace=RadarSqlProbe.capture(true)){
            Throwable failure=catchThrowable(()->radar.listMine(t.staff(),t.id(),10,null));
            assertThat(failure).isInstanceOf(DataAccessException.class);
            sqlState(failure,"08006");
            readOnly(trace,1);
            assertThat(trace.entries().stream().filter(RadarSqlProbe.Entry::injected).count()).isEqualTo(1);
        }
    }
    @Test void c212InjectedBusinessSelectFailureNeverReturnsEmptyPage()throws Exception{
        var t=tenant();create(t,past());var before=ledger(t);
        injected(t);assertThat(ledger(t)).isEqualTo(before);
    }
    @Test void c213RetryAfterFaultReadsNewCommittedVersion()throws Exception{
        var t=tenant();var task=create(t,past());injected(t);
        Instant newDue=past().minusSeconds(3600);
        tasks.reschedule(t.manager(),t.id(),task.id(),newDue,0,"radar-retry-"+UUID.randomUUID());
        try(var trace=RadarSqlProbe.capture(false)){
            assertThat(radar.listMine(t.staff(),t.id(),10,null).items()).singleElement().satisfies(x->{
                assertThat(x.taskId()).isEqualTo(task.id());assertThat(x.sourceVersion()).isEqualTo(1L);
                assertThat(x.dueAt()).isEqualTo(newDue);
            });
            readOnly(trace,1);
        }
    }
    @Test void c214ExactlyOneBusinessSelectWithIamAdmission()throws Exception{
        var t=tenant();create(t,past());
        try(var trace=RadarSqlProbe.capture(false)){
            assertThat(radar.listMine(t.staff(),t.id(),10,null).items()).hasSize(1);
            readOnly(trace,1);
            assertThat(trace.entries().stream().filter(e->!e.business()).count())
                .as("actor fence, tenant fence, fresh membership/RBAC query").isEqualTo(3);
        }
    }
    @Test void c215ZeroBusinessDmlAndLedgerUnchanged()throws Exception{
        var t=tenant();create(t,past());var before=ledger(t);
        try(var trace=RadarSqlProbe.capture(false)){
            radar.listMine(t.staff(),t.id(),100,null);readOnly(trace,1);
        }
        assertThat(ledger(t)).isEqualTo(before);
    }
    @Test void c216PageSizeDoesNotCreateNPlusOne()throws Exception{
        var t=tenant();Instant due=past();for(int i=0;i<21;i++)create(t,due.plusSeconds(i));
        int shortCount;
        try(var trace=RadarSqlProbe.capture(false)){
            assertThat(radar.listMine(t.staff(),t.id(),1,null).items()).hasSize(1);readOnly(trace,1);shortCount=trace.entries().size();
        }
        try(var trace=RadarSqlProbe.capture(false)){
            assertThat(radar.listMine(t.staff(),t.id(),100,null).items()).hasSize(21);readOnly(trace,1);
            assertThat(trace.entries()).hasSize(shortCount);
        }
        var p=radar.listMine(t.staff(),t.id(),10,null);
        try(var trace=RadarSqlProbe.capture(false)){
            assertThat(radar.listMine(t.staff(),t.id(),10,p.nextCursor()).items()).hasSize(10);readOnly(trace,1);
        }
    }
    @Test void c217DeniedReadAndInvalidCursorNeverQueryBusinessRows()throws Exception{
        var t=tenant();create(t,past());create(t,past().minusSeconds(1));
        var cursor=radar.listMine(t.staff(),t.id(),1,null).nextCursor();
        try(var trace=RadarSqlProbe.capture(false)){
            assertThatThrownBy(()->radar.listMine(t.manager(),t.id(),1,cursor)).isInstanceOf(IllegalArgumentException.class);
            readOnly(trace,0);
        }
        roles.revoke(t.owner(),t.id(),role(t,t.staff(),DomainPermission.TASK_READ),DomainPermission.TASK_READ);
        try(var trace=RadarSqlProbe.capture(false)){
            assertThatThrownBy(()->radar.listMine(t.staff(),t.id(),10,null)).isInstanceOf(SecurityException.class);
            readOnly(trace,0);
        }
    }
    @Test void c218SignalDtoUsesStrictAllowlistNoTitleOrActorFields()throws Exception{
        var t=tenant();create(t,past());
        var signal=radar.listMine(t.staff(),t.id(),10,null).items().getFirst();
        // Spring Boot 4 uses Jackson 3 (tools.jackson), including java.time support.
        var mapper=JsonMapper.builder().build();
        String json=mapper.writeValueAsString(signal);
        Map<?,?> payload=mapper.readValue(json,Map.class);
        Set<String> keys=new HashSet<>();
        payload.keySet().forEach(k->keys.add((String)k));
        assertThat(keys).containsExactlyInAnyOrder("taskId","type","state","dueAt","sourceVersion","overdueSeconds","severity","reasonCode");
        assertThat(json).doesNotContain(t.staff().userId().toString(),t.manager().userId().toString(),"Review order");
    }
    private UUID interaction(Tenant t){
        UUID customer=UUID.randomUUID(),interaction=UUID.randomUUID();Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO crm.customer(tenant_id,id,customer_type,organization_label,status,created_at,row_version)
            VALUES (?,?,'COMPANY','Synthetic CRM customer','ACTIVE',?,0)
            """,t.id(),customer,now);
        jdbc.update("""
            INSERT INTO crm.customer_interaction(tenant_id,id,customer_id,kind,status,responsible_user_id,
                occurred_at,request_key,request_sha256,created_by,created_at,updated_at,row_version)
            VALUES (?,?,?,'CALL','OPEN',?,?,?,?,?,?,?,0)
            """,t.id(),interaction,customer,t.manager().userId(),now,"radarcrm_"+UUID.randomUUID().toString().replace("-",""),
            new byte[32],t.manager().userId(),now,now);
        return interaction;
    }
    @Test void c219CancelledCrmSourceDoesNotHideActiveLinkedTask()throws Exception{
        var t=tenant();UUID source=interaction(t);
        var task=followups.create(t.manager(),t.id(),source,"Review order",t.staff().userId(),past(),"radar-link-"+UUID.randomUUID());
        crm.cancel(t.manager(),t.id(),source,0);
        try(var trace=RadarSqlProbe.capture(false)){
            assertThat(ids(radar.listMine(t.staff(),t.id(),10,null))).containsExactly(task.id());
            readOnly(trace,1);
        }
        tasks.cancelForSource(t.manager(),t.id(),source,task.id(),0,"radar-source-"+UUID.randomUUID());
        assertThat(radar.listMine(t.staff(),t.id(),10,null).items()).isEmpty();
    }
    @Test void c220ProbePositiveControlCapturesJdbcDmlAndJpaSelect()throws Exception{
        var t=tenant();var task=create(t,past());var before=ledger(t);
        try(var trace=RadarSqlProbe.capture(false)){
            new TransactionTemplate(transactions).executeWithoutResult(tx->{
                assertThat(users.findById(t.staff().userId())).isPresent();
                assertThat(jdbc.update("UPDATE crm.task_item SET row_version=row_version+1 WHERE tenant_id=? AND id=?",t.id(),task.id())).isEqualTo(1);
                tx.setRollbackOnly();
            });
            assertThat(trace.entries()).anyMatch(e->e.select()&&!e.business());
            assertThat(trace.entries()).anyMatch(e->!e.select()&&e.business()&&e.task());
        }
        assertThat(ledger(t)).isEqualTo(before);
    }
    @Test void c221RealPgLockTimeoutAndRecovery()throws Exception{
        Assumptions.assumeTrue(pg(),"real PostgreSQL lock timeout only");
        var t=tenant();var task=create(t,past());var before=ledger(t);
        var tx=new TransactionTemplate(transactions);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var lock=pool.submit(()->tx.executeWithoutResult(status->{
                jdbc.execute("LOCK TABLE crm.task_item IN ACCESS EXCLUSIVE MODE");
                locked.countDown();
                try{assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }));
            try{
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                Throwable failure=catchThrowable(()->tx.executeWithoutResult(status->{
                    jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                    try(var trace=RadarSqlProbe.capture(false)){
                        try{radar.listMine(t.staff(),t.id(),10,null);}
                        finally{readOnly(trace,1);}
                    }
                }));
                assertThat(failure).isInstanceOf(DataAccessException.class);sqlState(failure,"55P03");
            }finally{release.countDown();}
            lock.get(10,TimeUnit.SECONDS);
        }
        assertThat(ledger(t)).isEqualTo(before);
        assertThat(ids(radar.listMine(t.staff(),t.id(),10,null))).containsExactly(task.id());
    }
}
