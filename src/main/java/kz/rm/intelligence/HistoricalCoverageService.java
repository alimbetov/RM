package kz.rm.intelligence;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** S1.1/2 shadow coverage audit: tenant-wide BOOKED_ORDERS only.
 * Owner identity reconciliation is not authoritative historical completeness.
 * No public proof ingestion, persistence, L3 read, or runtime READY promotion. */
@Service
public class HistoricalCoverageService {
    static final int POLICY_VERSION=1;
    static final int MAX_MONTHS=25;
    public enum Role { TARGET,BASELINE,WARMUP }
    public enum Reason { PRE_ONBOARDING_OR_BOUNDARY,OPEN_PERIOD,OWNER_AUTHORITY_MISSING,
        SOURCE_IDENTITY_MISMATCH,OWNER_POPULATION_INVALID,NO_OBSERVATION,INCOMPATIBLE_PROOF,
        STALE_PROOF,QUALIFIED_VALUE,QUALIFIED_ZERO }
    public enum Eligibility { BLOCKED }
    public record RequiredMonth(YearMonth month,Role role){
        public RequiredMonth {Objects.requireNonNull(month);Objects.requireNonNull(role);}
    }
    public record Manifest(UUID tenant,UUID actor,long calendarVersion,String zoneId,long sourceRevision,
                           Instant asOf,Instant tenantCreatedAt){
        public Manifest {Objects.requireNonNull(tenant);Objects.requireNonNull(actor);Objects.requireNonNull(asOf);
            Objects.requireNonNull(tenantCreatedAt);new PeriodResolver.Policy(ZoneId.of(zoneId),calendarVersion);
            if(sourceRevision<0)throw new IllegalArgumentException("S1_REVISION");}
        public AuthorizedAnalyticalReadService.Metric metric(){return AuthorizedAnalyticalReadService.Metric.BOOKED_ORDERS;}
        public int methodVersion(){return 1;}
        public CanonicalFactKey.Owner owner(){return CanonicalFactKey.Owner.SALES;}
        public CanonicalFactKey.Kind sourceKind(){return CanonicalFactKey.Kind.ORDER_PLACED;}
        public MetricRegistry.Basis timeBasis(){return MetricRegistry.Basis.STATUS_OCCURRED;}
        public String scope(){return "TENANT_SALES_READ";}
        public String unit(){return "COUNT";}
    }
    public record SourceCounts(long ownerEvents,long canonicalFacts,long distinctValidOrders,long invalidOwnerEvents,
                               long missingFacts,long extraFacts){
        public SourceCounts {
            if(ownerEvents<0||canonicalFacts<0||distinctValidOrders<0||invalidOwnerEvents<0||missingFacts<0||extraFacts<0||
                distinctValidOrders>ownerEvents||invalidOwnerEvents>ownerEvents||missingFacts>ownerEvents||extraFacts>canonicalFacts)
                throw new IllegalArgumentException("S1_COUNTS");
        }
        boolean identityParity(){return missingFacts==0&&extraFacts==0&&ownerEvents==canonicalFacts;}
    }
    public record IntervalEvidence(RequiredMonth requirement,PeriodResolver.Period interval,SourceCounts source,
                                   BigDecimal value,DataQuality quality,List<Reason> reasons,String authorityRef){
        public IntervalEvidence {reasons=List.copyOf(reasons);}
    }
    public record RoleCoverage(Role role,int required,int qualified,BigDecimal ratio){}
    public record Assessment(int policyVersion,Manifest manifest,List<IntervalEvidence> intervals,
                             List<RoleCoverage> roles,int required,int qualified,BigDecimal coverageRatio,
                             Eligibility decisionEligibility){
        public Assessment {intervals=List.copyOf(intervals);roles=List.copyOf(roles);}
        public boolean allRequiredQualified(){return required>0&&required==qualified;}
    }
    // Internal qualification contract, not a runtime proof issuer. Only tests currently construct it.
    // An owner-specific authority adapter must be separately admitted before a live producer is added.
    record OwnerProof(Manifest manifest,PeriodResolver.Period interval,Instant sourceFrom,Instant sourceUntil,
                      String authorityRef,long exactCount,boolean qualifiedZero){
        OwnerProof {Objects.requireNonNull(manifest);Objects.requireNonNull(interval);
            Objects.requireNonNull(sourceFrom);Objects.requireNonNull(sourceUntil);
            if(!sourceFrom.isBefore(sourceUntil)||authorityRef==null||authorityRef.isBlank()||
                exactCount<0||(exactCount==0)!=qualifiedZero)throw new IllegalArgumentException("S1_OWNER_PROOF");}
    }
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final TenantAuthorization authorization;
    HistoricalCoverageService(JdbcTemplate jdbc,PlatformTransactionManager manager,TenantAuthorization authorization){
        this.jdbc=jdbc;this.manager=manager;this.authorization=authorization;
    }
    /** Exact requested set, never a dense tenant x SKU matrix or a public report admission. */
    public Assessment inspectBookedOrders(VerifiedActor actor,UUID tenant,long calendarVersion,List<RequiredMonth> months){
        Objects.requireNonNull(actor);Objects.requireNonNull(tenant);
        if(calendarVersion<1)throw new IllegalArgumentException("S1_CALENDAR");
        var required=normalize(months);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("S1_REQUIRES_OWN_TRANSACTION");
        var admission=new TransactionTemplate(manager);
        admission.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);admission.setTimeout(180);
        return admission.execute(outer->{
            UUID user=actor.userId();
            authorization.requireForCommand(user,tenant,DomainPermission.SALES_ORDER_READ);
            var snapshot=new TransactionTemplate(manager);
            snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setReadOnly(true);snapshot.setTimeout(120);
            var result=snapshot.execute(inner->inspectSnapshot(tenant,user,calendarVersion,required));
            actor.userId();
            return result;
        });
    }
    private Assessment inspectSnapshot(UUID tenant,UUID actor,long calendarVersion,List<RequiredMonth> required){
        if(!Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql"))))
            throw new IllegalStateException("S1_POSTGRESQL_REQUIRED");
        var manifest=jdbc.query("""
            SELECT p.zone_id,w.created_at,transaction_timestamp(),
              coalesce((SELECT revision FROM crm.int_source_revision WHERE tenant_id=w.id),0)
               +(SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=w.id)
            FROM public.iam_workspace w JOIN crm.int_calendar_policy p ON p.tenant_id=w.id
            WHERE w.id=? AND p.version=?
            """,rs->rs.next()?new Manifest(tenant,actor,calendarVersion,rs.getString(1),rs.getLong(4),
                rs.getTimestamp(3).toInstant(),rs.getTimestamp(2).toInstant()):null,tenant,calendarVersion);
        if(manifest==null)throw new IllegalArgumentException("CALENDAR_VERSION_NOT_FOUND");
        var counts=required.isEmpty()?Map.<YearMonth,SourceCounts>of():sourceCounts(manifest,required);
        // No admitted owner completeness authority exists in the audited baseline.
        return assess(manifest,required,counts,Map.of());
    }
    /** Independent source identity audit. Not the projection view, not a digest verifier,
     * not a claim of completeness of imports or owner source retention. */
    private Map<YearMonth,SourceCounts> sourceCounts(Manifest m,List<RequiredMonth> required){
        var args=new ArrayList<Object>();
        for(int i=0;i<required.size();i++){
            var p=period(m,required.get(i).month());
            args.add(i);args.add(Timestamp.from(p.startInclusive()));args.add(Timestamp.from(p.endExclusive()));
        }
        args.add(m.tenant());args.add(Timestamp.from(m.asOf()));
        String tuples=String.join(",",Collections.nCopies(required.size(),
            "(CAST(? AS integer),CAST(? AS timestamptz),CAST(? AS timestamptz))"));
        String sql="""
            WITH requested(idx,from_ts,until_ts) AS (VALUES %s),
            p AS (SELECT CAST(? AS uuid) t,CAST(? AS timestamptz) cutoff),
            owners AS (
              SELECT r.idx,e.tenant_id,e.id,e.order_id,e.occurred_at,
                (o.booked_snapshot_sealed AND EXISTS(
                    SELECT 1 FROM crm.sales_order_line l WHERE l.tenant_id=e.tenant_id AND l.order_id=e.order_id)
                 AND NOT EXISTS(SELECT 1 FROM crm.sales_order_status_event duplicate
                    WHERE duplicate.tenant_id=e.tenant_id AND duplicate.order_id=e.order_id
                      AND duplicate.event_type='PLACED' AND duplicate.id<>e.id)) AS valid
              FROM requested r CROSS JOIN p
              JOIN crm.sales_order_status_event e ON e.tenant_id=p.t AND e.event_type='PLACED'
                AND e.occurred_at>=r.from_ts AND e.occurred_at<least(r.until_ts,p.cutoff)
              JOIN crm.sales_order o ON o.tenant_id=e.tenant_id AND o.id=e.order_id
            ), facts AS (
              SELECT r.idx,f.tenant_id,f.source_id,f.subject_id,f.occurred_at,f.recorded_at
              FROM requested r CROSS JOIN p JOIN crm.int_canonical_fact f ON f.tenant_id=p.t
                AND f.owner_module='SALES' AND f.source_kind='ORDER_PLACED'
                AND f.occurred_at>=r.from_ts AND f.occurred_at<least(r.until_ts,p.cutoff)
            ), owner_counts AS (
              SELECT o.idx,count(*) owners,count(DISTINCT o.order_id) FILTER(WHERE o.valid) valid_orders,
                count(*) FILTER(WHERE NOT o.valid) invalid,
                count(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM facts f WHERE f.idx=o.idx
                    AND f.tenant_id=o.tenant_id AND f.source_id=o.id AND f.subject_id=o.order_id
                    AND f.occurred_at=o.occurred_at AND f.recorded_at IS NULL)) missing
              FROM owners o GROUP BY o.idx
            ), fact_counts AS (
              SELECT f.idx,count(*) facts,count(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM owners o
                  WHERE o.idx=f.idx AND o.tenant_id=f.tenant_id AND o.id=f.source_id AND o.order_id=f.subject_id
                    AND o.occurred_at=f.occurred_at AND f.recorded_at IS NULL)) extra
              FROM facts f GROUP BY f.idx
            )
            SELECT r.idx,coalesce(o.owners,0),coalesce(f.facts,0),coalesce(o.valid_orders,0),
              coalesce(o.invalid,0),coalesce(o.missing,0),coalesce(f.extra,0)
            FROM requested r LEFT JOIN owner_counts o USING(idx) LEFT JOIN fact_counts f USING(idx)
            ORDER BY r.idx
            """.formatted(tuples);
        return jdbc.query(sql,rs->{
            var out=new LinkedHashMap<YearMonth,SourceCounts>();
            while(rs.next()){
                int i=rs.getInt(1);
                if(i<0||i>=required.size()||out.put(required.get(i).month(),new SourceCounts(rs.getLong(2),rs.getLong(3),
                    rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7)))!=null)
                    throw new IllegalStateException("S1_SOURCE_CARDINALITY");
            }
            if(out.size()!=required.size())throw new IllegalStateException("S1_SOURCE_CARDINALITY");
            return Map.copyOf(out);
        },args.toArray());
    }
    static List<RequiredMonth> normalize(List<RequiredMonth> input){
        var list=List.copyOf(input);
        if(list.size()>MAX_MONTHS||list.stream().map(RequiredMonth::month).distinct().count()!=list.size())
            throw new IllegalArgumentException("S1_REQUIRED_MONTHS");
        return list.stream().sorted(Comparator.comparing(RequiredMonth::month)).toList();
    }
    private static PeriodResolver.Period period(Manifest m,YearMonth month){
        return PeriodResolver.resolve(PeriodResolver.Code.M,month.atDay(1),
            new PeriodResolver.Policy(ZoneId.of(m.zoneId()),m.calendarVersion()));
    }
    /** Pure exact-set gate. Synthetic authority proofs qualify this contract only. */
    static Assessment assess(Manifest m,List<RequiredMonth> months,Map<YearMonth,SourceCounts> counts,
                             Map<YearMonth,OwnerProof> proofs){
        var required=normalize(months);
        var keys=new HashSet<>(required.stream().map(RequiredMonth::month).toList());
        if(!counts.keySet().equals(keys)||!keys.containsAll(proofs.keySet()))
            throw new IllegalArgumentException("S1_EVIDENCE_SET_MISMATCH");
        var out=new ArrayList<IntervalEvidence>();
        for(var r:required)out.add(evaluate(m,r,Objects.requireNonNull(counts.get(r.month())),proofs.get(r.month())));
        int qualified=(int)out.stream().filter(x->x.quality()==DataQuality.READY).count();
        var roles=Arrays.stream(Role.values()).map(role->{
            int n=(int)out.stream().filter(x->x.requirement().role()==role).count();
            int ready=(int)out.stream().filter(x->x.requirement().role()==role&&x.quality()==DataQuality.READY).count();
            return new RoleCoverage(role,n,ready,ratio(ready,n));
        }).toList();
        return new Assessment(POLICY_VERSION,m,out,roles,out.size(),qualified,ratio(qualified,out.size()),Eligibility.BLOCKED);
    }
    private static BigDecimal ratio(int qualified,int required){
        return required==0?null:BigDecimal.valueOf(qualified).divide(BigDecimal.valueOf(required),MathContext.DECIMAL128);
    }
    private static IntervalEvidence evaluate(Manifest m,RequiredMonth r,SourceCounts c,OwnerProof proof){
        var p=period(m,r.month());var reasons=new ArrayList<Reason>();
        if(p.startInclusive().isBefore(m.tenantCreatedAt()))reasons.add(Reason.PRE_ONBOARDING_OR_BOUNDARY);
        if(p.endExclusive().isAfter(m.asOf()))reasons.add(Reason.OPEN_PERIOD);
        if(!c.identityParity())reasons.add(Reason.SOURCE_IDENTITY_MISMATCH);
        if(c.invalidOwnerEvents()>0||c.distinctValidOrders()!=c.ownerEvents())reasons.add(Reason.OWNER_POPULATION_INVALID);
        if(proof==null)reasons.add(Reason.OWNER_AUTHORITY_MISSING);
        else {
            var pm=proof.manifest();
            if(!m.tenant().equals(pm.tenant())||!m.actor().equals(pm.actor())||m.calendarVersion()!=pm.calendarVersion()||
                !m.zoneId().equals(pm.zoneId())||!m.tenantCreatedAt().equals(pm.tenantCreatedAt())||!p.equals(proof.interval())||
                proof.sourceFrom().isAfter(p.startInclusive())||proof.sourceUntil().isBefore(p.endExclusive())||
                proof.exactCount()!=c.distinctValidOrders())reasons.add(Reason.INCOMPATIBLE_PROOF);
            else if(m.sourceRevision()!=pm.sourceRevision()||!m.asOf().equals(pm.asOf()))reasons.add(Reason.STALE_PROOF);
        }
        if(reasons.isEmpty())return new IntervalEvidence(r,p,c,BigDecimal.valueOf(proof.exactCount()),DataQuality.READY,
            List.of(proof.qualifiedZero()?Reason.QUALIFIED_ZERO:Reason.QUALIFIED_VALUE),proof.authorityRef());
        if(c.ownerEvents()==0)reasons.add(Reason.NO_OBSERVATION);
        boolean conflict=reasons.contains(Reason.INCOMPATIBLE_PROOF)||reasons.contains(Reason.STALE_PROOF);
        // Do not expose a misleading numerical zero or an inconsistent owner count.
        BigDecimal observed=!conflict&&c.identityParity()&&c.invalidOwnerEvents()==0&&c.distinctValidOrders()>0
            &&c.distinctValidOrders()==c.ownerEvents()?BigDecimal.valueOf(c.distinctValidOrders()):null;
        return new IntervalEvidence(r,p,c,observed,conflict?DataQuality.STALE:
            observed==null?DataQuality.MISSING:DataQuality.PARTIAL,reasons,null);
    }
}
