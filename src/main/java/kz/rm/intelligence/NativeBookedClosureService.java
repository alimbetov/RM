package kz.rm.intelligence;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Native BOOKED_ORDERS snapshot candidate, not durable closure or READY.
 * Short IAM fences surround a single read-only RR scan; no owner lock survives
 * into the scan. A late commit is visible only in a subsequent snapshot.
 * Internal diagnostic API only; no transport, scheduler or publication admission. */
@Service
public class NativeBookedClosureService {
    private static final int LIMIT=CanonicalFactDeliveryService.MAX_BATCH;
    private static final int VERSION=1;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final TenantAuthorization authorization;
    // Conservative bounded per-pod diagnostic workload; no second connection held
    // per inspection, no waiting queue or pool acquisition on a rejected call.
    private final Semaphore admission=new Semaphore(1);
    public enum State { CAPTURE_INACTIVE, BEFORE_BOUNDARY, OPEN_RANGE, GUARDS_UNAVAILABLE,
        BUDGET_EXHAUSTED, SOURCE_MISMATCH, DIGEST_MISMATCH, SNAPSHOT_VALUE, SNAPSHOT_ZERO }
    public record Revision(long baseline,long events,long scopedCheckoutReceipts){
        public Revision {if(baseline<0||events<0||scopedCheckoutReceipts<0)throw new IllegalArgumentException("NATIVE_REVISION");}
    }
    public record Population(long headers,long events,long facts,long receipts,long invalidHeaders,
                             long orphanEvents,long orphanFacts){
        public Population {
            if(headers<0||events<0||facts<0||receipts<0||invalidHeaders<0||orphanEvents<0||orphanFacts<0)
                throw new IllegalArgumentException("NATIVE_POPULATION");
        }
        boolean overBudget(){return headers>LIMIT||events>LIMIT||facts>LIMIT;}
        boolean exact(){return headers==events&&events==facts&&headers==receipts&&
            invalidHeaders==0&&orphanEvents==0&&orphanFacts==0;}
    }
    public record Evidence(int policyVersion,UUID tenant,Instant from,Instant until,Instant observedAt,
                           NativeBookedCaptureService.Boundary boundary,Revision revision,State state,
                           Population population,String populationDigest,Long shadowValue){
        public boolean admitted(){return false;}
        /** Equality of independently re-observed evidence, not an authorization token.
         * A later commit may invalidate it again immediately. */
        public boolean sameEvidence(Evidence current){
            return current!=null&&current.observedAt()!=null&&!current.observedAt().isBefore(observedAt)
                &&policyVersion==current.policyVersion&&tenant.equals(current.tenant)
                &&from.equals(current.from)&&until.equals(current.until)&&Objects.equals(boundary,current.boundary)
                &&revision.equals(current.revision)&&state==current.state&&Objects.equals(population,current.population)
                &&Objects.equals(populationDigest,current.populationDigest)&&Objects.equals(shadowValue,current.shadowValue);
        }
    }
    /** Explicit calendar interpretation, never an implicit JVM/default zone. */
    public record CalendarBinding(UUID tenant,long version,String zoneId,Instant effectiveFrom,YearMonth month){
        public CalendarBinding{
            Objects.requireNonNull(tenant);Objects.requireNonNull(effectiveFrom);Objects.requireNonNull(month);
            new PeriodResolver.Policy(ZoneId.of(zoneId),version);
        }
        public PeriodResolver.Period period(){
            return PeriodResolver.resolve(PeriodResolver.Code.M,month.atDay(1),
                new PeriodResolver.Policy(ZoneId.of(zoneId),version));
        }
    }
    /** A verified monthly population at one revision is not a durable historical
     * authority. In particular a snapshot zero is never published as historical zero. */
    public static final class MonthlyEvidence {
        private final CalendarBinding calendar;
        private final Evidence source;
        private MonthlyEvidence(CalendarBinding calendar,Evidence source){
            this.calendar=Objects.requireNonNull(calendar);this.source=Objects.requireNonNull(source);
            var period=calendar.period();
            if(!calendar.tenant().equals(source.tenant())||!period.startInclusive().equals(source.from())
                ||!period.endExclusive().equals(source.until())||calendar.effectiveFrom().isAfter(source.observedAt())
                ||source.policyVersion()!=VERSION||source.revision()==null||source.state()==null)
                throw new IllegalArgumentException("NATIVE_MONTH_EVIDENCE_SCOPE");
            if(snapshotQualified()){
                var p=source.population();var boundary=source.boundary();
                if(boundary==null||!calendar.tenant().equals(boundary.tenant())||boundary.policyVersion()!=1
                    ||boundary.startedAt()==null||source.from().isBefore(boundary.startedAt())
                    ||source.until().isAfter(source.observedAt())||p==null||p.overBudget()||!p.exact()
                    ||source.revision().scopedCheckoutReceipts()!=p.receipts()
                    ||source.shadowValue()==null||source.shadowValue()!=p.headers()
                    ||(source.state()==State.SNAPSHOT_ZERO)!=(p.headers()==0)
                    ||source.populationDigest()==null||!source.populationDigest().matches("[a-f0-9]{64}"))
                    throw new IllegalArgumentException("NATIVE_MONTH_SNAPSHOT_INTEGRITY");
            }else if(source.shadowValue()!=null)throw new IllegalArgumentException("NATIVE_MONTH_UNQUALIFIED_VALUE");
        }
        public CalendarBinding calendar(){return calendar;}
        public Evidence source(){return source;}
        public String metric(){return "BOOKED_ORDERS";}
        public int methodVersion(){return 1;}
        public boolean snapshotQualified(){return source.state()==State.SNAPSHOT_VALUE||source.state()==State.SNAPSHOT_ZERO;}
        public Long snapshotValue(){return snapshotQualified()?source.shadowValue():null;}
        public boolean qualifiedSnapshotZero(){return source.state()==State.SNAPSHOT_ZERO&&snapshotQualified();}
        public boolean admitted(){return false;}
        public DataQuality historicalQuality(){
            return source.state()==State.SNAPSHOT_VALUE?DataQuality.PARTIAL:DataQuality.MISSING;
        }
        public Long historicalValue(){return historicalQuality()==DataQuality.PARTIAL?snapshotValue():null;}
        public String historicalReason(){
            return snapshotQualified()?"HISTORICAL_CONTINUITY_UNQUALIFIED":source.state().name();
        }
        public boolean sameEvidence(MonthlyEvidence current){
            return current!=null&&calendar.equals(current.calendar)&&source.sameEvidence(current.source);
        }
    }
    static MonthlyEvidence bindMonth(CalendarBinding calendar,Evidence source){
        return new MonthlyEvidence(calendar,source);
    }
    NativeBookedClosureService(JdbcTemplate jdbc,PlatformTransactionManager manager,TenantAuthorization authorization){
        this.jdbc=jdbc;this.manager=manager;this.authorization=authorization;
    }
    public Evidence inspect(VerifiedActor actor,UUID tenant,Instant from,Instant until){
        validateRange(from,until);
        return inspectSnapshot(actor,tenant,(t,asOf)->scan(t,from,until,asOf));
    }
    /** Resolve the tenant's actual persisted calendar and scan in the SAME RR snapshot. */
    public MonthlyEvidence inspectMonth(VerifiedActor actor,UUID tenant,long calendarVersion,YearMonth month){
        if(calendarVersion<1||month==null)throw new IllegalArgumentException("NATIVE_MONTH_REQUEST");
        return inspectSnapshot(actor,tenant,(t,asOf)->monthInSnapshot(t,calendarVersion,month,asOf));
    }
    /** Internal only: called within this service's already authorized RR inspection. */
    MonthlyEvidence monthInSnapshot(UUID t,long calendarVersion,YearMonth month,Instant asOf){
            var calendar=jdbc.query("""
                SELECT zone_id,effective_from FROM crm.int_calendar_policy WHERE tenant_id=? AND version=?
                """,rs->rs.next()?new CalendarBinding(t,calendarVersion,rs.getString(1),
                    rs.getTimestamp(2).toInstant(),month):null,t,calendarVersion);
            if(calendar==null)throw new IllegalArgumentException("CALENDAR_VERSION_NOT_FOUND");
            if(calendar.effectiveFrom().isAfter(asOf))throw new IllegalStateException("NATIVE_CALENDAR_NOT_EFFECTIVE");
            var p=calendar.period();
            validateRange(p.startInclusive(),p.endExclusive());
            return bindMonth(calendar,scan(t,p.startInclusive(),p.endExclusive(),asOf));
    }
    private static void validateRange(Instant from,Instant until){
        Objects.requireNonNull(from);Objects.requireNonNull(until);
        if(!from.isBefore(until)||Duration.between(from,until).compareTo(Duration.ofDays(32))>0||
            from.getNano()%1000!=0||until.getNano()%1000!=0)
            throw new IllegalArgumentException("NATIVE_CLOSURE_RANGE");
    }
    private <T> T inspectSnapshot(VerifiedActor actor,UUID tenant,java.util.function.BiFunction<UUID,Instant,T> read){
        return inspectSnapshot(actor,tenant,false,read,result->{});
    }
    /** Shared one-per-instance budget and short authorization fences for report diagnostics.
     * The post-check runs inside the final short RC fence, never in the long RR read. */
    <T> T inspectReportSnapshot(VerifiedActor actor,UUID tenant,java.util.function.BiFunction<UUID,Instant,T> read,
                               java.util.function.Consumer<T> postCheck){
        return inspectSnapshot(actor,tenant,true,read,postCheck);
    }
    private <T> T inspectSnapshot(VerifiedActor actor,UUID tenant,boolean report,
                                  java.util.function.BiFunction<UUID,Instant,T> read,java.util.function.Consumer<T> postCheck){
        Objects.requireNonNull(actor);Objects.requireNonNull(tenant);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("NATIVE_CLOSURE_OWN_TRANSACTION");
        if(!admission.tryAcquire())throw new IllegalStateException("NATIVE_CLOSURE_BUSY");
        try{
            admit(actor,tenant,report,()->{});
            var tx=new TransactionTemplate(manager);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            tx.setReadOnly(true);tx.setTimeout(30);
            var result=Objects.requireNonNull(tx.execute(st->{
                if(!Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c->
                    c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql"))))
                    throw new IllegalStateException("NATIVE_CLOSURE_POSTGRESQL_REQUIRED");
                // Establish MVCC snapshot before any subsequent query; no FOR UPDATE/SHARE.
                Instant asOf=Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT transaction_timestamp() FROM (SELECT pg_current_snapshot()) s",Timestamp.class)).toInstant();
                // Snapshot-local authorization as well as fresh pre/post fences.
                authorization.require(actor.userId(),tenant,DomainPermission.SALES_ORDER_READ);
                if(report)authorization.require(actor.userId(),tenant,DomainPermission.REPORT_GENERATE);
                return read.apply(tenant,asOf);
            }));
            admit(actor,tenant,report,()->postCheck.accept(result));
            return result;
        }finally{admission.release();}
    }
    private void admit(VerifiedActor actor,UUID tenant,boolean report,Runnable postCheck){
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
        tx.executeWithoutResult(st->{
            var decision=authorization.requireForCommand(actor.userId(),tenant,DomainPermission.SALES_ORDER_READ);
            if(!decision.validForCurrentTransaction()||!tenant.equals(decision.tenantId()))
                throw new SecurityException("NATIVE_CLOSURE_ADMISSION");
            if(report)authorization.requireForCommand(actor.userId(),tenant,DomainPermission.REPORT_GENERATE);
            postCheck.run();
            actor.userId();
        });
    }
    private Evidence scan(UUID tenant,Instant from,Instant until,Instant asOf){
        var boundary=NativeBookedCaptureService.read(jdbc,tenant);
        var source=Objects.requireNonNull(jdbc.queryForObject("""
            SELECT coalesce((SELECT revision FROM crm.int_source_revision WHERE tenant_id=?),0),
                   (SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=?)
            """,(rs,n)->new Revision(rs.getLong(1),rs.getLong(2),0),tenant,tenant));
        State state=null;
        if(boundary==null||boundary.startedAt()==null||boundary.policyVersion()!=1)state=State.CAPTURE_INACTIVE;
        else if(from.isBefore(boundary.startedAt()))state=State.BEFORE_BOUNDARY;
        else if(until.isAfter(asOf))state=State.OPEN_RANGE;
        else if(!guards())state=State.GUARDS_UNAVAILABLE;
        if(state!=null)return new Evidence(VERSION,tenant,from,until,asOf,boundary,source,state,null,null,null);
        var p=population(tenant,from,until);
        // Checkout receipt insertion is not part of the existing source journal.
        // Active capture makes updates/deletes impossible; bind scoped cardinality
        // separately so a new duplicate receipt cannot reuse a prior candidate.
        var revision=new Revision(source.baseline(),source.events(),p.receipts());
        if(p.overBudget())state=State.BUDGET_EXHAUSTED;
        else if(!p.exact())state=State.SOURCE_MISMATCH;
        if(state!=null)return new Evidence(VERSION,tenant,from,until,asOf,boundary,revision,state,p,null,null);
        var digests=new IndependentCanonicalDigestVerifier(jdbc).bookedSlice(tenant,from,until,LIMIT);
        if(digests.budgetExceeded())state=State.BUDGET_EXHAUSTED;
        else if(digests.checked()!=p.facts()||digests.legacy()!=0||digests.mismatches()!=0)state=State.DIGEST_MISMATCH;
        if(state!=null)return new Evidence(VERSION,tenant,from,until,asOf,boundary,revision,state,p,null,null);
        return new Evidence(VERSION,tenant,from,until,asOf,boundary,revision,
            p.headers()==0?State.SNAPSHOT_ZERO:State.SNAPSHOT_VALUE,p,digests.populationDigest(),p.headers());
    }
    private boolean guards(){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT count(*)=27 AND current_setting('session_replication_role')='origin' FROM pg_catalog.pg_trigger t
            WHERE NOT t.tgisinternal AND t.tgenabled IN ('O','A') AND (
              (t.tgrelid='public.iam_workspace'::regclass AND t.tgname='trg_int_native_capture_init') OR
              (t.tgrelid='crm.int_native_booked_capture'::regclass AND t.tgname IN ('trg_int_native_capture_transition','trg_int_native_capture_no_truncate')) OR
              (t.tgrelid='crm.sales_order'::regclass AND t.tgname IN ('trg_int_native_order_fence','trg_int_native_header_guard',
                'trg_int_native_order_complete','trg_int_native_header_no_truncate','trg_sales_booked_header_immutable','trg_sales_snapshot_no_unseal')) OR
              (t.tgrelid='crm.sales_order_status_event'::regclass AND t.tgname IN ('trg_int_native_event_fence',
                'trg_sales_status_event_immutable','trg_sales_status_event_no_truncate','trg_sales_snapshot_seal','trg_int_source_rev_0')) OR
              (t.tgrelid='crm.sales_checkout_receipt'::regclass AND t.tgname IN ('trg_int_native_checkout_receipt_guard','trg_int_native_checkout_receipt_no_truncate')) OR
              (t.tgrelid='crm.sales_order_line'::regclass AND t.tgname IN ('trg_sales_line_immutable','trg_sales_line_no_truncate',
                'trg_sales_line_no_late_insert','trg_int_source_rev_1')) OR
              (t.tgrelid='crm.int_canonical_fact'::regclass AND t.tgname IN ('trg_int_fact_immutable','trg_int_fact_no_truncate','trg_int_source_rev_3')) OR
              (t.tgrelid='crm.int_source_revision'::regclass AND t.tgname IN ('trg_int_source_rev_baseline_immutable','trg_int_source_rev_baseline_no_truncate')) OR
              (t.tgrelid='crm.int_source_revision_event'::regclass AND t.tgname IN ('trg_int_source_rev_event_immutable','trg_int_source_rev_event_no_truncate')))
            """,Boolean.class));
    }
    private Population population(UUID tenant,Instant from,Instant until){
        return Objects.requireNonNull(jdbc.queryForObject("""
            WITH p AS (SELECT CAST(? AS uuid) t,CAST(? AS timestamptz) a,CAST(? AS timestamptz) b),
            h AS MATERIALIZED (SELECT o.tenant_id,o.id,o.created_at,o.booked_snapshot_sealed FROM crm.sales_order o,p
              WHERE o.tenant_id=p.t AND o.created_at>=p.a AND o.created_at<p.b LIMIT 501),
            e AS MATERIALIZED (SELECT e.tenant_id,e.id,e.order_id,e.occurred_at FROM crm.sales_order_status_event e,p
              WHERE e.tenant_id=p.t AND e.event_type='PLACED' AND e.occurred_at>=p.a AND e.occurred_at<p.b LIMIT 501),
            f AS MATERIALIZED (SELECT f.tenant_id,f.source_id,f.subject_id,f.occurred_at,f.recorded_at,f.source_version,f.schema_version
              FROM crm.int_canonical_fact f,p WHERE f.tenant_id=p.t AND f.owner_module='SALES'
              AND f.source_kind='ORDER_PLACED' AND f.occurred_at>=p.a AND f.occurred_at<p.b LIMIT 501),
            r AS MATERIALIZED (SELECT h.id,count(r.order_id) n,
                count(r.order_id) FILTER(WHERE r.created_at=h.created_at) matching
              FROM h LEFT JOIN crm.sales_checkout_receipt r ON r.tenant_id=h.tenant_id AND r.order_id=h.id GROUP BY h.id)
            SELECT (SELECT count(*) FROM h),(SELECT count(*) FROM e),(SELECT count(*) FROM f),
              (SELECT coalesce(sum(n),0) FROM r),
              (SELECT count(*) FROM h JOIN r USING(id) WHERE NOT h.booked_snapshot_sealed OR r.n<>1 OR r.matching<>1
                OR NOT EXISTS(SELECT 1 FROM crm.sales_order_line l WHERE l.tenant_id=h.tenant_id AND l.order_id=h.id)
                OR (SELECT count(*) FROM crm.sales_order_status_event x WHERE x.tenant_id=h.tenant_id AND x.order_id=h.id AND x.event_type='PLACED')<>1
                OR NOT EXISTS(SELECT 1 FROM e WHERE e.tenant_id=h.tenant_id AND e.order_id=h.id AND e.occurred_at=h.created_at)),
              (SELECT count(*) FROM e WHERE NOT EXISTS(SELECT 1 FROM h WHERE h.tenant_id=e.tenant_id AND h.id=e.order_id AND h.created_at=e.occurred_at)
                OR NOT EXISTS(SELECT 1 FROM f WHERE f.tenant_id=e.tenant_id AND f.source_id=e.id AND f.subject_id=e.order_id
                  AND f.occurred_at=e.occurred_at AND f.recorded_at IS NULL AND f.source_version IS NULL AND f.schema_version=2)),
              (SELECT count(*) FROM f WHERE NOT EXISTS(SELECT 1 FROM e WHERE e.tenant_id=f.tenant_id AND e.id=f.source_id
                  AND e.order_id=f.subject_id AND e.occurred_at=f.occurred_at))
            """,(rs,n)->new Population(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7)),
            tenant,Timestamp.from(from),Timestamp.from(until)));
    }
}
