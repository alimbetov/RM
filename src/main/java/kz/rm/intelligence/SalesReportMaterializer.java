package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static kz.rm.intelligence.AuthorizedAnalyticalReadService.*;

/** Dormant owner-side R02 pilot. No runtime bean, publication or external manifest input. */
final class SalesReportMaterializer {
    private final AuthorizedAnalyticalReadService reader;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReportDatasetBatchStore batch;
    private final ReportDatasetValidationStore validation;

    SalesReportMaterializer(AuthorizedAnalyticalReadService reader,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.reader=Objects.requireNonNull(reader);this.jdbc=Objects.requireNonNull(jdbc);
        tx=new TransactionTemplate(Objects.requireNonNull(manager));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(30);
        batch=new ReportDatasetBatchStore(jdbc,manager);
        validation=new ReportDatasetValidationStore(jdbc,manager);
    }

    record Receipt(UUID tenant,UUID dataset,LocalDate stagingDate,String scopeDigest,String sourceDigest,
                   ReportDatasetDigest.Result expected,ReportDatasetDigest.Result observed){}

    Receipt materialize(VerifiedActor actor,UUID tenant,long calendar,LocalDate lastClosedMonth){
        return materializeAccounting(actor,tenant,calendar,lastClosedMonth,UUID.randomUUID().toString());
    }
    Receipt materializeAccounting(VerifiedActor actor,UUID tenant,long calendar,LocalDate lastClosedMonth,String key){
        return materialize(actor,tenant,calendar,lastClosedMonth,null,key);
    }
    /** Compatibility overload: supplied currency is an assertion, never a selector. */
    Receipt materialize(VerifiedActor actor,UUID tenant,long calendar,LocalDate lastClosedMonth,String currency){
        return materialize(actor,tenant,calendar,lastClosedMonth,currency,UUID.randomUUID().toString());
    }

