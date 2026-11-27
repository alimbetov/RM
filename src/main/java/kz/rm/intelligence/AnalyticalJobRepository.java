package kz.rm.intelligence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.sql.SQLException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal durable NIGHT planning and leased claim primitives. No activation or public API. */
@Repository
class AnalyticalJobRepository {
    record NightSpec(UUID tenant, UUID job, UUID generation, Instant slot, Instant scheduledAt,
                     LocalDate businessDay, long calendarVersion, String zoneId,
                     LocalDate fromDay, LocalDate untilDay, UUID expectedPrevious) {
        NightSpec {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(job);
            Objects.requireNonNull(generation);
            slot=Objects.requireNonNull(slot).truncatedTo(ChronoUnit.MICROS);
            scheduledAt=Objects.requireNonNull(scheduledAt).truncatedTo(ChronoUnit.MICROS);
            Objects.requireNonNull(businessDay);
            Objects.requireNonNull(fromDay);
            Objects.requireNonNull(untilDay);
            Objects.requireNonNull(zoneId);
            ZoneId.of(zoneId);
            if(calendarVersion<1 || zoneId.length()>64 || !fromDay.isBefore(untilDay)
                || !businessDay.equals(untilDay.minusDays(1)) || scheduledAt.isBefore(slot))
                throw new IllegalArgumentException("INT_JOB_INVALID_SCOPE");
        }
    }
    enum Outcome { INSERTED, EXISTING, CONFLICT }
    record Registration(Outcome outcome, UUID job, UUID generation) {}
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    AnalyticalJobRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        this.manager=manager;
    }

    /** Caller supplies an already planned scope; eligibility and scheduler identity belong to R1.1B. */
    Registration enqueueNight(NightSpec s) {
        Objects.requireNonNull(s);
        return ownTx(() -> register(s));
    }

    private <T> T ownTx(java.util.function.Supplier<T> work) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("INT_JOB_OWN_TRANSACTION");
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
        return Objects.requireNonNull(tx.execute(status->{
            jdbc.execute("SET LOCAL lock_timeout = '2s'");
            jdbc.execute("SET LOCAL statement_timeout = '8s'");
            return work.get();
        }));
    }

    private Registration register(NightSpec s) {
            int inserted=jdbc.update("""
                INSERT INTO crm.int_analytics_job_run
                  (tenant_id,job_id,job_type,scheduled_slot,scheduled_at,business_day,
                   calendar_version,zone_id,from_day,until_day,generation_id,expected_previous,status)
                SELECT ?,?,'NIGHT',?,?,?,?,?,?,?,?,?,'PENDING'
                WHERE EXISTS(SELECT 1 FROM crm.int_calendar_policy p
                  WHERE p.tenant_id=? AND p.version=? AND p.zone_id=?)
                ON CONFLICT DO NOTHING
                """,s.tenant(),s.job(),utc(s.slot()),utc(s.scheduledAt()),s.businessDay(),
                s.calendarVersion(),s.zoneId(),s.fromDay(),s.untilDay(),s.generation(),
                s.expectedPrevious(),s.tenant(),s.calendarVersion(),s.zoneId());
            // A fresh READ COMMITTED statement observes the winning committed insert.
            // Only adopt the same immutable scope; do not mutate another open job.
            Registration existing=jdbc.query("""
                SELECT job_id,generation_id,scheduled_at,business_day,calendar_version,zone_id,
                       from_day,until_day,expected_previous,status
                FROM crm.int_analytics_job_run
                WHERE tenant_id=? AND job_type='NIGHT' AND scheduled_slot=?
                """,rs->{
                    if(!rs.next())return new Registration(Outcome.CONFLICT,null,null);
                    boolean same=rs.getObject(3,OffsetDateTime.class).toInstant().equals(s.scheduledAt())
                        && rs.getObject(4,LocalDate.class).equals(s.businessDay())
                        && rs.getLong(5)==s.calendarVersion()
                        && rs.getString(6).equals(s.zoneId())
                        && rs.getObject(7,LocalDate.class).equals(s.fromDay())
                        && rs.getObject(8,LocalDate.class).equals(s.untilDay())
                        && (Objects.equals(rs.getObject(9,UUID.class),s.expectedPrevious())
                            || ("COMPLETED".equals(rs.getString(10))
                                && Objects.equals(rs.getObject(2,UUID.class),s.expectedPrevious())))
                        && !"SKIPPED".equals(rs.getString(10));
                    if(!same)return new Registration(Outcome.CONFLICT,null,null);
                    return new Registration(inserted==1?Outcome.INSERTED:Outcome.EXISTING,
                        rs.getObject(1,UUID.class),rs.getObject(2,UUID.class));
                },s.tenant(),utc(s.slot()));
            return existing;
    }

    enum PlanState { INSERTED, EXISTING, CONFLICT, INELIGIBLE, NO_CALENDAR, OUTSIDE_PILOT, ACTIVE_SCOPE_CONFLICT }
    record Plan(PlanState state, UUID job, UUID generation) {}
    private record Calendar(long version,String zone) {}
    private record Active(UUID generation,long calendar,LocalDate from,LocalDate until) {}
    private record JobKey(UUID tenant,UUID job,int slot,long token) {}
    record Claim(UUID tenant,UUID job,UUID generation,UUID worker,long token,int slot,
                 Instant leaseUntil,long calendar,LocalDate from,LocalDate until,UUID expectedPrevious) {}
    enum Failure { TRANSIENT, STALE_PROOF, SOURCE_INCOMPLETE, POLICY_CHANGED, EXECUTION_REJECTED }
    private static final String CLAIM_COLUMNS = """
        tenant_id,job_id,generation_id,lease_owner,attempt_token,cluster_slot,
        lease_until,calendar_version,from_day,until_day,expected_previous
        """;
    private static final String FENCE = """
        WHERE tenant_id=? AND job_id=? AND job_type='NIGHT' AND status='RUNNING' AND lease_owner=?
          AND attempt_token=? AND cluster_slot=? AND lease_until>clock_timestamp()
          AND generation_id=? AND calendar_version=? AND from_day=? AND until_day=?
          AND expected_previous IS NOT DISTINCT FROM CAST(? AS uuid)
        """;
    // Use the persisted job's scope and current owner records in the same
    // statement snapshot. R1.2 must repeat this under the activation locks.
    private static final String CURRENT_OWNER_POLICY = """
        AND EXISTS(SELECT 1 FROM public.iam_workspace w JOIN public.org_organization o
            ON o.id=w.organization_id WHERE w.id=j.tenant_id
              AND w.kind='ORGANIZATION' AND w.status='ACTIVE' AND o.status='ACTIVE')
        AND j.calendar_version=(SELECT p.version FROM crm.int_calendar_policy p
            WHERE p.tenant_id=j.tenant_id AND p.effective_from<=clock_timestamp()
            ORDER BY p.version DESC LIMIT 1)
        """;
    private static final String CURRENT_POLICY = CURRENT_OWNER_POLICY+"""
        AND j.expected_previous IS NOT DISTINCT FROM (SELECT a.generation_id
            FROM crm.int_active_generation a WHERE a.tenant_id=j.tenant_id AND a.projection_code='L3')
        """;

    private static Object[] fenceArgs(Claim c,Object... prefix){
        Objects.requireNonNull(c);
        var args=new ArrayList<Object>(Arrays.asList(prefix));
        Collections.addAll(args,c.tenant(),c.job(),c.worker(),c.token(),c.slot(),c.generation(),
            c.calendar(),c.from(),c.until(),c.expectedPrevious());
        return args.toArray();
    }

    private Instant dbNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class)).toInstant();
    }
    private boolean activeTenant(UUID tenant) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM public.iam_workspace w JOIN public.org_organization o
              ON o.id=w.organization_id WHERE w.id=? AND w.kind='ORGANIZATION'
              AND w.status='ACTIVE' AND o.status='ACTIVE')
            """,Boolean.class,tenant));
    }
    private Optional<Calendar> effectiveCalendar(UUID tenant,Instant now) {
        return jdbc.query("""
            SELECT version,zone_id FROM crm.int_calendar_policy
            WHERE tenant_id=? AND effective_from<=? ORDER BY version DESC LIMIT 1
            """,(rs,n)->new Calendar(rs.getLong(1),rs.getString(2)),tenant,utc(now)).stream().findFirst();
    }

    /** Explicit pilot scope only. No global tenant scan or implicit calendar. */
    Plan planNight(UUID tenant,LocalDate approvedFrom) {
        Objects.requireNonNull(tenant);Objects.requireNonNull(approvedFrom);
        return ownTx(()->{
            // Coordinate with owner workspace transitions and active-pointer CAS.
            boolean locked=!jdbc.query("""
                SELECT id FROM public.iam_workspace WHERE id=? AND kind='ORGANIZATION'
                  AND status='ACTIVE' FOR SHARE
                """,(rs,n)->rs.getObject(1,UUID.class),tenant).isEmpty();
            if(!locked||!activeTenant(tenant))return new Plan(PlanState.INELIGIBLE,null,null);
            Instant now=dbNow();
            var effective=effectiveCalendar(tenant,now);
            if(effective.isEmpty())return new Plan(PlanState.NO_CALENDAR,null,null);
            Calendar cal=effective.get();
            var due=AnalyticalJobService.latestNight(now,new PeriodResolver.Policy(ZoneId.of(cal.zone()),cal.version()));
            long days=ChronoUnit.DAYS.between(approvedFrom,due.until());
            if(days<7||days>31)return new Plan(PlanState.OUTSIDE_PILOT,null,null);
            var active=jdbc.query("""
                SELECT g.generation_id,g.calendar_version,g.from_day,g.until_day
                FROM crm.int_active_generation a JOIN crm.int_projection_generation g
                  ON g.tenant_id=a.tenant_id AND g.generation_id=a.generation_id
                WHERE a.tenant_id=? AND a.projection_code='L3'
                """,(rs,n)->new Active(rs.getObject(1,UUID.class),rs.getLong(2),
                      rs.getObject(3,LocalDate.class),rs.getObject(4,LocalDate.class)),tenant).stream().findFirst();
            if(active.isPresent()){
                Active a=active.get();
                if(a.calendar()!=cal.version()||approvedFrom.isAfter(a.from())||due.until().isBefore(a.until()))
                    return new Plan(PlanState.ACTIVE_SCOPE_CONFLICT,null,null);
            }
            // scheduled_at is deterministic nominal slot, not the discovery instant.
            var spec=new NightSpec(tenant,UUID.randomUUID(),UUID.randomUUID(),due.slot(),due.slot(),
                due.until().minusDays(1),cal.version(),cal.zone(),approvedFrom,due.until(),
                active.map(Active::generation).orElse(null));
            var result=register(spec);
            return new Plan(PlanState.valueOf(result.outcome().name()),result.job(),result.generation());
        });
    }

    /** Expired NIGHT first, then one due PENDING in a free numbered slot.
     * The unique indexes, not the pre-read, arbitrate concurrent claims. */
    Optional<Claim> claimNext(UUID worker,Map<UUID,LocalDate> pilot,int clusterLimit,int leaseSeconds) {
        Objects.requireNonNull(worker);Objects.requireNonNull(pilot);
        if(pilot.isEmpty()||pilot.size()>1000||clusterLimit<1||clusterLimit>4||leaseSeconds<1||leaseSeconds>900)
            throw new IllegalArgumentException("INT_JOB_CLAIM_LIMIT");
        var scopes=Map.copyOf(pilot);
        var tenants=scopes.keySet().stream().sorted().toList();
        String in=String.join(",",Collections.nCopies(tenants.size(),"?"));
        for(int attempt=0;attempt<2;attempt++){
            try {
                return ownTx(()->claimOnce(worker,scopes,tenants,in,clusterLimit,leaseSeconds));
            } catch(DuplicateKeyException e) {
                if(!knownClaimRace(e))throw e;
                // Rollback occurred before retry; never continue an aborted transaction.
            }
        }
        return Optional.empty();
    }
    private Optional<Claim> claimOnce(UUID worker,Map<UUID,LocalDate> scopes,List<UUID> tenants,String in,int limit,int seconds) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM crm.int_analytics_job_run
                WHERE status='RUNNING' AND cluster_slot>?)
            """,Boolean.class,limit)))throw new IllegalStateException("INT_JOB_CLUSTER_DRAIN_REQUIRED");
        var args=new ArrayList<Object>();args.add(limit);args.addAll(tenants);
        var expired=jdbc.query("""
            SELECT tenant_id,job_id,cluster_slot,attempt_token FROM crm.int_analytics_job_run
            WHERE status='RUNNING' AND job_type='NIGHT' AND lease_until<=clock_timestamp()
              AND cluster_slot<=? AND tenant_id IN (
            """+in+"""
            )
            ORDER BY lease_until,tenant_id,job_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """,(rs,n)->key(rs),args.toArray()).stream().findFirst();
        JobKey chosen;
        String oldState;
        if(expired.isPresent()){chosen=expired.get();oldState="RUNNING";}
        else {
            var free=jdbc.query("""
                SELECT s.slot FROM generate_series(1,?) AS s(slot)
                WHERE NOT EXISTS(SELECT 1 FROM crm.int_analytics_job_run j
                  WHERE j.status='RUNNING' AND j.cluster_slot=s.slot)
                ORDER BY s.slot LIMIT 1
                """,(rs,n)->rs.getInt(1),limit).stream().findFirst();
            if(free.isEmpty())return Optional.empty();
            var pending=jdbc.query("""
                SELECT j.tenant_id,j.job_id,j.attempt_token FROM crm.int_analytics_job_run j
                WHERE j.status='PENDING' AND j.job_type='NIGHT'
                  AND j.next_attempt_at<=clock_timestamp() AND j.scheduled_at<=clock_timestamp()
                  AND j.tenant_id IN (
                """+in+"""
                ) AND NOT EXISTS(SELECT 1 FROM crm.int_analytics_job_run r
                      WHERE r.tenant_id=j.tenant_id AND r.status='RUNNING')
                ORDER BY j.next_attempt_at,j.scheduled_slot,j.tenant_id,j.job_id
                LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                """,(rs,n)->new JobKey(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                      free.get(),rs.getLong(3)),tenants.toArray()).stream().findFirst();
            if(pending.isEmpty())return Optional.empty();
            chosen=pending.get();oldState="PENDING";
        }
        // A locked expired row retains its slot and immutable generation.
        var claimed=jdbc.query("""
            UPDATE crm.int_analytics_job_run SET status='RUNNING',cluster_slot=?,lease_owner=?,
              lease_until=clock_timestamp()+make_interval(secs=>?),attempt_token=attempt_token+1,
              attempts=attempts+1,updated_at=clock_timestamp()
            WHERE tenant_id=? AND job_id=? AND status=? AND attempt_token=?
            RETURNING
            """+CLAIM_COLUMNS,(rs,n)->claim(rs),chosen.slot(),worker,seconds,chosen.tenant(),
                chosen.job(),oldState,chosen.token()).stream().findFirst();
        if(claimed.isEmpty())throw new IllegalStateException("INT_JOB_LOCKED_CLAIM_LOST");
        Claim c=claimed.get();
        long days=ChronoUnit.DAYS.between(c.from(),c.until());
        if(!Objects.equals(scopes.get(c.tenant()),c.from())||days<7||days>31||!currentPolicy(c)){
            if(!release(c,"BLOCKED",Failure.POLICY_CHANGED))throw new IllegalStateException("INT_JOB_POLICY_FENCE_LOST");
            return Optional.empty();
        }
        return claimed;
    }
    private boolean currentPolicy(Claim c) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM crm.int_analytics_job_run j "+FENCE+CURRENT_POLICY+")",
            Boolean.class,fenceArgs(c)));
    }
    private static JobKey key(java.sql.ResultSet rs) throws SQLException {
        return new JobKey(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getInt(3),rs.getLong(4));
    }
    private static Claim claim(java.sql.ResultSet rs) throws SQLException {
        return new Claim(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
            rs.getObject(4,UUID.class),rs.getLong(5),rs.getInt(6),
            rs.getObject(7,OffsetDateTime.class).toInstant(),rs.getLong(8),
            rs.getObject(9,LocalDate.class),rs.getObject(10,LocalDate.class),rs.getObject(11,UUID.class));
    }
    private static boolean knownClaimRace(DuplicateKeyException ex) {
        for(Throwable x=ex;x!=null;x=x.getCause()){
            if(x instanceof SQLException sql&&"23505".equals(sql.getSQLState())){
                String m=sql.getMessage();
                return m!=null&&(m.contains("\"uq_int_analytics_job_running_slot\"")||
                    m.contains("\"uq_int_analytics_job_tenant_running\""));
            }
        }
        return false;
    }
    Optional<Instant> renew(Claim c,int seconds) {
        Objects.requireNonNull(c);
        if(seconds<1||seconds>900)throw new IllegalArgumentException("INT_JOB_LEASE_LIMIT");
        return ownTx(()->{
            var renewed=jdbc.query("""
                UPDATE crm.int_analytics_job_run j
                SET lease_until=greatest(lease_until,clock_timestamp()+make_interval(secs=>?)),
                    updated_at=clock_timestamp()
                """+FENCE+CURRENT_POLICY+" RETURNING lease_until",
                (rs,n)->rs.getObject(1,OffsetDateTime.class).toInstant(),
                fenceArgs(c,seconds)).stream().findFirst();
            // A stale or forged claim cannot release the current owner's row.
            // A live claim with a changed owner policy is fenced BLOCKED.
            if(renewed.isEmpty()&&!currentPolicy(c))release(c,"BLOCKED",Failure.POLICY_CHANGED);
            return renewed;
        });
    }
    boolean yieldProgress(Claim c){return ownTx(()->release(c,"PENDING",null));}
    boolean block(Claim c,Failure reason){
        if(reason!=Failure.SOURCE_INCOMPLETE&&reason!=Failure.POLICY_CHANGED&&reason!=Failure.EXECUTION_REJECTED)
            throw new IllegalArgumentException("INT_JOB_BLOCK_REASON");
        return ownTx(()->release(c,"BLOCKED",reason));
    }
    boolean retry(Claim c,Failure reason){
        if(reason!=Failure.TRANSIENT&&reason!=Failure.STALE_PROOF)
            throw new IllegalArgumentException("INT_JOB_RETRY_REASON");
        return ownTx(()->jdbc.update("""
            UPDATE crm.int_analytics_job_run SET
              status=CASE WHEN consecutive_failures>=3 THEN 'FAILED' ELSE 'PENDING' END,
              next_attempt_at=clock_timestamp()+make_interval(secs=>CASE consecutive_failures
                WHEN 0 THEN 60 WHEN 1 THEN 300 ELSE 900 END),
              consecutive_failures=consecutive_failures+1,last_error_code=?,
              lease_owner=NULL,lease_until=NULL,cluster_slot=NULL,updated_at=clock_timestamp()
            """+FENCE,fenceArgs(c,reason.name()))==1);
    }
    private boolean release(Claim c,String state,Failure reason){
        return jdbc.update("""
            UPDATE crm.int_analytics_job_run SET status=?,last_error_code=?,
              consecutive_failures=CASE WHEN ?='PENDING' THEN 0 ELSE consecutive_failures END,
              next_attempt_at=clock_timestamp()+interval '1 second',
              lease_owner=NULL,lease_until=NULL,cluster_slot=NULL,updated_at=clock_timestamp()
            """+FENCE,fenceArgs(c,state,reason==null?null:reason.name(),state))==1;
    }

    // These three methods are called exclusively inside the existing activation
    // transaction (same manager and connection). No nested transaction.
    private static void requireActivationTransaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("INT_JOB_ACTIVATION_TRANSACTION_REQUIRED");
    }
    void lockClaimForActivation(Claim c){
        requireActivationTransaction();
        if(jdbc.query("SELECT job_id FROM crm.int_analytics_job_run "+FENCE+" FOR UPDATE",
            (rs,n)->rs.getObject(1,UUID.class),fenceArgs(c)).size()!=1)
            throw new IllegalStateException("INT_JOB_LEASE_LOST");
    }
    void verifyScheduledScope(Claim c){
        requireActivationTransaction();
        // Workspace UPDATE fence is already held by activateInternal. Lock
        // organization without waiting on a reversed owner lock order.
        var org=jdbc.query("""
            SELECT o.id FROM public.org_organization o
            JOIN public.iam_workspace w ON w.organization_id=o.id
            WHERE w.id=? AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
              AND o.status='ACTIVE' FOR SHARE OF o NOWAIT
            """,(rs,n)->rs.getObject(1,UUID.class),c.tenant());
        if(org.size()!=1||!currentPolicy(c))
            throw new IllegalStateException("INT_JOB_POLICY_CHANGED");
        boolean scope=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM crm.int_projection_generation g
                WHERE g.tenant_id=? AND g.generation_id=? AND g.calendar_version=?
                  AND g.from_day=? AND g.until_day=?)
            """,Boolean.class,c.tenant(),c.generation(),c.calendar(),c.from(),c.until()));
        if(!scope)throw new IllegalStateException("INT_JOB_GENERATION_SCOPE_CONFLICT");
        // Do not shrink a predecessor even if the job was registered by a
        // different internal producer rather than the normal planner.
        if(c.expectedPrevious()!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM crm.int_projection_generation g
              WHERE g.tenant_id=? AND g.generation_id=? AND g.calendar_version=?
                AND g.from_day>=? AND g.until_day<=?)
            """,Boolean.class,c.tenant(),c.expectedPrevious(),c.calendar(),c.from(),c.until())))
            throw new IllegalStateException("INT_JOB_ACTIVE_SCOPE_CONFLICT");
    }
    void completeNight(Claim c,MultidimensionalCoverageService.Activation activation){
        requireActivationTransaction();
        if(activation.replay()||!c.generation().equals(activation.generation()))
            throw new IllegalStateException("INT_JOB_ACTIVATION_IDENTITY");
        record Evidence(OffsetDateTime observed,long revision){}
        var proof=jdbc.query("""
            SELECT observed_at,source_revision FROM crm.int_dim_coverage_proof
            WHERE tenant_id=? AND generation_id=? AND proof_id=? AND lifecycle_revision=1
            """,(rs,n)->new Evidence(rs.getObject(1,OffsetDateTime.class),rs.getLong(2)),
            c.tenant(),c.generation(),activation.activeProof()).stream().findFirst()
            .orElseThrow(()->new IllegalStateException("INT_JOB_ACTIVE_PROOF_MISSING"));
        // The predecessor has already changed in this transaction. Check
        // current owner/calendar again and the new exact pointer, not the old one.
        var args=new ArrayList<Object>(Arrays.asList(fenceArgs(c,activation.activeProof(),
            proof.observed(),proof.revision())));
        args.add(activation.activeProof());args.add(activation.admissionProof());
        if(jdbc.update("""
            UPDATE crm.int_analytics_job_run j SET status='COMPLETED',active_proof_id=?,
              actual_as_of=?,source_revision=?,consecutive_failures=0,last_error_code=NULL,
              lease_owner=NULL,lease_until=NULL,cluster_slot=NULL,updated_at=clock_timestamp()
            """+FENCE+CURRENT_OWNER_POLICY+"""
            AND EXISTS(SELECT 1 FROM crm.int_active_generation a WHERE a.tenant_id=j.tenant_id
              AND a.projection_code='L3' AND a.generation_id=j.generation_id
              AND a.dim_proof_id=? AND a.admission_proof_id=?)
            """,args.toArray())!=1)throw new IllegalStateException("INT_JOB_COMPLETION_FENCE");
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
