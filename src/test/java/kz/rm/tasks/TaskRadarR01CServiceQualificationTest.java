package kz.rm.tasks;

import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jwt.JwtException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(TaskRadarR01CServiceQualificationTest.FixedClockConfig.class)
class TaskRadarR01CServiceQualificationTest {
    private static final Instant RAW=Instant.parse("2026-11-24T12:00:00.123456789Z");
    private static final Instant CUT=RAW.truncatedTo(ChronoUnit.MICROS);
    static final class CountingClock extends Clock {
        private final AtomicInteger samples=new AtomicInteger();
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return Clock.fixed(RAW,zone);}
        @Override public Instant instant(){samples.incrementAndGet();return RAW;}
        int samples(){return samples.get();}
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class FixedClockConfig {
        @Bean @Primary CountingClock radarQualificationClock(){return new CountingClock();}
    }
    @Autowired CountingClock clock;
    @Autowired TaskService tasks;
    @Autowired TaskOverdueQueryService radar;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
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

    @BeforeEach void verifyDb() throws Exception {
        try(var c=dataSource.getConnection()){
            String expected=System.getProperty("rm.radar.expectedDb");
            if(expected!=null){
                assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo(expected.equals("postgresql")?"PostgreSQL":"H2");
                if(expected.equals("postgresql"))assertThat(c.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            }
        }
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

    // Deliberately incomplete historical fixtures, not fabricated event evidence.
    private void legacy(Tenant t,UUID id,UUID assignee,UUID creator,Instant due){
        jdbc.update("""
            INSERT INTO crm.task_item(tenant_id,id,assignee_user_id,created_by,status,due_at,created_at,row_version)
            VALUES (?,?,?,?,'OPEN',?,?,0)
            """,ps->{
                ps.setObject(1,t.id());ps.setObject(2,id);ps.setObject(3,assignee,Types.OTHER);
                ps.setObject(4,creator,Types.OTHER);
                ps.setObject(5,due==null?null:due.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setObject(6,CUT.minusSeconds(400000).atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
            });
    }

    @Test void c01ExactCutoffAndSeverityThroughService() throws Exception {
        var t=tenant();
        var micro=create(t,CUT.minusNanos(1000));
        var equal=create(t,CUT);
        var future=create(t,CUT.plusNanos(1000));
        var before24=create(t,CUT.minusSeconds(86400).plusNanos(1000));
        var at24=create(t,CUT.minusSeconds(86400));
        var before72=create(t,CUT.minusSeconds(259200).plusNanos(1000));
        var at72=create(t,CUT.minusSeconds(259200));
        int before=clock.samples();
        var page=radar.listMine(t.staff(),t.id(),100,null);
        assertThat(clock.samples()-before).isEqualTo(1);
        assertThat(page.evaluatedAt()).isEqualTo(CUT);
        assertThat(ids(page)).containsExactly(at72.id(),before72.id(),at24.id(),before24.id(),micro.id())
            .doesNotContain(equal.id(),future.id());
        assertThat(page.items()).extracting(TaskOverdueQueryService.OverdueSignal::severity)
            .containsExactly(TaskOverdueQueryService.Severity.CRITICAL,TaskOverdueQueryService.Severity.HIGH,
                TaskOverdueQueryService.Severity.HIGH,TaskOverdueQueryService.Severity.ATTENTION,
                TaskOverdueQueryService.Severity.ATTENTION);
        assertThat(page.items()).extracting(TaskOverdueQueryService.OverdueSignal::overdueSeconds)
            .containsExactly(259200L,259199L,86400L,86399L,0L);
    }

    @Test void c02LegacyNullsOverlapUnrelatedAndCompositeTenant() throws Exception {
        var t=tenant();var foreign=tenant();var unrelated=additionalStaff(t);
        UUID same=UUID.randomUUID(), creatorOnly=UUID.randomUUID(),assigneeOnly=UUID.randomUUID();
        legacy(t,same,t.staff().userId(),t.staff().userId(),CUT.minusSeconds(5));
        legacy(foreign,same,foreign.staff().userId(),foreign.manager().userId(),CUT.minusSeconds(5000));
        legacy(t,UUID.randomUUID(),t.staff().userId(),t.staff().userId(),null);
        legacy(t,UUID.randomUUID(),null,t.staff().userId(),CUT.minusSeconds(5));
        legacy(t,creatorOnly,t.manager().userId(),t.staff().userId(),CUT.minusSeconds(4));
        legacy(t,assigneeOnly,t.staff().userId(),t.manager().userId(),CUT.minusSeconds(3));
        var p=radar.listMine(t.staff(),t.id(),100,null);
        assertThat(ids(p)).containsExactly(same,creatorOnly,assigneeOnly);
        assertThat(p.items().getFirst().dueAt()).isEqualTo(CUT.minusSeconds(5));
        assertThat(ids(radar.listMine(unrelated,t.id(),100,null))).isEmpty();
        assertThat(radar.listMine(foreign.staff(),foreign.id(),100,null).items())
            .singleElement().satisfies(x->{assertThat(x.taskId()).isEqualTo(same);
                assertThat(x.dueAt()).isEqualTo(CUT.minusSeconds(5000));});
    }

    @Test void c03ManageOnlyOwnerNullAndMalformedCursorDenied() throws Exception {
        var t=tenant();create(t,CUT.minusSeconds(10));create(t,CUT.minusSeconds(20));
        var cursor=radar.listMine(t.staff(),t.id(),1,null).nextCursor();
        assertThat(cursor).isNotNull();
        assertThatThrownBy(()->radar.listMine(null,t.id(),1,null)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->radar.listMine(t.staff(),null,1,null)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->radar.listMine(t.staff(),t.id(),2,cursor)).isInstanceOf(IllegalArgumentException.class);
        var broken=new TaskOverdueQueryService.OverdueCursor(t.id(),t.staff().userId(),1,
            CUT.plusNanos(1),cursor.lastTaskId());
        assertThatThrownBy(()->radar.listMine(t.staff(),t.id(),1,broken)).isInstanceOf(IllegalArgumentException.class);
        roles.revoke(t.owner(),t.id(),role(t,t.manager(),DomainPermission.TASK_READ),DomainPermission.TASK_READ);
        assertThatThrownBy(()->radar.listMine(t.manager(),t.id(),1,null)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->radar.listMine(t.owner(),t.id(),1,null)).isInstanceOf(SecurityException.class);
        assertThat(create(t,CUT.minusSeconds(1))).isNotNull();
        // Legacy unenforced TASK_READ policy remains available to active members;
        // re-enabling RBAC must immediately deny the already-minted actor again.
        assertThat(jdbc.update("UPDATE public.iam_tenant_rbac_config SET enforced=FALSE WHERE tenant_id=?",t.id())).isEqualTo(1);
        assertThat(radar.listMine(t.manager(),t.id(),10,null).items()).hasSize(3);
        assertThat(jdbc.update("UPDATE public.iam_tenant_rbac_config SET enforced=TRUE WHERE tenant_id=?",t.id())).isEqualTo(1);
        assertThatThrownBy(()->radar.listMine(t.manager(),t.id(),10,null)).isInstanceOf(SecurityException.class);
    }

    @Test void c04CommittedInactiveAccountMembershipWorkspaceAndPersonalDenied() throws Exception {
        // Committed-state rejection only; not proof of trusted writer locking.
        var account=tenant();var membership=tenant();var workspace=tenant();
        for(var t:List.of(account,membership,workspace))create(t,CUT.minusSeconds(10));
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED',row_version=row_version+1 WHERE id=?",account.staff().userId());
        assertThatThrownBy(()->radar.listMine(account.staff(),account.id(),10,null)).isInstanceOf(SecurityException.class);
        jdbc.update("UPDATE public.iam_org_membership SET status='SUSPENDED',row_version=row_version+1 WHERE organization_id=? AND user_id=?",
            membership.orgId(),membership.staff().userId());
        assertThatThrownBy(()->radar.listMine(membership.staff(),membership.id(),10,null)).isInstanceOf(SecurityException.class);
        jdbc.update("UPDATE public.iam_workspace SET status='SUSPENDED',row_version=row_version+1 WHERE id=?",workspace.id());
        assertThatThrownBy(()->radar.listMine(workspace.staff(),workspace.id(),10,null)).isInstanceOf(SecurityException.class);
        UUID personal=workspaces.saveAndFlush(WorkspaceEntity.personal(UUID.randomUUID(),membership.manager().userId())).getId();
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",personal);
        assertThatThrownBy(()->radar.listMine(membership.manager(),personal,10,null)).isInstanceOf(SecurityException.class);
    }

    @Test void c05SignedExpiredAndUntrustedBearerRejectedBeforeRadar() throws Exception {
        var t=tenant();
        String sub=jdbc.queryForObject("SELECT external_subject FROM public.iam_user_account WHERE id=?",String.class,t.staff().userId());
        var resolver=new OidcActorResolver(authority.decoder(),users);
        String expired=authority.issue(sub,"Bearer",SignedJwtTestAuthority.ISSUER,SignedJwtTestAuthority.AUDIENCE,
            Instant.now().minusSeconds(300).getEpochSecond(),SignedJwtTestAuthority.SigningKey.TRUSTED,null,null,null);
        String untrusted=authority.issue(sub,"Bearer",SignedJwtTestAuthority.ISSUER,SignedJwtTestAuthority.AUDIENCE,
            Instant.now().plusSeconds(600).getEpochSecond(),SignedJwtTestAuthority.SigningKey.UNTRUSTED,null,null,null);
        for(String token:List.of(expired,untrusted))
            assertThatThrownBy(()->resolver.resolve(token)).isInstanceOfAny(SecurityException.class,JwtException.class);
        create(t,CUT.minusSeconds(10));
        assertThat(radar.listMine(resolver.resolve(authority.access(sub)),t.id(),10,null).items()).hasSize(1);
    }

    @Test void c06ReassignmentRemovesOldAssigneeButPreservesCreator() throws Exception {
        var t=tenant();var replacement=additionalStaff(t);var task=create(t,CUT.minusSeconds(10));
        assertThat(ids(radar.listMine(t.staff(),t.id(),10,null))).containsExactly(task.id());
        tasks.assign(t.manager(),t.id(),task.id(),replacement.userId(),0);
        assertThat(ids(radar.listMine(t.staff(),t.id(),10,null))).isEmpty();
        assertThat(radar.listMine(replacement,t.id(),10,null).items()).singleElement()
            .satisfies(x->{assertThat(x.taskId()).isEqualTo(task.id());assertThat(x.sourceVersion()).isEqualTo(1L);});
        assertThat(ids(radar.listMine(t.manager(),t.id(),10,null))).containsExactly(task.id());
    }

    @Test void c07RollbackDoesNotChangeVisibleVersionOrHistory() throws Exception {
        var t=tenant();var task=create(t,CUT.minusSeconds(10));
        var before=radar.listMine(t.staff(),t.id(),10,null);
        var history=jdbc.queryForList("SELECT * FROM crm.task_event WHERE tenant_id=? ORDER BY task_id,new_version",t.id());
        var rows=jdbc.queryForList("SELECT * FROM crm.task_item WHERE tenant_id=? ORDER BY id",t.id());
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            tasks.reschedule(t.manager(),t.id(),task.id(),CUT.plusSeconds(100),0,"rollback-"+UUID.randomUUID());
            tx.setRollbackOnly();
        });
        assertThat(radar.listMine(t.staff(),t.id(),10,null)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM crm.task_event WHERE tenant_id=? ORDER BY task_id,new_version",t.id())).isEqualTo(history);
        assertThat(jdbc.queryForList("SELECT * FROM crm.task_item WHERE tenant_id=? ORDER BY id",t.id())).isEqualTo(rows);
    }

    private record OracleRow(UUID id,TaskState state,Instant due,long version) {}
    @Test void c08IndependentSqlOracleAndWholeTaskLedgerUnchanged() throws Exception {
        var t=tenant();var other=tenant();var unrelated=additionalStaff(t);
        for(int i=0;i<37;i++) {
            UUID assigned=i%5==0?null:i%5==1?unrelated.userId():t.staff().userId();
            UUID creator=i%3==0?t.staff().userId():t.manager().userId();
            Instant due=i%7==0?null:CUT.minusSeconds((i%9-2)*50000L);
            legacy(t,UUID.randomUUID(),assigned,creator,due);
        }
        var progress=create(t,CUT.minusSeconds(123));tasks.start(t.staff(),t.id(),progress.id(),0);
        var done=create(t,CUT.minusSeconds(123));tasks.start(t.staff(),t.id(),done.id(),0);tasks.complete(t.staff(),t.id(),done.id(),1);
        create(other,CUT.minusSeconds(123));
        var rows=jdbc.queryForList("SELECT * FROM crm.task_item WHERE tenant_id IN (?,?) ORDER BY tenant_id,id",t.id(),other.id());
        var events=jdbc.queryForList("SELECT * FROM crm.task_event WHERE tenant_id IN (?,?) ORDER BY tenant_id,task_id,new_version",t.id(),other.id());
        List<OracleRow> expected=jdbc.query("""
            SELECT id,status,due_at,row_version FROM crm.task_item
            WHERE tenant_id=? AND (created_by=? OR assignee_user_id=?)
            AND assignee_user_id IS NOT NULL AND status IN ('OPEN','IN_PROGRESS','BLOCKED')
            AND due_at<? ORDER BY due_at,id
            """,ps->{ps.setObject(1,t.id());ps.setObject(2,t.staff().userId());ps.setObject(3,t.staff().userId());
                ps.setObject(4,CUT.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);},
            (rs,n)->new OracleRow(rs.getObject(1,UUID.class),TaskState.valueOf(rs.getString(2)),
                rs.getObject(3,OffsetDateTime.class).toInstant(),rs.getLong(4)));
        assertThat(expected.size()).isGreaterThan(7);
        List<TaskOverdueQueryService.OverdueSignal> actual=new ArrayList<>();
        TaskOverdueQueryService.OverdueCursor cursor=null;
        do{
            var p=radar.listMine(t.staff(),t.id(),7,cursor);
            assertThat(p.evaluatedAt()).isEqualTo(CUT);
            assertThat(p.items().size()).isBetween(1,7);
            actual.addAll(p.items());cursor=p.nextCursor();
            assertThat(actual.size()).isLessThanOrEqualTo(expected.size());
        }while(cursor!=null);
        assertThat(actual).hasSize(expected.size());
        for(int i=0;i<expected.size();i++){
            var e=expected.get(i);var v=actual.get(i);
            long seconds=Duration.between(e.due(),CUT).getSeconds();
            var severity=seconds<86400?TaskOverdueQueryService.Severity.ATTENTION:
                seconds<259200?TaskOverdueQueryService.Severity.HIGH:TaskOverdueQueryService.Severity.CRITICAL;
            assertThat(v.taskId()).isEqualTo(e.id());assertThat(v.state()).isEqualTo(e.state());
            assertThat(v.dueAt()).isEqualTo(e.due());assertThat(v.sourceVersion()).isEqualTo(e.version());
            assertThat(v.overdueSeconds()).isEqualTo(seconds);assertThat(v.severity()).isEqualTo(severity);
        }
        assertThat(jdbc.queryForList("SELECT * FROM crm.task_item WHERE tenant_id IN (?,?) ORDER BY tenant_id,id",t.id(),other.id())).isEqualTo(rows);
        assertThat(jdbc.queryForList("SELECT * FROM crm.task_event WHERE tenant_id IN (?,?) ORDER BY tenant_id,task_id,new_version",t.id(),other.id())).isEqualTo(events);
        // A dense tie across all three statuses must preserve global top-K;
        // check exact page size and cursor exhaustion at 100/101.
        var dense=tenant();
        for(int i=0;i<101;i++){
            var task=create(dense,CUT.minusSeconds(30));
            if(i%3!=0)tasks.start(dense.staff(),dense.id(),task.id(),0);
            if(i%3==2)tasks.block(dense.staff(),dense.id(),task.id(),1);
        }
        List<UUID> ordered=jdbc.query("SELECT id FROM crm.task_item WHERE tenant_id=? ORDER BY due_at,id",
            (rs,n)->rs.getObject(1,UUID.class),dense.id());
        // Earlier, invisible rows must not consume any of the page budget.
        // Raw legacy fixtures intentionally omit history; Radar uses active
        // task state only. Another active tenant is already in the oracle.
        for(int i=0;i<105;i++)
            legacy(dense,UUID.randomUUID(),dense.manager().userId(),
                dense.manager().userId(),CUT.minusSeconds(60));
        var first=radar.listMine(dense.staff(),dense.id(),100,null);
        assertThat(ids(first)).containsExactlyElementsOf(ordered.subList(0,100));
        assertThat(first.nextCursor()).isNotNull();
        var last=radar.listMine(dense.staff(),dense.id(),100,first.nextCursor());
        assertThat(ids(last)).containsExactly(ordered.getLast());
        assertThat(last.nextCursor()).isNull();
        tasks.cancel(dense.manager(),dense.id(),ordered.getLast(),
            jdbc.queryForObject("SELECT row_version FROM crm.task_item WHERE tenant_id=? AND id=?",Long.class,dense.id(),ordered.getLast()),
            "radar-bound-"+UUID.randomUUID());
        var exact=radar.listMine(dense.staff(),dense.id(),100,null);
        assertThat(ids(exact)).containsExactlyElementsOf(ordered.subList(0,100));
        assertThat(exact.nextCursor()).isNull();

    }
}
