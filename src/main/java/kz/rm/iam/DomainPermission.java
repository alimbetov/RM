package kz.rm.iam;

import kz.rm.architecture.DomainModule;

/**
 * Explicit internal permissions; an unregistered domain operation is denied.
 * Transport adapters MUST derive actor identity from a verified principal.
 */
public enum DomainPermission {
    CRM_READ(DomainModule.CRM),
    CRM_PII_READ(DomainModule.CRM),
    CRM_WRITE(DomainModule.CRM),
    ORGANIZATION_LOCATION_WRITE(DomainModule.ORG),
    INVENTORY_SETTINGS_MANAGE(DomainModule.INVENTORY);

    private final DomainModule module;
    DomainPermission(DomainModule module) { this.module = module; }
    public DomainModule module() { return module; }
}
