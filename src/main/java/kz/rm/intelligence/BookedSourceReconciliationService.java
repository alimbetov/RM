package kz.rm.intelligence;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** S1.2B diagnostic only. A reconciled backfill is NOT an owner completeness authority. */
@Service
public class BookedSourceReconciliationService {
    static final int VERSION=2;
    public enum State { RECONCILED_PRESENT_SOURCE,INCOMPLETE,CONFLICT,BUDGET_EXHAUSTED }
    public enum Limitation { OWNER_AUTHORITY_MISSING,OPEN_RANGE,IDENTITY_MISMATCH,MISSING_RECEIPTS,
        REJECTED_RECEIPTS,RECEIPT_MISMATCH,DIGEST_MISMATCH,LEGACY_DIGEST,DIGEST_BUDGET_EXHAUSTED,OWNER_POPULATION_INVALID }
    public record Counts(long owners,long facts,long missing,long extra,long unprocessed,long rejected,
                         long receiptMismatches,long orphanReceipts,long distinctValidOrders,long invalidOwnerEvents){
        public Counts {if(owners<0||facts<0||missing<0||extra<0||unprocessed<0||rejected<0||
            receiptMismatches<0||orphanReceipts<0||distinctValidOrders<0||invalidOwnerEvents<0||
            missing>owners||unprocessed>owners||extra>facts||invalidOwnerEvents>owners||
            distinctValidOrders>owners-invalidOwnerEvents)throw new IllegalArgumentException("S1_RECONCILIATION_COUNTS");}
    }
    public record Digest(long checked,long mismatches,long legacy,boolean budgetExceeded,String populationDigest){
        public Digest {if(checked<0||mismatches<0||legacy<0||mismatches>checked||legacy>checked||
            (budgetExceeded?(checked!=0||populationDigest!=null):
                (populationDigest==null||!populationDigest.matches("[0-9a-f]{64}"))))
            throw new IllegalArgumentException("S1_DIGEST_COUNTS");}
    }
    /** Within an immutable run, receipt cardinality is an append-only revision,
     * not a global sequence or an independent content digest. */
    public record RevisionBinding(UUID tenant,UUID runId,Instant from,Instant until,long sourceRevision,long receiptRevision){
        public RevisionBinding {
            Objects.requireNonNull(tenant);Objects.requireNonNull(runId);Objects.requireNonNull(from);Objects.requireNonNull(until);
            if(!from.isBefore(until)||sourceRevision<0||receiptRevision<0)
                throw new IllegalArgumentException("S1_REVISION_BINDING");
        }
    }
    public record Evidence(int version,UUID tenant,UUID actor,UUID runId,Instant from,Instant until,
                           long sourceRevision,long receiptRevision,Instant asOf,Counts counts,Digest digest,State state,
                           List<Limitation> limitations,HistoricalCoverageService.Eligibility eligibility){
        public Evidence {
            limitations=List.copyOf(limitations);
            new RevisionBinding(tenant,runId,from,until,sourceRevision,receiptRevision);
        }
        public RevisionBinding revisionBinding(){return new RevisionBinding(tenant,runId,from,until,sourceRevision,receiptRevision);}
        public boolean ownerCompletenessProven(){return false;}
    }
    private record Run(Instant from,Instant until,long revision,Instant asOf){}
    private record Measured(Counts counts,long receiptRevision){}
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final TenantAuthorization authorization;
    BookedSourceReconciliationService(JdbcTemplate jdbc,PlatformTransactionManager manager,TenantAuthorization authorization){
        this.jdbc=jdbc;this.manager=manager;this.authorization=authorization;
    }
    /** Reconcile one existing SALES/ORDER_PLACED/OCCURRED run in an authorized snapshot.
     * No writes, caller supplied evidence, generic owner permission or runtime qualification. */
    public Evidence inspect(VerifiedActor actor,UUID tenant,UUID runId){
        Objects.requireNonNull(runId);
        return authorizedSnapshot(actor,tenant,user->reconcile(tenant,user,runId));
    }
    /** Read durable owner declaration and fresh reconciliation in the same authorized RR snapshot.
     * Snapshot status is diagnostic only, never an authorization to publish history. */
    public BookedOwnerDeclarationService.Inspection inspectAuthority(VerifiedActor actor,UUID tenant,UUID proofId){
        Objects.requireNonNull(proofId);
        return authorizedSnapshot(actor,tenant,user->{
            requirePostgres();
            var stored=BookedOwnerDeclarationService.load(jdbc,tenant,proofId);
            if(stored==null)throw new IllegalArgumentException("S1_DECLARATION_NOT_FOUND");
            return BookedOwnerDeclarationService.inspect(stored,reconcile(tenant,user,stored.draft().runId()));
        });
    }
    /** Compare persisted declarations against exact calendar months and independent owner counts.
     * Up to 25 requested months and four proof IDs total; no caller-provided proof content.
     * Returns diagnostics only, including when a zero observation exactly matches declarations. */
    public BookedMonthlyAuthorityAssessment.Result inspectMonthlyAuthority(VerifiedActor actor,UUID tenant,long calendarVersion,
            List<HistoricalCoverageService.RequiredMonth> months,Map<YearMonth,List<UUID>> proofIds){
        if(calendarVersion<1)throw new IllegalArgumentException("S1_CALENDAR");
        var required=HistoricalCoverageService.normalize(months);
        var selection=BookedMonthlyAuthorityAssessment.request(required,proofIds);
        return authorizedSnapshot(actor,tenant,user->{
            var history=HistoricalCoverageService.inspectSnapshot(jdbc,tenant,user,calendarVersion,required);
            var byMonth=new HashMap<YearMonth,List<BookedMonthlyAuthorityAssessment.Slice>>();
            var reconciled=new HashMap<UUID,Evidence>();
            for(var month:required){
                var slices=new ArrayList<BookedMonthlyAuthorityAssessment.Slice>();
                for(UUID proof:selection.getOrDefault(month.month(),List.of())){
                    var stored=BookedOwnerDeclarationService.load(jdbc,tenant,proof);
                    if(stored==null)throw new IllegalArgumentException("S1_DECLARATION_NOT_FOUND");
                    var current=reconciled.computeIfAbsent(stored.draft().runId(),id->reconcile(tenant,user,id));
                    slices.add(new BookedMonthlyAuthorityAssessment.Slice(current,BookedOwnerDeclarationService.inspect(stored,current)));
                }
                byMonth.put(month.month(),List.copyOf(slices));
            }
            return BookedMonthlyAuthorityAssessment.assess(history,byMonth);
        });
    }
    /** Inspect a bounded exact interval partition within ONE authorized RR snapshot.
     * No caller-supplied evidence, no proof issuance. */
    public BookedHistoryComposition.Assessment inspectComposition(VerifiedActor actor,UUID tenant,
            BookedHistoryComposition.Mode mode,Instant from,Instant until,List<UUID> runIds){
        Objects.requireNonNull(mode);
        var requested=BookedHistoryComposition.request(from,until);
        var ids=List.copyOf(runIds);
        if(ids.size()>BookedHistoryComposition.MAX_RUNS||new HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("S1_COMPOSITION_RUNS");
        return authorizedSnapshot(actor,tenant,user->{
            requirePostgres();
            var manifest=jdbc.query("""
                SELECT w.created_at,transaction_timestamp(),
                  coalesce((SELECT revision FROM crm.int_source_revision WHERE tenant_id=w.id),0)
                   +(SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=w.id)
                FROM public.iam_workspace w WHERE w.id=?
                """,rs->rs.next()?new BookedHistoryComposition.Snapshot(tenant,user,rs.getTimestamp(1).toInstant(),
                    rs.getLong(3),rs.getTimestamp(2).toInstant()):null,tenant);
            if(manifest==null)throw new IllegalArgumentException("S1_TENANT_NOT_FOUND");
            var slices=new ArrayList<Evidence>();
            for(UUID id:ids)slices.add(reconcile(tenant,user,id));
            return BookedHistoryComposition.assess(manifest,mode,requested,slices);
        });
    }
    private <T> T authorizedSnapshot(VerifiedActor actor,UUID tenant,java.util.function.Function<UUID,T> action){
        Objects.requireNonNull(actor);Objects.requireNonNull(tenant);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("S1_REQUIRES_OWN_TRANSACTION");
        var admission=new TransactionTemplate(manager);
        admission.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);admission.setTimeout(180);
        return admission.execute(outer->{
            UUID user=actor.userId();
            authorization.requireForCommand(user,tenant,DomainPermission.SALES_ORDER_READ);
            var snapshot=new TransactionTemplate(manager);
            snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            snapshot.setReadOnly(true);snapshot.setTimeout(120);
            var result=snapshot.execute(inner->action.apply(user));
            actor.userId();return result;
        });
    }
    private void requirePostgres(){
        if(!Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql"))))
            throw new IllegalStateException("S1_POSTGRESQL_REQUIRED");
    }
    private Evidence reconcile(UUID tenant,UUID actor,UUID runId){
        requirePostgres();
        var run=jdbc.query("""
            SELECT r.range_start,r.range_end,
              coalesce((SELECT revision FROM crm.int_source_revision WHERE tenant_id=r.tenant_id),0)
                +(SELECT count(*) FROM crm.int_source_revision_event WHERE tenant_id=r.tenant_id),transaction_timestamp()
            FROM crm.int_fact_backfill_run r
            WHERE r.tenant_id=? AND r.run_id=? AND r.owner_module='SALES'
              AND r.source_kind='ORDER_PLACED' AND r.time_basis='OCCURRED'
            """,rs->rs.next()?new Run(rs.getTimestamp(1).toInstant(),rs.getTimestamp(2).toInstant(),
                rs.getLong(3),rs.getTimestamp(4).toInstant()):null,tenant,runId);
        if(run==null)throw new IllegalArgumentException("S1_BOOKED_RUN_NOT_FOUND");
        if(!run.from().isBefore(run.until())||Duration.between(run.from(),run.until()).compareTo(CanonicalFactDeliveryService.MAX_SLICE)>0)
            throw new IllegalArgumentException("S1_BOOKED_RUN_RANGE");
        // Independently compare the exact identity set; receipt identity includes tenant, owner and kind.
        // No nested call to delivery.coverage(): that would own a different snapshot.
        var measured=jdbc.queryForObject("""
            WITH p AS (SELECT CAST(? AS uuid) t,CAST(? AS uuid) run_id,
                  CAST(? AS timestamptz) a,CAST(? AS timestamptz) b),
            o AS (SELECT e.tenant_id,e.id,e.order_id,e.occurred_at,
                (EXISTS(SELECT 1 FROM crm.sales_order h WHERE h.tenant_id=e.tenant_id AND h.id=e.order_id AND h.booked_snapshot_sealed)
                 AND EXISTS(SELECT 1 FROM crm.sales_order_line l WHERE l.tenant_id=e.tenant_id AND l.order_id=e.order_id)
                 AND NOT EXISTS(SELECT 1 FROM crm.sales_order_status_event duplicate
                    WHERE duplicate.tenant_id=e.tenant_id AND duplicate.order_id=e.order_id
                      AND duplicate.event_type='PLACED' AND duplicate.id<>e.id)) AS valid
                FROM crm.sales_order_status_event e,p
                WHERE e.tenant_id=p.t AND e.event_type='PLACED' AND e.occurred_at>=p.a AND e.occurred_at<p.b),
            f AS (SELECT f.tenant_id,f.source_id,f.subject_id,f.occurred_at,f.recorded_at,f.digest,f.schema_version
                FROM crm.int_canonical_fact f,p WHERE f.tenant_id=p.t AND f.owner_module='SALES'
                AND f.source_kind='ORDER_PLACED' AND f.occurred_at>=p.a AND f.occurred_at<p.b),
            r AS (SELECT r.tenant_id,r.source_id,r.disposition,r.digest,r.schema_version
                FROM crm.int_fact_backfill_receipt r,p WHERE r.tenant_id=p.t AND r.run_id=p.run_id
                AND r.owner_module='SALES' AND r.source_kind='ORDER_PLACED')
            SELECT (SELECT count(*) FROM o),(SELECT count(*) FROM f),
                (SELECT count(*) FROM o WHERE NOT EXISTS(SELECT 1 FROM f WHERE f.tenant_id=o.tenant_id
                    AND f.source_id=o.id AND f.subject_id=o.order_id AND f.occurred_at=o.occurred_at AND f.recorded_at IS NULL)),
                (SELECT count(*) FROM f WHERE NOT EXISTS(SELECT 1 FROM o WHERE o.tenant_id=f.tenant_id
                    AND o.id=f.source_id AND o.order_id=f.subject_id AND o.occurred_at=f.occurred_at AND f.recorded_at IS NULL)),
                (SELECT count(*) FROM o WHERE NOT EXISTS(SELECT 1 FROM r WHERE r.tenant_id=o.tenant_id AND r.source_id=o.id)),
                (SELECT count(*) FROM r WHERE disposition IN ('CONFLICT','INVALID')),
                (SELECT count(*) FROM r LEFT JOIN f USING(tenant_id,source_id)
                    WHERE r.disposition IN ('INSERTED','VERIFIED') AND
                    (f.source_id IS NULL OR r.digest IS DISTINCT FROM f.digest OR r.schema_version IS DISTINCT FROM f.schema_version)),
                (SELECT count(*) FROM r WHERE NOT EXISTS(SELECT 1 FROM o WHERE o.tenant_id=r.tenant_id AND o.id=r.source_id)),
                (SELECT count(DISTINCT order_id) FROM o WHERE valid),
                (SELECT count(*) FROM o WHERE NOT valid),
                (SELECT count(*) FROM r)
            """,(rs,n)->new Measured(new Counts(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),
                rs.getLong(7),rs.getLong(8),rs.getLong(9),rs.getLong(10)),rs.getLong(11)),tenant,runId,Timestamp.from(run.from()),Timestamp.from(run.until()));
        var checked=new IndependentCanonicalDigestVerifier(jdbc).bookedSlice(tenant,run.from(),run.until(),CanonicalFactDeliveryService.MAX_BATCH);
        var digest=new Digest(checked.checked(),checked.mismatches(),checked.legacy(),checked.budgetExceeded(),checked.populationDigest());
        Objects.requireNonNull(measured);
        return classify(tenant,actor,runId,run.from(),run.until(),run.revision(),measured.receiptRevision(),run.asOf(),measured.counts(),digest);
    }
    static Evidence classify(UUID tenant,UUID actor,UUID run,Instant from,Instant until,long revision,long receiptRevision,Instant asOf,Counts c,Digest d){
        var reasons=new ArrayList<Limitation>();reasons.add(Limitation.OWNER_AUTHORITY_MISSING);
        boolean open=until.isAfter(asOf);
        if(open)reasons.add(Limitation.OPEN_RANGE);
        Objects.requireNonNull(tenant);Objects.requireNonNull(actor);Objects.requireNonNull(run);
        if(revision<0||!from.isBefore(until))throw new IllegalArgumentException("S1_RECONCILIATION_SCOPE");
        // The receipt PK gives at most one receipt per owner identity in this run.
        // A receipt is either for a present owner or an orphan; bind the measured set.
        if(receiptRevision!=Math.addExact(c.owners()-c.unprocessed(),c.orphanReceipts()))
            throw new IllegalArgumentException("S1_RECEIPT_CARDINALITY");
        boolean invalidPopulation=c.invalidOwnerEvents()!=0||c.distinctValidOrders()!=c.owners();
        if(invalidPopulation)reasons.add(Limitation.OWNER_POPULATION_INVALID);
        boolean identity=c.missing()!=0||c.extra()!=0||c.owners()!=c.facts();
        if(identity)reasons.add(Limitation.IDENTITY_MISMATCH);
        if(c.unprocessed()!=0)reasons.add(Limitation.MISSING_RECEIPTS);
        if(c.rejected()!=0)reasons.add(Limitation.REJECTED_RECEIPTS);
        boolean receipt=c.receiptMismatches()!=0||c.orphanReceipts()!=0;
        if(receipt)reasons.add(Limitation.RECEIPT_MISMATCH);
        boolean badDigest=d.mismatches()!=0||(!d.budgetExceeded()&&d.checked()!=c.facts());
        if(badDigest)reasons.add(Limitation.DIGEST_MISMATCH);
        if(d.legacy()!=0)reasons.add(Limitation.LEGACY_DIGEST);
        if(d.budgetExceeded())reasons.add(Limitation.DIGEST_BUDGET_EXHAUSTED);
        State state=identity||invalidPopulation||receipt||badDigest||c.rejected()!=0?State.CONFLICT:
            d.budgetExceeded()?State.BUDGET_EXHAUSTED:open||c.unprocessed()!=0||d.legacy()!=0?State.INCOMPLETE:
            State.RECONCILED_PRESENT_SOURCE;
        return new Evidence(VERSION,tenant,actor,run,from,until,revision,receiptRevision,asOf,c,d,state,reasons,
            HistoricalCoverageService.Eligibility.BLOCKED);
    }
}
