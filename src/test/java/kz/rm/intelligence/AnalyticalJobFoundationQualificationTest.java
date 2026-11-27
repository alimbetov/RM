package kz.rm.intelligence;

import java.time.OffsetDateTime;
import java.time.LocalDate;
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

    @Autowired AnalyticalJobRepository jobs;

    private AnalyticalJobRepository.NightSpec spec(UUID t, int hour, LocalDate from) {
        var slot=java.time.Instant.parse("2026-11-26T00:15:00Z").plusSeconds(hour*3600L);
        return new AnalyticalJobRepository.NightSpec(t,UUID.randomUUID(),UUID.randomUUID(),
            slot,slot,LocalDate.of(2026,11,25),1,"Asia/Almaty",from,
            LocalDate.of(2026,11,26),null);
    }

    @Test void repositoryAdoptsWinningImmutableScopePg() {
        UUID t=tenant();
        var from=LocalDate.of(2026,11,1);
        var first=spec(t,0,from);
        var saved=jobs.enqueueNight(first);
        assertThat(saved.outcome()).isEqualTo(AnalyticalJobRepository.Outcome.INSERTED);
        var replay=jobs.enqueueNight(spec(t,0,from));
        assertThat(replay.outcome()).isEqualTo(AnalyticalJobRepository.Outcome.EXISTING);
        assertThat(replay.job()).isEqualTo(first.job());
        assertThat(replay.generation()).isEqualTo(first.generation());
        assertThat(jobs.enqueueNight(spec(t,0,from.plusDays(1))).outcome())
            .isEqualTo(AnalyticalJobRepository.Outcome.CONFLICT);
        assertThat(jobs.enqueueNight(spec(t,1,from)).outcome())
            .isEqualTo(AnalyticalJobRepository.Outcome.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_analytics_job_run WHERE tenant_id=?",
            Integer.class,t)).isEqualTo(1);
        assertThat(jobs.enqueueNight(spec(tenant(),0,from)).outcome())
            .isEqualTo(AnalyticalJobRepository.Outcome.INSERTED);
    }

    @Test void repositoryConcurrentEnqueuePg() throws Exception {
        UUID t=tenant();
        var from=LocalDate.of(2026,11,1);
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{start.await();return jobs.enqueueNight(spec(t,0,from));});
            var b=pool.submit(()->{start.await();return jobs.enqueueNight(spec(t,0,from));});
            start.countDown();
            var x=a.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var y=b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.List.of(x.outcome(),y.outcome())).containsExactlyInAnyOrder(
                AnalyticalJobRepository.Outcome.INSERTED,AnalyticalJobRepository.Outcome.EXISTING);
            assertThat(x.job()).isEqualTo(y.job());
            assertThat(x.generation()).isEqualTo(y.generation());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_analytics_job_run WHERE tenant_id=?",
                Integer.class,t)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test void repositoryRejectsUnknownCalendarScopePg() {
        UUID t=tenant();
        var valid=spec(t,0,LocalDate.of(2026,11,1));
        var unknownVersion=new AnalyticalJobRepository.NightSpec(t,UUID.randomUUID(),UUID.randomUUID(),
            valid.slot(),valid.scheduledAt(),valid.businessDay(),2,"Asia/Almaty",
            valid.fromDay(),valid.untilDay(),null);
        var wrongZone=new AnalyticalJobRepository.NightSpec(t,UUID.randomUUID(),UUID.randomUUID(),
            valid.slot(),valid.scheduledAt(),valid.businessDay(),1,"UTC",
            valid.fromDay(),valid.untilDay(),null);
        assertThat(jobs.enqueueNight(unknownVersion).outcome())
            .isEqualTo(AnalyticalJobRepository.Outcome.CONFLICT);
        assertThat(jobs.enqueueNight(wrongZone).outcome())
            .isEqualTo(AnalyticalJobRepository.Outcome.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_analytics_job_run WHERE tenant_id=?",
            Integer.class,t)).isZero();
        assertThat(jobs.enqueueNight(valid).outcome())
            .isEqualTo(AnalyticalJobRepository.Outcome.INSERTED);
    }


    private UUID activeTenant() {
        UUID t=tenant();
        jdbc.update("UPDATE public.org_organization SET status='ACTIVE' WHERE id=(SELECT organization_id FROM public.iam_workspace WHERE id=?)",t);
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",t);
        return t;
    }

    @Test void schedulerNightSlotsRespectCalendar() {
        var almaty=new PeriodResolver.Policy(java.time.ZoneId.of("Asia/Almaty"),1);
        var berlin=new PeriodResolver.Policy(java.time.ZoneId.of("Europe/Berlin"),2);
        var now=java.time.Instant.parse("2026-11-26T19:14:59Z");
        assertThat(AnalyticalJobService.latestNight(now,almaty).until()).isEqualTo(LocalDate.of(2026,11,26));
        var due=AnalyticalJobService.latestNight(now.plusSeconds(1),almaty);
        assertThat(due.until()).isEqualTo(LocalDate.of(2026,11,27));
        assertThat(due.slot()).isEqualTo(java.time.Instant.parse("2026-11-26T19:15:00Z"));
        assertThat(AnalyticalJobService.latestNight(now.plusSeconds(1),berlin).until())
            .isEqualTo(LocalDate.of(2026,11,26));
        var havana=new PeriodResolver.Policy(java.time.ZoneId.of("America/Havana"),1);
        // Midnight overlap: choose the earlier offset; repeated hour cannot create a new slot.
        var early=AnalyticalJobService.latestNight(java.time.Instant.parse("2026-11-01T04:20:00Z"),havana);
        var late=AnalyticalJobService.latestNight(java.time.Instant.parse("2026-11-01T05:20:00Z"),havana);
        assertThat(early).isEqualTo(late);
        assertThat(early.slot()).isEqualTo(java.time.Instant.parse("2026-11-01T04:15:00Z"));
    }

    @Test void schedulerPlansOnlyActivePilotAndEffectiveCalendarPg() {
        UUID t=activeTenant(),inactive=tenant();
        var now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
        var due=AnalyticalJobService.latestNight(now,new PeriodResolver.Policy(java.time.ZoneId.of("Asia/Almaty"),1));
        LocalDate from=due.until().minusDays(10);
        assertThat(jobs.planNight(inactive,from).state()).isEqualTo(AnalyticalJobRepository.PlanState.INELIGIBLE);
        jdbc.update("""
            INSERT INTO crm.int_calendar_policy(tenant_id,version,zone_id,effective_from,created_by,created_at)
            SELECT tenant_id,2,'Europe/Berlin',effective_from+interval '2 days',created_by,created_at+interval '2 days'
            FROM crm.int_calendar_policy WHERE tenant_id=? AND version=1
            """,t);
        var first=jobs.planNight(t,from);
        assertThat(first.state()).isEqualTo(AnalyticalJobRepository.PlanState.INSERTED);
        var replay=jobs.planNight(t,from);
        assertThat(replay.state()).isEqualTo(AnalyticalJobRepository.PlanState.EXISTING);
        assertThat(replay.job()).isEqualTo(first.job());
        assertThat(replay.generation()).isEqualTo(first.generation());
        assertThat(jdbc.queryForObject("SELECT calendar_version FROM crm.int_analytics_job_run WHERE tenant_id=? AND job_id=?",
            Long.class,t,first.job())).isEqualTo(1L);
        assertThat(jobs.planNight(t,from.minusDays(32)).state()).isEqualTo(AnalyticalJobRepository.PlanState.OUTSIDE_PILOT);
    }

    @Test void schedulerConcurrentClaimsAndExpiredTakeoverPg() throws Exception {
        UUID t=activeTenant(),other=activeTenant(),a=UUID.randomUUID(),b=UUID.randomUUID();
        enqueue(t,"NIGHT",0,1);enqueue(other,"NIGHT",0,1);
        // Existing fixture slots are historically dated; use DB clock for claim eligibility.
        var tenants=java.util.Set.of(t,other);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var x=pool.submit(()->{start.await();return jobs.claimNext(a,tenants,2,30);});
            var y=pool.submit(()->{start.await();return jobs.claimNext(b,tenants,2,30);});
            start.countDown();
            var first=x.get(20,java.util.concurrent.TimeUnit.SECONDS).orElseThrow();
            var second=y.get(20,java.util.concurrent.TimeUnit.SECONDS).orElseGet(()->jobs.claimNext(b,tenants,2,30).orElseThrow());
            assertThat(first.tenant()).isNotEqualTo(second.tenant());
            assertThat(first.slot()).isNotEqualTo(second.slot());
            assertThat(jobs.claimNext(UUID.randomUUID(),tenants,2,30)).isEmpty();
            assertThat(jobs.yieldProgress(first)).isTrue();
            assertThat(jobs.yieldProgress(second)).isTrue();
        } finally {pool.shutdownNow();}
        // Separate tenant, cap 1: expired claim must be reclaimed even with no free slot.
        UUID recovery=activeTenant();enqueue(recovery,"NIGHT",0,1);
        var scope=java.util.Set.of(recovery);
        var old=jobs.claimNext(a,scope,1,1).orElseThrow();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_until>clock_timestamp() FROM crm.int_analytics_job_run WHERE tenant_id=? AND job_id=?",Boolean.class,recovery,old.job()))){
            if(System.nanoTime()>deadline)throw new AssertionError("lease did not expire");
            Thread.sleep(25);
        }
        var next=jobs.claimNext(b,scope,1,30).orElseThrow();
        assertThat(next.job()).isEqualTo(old.job());
        assertThat(next.generation()).isEqualTo(old.generation());
        assertThat(next.slot()).isEqualTo(old.slot());
        assertThat(next.token()).isEqualTo(old.token()+1);
        assertThat(jobs.renew(old,30)).isEmpty();
        assertThat(jobs.yieldProgress(old)).isFalse();
        assertThat(jobs.retry(old,AnalyticalJobRepository.Failure.TRANSIENT)).isFalse();
        assertThat(jobs.renew(next,30)).isPresent();
        assertThat(jobs.yieldProgress(next)).isTrue();
    }

    @Test void schedulerRetryBudgetAndPolicyDriftPg() {
        UUID t=activeTenant();enqueue(t,"NIGHT",0,1);
        var scope=java.util.Set.of(t);
        UUID worker=UUID.randomUUID();
        for(int i=0;i<4;i++){
            var c=jobs.claimNext(worker,scope,1,30).orElseThrow();
            assertThat(jobs.retry(c,AnalyticalJobRepository.Failure.TRANSIENT)).isTrue();
            assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM crm.int_analytics_job_run WHERE tenant_id=?",Integer.class,t)).isEqualTo(i+1);
            if(i<3)jdbc.update("UPDATE crm.int_analytics_job_run SET next_attempt_at=clock_timestamp() WHERE tenant_id=?",t);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,t)).isEqualTo("FAILED");
        assertThat(jobs.claimNext(worker,scope,1,30)).isEmpty();
        UUID other=activeTenant();enqueue(other,"NIGHT",0,1);
        jdbc.update("UPDATE public.iam_workspace SET status='SUSPENDED' WHERE id=?",other);
        assertThat(jobs.claimNext(worker,java.util.Set.of(other),1,30)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,other)).isEqualTo("BLOCKED");
    }

}
