package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owner-side bounded staging adapter. Deliberately not a runtime bean or authorization port. */
final class ReportDatasetBatchStore {
    static final int MAX_BATCH_ROWS = 256;
    static final int MAX_BATCH_BYTES = 4 * 1024 * 1024;
    private static final java.time.format.DateTimeFormatter FORMAT = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    ReportDatasetBatchStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=Objects.requireNonNull(jdbc);
        tx=new TransactionTemplate(Objects.requireNonNull(manager));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(30);
    }

    int append(UUID tenant, UUID dataset, LocalDate date, UUID worker, long token,
               List<ReportDatasetDigest.Row> input) {
        Objects.requireNonNull(tenant);Objects.requireNonNull(dataset);
        Objects.requireNonNull(date);Objects.requireNonNull(worker);
        if(token<1 || TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("REPORT_BATCH_TRANSACTION_OR_TOKEN");
        Objects.requireNonNull(input);
        if(input.isEmpty() || input.size()>MAX_BATCH_ROWS)
            throw new IllegalArgumentException("REPORT_BATCH_ROW_BUDGET");
        List<ReportDatasetDigest.Row> rows=List.copyOf(input);
        String payload=encode(rows);
        return Objects.requireNonNull(tx.execute(status->{
            jdbc.execute("SET LOCAL lock_timeout = '2s'");
            jdbc.execute("SET LOCAL statement_timeout = '20s'");
            jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock_shared(1380794706,?::date-DATE '1970-01-01')",
                Integer.class,date);
            Integer inserted=jdbc.queryForObject("SELECT reporting.int_report_append_batch(?,?,?,?,?,?::jsonb)",
                Integer.class,tenant,dataset,date,worker,token,payload);
            if(inserted==null || inserted<0 || inserted>rows.size())
                throw new IllegalStateException("REPORT_BATCH_RECEIPT");
            return inserted;
        }));
    }

    private static String encode(List<ReportDatasetDigest.Row> rows) {
        var out=new StringBuilder("[");
        var keys=new HashSet<Long>();
        for(var row:rows) {
            if(row.rowKey()>ReportDatasetDigest.MAX_ROWS || !keys.add(row.rowKey()))
                throw new IllegalArgumentException("REPORT_BATCH_ROW_KEY");
            if(row.metricCode().length()>64 || row.unit().length()>64)
                throw new IllegalArgumentException("REPORT_BATCH_SQL_TOKEN");
            // Same canonical row validation as the digest, without requiring
            // that an arbitrary batch start at ordinal 1.
            ReportDatasetDigest.canonicalRow(row);
            if(out.length()>1)out.append(',');
            out.append("{\"row_key\":").append(row.rowKey())
                .append(",\"metric_code\":").append(q(row.metricCode()))
                .append(",\"method_version\":").append(row.methodVersion())
                .append(",\"from_inclusive\":").append(q(FORMAT.format(row.fromInclusive())))
                .append(",\"until_exclusive\":").append(q(FORMAT.format(row.untilExclusive())))
                .append(",\"dimensions\":[");
            boolean first=true;
            for(var d:row.dimensions()) {
                if(!first)out.append(',');
                first=false;
                out.append('[').append(q(d.kind())).append(',').append(q(d.id())).append(',')
                    .append(q(Long.toString(d.mappingVersion()))).append(']');
            }
            out.append("],\"unit_code\":").append(q(row.unit()))
                .append(",\"currency\":").append(q(row.currency()))
                .append(",\"metric_value\":").append(q(ReportDatasetDigest.decimal(row.value())))
                .append(",\"quality_code\":").append(q(row.quality().name()))
                .append(",\"quality_reason\":").append(q(row.reason())).append('}');
            // All admitted fields are ASCII, so chars == UTF-8 bytes.
            if(out.length()+1>MAX_BATCH_BYTES)throw new IllegalArgumentException("REPORT_BATCH_BYTE_BUDGET");
        }
        return out.append(']').toString();
    }

    private static String q(String v){return v==null?"null":"\""+v+"\"";}
}
