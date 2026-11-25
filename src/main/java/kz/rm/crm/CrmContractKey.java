package kz.rm.crm;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite tenant-local contract identity. */
public final class CrmContractKey implements Serializable {
    private UUID tenantId;
    private UUID id;
    public CrmContractKey() {}
    public CrmContractKey(UUID tenantId, UUID id) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.id = Objects.requireNonNull(id);
    }
    public UUID getTenantId() { return tenantId; }
    public UUID getId() { return id; }
    @Override public boolean equals(Object obj) {
        return this == obj || (obj instanceof CrmContractKey other
                && Objects.equals(tenantId, other.tenantId) && Objects.equals(id, other.id));
    }
    @Override public int hashCode() { return Objects.hash(tenantId, id); }
}
