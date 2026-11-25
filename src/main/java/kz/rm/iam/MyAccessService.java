package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Minimal post-login read model. One verified global identity may have unrelated
 * tenant memberships and global platform privileges. Never accepts caller userId.
 */
@Service
public class MyAccessService {
    private final JdbcTemplate jdbc;

    public MyAccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Tenant(UUID tenantId, UUID organizationId,
                         String displayName, String membershipRole, boolean rbacEnforced) {}

    @Transactional(readOnly=true)
    public List<Tenant> myTenants(VerifiedActor actor) {
        UUID userId = active(actor);
        return jdbc.query("""
                SELECT w.id,w.organization_id,o.display_name,m.role,
                       COALESCE(c.enforced,FALSE)
                FROM public.iam_org_membership m
                JOIN public.iam_workspace w ON w.organization_id=m.organization_id
                JOIN public.org_organization o ON o.id=w.organization_id
                LEFT JOIN public.iam_tenant_rbac_config c ON c.tenant_id=w.id
                WHERE m.user_id=? AND m.status='ACTIVE'
                  AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                ORDER BY o.display_name,w.id
                """,(rs,n)->new Tenant(
                    rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    rs.getString(3),rs.getString(4),rs.getBoolean(5)),userId);
    }

    @Transactional(readOnly=true)
    public List<String> myGlobalRoles(VerifiedActor actor) {
        UUID userId = active(actor);
        return jdbc.query("""
                SELECT role FROM public.iam_global_role_assignment
                WHERE user_id=? AND revoked_at IS NULL ORDER BY role
                """,(rs,n)->rs.getString(1),userId);
    }

    @Transactional(readOnly=true)
    public List<String> myFunctionalRoles(VerifiedActor actor,UUID tenantId) {
        UUID userId=active(actor);
        if(tenantId==null) throw new SecurityException("Tenant required");
        boolean hasMembership=jdbc.query("""
                SELECT 1
                FROM public.iam_workspace w
                JOIN public.iam_org_membership m ON m.organization_id=w.organization_id
                WHERE w.id=? AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                  AND m.user_id=? AND m.status='ACTIVE'
                """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),
                tenantId,userId);
        if(!hasMembership) throw new SecurityException("Active tenant membership required");
        return jdbc.query("""
                SELECT r.role_code FROM public.iam_tenant_user_role a
                JOIN public.iam_tenant_role r
                  ON r.tenant_id=a.tenant_id AND r.id=a.role_id
                WHERE a.tenant_id=? AND a.user_id=? AND r.active=TRUE
                ORDER BY r.role_code
                """,(rs,n)->rs.getString(1),tenantId,userId);
    }

    private UUID active(VerifiedActor actor) {
        if(actor==null)throw new SecurityException("Verified principal required");
        UUID userId=actor.userId();
        String status=jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=?
                """,rs->rs.next()?rs.getString(1):null,userId);
        if(!"ACTIVE".equals(status))throw new SecurityException("Account inactive");
        return userId;
    }
}
