package kz.rm.intelligence;

import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedServiceActor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One typed outcome per existing durable job. No report/forecast publication. */
@Service
class IntradayResultStore {
    record Scope(UUID tenant,UUID account,long version,LocalDate expires,int maxRows){
        Scope{
            Objects.requireNonNull(tenant);Objects.requireNonNull(account);Objects.requireNonNull(expires);
            if(version<1||maxRows<1||maxRows>217)throw new IllegalArgumentException("INT_INTRADAY_SCOPE");
        }
        String digest(){return hash("1|"+tenant+"|"+account+"|"+version+"|"+expires+"|"+maxRows+"|BOOKED_ORDERS|1|INTRADAY_SNAPSHOT_STORE_V1");}
    }
    record Request(UUID tenant,UUID job,UUID account,long scopeVersion,String scopeDigest,long calendar,
                   String zone,LocalDate day,Instant slot,Instant start,Instant end){}
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final AnalyticalJobRepository jobs;
    private final TenantAuthorization authorization;
    private final AuthorizedAnalyticalReadService read;
    IntradayResultStore(JdbcTemplate jdbc,PlatformTransactionManager manager,AnalyticalJobRepository jobs,
                        TenantAuthorization authorization,AuthorizedAnalyticalReadService read){
        this.jdbc=jdbc;this.manager=manager;this.jobs=jobs;this.authorization=authorization;this.read=read;
    }
    private <T>T tx(int timeout,java.util.function.Supplier<T> action){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("INT_INTRADAY_OWN_TX");
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(timeout);
        return Objects.requireNonNull(tx.execute(status->{
            jdbc.execute("SET LOCAL lock_timeout='2s'");
            return action.get();
        }));
    }
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();}
    private static OffsetDateTime utc(Instant i){return i.atOffset(ZoneOffset.UTC);}
    private record Calendar(long version,String zone){}
    private Calendar calendar(UUID tenant,Instant now){
        return jdbc.query("""
            SELECT version,zone_id FROM crm.int_calendar_policy WHERE tenant_id=? AND effective_from<=?
            ORDER BY version DESC LIMIT 1
            """,rs->rs.next()?new Calendar(rs.getLong(1),rs.getString(2)):null,tenant,utc(now));
    }
    private static PeriodResolver.Policy policy(Calendar c){return new PeriodResolver.Policy(ZoneId.of(c.zone()),c.version());}
    Optional<UUID> plan(Scope scope){
        var registered=tx(10,()->{
            jdbc.execute("SET LOCAL statement_timeout='8s'");
            UUID tenant=scope.tenant();
            var lock=jdbc.query("""
                SELECT w.id FROM public.iam_workspace w JOIN public.org_organization o ON o.id=w.organization_id
                WHERE w.id=? AND w.kind='ORGANIZATION' AND w.status='ACTIVE' AND o.status='ACTIVE'
                FOR UPDATE OF w NOWAIT
                """,(rs,n)->rs.getObject(1,UUID.class),tenant);
            if(lock.isEmpty())return Optional.<Request>empty();
            Instant at=now();var cal=calendar(tenant,at);
            if(cal==null)return Optional.<Request>empty();
            var due=AnalyticalJobService.latestIntraday(at,policy(cal));
            if(due.isEmpty()||due.get().day().isAfter(scope.expires()))return Optional.<Request>empty();
            var d=due.get();var start=d.period().startInclusive();var end=d.period().endExclusive();
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM crm.int_intraday_result r JOIN crm.int_analytics_job_run j USING(tenant_id,job_id)
                WHERE r.tenant_id=? AND (j.business_day=? OR (r.day_start<? AND r.day_end>?))
                  AND (j.calendar_version<>? OR r.scope_digest<>? OR r.day_start<>? OR r.day_end<>?))
                """,Boolean.class,tenant,d.day(),utc(end),utc(start),cal.version(),scope.digest(),utc(start),utc(end))))
                return Optional.<Request>empty();
            var existing=findSlot(tenant,d.slot());
            if(existing!=null)return compatible(existing,scope,cal,d)?Optional.of(existing):Optional.<Request>empty();
            long count=jdbc.queryForObject("SELECT count(*) FROM crm.int_intraday_result WHERE tenant_id=?",Long.class,tenant);
            if(count>=scope.maxRows())return Optional.<Request>empty();
            UUID job=UUID.randomUUID();
            int n=jdbc.update("""
                INSERT INTO crm.int_analytics_job_run(tenant_id,job_id,job_type,scheduled_slot,scheduled_at,
                  business_day,calendar_version,zone_id,status)
                VALUES (?,?,'INTRADAY',?,?,?,?,?,'PENDING') ON CONFLICT DO NOTHING
                """,tenant,job,utc(d.slot()),utc(d.slot()),d.day(),cal.version(),cal.zone());
            if(n!=1)return Optional.<Request>empty();
            if(!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT crm.int_intraday_register(?,?,?,?,?,?,?)
                """,Boolean.class,tenant,job,scope.account(),scope.version(),scope.digest(),utc(start),utc(end))))
                throw new IllegalStateException("INT_INTRADAY_REGISTER");
            return Optional.of(new Request(tenant,job,scope.account(),scope.version(),scope.digest(),cal.version(),cal.zone(),d.day(),d.slot(),start,end));
        });
        // Separate job-first reconciliation. Never take a job row lock while
        // retaining the planning workspace lock. Live leases are untouched.
        registered.ifPresent(r->tx(10,()->{
            jdbc.update("""
                WITH old AS (SELECT j.tenant_id,j.job_id FROM crm.int_analytics_job_run j
                  JOIN crm.int_intraday_result r USING(tenant_id,job_id)
                  WHERE j.tenant_id=? AND j.job_type='INTRADAY' AND j.status='PENDING'
                    AND j.scheduled_slot<? AND r.acknowledged_at IS NULL
                  ORDER BY j.scheduled_slot LIMIT 7 FOR UPDATE OF j SKIP LOCKED)
                UPDATE crm.int_analytics_job_run j SET status='SKIPPED',last_error_code='OBSOLETE_SLOT',updated_at=clock_timestamp()
                FROM old WHERE j.tenant_id=old.tenant_id AND j.job_id=old.job_id AND j.status='PENDING'
                """,r.tenant(),utc(r.slot()));return true;
        }));
        return registered.map(Request::job);
    }
    private static final String REQUEST_SQL="""
        SELECT j.tenant_id,j.job_id,r.service_account_id,r.scope_version,r.scope_digest,j.calendar_version,
          j.zone_id,j.business_day,j.scheduled_slot,r.day_start,r.day_end
        FROM crm.int_analytics_job_run j JOIN crm.int_intraday_result r USING(tenant_id,job_id)
        WHERE j.job_type='INTRADAY' AND j.tenant_id=?
        """;
    private static Request request(java.sql.ResultSet rs)throws java.sql.SQLException{
        return new Request(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
            rs.getLong(4),rs.getString(5),rs.getLong(6),rs.getString(7),rs.getObject(8,LocalDate.class),
            rs.getObject(9,OffsetDateTime.class).toInstant(),rs.getObject(10,OffsetDateTime.class).toInstant(),
            rs.getObject(11,OffsetDateTime.class).toInstant());
    }
    private Request findSlot(UUID tenant,Instant slot){
        return jdbc.query(REQUEST_SQL+" AND j.scheduled_slot=?",rs->rs.next()?request(rs):null,tenant,utc(slot));
    }
    private static boolean compatible(Request r,Scope s,Calendar c,AnalyticalJobService.IntradayDue d){
        return r.tenant().equals(s.tenant())&&r.account().equals(s.account())&&r.scopeVersion()==s.version()
            &&r.scopeDigest().equals(s.digest())&&r.calendar()==c.version()&&r.zone().equals(c.zone())
            &&r.day().equals(d.day())&&r.slot().equals(d.slot())
            &&r.start().equals(d.period().startInclusive())&&r.end().equals(d.period().endExclusive());
    }
    AnalyticalJobService.RunResult execute(AnalyticalJobRepository.Claim claim,Scope scope,VerifiedServiceActor actor){
        if(!"INTRADAY".equals(claim.type())||!claim.tenant().equals(scope.tenant())||!scope.account().equals(actor.accountId()))
            throw new SecurityException("INT_INTRADAY_SCOPE_DENIED");
        return tx(180,()->{
            jobs.lockClaimForActivation(claim);
            authorization.requireIntradayRead(actor,scope.tenant());
            var org=jdbc.query("""
                SELECT o.id FROM public.org_organization o JOIN public.iam_workspace w ON w.organization_id=o.id
                WHERE w.id=? AND o.status='ACTIVE' FOR SHARE OF o NOWAIT
                """,(rs,n)->rs.getObject(1,UUID.class),scope.tenant());
            if(org.isEmpty())throw new SecurityException("INT_INTRADAY_ORG_DENIED");
            var r=jdbc.query(REQUEST_SQL+" AND j.job_id=?",rs->rs.next()?request(rs):null,claim.tenant(),claim.job());
            if(r==null||!r.account().equals(scope.account())||r.scopeVersion()!=scope.version()||!r.scopeDigest().equals(scope.digest()))
                throw new SecurityException("INT_INTRADAY_REQUEST_DENIED");
            Instant at=now();var cal=calendar(r.tenant(),at);
            if(cal==null||cal.version()!=r.calendar()||!cal.zone().equals(r.zone()))
                throw new SecurityException("INT_INTRADAY_CALENDAR_CHANGED");
            var due=AnalyticalJobService.latestIntraday(at,policy(cal));
            if(due.isEmpty()||!due.get().slot().equals(r.slot())||due.get().day().isAfter(scope.expires())){
                jobs.skipIntraday(claim);return AnalyticalJobService.RunResult.SKIPPED;
            }
            if(!compatible(r,scope,cal,due.get()))throw new SecurityException("INT_INTRADAY_SCOPE_CHANGED");
            var observation=read.readIntraday(r.tenant(),r.calendar(),r.day());var result=observation.result();
            Instant ack=now();
            if(!ack.isBefore(due.get().deadline())){jobs.skipIntraday(claim);return AnalyticalJobService.RunResult.SKIPPED;}
            actor.accountId();
            if(!ack.isBefore(actor.expiresAt()))throw new SecurityException("INT_SERVICE_EXPIRED");
            if(result.route()!=AuthorizedAnalyticalReadService.Route.L0||result.generation()!=null||result.proof()!=null||
               result.observedAt().isBefore(r.slot())||observation.readObservedAt().isBefore(result.observedAt())||
               ack.isBefore(observation.readObservedAt())||
               !r.start().equals(result.period().startInclusive())||!r.end().equals(result.period().endExclusive()))
                throw new IllegalStateException("INT_INTRADAY_EVIDENCE");
            Long value=result.value()==null?null:result.value().longValueExact();
            if(value==null?result.quality()!=DataQuality.MISSING:result.quality()!=DataQuality.PARTIAL||value<=0)
                throw new IllegalStateException("INT_INTRADAY_QUALITY");
            String digest=resultDigest(r,result.observedAt(),observation.readObservedAt(),result.sourceRevision(),value,result.quality().name(),result.qualityReason(),ack);
            if(!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT crm.int_intraday_ack(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,Boolean.class,r.tenant(),r.job(),claim.worker(),claim.token(),claim.slot(),scope.account(),scope.digest(),
                utc(result.observedAt()),utc(observation.readObservedAt()),result.sourceRevision(),value,result.quality().name(),
                result.qualityReason(),digest,utc(ack),utc(due.get().deadline()))))
                throw new IllegalStateException("INT_INTRADAY_ACK_FAILED");
            return AnalyticalJobService.RunResult.COMPLETED;
        });
    }
    // Fixed-order, unambiguous ASCII fields (all variable text comes from
    // constrained enums/IANA zone or hex); pipe separator forbidden in fields.
    static String resultDigest(Request r,Instant cutoff,Instant observed,long revision,Long value,String quality,String reason,Instant ack){
        return hash(String.join("|","1","INTRADAY_SNAPSHOT_STORE_V1",r.tenant().toString(),r.job().toString(),
            r.account().toString(),Long.toString(r.scopeVersion()),r.scopeDigest(),Long.toString(r.calendar()),r.zone(),
            r.day().toString(),r.slot().toString(),r.start().toString(),r.end().toString(),"BOOKED_ORDERS","1","COUNT","L0",
            "null","null","null","null","null","null","null",cutoff.toString(),observed.toString(),Long.toString(revision),
            value==null?"null":value.toString(),quality,reason,ack.toString()));
    }
    static String hash(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
