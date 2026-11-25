package kz.rm.intelligence;
import kz.rm.iam.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class CalendarPolicyQualificationTest {
    @Autowired CalendarPolicyService service;
    @Autowired OrganizationRepository organizations;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    static JourneySignedActorFixture issuer;
    @BeforeAll static void start() throws Exception {issuer=new JourneySignedActorFixture();}
    @AfterAll static void stop(){if(issuer!=null)issuer.close();}
    record Fixture(UUID tenant,VerifiedActor owner,VerifiedActor viewer){}
    Fixture fixture(){
        var org=organizations.saveAndFlush(new OrganizationEntity(UUID.randomUUID(),"Calendar LLP","Calendar","KZ"));
        UUID tenant=workspaces.saveAndFlush(WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID owner=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"calendar-owner-"+UUID.randomUUID(),"Owner")).getId();
        UUID viewer=users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),"calendar-viewer-"+UUID.randomUUID(),"Viewer")).getId();
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),owner,OrganizationMembershipEntity.Role.OWNER));
        memberships.saveAndFlush(new OrganizationMembershipEntity(UUID.randomUUID(),org.getId(),viewer,OrganizationMembershipEntity.Role.VIEWER));
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",tenant);
        return new Fixture(tenant,issuer.resolve(jdbc,users,owner),issuer.resolve(jdbc,users,viewer));
    }
    @Test void versionedCalendarAndNoImplicitDefault(){
        var a=fixture();
        assertThatThrownBy(()->service.current(a.owner(),a.tenant())).hasMessage("CALENDAR_NOT_CONFIGURED");
        assertThat(service.append(a.owner(),a.tenant(),"Asia/Almaty",0).version()).isEqualTo(1);
        assertThat(service.append(a.owner(),a.tenant(),"Asia/Almaty",1).version()).isEqualTo(1);
        assertThat(service.append(a.owner(),a.tenant(),"Europe/Berlin",1).version()).isEqualTo(2);
        assertThat(service.version(a.owner(),a.tenant(),1).zoneId()).isEqualTo("Asia/Almaty");
        assertThat(service.current(a.owner(),a.tenant()).zoneId()).isEqualTo("Europe/Berlin");
        assertThatThrownBy(()->service.append(a.owner(),a.tenant(),"Asia/Almaty",1)).hasMessage("CALENDAR_STALE_VERSION");
        assertThatThrownBy(()->service.append(a.owner(),a.tenant(),"+05:00",2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void calendarTenantAndOwnerIsolation(){
        var a=fixture();var b=fixture();
        service.append(a.owner(),a.tenant(),"Asia/Almaty",0);
        assertThatThrownBy(()->service.current(b.owner(),a.tenant())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.version(a.viewer(),a.tenant(),1)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.append(a.viewer(),a.tenant(),"Europe/Berlin",1)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_calendar_policy WHERE tenant_id=?",Integer.class,a.tenant())).isEqualTo(1);
    }
}
