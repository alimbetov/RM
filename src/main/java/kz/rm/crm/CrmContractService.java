package kz.rm.crm;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final CrmHistoryService history;
    private final JdbcTemplate jdbc;

    public record Contract(UUID tenantId, UUID id, UUID customerId, String contractCode,
            LocalDate startsOn, LocalDate endsOn, CustomerContractEntity.Status status,
            ContractExpiryPolicy.Indicator expiryIndicator, long rowVersion) {}
    public CrmContractService(CustomerContractRepository contracts, CrmCustomerRepository customers,
                              TenantAuthorization authorization, CrmHistoryService history, JdbcTemplate jdbc) {
        this.contracts = contracts; this.customers = customers; this.authorization = authorization;
        this.history = history; this.jdbc = jdbc;
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
        jdbc.queryForObject("SELECT id FROM crm.customer WHERE tenant_id=? AND id=? FOR UPDATE", UUID.class, tenant, customer);
        var client = customers.findByTenantIdAndId(tenant, customer)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found in tenant"));
        if (client.getStatus() != CrmCustomerEntity.Status.ACTIVE)
            throw new IllegalStateException("Customer is archived");
        var created=contracts.saveAndFlush(CustomerContractEntity.create(tenant, customer, code, starts, ends,
                Instant.now()));
        history.contract(tenant,created.getId(),actor.userId(),"CREATED",
                created.getRowVersion(),created.getStartsOn(),created.getEndsOn(),created.getStatus());
        return view(created,asOf,30);
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
        captureBaselineIfMissing(contract, actor.userId());
        contract.dates(starts, ends, Instant.now());
        var saved=contracts.saveAndFlush(contract);
        history.contract(tenant,id,actor.userId(),"DATES_CHANGED",saved.getRowVersion(),
                saved.getStartsOn(),saved.getEndsOn(),saved.getStatus());
        return view(saved, asOf, 30);
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
            if (next == CustomerContractEntity.Status.ACTIVE) {
                // Same customer lock ordering as archiveCustomer and create.
                String customerStatus = jdbc.queryForObject("""
                    SELECT status FROM crm.customer WHERE tenant_id=? AND id=? FOR UPDATE
                    """, String.class, tenant, c.getCustomerId());
                if (!"ACTIVE".equals(customerStatus))
                    throw new IllegalStateException("Cannot activate contract for archived customer");
            }
            captureBaselineIfMissing(c, actor.userId());
            c.status(next, Instant.now());
            var saved=contracts.saveAndFlush(c);
            history.contract(tenant,id,actor.userId(),"STATUS_CHANGED",saved.getRowVersion(),
                    saved.getStartsOn(),saved.getEndsOn(),saved.getStatus());
            return view(saved, asOf, 30);
        }
        return view(c,asOf,30);
    }

    /**
     * Renewal changes the effective end date of an ACTIVE contract but preserves
     * all preceding terms in the immutable revision table. Never resurrect a CLOSED contract.
     */
    @Transactional
    public Contract renew(VerifiedActor actor, UUID tenant, UUID id, LocalDate extendedEnd,
                          long expectedVersion, LocalDate asOf) {
        authorize(actor,tenant,DomainPermission.CRM_WRITE);
        if(extendedEnd==null || asOf==null) throw new IllegalArgumentException("Renewal end/asOf required");
        var c=contract(tenant,id,expectedVersion);
        if(c.getStatus()!=CustomerContractEntity.Status.ACTIVE || c.getEndsOn()==null
                || !extendedEnd.isAfter(c.getEndsOn()))
            throw new IllegalStateException("Renewal requires active fixed-term contract and later end");
        captureBaselineIfMissing(c,actor.userId());
        c.dates(c.getStartsOn(),extendedEnd,Instant.now());
        var saved=contracts.saveAndFlush(c);
        history.contract(tenant,id,actor.userId(),"RENEWED",saved.getRowVersion(),
                saved.getStartsOn(),saved.getEndsOn(),saved.getStatus());
        return view(saved,asOf,30);
    }


    private void captureBaselineIfMissing(CustomerContractEntity c, UUID actorId) {
        Integer existing = jdbc.queryForObject("""
            SELECT count(*) FROM crm.contract_revision
            WHERE tenant_id=? AND contract_id=?
            """,Integer.class,c.getTenantId(),c.getId());
        if(existing != null && existing == 0) {
            history.contract(c.getTenantId(),c.getId(),actorId,"BASELINE_CAPTURED",
                    c.getRowVersion(),c.getStartsOn(),c.getEndsOn(),c.getStatus());
        }
    }

    private CustomerContractEntity contract(UUID tenant, UUID id, long expectedVersion) {
        var c = contracts.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found in tenant"));
        if (c.getRowVersion() != expectedVersion) throw new IllegalStateException("Stale contract revision");
        return c;
    }
    @Transactional
    public Contract get(VerifiedActor actor, UUID tenant, UUID id, LocalDate asOf, int warningDays) {
        authorize(actor, tenant, DomainPermission.CRM_READ);
        if (asOf == null) throw new IllegalArgumentException("asOf required");
        return view(contracts.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found in tenant")), asOf, warningDays);
    }
    @Transactional
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
