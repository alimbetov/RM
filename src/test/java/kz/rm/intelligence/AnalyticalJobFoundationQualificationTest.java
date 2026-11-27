package kz.rm.intelligence;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RM_TEST_DB_URL",matches="jdbc:postgresql:.*")
@SpringBootTest
class AnalyticalJobFoundationQualificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired kz.rm.iam.OrganizationRepository organizations;
    @Autowired kz.rm.iam.WorkspaceRepository workspaces;
    @Autowired kz.rm.iam.UserAccountRepository users;

    UUID tenant() {
        var org=organizations.saveAndFlush(new kz.rm.iam.OrganizationEntity(UUID.randomUUID(),"Scheduler LLP","Scheduler","KZ"));
        UUID t=workspaces.saveAndFlush(kz.rm.iam.WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        UUID actor=users.saveAndFlush(new kz.rm.iam.UserAccountEntity(UUID.randomUUID(),"scheduler-"+UUID.randomUUID(),"Owner")).getId();
        jdbc.update("INSERT INTO crm.int_calendar_policy(tenant_id,version,zone_id,effective_from,created_by,created_at) VALUES (?,1,'Asia/Almaty',CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP)",t,actor);
        return t;
    }
    UUID enqueue(UUID t,String type,int hour,long version) {
        UUID j=UUID.randomUUID();
        var slot=OffsetDateTime.parse("2026-11-26T00:15:00Z").plusHours(hour);
        jdbc.update("""
            INSERT INTO crm.int_analytics_job_run(tenant_id,job_id,job_type,scheduled_slot,scheduled_at,
              business_day,calendar_version,zone_id,from_day,until_day,generation_id,status)
            VALUES (?,?,?,?,?,DATE '2026-11-25',?,'Asia/Almaty',?,?,?,'PENDING')
            """,t,j,type,slot,slot,version,
            type.equals("NIGHT")?java.sql.Date.valueOf("2026-11-01"):null,
            type.equals("NIGHT")?java.sql.Date.valueOf("2026-11-26"):null,
            type.equals("NIGHT")?UUID.randomUUID():null);
        return j;
    }
    void claim(UUID t,UUID j,int slot) {
        jdbc.update("""
            UPDATE crm.int_analytics_job_run SET status='RUNNING',cluster_slot=?,lease_owner=?,
              lease_until=clock_timestamp()+interval '5 minutes',attempt_token=attempt_token+1,attempts=attempts+1
            WHERE tenant_id=? AND job_id=?
            """,slot,UUID.randomUUID(),t,j);
    }
    void release(UUID t,UUID j) {
        jdbc.update("UPDATE crm.int_analytics_job_run SET status='PENDING',cluster_slot=NULL,lease_owner=NULL,lease_until=NULL WHERE tenant_id=? AND job_id=?",t,j);
    }
    @Test void durableJobScheduleIdentityPg() {
        UUID t=tenant(); UUID other=tenant();
        enqueue(t,"NIGHT",0,1);
        assertThatThrownBy(()->enqueue(t,"NIGHT",0,1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->enqueue(t,"NIGHT",1,1)).isInstanceOf(DataIntegrityViolationException.class);
        enqueue(other,"NIGHT",0,1);
        assertThatThrownBy(()->enqueue(t,"INTRADAY",3,2)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void durableJobSlotAndTenantFencesPg() {
        UUID t=tenant(), other=tenant();
        UUID first=enqueue(t,"NIGHT",0,1), second=enqueue(t,"INTRADAY",3,1), third=enqueue(other,"NIGHT",0,1);
        claim(t,first,1);
        try {
            assertThatThrownBy(()->claim(t,second,2)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->claim(other,third,1)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->claim(other,third,5)).isInstanceOf(DataIntegrityViolationException.class);
            claim(other,third,2);
            release(other,third);
        } finally { release(t,first); }
    }
    @Test void durableJobScopeAndAttemptFencesPg() {
        UUID t=tenant(), j=enqueue(t,"NIGHT",0,1);
        assertThatThrownBy(()->jdbc.update("UPDATE crm.int_analytics_job_run SET generation_id=? WHERE tenant_id=? AND job_id=?",UUID.randomUUID(),t,j)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE crm.int_analytics_job_run SET status='RUNNING' WHERE tenant_id=? AND job_id=?",t,j)).isInstanceOf(DataIntegrityViolationException.class);
        claim(t,j,1);
        try {
            assertThatThrownBy(()->jdbc.update("UPDATE crm.int_analytics_job_run SET attempt_token=0 WHERE tenant_id=? AND job_id=?",t,j)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE crm.int_analytics_job_run SET lease_owner=? WHERE tenant_id=? AND job_id=?",UUID.randomUUID(),t,j)).isInstanceOf(DataIntegrityViolationException.class);
        } finally { release(t,j); }
        assertThat(jdbc.queryForObject("SELECT attempt_token FROM crm.int_analytics_job_run WHERE tenant_id=? AND job_id=?",Long.class,t,j)).isEqualTo(1L);
    }
    @Test void durableJobPublicPrivilegesPg() {
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            CROSS JOIN LATERAL aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
            WHERE n.nspname='crm' AND c.relname='int_analytics_job_run' AND a.grantee=0
            """,Integer.class)).isZero();
    }
}
