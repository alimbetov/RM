package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * RM-R4-W0.1: verified OIDC-linked, email-confirmed principal can create its
 * own first organization tenant. No platform-admin privilege is minted.
 *
 * Provisioning key is caller-supplied for exact retry; row-lock on the user
 * serializes concurrent first-tenant attempts. Existing tenants are never
 * discovered from client-supplied identifiers without ownership proof.
 */
@Service
public class SelfServiceTenantOnboardingService {
    private final JdbcTemplate jdbc;
    private final MyAccessService myAccess;

    public SelfServiceTenantOnboardingService(JdbcTemplate jdbc, MyAccessService myAccess) {
        this.jdbc = jdbc;
        this.myAccess = myAccess;
    }

    public record OnboardedTenant(UUID tenantId, UUID organizationId, UUID ownerUserId) {}

    @Transactional
    public OnboardedTenant createFirstTenant(VerifiedActor actor, UUID tenantId,
                                             String legalName, String displayName,
                                             String countryCode) {
        if (actor == null || tenantId == null) {
            throw new SecurityException("Verified identity and tenant request required");
        }
        if (legalName == null || legalName.isBlank() || legalName.length() > 300
                || displayName == null || displayName.isBlank() || displayName.length() > 200
                || countryCode == null || !countryCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("Invalid organization details");
        }
        UUID owner = actor.userId();
        String status = jdbc.query("""
                SELECT status FROM public.iam_user_account
                WHERE id=? AND external_subject IS NOT NULL
                  AND length(trim(external_subject))>0 FOR UPDATE
                """, rs -> rs.next() ? rs.getString(1) : null, owner);
        if (!"ACTIVE".equals(status)) {
            throw new SecurityException("Active OIDC-linked account required");
        }
        Boolean confirmed = jdbc.query("""
                SELECT 1 FROM public.iam_email_identity
                WHERE user_id=? AND lifecycle_state='CURRENT' AND verified_at IS NOT NULL
                """, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), owner);
        if (!Boolean.TRUE.equals(confirmed)) {
            throw new SecurityException("Confirmed email required for first tenant");
        }
        String legal = legalName.strip();
        String display = displayName.strip();
        if (legal.isEmpty() || display.isEmpty()) {
            throw new IllegalArgumentException("Invalid organization details");
        }

        // The membership check happens under the user's row lock so competing
        // create-first requests cannot each become a different first tenant.
        var existing = myAccess.myTenants(actor);
        var requested = jdbc.query("""
                SELECT w.organization_id, m.user_id, o.legal_name, o.display_name,
                       o.country_code, w.kind, w.status, m.role, m.status
                FROM public.iam_workspace w
                JOIN public.org_organization o ON o.id=w.organization_id
                LEFT JOIN public.iam_org_membership m
                  ON m.organization_id=w.organization_id AND m.user_id=?
                WHERE w.id=?
                """, rs -> {
                    if (!rs.next()) return null;
                    if (!owner.equals(rs.getObject(2, UUID.class))
                            || !legal.equals(rs.getString(3))
                            || !display.equals(rs.getString(4))
                            || !countryCode.equals(rs.getString(5))
                            || !"ORGANIZATION".equals(rs.getString(6))
                            || !"ACTIVE".equals(rs.getString(7))
                            || !"OWNER".equals(rs.getString(8))
                            || !"ACTIVE".equals(rs.getString(9))) {
                        throw new SecurityException("Tenant provisioning key unavailable");
                    }
                    return new OnboardedTenant(tenantId,rs.getObject(1,UUID.class),owner);
                }, owner, tenantId);
        if (requested != null) {
            if (existing.size() != 1 || !tenantId.equals(existing.getFirst().tenantId())) {
                throw new SecurityException("First tenant already exists");
            }
            return requested;
        }
        if (!existing.isEmpty()) {
            throw new SecurityException("First tenant already exists");
        }
        Timestamp now = Timestamp.from(Instant.now());
        UUID organization = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.org_organization
                 (id,legal_name,display_name,organization_type,country_code,status,
                  created_at,updated_at,row_version)
                VALUES (?,?,?,'LEGAL_ENTITY',?,'ACTIVE',?,?,0)
                """,organization,legal,display,countryCode,now,now);
        jdbc.update("""
                INSERT INTO public.iam_workspace
                 (id,kind,organization_id,status,created_at,row_version)
                VALUES (?,'ORGANIZATION',?,'ACTIVE',?,0)
                """,tenantId,organization,now);
        jdbc.update("""
                INSERT INTO public.iam_org_membership
                 (id,organization_id,user_id,role,status,joined_at,row_version)
                VALUES (?,?,?,'OWNER','ACTIVE',?,0)
                """,UUID.randomUUID(),organization,owner,now);
        jdbc.update("""
                INSERT INTO public.iam_tenant_rbac_config
                 (tenant_id,enforced,updated_at,row_version) VALUES (?,TRUE,?,0)
                """,tenantId,now);
        jdbc.update("""
                INSERT INTO public.iam_tenant_rbac_event
                 (tenant_id,id,actor_user_id,action_code,target_user_id,occurred_at)
                VALUES (?,? ,?,'TENANT_PROVISIONED',?,?)
                """,tenantId,UUID.randomUUID(),owner,owner,now);
        return new OnboardedTenant(tenantId,organization,owner);
    }

    @Transactional(readOnly = true)
    public MyAccessService.Tenant selectTenant(VerifiedActor actor, UUID tenantId) {
        if (actor == null || tenantId == null)
            throw new SecurityException("Verified tenant selection required");
        return myAccess.myTenants(actor).stream()
                .filter(t -> t.tenantId().equals(tenantId))
                .findFirst()
                .orElseThrow(() -> new SecurityException("Tenant selection denied"));
    }
}
