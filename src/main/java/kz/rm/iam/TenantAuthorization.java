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
    private record FreshMember(UUID organizationId, OrganizationMembershipEntity.Role role) {}

    @PostConstruct
    void initializeCommandLock() {
        String database=jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        // PostgreSQL shared row locks let different employee commands run in parallel;
        // H2 uses its supported exclusive row lock for the test-only compatibility path.
        String lockMode=database!=null && database.toLowerCase(java.util.Locale.ROOT).contains("postgresql")
                ? "FOR SHARE" : "FOR UPDATE";
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

    private boolean permitted(DomainPermission permission, OrganizationMembershipEntity.Role role) {
        if (role == null) return false;
        return switch (permission) {
            case CRM_READ, ORGANIZATION_LOCATION_READ, INVENTORY_STOCK_READ, SALES_ORDER_READ -> true;
            case TENANT_RBAC_MANAGE, SALES_PROPOSAL_RESPOND, SALES_ORDER_CONFIRM, SALES_ORDER_CANCEL,
                 SALES_ORDER_FULFILL, INVENTORY_RELEASE,
                 INVENTORY_RECEIVE, INVENTORY_ADJUST, INVENTORY_RESERVE,
                 INVENTORY_FULFILL, FILES_UPLOAD, FILES_READ, FILES_ATTACH,
                 FILES_DETACH, FILES_DELETE, CATALOG_READ, CATALOG_WRITE,
                 CATALOG_PRICE_MANAGE, CATALOG_PUBLISH,
                 STOREFRONT_OFFER_MANAGE, STOREFRONT_OFFER_PUBLISH -> false;
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
