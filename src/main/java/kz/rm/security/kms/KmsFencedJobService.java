package kz.rm.security.kms;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;

/**
 * PostgreSQL-backed fencing for one key-maintenance job per tenant.
 * Worker callbacks MUST mutate data using the current DB transaction only.
 * Not a barrier for ordinary CRM writes; generation admission is a separate gate.
 */
@Service
public class KmsFencedJobService {
    private static final Duration MIN_LEASE = Duration.ofSeconds(1);
    private static final Duration MAX_LEASE = Duration.ofMinutes(15);
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final String clockSql;

    public KmsFencedJobService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        String db = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        // PostgreSQL CURRENT_TIMESTAMP is fixed at transaction start: use clock_timestamp()
        // to reject a paused worker even when row-lock wait was prolonged.
        this.clockSql = db != null && db.toLowerCase(java.util.Locale.ROOT).contains("postgresql")
                ? "SELECT clock_timestamp()" : null; // H2 freezes CURRENT_TIMESTAMP per transaction.
    }

    /** A lease identity is unique per acquisition; never reuse a fencing token. */
    public record Lease(UUID tenantId, UUID ownerId, long fencingToken, Instant expiresAt) {}

    /** Returns null when another owner holds an unexpired lease. */
    public Lease acquire(UUID tenantId, Duration duration) {
        validate(tenantId, duration);
        return newTransaction(() -> {
            LeaseRow row = lockTenant(tenantId);
            Instant now = dbNow();
            if (row.ownerId() != null && row.expiresAt().isAfter(now)) {
                return null;
            }
            long token = Math.addExact(row.token(), 1);
            UUID owner = UUID.randomUUID();
            Instant expiry = now.plus(duration);
            int changed = jdbc.update("""
                UPDATE security.kms_tenant_key
                   SET job_lease_owner=?, job_lease_until=?, job_fence_token=?,
                       row_version=row_version+1, updated_at=?
                 WHERE tenant_id=? AND job_fence_token=?
                """, owner, Timestamp.from(expiry), token, Timestamp.from(now),
                    tenantId, row.token());
            if (changed != 1) throw new IllegalStateException("KMS lease acquisition conflict");
            return new Lease(tenantId, owner, token, expiry);
        });
    }

    /** Renewal is allowed only before expiry, while the same owner and token are current. */
    public Lease renew(Lease lease, Duration duration) {
        validateLease(lease);
        validate(lease.tenantId(), duration);
        return newTransaction(() -> {
            LeaseRow row = lockTenant(lease.tenantId());
            Instant now = dbNow();
            requireCurrent(lease, row, now);
            Instant expiry = now.plus(duration);
            jdbc.update("""
                UPDATE security.kms_tenant_key
                   SET job_lease_until=?, row_version=row_version+1, updated_at=?
                 WHERE tenant_id=? AND job_lease_owner=? AND job_fence_token=?
                """, Timestamp.from(expiry), Timestamp.from(now),
                    lease.tenantId(), lease.ownerId(), lease.fencingToken());
            return new Lease(lease.tenantId(), lease.ownerId(), lease.fencingToken(), expiry);
        });
    }

    /**
     * Owns one complete DB transaction and locks the key row throughout a mutation.
     * The lease is checked both before work and immediately before transaction commit.
     * This provides fencing only for mutations performed via this callback/transaction.
     */
    public <T> T executeFenced(Lease lease, Function<JdbcTemplate, T> mutation) {
        validateLease(lease);
        if (mutation == null) throw new IllegalArgumentException("Mutation required");
        return newTransaction(() -> {
            LeaseRow row = lockTenant(lease.tenantId());
            requireCurrent(lease, row, dbNow());
            T result = mutation.apply(jdbc);
            // A worker that ran longer than its lease MUST roll back.
            requireCurrent(lease, row, dbNow());
            return result;
        });
    }

    /** Idempotent release; stale owners are never permitted to release newer leases. */
    public boolean release(Lease lease) {
        validateLease(lease);
        return newTransaction(() -> {
            LeaseRow row = lockTenant(lease.tenantId());
            if (!sameIdentity(lease, row)) return false;
            Instant now = dbNow();
            return jdbc.update("""
                UPDATE security.kms_tenant_key
                   SET job_lease_owner=NULL, job_lease_until=NULL,
                       row_version=row_version+1, updated_at=?
                 WHERE tenant_id=? AND job_lease_owner=? AND job_fence_token=?
                """, Timestamp.from(now), lease.tenantId(),
                    lease.ownerId(), lease.fencingToken()) == 1;
        });
    }

    private LeaseRow lockTenant(UUID tenantId) {
        return jdbc.query("""
            SELECT job_lease_owner, job_lease_until, job_fence_token
              FROM security.kms_tenant_key WHERE tenant_id=? FOR UPDATE
            """, rs -> {
                if (!rs.next()) throw new IllegalStateException("Tenant key not provisioned");
                UUID owner = rs.getObject(1, UUID.class);
                OffsetDateTime until = rs.getObject(2, OffsetDateTime.class);
                long token = rs.getLong(3);
                return new LeaseRow(owner, until == null ? null : until.toInstant(), token);
            }, tenantId);
    }

    private Instant dbNow() {
        // Test-only H2 fallback: PostgreSQL clock_timestamp() is the sole distributed authority.
        if (clockSql == null) return Instant.now();
        OffsetDateTime timestamp = jdbc.queryForObject(clockSql, OffsetDateTime.class);
        if (timestamp == null) throw new IllegalStateException("No authoritative DB clock");
        return timestamp.toInstant();
    }

    private void requireCurrent(Lease lease, LeaseRow row, Instant now) {
        if (!sameIdentity(lease, row) || row.expiresAt() == null
                || !row.expiresAt().isAfter(now)) {
            throw new IllegalStateException("KMS job fencing token expired or superseded");
        }
    }

    private boolean sameIdentity(Lease lease, LeaseRow row) {
        return lease.fencingToken() == row.token() && lease.ownerId().equals(row.ownerId());
    }

    private void validate(UUID tenantId, Duration duration) {
        if (tenantId == null || duration == null || duration.compareTo(MIN_LEASE) < 0
                || duration.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("Tenant ID and 1s..15m lease required");
        }
    }

    private void validateLease(Lease lease) {
        if (lease == null || lease.tenantId() == null || lease.ownerId() == null
                || lease.fencingToken() <= 0) {
            throw new IllegalArgumentException("Valid lease required");
        }
    }

    private <T> T newTransaction(java.util.function.Supplier<T> operation) {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> operation.get());
    }

    private record LeaseRow(UUID ownerId, Instant expiresAt, long token) {}
}
