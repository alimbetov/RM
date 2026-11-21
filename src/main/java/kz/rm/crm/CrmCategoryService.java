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
import java.util.UUID;
import java.util.regex.Pattern;

/** Category commands and reads; future controllers may call only this verified-actor boundary. */
@Service
public class CrmCategoryService {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,39}");
    private final CustomerCategoryRepository categories;
    private final CrmCustomerRepository customers;
    private final TenantAuthorization authorization;

    public record Category(UUID tenantId, UUID id, String code, String name,
                           CustomerCategoryEntity.Status status, long rowVersion) {}
    public CrmCategoryService(CustomerCategoryRepository categories, CrmCustomerRepository customers,
                              TenantAuthorization authorization) {
        this.categories = categories; this.customers = customers; this.authorization = authorization;
    }
    private void authorize(VerifiedActor actor, UUID tenant, DomainPermission permission) {
        if (actor == null || tenant == null) throw new SecurityException("Verified actor and tenant required");
        authorization.requireForCommand(actor.userId(), tenant, permission);
    }
    private static String label(String text, int max) {
        if (text == null || text.isBlank() || text.length() > max)
            throw new IllegalArgumentException("Invalid category name");
        return text.strip();
    }
    private static Category view(CustomerCategoryEntity e) {
        return new Category(e.getTenantId(), e.getId(), e.getCode(), e.getName(),
                e.getStatus(), e.getRowVersion());
    }
    @Transactional
    public Category create(VerifiedActor actor, UUID tenant, String code, String name) {
        authorize(actor, tenant, DomainPermission.CRM_WRITE);
        if (code == null || !CODE.matcher(code).matches())
            throw new IllegalArgumentException("Category code must be uppercase ASCII");
        return view(categories.saveAndFlush(CustomerCategoryEntity.create(tenant, code, label(name, 120), Instant.now())));
    }
    @Transactional
    public Category rename(VerifiedActor actor, UUID tenant, UUID id, String name, long expectedVersion) {
        authorize(actor, tenant, DomainPermission.CRM_WRITE);
        var category = categories.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> new IllegalArgumentException("Category not found in tenant"));
        if (category.getRowVersion() != expectedVersion) throw new IllegalStateException("Stale category revision");
        if (category.getStatus() != CustomerCategoryEntity.Status.ACTIVE)
            throw new IllegalStateException("Archived category is immutable");
        category.rename(label(name, 120), Instant.now());
        return view(categories.saveAndFlush(category));
    }
    @Transactional
    public Category archive(VerifiedActor actor, UUID tenant, UUID id, long expectedVersion) {
        authorize(actor, tenant, DomainPermission.CRM_WRITE);
        var category = categories.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> new IllegalArgumentException("Category not found in tenant"));
        if (category.getRowVersion() != expectedVersion) throw new IllegalStateException("Stale category revision");
        if (category.getStatus() != CustomerCategoryEntity.Status.ARCHIVED)
            category.archive(Instant.now());
        return view(categories.saveAndFlush(category));
    }
    @Transactional
    public void assignPrimary(VerifiedActor actor, UUID tenant, UUID customerId, UUID categoryId) {
        authorize(actor, tenant, DomainPermission.CRM_WRITE);
        if (categoryId != null) {
            var category = categories.findByTenantIdAndId(tenant, categoryId)
                    .orElseThrow(() -> new IllegalArgumentException("Category not found in tenant"));
            if (category.getStatus() != CustomerCategoryEntity.Status.ACTIVE)
                throw new IllegalStateException("Cannot assign archived category");
        }
        var customer = customers.findByTenantIdAndId(tenant, customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found in tenant"));
        if (customer.getStatus() != CrmCustomerEntity.Status.ACTIVE)
            throw new IllegalStateException("Customer is archived");
        // Composite FK in PostgreSQL is the final cross-tenant race safety net.
        customer.setPrimaryCategoryId(categoryId);
        customers.saveAndFlush(customer);
    }
    @Transactional
    public Category get(VerifiedActor actor, UUID tenant, UUID id) {
        authorize(actor, tenant, DomainPermission.CRM_READ);
        return view(categories.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> new IllegalArgumentException("Category not found in tenant")));
    }
    @Transactional
    public Page<Category> list(VerifiedActor actor, UUID tenant, boolean activeOnly, int page, int size) {
        authorize(actor, tenant, DomainPermission.CRM_READ);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        var pageable = PageRequest.of(page, size, Sort.by("code").ascending().and(Sort.by("id")));
        return (activeOnly ? categories.findByTenantIdAndStatus(tenant, CustomerCategoryEntity.Status.ACTIVE, pageable)
                : categories.findByTenantId(tenant, pageable)).map(CrmCategoryService::view);
    }
}
