package kz.rm.iam;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/**
 * Central authorization decision for implemented organization-domain operations.
 *
 * Actor ID is an INTERNAL trusted principal identifier, not a client-provided
 * claim. A future HTTP/queue adapter must authenticate the caller before use.
 * This class is not by itself an OIDC gateway or per-row PostgreSQL RLS.
 */
@Service
@RequiredArgsConstructor
public class TenantAuthorization {
    private final WorkspaceRepository workspaces;
    private final OrganizationMembershipRepository memberships;
    private final UserAccountRepository users;

    public record Decision(UUID actorId, UUID tenantId, UUID organizationId,
                           OrganizationMembershipEntity.Role role, DomainPermission permission) {}

    public Decision require(UUID actorId, UUID tenantId, DomainPermission permission) {
        if (actorId == null || tenantId == null || permission == null)
            throw new SecurityException("Authenticated actor, tenant and permission required");
        var actor = users.findById(actorId)
            .orElseThrow(() -> new SecurityException("Actor unknown"));
        if (actor.getStatus() != UserAccountEntity.Status.ACTIVE)
            throw new SecurityException("Actor inactive");
        var workspace = workspaces.findById(tenantId)
            .orElseThrow(() -> new SecurityException("Tenant unknown"));
        if (workspace.getKind() != WorkspaceEntity.Kind.ORGANIZATION
                || workspace.getStatus() != WorkspaceEntity.Status.ACTIVE
                || workspace.getOrganizationId() == null)
            throw new SecurityException("Organization tenant must be active");
        var membership = memberships.findByOrganizationIdAndUserId(
                workspace.getOrganizationId(),actorId)
            .orElseThrow(() -> new SecurityException("Active membership required"));
        if (membership.getStatus() != OrganizationMembershipEntity.Status.ACTIVE)
            throw new SecurityException("Inactive membership");
        if (!permitted(permission,membership.getRole()))
            throw new SecurityException("Domain permission denied");
        return new Decision(actorId,tenantId,workspace.getOrganizationId(),membership.getRole(),permission);
    }

    private boolean permitted(DomainPermission permission, OrganizationMembershipEntity.Role role) {
        if (role == null) return false;
        return switch (permission) {
            case CRM_READ -> true;
            case CRM_PII_READ -> role == OrganizationMembershipEntity.Role.OWNER
                || role == OrganizationMembershipEntity.Role.ADMIN
                || role == OrganizationMembershipEntity.Role.MANAGER;
            case CRM_WRITE, ORGANIZATION_LOCATION_WRITE, INVENTORY_SETTINGS_MANAGE ->
                role == OrganizationMembershipEntity.Role.OWNER
                || role == OrganizationMembershipEntity.Role.ADMIN
                || role == OrganizationMembershipEntity.Role.MANAGER;
        };
    }

    /** Backward-compatible internal entry point for the existing ORG service. */
    public UUID requireOrganizationWriter(UUID actorId,UUID tenantId) {
        return require(actorId,tenantId,DomainPermission.ORGANIZATION_LOCATION_WRITE).organizationId();
    }
}
