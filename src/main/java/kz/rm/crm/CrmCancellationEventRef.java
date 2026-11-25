package kz.rm.crm;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Canonical CRM event identity; never inferred from task or interaction timestamps. */
public record CrmCancellationEventRef(
        UUID tenantId, UUID interactionId, UUID eventId,
        Instant occurredAt, long newVersion) {
    public CrmCancellationEventRef {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(interactionId, "interactionId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (newVersion < 1) throw new IllegalArgumentException("Cancellation event version must be positive");
    }
}
