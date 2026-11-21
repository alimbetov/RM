package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal invitation/acceptance process for pre-existing, OIDC-linked RM accounts.
 * No email transport is performed: an invitee explicitly accepts using VerifiedActor.
 * OWNER administers membership; functional grants remain in TenantRoleService.
 */
@Service
public class TenantMemberEnrollmentService {
    private final JdbcTemplate jdbc;
    private final TenantRoleService roleService;

    public TenantMemberEnrollmentService(JdbcTemplate jdbc, TenantRoleService roleService) {
        this.jdbc = jdbc;
        this.roleService = roleService;
    }

    public record Member(UUID userId, String membershipRole, String status) {}

    @Transactional
    public Member invite(VerifiedActor owner, UUID tenantId, UUID employeeUserId) {
        if (owner == null || employeeUserId == null)
            throw new SecurityException("Verified owner and employee required");
        UUID ownerId = owner.userId();
        if (ownerId.equals(employeeUserId))
            throw new SecurityException("Cannot invite own identity");
        // Owner account -> workspace. Uses the same permission fence as role changes.
        roleService.lockAndAuthorize(ownerId,tenantId);
        assertEnforced(tenantId);
        String accountStatus = jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=?
                """,rs -> rs.next() ? rs.getString(1) : null, employeeUserId);
        if (!"ACTIVE".equals(accountStatus))
            throw new SecurityException("Employee account must be active");

        UUID organizationId = organization(tenantId);
        Member existing = jdbc.query("""
                SELECT role,status FROM public.iam_org_membership
                WHERE organization_id=? AND user_id=?
                """,rs -> rs.next()
                    ? new Member(employeeUserId,rs.getString(1),rs.getString(2)) : null,
                organizationId,employeeUserId);
        if (existing != null) {
            if ("INVITED".equals(existing.status()) && "MEMBER".equals(existing.membershipRole()))
                return existing;
            throw new IllegalStateException("Employee already has a tenant membership");
        }
        jdbc.update("""
                INSERT INTO public.iam_org_membership
                (id,organization_id,user_id,role,status,joined_at,row_version)
                VALUES (?,?,?,'MEMBER','INVITED',?,0)
                """,UUID.randomUUID(),organizationId,employeeUserId,Timestamp.from(Instant.now()));
        audit(tenantId,ownerId,employeeUserId,"MEMBER_INVITED");
        return new Member(employeeUserId,"MEMBER","INVITED");
    }

    @Transactional
    public Member accept(VerifiedActor employee, UUID tenantId) {
        if (employee == null || tenantId == null)
            throw new SecurityException("Verified employee and tenant required");
        UUID userId = employee.userId();
        String actorStatus = jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE
                """,rs -> rs.next() ? rs.getString(1) : null,userId);
        if (!"ACTIVE".equals(actorStatus)) throw new SecurityException("Employee account inactive");
        // Identical actor -> workspace lock order as TenantAuthorization.requireForCommand.
        UUID organizationId = jdbc.query("""
                SELECT organization_id FROM public.iam_workspace
                WHERE id=? AND kind='ORGANIZATION' AND status='ACTIVE' FOR UPDATE
                """,rs -> rs.next() ? rs.getObject(1,UUID.class) : null,tenantId);
        if (organizationId == null) throw new SecurityException("Tenant not active");
        assertEnforced(tenantId);

        Member membership = jdbc.query("""
                SELECT role,status FROM public.iam_org_membership
                WHERE organization_id=? AND user_id=? FOR UPDATE
                """,rs -> rs.next()
                    ? new Member(userId,rs.getString(1),rs.getString(2)) : null,
                organizationId,userId);
        if (membership == null || !"MEMBER".equals(membership.membershipRole()))
            throw new SecurityException("Employee invitation required");
        if ("ACTIVE".equals(membership.status())) return membership;
        if (!"INVITED".equals(membership.status()))
            throw new SecurityException("Invitation no longer valid");

        int updated = jdbc.update("""
                UPDATE public.iam_org_membership
                SET status='ACTIVE',row_version=row_version+1
                WHERE organization_id=? AND user_id=? AND status='INVITED'
                """,organizationId,userId);
        if (updated != 1) throw new IllegalStateException("Concurrent invitation acceptance");
        audit(tenantId,userId,userId,"MEMBER_JOINED");
        return new Member(userId,"MEMBER","ACTIVE");
    }

    @Transactional
    public List<Member> list(VerifiedActor owner, UUID tenantId) {
        if (owner == null) throw new SecurityException("Verified owner required");
        roleService.lockAndAuthorize(owner.userId(),tenantId);
        return jdbc.query("""
                SELECT m.user_id,m.role,m.status
                FROM public.iam_org_membership m
                JOIN public.iam_workspace w ON w.organization_id=m.organization_id
                WHERE w.id=?
                ORDER BY m.user_id
                """,(rs,row)->new Member(rs.getObject(1,UUID.class),
                     rs.getString(2),rs.getString(3)),tenantId);
    }

    private UUID organization(UUID tenantId) {
        return jdbc.queryForObject("""
                SELECT organization_id FROM public.iam_workspace
                WHERE id=? AND kind='ORGANIZATION' AND status='ACTIVE'
                """,UUID.class,tenantId);
    }

    private void assertEnforced(UUID tenantId) {
        boolean enabled = jdbc.query("""
                SELECT enforced FROM public.iam_tenant_rbac_config WHERE tenant_id=?
                """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs ->
                    rs.next() && rs.getBoolean(1),tenantId);
        if (!enabled)
            throw new SecurityException("Tenant RBAC must be enforced before staff onboarding");
    }

    private void audit(UUID tenantId, UUID actorId, UUID targetUserId, String action) {
        jdbc.update("""
                INSERT INTO public.iam_tenant_rbac_event
                  (tenant_id,id,actor_user_id,action_code,target_user_id,occurred_at)
                VALUES (?,?,?,?,?,?)
                """,tenantId,UUID.randomUUID(),actorId,action,targetUserId,
                Timestamp.from(Instant.now()));
    }
}
