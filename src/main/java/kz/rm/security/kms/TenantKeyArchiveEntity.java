package kz.rm.security.kms;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kms_tenant_key_archive", schema = "security")
@IdClass(TenantKeyArchiveId.class)
public class TenantKeyArchiveEntity {
    @Id @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Id @Column(name = "key_generation", nullable = false) private Long keyGeneration;
    @Column(name = "wrapped_key", nullable = false) private byte[] wrappedKey;
    @Column(name = "wrapping_key_id", nullable = false, length = 128) private String wrappingKeyId;
    @Column(name = "archived_at", nullable = false) private Instant archivedAt;
    protected TenantKeyArchiveEntity() {}
}
