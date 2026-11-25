package kz.rm.crm;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Contract metadata only. No personal names, addresses, notes or document bodies.
 * Sensitive contract documents belong in the tenant-protected file service.
 */
@Entity
@Table(name = "customer_contract", schema = "crm")
@IdClass(CrmContractKey.class)
public class CustomerContractEntity {
    public enum Status { DRAFT, ACTIVE, CLOSED, CANCELLED }
    @Id @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Id @Column(name = "id", nullable = false) private UUID id;
    @Column(name = "customer_id", nullable = false) private UUID customerId;
    @Column(name = "contract_code", nullable = false, length = 80) private String contractCode;
    @Column(name = "starts_on", nullable = false) private LocalDate startsOn;
    @Column(name = "ends_on") private LocalDate endsOn;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20) private Status status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    protected CustomerContractEntity() {}
    static CustomerContractEntity create(UUID tenant, UUID customer, String code,
                                         LocalDate start, LocalDate end, Instant now) {
        var contract = new CustomerContractEntity();
        contract.tenantId = tenant;
        contract.id = UUID.randomUUID();
        contract.customerId = customer;
        contract.contractCode = code;
        contract.startsOn = start;
        contract.endsOn = end;
        contract.status = Status.DRAFT;
        contract.createdAt = now;
        contract.updatedAt = now;
        return contract;
    }
    void dates(LocalDate start, LocalDate end, Instant now) {
        this.startsOn = start;
        this.endsOn = end;
        this.updatedAt = now;
    }
    void status(Status next, Instant now) { this.status = next; this.updatedAt = now; }
    public UUID getTenantId() { return tenantId; }
    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public String getContractCode() { return contractCode; }
    public LocalDate getStartsOn() { return startsOn; }
    public LocalDate getEndsOn() { return endsOn; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
