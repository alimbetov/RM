package kz.rm.architecture;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical RM bounded-context registry.
 *
 * DOMAIN REGISTERED != persistence implemented or production-qualified.\n * Implementation progress is tracked in docs/spec/RM-DOMAIN-IMPLEMENTATION-PLAN-R1.md,\n * NOT by a static boolean attached to the enum.
 * New tables must start with module prefix regardless of PostgreSQL schema.
 */
public enum DomainModule {
    IAM("iam", "kz.rm.iam", "Identity, access, tenant ownership"),
    ORG("org", "kz.rm.organization", "Organizations and business locations"),
    GEO("geo", "kz.rm.geo", "KATO geographic reference"),
    FILES("file", "kz.rm.files", "Encrypted file metadata and provider adapter"),
    CRM("crm", "kz.rm.crm", "Customers, contacts, consent"),
    CATALOG("cat", "kz.rm.catalog", "Items, categories, SKU and price lists"),
    SALES("sales", "kz.rm.sales", "Orders, lines, estimates and status history"),
    INVENTORY("inv", "kz.rm.inventory", "Balance, movement, reservations"),
    FINANCE("fin", "kz.rm.finance", "Invoices, payments, allocations"),
    MARKETING("mkt", "kz.rm.marketing", "Campaigns, consent-gated delivery"),
    TASKS("task", "kz.rm.tasks", "Tasks and activity"),
    AUDIT("audit", "kz.rm.audit", "Sanitized immutable evidence"),
    KMS("kms", "kz.rm.security.kms", "Tenant encryption keys and fenced operations"),
    SUBSCRIPTION("sub", "kz.rm.subscription", "Plans and tenant entitlements"),
    STOREFRONT("store", "kz.rm.storefront", "Public seller storefront settings");

    private final String prefix;
    private final String javaPackage;
    private final String responsibility;

    DomainModule(String prefix, String javaPackage, String responsibility) {
        this.prefix = prefix;
        this.javaPackage = javaPackage;
        this.responsibility = responsibility;
    }
    public String prefix() { return prefix; }
    public String javaPackage() { return javaPackage; }
    public String responsibility() { return responsibility; }
    public String table(String entityName) {
        if (entityName == null || !entityName.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("snake_case entity required");
        if (entityName.startsWith(prefix + "_"))
            throw new IllegalArgumentException("Do not repeat module prefix");
        String tableName = prefix + "_" + entityName;
        if (tableName.length() > 63)
            throw new IllegalArgumentException("PostgreSQL table identifier exceeds 63 ASCII bytes");
        return tableName;
    }
    public static List<DomainModule> modules() { return List.of(values()); }
    public static void validateRegistry() {
        Set<String> names = modules().stream().map(DomainModule::prefix).collect(Collectors.toSet());
        Set<String> packages = modules().stream().map(DomainModule::javaPackage).collect(Collectors.toSet());
        if (names.size() != values().length || packages.size() != values().length)
            throw new IllegalStateException("Duplicate module prefix or package");
    }
}
