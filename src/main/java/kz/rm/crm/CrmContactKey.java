package kz.rm.crm;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps crm.contact's (tenant_id, id) primary key.
 * The same contact UUID cannot be queried without its tenant context.
 */
public final class CrmContactKey implements Serializable {
    private UUID tenantId;
    private UUID id;

    public CrmContactKey() {}

    public CrmContactKey(UUID tenantId, UUID id) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.id = Objects.requireNonNull(id);
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getId() { return id; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CrmContactKey that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, id); }
}
