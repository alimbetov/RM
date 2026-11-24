package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal owner-only membership and workspace lifecycle. No HTTP endpoint.
 * Lock order: all required accounts UPDATE in UUID order -> workspace UPDATE -> membership.
 * Mutations are serialized with TenantAuthorization.requireForCommand.
 */
@Service
public class TenantAccessStatusService {
    private final JdbcTemplate jdbc;

    public TenantAccessStatusService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record WorkspaceState(UUID organizationId, String status) {}
    private record MembershipState(String role, String status) {}

    @Transactional
    public void suspendMember(VerifiedActor actor, UUID tenantId, UUID targetUserId) {
        changeMember(actor, tenantId, targetUserId, false);
    }

    @Transactional
    public void restoreMember(VerifiedActor actor, UUID tenantId, UUID targetUserId) {
        changeMember(actor, tenantId, targetUserId, true);
    }

    @Transactional
    public void suspendTenant(VerifiedActor actor, UUID tenantId) {
        changeTenant(actor, tenantId, false);
    }

    @Transactional
    public void restoreTenant(VerifiedActor actor, UUID tenantId) {
        changeTenant(actor, tenantId, true);
    }

    private void changeMember(VerifiedActor actor, UUID tenantId, UUID targetUserId, boolean restore) {
        if (targetUserId == null) throw new IllegalArgumentException("Target user required");
        UUID callerId = requireActor(actor);
        if (tenantId == null) throw new SecurityException("Tenant required");
        if (callerId.equals(targetUserId))
            throw new SecurityException("Owner cannot modify own membership status");
        // Restore needs both account locks BEFORE the workspace lock.
        // Use the same deterministic order across all JVMs and database sessions.
        String targetAccountStatus = null;
        if (restore) {
            UUID first = callerId.compareTo(targetUserId) < 0 ? callerId : targetUserId;
            UUID second = first.equals(callerId) ? targetUserId : callerId;
            String firstStatus = lockAccount(first);
            String secondStatus = lockAccount(second);
            String callerStatus = first.equals(callerId) ? firstStatus : secondStatus;
            targetAccountStatus = first.equals(targetUserId) ? firstStatus : secondStatus;
            if (!"ACTIVE".equals(callerStatus)) throw new SecurityException("Actor inactive");
        } else if (!"ACTIVE".equals(lockAccount(callerId))) {
            throw new SecurityException("Actor inactive");
        }
        WorkspaceState tenant = lockWorkspaceAndRequireOwner(callerId, tenantId, false);
        actor.userId();

        MembershipState target = jdbc.query("""
                SELECT role,status FROM public.iam_org_membership
                WHERE organization_id=? AND user_id=?
                """, rs -> rs.next() ? new MembershipState(rs.getString(1), rs.getString(2)) : null,
                tenant.organizationId(), targetUserId);
        if (target == null) throw new SecurityException("Target is not a tenant member");

        String desired = restore ? "ACTIVE" : "SUSPENDED";
        if (desired.equals(target.status())) return;
        if (!("ACTIVE".equals(target.status()) && !restore
                || "SUSPENDED".equals(target.status()) && restore))
            throw new IllegalStateException("Invalid membership status transition");

        if (!restore && "OWNER".equals(target.role())) {
            Integer activeOwners = jdbc.queryForObject("""
                    SELECT count(*) FROM public.iam_org_membership m
                    JOIN public.iam_user_account u ON u.id=m.user_id
                    WHERE m.organization_id=? AND m.role='OWNER' AND m.status='ACTIVE'
                      AND u.status='ACTIVE'
                    """, Integer.class, tenant.organizationId());
            if (activeOwners == null || activeOwners <= 1)
                throw new SecurityException("Cannot suspend the last active owner");
        }
        if (restore) {
            // The target account is already locked until transaction completion.
            if (!"ACTIVE".equals(targetAccountStatus))
                throw new SecurityException("Target account is not active");
        }

        int updated = jdbc.update("""
                UPDATE public.iam_org_membership SET status=?,row_version=row_version+1
                WHERE organization_id=? AND user_id=? AND status=?
                """, desired, tenant.organizationId(), targetUserId, target.status());
        if (updated != 1) throw new IllegalStateException("Concurrent membership transition");
        evidence(tenantId, callerId, restore ? "MEMBER_RESTORED" : "MEMBER_SUSPENDED", targetUserId);
    }

    private void changeTenant(VerifiedActor actor, UUID tenantId, boolean restore) {
        UUID callerId = requireActor(actor);
        if (!"ACTIVE".equals(lockAccount(callerId))) throw new SecurityException("Actor inactive");
        WorkspaceState workspace = lockWorkspaceAndRequireOwner(callerId, tenantId, restore);
        actor.userId();
        String desired = restore ? "ACTIVE" : "SUSPENDED";
        if (desired.equals(workspace.status())) return;
        String from = restore ? "SUSPENDED" : "ACTIVE";
        if (!from.equals(workspace.status()))
            throw new IllegalStateException("Invalid tenant status transition");
        int updated = jdbc.update("""
                UPDATE public.iam_workspace SET status=?,row_version=row_version+1
                WHERE id=? AND status=?
                """, desired,tenantId,from);
        if (updated != 1) throw new IllegalStateException("Concurrent tenant transition");
        evidence(tenantId,callerId,restore?"TENANT_RESTORED":"TENANT_SUSPENDED",null);
    }

    private UUID requireActor(VerifiedActor actor) {
        if (actor == null) throw new SecurityException("Verified actor required");
        return actor.userId();
    }

    /**
     * Also permits the OWNER of a SUSPENDED tenant to restore it: regular
     * TenantAuthorization cannot admit a command while tenant is suspended.
     */
    private String lockAccount(UUID userId) {
        return jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE
                """, rs -> rs.next() ? rs.getString(1) : null, userId);
    }

    private WorkspaceState lockWorkspaceAndRequireOwner(UUID callerId, UUID tenantId, boolean permitSuspended) {
        if (tenantId == null) throw new SecurityException("Tenant required");

        WorkspaceState workspace = jdbc.query("""
                SELECT organization_id,status FROM public.iam_workspace
                WHERE id=? AND kind='ORGANIZATION' FOR UPDATE
                """, rs -> rs.next()
                ? new WorkspaceState(rs.getObject(1,UUID.class),rs.getString(2)) : null, tenantId);
        if (workspace == null || workspace.organizationId() == null)
            throw new SecurityException("Organization tenant unavailable");
        if (!"ACTIVE".equals(workspace.status())
                && !(permitSuspended && "SUSPENDED".equals(workspace.status())))
            throw new SecurityException("Tenant not active for this operation");

        MembershipState owner = jdbc.query("""
                SELECT role,status FROM public.iam_org_membership
                WHERE organization_id=? AND user_id=?
                """, rs -> rs.next()
                ? new MembershipState(rs.getString(1),rs.getString(2)) : null,
                workspace.organizationId(),callerId);
        if (owner == null || !"OWNER".equals(owner.role()) || !"ACTIVE".equals(owner.status()))
            throw new SecurityException("Active organization owner required");
        return workspace;
    }

    private void evidence(UUID tenantId, UUID callerId, String action, UUID targetId) {
        jdbc.update("""
                INSERT INTO public.iam_tenant_rbac_event
                  (tenant_id,id,actor_user_id,action_code,target_user_id,occurred_at)
                VALUES (?,?,?,?,?,?)
                """,tenantId,UUID.randomUUID(),callerId,action,targetId,Timestamp.from(Instant.now()));
    }
}
