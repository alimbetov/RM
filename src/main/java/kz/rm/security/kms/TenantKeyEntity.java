package kz.rm.security.kms;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_key", schema = "security")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantKeyEntity {

    @EmbeddedId
    private TenantKeyId id;

    @Column(name = "wrapped_key", nullable = false)
    private byte[] wrappedKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private KeyStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "rotate_after", nullable = false)
    private Instant rotateAfter;

    @Column(name = "wrapping_key_id", nullable = false, length = 128)
    private String wrappingKeyId;

    // Cross-database UNIQUE(tenant_id, active_slot) allows one ACTIVE
    // and any number of DECRYPT_ONLY / REVOKED keys.
    @Column(name = "active_slot")
    private Short activeSlot;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    public static TenantKeyEntity active(UUID tenantId, int version,
            byte[] wrappedKey, String wrappingKeyId,
            Instant createdAt, Instant rotateAfter) {
        TenantKeyEntity key = new TenantKeyEntity();
        key.id = new TenantKeyId(tenantId, version);
        key.wrappedKey = wrappedKey.clone();
        key.wrappingKeyId = wrappingKeyId;
        key.createdAt = createdAt;
        key.rotateAfter = rotateAfter;
        key.status = KeyStatus.ACTIVE;
        key.activeSlot = (short) 1;
        return key;
    }

    public void markDecryptOnly() {
        if (status != KeyStatus.ACTIVE) {
            throw new IllegalStateException("Key is not ACTIVE");
        }
        this.status = KeyStatus.DECRYPT_ONLY;
        this.activeSlot = null;
    }
}
