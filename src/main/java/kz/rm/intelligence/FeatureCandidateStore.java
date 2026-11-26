package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.math.BigDecimal;

/** Internal candidate persistence only. No arbitrary payload input and no decision permit. */
@Service
class FeatureCandidateStore {
    record Request(UUID generation,UUID proof,L3DerivedSeriesService.Metric metric,
                   L3DerivedSeriesService.Filter filter,PeriodResolver.Code period,LocalDate anchor,
                   int window,DerivedMetricEngine.Operator operator){
        Request {
            Objects.requireNonNull(generation);Objects.requireNonNull(proof);Objects.requireNonNull(metric);
            Objects.requireNonNull(filter);Objects.requireNonNull(period);Objects.requireNonNull(anchor);Objects.requireNonNull(operator);
            if(window<2||window>120)throw new IllegalArgumentException("FEATURE_WINDOW");
            filter=new L3DerivedSeriesService.Filter(filter.region(),filter.group(),filter.sku(),
                filter.warehouses().stream().sorted().toList(),filter.currency());
        }
    }
    record Receipt(UUID id,boolean replay){}
    record ReadResult(UUID id,String payload,MetricActivityGate.Assessment currentActivity){}
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final L3DerivedSeriesService series;
    private final MultidimensionalCoverageService coverage;
    FeatureCandidateStore(JdbcTemplate jdbc,PlatformTransactionManager manager,L3DerivedSeriesService series,
                          MultidimensionalCoverageService coverage){
        this.jdbc=jdbc;this.manager=manager;this.series=series;this.coverage=coverage;
    }
    private <T>T own(java.util.function.Supplier<T> work){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("FEATURE_OWN_TRANSACTION");
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(240);
        return tx.execute(status->work.get());
    }
    // Build canonical request identity in PostgreSQL, never rely on record.toString().
    private String requestJson(Request r){
        return jdbc.queryForObject("""
            SELECT jsonb_build_object('generation',CAST(? AS uuid),'proof',CAST(? AS uuid),
              'metric',CAST(? AS text),'region',CAST(? AS text),'group',CAST(? AS uuid),
              'sku',CAST(? AS uuid),'warehouses',to_jsonb(CAST(? AS uuid[])),
              'currency',CAST(? AS text),'period',CAST(? AS text),'anchor',CAST(? AS date),
              'window',CAST(? AS integer),'operator',CAST(? AS text),
              'contractVersion',CAST(? AS integer),'methodVersion',CAST(? AS integer),
              'numericPolicyVersion',CAST(? AS integer),'activityPolicyVersion',CAST(? AS integer))::text
            """,String.class,r.generation(),r.proof(),r.metric().name(),r.filter().region(),r.filter().group(),
            r.filter().sku(),warehouseArray(r.filter()),r.filter().currency(),r.period().name(),r.anchor(),
            r.window(),r.operator().name(),PredictiveFeatureContract.VERSION,DerivedMetricEngine.METHOD_VERSION,
            DerivedNumericParity.VERSION,MetricActivityGate.POLICY_VERSION);
    }
    private static String warehouseArray(L3DerivedSeriesService.Filter f){
        return "{"+String.join(",",f.warehouses().stream().map(UUID::toString).toList())+"}";
    }
    private record Stored(UUID id,UUID actor,boolean matches,boolean hashValid,String payload,
                          String activity,UUID generation,UUID proof){}
    private Stored stored(UUID tenant,UUID key,String request){
        return jdbc.query("""
            SELECT feature_id,actor_id,payload->'request'=CAST(? AS jsonb),
              payload_sha256=encode(sha256(convert_to(payload::text,'UTF8')),'hex'),
              payload::text,activity,generation_id,proof_id
            FROM crm.int_feature_candidate WHERE tenant_id=? AND request_key=?
            """,rs->rs.next()?new Stored(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getBoolean(3),rs.getBoolean(4),rs.getString(5),rs.getString(6),
                rs.getObject(7,UUID.class),rs.getObject(8,UUID.class)):null,request,tenant,key);
    }
    private static void verify(Stored row,UUID actor,Request request){
        if(row==null)throw new IllegalStateException("FEATURE_NOT_FOUND");
        if(!actor.equals(row.actor()))throw new SecurityException("FEATURE_ACTOR_SCOPE");
        if(!row.hashValid())throw new IllegalStateException("FEATURE_HASH_MISMATCH");
        if(!row.matches()||!request.generation().equals(row.generation())||!request.proof().equals(row.proof()))
            throw new IllegalStateException("FEATURE_IDEMPOTENCY_CONFLICT");
    }
    Receipt write(VerifiedActor actor,UUID tenant,UUID requestKey,Request request){
        Objects.requireNonNull(requestKey);Objects.requireNonNull(request);
        return own(()->{
            series.admit(actor,tenant,request.metric(),request.filter());
            String identity=requestJson(request);
            // Serialize exact request retries (unique constraint is the final fence).
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),7216))",
                rs->{rs.next();return Boolean.TRUE;},tenant+":"+requestKey);
            Stored prior=stored(tenant,requestKey,identity);
            if(prior!=null){
                verify(prior,actor.userId(),request);
                return new Receipt(prior.id(),true);
            }
            var observed=series.readAdmitted(actor,tenant,request.generation(),request.proof(),request.metric(),
                request.filter(),request.period(),request.anchor(),request.window());
            if(!observed.currentProof())throw new IllegalStateException("FEATURE_PROOF_NOT_CURRENT");
            var candidate=PredictiveFeatureContract.candidate(observed,request.operator());
            // No caller supplied JSON. The immutable payload includes the exact ordered inputs.
            String payload=payload(identity,candidate);
            UUID id=UUID.randomUUID();var r=candidate.revision();
            int inserted=jdbc.update("""
                INSERT INTO crm.int_feature_candidate(tenant_id,generation_id,feature_id,contract_version,
                  numeric_policy_version,method_version,observed_at,payload,availability,proof_id,
                  source_revision,dirty_revision,receipt_revision,materialization_revision,lifecycle_revision,
                  activity_policy_version,activity,payload_sha256,request_key,actor_id)
                SELECT ?,?,?,?,?,?,?,p,'CURRENT_SNAPSHOT_ONLY',?,?,?,?,?,?,?,?,
                  encode(sha256(convert_to(p::text,'UTF8')),'hex'),?,?
                FROM (SELECT CAST(? AS jsonb) p) q
                ON CONFLICT(tenant_id,request_key) DO NOTHING
                """,tenant,request.generation(),id,candidate.contractVersion(),candidate.numericPolicyVersion(),
                candidate.derivedMethodVersion(),Timestamp.from(candidate.observedAt()),request.proof(),r.source(),
                r.dirty(),r.receipt(),r.materialization(),r.lifecycle(),candidate.activity().policyVersion(),
                candidate.activity().activity().name(),requestKey,actor.userId(),payload);
            Stored saved=stored(tenant,requestKey,identity);
            verify(saved,actor.userId(),request);
            if(inserted==0&&!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT CAST(? AS jsonb)=CAST(? AS jsonb)",Boolean.class,payload,saved.payload())))
                throw new IllegalStateException("FEATURE_IDEMPOTENCY_CONFLICT");
            return new Receipt(saved.id(),inserted==0);
        });
    }
    private String payload(String identity,PredictiveFeatureContract.Candidate c){
        var args=new ArrayList<Object>();
        int ordinal=0;
        for(var o:c.inputs()){
            Collections.addAll(args,ordinal++,o.period().startInclusive().toString(),o.period().endExclusive().toString(),
                o.period().zoneId(),o.period().calendarVersion(),o.value(),o.quality().name(),o.reason());
        }
        String tuples=String.join(",",Collections.nCopies(c.inputs().size(),
            "(CAST(? AS integer),CAST(? AS text),CAST(? AS text),CAST(? AS text),CAST(? AS bigint),CAST(? AS numeric),CAST(? AS text),CAST(? AS text))"));
        String inputJson=jdbc.queryForObject("""
            SELECT jsonb_agg(jsonb_build_object('from',a,'until',b,'zone',z,'calendarVersion',cv,
              'value',v,'quality',q,'reason',r) ORDER BY ordinal)::text
            FROM (VALUES %s) AS inputs(ordinal,a,b,z,cv,v,q,r)
            """.formatted(tuples),String.class,args.toArray());
        var r=c.revision();var a=c.activity();
        return jdbc.queryForObject("""
            SELECT jsonb_build_object('request',CAST(? AS jsonb),'inputs',CAST(? AS jsonb),
              'observedAt',CAST(? AS text),'value',CAST(? AS numeric),'quality',CAST(? AS text),
              'sourceRevision',CAST(? AS bigint),'dirtyRevision',CAST(? AS bigint),'receiptRevision',CAST(? AS bigint),
              'materializationRevision',CAST(? AS bigint),'lifecycleRevision',CAST(? AS bigint),
              'activity',CAST(? AS text),'requiredPeriods',CAST(? AS integer),'availablePeriods',CAST(? AS integer),
              'decisionEligibility','BLOCKED')::text
            """,String.class,identity,inputJson,c.observedAt().toString(),c.value(),c.quality().name(),
            r.source(),r.dirty(),r.receipt(),r.materialization(),r.lifecycle(),a.activity().name(),a.requiredPeriods(),a.availablePeriods());
    }
    ReadResult read(VerifiedActor actor,UUID tenant,UUID requestKey,Request request){
        Objects.requireNonNull(requestKey);Objects.requireNonNull(request);
        return own(()->{
            series.admit(actor,tenant,request.metric(),request.filter());
            Stored row=stored(tenant,requestKey,requestJson(request));
            verify(row,actor.userId(),request);
            // Recompute from current evidence; never turn a stored activity into a permit.
            var current=series.readAdmitted(actor,tenant,request.generation(),request.proof(),request.metric(),
                request.filter(),request.period(),request.anchor(),request.window());
            var activity=current.activity().get(request.operator());
            boolean same=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM crm.int_feature_candidate
                WHERE tenant_id=? AND request_key=? AND source_revision=? AND dirty_revision=? AND receipt_revision=?
                  AND materialization_revision=? AND lifecycle_revision=?)
                """,Boolean.class,tenant,requestKey,current.revision().source(),current.revision().dirty(),
                current.revision().receipt(),current.revision().materialization(),current.revision().lifecycle()));
            if(!same || !current.currentProof())activity=new MetricActivityGate.Assessment(MetricActivityGate.POLICY_VERSION,
                MetricActivityGate.Activity.STALE,MetricActivityGate.DecisionEligibility.BLOCKED,
                request.window(),0,List.of(same?"FEATURE_PROOF_NOT_CURRENT":"FEATURE_REVISION_CHANGED"));
            actor.userId();
            return new ReadResult(row.id(),row.payload(),activity);
        });
    }
}
