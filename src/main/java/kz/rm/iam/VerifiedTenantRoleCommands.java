package kz.rm.iam;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

/**
 * Minimal trusted-principal application boundary for tenant role administration.
 * Owner admission, tenant isolation and immutable evidence remain in TenantRoleService.
 * No HTTP/controller implementation is implied.
 */
@Service
public class VerifiedTenantRoleCommands {
    private final TenantRoleService roles;

    public VerifiedTenantRoleCommands(TenantRoleService roles) {
        this.roles = roles;
    }

    public TenantRoleService.TenantRole createRole(VerifiedActor owner, UUID tenantId,
                                                   String code, String displayName) {
        return roles.createRole(actor(owner),tenantId,code,displayName);
    }

    public void grant(VerifiedActor owner, UUID tenantId, UUID roleId, DomainPermission permission) {
        roles.grant(actor(owner),tenantId,roleId,permission);
    }

    public void revoke(VerifiedActor owner, UUID tenantId, UUID roleId, DomainPermission permission) {
        roles.revoke(actor(owner),tenantId,roleId,permission);
    }

    public void assign(VerifiedActor owner, UUID tenantId, UUID employeeId, UUID roleId) {
        roles.assign(actor(owner),tenantId,employeeId,roleId);
    }

    public void unassign(VerifiedActor owner, UUID tenantId, UUID employeeId, UUID roleId) {
        roles.unassign(actor(owner),tenantId,employeeId,roleId);
    }

    public void activateRole(VerifiedActor owner, UUID tenantId, UUID roleId, boolean active) {
        roles.setRoleActive(actor(owner),tenantId,roleId,active);
    }

    public List<TenantRoleService.TenantRole> listRoles(VerifiedActor owner, UUID tenantId) {
        return roles.listRoles(actor(owner),tenantId);
    }

    public void enable(VerifiedActor owner, UUID tenantId) {
        roles.enable(actor(owner),tenantId);
    }

    public List<DomainPermission> listGrants(VerifiedActor owner, UUID tenantId, UUID roleId) {
        return roles.listGrants(actor(owner),tenantId,roleId);
    }

    public List<TenantRoleService.RoleAssignment> listAssignments(VerifiedActor owner, UUID tenantId) {
        return roles.listAssignments(actor(owner),tenantId);
    }

    private UUID actor(VerifiedActor owner) {
        if (owner == null) throw new SecurityException("Verified organization owner required");
        return owner.userId();
    }
}
