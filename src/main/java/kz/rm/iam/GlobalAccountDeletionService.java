package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * IAM-R2.2D: administrator-approved logical deletion with irreversible
 * de-identification of live profile/email data. Referential identities used
 * by CRM/SALES and immutable evidence are retained.
 *
 * Not a legal-retention or backups-erasure policy. OIDC/provider deletion
 * and external copies require separate orchestration.
 */
@Service
public class GlobalAccountDeletionService {
    private final JdbcTemplate jdbc;
    private final EmailIdentityCrypto crypto;
    private final SecureRandom random=new SecureRandom();

    public GlobalAccountDeletionService(JdbcTemplate jdbc,EmailIdentityCrypto crypto) {
        this.jdbc=jdbc;
        this.crypto=crypto;
    }

    @Transactional
    public void deleteAccount(VerifiedActor admin,UUID targetId,String requestKey) {
        if(admin==null || targetId==null || requestKey==null
                || !requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new SecurityException("Trusted deletion request required");
        UUID adminId=admin.userId();
        if(adminId.equals(targetId))
            throw new SecurityException("Self deletion through administrator endpoint forbidden");
        String adminStatus=jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,adminId);
        if(!"ACTIVE".equals(adminStatus))
            throw new SecurityException("Active administrator required");
        Integer role=jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_global_role_assignment
                WHERE user_id=? AND role='PLATFORM_ADMIN' AND revoked_at IS NULL
                """,Integer.class,adminId);
        if(role==null || role!=1)
            throw new SecurityException("Platform administrator required");

        String status=jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,targetId);
        if(status==null) throw new SecurityException("Account not found");
        if("DELETED".equals(status)) {
            Integer replay=jdbc.queryForObject("""
                    SELECT count(*) FROM public.iam_account_deletion_event
                    WHERE actor_user_id=? AND target_user_id=? AND request_key=?
                    """,Integer.class,adminId,targetId,requestKey);
            if(replay!=null && replay==1) return;
            throw new IllegalStateException("Account already deleted with other request");
        }
        if(!"ACTIVE".equals(status) && !"SUSPENDED".equals(status))
            throw new IllegalStateException("Only existing activated accounts may be deleted");
        Integer owner=jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_org_membership
                WHERE user_id=? AND role='OWNER' AND status='ACTIVE'
                """,Integer.class,targetId);
        if(owner!=null && owner>0)
            throw new SecurityException("Organization ownership transfer required");
        Integer adminRoles=jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_global_role_assignment
                WHERE user_id=? AND role='PLATFORM_ADMIN' AND revoked_at IS NULL
                """,Integer.class,targetId);
        if(adminRoles!=null && adminRoles>0)
            throw new SecurityException("Administrator role revocation required");

        Timestamp now=Timestamp.from(Instant.now());
        // Invalidate delivery jobs before retiring the identity to prevent
        // unsent verification tokens being delivered after the tombstone.
        jdbc.update("""
                UPDATE public.iam_email_delivery_outbox
                SET state='FAILED'
                WHERE email_identity_id IN
                  (SELECT id FROM public.iam_email_identity WHERE user_id=?)
                  AND state='PENDING'
                """,targetId);
        // Challenge hashes have a UNIQUE constraint; rotate each row independently.
        var challenges=jdbc.query("""
                SELECT id FROM public.iam_email_verification_challenge
                WHERE email_identity_id IN
                  (SELECT id FROM public.iam_email_identity WHERE user_id=?)
                  AND consumed_at IS NULL ORDER BY id FOR UPDATE
                """,(rs,i)->rs.getObject(1,UUID.class),targetId);
        for(UUID challenge:challenges) {
            jdbc.update("""
                    UPDATE public.iam_email_verification_challenge
                    SET token_sha256=?,token_envelope=?,consumed_at=?
                    WHERE id=? AND consumed_at IS NULL
                    """,randomBytes(32),randomBytes(48),now,challenge);
        }
        // Historical email identity IDs must survive SALES order references.
        // Erase their encrypted addresses and lookup material, not the row IDs.
        var identities=jdbc.query("""
                SELECT id FROM public.iam_email_identity WHERE user_id=? FOR UPDATE
                """,(rs,i)->rs.getObject(1,UUID.class),targetId);
        for(UUID id:identities) {
            jdbc.update("""
                    UPDATE public.iam_email_identity
                    SET lifecycle_state='HISTORICAL',
                        pending_reason=NULL,pending_owner_id=NULL,current_owner_id=NULL,
                        active_lookup_hmac=NULL,lookup_hmac=?,email_envelope=?
                    WHERE id=? AND user_id=?
                    """,randomBytes(32),randomBytes(48),id,targetId);
        }
        // Preserve the last OIDC subject for a trusted revocation consumer
        // before clearing it from the live IAM account. The outbox is not ACK.
        String externalSubject=jdbc.queryForObject("""
                SELECT external_subject FROM public.iam_user_account WHERE id=?
                """,String.class,targetId);
        jdbc.update("DELETE FROM iam_pii.user_profile_secret WHERE user_id=?",targetId);
        int changed=jdbc.update("""
                UPDATE public.iam_user_account
                SET status='DELETED',external_subject=NULL,email_normalized=NULL,
                    phone_e164=NULL,display_name=NULL,row_version=row_version+1,updated_at=?
                WHERE id=? AND status=?
                """,now,targetId,status);
        if(changed!=1)throw new IllegalStateException("Concurrent deletion");
        UUID deletionId=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.iam_account_deletion_event
                (id,actor_user_id,target_user_id,request_key,occurred_at)
                VALUES (?,?,?,?,?)
                """,deletionId,adminId,targetId,requestKey,now);
        if(externalSubject!=null && !externalSubject.isBlank()) {
            jdbc.update("""
                    INSERT INTO public.iam_deletion_oidc_revocation_outbox
                    (deletion_event_id,target_user_id,provider_subject_envelope,state,created_at,next_attempt_at)
                    VALUES (?,?,?,'PENDING',?,?)
                    """,deletionId,targetId,crypto.encryptOidcSubject(externalSubject),now,now);
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes=new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
