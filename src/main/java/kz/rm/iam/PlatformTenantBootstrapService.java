package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Trusted internal platform-admin command: provision a company tenant with its first OWNER.
 * An existing platform administrator is bootstrapped separately; this service never mints
 * PLATFORM_ADMIN or lets that global authority bypass tenant business permissions.
 * Caller chooses a stable provisioning UUID as the retry key, not an HTTP client.
 */
@Service
public class PlatformTenantBootstrapService {
    private final JdbcTemplate jdbc;

    public PlatformTenantBootstrapService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OrganizationAccess(UUID tenantId, UUID organizationId, UUID ownerUserId) {}

    @Transactional
    public OrganizationAccess provision(VerifiedActor platformAdmin, UUID tenantId,
                                        UUID ownerUserId, String legalName,
                                        String displayName, String countryCode) {
        if (platformAdmin == null || tenantId == null || ownerUserId == null)
            throw new SecurityException("Verified administrator and valid tenant/owner required");
        if (legalName == null || legalName.isBlank() || legalName.length() > 300
                || displayName == null || displayName.isBlank() || displayName.length() > 200
                || countryCode == null || !countryCode.matches("[A-Z]{2}"))
            throw new IllegalArgumentException("Valid organization details required");

        UUID adminId = platformAdmin.userId();
        // Serialize a single admin's bootstrap retries, then lock owner account.
        String adminStatus = jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE
                """,rs -> rs.next() ? rs.getString(1) : null, adminId);
        if (!"ACTIVE".equals(adminStatus)) throw new SecurityException("Administrator inactive");
        boolean platformAuthority = jdbc.query("""
                SELECT 1 FROM public.iam_global_role_assignment
                WHERE user_id=? AND role='PLATFORM_ADMIN' AND revoked_at IS NULL FOR UPDATE
                """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs -> rs.next(),adminId);
        if (!platformAuthority) throw new SecurityException("Active platform administrator required");

        // No tenant creation for an unverified/inactive account.
        String ownerStatus = jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE
                """,rs -> rs.next() ? rs.getString(1) : null,ownerUserId);
        if (!"ACTIVE".equals(ownerStatus)) throw new SecurityException("Owner account inactive");

        String requestedLegal = legalName.strip(), requestedName = displayName.strip();
        if (requestedLegal.isEmpty() || requestedName.isEmpty())
            throw new IllegalArgumentException("Valid organization details required");

        OrganizationAccess existing = jdbc.query("""
                SELECT w.organization_id,m.user_id,o.legal_name,o.display_name,o.country_code,
                       w.kind,w.status,m.role,m.status
                FROM public.iam_workspace w
                JOIN public.org_organization o ON o.id=w.organization_id
                LEFT JOIN public.iam_org_membership m
                  ON m.organization_id=w.organization_id AND m.user_id=?
                WHERE w.id=?
                """,rs -> {
                    if (!rs.next()) return null;
                    if (!ownerUserId.equals(rs.getObject(2,UUID.class))
                            || !requestedLegal.equals(rs.getString(3))
                            || !requestedName.equals(rs.getString(4))
                            || !countryCode.equals(rs.getString(5))
                            || !"ORGANIZATION".equals(rs.getString(6))
                            || !"ACTIVE".equals(rs.getString(7))
                            || !"OWNER".equals(rs.getString(8))
                            || !"ACTIVE".equals(rs.getString(9)))
                        throw new IllegalStateException("Provisioning key reused for different organization");
                    return new OrganizationAccess(tenantId,rs.getObject(1,UUID.class),ownerUserId);
                },ownerUserId,tenantId);
        if (existing != null) return existing;

        UUID organizationId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO public.org_organization
                (id,legal_name,display_name,organization_type,country_code,status,
                 created_at,updated_at,row_version)
                VALUES (?,?,?,'LEGAL_ENTITY',?,'ACTIVE',?,?,0)
                """,organizationId,requestedLegal,requestedName,countryCode,now,now);
        jdbc.update("""
                INSERT INTO public.iam_workspace
                (id,kind,organization_id,status,created_at,row_version)
                VALUES (?,'ORGANIZATION',?,'ACTIVE',?,0)
                """,tenantId,organizationId,now);
        jdbc.update("""
                INSERT INTO public.iam_org_membership
                (id,organization_id,user_id,role,status,joined_at,row_version)
                VALUES (?,?,?,'OWNER','ACTIVE',?,0)
                """,UUID.randomUUID(),organizationId,ownerUserId,now);
        // New tenants are fail-closed: no business permissions until an OWNER assigns roles.
        // This avoids the legacy role-based read fallback during employee enrollment.
        jdbc.update("""
                INSERT INTO public.iam_tenant_rbac_config
                (tenant_id,enforced,updated_at,row_version) VALUES (?,TRUE,?,0)
                """,tenantId,now);
        jdbc.update("""
                INSERT INTO public.iam_tenant_rbac_event
                (tenant_id,id,actor_user_id,action_code,target_user_id,occurred_at)
                VALUES (?,? ,?,'TENANT_PROVISIONED',?,?)
                """,tenantId,UUID.randomUUID(),adminId,ownerUserId,now);
        return new OrganizationAccess(tenantId,organizationId,ownerUserId);
    }
}
