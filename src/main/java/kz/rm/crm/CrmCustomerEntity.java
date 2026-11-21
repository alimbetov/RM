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
 * CRM customer metadata aggregate mapping (PERSIST-R1.0).
 * A PERSON's name is encrypted in crm_pii.customer_secret and is deliberately
 * NOT mapped as an entity property. Do not expose this JPA class as a transport DTO.
 *
 * Existing JDBC command and query paths stay unchanged until separately qualified.
 */
@Entity
@Table(name = "customer", schema = "crm")
@IdClass(CrmCustomerKey.class)
public class CrmCustomerEntity {
    public enum Type { PERSON, COMPANY }
    public enum Status { ACTIVE, ARCHIVED }

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", nullable = false, length = 20)
    private Type customerType;

    /** COMPANY only. PERSON names must remain in tenant-encrypted secret storage. */
    @Column(name = "organization_label", length = 200)
    private String organizationLabel;

    @Column(name = "region_zone_id", length = 2)
    private String regionZoneId;

    @Column(name = "kato_code", length = 16)
    private String katoCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected CrmCustomerEntity() {}

    public UUID getTenantId() { return tenantId; }
    public UUID getId() { return id; }
    public Type getCustomerType() { return customerType; }
    public String getOrganizationLabel() { return organizationLabel; }
    public String getRegionZoneId() { return regionZoneId; }
    public String getKatoCode() { return katoCode; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public long getRowVersion() { return rowVersion; }
}
