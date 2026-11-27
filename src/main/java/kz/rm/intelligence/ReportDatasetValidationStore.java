package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/**
 * Dormant owner-side validation adapter for the bounded ARS storage experiment.
 * Not a Spring bean, worker, authorization entrypoint, or runtime-granted API.
 * Full streaming/pin protocol and source evidence admission remain separate.
 */
final class ReportDatasetValidationStore {
    private static final int PAGE_SIZE = 256;
    private static final long MAX_ROWS = 10_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    ReportDatasetValidationStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = new TransactionTemplate(Objects.requireNonNull(manager));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(30);
    }

    private record Manifest(String scope, String source, long rows, String digest) {}

    ReportDatasetDigest.Result validateAndSeal(UUID tenant, UUID dataset, LocalDate date, UUID worker, long token) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(dataset);
        Objects.requireNonNull(date);
        Objects.requireNonNull(worker);
        if (token < 1 || TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("REPORT_VALIDATION_TRANSACTION_OR_TOKEN");
        return Objects.requireNonNull(tx.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '2s'");
            jdbc.execute("SET LOCAL statement_timeout = '20s'");
            // Date fence acquired before planning or touching staging relations.
            jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock_shared(1380794706,?::date-DATE '1970-01-01')",
                Integer.class, date);
            Manifest manifest = jdbc.queryForObject("""
                SELECT scope_digest,source_manifest_digest,expected_rows,expected_digest
                  FROM reporting.int_report_lock_build(?,?,?,?,?,'VALIDATING')
                """,(rs,n)->new Manifest(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4)),
                tenant,dataset,date,worker,token);
            if (manifest == null || manifest.rows() < 0 || manifest.rows() > MAX_ROWS)
                throw new IllegalStateException("REPORT_VALIDATION_BUDGET");
            var digest = new ReportDatasetDigest(new ReportDatasetDigest.Header(tenant,dataset,manifest.scope(),manifest.source()));
            long last = 0;
            for (;;) {
                var rows = jdbc.query("""
                    SELECT d.row_key,d.metric_code,d.method_version,d.from_inclusive,d.until_exclusive,
                           d.unit_code,d.currency,d.metric_value,d.quality_code,d.quality_reason,
                           ARRAY(SELECT x->>0 FROM jsonb_array_elements(d.dimensions) WITH ORDINALITY e(x,o) ORDER BY o) AS kinds,
                           ARRAY(SELECT x->>1 FROM jsonb_array_elements(d.dimensions) WITH ORDINALITY e(x,o) ORDER BY o) AS ids,
                           ARRAY(SELECT x->>2 FROM jsonb_array_elements(d.dimensions) WITH ORDINALITY e(x,o) ORDER BY o) AS versions,
                           NOT EXISTS(SELECT 1 FROM jsonb_array_elements(d.dimensions) AS e(x)
                               WHERE CASE WHEN jsonb_typeof(x)='array' THEN
                                 jsonb_array_length(x)<>3 OR jsonb_typeof(x->0) IS DISTINCT FROM 'string'
                                  OR jsonb_typeof(x->1) IS DISTINCT FROM 'string'
                                  OR jsonb_typeof(x->2) IS DISTINCT FROM 'string'
                               ELSE true END) AS valid_dimensions
                    FROM reporting.int_report_dataset d
                    WHERE d.staging_date=? AND d.tenant_id=? AND d.dataset_id=? AND d.row_key>?
                    ORDER BY d.row_key LIMIT ?
                    """,(rs,n)->decode(rs),date,tenant,dataset,last,PAGE_SIZE);
                if (rows.isEmpty()) break;
                for (var row : rows) {
                    if (row.rowKey() > manifest.rows()) throw new IllegalStateException("REPORT_VALIDATION_ROW_RANGE");
                    digest.append(row);
                    last = row.rowKey();
                }
            }
            var observed = digest.finish();
            if (observed.rows()!=manifest.rows() || !observed.sha256().equals(manifest.digest()))
                throw new IllegalStateException("REPORT_VALIDATION_MISMATCH");
            Boolean sealed=jdbc.queryForObject("SELECT reporting.int_report_seal(?,?,?,?,?,?,?)",Boolean.class,
                tenant,dataset,date,worker,token,observed.rows(),observed.sha256());
            if (!Boolean.TRUE.equals(sealed)) throw new IllegalStateException("REPORT_SEAL_FAILED");
            return observed;
        }));
    }

    private static ReportDatasetDigest.Row decode(ResultSet rs) throws SQLException {
        if (!rs.getBoolean("valid_dimensions")) throw new IllegalStateException("REPORT_DIMENSION_SHAPE");
        String[] kinds = strings(rs,"kinds");
        String[] ids = strings(rs,"ids");
        String[] versions = strings(rs,"versions");
        if(kinds.length!=ids.length || kinds.length!=versions.length)
            throw new IllegalStateException("REPORT_DIMENSION_LENGTH");
        var dims = new ArrayList<ReportDatasetDigest.Dimension>(kinds.length);
        for(int i=0;i<kinds.length;i++) {
            if(versions[i]==null || !versions[i].matches("[1-9][0-9]*"))
                throw new IllegalStateException("REPORT_DIMENSION_VERSION");
            dims.add(new ReportDatasetDigest.Dimension(kinds[i],ids[i],Long.parseLong(versions[i])));
        }
        var row = new ReportDatasetDigest.Row(rs.getLong("row_key"),rs.getString("metric_code"),
            rs.getLong("method_version"),rs.getObject("from_inclusive",OffsetDateTime.class).toInstant(),
            rs.getObject("until_exclusive",OffsetDateTime.class).toInstant(),dims,rs.getString("unit_code"),
            rs.getString("currency"),rs.getBigDecimal("metric_value"),DataQuality.valueOf(rs.getString("quality_code")),
            rs.getString("quality_reason"));
        if (!row.dimensions().equals(dims)) throw new IllegalStateException("REPORT_DIMENSION_ORDER");
        return row;
    }

    private static String[] strings(ResultSet rs,String name) throws SQLException {
        var array=rs.getArray(name);
        try { return (String[])array.getArray(); }
        finally { array.free(); }
    }
}
