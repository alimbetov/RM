package kz.rm.tasks;

import kz.rm.iam.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class TaskOverdueSignalQualificationTest {
    @Autowired TaskService tasks;
    @Autowired TaskOverdueQueryService radar;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired VerifiedTenantRoleCommands roles;
    @Autowired JdbcTemplate jdbc;
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
    @Test void b01ActiveStatesAndExactSourceProjection() throws Exception {
        var t=tenant(); Instant due=Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var open=create(t,due); var progress=create(t,due); var blocked=create(t,due);
        tasks.start(t.staff(),t.id(),progress.id(),0);
        tasks.start(t.staff(),t.id(),blocked.id(),0);
        tasks.block(t.staff(),t.id(),blocked.id(),1);
        var p=radar.listMine(t.staff(),t.id(),10,null);
        assertThat(ids(p)).containsExactlyInAnyOrder(open.id(),progress.id(),blocked.id());
        assertThat(p.evaluatedAt().getNano()%1000).isZero();
        assertThat(p.items()).allSatisfy(x->{
            assertThat(x.dueAt()).isEqualTo(due);
            assertThat(x.overdueSeconds()).isEqualTo(Duration.between(due,p.evaluatedAt()).getSeconds());
            assertThat(x.severity()).isEqualTo(TaskOverdueQueryService.Severity.ATTENTION);
            assertThat(x.reasonCode()).isEqualTo(TaskOverdueQueryService.ReasonCode.TASK_DUE_TIME_EXCEEDED);
            assertThat(x.sourceVersion()).isEqualTo(x.taskId().equals(open.id())?0L:x.taskId().equals(progress.id())?1L:2L);
        });
        assertThat(p.nextCursor()).isNull();
    }
    @Test void b02TerminalQueuedAndFutureExcluded() throws Exception {
        var t=tenant(); Instant past=Instant.now().minusSeconds(3600);
        var done=create(t,past); tasks.start(t.staff(),t.id(),done.id(),0);
        tasks.complete(t.staff(),t.id(),done.id(),1);
        var archived=create(t,past); tasks.start(t.staff(),t.id(),archived.id(),0);
        tasks.complete(t.staff(),t.id(),archived.id(),1);
        tasks.archive(t.manager(),t.id(),archived.id(),2);
        var cancelled=create(t,past); tasks.cancel(t.manager(),t.id(),cancelled.id(),0,"cancel-"+UUID.randomUUID());
        var queued=create(t,past); tasks.requeue(t.manager(),t.id(),queued.id(),0,"queue-"+UUID.randomUUID());
        create(t,Instant.now().plusSeconds(86400));
        assertThat(radar.listMine(t.manager(),t.id(),10,null).items()).isEmpty();
        assertThat(radar.listMine(t.staff(),t.id(),10,null).items()).isEmpty();
    }
    @Test void b03TenantScopeAndWrongCursorDenied() throws Exception {
        var a=tenant(); var b=tenant(); Instant due=Instant.now().minusSeconds(3600);
        var x=create(a,due); var y=create(a,due); var foreign=create(b,due);
        var p=radar.listMine(a.staff(),a.id(),1,null);
        assertThat(ids(p)).hasSize(1).doesNotContain(foreign.id());
        assertThat(p.nextCursor()).isNotNull();
        assertThatThrownBy(()->radar.listMine(a.manager(),a.id(),1,p.nextCursor())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->radar.listMine(b.staff(),b.id(),1,p.nextCursor())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->radar.listMine(a.staff(),b.id(),10,null)).isInstanceOf(SecurityException.class);
        assertThat(ids(radar.listMine(a.manager(),a.id(),10,null))).containsExactlyInAnyOrder(x.id(),y.id());
        assertThatThrownBy(()->radar.listMine(a.owner(),a.id(),10,null)).isInstanceOf(SecurityException.class);
    }
    @Test void b04StaticKeysetTraversalAndBounds() throws Exception {
        var t=tenant(); Instant due=Instant.now().minusSeconds(10000);
        var a=create(t,due); var b=create(t,due); var c=create(t,due.plusSeconds(1));
        create(t,Instant.now().plusSeconds(5000));
        var p=radar.listMine(t.staff(),t.id(),1,null);
        List<UUID> actual=new ArrayList<>();
        for(int i=0;i<3;i++){
            assertThat(p.items()).hasSize(1);
            actual.addAll(ids(p));
            if(i<2){assertThat(p.nextCursor()).isNotNull();p=radar.listMine(t.staff(),t.id(),1,p.nextCursor());}
        }
        assertThat(p.nextCursor()).isNull();
        assertThat(actual).containsExactlyInAnyOrder(a.id(),b.id(),c.id()).doesNotHaveDuplicates();
        assertThat(actual.getLast()).isEqualTo(c.id());
        assertThat(ids(radar.listMine(t.staff(),t.id(),100,null))).containsExactlyElementsOf(actual);
        assertThatThrownBy(()->radar.listMine(t.staff(),t.id(),0,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->radar.listMine(t.staff(),t.id(),101,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void b05RescheduleFreshVersionAndNoReadWrites() throws Exception {
        var t=tenant(); var task=create(t,Instant.now().minusSeconds(300000));
        var p=radar.listMine(t.staff(),t.id(),10,null);
        assertThat(p.items()).singleElement().satisfies(x->assertThat(x.severity()).isEqualTo(TaskOverdueQueryService.Severity.CRITICAL));
        tasks.reschedule(t.manager(),t.id(),task.id(),Instant.now().plusSeconds(5000),0,"future-"+UUID.randomUUID());
        assertThat(radar.listMine(t.staff(),t.id(),10,null).items()).isEmpty();
        tasks.reschedule(t.manager(),t.id(),task.id(),Instant.now().minusSeconds(100000),1,"past-"+UUID.randomUUID());
        int before=jdbc.queryForObject("SELECT count(*) FROM crm.task_event WHERE tenant_id=?",Integer.class,t.id());
        assertThat(radar.listMine(t.staff(),t.id(),10,null).items()).singleElement().satisfies(x->{
            assertThat(x.sourceVersion()).isEqualTo(2);
            assertThat(x.severity()).isEqualTo(TaskOverdueQueryService.Severity.HIGH);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.task_event WHERE tenant_id=?",Integer.class,t.id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT row_version FROM crm.task_item WHERE tenant_id=? AND id=?",Long.class,t.id(),task.id())).isEqualTo(2L);
    }
    @Test void b06RevokedReadGrantDeniesPreviouslyMintedActor() throws Exception {
        var t=tenant(); create(t,Instant.now().minusSeconds(3600));
        assertThat(radar.listMine(t.staff(),t.id(),10,null).items()).hasSize(1);
        UUID role=jdbc.queryForObject("""
            SELECT a.role_id FROM public.iam_tenant_user_role a
            JOIN public.iam_tenant_role_permission p ON p.tenant_id=a.tenant_id AND p.role_id=a.role_id
            WHERE a.tenant_id=? AND a.user_id=? AND p.permission_code='TASK_READ'
            """,UUID.class,t.id(),t.staff().userId());
        roles.revoke(t.owner(),t.id(),role,DomainPermission.TASK_READ);
        assertThatThrownBy(()->radar.listMine(t.staff(),t.id(),10,null)).isInstanceOf(SecurityException.class);
    }
}
