package kz.rm.audit;
import java.time.Instant;
import java.util.UUID;
/** Evidence is metadata-only: never put email, tokens, PII or DEKs in an audit event. */
public record AuditAction(UUID tenantId, UUID actorId, String actionCode,
                          UUID entityId, Instant occurredAt) {
    public AuditAction {
        if(tenantId==null||actorId==null||entityId==null||occurredAt==null
                ||actionCode==null||!actionCode.matches("[A-Z][A-Z0-9_]{2,79}"))
            throw new IllegalArgumentException("Invalid audit metadata");
    }
}
