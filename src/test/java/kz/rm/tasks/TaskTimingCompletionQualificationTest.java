package kz.rm.tasks;

import kz.rm.iam.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.*;
import java.sql.Types;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.tasks.TaskTimingSnapshot.Timeliness.*;
import static kz.rm.tasks.TaskTimingSnapshot.CompletionEvidence.*;
import static kz.rm.tasks.TaskTimingSnapshot.OriginalEvidence.*;
import static kz.rm.tasks.TaskTimingSnapshot.Direction.*;

@SpringBootTest
@Import(TaskTimingCompletionQualificationTest.ClockConfig.class)
class TaskTimingCompletionQualificationTest {
    static final Instant BASE=Instant.parse("2026-11-01T00:00:00Z");
    static final class MutableClock extends Clock {
        private Instant now=BASE;
        void at(Instant t){now=t;}
        @Override public Instant instant(){return now;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId z){return Clock.fixed(now,z);}
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class ClockConfig { @Bean @Primary MutableClock timingClock(){return new MutableClock();} }
    @Autowired MutableClock clock;
    @Autowired TaskService tasks;
    @Autowired TaskOverdueQueryService radar;
    @Autowired TaskTimingQueryService timing;
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


    @BeforeEach void resetClockAndDb()throws Exception{
        clock.at(BASE);
        try(var c=dataSource.getConnection()){
            String expected=System.getProperty("rm.radar.e.expectedDb");
            if(expected!=null){
                assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo(expected.equals("postgresql")?"PostgreSQL":"H2");
                if(expected.equals("postgresql"))assertThat(c.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            }
        }
    }
    private TaskTimingSnapshot read(Tenant t,UUID id){return timing.getMine(t.staff(),t.id(),id).timing();}
    private UUID completed(Tenant t,Instant due,Instant end){
        clock.at(BASE);var x=create(t,due);
        tasks.start(t.staff(),t.id(),x.id(),0);
        clock.at(end);tasks.complete(t.staff(),t.id(),x.id(),1);
        return x.id();
    }
    @Test void e01ExactHalfAndAdjacentMicroseconds()throws Exception{
        var t=tenant();var due=BASE.plusSeconds(100);
        UUID a=completed(t,due,BASE.plusSeconds(50).minusNanos(1000));
        UUID b=completed(t,due,BASE.plusSeconds(50));
        UUID c=completed(t,due,BASE.plusSeconds(50).plusNanos(1000));
        assertThat(read(t,a).timeliness()).isEqualTo(EARLY_50);
        assertThat(read(t,b).timeliness()).isEqualTo(EARLY_50);
        assertThat(read(t,c).timeliness()).isEqualTo(ON_TIME);
        assertThat(read(t,b).savingsPercent()).isEqualByComparingTo("50.00");
        assertThat(read(t,c).savingsPercent()).isEqualByComparingTo("50.00"); // display rounding must not classify
        assertThat(read(t,c).magnitudeMicros()).isEqualTo(BigInteger.valueOf(49_999_999));
    }
    @Test void e02EqualityLateAndNonpositivePlan()throws Exception{
        var t=tenant();var due=BASE.plusSeconds(100);
        UUID exact=completed(t,due,due);
        UUID late=completed(t,due,due.plusNanos(1000));
        assertThat(read(t,exact).direction()).isEqualTo(EXACT_COMPLETION);
        assertThat(read(t,exact).magnitudeMicros()).isZero();
        assertThat(read(t,late).timeliness()).isEqualTo(COMPLETED_LATE);
        assertThat(read(t,late).direction()).isEqualTo(LATE);
        assertThat(read(t,late).magnitudeMicros()).isEqualTo(BigInteger.ONE);
        UUID zero=completed(t,BASE,BASE);
        assertThat(read(t,zero).timeliness()).isEqualTo(ON_TIME);
        assertThat(read(t,zero).savingsPercent()).isNull();
        UUID negative=completed(t,BASE.minusSeconds(1),BASE);
        assertThat(read(t,negative).timeliness()).isEqualTo(COMPLETED_LATE);
        assertThat(read(t,negative).savingsPercent()).isNull();
    }
    @Test void e03ArchiveKeepsCompletionNotArchiveTimestamp()throws Exception{
        var t=tenant();UUID id=completed(t,BASE.plusSeconds(100),BASE.plusSeconds(40));
        var before=read(t,id);clock.at(BASE.plusSeconds(400));
        tasks.archive(t.manager(),t.id(),id,2);
        var after=read(t,id);
        assertThat(after.state()).isEqualTo(TaskState.ARCHIVED);
        assertThat(after.sourceVersion()).isEqualTo(3);
        assertThat(after.completedAt()).isEqualTo(before.completedAt());
        assertThat(after.timeliness()).isEqualTo(EARLY_50);
        assertThat(after.originalEvidenceCode()).isEqualTo(KNOWN);
        assertThat(after.deadlineChanged()).isFalse();
        assertThat(radar.listMine(t.staff(),t.id(),100,null).items()).isEmpty();
    }
    @Test void e04ReschedulePreservesOriginalLateDespiteEffectiveGreen()throws Exception{
        var t=tenant();var original=BASE.plusSeconds(20);var x=create(t,original);
        clock.at(BASE.plusSeconds(10));tasks.reschedule(t.manager(),t.id(),x.id(),BASE.plusSeconds(80),0,"e-due-"+UUID.randomUUID());
        tasks.start(t.staff(),t.id(),x.id(),1);
        clock.at(BASE.plusSeconds(15));tasks.reschedule(t.manager(),t.id(),x.id(),BASE.plusSeconds(100),2,"e-due-"+UUID.randomUUID());
        clock.at(BASE.plusSeconds(40));tasks.complete(t.staff(),t.id(),x.id(),3);
        var v=read(t,x.id());
        assertThat(v.timeliness()).isEqualTo(EARLY_50);
        assertThat(v.originalDueAt()).isEqualTo(original);
        assertThat(v.originalDeadlineOutcome()).isEqualTo(TaskTimingSnapshot.OriginalOutcome.LATE);
        assertThat(v.deadlineChanged()).isTrue();
        assertThat(v.savingsPercent()).isEqualByComparingTo("60.00");
    }
    @Test void e05AfterCutoffAndBeforeCreationFailClosed()throws Exception{
        var t=tenant();UUID id=completed(t,BASE.plusSeconds(100),BASE.plusSeconds(40));
        clock.at(BASE.plusSeconds(39));
        var v=read(t,id);
        assertThat(v.completionEvidenceCode()).isEqualTo(AFTER_CUTOFF);
        assertThat(v.timeliness()).isEqualTo(TaskTimingSnapshot.Timeliness.UNKNOWN);
        assertThat(v.completedAt()).isNull();assertThat(v.magnitudeMicros()).isNull();
        clock.at(BASE.plusSeconds(40));assertThat(read(t,id).completionEvidenceCode()).isEqualTo(VALID);
        UUID bad=completed(t,BASE.plusSeconds(100),BASE.minusSeconds(1));
        clock.at(BASE.plusSeconds(50));
        assertThat(read(t,bad).completionEvidenceCode()).isEqualTo(INVALID);
        assertThat(read(t,bad).completedAt()).isNull();
    }
    private void rawTask(Tenant t,UUID id,String status,long version,Instant due){
        jdbc.update("""
            INSERT INTO crm.task_item(tenant_id,id,assignee_user_id,created_by,status,due_at,created_at,row_version)
            VALUES (?,?,?,?,?,?,?,?)
            """,ps->{ps.setObject(1,t.id());ps.setObject(2,id);ps.setObject(3,t.staff().userId());
                ps.setObject(4,t.manager().userId());ps.setString(5,status);
                ps.setObject(6,due==null?null:due.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setObject(7,BASE.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);ps.setLong(8,version);});
    }
    private void event(Tenant t,UUID id,String action,String from,String to,Long previous,long version,
                       Instant at,Instant oldDue,Instant newDue){
        jdbc.update("""
            INSERT INTO crm.task_event(tenant_id,id,task_id,actor_user_id,action_code,from_state,to_state,
                                      previous_version,new_version,occurred_at,previous_due_at,next_due_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,ps->{ps.setObject(1,t.id());ps.setObject(2,UUID.randomUUID());ps.setObject(3,id);
                ps.setObject(4,t.staff().userId());ps.setString(5,action);ps.setString(6,from);ps.setString(7,to);
                ps.setObject(8,previous,Types.BIGINT);ps.setLong(9,version);
                ps.setObject(10,at.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setObject(11,oldDue==null?null:oldDue.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setObject(12,newDue==null?null:newDue.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);});
    }
    @Test void e06MissingDuplicateConflictingAndWrongVersionEvidence()throws Exception{
        var t=tenant();clock.at(BASE.plusSeconds(100));var due=BASE.plusSeconds(90);
        UUID missing=UUID.randomUUID();rawTask(t,missing,"COMPLETED",2,due);
        assertThat(read(t,missing).completionEvidenceCode()).isEqualTo(MISSING);
        UUID duplicate=UUID.randomUUID();rawTask(t,duplicate,"COMPLETED",3,due);
        event(t,duplicate,"COMPLETED","IN_PROGRESS","COMPLETED",1L,2,BASE.plusSeconds(30),null,null);
        event(t,duplicate,"COMPLETED","IN_PROGRESS","COMPLETED",2L,3,BASE.plusSeconds(40),null,null);
        assertThat(read(t,duplicate).completionEvidenceCode()).isEqualTo(INVALID);
        UUID archive=UUID.randomUUID();rawTask(t,archive,"ARCHIVED",3,due);
        event(t,archive,"COMPLETED","IN_PROGRESS","COMPLETED",1L,2,BASE.plusSeconds(30),null,null);
        assertThat(read(t,archive).completionEvidenceCode()).isEqualTo(INVALID);
        UUID wrong=UUID.randomUUID();rawTask(t,wrong,"COMPLETED",4,due);
        event(t,wrong,"COMPLETED","IN_PROGRESS","COMPLETED",1L,2,BASE.plusSeconds(30),null,null);
        assertThat(read(t,wrong).completionEvidenceCode()).isEqualTo(INVALID);
        assertThat(read(t,missing).originalEvidenceCode()).isEqualTo(TaskTimingSnapshot.OriginalEvidence.UNKNOWN);
    }
    @Test void e07ValidCompletionWithoutDeadlineAndIncompleteOriginal()throws Exception{
        var t=tenant();clock.at(BASE.plusSeconds(100));UUID id=UUID.randomUUID();
        rawTask(t,id,"COMPLETED",2,null);
        event(t,id,"COMPLETED","IN_PROGRESS","COMPLETED",1L,2,BASE.plusSeconds(20),null,null);
        var v=read(t,id);
        assertThat(v.completionEvidenceCode()).isEqualTo(VALID);
        assertThat(v.completedAt()).isEqualTo(BASE.plusSeconds(20));
        assertThat(v.timeliness()).isEqualTo(TaskTimingSnapshot.Timeliness.UNKNOWN);
        assertThat(v.originalEvidenceCode()).isEqualTo(TaskTimingSnapshot.OriginalEvidence.UNKNOWN);
        assertThat(v.deadlineChanged()).isNull();
    }
    @Test void e08KnownOriginalNullAndBrokenDueChain()throws Exception{
        var t=tenant();clock.at(BASE.plusSeconds(100));var due=BASE.plusSeconds(90);
        UUID a=UUID.randomUUID();rawTask(t,a,"COMPLETED",3,due);
        event(t,a,"CREATED",null,"OPEN",null,0,BASE,null,null);
        event(t,a,"DUE_DATE_CHANGED","OPEN","OPEN",0L,1,BASE.plusSeconds(1),null,due);
        event(t,a,"STARTED","OPEN","IN_PROGRESS",1L,2,BASE.plusSeconds(2),null,null);
        event(t,a,"COMPLETED","IN_PROGRESS","COMPLETED",2L,3,BASE.plusSeconds(20),null,null);
        assertThat(read(t,a).originalEvidenceCode()).isEqualTo(KNOWN_NO_DEADLINE);
        assertThat(read(t,a).deadlineChanged()).isTrue();
        UUID b=UUID.randomUUID();rawTask(t,b,"COMPLETED",4,due);
        event(t,b,"CREATED",null,"OPEN",null,0,BASE,null,null);
        event(t,b,"DUE_DATE_CHANGED","OPEN","OPEN",0L,1,BASE.plusSeconds(1),BASE.plusSeconds(10),BASE.plusSeconds(40));
        event(t,b,"DUE_DATE_CHANGED","OPEN","OPEN",1L,2,BASE.plusSeconds(2),BASE.plusSeconds(41),due);
        event(t,b,"STARTED","OPEN","IN_PROGRESS",2L,3,BASE.plusSeconds(3),null,null);
        event(t,b,"COMPLETED","IN_PROGRESS","COMPLETED",3L,4,BASE.plusSeconds(20),null,null);
        assertThat(read(t,b).originalEvidenceCode()).isEqualTo(TaskTimingSnapshot.OriginalEvidence.UNKNOWN);
        assertThat(read(t,b).originalDueAt()).isNull();
        assertThat(read(t,b).timeliness()).isEqualTo(EARLY_50);
    }
    @Test void e09ActiveProjectionMatchesOverdueAndScope()throws Exception{
        var t=tenant();var other=tenant();UUID id=UUID.randomUUID();
        rawTask(t,id,"OPEN",0,BASE.minusSeconds(100));
        rawTask(other,id,"COMPLETED",2,BASE.plusSeconds(100));
        event(other,id,"COMPLETED","IN_PROGRESS","COMPLETED",1L,2,BASE.plusSeconds(20),null,null);
        clock.at(BASE.plusSeconds(30));
        var v=read(t,id);var page=radar.listMine(t.staff(),t.id(),10,null);
        assertThat(page.items()).singleElement().satisfies(x->{
            assertThat(x.taskId()).isEqualTo(v.taskId());assertThat(x.dueAt()).isEqualTo(v.effectiveDueAt());
            assertThat(x.sourceVersion()).isEqualTo(v.sourceVersion());assertThat(x.severity()).isEqualTo(v.overdueSeverity());
            assertThat(x.overdueSeconds()).isEqualTo(v.magnitudeMicros().divide(BigInteger.valueOf(1_000_000)).longValueExact());
        });
        assertThat(v.timeliness()).isEqualTo(TaskTimingSnapshot.Timeliness.OVERDUE);
        assertThat(v.completionEvidenceCode()).isEqualTo(NOT_APPLICABLE);
        assertThat(read(other,id).timeliness()).isEqualTo(EARLY_50);
        assertThatThrownBy(()->timing.getMine(t.staff(),other.id(),id)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->timing.getMine(t.owner(),t.id(),id)).isInstanceOf(SecurityException.class);
    }
    @Test void e10RolledBackCompletionDoesNotCreateGreen()throws Exception{
        var t=tenant();var x=create(t,BASE.plusSeconds(100));tasks.start(t.staff(),t.id(),x.id(),0);
        clock.at(BASE.plusSeconds(40));var before=read(t,x.id());
        var history=jdbc.queryForList("SELECT * FROM crm.task_event WHERE tenant_id=? AND task_id=? ORDER BY new_version",t.id(),x.id());
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            tasks.complete(t.staff(),t.id(),x.id(),1);tx.setRollbackOnly();
        });
        assertThat(read(t,x.id())).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM crm.task_event WHERE tenant_id=? AND task_id=? ORDER BY new_version",t.id(),x.id())).isEqualTo(history);
    }
}
