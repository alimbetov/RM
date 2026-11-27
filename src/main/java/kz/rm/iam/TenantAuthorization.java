package kz.rm.iam;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.annotation.PostConstruct;
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
    private final JdbcTemplate jdbc;
    private String commandTenantLockSql;
    private String commandActorLockSql;
    private boolean postgres;
    private record FreshMember(UUID organizationId, OrganizationMembershipEntity.Role role) {}

    @PostConstruct
    void initializeCommandLock() {
        String database=jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        // PostgreSQL shared row locks let different employee commands run in parallel;
        // H2 uses its supported exclusive row lock for the test-only compatibility path.
        postgres=database!=null && database.toLowerCase(java.util.Locale.ROOT).contains("postgresql");
        String lockMode=postgres ? "FOR SHARE" : "FOR UPDATE";
        commandActorLockSql="SELECT status FROM public.iam_user_account WHERE id=? " + lockMode;
        commandTenantLockSql="SELECT id FROM public.iam_workspace WHERE id=? " + lockMode;
    }

    /**
     * Unforgeable inside application Java code: only TenantAuthorization can
     * create a permission decision. It is not an authentication credential.
     */
    public static final class Decision {
        private final UUID actorId,tenantId,organizationId;
        private final OrganizationMembershipEntity.Role role;
        private final DomainPermission permission;
        private final TransactionSynchronization admissionMarker;
        private Decision(UUID actorId,UUID tenantId,UUID organizationId,
                         OrganizationMembershipEntity.Role role,DomainPermission permission,
                         TransactionSynchronization admissionMarker) {
            this.actorId=actorId;this.tenantId=tenantId;this.organizationId=organizationId;
            this.role=role;this.permission=permission;this.admissionMarker=admissionMarker;
        }
        public UUID actorId(){return actorId;}
        public UUID tenantId(){return tenantId;}
        public UUID organizationId(){return organizationId;}
        public OrganizationMembershipEntity.Role role(){return role;}
        public DomainPermission permission(){return permission;}
        /** A command permit cannot be replayed in another transaction. */
        public boolean validForCurrentTransaction() {
            return admissionMarker!=null
                    && TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.getSynchronizations().contains(admissionMarker);
        }
    }

    /**
     * Only for mutating domain transactions. The shared workspace lock is kept
     * until COMMIT, so an OWNER revocation (workspace FOR UPDATE) cannot cross
     * an already-authorized mutation. Authentication / membership state changes
     * must follow the same lock protocol in their own admin workflows.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public Decision requireForCommand(UUID actorId,UUID tenantId,DomainPermission permission) {
        if(actorId==null || tenantId==null || permission==null)
            throw new SecurityException("Authenticated actor, tenant and permission required");
        lockActorAndTenant(actorId,tenantId);

        // A JPA persistence context may already contain an ACTIVE membership or
        // workspace loaded before another transaction suspended it. The tenant
        // lock serializes trusted status writers; re-read current DB status
        // after admission, and fail closed if JPA returns a stale role.
        FreshMember fresh=jdbc.query("""
                SELECT w.organization_id,m.role
                FROM public.iam_workspace w
                JOIN public.iam_org_membership m
                  ON m.organization_id=w.organization_id AND m.user_id=?
                WHERE w.id=? AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                  AND m.status='ACTIVE'
                """,rs->rs.next()
                ?new FreshMember(rs.getObject(1,UUID.class),
                    OrganizationMembershipEntity.Role.valueOf(rs.getString(2))):null,
                actorId,tenantId);
        if(fresh==null) throw new SecurityException("Active organization membership required");
        Decision checked=require(actorId,tenantId,permission);
        if(!fresh.organizationId().equals(checked.organizationId()) ||
                fresh.role()!=checked.role())
            throw new SecurityException("Stale membership decision");
        if(!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Transactional synchronization required");
        TransactionSynchronization marker=new TransactionSynchronization() {};
        TransactionSynchronizationManager.registerSynchronization(marker);
        return new Decision(checked.actorId(),checked.tenantId(),
                checked.organizationId(),checked.role(),checked.permission(),marker);
    }

    private void lockActorAndTenant(UUID actorId,UUID tenantId) {
        // A global account status UPDATE conflicts with this row lock, so an
        // account suspension cannot commit across an admitted business command.
        // Lock actor first, tenant second; future status writers must obey the
        // same lock order and tenant membership writers must fence on workspace.
        String actorStatus=jdbc.query(commandActorLockSql,rs->
                rs.next()?rs.getString(1):null,actorId);
        if(!"ACTIVE".equals(actorStatus))
            throw new SecurityException("Actor inactive or unknown");
        UUID locked=jdbc.query(commandTenantLockSql,rs->
                rs.next()?rs.getObject(1,UUID.class):null,tenantId);
        if(locked==null || !locked.equals(tenantId))
            throw new SecurityException("Tenant unavailable");
    }

    /**
     * Read-only TASK admission for SQL projections. Same current account and
     * workspace fences (in the same order) as command admission. Read only
     * fresh JDBC state after the fence; no JPA entity materialization, cache,
     * reusable Decision, or permission for mutation. Keep this API specific
     * to TASK_READ until additional read domains are independently qualified.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireTaskRead(UUID actorId,UUID tenantId) {
        if(actorId==null||tenantId==null)
            throw new SecurityException("Authenticated actor and tenant required");
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Transactional synchronization required");
        if(postgres) {
            // A materialized actor CTE supplies an ACTIVE row before the
            // outer LockRows node locks a workspace. Both FOR SHARE locks
            // remain through COMMIT. Preserve actor-before-tenant order.
            // Do NOT include RBAC or membership here: if the workspace
            // lock waits for a revocation, the initial statement snapshot
            // can predate it. The next statement is fresh at READ COMMITTED.
            UUID locked=jdbc.query("""
                    SELECT w.id
                    FROM (WITH actor_fence AS MATERIALIZED (
                        SELECT id,status FROM public.iam_user_account
                        WHERE id=? FOR SHARE
                    )
                    SELECT workspace.id
                    FROM actor_fence a
                    JOIN public.iam_workspace workspace ON workspace.id=?
                    WHERE a.status='ACTIVE'
                    FOR SHARE OF workspace) w
                    """,rs->rs.next()?rs.getObject(1,UUID.class):null,actorId,tenantId);
            if(!tenantId.equals(locked)) {
                // The combined fence returned no admitted pair. Preserve
                // the established denial classification on this failure-only
                // path; never turn a failed fence into successful admission.
                String actorStatus=jdbc.query(commandActorLockSql,rs->
                    rs.next()?rs.getString(1):null,actorId);
                if(!"ACTIVE".equals(actorStatus))
                    throw new SecurityException("Actor inactive or unknown");
                throw new SecurityException("Tenant unavailable");
            }
        } else {
            lockActorAndTenant(actorId,tenantId);
        }
        record ReadAdmission(OrganizationMembershipEntity.Role role,boolean enforced,boolean granted) {}
        ReadAdmission admission=jdbc.query("""
                SELECT m.role,
                       COALESCE((SELECT cfg.enforced FROM public.iam_tenant_rbac_config cfg
                                 WHERE cfg.tenant_id=w.id),FALSE) AS enforced,
                       EXISTS(SELECT 1 FROM public.iam_tenant_user_role a
                              JOIN public.iam_tenant_role r
                                ON r.tenant_id=a.tenant_id AND r.id=a.role_id
                              JOIN public.iam_tenant_role_permission p
                                ON p.tenant_id=r.tenant_id AND p.role_id=r.id
                              WHERE a.tenant_id=w.id AND a.user_id=m.user_id
                                AND r.active=TRUE AND p.permission_code=?) AS granted
                FROM public.iam_workspace w
                JOIN public.iam_org_membership m
                  ON m.organization_id=w.organization_id AND m.user_id=?
                WHERE w.id=? AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                  AND m.status='ACTIVE'
                  AND EXISTS(SELECT 1 FROM public.iam_user_account u WHERE u.id=m.user_id AND u.principal_kind='HUMAN')
                """,rs->rs.next()?new ReadAdmission(
                    OrganizationMembershipEntity.Role.valueOf(rs.getString(1)),
                    rs.getBoolean(2),rs.getBoolean(3)):null,
                DomainPermission.TASK_READ.name(),actorId,tenantId);
        if(admission==null)
            throw new SecurityException("Active organization membership required");
        if(admission.enforced() ? !admission.granted()
                : !permitted(DomainPermission.TASK_READ,admission.role()))
            throw new SecurityException("Tenant RBAC permission denied");
    }

    public Decision require(UUID actorId, UUID tenantId, DomainPermission permission) {
        if (actorId == null || tenantId == null || permission == null)
            throw new SecurityException("Authenticated actor, tenant and permission required");
        var actor = users.findById(actorId)
            .orElseThrow(() -> new SecurityException("Actor unknown"));
        if (actor.getPrincipalKind()!=UserAccountEntity.PrincipalKind.HUMAN)
            throw new SecurityException("Human principal required");
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
        if (permission==DomainPermission.TENANT_RBAC_MANAGE) {
            // Membership ownership is a separately anchored, non-delegable admin
            // capability; functional RBAC cannot grant RBAC_MANAGE to itself.
            if (membership.getRole()!=OrganizationMembershipEntity.Role.OWNER)
                throw new SecurityException("Only organization owner can manage tenant RBAC");
        } else {
            boolean enforced=Boolean.TRUE.equals(jdbc.query("""
                SELECT enforced FROM public.iam_tenant_rbac_config WHERE tenant_id=?
                """,rs->rs.next() && rs.getBoolean(1),tenantId));
            if (enforced) {
                Integer grants=jdbc.queryForObject("""
                    SELECT count(*) FROM public.iam_tenant_user_role a
                    JOIN public.iam_tenant_role r
                      ON r.tenant_id=a.tenant_id AND r.id=a.role_id
                    JOIN public.iam_tenant_role_permission p
                      ON p.tenant_id=r.tenant_id AND p.role_id=r.id
                    WHERE a.tenant_id=? AND a.user_id=? AND r.active=TRUE AND p.permission_code=?
                    """,Integer.class,tenantId,actorId,permission.name());
                if(grants==null || grants==0)
                    throw new SecurityException("Tenant RBAC permission denied");
            } else if (!permitted(permission,membership.getRole())) {
                throw new SecurityException("Domain permission denied");
            }
        }
        return new Decision(actorId,tenantId,workspace.getOrganizationId(),membership.getRole(),permission,null);
    }

    /** Narrow service-only admission. Reuse the actor -> workspace lock protocol,
     * fresh DB membership and effective role grants, not a synthetic VerifiedActor. */
    public static final class IntradayReadPermit {
        private final UUID tenant;
        private final VerifiedServiceActor actor;
        private final TransactionSynchronization marker;
        private IntradayReadPermit(UUID tenant,VerifiedServiceActor actor,TransactionSynchronization marker){
            this.tenant=tenant;this.actor=actor;this.marker=marker;
        }
        public void assertCurrent(UUID expectedTenant){
            actor.accountId();
            if(!tenant.equals(expectedTenant)||!TransactionSynchronizationManager.isActualTransactionActive()
               ||!TransactionSynchronizationManager.isSynchronizationActive()
               ||!TransactionSynchronizationManager.getSynchronizations().contains(marker))
                throw new SecurityException("INT_INTRADAY_PERMIT_REQUIRED");
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public IntradayReadPermit requireIntradayRead(VerifiedServiceActor actor,UUID tenantId) {
        UUID id=actor.accountId();
        lockActorAndTenant(id,tenantId);
        var org=jdbc.query("""
            SELECT o.id FROM public.org_organization o JOIN public.iam_workspace w ON w.organization_id=o.id
            WHERE w.id=? AND o.status='ACTIVE' FOR SHARE OF o NOWAIT
            """,(rs,n)->rs.getObject(1,UUID.class),tenantId);
        if(org.isEmpty())throw new SecurityException("INT_INTRADAY_ORG_DENIED");
        boolean allowed=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(
              SELECT 1 FROM public.iam_user_account u
              JOIN public.iam_workspace w ON w.id=?
              JOIN public.iam_org_membership m ON m.organization_id=w.organization_id AND m.user_id=u.id
              JOIN public.iam_tenant_rbac_config cfg ON cfg.tenant_id=w.id
              WHERE u.id=? AND u.external_subject=? AND u.status='ACTIVE' AND u.principal_kind='SERVICE'
                AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                AND m.role='MEMBER' AND m.status='ACTIVE' AND cfg.enforced
                AND EXISTS(SELECT 1 FROM public.iam_tenant_user_role a
                    JOIN public.iam_tenant_role r ON r.tenant_id=a.tenant_id AND r.id=a.role_id
                    JOIN public.iam_tenant_role_permission p ON p.tenant_id=r.tenant_id AND p.role_id=r.id
                    WHERE a.tenant_id=w.id AND a.user_id=u.id AND r.active AND p.permission_code='SALES_ORDER_READ')
                AND NOT EXISTS(SELECT 1 FROM public.iam_tenant_user_role a
                    JOIN public.iam_tenant_role r ON r.tenant_id=a.tenant_id AND r.id=a.role_id
                    JOIN public.iam_tenant_role_permission p ON p.tenant_id=r.tenant_id AND p.role_id=r.id
                    WHERE a.tenant_id=w.id AND a.user_id=u.id AND r.active AND p.permission_code<>'SALES_ORDER_READ'))
            """,Boolean.class,tenantId,id,actor.subject()));
        if(!allowed)throw new SecurityException("INT_SERVICE_SCOPE_DENIED");
        actor.accountId();
        TransactionSynchronization marker=new TransactionSynchronization(){};
        TransactionSynchronizationManager.registerSynchronization(marker);
        return new IntradayReadPermit(tenantId,actor,marker);
    }

    private boolean permitted(DomainPermission permission, OrganizationMembershipEntity.Role role) {
        if (role == null) return false;
        return switch (permission) {
            case CRM_READ, ORGANIZATION_LOCATION_READ, INVENTORY_STOCK_READ, SALES_ORDER_READ, TASK_READ -> true;
            case TENANT_RBAC_MANAGE, SALES_PROPOSAL_RESPOND, SALES_ORDER_CONFIRM, SALES_ORDER_CANCEL,
                 SALES_ORDER_FULFILL, SALES_RETURN_REVIEW, SALES_RETURN_RECEIVE,
                 SALES_RETURN_INSPECT, SALES_RETURN_POST, SALES_PACKAGING_MANAGE, INVENTORY_RELEASE,
                 INVENTORY_RECEIVE, INVENTORY_ADJUST, INVENTORY_RESERVE,
                 INVENTORY_FULFILL, FILES_UPLOAD, FILES_READ, FILES_ATTACH,
                 FILES_DETACH, FILES_DELETE, CATALOG_READ, CATALOG_WRITE,
                 CATALOG_PRICE_MANAGE, CATALOG_PUBLISH,
                 STOREFRONT_OFFER_MANAGE, STOREFRONT_OFFER_PUBLISH, TASK_MANAGE, TASK_EXECUTE -> false;
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
