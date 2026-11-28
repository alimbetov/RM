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

    Receipt materialize(VerifiedActor actor,UUID tenant,long calendar,LocalDate lastClosedMonth,String currency){
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("R02_OWN_TRANSACTION");
        Objects.requireNonNull(currency);
        var queries=List.of(
            new Query(Metric.BOOKED_AMOUNT,calendar,PeriodResolver.Code.M,lastClosedMonth,null,null,null,List.of(),currency),
            new Query(Metric.BOOKED_ORDERS,calendar,PeriodResolver.Code.M,lastClosedMonth,null,null,null,List.of(),null));
        // Source admission and RR snapshot finish before any reporting writes.
        var manifest=reader.readReportMonths(actor,tenant,queries);
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
            limits();lock(day);
            requireTrue(jdbc.queryForObject("SELECT reporting.int_report_allocate(?,?,?,?,?,?,?,?)",Boolean.class,
                header.tenant(),dataset,day,worker,header.scopeSha256(),header.sourceManifestSha256(),
                plan.expected().rows(),plan.expected().sha256()),"R02_ALLOCATE");
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
        return new Receipt(header.tenant(),dataset,day,header.scopeSha256(),header.sourceManifestSha256(),plan.expected(),observed);
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
