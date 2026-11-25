package kz.rm.crm;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Existing crm.customer primary key. Neither id alone nor tenant alone identifies
 * a customer for application persistence.
 */
public final class CrmCustomerKey implements Serializable {
    private UUID tenantId;
    private UUID id;

    public CrmCustomerKey() {}

    public CrmCustomerKey(UUID tenantId, UUID id) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.id = Objects.requireNonNull(id);
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getId() { return id; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CrmCustomerKey that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, id); }
}
