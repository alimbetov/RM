package kz.rm.crm;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite tenant-local identity; never resolve a category by UUID alone. */
public final class CrmCategoryKey implements Serializable {
    private UUID tenantId;
    private UUID id;
    public CrmCategoryKey() {}
    public CrmCategoryKey(UUID tenantId, UUID id) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.id = Objects.requireNonNull(id);
    }
    public UUID getTenantId() { return tenantId; }
    public UUID getId() { return id; }
    @Override public boolean equals(Object obj) {
        return this == obj || (obj instanceof CrmCategoryKey other
                && Objects.equals(tenantId, other.tenantId) && Objects.equals(id, other.id));
    }
    @Override public int hashCode() { return Objects.hash(tenantId, id); }
}
