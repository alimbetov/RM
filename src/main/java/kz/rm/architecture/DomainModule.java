package kz.rm.architecture;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical RM bounded-context registry.
 *
 * DOMAIN REGISTERED != persistence implemented or production-qualified.
 * New tables must start with module prefix regardless of PostgreSQL schema.
 */
public enum DomainModule {
    IAM("iam", "kz.rm.iam", "Identity, access, tenant ownership", true),
    ORG("org", "kz.rm.organization", "Organizations and business locations", true),
    GEO("geo", "kz.rm.geo", "KATO geographic reference", true),
    FILES("file", "kz.rm.files", "Encrypted file metadata and provider adapter", false),
    CRM("crm", "kz.rm.crm", "Customers, contacts, consent", true),
    CATALOG("cat", "kz.rm.catalog", "Items, categories, SKU and price lists", false),
    SALES("sales", "kz.rm.sales", "Orders, lines, estimates and status history", true),
    INVENTORY("inv", "kz.rm.inventory", "Balance, movement, reservations", false),
    FINANCE("fin", "kz.rm.finance", "Invoices, payments, allocations", false),
    MARKETING("mkt", "kz.rm.marketing", "Campaigns, consent-gated delivery", false),
    TASKS("task", "kz.rm.tasks", "Tasks and activity", false),
    AUDIT("audit", "kz.rm.audit", "Sanitized immutable evidence", false),
    KMS("kms", "kz.rm.security.kms", "Tenant encryption keys and fenced operations", true),
    SUBSCRIPTION("sub", "kz.rm.subscription", "Plans and tenant entitlements", false),
    STOREFRONT("store", "kz.rm.storefront", "Public seller storefront settings", true);

    private final String prefix;
    private final String javaPackage;
    private final String responsibility;
    private final boolean existingImplementation;

    DomainModule(String prefix, String javaPackage, String responsibility, boolean existingImplementation) {
        this.prefix = prefix;
        this.javaPackage = javaPackage;
        this.responsibility = responsibility;
        this.existingImplementation = existingImplementation;
    }
    public String prefix() { return prefix; }
    public String javaPackage() { return javaPackage; }
    public String responsibility() { return responsibility; }
    public boolean hasExistingImplementation() { return existingImplementation; }
    public String table(String entityName) {
        if (entityName == null || !entityName.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("snake_case entity required");
        return prefix + "_" + entityName;
    }
    public static List<DomainModule> modules() { return List.of(values()); }
    public static void validateRegistry() {
        Set<String> names = modules().stream().map(DomainModule::prefix).collect(Collectors.toSet());
        Set<String> packages = modules().stream().map(DomainModule::javaPackage).collect(Collectors.toSet());
        if (names.size() != values().length || packages.size() != values().length)
            throw new IllegalStateException("Duplicate module prefix or package");
    }
}
