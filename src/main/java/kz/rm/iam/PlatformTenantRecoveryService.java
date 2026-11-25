package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Emergency recovery only when a tenant has NO ACTIVE, eligible OWNER.
 * Does not make the platform administrator a tenant member or give it any
 * tenant business permissions. A replacement must already be an ACTIVE,
 * OIDC-linked tenant member: no arbitrary outsider may be inserted as OWNER.
 */
@Service
public class PlatformTenantRecoveryService {
    private final JdbcTemplate jdbc;
    private final PlatformAdminGovernanceService admins;

    public PlatformTenantRecoveryService(JdbcTemplate jdbc,PlatformAdminGovernanceService admins) {
        this.jdbc=jdbc;
        this.admins=admins;
    }

    @Transactional
    public void recoverOwner(VerifiedActor administrator,UUID tenantId,UUID replacementUserId,String incidentCode) {
        if(tenantId==null || replacementUserId==null)
            throw new SecurityException("Valid tenant and replacement account required");
        if(incidentCode==null || !incidentCode.matches("[A-Z0-9_-]{6,80}"))
            throw new IllegalArgumentException("Approved incident reference required");
        UUID adminId=admins.activeAdministrator(administrator);
        if(adminId.equals(replacementUserId))
            throw new SecurityException("Platform administrator cannot recover self as tenant owner");
        // Target account lock must precede workspace lock, consistent with commands.
        String replacementStatus=jdbc.query("""
                SELECT status FROM public.iam_user_account
                WHERE id=? AND external_subject IS NOT NULL
                  AND length(trim(external_subject))>0 FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,replacementUserId);
        if(!"ACTIVE".equals(replacementStatus))
            throw new SecurityException("Active OIDC-linked recovery target required");

        UUID organizationId=jdbc.query("""
                SELECT organization_id FROM public.iam_workspace
                WHERE id=? AND kind='ORGANIZATION'
                  AND status IN ('ACTIVE','SUSPENDED') FOR UPDATE
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenantId);
        if(organizationId==null)throw new SecurityException("Organization unavailable for recovery");

        Integer activeOwnerCount=jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_org_membership m
                JOIN public.iam_user_account u ON u.id=m.user_id
                WHERE m.organization_id=? AND m.role='OWNER' AND m.status='ACTIVE'
                  AND u.status='ACTIVE'
                """,Integer.class,organizationId);
        if(activeOwnerCount!=null && activeOwnerCount>0)
            throw new SecurityException("Existing active owner must authorize normal transfer");

        String targetRole=jdbc.query("""
                SELECT role FROM public.iam_org_membership
                WHERE organization_id=? AND user_id=? AND status='ACTIVE'
                """,rs->rs.next()?rs.getString(1):null,organizationId,replacementUserId);
        if(targetRole==null || "OWNER".equals(targetRole))
            throw new SecurityException("Active non-owner organization member required");

        List<UUID> previousOwners=jdbc.query("""
                SELECT user_id FROM public.iam_org_membership
                WHERE organization_id=? AND role='OWNER' ORDER BY user_id
                """,(rs,n)->rs.getObject(1,UUID.class),organizationId);

        // Failed original identities cannot silently regain OWNER later on restore.
        for(UUID previous:previousOwners) {
            int changed=jdbc.update("""
                    UPDATE public.iam_org_membership
                    SET role='MEMBER',row_version=row_version+1
                    WHERE organization_id=? AND user_id=? AND role='OWNER'
                    """,organizationId,previous);
            if(changed!=1)throw new IllegalStateException("Concurrent old owner change");
            audit(tenantId,adminId,previous,"OWNER_DEMOTED_BY_RECOVERY",incidentCode);
        }

        int changed=jdbc.update("""
                UPDATE public.iam_org_membership
                SET role='OWNER',row_version=row_version+1
                WHERE organization_id=? AND user_id=? AND role<>'OWNER' AND status='ACTIVE'
                """,organizationId,replacementUserId);
        if(changed!=1)throw new IllegalStateException("Recovery successor unavailable");
        UUID pendingTarget=jdbc.query("""
                SELECT to_user_id FROM public.iam_owner_transfer_request
                WHERE tenant_id=? AND state='PENDING' FOR UPDATE
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenantId);
        if(pendingTarget!=null) {
            int cancelled=jdbc.update("""
                    UPDATE public.iam_owner_transfer_request
                    SET state='CANCELLED',decided_at=?,row_version=row_version+1
                    WHERE tenant_id=? AND state='PENDING'
                    """,Timestamp.from(Instant.now()),tenantId);
            if(cancelled!=1)throw new IllegalStateException("Concurrent transfer cancellation");
            audit(tenantId,adminId,pendingTarget,"OWNER_TRANSFER_CANCELLED",incidentCode);
        }
        audit(tenantId,adminId,replacementUserId,"OWNER_RECOVERED",incidentCode);
    }

    private void audit(UUID tenantId,UUID actorId,UUID targetId,String action,String reason) {
        jdbc.update("""
                INSERT INTO public.iam_tenant_rbac_event
                  (tenant_id,id,actor_user_id,action_code,target_user_id,occurred_at,reason_code)
                VALUES (?,?,?,?,?,?,?)
                """,tenantId,UUID.randomUUID(),actorId,action,targetId,
                Timestamp.from(Instant.now()),reason);
    }
}
