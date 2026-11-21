package kz.rm.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * PostgreSQL-only, bounded audit retention worker.
 *
 * Only explicitly approved tenant policies can remove rows; each event under an
 * active legal hold remains untouched. Each batch is its own short transaction,
 * and an advisory transaction lock serializes workers across application pods.
 * No partition DDL is issued by this application service.
 */
@Service
public class AuditRetentionService {
    private static final int ADVISORY_NAMESPACE = 701121;
    private static final int ADVISORY_RETENTION_KEY = 1;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public AuditRetentionService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public record RetentionResult(UUID runId, int deletedRows, int batches, boolean skipped) {}

    public RetentionResult runBounded(int batchSize, int maxBatches) {
        if (batchSize < 1 || batchSize > 1_000 || maxBatches < 1 || maxBatches > 100)
            throw new IllegalArgumentException("Retention batch limits are invalid");

        UUID runId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO crm.audit_retention_run(id,started_at,outcome)
            VALUES (?,?,'RUNNING')
            """,runId,now);
        int deleted = 0;
        int batches = 0;
        boolean skipped = false;
        try {
            for (int i = 0; i < maxBatches; i++) {
                Integer count = transactions.execute(status -> deleteBatch(batchSize));
                if (count == null) throw new IllegalStateException("Retention transaction unavailable");
                if (count == -1) {
                    skipped = true;
                    break;
                }
                if (count == 0) break;
                deleted = Math.addExact(deleted, count);
                batches++;
                if (count < batchSize) break;
            }
            jdbc.update("""
                UPDATE crm.audit_retention_run
                SET finished_at=?,deleted_rows=?,batch_count=?,outcome=?
                WHERE id=?
                """,Timestamp.from(Instant.now()),deleted,batches,skipped ? "SKIPPED" : "DONE",runId);
            return new RetentionResult(runId,deleted,batches,skipped);
        } catch (RuntimeException e) {
            jdbc.update("""
                UPDATE crm.audit_retention_run
                SET finished_at=?,deleted_rows=?,batch_count=?,outcome='FAILED'
                WHERE id=?
                """,Timestamp.from(Instant.now()),deleted,batches,runId);
            throw e;
        }
    }

    private int deleteBatch(int limit) {
        // Lock is acquired and released with this short database transaction.
        Boolean acquired = jdbc.queryForObject("""
            SELECT pg_try_advisory_xact_lock(?,?)
            """,Boolean.class,ADVISORY_NAMESPACE,ADVISORY_RETENTION_KEY);
        if (!Boolean.TRUE.equals(acquired)) return -1;

        // Using (tableoid,ctid) to identify a physical row is valid ONLY within this
        // statement/transaction and is necessary for rows in different partitions.
        // Policy, cutoff, hold and row locks are all checked server-side.
        return jdbc.update("""
            WITH victims AS (
              SELECT a.tableoid AS source_table,a.ctid AS row_locator
              FROM crm.audit_event a
              JOIN crm.audit_retention_policy p ON p.tenant_id=a.tenant_id
              WHERE p.enabled=TRUE AND p.approved_at IS NOT NULL
                AND a.occurred_at < (CURRENT_TIMESTAMP - make_interval(years => p.retention_years))
                AND NOT EXISTS (
                  SELECT 1 FROM crm.audit_retention_hold h
                  WHERE h.tenant_id=a.tenant_id AND h.event_id=a.id AND h.released_at IS NULL
                )
              ORDER BY a.occurred_at,a.id
              LIMIT ?
              FOR UPDATE OF a SKIP LOCKED
            )
            DELETE FROM crm.audit_event a
            USING victims v
            WHERE a.tableoid=v.source_table AND a.ctid=v.row_locator
            """,limit);
    }
}
