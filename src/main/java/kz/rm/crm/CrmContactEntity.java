package kz.rm.crm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata-only mapping of crm.contact (PERSIST-R1.1).
 *
 * Contact name, email and phone are encrypted in crm_pii.contact_secret,
 * and MUST NOT be mapped, logged, or included in generic entity projections.
 * Existing JDBC commands and searches remain authoritative pending separate
 * service-level write qualification.
 */
@Entity
@Table(name = "contact", schema = "crm")
@IdClass(CrmContactKey.class)
public class CrmContactEntity {
    public enum Status { ACTIVE, ARCHIVED }

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected CrmContactEntity() {}

    public UUID getTenantId() { return tenantId; }
    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public boolean isPrimary() { return primary; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public long getRowVersion() { return rowVersion; }
}
