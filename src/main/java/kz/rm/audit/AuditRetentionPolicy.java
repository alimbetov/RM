package kz.rm.audit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Approved AUDIT retention policy. This is NOT a retention policy for invoice,
 * payment, consent, inventory ledger or customer-order evidence.
 */
public record AuditRetentionPolicy(UUID tenantId, int years, boolean enabled, Instant approvedAt) {
    public AuditRetentionPolicy {
        if (tenantId == null || years < 3 || years > 5)
            throw new IllegalArgumentException("Retention requires tenant and 3-5 calendar years");
        if (enabled && approvedAt == null)
            throw new IllegalArgumentException("Enabled retention requires approval");
    }

    public Instant cutoff(Instant now) {
        if (now == null) throw new IllegalArgumentException("Current instant required");
        return ZonedDateTime.ofInstant(now, ZoneOffset.UTC).minusYears(years).toInstant();
    }

    public boolean expired(Instant occurredAt, Instant now, boolean legalHold) {
        if (occurredAt == null) throw new IllegalArgumentException("Event time required");
        return enabled && !legalHold && occurredAt.isBefore(cutoff(now));
    }
}