    Receipt materialize(VerifiedActor actor,UUID tenant,long calendar,LocalDate lastClosedMonth,String currency,String key){
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("R02_OWN_TRANSACTION");
        Objects.requireNonNull(key);
        if(key.isBlank()||key.length()>128||lastClosedMonth.getDayOfMonth()!=1)
            throw new IllegalArgumentException("R02_REQUEST");

        var policy=Objects.requireNonNull(tx.execute(status->{
            limits();return reader.admitAccountingPolicy(actor,tenant);
        }));
        if(currency!=null&&!policy.currency().equals(currency))throw new IllegalArgumentException("REPORT_ACCOUNTING_CURRENCY_MISMATCH");
        var queries=List.of(
            new Query(Metric.BOOKED_AMOUNT,calendar,PeriodResolver.Code.M,lastClosedMonth,null,null,null,List.of(),policy.currency()),
            new Query(Metric.BOOKED_ORDERS,calendar,PeriodResolver.Code.M,lastClosedMonth,null,null,null,List.of(),null));
        String scope=ReportSourceManifest.scopeDigest(tenant,queries);
        // The actor is part of the logical request. A different actor cannot replay a private request.
        String request=requestDigest(actor.userId(),scope,policy);
        UUID instance=Objects.requireNonNull(tx.execute(status->{
            limits();reader.admitReportRecovery(actor,tenant,queries);reader.requireAccountingPolicy(tenant,policy,true);
            return jdbc.queryForObject("SELECT reporting.int_report_register_request(?,?,?,?,?,?,?)",UUID.class,
                tenant,UUID.randomUUID(),key,request,scope,"RM-R02","1");
        }));
        var recovered=recover(actor,tenant,queries,instance,policy);
        if(recovered!=null)return recovered;
        disposeExpired(actor,tenant,queries,instance,policy);
        // An earlier Seal may have committed while disposition was waiting.
        recovered=recover(actor,tenant,queries,instance,policy);
        if(recovered!=null)return recovered;
        // Source admission and RR snapshot finish before any reporting writes.
        var manifest=reader.readReportMonths(actor,tenant,queries,policy);
        if(!scope.equals(manifest.scopeDigest()))throw new IllegalStateException("R02_SCOPE_MISMATCH");
        UUID dataset=UUID.randomUUID(),worker=UUID.randomUUID();
        var plan=SalesReportDatasetPlan.from(manifest,dataset);
        var header=plan.header();
        LocalDate day=Objects.requireNonNull(tx.execute(status->{
            limits();
            LocalDate d=jdbc.queryForObject("SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date",LocalDate.class);
            jdbc.queryForObject("SELECT reporting.int_report_ensure_day(?)",Boolean.class,d);
            return d;
        }));
        // A different transaction is necessary: no shared/exclusive day-lock upgrade.
        tx.executeWithoutResult(status->{
            limits();reader.admitReportRecovery(actor,tenant,queries);lock(day);
            requireTrue(jdbc.queryForObject("SELECT reporting.int_report_allocate_request(?,?,?,?,?,?,?,?,?)",Boolean.class,
                header.tenant(),instance,dataset,day,worker,header.scopeSha256(),header.sourceManifestSha256(),
                plan.expected().rows(),plan.expected().sha256()),"R02_ALLOCATE");
            if(jdbc.update("""
                INSERT INTO reporting.int_report_source_evidence(tenant_id,dataset_id,staging_date,scope_bytes,source_bytes,canonical_bytes)
                VALUES(?,?,?,?,?,?)
                """,tenant,dataset,day,manifest.scopeBytes(),manifest.sourceBytes(),plan.expected().canonicalBytes())!=1)
                throw new IllegalStateException("R02_EVIDENCE_INSERT");
        });
        if(batch.append(header.tenant(),dataset,day,worker,1L,plan.rows())!=plan.rows().size())
            throw new IllegalStateException("R02_BATCH_COUNT");
        tx.executeWithoutResult(status->{
            limits();lock(day);
            requireTrue(jdbc.queryForObject("SELECT reporting.int_report_freeze(?,?,?,?,?)",Boolean.class,
                header.tenant(),dataset,day,worker,1L),"R02_FREEZE");
        });
        var observed=validation.validateAndSeal(header.tenant(),dataset,day,worker,1L);
        if(!observed.equals(plan.expected()))throw new IllegalStateException("R02_OBSERVED_MISMATCH");
        return Objects.requireNonNull(recover(actor,tenant,queries,instance,policy),"R02_READY_NOT_FOUND");
    }
    private static String requestDigest(UUID actor,String scope,AccountingPolicy policy){
        try{
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(("RM-R02-REQUEST-2\n"+actor+"\n"+scope+"\n"+policy.country()+"\n"+policy.currency()+"\n"+policy.version()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private record Candidate(UUID dataset,LocalDate day){}
    private void disposeExpired(VerifiedActor actor,UUID tenant,List<Query> queries,UUID instance,AccountingPolicy policy){
        var candidates=Objects.requireNonNull(tx.execute(status->{
            limits();reader.admitReportRecovery(actor,tenant,queries);
            return jdbc.query("""
                SELECT l.dataset_id,l.staging_date FROM reporting.int_report_request_attempt l
                JOIN reporting.int_report_dataset_attempt a USING(tenant_id,dataset_id,staging_date)
                WHERE l.tenant_id=? AND l.instance_id=? AND l.abandoned_at IS NULL
                  AND l.selected_at IS NULL AND a.build_state<>'READY' AND a.build_until<=clock_timestamp()
                ORDER BY l.staging_date,l.dataset_id LIMIT 4
                """,(rs,n)->new Candidate(rs.getObject(1,UUID.class),rs.getObject(2,LocalDate.class)),tenant,instance);
        }));
        if(candidates.size()>3)throw new IllegalStateException("R02_ATTEMPT_BUDGET");
        for(var candidate:candidates)tx.executeWithoutResult(status->{
            limits();reader.admitReportRecovery(actor,tenant,queries);lock(candidate.day());
            jdbc.queryForObject("SELECT reporting.int_report_abandon_request_attempt(?,?,?,?)",Boolean.class,
                tenant,instance,candidate.dataset(),candidate.day());
            actor.userId();
        });
    }
    private Receipt recover(VerifiedActor actor,UUID tenant,List<Query> queries,UUID instance,AccountingPolicy policy){
        return tx.execute(status->{
            limits();reader.admitReportRecovery(actor,tenant,queries);
            // Metadata discovery only; take the date fence before selection or data access.
            var candidates=jdbc.query("""
                SELECT l.dataset_id,l.staging_date FROM reporting.int_report_request_attempt l
                JOIN reporting.int_report_dataset_attempt a USING(tenant_id,dataset_id,staging_date)
                WHERE l.tenant_id=? AND l.instance_id=? AND a.build_state='READY'
                ORDER BY l.selected_at DESC NULLS LAST,l.dataset_id LIMIT 2
                """,(rs,n)->new Candidate(rs.getObject(1,UUID.class),rs.getObject(2,LocalDate.class)),tenant,instance);
            if(candidates.isEmpty())return null;
            if(candidates.size()!=1)throw new IllegalStateException("R02_AMBIGUOUS_READY");
            var candidate=candidates.getFirst();lock(candidate.day());
            UUID chosen=jdbc.queryForObject("SELECT reporting.int_report_select_request(?,?,?,?)",UUID.class,
                tenant,instance,candidate.dataset(),candidate.day());
            if(!candidate.dataset().equals(chosen))throw new IllegalStateException("R02_SELECTED_MISMATCH");
            var receipt=jdbc.queryForObject("""
                SELECT a.scope_digest,a.source_manifest_digest,a.expected_rows,a.expected_digest,
                       a.observed_rows,a.observed_digest,e.canonical_bytes
                FROM reporting.int_report_dataset_attempt a JOIN reporting.int_report_source_evidence e
                  USING(tenant_id,dataset_id,staging_date)
                WHERE a.tenant_id=? AND a.dataset_id=? AND a.staging_date=? AND a.build_state='READY'
                  AND a.scope_digest=encode(sha256(e.scope_bytes),'hex')
                  AND a.source_manifest_digest=encode(sha256(e.source_bytes),'hex')
                """,(rs,n)->new Receipt(tenant,chosen,candidate.day(),rs.getString(1),rs.getString(2),
                    new ReportDatasetDigest.Result(rs.getLong(3),rs.getLong(7),rs.getString(4)),
                    new ReportDatasetDigest.Result(rs.getLong(5),rs.getLong(7),rs.getString(6))),
                tenant,chosen,candidate.day());
            actor.userId();
            return Objects.requireNonNull(receipt);
        });
    }
    private void limits(){
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '20s'");
    }
    private void lock(LocalDate d){
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock_shared(1380794706,?::date-DATE '1970-01-01')",Integer.class,d);
    }
    private static void requireTrue(Boolean value,String message){
        if(!Boolean.TRUE.equals(value))throw new IllegalStateException(message);
    }
}
