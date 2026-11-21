package kz.rm.security.kms;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Portable JPA IdClass: public no-arg constructor required by the JPA contract. */
public class TenantKeyArchiveId implements Serializable {
    private UUID tenantId;
    private Long keyGeneration;

    public TenantKeyArchiveId() {}

    public TenantKeyArchiveId(UUID tenantId, Long keyGeneration) {
        this.tenantId = tenantId;
        this.keyGeneration = keyGeneration;
    }

    public UUID getTenantId() { return tenantId; }
    public Long getKeyGeneration() { return keyGeneration; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TenantKeyArchiveId id)) return false;
        return Objects.equals(tenantId, id.tenantId)
                && Objects.equals(keyGeneration, id.keyGeneration);
    }

    @Override public int hashCode() {
        return Objects.hash(tenantId, keyGeneration);
    }
}
