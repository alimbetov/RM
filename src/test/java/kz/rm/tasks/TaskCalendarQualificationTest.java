package kz.rm.tasks;

import kz.rm.iam.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Types;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.tasks.TaskCalendarQueryService.*;
import static kz.rm.tasks.TaskTimingSnapshot.Timeliness.*;

@SpringBootTest
@Import({TaskTimingCompletionQualificationTest.ClockConfig.class,TaskRadarSqlFailureQualificationTest.ProbeConfig.class})
class TaskCalendarQualificationTest {
    private static final Instant BASE=Instant.parse("2026-11-01T00:00:00Z");
    @Autowired TaskTimingCompletionQualificationTest.MutableClock clock;
    @Autowired TaskService tasks;
    @Autowired TaskCalendarQueryService calendar;
    @Autowired TaskTimingQueryService timing;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired JdbcTemplate jdbc;
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



    @BeforeEach void resetClockAndDb()throws Exception{
        clock.at(BASE);
        assertThat(dataSource).isInstanceOf(RadarSqlProbe.Wrapped.class);
        try(var c=dataSource.getConnection()){
            String expected=System.getProperty("rm.radar.f.expectedDb");
            if(expected!=null){
                assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo(expected.equals("postgresql")?"PostgreSQL":"H2");
                if(expected.equals("postgresql"))assertThat(c.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            }
        }
    }
    private Filter range(LocalDate from,LocalDate to,String zone){
        return new Filter(null,null,null,DateBasis.DUE,from,to,zone,false);
    }
    private Filter november(){
        return range(LocalDate.of(2026,11,1),LocalDate.of(2026,12,1),"UTC");
    }
    private List<UUID> pageIds(Page p){return p.items().stream().map(x->x.timing().taskId()).toList();}
    private Page page(Tenant t,Filter f,int limit,Cursor c){return calendar.listMine(t.staff(),t.id(),f,limit,c);}
    private UUID raw(Tenant t,UUID id,UUID assignee,UUID creator,String status,Instant due,String title){
        jdbc.update("""
            INSERT INTO crm.task_item(tenant_id,id,assignee_user_id,created_by,status,due_at,created_at,row_version,title)
            VALUES (?,?,?,?,?,?,?,0,?)
            """,ps->{ps.setObject(1,t.id());ps.setObject(2,id);ps.setObject(3,assignee,Types.OTHER);ps.setObject(4,creator,Types.OTHER);
                ps.setString(5,status);ps.setObject(6,due==null?null:due.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setObject(7,BASE.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);ps.setString(8,title);});
        return id;
    }
    private UUID raw(Tenant t,String state,Instant due){
        return raw(t,UUID.randomUUID(),t.staff().userId(),t.manager().userId(),state,due,"Review order");
    }
    private UUID completed(Tenant t,Instant due,Instant end){
        clock.at(BASE);var x=create(t,due);tasks.start(t.staff(),t.id(),x.id(),0);
        clock.at(end);tasks.complete(t.staff(),t.id(),x.id(),1);return x.id();
    }
    private void readTrace(RadarSqlProbe.Trace trace,long business){
        assertThat(trace.businessStatements()).isEqualTo(business);
        assertThat(trace.taskSelects()).isEqualTo(business);
        assertThat(trace.entries()).isNotEmpty().allSatisfy(e->{
            assertThat(e.select()).isTrue();assertThat(e.batch()).isFalse();
        });
    }
    @Test void f01CombinedSparseFiltersBeforeLimit()throws Exception{
        var t=tenant();clock.at(BASE.plusSeconds(300));
        for(int i=0;i<12;i++)raw(t,"CANCELLED",BASE.plusSeconds(i));
        UUID first=raw(t,"OPEN",BASE.plusSeconds(100)),second=raw(t,"BLOCKED",BASE.plusSeconds(200));
        raw(t,"OPEN",BASE.plusSeconds(86400*3));
        var f=new Filter(Perspective.MINE_ASSIGNED,Set.of(TaskState.OPEN,TaskState.BLOCKED),Set.of(OVERDUE),
            DateBasis.DUE,LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),"UTC",false);
        var p=page(t,f,1,null);
        assertThat(pageIds(p)).containsExactly(first);assertThat(p.hasMore()).isTrue();
        assertThat(pageIds(page(t,f,1,p.nextCursor()))).containsExactly(second);
    }
    @Test void f02PerspectiveAndDefaultArchivedExclusion()throws Exception{
        var t=tenant();var due=BASE.plusSeconds(100);
        UUID assigned=raw(t,"OPEN",due);
        UUID created=raw(t,UUID.randomUUID(),t.manager().userId(),t.staff().userId(),"OPEN",due.plusSeconds(1),"Review order");
        UUID both=raw(t,UUID.randomUUID(),t.staff().userId(),t.staff().userId(),"OPEN",due.plusSeconds(2),"Review order");
        UUID hidden=raw(t,UUID.randomUUID(),t.manager().userId(),t.manager().userId(),"OPEN",due.plusSeconds(3),"Review order");
        UUID archived=completed(t,due,BASE.plusSeconds(40));tasks.archive(t.manager(),t.id(),archived,2);
        assertThat(pageIds(page(t,november(),100,null))).containsExactly(assigned,created,both).doesNotContain(hidden,archived);
        var n=november();
        for(var perspective:List.of(Perspective.MINE_ASSIGNED,Perspective.MINE_CREATED)){
            var f=new Filter(perspective,null,null,n.dateBasis(),n.dateFrom(),n.dateTo(),n.zone(),false);
            assertThat(pageIds(page(t,f,100,null))).containsExactly(perspective==Perspective.MINE_ASSIGNED?assigned:created,both);
        }
        var onlyArchive=new Filter(null,Set.of(TaskState.ARCHIVED),null,n.dateBasis(),n.dateFrom(),n.dateTo(),n.zone(),false);
        assertThat(pageIds(page(t,onlyArchive,100,null))).containsExactly(archived);
    }
    @Test void f03DueVersusCompletedDateAndEvidence()throws Exception{
        var t=tenant();Instant due=BASE.plusSeconds(3*86400);
        UUID done=completed(t,due,BASE.plusSeconds(86400));
        UUID missing=raw(t,"COMPLETED",due.plusSeconds(1));
        UUID active=raw(t,"OPEN",due.plusSeconds(2));
        var d=range(LocalDate.of(2026,11,4),LocalDate.of(2026,11,5),"UTC");
        assertThat(pageIds(page(t,d,100,null))).containsExactly(done,missing,active);
        var f=new Filter(null,null,null,DateBasis.COMPLETED,LocalDate.of(2026,11,2),LocalDate.of(2026,11,3),"UTC",false);
        var p=page(t,f,100,null);
        assertThat(pageIds(p)).containsExactly(done);
        assertThat(p.items().getFirst().selectedDate()).isEqualTo(BASE.plusSeconds(86400));
        assertThat(p.items().getFirst().timing()).isEqualTo(timing.getMine(t.staff(),t.id(),done).timing());
    }
    @Test void f04HalfOpenLeapYearAndMonthBoundaries()throws Exception{
        var t=tenant();Instant start=Instant.parse("2028-02-29T00:00:00Z"),end=Instant.parse("2028-03-01T00:00:00Z");
        raw(t,"OPEN",start.minusNanos(1000));UUID a=raw(t,"OPEN",start),b=raw(t,"OPEN",end.minusNanos(1000));
        UUID c=raw(t,"OPEN",end);
        var f=range(LocalDate.of(2028,2,29),LocalDate.of(2028,3,1),"UTC");
        assertThat(pageIds(page(t,f,100,null))).containsExactly(a,b);
        assertThat(pageIds(page(t,range(LocalDate.of(2028,3,1),LocalDate.of(2028,3,2),"UTC"),100,null))).containsExactly(c);
    }
    @Test void f05DstSpringFallAndZoneDoesNotChangeTiming()throws Exception{
        var t=tenant();String zone="America/New_York";
        for(var day:List.of(LocalDate.of(2026,3,8),LocalDate.of(2026,11,1))){
            var z=ZoneId.of(zone);Instant start=day.atStartOfDay(z).toInstant(),end=day.plusDays(1).atStartOfDay(z).toInstant();
            raw(t,"OPEN",start.minusNanos(1000));UUID a=raw(t,"OPEN",start),b=raw(t,"OPEN",end.minusNanos(1000));
            raw(t,"OPEN",end);
            var p=page(t,range(day,day.plusDays(1),zone),100,null);
            assertThat(pageIds(p)).containsExactly(a,b);
            assertThat(Duration.between(p.appliedFilter().fromInclusive(),p.appliedFilter().toExclusive()).toHours())
                .isEqualTo(day.getMonthValue()==3?23:25);
            assertThat(p.items().getFirst().timing()).isEqualTo(timing.getMine(t.staff(),t.id(),a).timing());
        }
    }
    @Test void f06InvalidFilterLimitAndCursorBeforeBusinessSql()throws Exception{
        var t=tenant();raw(t,"OPEN",BASE.plusSeconds(1));
        var day=LocalDate.of(2026,11,1);
        var bad=List.of(range(day,day,"UTC"),range(day,day.plusDays(94),"UTC"),
            range(day,null,"UTC"),range(day,day.plusDays(1),"+05:00"),
            new Filter(null,null,null,DateBasis.COMPLETED,null,null,"UTC",true));
        for(var f:bad)try(var trace=RadarSqlProbe.capture(false)){
            assertThatThrownBy(()->page(t,f,10,null)).isInstanceOf(IllegalArgumentException.class);readTrace(trace,0);
        }
        for(int limit:new int[]{0,101})try(var trace=RadarSqlProbe.capture(false)){
            assertThatThrownBy(()->page(t,november(),limit,null)).isInstanceOf(IllegalArgumentException.class);readTrace(trace,0);
        }
        assertThatThrownBy(()->new Filter(null,Set.of(),null,null,null,null,null,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Filter(null,null,Set.of(),null,null,null,null,false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void f07StaticKeysetFullTraversalAndSingleSqlPerPage()throws Exception{
        var t=tenant();for(int i=0;i<121;i++)raw(t,"OPEN",BASE.plusSeconds(i%3));
        var expected=jdbc.query("SELECT id FROM crm.task_item WHERE tenant_id=? ORDER BY due_at,id",
            (rs,n)->rs.getObject(1,UUID.class),t.id());
        var before=jdbc.queryForList("SELECT * FROM crm.task_item WHERE tenant_id=? ORDER BY id",t.id());
        for(int limit:new int[]{20,50,100}){
            List<UUID> seen=new ArrayList<>();Cursor cursor=null;int count=0;
            do{
                try(var trace=RadarSqlProbe.capture(false)){
                    var p=page(t,november(),limit,cursor);readTrace(trace,1);
                    assertThat(p.items().size()).isBetween(1,limit);
                    assertThat(p.hasMore()).isEqualTo(p.nextCursor()!=null);
                    seen.addAll(pageIds(p));cursor=p.nextCursor();
                    assertThat(++count).isLessThan(10);
                }
            }while(cursor!=null);
            assertThat(seen).containsExactlyElementsOf(expected);
        }
        assertThat(jdbc.queryForList("SELECT * FROM crm.task_item WHERE tenant_id=? ORDER BY id",t.id())).isEqualTo(before);
    }
    @Test void f08CursorBindsActorTenantZoneBasisFiltersAndLimit()throws Exception{
        var t=tenant();var other=tenant();raw(t,"OPEN",BASE.plusSeconds(1));raw(t,"OPEN",BASE.plusSeconds(2));
        var n=november();var c=page(t,n,1,null).nextCursor();assertThat(c).isNotNull();
        var alternatives=List.of(range(n.dateFrom(),n.dateTo(),"Asia/Almaty"),
            range(n.dateFrom(),n.dateTo().minusDays(1),"UTC"),
            new Filter(null,null,null,DateBasis.COMPLETED,n.dateFrom(),n.dateTo(),"UTC",false),
            new Filter(Perspective.MINE_ASSIGNED,null,null,DateBasis.DUE,n.dateFrom(),n.dateTo(),"UTC",false),
            new Filter(null,Set.of(TaskState.OPEN),null,DateBasis.DUE,n.dateFrom(),n.dateTo(),"UTC",false),
            new Filter(null,null,Set.of(DUE_SOON),DateBasis.DUE,n.dateFrom(),n.dateTo(),"UTC",false));
        for(var f:alternatives)try(var trace=RadarSqlProbe.capture(false)){
            assertThatThrownBy(()->page(t,f,1,c)).isInstanceOf(IllegalArgumentException.class);readTrace(trace,0);
        }
        assertThatThrownBy(()->page(t,n,2,c)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->calendar.listMine(t.manager(),t.id(),n,1,c)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->page(other,n,1,c)).isInstanceOf(IllegalArgumentException.class);
        var malformed=new Cursor(t.id(),t.staff().userId(),c.filter(),1,BASE.plusNanos(1),c.lastTaskId());
        assertThatThrownBy(()->page(t,n,1,malformed)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void f09UndatedAndSafeTitleVocabulary()throws Exception{
        var t=tenant();UUID a=raw(t,UUID.randomUUID(),t.staff().userId(),t.manager().userId(),"OPEN",null,"Legacy private customer label");
        UUID b=raw(t,UUID.randomUUID(),t.staff().userId(),t.manager().userId(),"OPEN",null,null);
        UUID c=raw(t,"OPEN",BASE.plusSeconds(1));
        var f=new Filter(null,null,null,DateBasis.DUE,null,null,"UTC",true);
        var first=page(t,f,1,null);var second=page(t,f,1,first.nextCursor());
        assertThat(List.of(first.items().getFirst().timing().taskId(),second.items().getFirst().timing().taskId())).containsExactlyInAnyOrder(a,b);
        assertThat(first.items().getFirst().selectedDate()).isNull();assertThat(second.items().getFirst().selectedDate()).isNull();
        assertThat(first.nextCursor().lastDate()).isNull();
        assertThat(second.nextCursor()).isNull();
        assertThat(first.items().getFirst().titleCode()).isEqualTo(TaskTitlePolicy.TitleCode.TASK_GENERIC);
        assertThat(second.items().getFirst().titleCode()).isEqualTo(TaskTitlePolicy.TitleCode.TASK_GENERIC);
        var dated=page(t,november(),100,null);
        assertThat(pageIds(dated)).containsExactly(c);assertThat(dated.items().getFirst().titleCode()).isEqualTo(TaskTitlePolicy.TitleCode.REVIEW_ORDER);
    }
    @Test void f10FreshAdmissionAndCompositeTenantIsolation()throws Exception{
        var t=tenant();var other=tenant();UUID same=UUID.randomUUID();
        raw(t,same,t.staff().userId(),t.manager().userId(),"OPEN",BASE.plusSeconds(1),"Review order");
        raw(other,same,other.staff().userId(),other.manager().userId(),"OPEN",BASE.plusSeconds(2),"Check inventory");
        raw(t,"OPEN",BASE.plusSeconds(3));
        var p=page(t,november(),1,null);
        assertThat(p.items().getFirst().selectedDate()).isEqualTo(BASE.plusSeconds(1));
        assertThat(page(other,november(),100,null).items().getFirst().selectedDate()).isEqualTo(BASE.plusSeconds(2));
        UUID role=jdbc.queryForObject("""
            SELECT a.role_id FROM public.iam_tenant_user_role a JOIN public.iam_tenant_role_permission p
            ON p.tenant_id=a.tenant_id AND p.role_id=a.role_id
            WHERE a.tenant_id=? AND a.user_id=? AND p.permission_code='TASK_READ'
            """,UUID.class,t.id(),t.staff().userId());
        roles.revoke(t.owner(),t.id(),role,DomainPermission.TASK_READ);
        try(var trace=RadarSqlProbe.capture(false)){
            assertThatThrownBy(()->page(t,november(),1,p.nextCursor())).isInstanceOf(SecurityException.class);readTrace(trace,0);
        }
    }
    @Test void f11DefaultMonthAndFreshTimingOnLivePages()throws Exception{
        var t=tenant();UUID a=raw(t,"OPEN",BASE.plusSeconds(1)),b=raw(t,"OPEN",BASE.plusSeconds(2));
        var p=page(t,null,1,null);
        assertThat(p.appliedFilter().dateFrom()).isEqualTo(LocalDate.of(2026,11,1));
        assertThat(p.appliedFilter().dateTo()).isEqualTo(LocalDate.of(2026,12,1));
        assertThat(pageIds(p)).containsExactly(a);
        assertThat(p.items().getFirst().timing().timeliness()).isEqualTo(DUE_SOON);
        clock.at(BASE.plusSeconds(3));var next=page(t,null,1,p.nextCursor());
        assertThat(pageIds(next)).containsExactly(b);assertThat(next.items().getFirst().timing().timeliness()).isEqualTo(OVERDUE);
        assertThat(next.evaluatedAt()).isEqualTo(BASE.plusSeconds(3));
        clock.at(Instant.parse("2026-12-01T00:00:00Z"));
        assertThatThrownBy(()->page(t,null,1,p.nextCursor())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void f12CompletedDateIncludesValidEvidenceWithoutDue()throws Exception{
        var t=tenant();UUID id=raw(t,"COMPLETED",null);
        jdbc.update("UPDATE crm.task_item SET row_version=2 WHERE tenant_id=? AND id=?",t.id(),id);
        jdbc.update("""
            INSERT INTO crm.task_event(tenant_id,id,task_id,actor_user_id,action_code,from_state,to_state,previous_version,new_version,occurred_at)
            VALUES (?,?,?,?,'COMPLETED','IN_PROGRESS','COMPLETED',1,2,?)
            """,t.id(),UUID.randomUUID(),id,t.staff().userId(),java.sql.Timestamp.from(BASE.plusSeconds(20)));
        clock.at(BASE.plusSeconds(30));
        var f=new Filter(null,null,Set.of(UNKNOWN),DateBasis.COMPLETED,LocalDate.of(2026,11,1),LocalDate.of(2026,11,2),"UTC",false);
        var p=page(t,f,100,null);
        assertThat(pageIds(p)).containsExactly(id);
        assertThat(p.items().getFirst().timing().completedAt()).isEqualTo(BASE.plusSeconds(20));
        assertThat(p.items().getFirst().timing().effectiveDueAt()).isNull();
        assertThat(p.items().getFirst().timing().timeliness()).isEqualTo(UNKNOWN);
    }
}
