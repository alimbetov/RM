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
@SpringBootTest(properties={
    "rm.mvp.service-mode.enabled=true",
    "rm.currency.commerce-admission.enabled=true"
})
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
    @Autowired org.springframework.context.ApplicationContext context;

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
        var tenants=java.util.Map.of(t,LocalDate.of(2026,11,1),other,LocalDate.of(2026,11,1));
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
        var scope=java.util.Map.of(recovery,LocalDate.of(2026,11,1));
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
        var scope=java.util.Map.of(t,LocalDate.of(2026,11,1));
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
        assertThat(jobs.claimNext(worker,java.util.Map.of(other,LocalDate.of(2026,11,1)),1,30)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,other)).isEqualTo("BLOCKED");
    }


    @Test void schedulerRejectsWrongPilotAndForgedClaimPg() {
        UUID t=activeTenant();enqueue(t,"NIGHT",0,1);
        UUID worker=UUID.randomUUID();
        LocalDate from=LocalDate.of(2026,11,1);
        var c=jobs.claimNext(worker,java.util.Map.of(t,from),1,30).orElseThrow();
        var forged=new AnalyticalJobRepository.Claim(c.tenant(),c.job(),UUID.randomUUID(),
            c.worker(),c.token(),c.slot(),c.leaseUntil(),c.calendar(),c.from(),c.until(),c.expectedPrevious());
        try {
            assertThat(jobs.renew(forged,30)).isEmpty();
            assertThat(jobs.yieldProgress(forged)).isFalse();
            assertThat(jobs.retry(forged,AnalyticalJobRepository.Failure.TRANSIENT)).isFalse();
            assertThat(jobs.block(forged,AnalyticalJobRepository.Failure.SOURCE_INCOMPLETE)).isFalse();
            assertThat(jobs.renew(c,30)).isPresent();
            jdbc.update("UPDATE public.iam_workspace SET status='SUSPENDED' WHERE id=?",t);
            assertThat(jobs.renew(c,30)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,t))
                .isEqualTo("BLOCKED");
        } finally {jobs.yieldProgress(c);}
        UUID other=activeTenant();enqueue(other,"NIGHT",0,1);
        assertThat(jobs.claimNext(worker,java.util.Map.of(other,from.plusDays(1)),1,30)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,other))
            .isEqualTo("BLOCKED");
    }

    @Test void schedulerDefaultOffAndMissingCalendarPg() {
        assertThat(context.getBeansOfType(AnalyticalLifecycleScheduler.class)).isEmpty();
        var org=organizations.saveAndFlush(new kz.rm.iam.OrganizationEntity(UUID.randomUUID(),"No calendar LLP","No calendar","KZ"));
        UUID t=workspaces.saveAndFlush(kz.rm.iam.WorkspaceEntity.organization(UUID.randomUUID(),org.getId())).getId();
        jdbc.update("UPDATE public.org_organization SET status='ACTIVE' WHERE id=?",org.getId());
        jdbc.update("UPDATE public.iam_workspace SET status='ACTIVE' WHERE id=?",t);
        assertThat(jobs.planNight(t,LocalDate.of(2026,11,1)).state()).isEqualTo(AnalyticalJobRepository.PlanState.NO_CALENDAR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_analytics_job_run WHERE tenant_id=?",Integer.class,t)).isZero();
    }

    @Test void schedulerNightGapShiftsForward() {
        var policy=new PeriodResolver.Policy(java.time.ZoneId.of("America/Havana"),1);
        var before=AnalyticalJobService.latestNight(java.time.Instant.parse("2026-03-08T05:14:59Z"),policy);
        var after=AnalyticalJobService.latestNight(java.time.Instant.parse("2026-03-08T05:15:00Z"),policy);
        assertThat(before.until()).isEqualTo(LocalDate.of(2026,3,7));
        assertThat(after.until()).isEqualTo(LocalDate.of(2026,3,8));
        assertThat(after.slot()).isEqualTo(java.time.Instant.parse("2026-03-08T05:15:00Z"));
    }


    @Autowired GenerationRebuildService rebuild;
    @Autowired MultidimensionalCoverageService coverage;
    @Autowired AnalyticalJobService jobService;
    @Autowired L1ProjectionService l1;

    private UUID ready(AnalyticalJobRepository.Claim c){
        var request=new GenerationRebuildService.Request(c.tenant(),c.generation(),c.calendar(),c.from(),c.until());
        for(int i=0;i<10;i++){
            var p=rebuild.step(request,100,128);
            if(p.state()==GenerationRebuildService.State.READY)return p.proof();
            assertThat(p.state()).isIn(GenerationRebuildService.State.REPLAY_PENDING,GenerationRebuildService.State.ROLLUP_PENDING);
        }
        throw new AssertionError("generation never ready");
    }

    @Test void schedulerAtomicActivationAndCompletionRollbackPg() {
        UUID t=activeTenant();enqueue(t,"NIGHT",0,1);
        var pilot=java.util.Map.of(t,LocalDate.of(2026,11,1));
        var c=jobs.claimNext(UUID.randomUUID(),pilot,1,300).orElseThrow();
        UUID proof=ready(c);
        String fault="sched_completion_"+c.job().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION crm."+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.job_id='"
            +c.job()+"'::uuid AND NEW.status='COMPLETED' THEN RAISE EXCEPTION 'SCHED_COMPLETION_FAULT'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER "+fault+" BEFORE UPDATE ON crm.int_analytics_job_run FOR EACH ROW EXECUTE FUNCTION crm."+fault+"()");
        try{
            assertThatThrownBy(()->coverage.activateScheduled(c,proof,jobs)).hasStackTraceContaining("SCHED_COMPLETION_FAULT");
            assertThat(l1.lifecycle(t,c.generation()).state()).isEqualTo("BUILDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_active_generation WHERE tenant_id=?",Integer.class,t)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,t)).isEqualTo("RUNNING");
            assertThat(coverage.status(t,c.generation(),proof)).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
        }finally{
            jdbc.execute("DROP TRIGGER "+fault+" ON crm.int_analytics_job_run");
            jdbc.execute("DROP FUNCTION crm."+fault+"()");
        }
        var activated=coverage.activateScheduled(c,proof,jobs);
        assertThat(activated.generation()).isEqualTo(c.generation());
        assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,t)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT active_proof_id FROM crm.int_analytics_job_run WHERE tenant_id=?",UUID.class,t))
            .isEqualTo(activated.activeProof());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.int_analytics_job_run j JOIN crm.int_dim_coverage_proof p
              ON (p.tenant_id,p.generation_id,p.proof_id)=(j.tenant_id,j.generation_id,j.active_proof_id)
            WHERE j.tenant_id=? AND j.actual_as_of=p.observed_at AND j.source_revision=p.source_revision
              AND j.lease_owner IS NULL AND j.cluster_slot IS NULL AND j.lease_until IS NULL
            """,Integer.class,t)).isEqualTo(1);
        assertThatThrownBy(()->coverage.activateScheduled(c,proof,jobs)).hasMessage("INT_JOB_LEASE_LOST");
    }

    @Test void schedulerWorkerResumesBoundedGenerationPg() {
        UUID t=activeTenant();
        var now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
        var due=AnalyticalJobService.latestNight(now,new PeriodResolver.Policy(java.time.ZoneId.of("Asia/Almaty"),1));
        LocalDate from=due.until().minusDays(7);
        var plan=jobs.planNight(t,from);
        assertThat(plan.state()).isEqualTo(AnalyticalJobRepository.PlanState.INSERTED);
        var pilot=java.util.Map.of(t,from);
        var limits=new AnalyticalJobService.Limits(1,300,100,2);
        // First bounded step leaves durable period stamps; no pointer.
        assertThat(jobService.runNext(UUID.randomUUID(),pilot,limits)).isEqualTo(AnalyticalJobService.RunResult.YIELDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_l3_period WHERE tenant_id=? AND generation_id=?",
            Integer.class,t,plan.generation())).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_active_generation WHERE tenant_id=?",Integer.class,t)).isZero();
        AnalyticalJobService.RunResult outcome=AnalyticalJobService.RunResult.YIELDED;
        for(int i=0;i<20&&outcome==AnalyticalJobService.RunResult.YIELDED;i++){
            jdbc.update("UPDATE crm.int_analytics_job_run SET next_attempt_at=clock_timestamp() WHERE tenant_id=? AND status='PENDING'",t);
            outcome=jobService.runNext(UUID.randomUUID(),pilot,limits);
        }
        assertThat(outcome).isEqualTo(AnalyticalJobService.RunResult.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT generation_id FROM crm.int_active_generation WHERE tenant_id=? AND projection_code='L3'",UUID.class,t))
            .isEqualTo(plan.generation());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_projection_generation WHERE tenant_id=?",Integer.class,t)).isEqualTo(1);
        assertThat(jobs.planNight(t,from).state()).isEqualTo(AnalyticalJobRepository.PlanState.EXISTING);
        assertThat(jobService.runNext(UUID.randomUUID(),pilot,limits)).isEqualTo(AnalyticalJobService.RunResult.NO_WORK);
    }

    @Test void schedulerActivationRejectsCalendarDriftAndLostLeasePg() {
        UUID t=activeTenant();enqueue(t,"NIGHT",0,1);
        var pilot=java.util.Map.of(t,LocalDate.of(2026,11,1));
        var c=jobs.claimNext(UUID.randomUUID(),pilot,1,300).orElseThrow();
        UUID proof=ready(c);
        jdbc.update("""
            INSERT INTO crm.int_calendar_policy(tenant_id,version,zone_id,effective_from,created_by,created_at)
            SELECT tenant_id,2,'Europe/Berlin',effective_from,created_by,created_at
            FROM crm.int_calendar_policy WHERE tenant_id=? AND version=1
            """,t);
        assertThatThrownBy(()->coverage.activateScheduled(c,proof,jobs)).hasMessage("INT_JOB_POLICY_CHANGED");
        assertThat(jobs.renew(c,300)).isEmpty();
        assertThatThrownBy(()->coverage.activateScheduled(c,proof,jobs)).hasMessage("INT_JOB_LEASE_LOST");
        assertThat(l1.lifecycle(t,c.generation()).state()).isEqualTo("BUILDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_active_generation WHERE tenant_id=?",Integer.class,t)).isZero();
    }


    @Test void schedulerLeaseExpiryDuringActivationRollsBackPg() {
        UUID t=activeTenant();enqueue(t,"NIGHT",0,1);
        var pilot=java.util.Map.of(t,LocalDate.of(2026,11,1));
        var prepared=jobs.claimNext(UUID.randomUUID(),pilot,1,300).orElseThrow();
        UUID proof=ready(prepared);
        assertThat(jobs.yieldProgress(prepared)).isTrue();
        jdbc.update("UPDATE crm.int_analytics_job_run SET next_attempt_at=clock_timestamp() WHERE tenant_id=?",t);
        var old=jobs.claimNext(UUID.randomUUID(),pilot,1,5).orElseThrow();
        String fault="sched_expiry_"+old.job().toString().replace("-","");
        // The existing activation writes the pointer before completing the job.
        // Wait against the persisted DB deadline, not the test JVM clock.
        jdbc.execute("CREATE FUNCTION crm."+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ "
            +"DECLARE deadline timestamptz; BEGIN IF NEW.tenant_id='"+t+"'::uuid "
            +"AND NEW.generation_id='"+old.generation()+"'::uuid THEN "
            +"SELECT lease_until INTO deadline FROM crm.int_analytics_job_run "
            +"WHERE tenant_id=NEW.tenant_id AND job_id='"+old.job()+"'::uuid; "
            +"PERFORM pg_sleep(greatest(0,extract(epoch FROM deadline-clock_timestamp())+0.1)); "
            +"END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER "+fault+" BEFORE INSERT ON crm.int_active_generation FOR EACH ROW EXECUTE FUNCTION crm."+fault+"()");
        try{
            assertThatThrownBy(()->coverage.activateScheduled(old,proof,jobs))
                .hasMessage("INT_JOB_COMPLETION_FENCE");
            assertThat(l1.lifecycle(t,old.generation()).state()).isEqualTo("BUILDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_active_generation WHERE tenant_id=?",Integer.class,t)).isZero();
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.int_dim_coverage_proof WHERE tenant_id=? AND generation_id=? AND lifecycle_revision=1
                """,Integer.class,t,old.generation())).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,t)).isEqualTo("RUNNING");
        }finally{
            jdbc.execute("DROP TRIGGER "+fault+" ON crm.int_active_generation");
            jdbc.execute("DROP FUNCTION crm."+fault+"()");
        }
        var takeover=jobs.claimNext(UUID.randomUUID(),pilot,1,300).orElseThrow();
        assertThat(takeover.job()).isEqualTo(old.job());
        assertThat(takeover.generation()).isEqualTo(old.generation());
        assertThat(takeover.slot()).isEqualTo(old.slot());
        assertThat(takeover.token()).isEqualTo(old.token()+1);
        assertThatThrownBy(()->coverage.activateScheduled(old,proof,jobs)).hasMessage("INT_JOB_LEASE_LOST");
        var active=coverage.activateScheduled(takeover,proof,jobs);
        assertThat(active.generation()).isEqualTo(old.generation());
        assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,t)).isEqualTo("COMPLETED");
    }


    @Test void schedulerReplacementRollbackPreservesActiveGenerationPg() {
        UUID t=activeTenant();
        LocalDate from=LocalDate.of(2026,11,1),until=LocalDate.of(2026,11,26);
        UUID previous=UUID.randomUUID();
        var request=new GenerationRebuildService.Request(t,previous,1,from,until.minusDays(1));
        UUID admission=null;
        for(int i=0;i<10;i++){
            var p=rebuild.step(request,100,128);
            if(p.state()==GenerationRebuildService.State.READY){admission=p.proof();break;}
        }
        assertThat(admission).isNotNull();
        var prior=coverage.activate(t,previous,admission,null);
        var slot=java.time.Instant.parse("2026-11-26T00:15:00Z");
        var spec=new AnalyticalJobRepository.NightSpec(t,UUID.randomUUID(),UUID.randomUUID(),slot,slot,
            until.minusDays(1),1,"Asia/Almaty",from,until,previous);
        assertThat(jobs.enqueueNight(spec).outcome()).isEqualTo(AnalyticalJobRepository.Outcome.INSERTED);
        var c=jobs.claimNext(UUID.randomUUID(),java.util.Map.of(t,from),1,300).orElseThrow();
        UUID proof=ready(c);
        String fault="sched_replace_"+c.job().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION crm."+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.job_id='"
            +c.job()+"'::uuid AND NEW.status='COMPLETED' THEN RAISE EXCEPTION 'SCHED_REPLACEMENT_FAULT'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER "+fault+" BEFORE UPDATE ON crm.int_analytics_job_run FOR EACH ROW EXECUTE FUNCTION crm."+fault+"()");
        try{
            assertThatThrownBy(()->coverage.activateScheduled(c,proof,jobs)).hasStackTraceContaining("SCHED_REPLACEMENT_FAULT");
            assertThat(l1.lifecycle(t,previous)).isEqualTo(new L1ProjectionService.Lifecycle("ACTIVE",1));
            assertThat(l1.lifecycle(t,c.generation())).isEqualTo(new L1ProjectionService.Lifecycle("BUILDING",0));
            assertThat(jdbc.queryForObject("SELECT generation_id FROM crm.int_active_generation WHERE tenant_id=? AND projection_code='L3'",UUID.class,t))
                .isEqualTo(previous);
            assertThat(jdbc.queryForObject("SELECT dim_proof_id FROM crm.int_active_generation WHERE tenant_id=? AND projection_code='L3'",UUID.class,t))
                .isEqualTo(prior.activeProof());
            assertThat(coverage.status(t,previous,prior.activeProof())).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_dim_coverage_proof WHERE tenant_id=? AND generation_id=? AND lifecycle_revision=1",Integer.class,t,c.generation())).isZero();
        }finally{
            jdbc.execute("DROP TRIGGER "+fault+" ON crm.int_analytics_job_run");
            jdbc.execute("DROP FUNCTION crm."+fault+"()");
        }
        var replaced=coverage.activateScheduled(c,proof,jobs);
        assertThat(l1.lifecycle(t,previous)).isEqualTo(new L1ProjectionService.Lifecycle("RETIRED",2));
        assertThat(l1.lifecycle(t,replaced.generation())).isEqualTo(new L1ProjectionService.Lifecycle("ACTIVE",1));
        assertThat(jdbc.queryForObject("SELECT generation_id FROM crm.int_active_generation WHERE tenant_id=? AND projection_code='L3'",UUID.class,t))
            .isEqualTo(c.generation());
        assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,t)).isEqualTo("COMPLETED");
    }

    @Test void schedulerRemovedPilotDoesNotStrandExpiredSlotPg() throws Exception {
        UUID removed=activeTenant(),remaining=activeTenant();
        enqueue(removed,"NIGHT",0,1);enqueue(remaining,"NIGHT",0,1);
        LocalDate from=LocalDate.of(2026,11,1);
        var old=jobs.claimNext(UUID.randomUUID(),java.util.Map.of(removed,from),1,1).orElseThrow();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_until>clock_timestamp() FROM crm.int_analytics_job_run WHERE tenant_id=? AND job_id=?",Boolean.class,removed,old.job()))){
            if(System.nanoTime()>deadline)throw new AssertionError("lease did not expire");
            Thread.sleep(25);
        }
        var remainingScope=java.util.Map.of(remaining,from);
        UUID worker=UUID.randomUUID();
        assertThat(jobs.claimNext(worker,remainingScope,1,30)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=?",String.class,removed)).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("SELECT attempt_token FROM crm.int_analytics_job_run WHERE tenant_id=?",Long.class,removed)).isEqualTo(old.token()+1);
        assertThat(jobs.renew(old,30)).isEmpty();
        assertThat(jobs.yieldProgress(old)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_projection_generation WHERE tenant_id=?",Integer.class,removed)).isZero();
        var next=jobs.claimNext(worker,remainingScope,1,30).orElseThrow();
        assertThat(next.tenant()).isEqualTo(remaining);
        assertThat(next.slot()).isEqualTo(old.slot());
        assertThat(jobs.yieldProgress(next)).isTrue();
    }


    private record OwnerFacts(UUID tenant,UUID warehouse,UUID sku,String slug,UUID offer) {}

    // Same actual owner commands and signed actor/email fixtures as BUS-MVP.
    // No direct mutation of SALES, INVENTORY, facts, receipts or projection rows.
    private OwnerFacts ownerFacts() throws Exception {return ownerFacts("Etc/UTC");}
    private OwnerFacts ownerFacts(String zone) throws Exception {return ownerFacts(zone,true);}
    private OwnerFacts ownerFacts(String zone,boolean createOrders) throws Exception {
        try(var issuer=new kz.rm.iam.JourneySignedActorFixture()){
            UUID ownerId=users.saveAndFlush(new kz.rm.iam.UserAccountEntity(UUID.randomUUID(),
                "night-owner-"+UUID.randomUUID(),"Owner")).getId();
            UUID staffId=users.saveAndFlush(new kz.rm.iam.UserAccountEntity(UUID.randomUUID(),
                "night-staff-"+UUID.randomUUID(),"Staff")).getId();
            var owner=issuer.resolve(jdbc,users,ownerId);
            var staff=issuer.resolve(jdbc,users,staffId);
            kz.rm.iam.VerifiedBuyerEmailFixture.registerAndVerify(
                context.getBean(kz.rm.iam.EmailIdentityService.class),
                context.getBean(kz.rm.iam.EmailIdentityCrypto.class),jdbc,owner,
                "night-owner-"+UUID.randomUUID()+"@example.kz");
            UUID t=UUID.randomUUID();
            var onboarding=context.getBean(kz.rm.iam.SelfServiceTenantOnboardingService.class);
            assertThat(onboarding.createFirstTenant(owner,t,"Scheduler recovery LLP","Scheduler recovery","KZ").tenantId()).isEqualTo(t);
            onboarding.selectTenant(owner,t);
            var enrollment=context.getBean(kz.rm.iam.TenantMemberEnrollmentService.class);
            enrollment.invite(owner,t,staffId);enrollment.accept(staff,t);
            var roles=context.getBean(kz.rm.iam.VerifiedTenantRoleCommands.class);
            var role=roles.createRole(owner,t,"SCH_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(java.util.Locale.ROOT),
                "Scheduler owner facts fixture");
            for(String permission:java.util.List.of("ORGANIZATION_LOCATION_WRITE","CATALOG_WRITE",
                    "CATALOG_PRICE_MANAGE","CATALOG_PUBLISH","STOREFRONT_OFFER_MANAGE",
                    "STOREFRONT_OFFER_PUBLISH","INVENTORY_RECEIVE","INVENTORY_STOCK_READ",
                    "SALES_ORDER_CONFIRM","INVENTORY_RESERVE","SALES_ORDER_FULFILL",
                    "INVENTORY_FULFILL","SALES_ORDER_READ","SALES_ORDER_CANCEL","INVENTORY_RELEASE"))
                roles.grant(owner,t,role.id(),kz.rm.iam.DomainPermission.valueOf(permission));
            roles.assign(owner,t,staffId,role.id());roles.enable(owner,t);
            context.getBean(kz.rm.organization.TenantCurrencyPolicyService.class).initialize(owner,t);
            context.getBean(kz.rm.security.kms.TenantKeyService.class).provision(t);
            String kato=jdbc.queryForObject("""
                SELECT code FROM public.ref_kato_territory
                WHERE is_deleted=FALSE AND level_no<=3 ORDER BY code LIMIT 1
                """,String.class);
            var warehouse=context.getBean(kz.rm.organization.AuthenticatedOrganizationLocations.class)
                .create(staff,t,kz.rm.organization.OrganizationLocationEntity.Type.WAREHOUSE,"Night stock",kato,"Night warehouse");
            context.getBean(kz.rm.iam.WarehouseStaffScopeService.class).assign(owner,t,warehouse.id(),staffId);
            var catalog=context.getBean(kz.rm.catalog.CatalogItemService.class);
            var draft=catalog.createDraft(staff,t,"SCH-"+UUID.randomUUID(),kz.rm.catalog.CatalogItem.Kind.PRODUCT,"ru","Night product",null);
            var priced=catalog.addPriceRevision(staff,t,draft.id(),new java.math.BigDecimal("50.0000"),"KZT",
                java.time.Instant.now().minusSeconds(100),draft.rowVersion());
            var sku=catalog.publish(staff,t,priced.id(),priced.rowVersion());
            var stores=context.getBean(kz.rm.storefront.StorefrontManagementService.class);
            var storeDraft=stores.createDraft(staff,t,"night-"+UUID.randomUUID());
            var store=stores.publish(staff,t,storeDraft.id(),storeDraft.rowVersion());
            var offers=context.getBean(kz.rm.storefront.StorefrontOfferService.class);
            var offerDraft=offers.createDraft(staff,t,sku.id(),"Night product",new java.math.BigDecimal("75.0000"),"KZT");
            var offer=offers.publish(staff,t,offerDraft.id(),offerDraft.rowVersion());
            context.getBean(kz.rm.inventory.AuthenticatedInventoryCommands.class).receive(staff,t,warehouse.id(),sku.id(),
                "night-receipt-"+UUID.randomUUID(),new java.math.BigDecimal("10"));
            if(!createOrders){
                context.getBean(CalendarPolicyService.class).append(owner,t,zone,0);
                return new OwnerFacts(t,warehouse.id(),sku.id(),store.slug(),offer.id());
            }
            var checkout=context.getBean(kz.rm.autoorder.AutoOrderService.class);
            var sales=context.getBean(kz.rm.sales.AuthenticatedSalesCommands.class);
            UUID first=checkout.checkout(store.slug(),"night-buyer-"+UUID.randomUUID()+"@example.kz",
                "night-order-"+UUID.randomUUID(),java.util.List.of(new kz.rm.autoorder.AutoOrderService.Line(offer.id(),3))).order().orderId();
            assertThat(sales.confirm(staff,t,first,warehouse.id()).status()).isEqualTo("CONFIRMED");
            assertThat(sales.fulfill(staff,t,first).status()).isEqualTo("COMPLETED");
            UUID second=checkout.checkout(store.slug(),"night-buyer-"+UUID.randomUUID()+"@example.kz",
                "night-cancel-"+UUID.randomUUID(),java.util.List.of(new kz.rm.autoorder.AutoOrderService.Line(offer.id(),2))).order().orderId();
            assertThat(sales.confirm(staff,t,second,warehouse.id()).status()).isEqualTo("CONFIRMED");
            assertThat(sales.cancel(staff,t,second).status()).isEqualTo("CANCELLED");
            context.getBean(CalendarPolicyService.class).append(owner,t,zone,0);
            kz.rm.iam.IntelligenceSourceOracle.assertTenantFacts(jdbc,t,warehouse.id(),sku.id(),
                2,"375",2,1,1,"10","3","7");
            return new OwnerFacts(t,warehouse.id(),sku.id(),store.slug(),offer.id());
        }
    }

    @Test void schedulerRealOwnerFactsResumeAfterCrashPg() throws Exception {
        var f=ownerFacts();UUID t=f.tenant();
        long facts=jdbc.queryForObject("SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=?",Long.class,t);
        assertThat(facts).isGreaterThan(2);
        var now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
        LocalDate first=jdbc.queryForObject("SELECT min(occurred_at) FROM crm.int_canonical_fact WHERE tenant_id=?",
            OffsetDateTime.class,t).atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
        LocalDate last=jdbc.queryForObject("SELECT max(occurred_at) FROM crm.int_canonical_fact WHERE tenant_id=?",
            OffsetDateTime.class,t).atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
        LocalDate from=first.minusDays(6),until=last.plusDays(1);
        // Test-only explicit job scope includes the day of freshly generated
        // service facts; this is NOT evidence of automatic closed-day planning.
        var slot=now.minusSeconds(1);
        var spec=new AnalyticalJobRepository.NightSpec(t,UUID.randomUUID(),UUID.randomUUID(),slot,slot,
            until.minusDays(1),1,"Etc/UTC",from,until,null);
        assertThat(jobs.enqueueNight(spec).outcome()).isEqualTo(AnalyticalJobRepository.Outcome.INSERTED);
        var pilot=java.util.Map.of(t,from);
        var abandoned=jobs.claimNext(UUID.randomUUID(),pilot,1,1).orElseThrow();
        var req=new GenerationRebuildService.Request(t,abandoned.generation(),1,from,until);
        var firstStep=rebuild.step(req,1,2);
        assertThat(firstStep.state()).isEqualTo(GenerationRebuildService.State.REPLAY_PENDING);
        var receipts=jdbc.queryForList("""
            SELECT consumer_code,owner_module,source_kind,source_id,digest FROM crm.int_fact_consumer_receipt
            WHERE tenant_id=? ORDER BY consumer_code,owner_module,source_kind,source_id
            """,t);
        assertThat(receipts).hasSize(2);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_until>clock_timestamp() FROM crm.int_analytics_job_run WHERE tenant_id=? AND job_id=?",
                Boolean.class,t,abandoned.job()))){
            if(System.nanoTime()>deadline)throw new AssertionError("lease did not expire");
            Thread.sleep(25);
        }
        var resumed=jobs.claimNext(UUID.randomUUID(),pilot,1,300).orElseThrow();
        assertThat(resumed.job()).isEqualTo(abandoned.job());
        assertThat(resumed.generation()).isEqualTo(abandoned.generation());
        assertThat(resumed.token()).isEqualTo(abandoned.token()+1);
        var limits=new AnalyticalJobService.Limits(1,300,1,2);
        assertThat(jobService.runNight(abandoned,limits)).isEqualTo(AnalyticalJobService.RunResult.LEASE_LOST);
        assertThat(jdbc.queryForList("""
            SELECT consumer_code,owner_module,source_kind,source_id,digest FROM crm.int_fact_consumer_receipt
            WHERE tenant_id=? ORDER BY consumer_code,owner_module,source_kind,source_id
            """,t)).isEqualTo(receipts);
        var outcome=jobService.runNight(resumed,limits);
        for(int i=0;i<40&&outcome==AnalyticalJobService.RunResult.YIELDED;i++){
            jdbc.update("UPDATE crm.int_analytics_job_run SET next_attempt_at=clock_timestamp() WHERE tenant_id=? AND status='PENDING'",t);
            outcome=jobService.runNext(UUID.randomUUID(),pilot,limits);
        }
        assertThat(outcome).isEqualTo(AnalyticalJobService.RunResult.COMPLETED);
        UUID gen=resumed.generation();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_projection_generation WHERE tenant_id=?",Integer.class,t)).isEqualTo(1);
        var finalReceipts=jdbc.queryForList("""
            SELECT consumer_code,owner_module,source_kind,source_id,digest FROM crm.int_fact_consumer_receipt
            WHERE tenant_id=? ORDER BY consumer_code,owner_module,source_kind,source_id
            """,t);
        assertThat(finalReceipts).containsAll(receipts);
        assertThat(finalReceipts).hasSize(Math.toIntExact(2*facts));
        var delivery=context.getBean(CanonicalFactDeliveryService.class);
        String l2Code=jdbc.queryForObject("SELECT consumer_code FROM crm.int_l2_consumer WHERE tenant_id=? AND generation_id=?",String.class,t,gen);
        assertThat(delivery.consumerCoverage(t,"l1:"+gen).caughtUp()).isTrue();
        assertThat(delivery.consumerCoverage(t,l2Code).caughtUp()).isTrue();
        var totals=new java.util.EnumMap<MetricRegistry.Code,java.math.BigDecimal>(MetricRegistry.Code.class);
        for(LocalDate d=from;d.isBefore(until);d=d.plusDays(1))
            for(var b:l1.buckets(t,gen,d))totals.merge(b.metric(),b.amount(),java.math.BigDecimal::add);
        var expected=java.util.Map.of(MetricRegistry.Code.PLACED_ORDERS,"2",MetricRegistry.Code.BOOKED_ORDER_AMOUNT,"375",
            MetricRegistry.Code.CONFIRMED_ORDERS,"2",MetricRegistry.Code.FULFILLED_ORDERS,"1",MetricRegistry.Code.CANCELLED_ORDERS,"1",
            MetricRegistry.Code.STOCK_IN_QTY,"10",MetricRegistry.Code.STOCK_OUT_QTY,"3");
        for(var entry:expected.entrySet())assertThat(totals.get(entry.getKey())).as(entry.getKey().name()).isEqualByComparingTo(entry.getValue());
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM crm.int_l2_contribution WHERE tenant_id=? AND generation_id=? AND stream_code='BOOKED'",
            java.math.BigDecimal.class,t,gen)).isEqualByComparingTo("375");
        assertThat(jdbc.queryForObject("""
            SELECT sum(amount) FROM crm.int_l3_cell WHERE tenant_id=? AND generation_id=? AND period_code='D' AND stream_code='BOOKED'
            """,java.math.BigDecimal.class,t,gen)).isEqualByComparingTo("375");
        assertThat(jdbc.queryForObject("""
            SELECT count(DISTINCT o.order_id) FROM crm.int_l3_cell_order o
              JOIN crm.int_l3_cell x USING(tenant_id,generation_id,period_code,from_day,cell_id)
            WHERE o.tenant_id=? AND o.generation_id=? AND o.period_code='D' AND x.stream_code='BOOKED'
            """,Long.class,t,gen)).isEqualTo(2L);
        UUID activeProof=jdbc.queryForObject("SELECT dim_proof_id FROM crm.int_active_generation WHERE tenant_id=? AND generation_id=? AND projection_code='L3'",UUID.class,t,gen);
        assertThat(coverage.status(t,gen,activeProof)).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
        kz.rm.iam.IntelligenceSourceOracle.assertTenantFacts(jdbc,t,f.warehouse(),f.sku(),2,"375",2,1,1,"10","3","7");
        assertThat(jobService.runNext(UUID.randomUUID(),pilot,limits)).isEqualTo(AnalyticalJobService.RunResult.NO_WORK);
    }

    @Test void schedulerUntrustedRoleCannotAccessRegistryPg(){
        var tx=new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        // Test-only NOLOGIN roles; all creation/grants are rolled back.
        // This is negative evidence, not an approved production scheduler role.
        for(String statement:java.util.List.of(
            "SELECT count(*) FROM crm.int_analytics_job_run",
            "UPDATE crm.int_analytics_job_run SET status='COMPLETED' WHERE false",
            "INSERT INTO crm.int_analytics_job_run(tenant_id) VALUES (NULL)",
            "DELETE FROM crm.int_analytics_job_run WHERE false",
            "TRUNCATE crm.int_analytics_job_run")){
            String role="rm_sch_denied_"+UUID.randomUUID().toString().replace("-","");
            assertThatThrownBy(()->tx.executeWithoutResult(status->{
                jdbc.execute("CREATE ROLE "+role+" NOLOGIN");
                jdbc.execute("GRANT USAGE ON SCHEMA crm TO "+role);
                jdbc.execute("SET LOCAL ROLE "+role);
                for(String privilege:java.util.List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE"))
                    assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,'crm.int_analytics_job_run',?)",
                        Boolean.class,privilege)).isFalse();
                jdbc.execute(statement);
            })).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("permission denied");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname=?",Integer.class,role)).isZero();
        }
    }

    @Test void schedulerShutdownReleasesClaimBeforeStartingStepPg() throws Exception {
        var service=org.mockito.Mockito.mock(AnalyticalJobService.class);
        UUID tenant=UUID.randomUUID();
        var from=LocalDate.of(2026,11,1);
        var fake=new AnalyticalJobRepository.Claim(tenant,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,
            java.time.Instant.now().plusSeconds(300),1,from,from.plusDays(7),null);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var finish=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(service.planNight(tenant,from)).thenReturn(new AnalyticalJobRepository.Plan(
            AnalyticalJobRepository.PlanState.EXISTING,fake.job(),fake.generation()));
        org.mockito.Mockito.when(service.claimNext(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any())).thenAnswer(inv->{
                entered.countDown();
                if(!finish.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("claim gate timeout");
                return java.util.Optional.of(fake);
            });
        var scheduler=new AnalyticalLifecycleScheduler(service,tenant+"="+from,true,1,300,100,16);
        var stopper=new Thread(scheduler::stop,"test-scheduler-stop");
        try{
            scheduler.tick();
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            stopper.start();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(stopper.getState()!=Thread.State.BLOCKED){
                if(System.nanoTime()>deadline)throw new AssertionError("shutdown did not wait for admitted claim");
                Thread.sleep(10);
            }
            finish.countDown();
            stopper.join(5000);
            assertThat(stopper.isAlive()).isFalse();
            org.mockito.Mockito.verify(service).releaseUnstarted(fake);
            org.mockito.Mockito.verify(service,org.mockito.Mockito.never()).runNight(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            scheduler.tick();
            org.mockito.Mockito.verify(service,org.mockito.Mockito.times(1)).claimNext(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.any());
        }finally{
            finish.countDown();scheduler.stop();stopper.join(5000);
        }
    }


    @Autowired IntradayResultStore intradayStore;

    private String currentIntradayZone(){
        var now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
        for(String zone:java.util.List.of("Etc/UTC","Asia/Kathmandu","Asia/Almaty","Pacific/Kiritimati","America/Los_Angeles")){
            var due=AnalyticalJobService.latestIntraday(now,new PeriodResolver.Policy(java.time.ZoneId.of(zone),1));
            if(due.isPresent()&&due.get().deadline().isAfter(now.plusSeconds(600)))return zone;
        }
        throw new AssertionError("No safe fixture slot");
    }
    private UUID serviceMember(UUID tenant,kz.rm.iam.JourneySignedActorFixture issuer) {
        UUID account=users.saveAndFlush(new kz.rm.iam.UserAccountEntity(UUID.randomUUID(),
            "scheduler-service-"+UUID.randomUUID(),"Test service")).getId();
        UUID ownerId=jdbc.queryForObject("""
            SELECT m.user_id FROM public.iam_org_membership m JOIN public.iam_workspace w ON w.organization_id=m.organization_id
            WHERE w.id=? AND m.role='OWNER' AND m.status='ACTIVE'
            """,UUID.class,tenant);
        var owner=issuer.resolve(jdbc,users,ownerId);
        var humanFixture=issuer.resolve(jdbc,users,account);
        var members=context.getBean(kz.rm.iam.TenantMemberEnrollmentService.class);
        members.invite(owner,tenant,account);members.accept(humanFixture,tenant);
        var roles=context.getBean(kz.rm.iam.VerifiedTenantRoleCommands.class);
        var role=roles.createRole(owner,tenant,"INT_SERVICE_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(java.util.Locale.ROOT),
            "Intraday qualification");
        roles.grant(owner,tenant,role.id(),kz.rm.iam.DomainPermission.SALES_ORDER_READ);
        roles.assign(owner,tenant,account,role.id());
        // Controlled test fixture only. No production provisioning or credentials.
        jdbc.update("UPDATE public.iam_user_account SET principal_kind='SERVICE' WHERE id=?",account);
        return account;
    }

    @Test void schedulerIntradayCalendarSlotsPg(){
        var p=new PeriodResolver.Policy(java.time.ZoneId.of("Asia/Almaty"),1);
        var d=LocalDate.of(2026,11,27);
        assertThat(AnalyticalJobService.latestIntraday(d.atTime(2,59).atZone(p.zone()).toInstant(),p)).isEmpty();
        for(int h=3;h<=21;h+=3){
            var due=AnalyticalJobService.latestIntraday(d.atTime(h,17).atZone(p.zone()).toInstant(),p).orElseThrow();
            assertThat(due.slot()).isEqualTo(d.atTime(h,0).atZone(p.zone()).toInstant());
            assertThat(due.day()).isEqualTo(d);
            assertThat(due.deadline()).isEqualTo(h==21?d.plusDays(1).atStartOfDay(p.zone()).toInstant():
                d.atTime(h+3,0).atZone(p.zone()).toInstant());
        }
        var berlin=new PeriodResolver.Policy(java.time.ZoneId.of("Europe/Berlin"),1);
        var spring=AnalyticalJobService.latestIntraday(java.time.Instant.parse("2026-03-29T01:00:01Z"),berlin).orElseThrow();
        assertThat(java.time.Duration.between(spring.period().startInclusive(),spring.period().endExclusive()).toHours()).isEqualTo(23);
        var autumn=AnalyticalJobService.latestIntraday(java.time.Instant.parse("2026-10-25T02:00:01Z"),berlin).orElseThrow();
        assertThat(java.time.Duration.between(autumn.period().startInclusive(),autumn.period().endExclusive()).toHours()).isEqualTo(25);
    }

    @Test void schedulerIntradaySignedIdentityAndAtomicResultPg() throws Exception {
        String zone=currentIntradayZone();
        var facts=ownerFacts(zone);UUID t=facts.tenant();
        try(var issuer=new kz.rm.iam.JourneySignedActorFixture()){
            UUID account=serviceMember(t,issuer);
            var tokenKind=kz.rm.iam.JourneySignedActorFixture.ServiceToken.VALID;
            var actor=issuer.resolveService(jdbc,users,account,tokenKind);
            for(var invalid:kz.rm.iam.JourneySignedActorFixture.ServiceToken.values()){
                if(invalid==tokenKind)continue;
                assertThatThrownBy(()->issuer.resolveService(jdbc,users,account,invalid)).isInstanceOf(SecurityException.class);
            }
            assertThatThrownBy(()->issuer.resolve(jdbc,users,account)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE public.iam_user_account SET principal_kind='HUMAN' WHERE id=?",account))
                .isInstanceOf(DataIntegrityViolationException.class);
            LocalDate day=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).atZoneSameInstant(java.time.ZoneId.of(zone)).toLocalDate();
            var scope=new IntradayResultStore.Scope(t,account,1,day,7);
            UUID job=intradayStore.plan(scope).orElseThrow();
            assertThat(intradayStore.plan(scope)).contains(job);
            var c=jobs.claimNext(UUID.randomUUID(),java.util.Map.of(),java.util.Set.of(t),1,300).orElseThrow();
            assertThat(c.type()).isEqualTo("INTRADAY");
            assertThat(c.job()).isEqualTo(job);
            assertThat(c.generation()).isNull();
            var foreign=new IntradayResultStore.Scope(UUID.randomUUID(),account,1,day,7);
            assertThatThrownBy(()->intradayStore.execute(c,foreign,actor)).isInstanceOf(SecurityException.class);
            String fault="intraday_fault_"+job.toString().replace("-","");
            jdbc.execute("CREATE FUNCTION crm."+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.job_id='"
                +job+"'::uuid AND NEW.status='COMPLETED' THEN RAISE EXCEPTION 'INTRADAY_COMPLETION_FAULT'; END IF; RETURN NEW; END; $$");
            jdbc.execute("CREATE TRIGGER "+fault+" BEFORE UPDATE ON crm.int_analytics_job_run FOR EACH ROW EXECUTE FUNCTION crm."+fault+"()");
            try{
                assertThatThrownBy(()->intradayStore.execute(c,scope,actor)).hasStackTraceContaining("INTRADAY_COMPLETION_FAULT");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_intraday_result WHERE tenant_id=? AND acknowledged_at IS NOT NULL",Integer.class,t)).isZero();
                assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=? AND job_id=?",String.class,t,job)).isEqualTo("RUNNING");
            }finally{
                jdbc.execute("DROP TRIGGER "+fault+" ON crm.int_analytics_job_run");
                jdbc.execute("DROP FUNCTION crm."+fault+"()");
            }
            assertThat(intradayStore.execute(c,scope,actor)).isEqualTo(AnalyticalJobService.RunResult.COMPLETED);
            var stored=intradayStore.completed(c,scope,actor).orElseThrow();
            assertThat(stored.value()).isEqualTo(2L);
            assertThat(stored.quality()).isEqualTo("PARTIAL");
            var oracle=jdbc.queryForObject("""
                SELECT count(DISTINCT l.order_id) FROM crm.sales_order_line l
                JOIN (SELECT tenant_id,order_id,min(occurred_at) AS event_at
                      FROM crm.sales_order_status_event WHERE tenant_id=? AND event_type='PLACED'
                      GROUP BY tenant_id,order_id HAVING count(*)=1) e
                  ON (e.tenant_id,e.order_id)=(l.tenant_id,l.order_id)
                WHERE l.tenant_id=? AND e.event_at>=? AND e.event_at<?
                """,Long.class,t,t,day.atStartOfDay(java.time.ZoneId.of(zone)).toOffsetDateTime(),stored.cutoff().atOffset(java.time.ZoneOffset.UTC));
            assertThat(stored.value()).isEqualTo(oracle);
            assertThat(intradayStore.completed(c,scope,actor)).contains(stored);
            assertThat(intradayStore.latest(scope,actor)).contains(stored);
            assertThat(intradayStore.latest(new IntradayResultStore.Scope(t,account,2,day,7),actor)).isEmpty();
            assertThat(intradayStore.latest(new IntradayResultStore.Scope(t,account,1,day.plusDays(31),7),actor)).isEmpty();
            assertThatThrownBy(()->intradayStore.execute(c,scope,actor)).hasMessage("INT_JOB_LEASE_LOST");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_active_generation WHERE tenant_id=?",Integer.class,t)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_projection_generation WHERE tenant_id=?",Integer.class,t)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_fact_consumer_receipt WHERE tenant_id=?",Integer.class,t)).isZero();
            assertThatThrownBy(()->jdbc.update("UPDATE crm.int_intraday_result SET metric_value=3 WHERE tenant_id=? AND job_id=?",t,job))
                .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",account);
            assertThatThrownBy(()->intradayStore.completed(c,scope,actor)).isInstanceOf(SecurityException.class);
        }
    }

    @Test void schedulerIntradayDirectCompletionRequiresAckPg(){
        UUID t=activeTenant();UUID job=enqueue(t,"INTRADAY",3,1);
        claim(t,job,1);
        try{
            assertThatThrownBy(()->jdbc.update("""
                UPDATE crm.int_analytics_job_run SET status='COMPLETED',actual_as_of=clock_timestamp(),source_revision=0,
                  lease_owner=NULL,lease_until=NULL,cluster_slot=NULL WHERE tenant_id=? AND job_id=?
                """,t,job)).hasStackTraceContaining("INT_INTRADAY_ATOMIC_PAIR");
            assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=? AND job_id=?",String.class,t,job)).isEqualTo("RUNNING");
        }finally{release(t,job);}
    }


    @Test void schedulerIntradayMissingAndSharedNightFencePg() throws Exception {
        String zone=currentIntradayZone();UUID t=ownerFacts(zone,false).tenant();
        try(var issuer=new kz.rm.iam.JourneySignedActorFixture()){
            UUID account=serviceMember(t,issuer);
            var actor=issuer.resolveService(jdbc,users,account,kz.rm.iam.JourneySignedActorFixture.ServiceToken.VALID);
            LocalDate day=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).atZoneSameInstant(java.time.ZoneId.of(zone)).toLocalDate();
            var scope=new IntradayResultStore.Scope(t,account,1,day,7);
            UUID job=intradayStore.plan(scope).orElseThrow();
            UUID night=enqueue(t,"NIGHT",0,1);
            claim(t,night,1);
            try{
                assertThat(jobs.claimNext(UUID.randomUUID(),java.util.Map.of(),java.util.Set.of(t),2,300)).isEmpty();
            }finally{
                jdbc.update("""
                    UPDATE crm.int_analytics_job_run SET status='SKIPPED',lease_owner=NULL,lease_until=NULL,cluster_slot=NULL
                    WHERE tenant_id=? AND job_id=?
                    """,t,night);
            }
            var c=jobs.claimNext(UUID.randomUUID(),java.util.Map.of(),java.util.Set.of(t),1,300).orElseThrow();
            assertThat(c.job()).isEqualTo(job);
            assertThat(intradayStore.execute(c,scope,actor)).isEqualTo(AnalyticalJobService.RunResult.COMPLETED);
            var result=intradayStore.completed(c,scope,actor).orElseThrow();
            assertThat(result.value()).isNull();
            assertThat(result.quality()).isEqualTo("MISSING");
            assertThat(result.reason()).isEqualTo("NO_QUALIFIED_OBSERVATION");
            assertThat(intradayStore.latest(scope,actor)).contains(result);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_active_generation WHERE tenant_id=?",Integer.class,t)).isZero();
        }
    }

    @Test void schedulerIntradayUntrustedRoleDeniedPg(){
        var tx=new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        String role="rm_intra_denied_"+UUID.randomUUID().toString().replace("-","");
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            jdbc.execute("CREATE ROLE "+role+" NOLOGIN");
            jdbc.execute("GRANT USAGE ON SCHEMA crm TO "+role);
            jdbc.execute("SET LOCAL ROLE "+role);
            for(String privilege:java.util.List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE"))
                assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,'crm.int_intraday_result',?)",Boolean.class,privilege)).isFalse();
            assertThat(jdbc.queryForObject("""
                SELECT has_function_privilege(current_user,
                  'crm.int_intraday_register(uuid,uuid,uuid,bigint,text,timestamptz,timestamptz)','EXECUTE')
                """,Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("""
                SELECT has_function_privilege(current_user,
                  'crm.int_intraday_ack(uuid,uuid,uuid,bigint,integer,uuid,text,timestamptz,timestamptz,bigint,bigint,text,text,text,timestamptz,timestamptz)','EXECUTE')
                """,Boolean.class)).isFalse();
            jdbc.execute("SELECT count(*) FROM crm.int_intraday_result");
        })).hasStackTraceContaining("permission denied");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname=?",Integer.class,role)).isZero();
    }


    @Test void schedulerIntradayAmbiguousSourceOutsideCutoffPg() throws Exception {
        String zone=currentIntradayZone();UUID t=ownerFacts(zone).tenant();
        try(var issuer=new kz.rm.iam.JourneySignedActorFixture()){
            UUID account=serviceMember(t,issuer);
            var actor=issuer.resolveService(jdbc,users,account,kz.rm.iam.JourneySignedActorFixture.ServiceToken.VALID);
            LocalDate day=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class)
                .atZoneSameInstant(java.time.ZoneId.of(zone)).toLocalDate();
            var scope=new IntradayResultStore.Scope(t,account,1,day,7);
            UUID job=intradayStore.plan(scope).orElseThrow();
            // Deliberate corrupt-source fixture, not an owner operation:
            // duplicate PLACED visible in RR but event time beyond cutoff.
            UUID order=jdbc.queryForObject("""
                SELECT order_id FROM crm.sales_order_status_event WHERE tenant_id=? AND event_type='PLACED'
                ORDER BY order_id LIMIT 1
                """,UUID.class,t);
            jdbc.update("""
                INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at)
                VALUES (?,?,?,'PLACED',clock_timestamp()+interval '2 days')
                """,t,UUID.randomUUID(),order);
            var claim=jobs.claimNext(UUID.randomUUID(),java.util.Map.of(),java.util.Set.of(t),1,300).orElseThrow();
            try{
                assertThatThrownBy(()->intradayStore.execute(claim,scope,actor)).hasMessage("INT_INTRADAY_SOURCE_AMBIGUOUS");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_intraday_result WHERE tenant_id=? AND acknowledged_at IS NOT NULL",Integer.class,t)).isZero();
                assertThat(jdbc.queryForObject("SELECT status FROM crm.int_analytics_job_run WHERE tenant_id=? AND job_id=?",String.class,t,job)).isEqualTo("RUNNING");
            }finally{
                assertThat(jobs.block(claim,AnalyticalJobRepository.Failure.SOURCE_INCOMPLETE)).isTrue();
            }
        }
    }


    @Test void schedulerIntradayLateVisibilityAndDistinctOraclePg() throws Exception {
        String zone=currentIntradayZone();var f=ownerFacts(zone);UUID t=f.tenant();
        try(var issuer=new kz.rm.iam.JourneySignedActorFixture()){
            UUID account=serviceMember(t,issuer);
            var actor=issuer.resolveService(jdbc,users,account,kz.rm.iam.JourneySignedActorFixture.ServiceToken.VALID);
            var manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
            var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
            tx.setTimeout(30);
            var auth=context.getBean(kz.rm.iam.TenantAuthorization.class);
            var reader=context.getBean(AuthorizedAnalyticalReadService.class);
            LocalDate day=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class)
                .atZoneSameInstant(java.time.ZoneId.of(zone)).toLocalDate();
            var checkout=context.getBean(kz.rm.autoorder.AutoOrderService.class);
            var before=tx.execute(status->reader.readIntraday(auth.requireIntradayRead(actor,t),t,1,day));
            assertThat(before.result().value()).isEqualByComparingTo("2");
            assertThat(before.result().quality()).isEqualTo(DataQuality.PARTIAL);
            var written=new java.util.concurrent.CountDownLatch(1);
            var allowCommit=new java.util.concurrent.CountDownLatch(1);
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            var pending=executor.submit(()->tx.execute(status->{
                UUID order=checkout.checkout(f.slug(),"late-"+UUID.randomUUID()+"@example.kz",
                    "late-"+UUID.randomUUID(),java.util.List.of(new kz.rm.autoorder.AutoOrderService.Line(f.offer(),1))).order().orderId();
                written.countDown();
                try{
                    if(!allowCommit.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("late visibility timeout");
                }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
                return order;
            }));
            try{
                assertThat(written.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                // Checkout holds workspace FOR UPDATE. An admitted service
                // read must wait, not bypass that fence. The independent
                // read-only RR oracle observes MVCC visibility separately.
                var rr=new org.springframework.transaction.support.TransactionTemplate(manager);
                rr.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                rr.setReadOnly(true);rr.setTimeout(10);
                var hiddenCutoff=rr.execute(status->{
                    var cutoff=jdbc.queryForObject("SELECT transaction_timestamp()",OffsetDateTime.class);
                    assertThat(jdbc.queryForObject("""
                        SELECT count(DISTINCT l.order_id) FROM crm.sales_order_line l
                        JOIN (SELECT tenant_id,order_id,min(occurred_at) AS event_at
                            FROM crm.sales_order_status_event WHERE tenant_id=? AND event_type='PLACED'
                            GROUP BY tenant_id,order_id HAVING count(*)=1) e
                        ON (e.tenant_id,e.order_id)=(l.tenant_id,l.order_id)
                        WHERE l.tenant_id=? AND e.event_at>=? AND e.event_at<?
                        """,Long.class,t,t,day.atStartOfDay(java.time.ZoneId.of(zone)).toOffsetDateTime(),cutoff)).isEqualTo(2L);
                    return cutoff.toInstant();
                });
                allowCommit.countDown();
                UUID late=pending.get(15,java.util.concurrent.TimeUnit.SECONDS);
                var after=tx.execute(status->reader.readIntraday(auth.requireIntradayRead(actor,t),t,1,day));
                assertThat(after.result().value()).isEqualByComparingTo("3");
                assertThat(after.result().sourceRevision()).isGreaterThan(before.result().sourceRevision());
                assertThat(after.result().observedAt()).isAfterOrEqualTo(before.result().observedAt());
                var eventAt=jdbc.queryForObject("""
                    SELECT occurred_at FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=? AND event_type='PLACED'
                    """,OffsetDateTime.class,t,late).toInstant();
                assertThat(eventAt).isBefore(hiddenCutoff);
                Long actualInterval=jdbc.queryForObject("""
                    SELECT count(DISTINCT order_id) FROM crm.sales_order_status_event
                    WHERE tenant_id=? AND event_type='PLACED' AND occurred_at>=? AND occurred_at<?
                    """,Long.class,t,hiddenCutoff.atOffset(java.time.ZoneOffset.UTC),
                    after.result().observedAt().atOffset(java.time.ZoneOffset.UTC));
                assertThat(actualInterval).isZero();
                assertThat(after.result().value().subtract(before.result().value())).isEqualByComparingTo("1");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_projection_generation WHERE tenant_id=?",Integer.class,t)).isZero();
            }finally{
                allowCommit.countDown();executor.shutdownNow();
                executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }


    @Test void schedulerIntradayTwoRealTenantsIsolationPg() throws Exception {
        String zone=currentIntradayZone();
        var first=ownerFacts(zone);var second=ownerFacts(zone,false);
        UUID a=first.tenant(),b=second.tenant();
        assertThat(a).isNotEqualTo(b);
        context.getBean(kz.rm.autoorder.AutoOrderService.class).checkout(
            second.slug(),"isolation-"+UUID.randomUUID()+"@example.kz",
            "isolation-"+UUID.randomUUID(),java.util.List.of(
                new kz.rm.autoorder.AutoOrderService.Line(second.offer(),1)));
        try(var issuer=new kz.rm.iam.JourneySignedActorFixture()){
            UUID aa=serviceMember(a,issuer),bb=serviceMember(b,issuer);
            var valid=kz.rm.iam.JourneySignedActorFixture.ServiceToken.VALID;
            var actorA=issuer.resolveService(jdbc,users,aa,valid);
            var actorB=issuer.resolveService(jdbc,users,bb,valid);
            LocalDate day=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class)
                .atZoneSameInstant(java.time.ZoneId.of(zone)).toLocalDate();
            var scopeA=new IntradayResultStore.Scope(a,aa,1,day,7);
            var scopeB=new IntradayResultStore.Scope(b,bb,1,day,7);
            var forged=new IntradayResultStore.Scope(b,aa,1,day,7);
            // Existing real tenant, not a random nonexistent tenant. The
            // planner transaction must roll back both parent and request.
            assertThatThrownBy(()->intradayStore.plan(forged))
                .hasStackTraceContaining("INT_INTRADAY_SERVICE_ACCOUNT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_analytics_job_run WHERE tenant_id=? AND job_type='INTRADAY'",Integer.class,b)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_intraday_result WHERE tenant_id=?",Integer.class,b)).isZero();
            UUID ja=intradayStore.plan(scopeA).orElseThrow();
            UUID jb=intradayStore.plan(scopeB).orElseThrow();
            var ca=jobs.claimNext(UUID.randomUUID(),java.util.Map.of(),java.util.Set.of(a),2,300).orElseThrow();
            var cb=jobs.claimNext(UUID.randomUUID(),java.util.Map.of(),java.util.Set.of(b),2,300).orElseThrow();
            try{
                assertThat(ca.job()).isEqualTo(ja);assertThat(cb.job()).isEqualTo(jb);
                assertThat(ca.slot()).isNotEqualTo(cb.slot());
                assertThatThrownBy(()->intradayStore.execute(cb,forged,actorA)).isInstanceOf(SecurityException.class);
                assertThatThrownBy(()->intradayStore.execute(ca,scopeA,actorB)).isInstanceOf(SecurityException.class);
                assertThatThrownBy(()->intradayStore.latest(forged,actorA)).isInstanceOf(SecurityException.class);
                var tx=new org.springframework.transaction.support.TransactionTemplate(
                    context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                var auth=context.getBean(kz.rm.iam.TenantAuthorization.class);
                var reader=context.getBean(AuthorizedAnalyticalReadService.class);
                assertThatThrownBy(()->tx.execute(status->reader.readIntraday(auth.requireIntradayRead(actorA,a),b,1,day)))
                    .isInstanceOf(SecurityException.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_intraday_result WHERE tenant_id IN (?,?) AND acknowledged_at IS NOT NULL",Integer.class,a,b)).isZero();
                assertThat(intradayStore.execute(ca,scopeA,actorA)).isEqualTo(AnalyticalJobService.RunResult.COMPLETED);
                assertThat(intradayStore.execute(cb,scopeB,actorB)).isEqualTo(AnalyticalJobService.RunResult.COMPLETED);
                var ra=intradayStore.latest(scopeA,actorA).orElseThrow();
                var rb=intradayStore.latest(scopeB,actorB).orElseThrow();
                assertThat(ra.job()).isEqualTo(ja);assertThat(ra.value()).isEqualTo(2L);
                assertThat(rb.job()).isEqualTo(jb);assertThat(rb.value()).isEqualTo(1L);
                assertThat(ra.digest()).isNotEqualTo(rb.digest());
                assertThatThrownBy(()->intradayStore.completed(cb,forged,actorA)).isInstanceOf(SecurityException.class);
                assertThatThrownBy(()->intradayStore.latest(scopeA,actorB)).isInstanceOf(SecurityException.class);
                assertThat(intradayStore.latest(scopeA,actorA)).contains(ra);
                assertThat(intradayStore.latest(scopeB,actorB)).contains(rb);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_active_generation WHERE tenant_id IN (?,?)",Integer.class,a,b)).isZero();
            }finally{
                // Fenced release is harmless for already completed jobs.
                jobs.yieldProgress(ca);jobs.yieldProgress(cb);
            }
        }
    }

}
