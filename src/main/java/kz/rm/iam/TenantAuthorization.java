package kz.rm.iam;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/**
 * Internal IAM authorization gate. actorId MUST come from an authenticated
 * principal in a trusted adapter; it must never be accepted from HTTP input.
 */
@Service
@RequiredArgsConstructor
public class TenantAuthorization {
    private final WorkspaceRepository workspaces;
    private final OrganizationMembershipRepository memberships;
    private final UserAccountRepository users;

    public UUID requireOrganizationWriter(UUID actorId, UUID tenantId) {
        if (actorId == null || tenantId == null)
            throw new SecurityException("Authenticated actor and tenant required");
        var actor = users.findById(actorId).orElseThrow(() -> new SecurityException("Actor unknown"));
        if (actor.getStatus() != UserAccountEntity.Status.ACTIVE)
            throw new SecurityException("Actor inactive");
        var workspace = workspaces.findById(tenantId)
                .orElseThrow(() -> new SecurityException("Tenant unknown"));
        if (workspace.getKind() != WorkspaceEntity.Kind.ORGANIZATION ||
                workspace.getStatus() != WorkspaceEntity.Status.ACTIVE)
            throw new SecurityException("Organization tenant must be active");
        var membership = memberships.findByOrganizationIdAndUserId(
                workspace.getOrganizationId(), actorId)
                .orElseThrow(() -> new SecurityException("Active membership required"));
        if (membership.getStatus() != OrganizationMembershipEntity.Status.ACTIVE)
            throw new SecurityException("Inactive membership");
        if (membership.getRole() != OrganizationMembershipEntity.Role.OWNER &&
                membership.getRole() != OrganizationMembershipEntity.Role.ADMIN &&
                membership.getRole() != OrganizationMembershipEntity.Role.MANAGER)
            throw new SecurityException("Organization write permission required");
        return workspace.getOrganizationId();
    }
}
