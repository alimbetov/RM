package kz.rm.crm;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Non-PII classification metadata; never reuse a category across tenants. */
@Entity
@Table(name = "customer_category", schema = "crm")
@IdClass(CrmCategoryKey.class)
public class CustomerCategoryEntity {
    public enum Status { ACTIVE, ARCHIVED }
    @Id @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Id @Column(name = "id", nullable = false) private UUID id;
    @Column(name = "code", nullable = false, length = 40) private String code;
    @Column(name = "name", nullable = false, length = 120) private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20) private Status status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    protected CustomerCategoryEntity() {}
    static CustomerCategoryEntity create(UUID tenant, String code, String name, Instant now) {
        var category = new CustomerCategoryEntity();
        category.tenantId = tenant;
        category.id = UUID.randomUUID();
        category.code = code;
        category.name = name;
        category.status = Status.ACTIVE;
        category.createdAt = now;
        category.updatedAt = now;
        return category;
    }
    void rename(String newName, Instant now) { this.name = newName; this.updatedAt = now; }
    void archive(Instant now) { this.status = Status.ARCHIVED; this.updatedAt = now; }
    public UUID getTenantId() { return tenantId; }
    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
