package kz.rm.iam;

import kz.rm.architecture.DomainModule;

/**
 * Explicit internal permissions; an unregistered domain operation is denied.
 * Transport adapters MUST derive actor identity from a verified principal.
 */
public enum DomainPermission {
    TENANT_RBAC_MANAGE(DomainModule.IAM),
    CRM_READ(DomainModule.CRM),
    ORGANIZATION_LOCATION_READ(DomainModule.ORG),
    INVENTORY_STOCK_READ(DomainModule.INVENTORY),
    SALES_ORDER_READ(DomainModule.SALES),
    SALES_ORDER_CONFIRM(DomainModule.SALES),
    SALES_ORDER_CANCEL(DomainModule.SALES),
    SALES_ORDER_FULFILL(DomainModule.SALES),
    INVENTORY_RECEIVE(DomainModule.INVENTORY),
    INVENTORY_ADJUST(DomainModule.INVENTORY),
    INVENTORY_RESERVE(DomainModule.INVENTORY),
    INVENTORY_RELEASE(DomainModule.INVENTORY),
    INVENTORY_FULFILL(DomainModule.INVENTORY),
    CRM_PII_READ(DomainModule.CRM),
    CRM_WRITE(DomainModule.CRM),
    ORGANIZATION_LOCATION_WRITE(DomainModule.ORG),
    INVENTORY_SETTINGS_MANAGE(DomainModule.INVENTORY),
    FILES_UPLOAD(DomainModule.FILES),
    FILES_READ(DomainModule.FILES),
    FILES_ATTACH(DomainModule.FILES),
    FILES_DETACH(DomainModule.FILES),
    FILES_DELETE(DomainModule.FILES),
    CATALOG_READ(DomainModule.CATALOG),
    CATALOG_WRITE(DomainModule.CATALOG),
    CATALOG_PRICE_MANAGE(DomainModule.CATALOG),
    CATALOG_PUBLISH(DomainModule.CATALOG);

    private final DomainModule module;
    DomainPermission(DomainModule module) { this.module = module; }
    public DomainModule module() { return module; }
}
