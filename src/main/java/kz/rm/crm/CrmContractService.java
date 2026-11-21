package kz.rm.crm;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;

/** Verified actor contract application boundary; no REST/controller or PII exposure. */
@Service
public class CrmContractService {
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_./-]{0,79}");
    private final CustomerContractRepository contracts;
    private final CrmCustomerRepository customers;
    private final TenantAuthorization authorization;

    public record Contract(UUID tenantId, UUID id, UUID customerId, String contractCode,
            LocalDate startsOn, LocalDate endsOn, CustomerContractEntity.Status status,
            ContractExpiryPolicy.Indicator expiryIndicator, long rowVersion) {}
    public CrmContractService(CustomerContractRepository contracts, CrmCustomerRepository customers,
                              TenantAuthorization authorization) {
        this.contracts = contracts; this.customers = customers; this.authorization = authorization;
    }
    private void authorize(VerifiedActor actor, UUID tenant, DomainPermission permission) {
        if (actor == null || tenant == null) throw new SecurityException("Verified actor and tenant required");
        authorization.requireForCommand(actor.userId(), tenant, permission);
    }
    private static void dates(LocalDate from, LocalDate to) {
        if (from == null || (to != null && to.isBefore(from)))
            throw new IllegalArgumentException("Invalid contract period");
    }
    private static Contract view(CustomerContractEntity c, LocalDate asOf, int warningDays) {
        return new Contract(c.getTenantId(), c.getId(), c.getCustomerId(), c.getContractCode(),
                c.getStartsOn(), c.getEndsOn(), c.getStatus(),
                ContractExpiryPolicy.evaluate(c.getStatus(), c.getStartsOn(), c.getEndsOn(), asOf, warningDays),
                c.getRowVersion());
    }
    @Transactional
    public Contract create(VerifiedActor actor, UUID tenant, UUID customer, String code,
                           LocalDate starts, LocalDate ends, LocalDate asOf) {
        authorize(actor, tenant, DomainPermission.CRM_WRITE);
        dates(starts, ends);
        if (asOf == null || code == null || !CODE.matcher(code).matches())
            throw new IllegalArgumentException("Invalid contract code or reference date");
        var client = customers.findByTenantIdAndId(tenant, customer)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found in tenant"));
        if (client.getStatus() != CrmCustomerEntity.Status.ACTIVE)
            throw new IllegalStateException("Customer is archived");
        return view(contracts.saveAndFlush(CustomerContractEntity.create(tenant, customer, code, starts, ends,
                Instant.now())), asOf, 30);
    }
    @Transactional
    public Contract changeDates(VerifiedActor actor, UUID tenant, UUID id, LocalDate starts,
                                LocalDate ends, long expectedVersion, LocalDate asOf) {
        authorize(actor, tenant, DomainPermission.CRM_WRITE);
        dates(starts, ends);
        if (asOf == null) throw new IllegalArgumentException("asOf required");
        var contract = contract(tenant, id, expectedVersion);
        if (contract.getStatus() == CustomerContractEntity.Status.CANCELLED
                || contract.getStatus() == CustomerContractEntity.Status.CLOSED)
            throw new IllegalStateException("Terminal contract is immutable");
        contract.dates(starts, ends, Instant.now());
        return view(contracts.saveAndFlush(contract), asOf, 30);
    }
    @Transactional
    public Contract changeStatus(VerifiedActor actor, UUID tenant, UUID id, CustomerContractEntity.Status next,
                                 long expectedVersion, LocalDate asOf) {
        authorize(actor, tenant, DomainPermission.CRM_WRITE);
        if (next == null || asOf == null) throw new IllegalArgumentException("Status and asOf required");
        var c = contract(tenant, id, expectedVersion);
        var current = c.getStatus();
        if (current != next) {
            boolean allowed = (current == CustomerContractEntity.Status.DRAFT &&
                    (next == CustomerContractEntity.Status.ACTIVE || next == CustomerContractEntity.Status.CANCELLED))
                    || (current == CustomerContractEntity.Status.ACTIVE &&
                    (next == CustomerContractEntity.Status.CLOSED || next == CustomerContractEntity.Status.CANCELLED));
            if (!allowed) throw new IllegalStateException("Invalid contract state transition");
            c.status(next, Instant.now());
        }
        return view(contracts.saveAndFlush(c), asOf, 30);
    }
    private CustomerContractEntity contract(UUID tenant, UUID id, long expectedVersion) {
        var c = contracts.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found in tenant"));
        if (c.getRowVersion() != expectedVersion) throw new IllegalStateException("Stale contract revision");
        return c;
    }
    @Transactional(readOnly = true)
    public Contract get(VerifiedActor actor, UUID tenant, UUID id, LocalDate asOf, int warningDays) {
        authorize(actor, tenant, DomainPermission.CRM_READ);
        if (asOf == null) throw new IllegalArgumentException("asOf required");
        return view(contracts.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found in tenant")), asOf, warningDays);
    }
    @Transactional(readOnly = true)
    public Page<Contract> list(VerifiedActor actor, UUID tenant, UUID customerId,
                               LocalDate asOf, int warningDays, int page, int size) {
        authorize(actor, tenant, DomainPermission.CRM_READ);
        if (asOf == null || page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException("Invalid contract list request");
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id")));
        return (customerId == null ? contracts.findByTenantId(tenant, pageable) :
                contracts.findByTenantIdAndCustomerId(tenant, customerId, pageable))
                .map(c -> view(c, asOf, warningDays));
    }
}
