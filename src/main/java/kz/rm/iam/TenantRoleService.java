package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SEC-R1.1 internal management port. Only a verified organization OWNER may
 * change RBAC; actor must come from trusted principal, never request body.
 * All mutations lock the workspace row and append immutable evidence.
 */
@Service
public class TenantRoleService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public TenantRoleService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this.jdbc=jdbc;
        this.authorization=authorization;
    }

    public record TenantRole(UUID id, String code, String displayName, boolean active) {}

    @Transactional
    TenantRole createRole(UUID actorId, UUID tenantId, String code, String displayName) {
        lockAndAuthorize(actorId,tenantId);
        if(code==null || !code.matches("[A-Z][A-Z0-9_]{2,63}")
                || displayName==null || displayName.isBlank() || displayName.length()>120)
            throw new IllegalArgumentException("Role code and display name required");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO public.iam_tenant_role
              (tenant_id,id,role_code,display_name,active,created_at)
            VALUES (?,?,?,?,TRUE,?)
            """,tenantId,id,code,displayName.strip(),Timestamp.from(Instant.now()));
        evidence(tenantId,actorId,"ROLE_CREATED",null,id,null);
        return new TenantRole(id,code,displayName.strip(),true);
    }

    @Transactional
    void grant(UUID actorId,UUID tenantId,UUID roleId,DomainPermission permission) {
        lockAndAuthorize(actorId,tenantId);
        ensureAssignable(permission);
        requireActiveRole(tenantId,roleId);
        int exists=jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_tenant_role_permission
            WHERE tenant_id=? AND role_id=? AND permission_code=?
            """,Integer.class,tenantId,roleId,permission.name());
        if(exists!=0)return; // idempotent retry
        jdbc.update("""
            INSERT INTO public.iam_tenant_role_permission
              (tenant_id,role_id,permission_code) VALUES (?,?,?)
            """,tenantId,roleId,permission.name());
        evidence(tenantId,actorId,"GRANT_ADDED",null,roleId,permission.name());
    }

    @Transactional
    void revoke(UUID actorId,UUID tenantId,UUID roleId,DomainPermission permission) {
        lockAndAuthorize(actorId,tenantId);
        ensureAssignable(permission);
        if(jdbc.update("""
            DELETE FROM public.iam_tenant_role_permission
            WHERE tenant_id=? AND role_id=? AND permission_code=?
            """,tenantId,roleId,permission.name())!=0)
            evidence(tenantId,actorId,"GRANT_REVOKED",null,roleId,permission.name());
    }

    @Transactional
    void assign(UUID actorId,UUID tenantId,UUID userId,UUID roleId) {
        lockAndAuthorize(actorId,tenantId);
        if(userId==null || userId.equals(actorId))
            throw new SecurityException("Role self-assignment prohibited");
        requireActiveRole(tenantId,roleId);
        requireActiveMember(tenantId,userId);
        int exists=jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_tenant_user_role
            WHERE tenant_id=? AND user_id=? AND role_id=?
            """,Integer.class,tenantId,userId,roleId);
        if(exists!=0)return;
        jdbc.update("""
            INSERT INTO public.iam_tenant_user_role
              (tenant_id,user_id,role_id,assigned_at) VALUES (?,?,?,?)
            """,tenantId,userId,roleId,Timestamp.from(Instant.now()));
        evidence(tenantId,actorId,"ROLE_ASSIGNED",userId,roleId,null);
    }

    @Transactional
    void unassign(UUID actorId,UUID tenantId,UUID userId,UUID roleId) {
        lockAndAuthorize(actorId,tenantId);
        if(userId==null || userId.equals(actorId))
            throw new SecurityException("Role self-revocation prohibited");
        if(jdbc.update("""
            DELETE FROM public.iam_tenant_user_role
            WHERE tenant_id=? AND user_id=? AND role_id=?
            """,tenantId,userId,roleId)!=0)
            evidence(tenantId,actorId,"ROLE_UNASSIGNED",userId,roleId,null);
    }

    @Transactional
    void enable(UUID actorId,UUID tenantId) {
        lockAndAuthorize(actorId,tenantId);
        Boolean already=jdbc.query("""
            SELECT enforced FROM public.iam_tenant_rbac_config WHERE tenant_id=?
            """,rs->rs.next()&&rs.getBoolean(1),tenantId);
        if(Boolean.TRUE.equals(already))return;
        // A sole-owner tenant is valid. Its OWNER always retains the
        // non-delegable RBAC management right, even without a functional role.
        // Prevent enabling a company with no usable operational permissions.
        Integer ready=jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_tenant_user_role a
            JOIN public.iam_tenant_role r
              ON r.tenant_id=a.tenant_id AND r.id=a.role_id
            JOIN public.iam_tenant_role_permission p
              ON p.tenant_id=r.tenant_id AND p.role_id=r.id
            JOIN public.iam_workspace w ON w.id=a.tenant_id
            JOIN public.iam_org_membership m
              ON m.organization_id=w.organization_id AND m.user_id=a.user_id
            JOIN public.iam_user_account u ON u.id=a.user_id
            WHERE a.tenant_id=? AND r.active=TRUE
              AND m.status='ACTIVE' AND u.status='ACTIVE'
              AND p.permission_code<>'TENANT_RBAC_MANAGE'
            """,Integer.class,tenantId);
        if(ready==null || ready==0)
            throw new IllegalStateException("Assign an active member a functional permission before enabling RBAC");
        Integer exists=jdbc.queryForObject(
                "SELECT count(*) FROM public.iam_tenant_rbac_config WHERE tenant_id=?",
                Integer.class,tenantId);
        Timestamp now=Timestamp.from(Instant.now());
        if(exists==0)jdbc.update("""
            INSERT INTO public.iam_tenant_rbac_config(tenant_id,enforced,updated_at)
            VALUES (?,TRUE,?)
            """,tenantId,now);
        else jdbc.update("""
            UPDATE public.iam_tenant_rbac_config
            SET enforced=TRUE,row_version=row_version+1,updated_at=?
            WHERE tenant_id=? AND enforced=FALSE
            """,now,tenantId);
        evidence(tenantId,actorId,"RBAC_ENABLED",null,null,null);
    }

    @Transactional
    void setRoleActive(UUID actorId,UUID tenantId,UUID roleId,boolean active) {
        lockAndAuthorize(actorId,tenantId);
        requireRole(tenantId,roleId);
        int updated=jdbc.update("""
            UPDATE public.iam_tenant_role SET active=?
            WHERE tenant_id=? AND id=? AND active<>?
            """,active,tenantId,roleId,active);
        if(updated!=0)
            evidence(tenantId,actorId,active?"ROLE_ACTIVATED":"ROLE_DEACTIVATED",
                    null,roleId,null);
    }

    @Transactional(readOnly=true)
    List<DomainPermission> listGrants(UUID actorId,UUID tenantId,UUID roleId) {
        authorization.require(actorId,tenantId,DomainPermission.TENANT_RBAC_MANAGE);
        requireRole(tenantId,roleId);
        return jdbc.query("""
            SELECT permission_code FROM public.iam_tenant_role_permission
            WHERE tenant_id=? AND role_id=? ORDER BY permission_code
            """,(rs,n)->DomainPermission.valueOf(rs.getString(1)),tenantId,roleId);
    }

    public record RoleAssignment(UUID userId,UUID roleId,String roleCode) {}

    @Transactional(readOnly=true)
    List<RoleAssignment> listAssignments(UUID actorId,UUID tenantId) {
        authorization.require(actorId,tenantId,DomainPermission.TENANT_RBAC_MANAGE);
        return jdbc.query("""
            SELECT a.user_id,a.role_id,r.role_code
            FROM public.iam_tenant_user_role a
            JOIN public.iam_tenant_role r
              ON r.tenant_id=a.tenant_id AND r.id=a.role_id
            WHERE a.tenant_id=?
            ORDER BY r.role_code,a.user_id
            """,(rs,n)->new RoleAssignment(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getString(3)),tenantId);
    }

    @Transactional(readOnly=true)
    List<TenantRole> listRoles(UUID actorId,UUID tenantId) {
        authorization.require(actorId,tenantId,DomainPermission.TENANT_RBAC_MANAGE);
        return jdbc.query("""
            SELECT id,role_code,display_name,active FROM public.iam_tenant_role
            WHERE tenant_id=? ORDER BY role_code
            """,(rs,i)->new TenantRole(rs.getObject(1,UUID.class),rs.getString(2),
                 rs.getString(3),rs.getBoolean(4)),tenantId);
    }

    void lockAndAuthorize(UUID actorId,UUID tenantId) {
        if(actorId==null || tenantId==null)
            throw new SecurityException("Verified owner and tenant required");
        // Match the command admission order. The actor lock also fences global
        // suspension; the tenant lock fences member and RBAC mutations.
        String status=jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,actorId);
        if(!"ACTIVE".equals(status))
            throw new SecurityException("Organization owner account inactive");
        UUID locked=jdbc.query("""
                SELECT id FROM public.iam_workspace
                WHERE id=? AND kind='ORGANIZATION' FOR UPDATE
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenantId);
        if(locked==null)throw new SecurityException("Tenant not found");
        // A JDBC read after acquiring the workspace lock avoids JPA L1 cached
        // OWNER/membership state from before a concurrent suspension.
        boolean activeOwner=jdbc.query("""
                SELECT 1 FROM public.iam_workspace w
                JOIN public.iam_org_membership m ON m.organization_id=w.organization_id
                WHERE w.id=? AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                  AND m.user_id=? AND m.role='OWNER' AND m.status='ACTIVE'
                """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),tenantId,actorId);
        if(!activeOwner)throw new SecurityException("Active organization owner required");
    }

    private void requireRole(UUID tenantId,UUID roleId) {
        if(roleId==null)throw new IllegalArgumentException("Role ID required");
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_tenant_role
            WHERE tenant_id=? AND id=?
            """,Integer.class,tenantId,roleId);
        if(count==null || count!=1)
            throw new SecurityException("Tenant role not found");
    }

    private void requireActiveRole(UUID tenantId,UUID roleId) {
        if(roleId==null)throw new IllegalArgumentException("Role ID required");
        Boolean active=jdbc.query("""
            SELECT active FROM public.iam_tenant_role WHERE tenant_id=? AND id=?
            """,rs->rs.next()&&rs.getBoolean(1),tenantId,roleId);
        if(!Boolean.TRUE.equals(active))throw new SecurityException("Active tenant role required");
    }

    private void requireActiveMember(UUID tenantId,UUID userId) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_org_membership m
            JOIN public.iam_workspace w ON w.organization_id=m.organization_id
            JOIN public.iam_user_account u ON u.id=m.user_id
            WHERE w.id=? AND m.user_id=? AND u.status='ACTIVE' AND m.status='ACTIVE'
            """,Integer.class,tenantId,userId);
        if(count==null || count!=1)
            throw new SecurityException("Active member of this tenant required");
    }

    private static void ensureAssignable(DomainPermission permission) {
        if(permission==null || permission==DomainPermission.TENANT_RBAC_MANAGE)
            throw new SecurityException("Administration rights are not delegable via RBAC");
    }

    private void evidence(UUID tenant,UUID actor,String action,
                          UUID user,UUID role,String permission) {
        jdbc.update("""
            INSERT INTO public.iam_tenant_rbac_event
              (tenant_id,id,actor_user_id,action_code,target_user_id,role_id,
               permission_code,occurred_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,tenant,UUID.randomUUID(),actor,action,user,role,permission,
                Timestamp.from(Instant.now()));
    }
}
