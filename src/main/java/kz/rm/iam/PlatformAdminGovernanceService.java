package kz.rm.iam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Global administration is separate from tenant business RBAC.
 * First administrator is enabled only by an operator-supplied one-time secret
 * whose SHA-256 hash is provided through deployment configuration, never the DB.
 * No HTTP endpoint, automatic default admin or hardcoded bootstrap credential.
 */
@Service
public class PlatformAdminGovernanceService {
    private final JdbcTemplate jdbc;
    private final boolean bootstrapEnabled;
    private final String bootstrapHash;

    public PlatformAdminGovernanceService(JdbcTemplate jdbc,
            @Value("${rm.iam.bootstrap.enabled:false}") boolean bootstrapEnabled,
            @Value("${rm.iam.bootstrap.token-sha256:}") String bootstrapHash) {
        this.jdbc=jdbc;
        this.bootstrapEnabled=bootstrapEnabled;
        this.bootstrapHash=bootstrapHash;
    }

    @Transactional
    public void bootstrapFirstAdmin(VerifiedActor candidate, String operatorSecret) {
        if (candidate==null || !bootstrapEnabled || operatorSecret==null
                || operatorSecret.length()<32 || bootstrapHash==null
                || !bootstrapHash.matches("[0-9a-fA-F]{64}"))
            throw new SecurityException("Trusted offline administrator bootstrap unavailable");
        byte[] expected=HexFormat.of().parseHex(bootstrapHash);
        byte[] actual=digest(operatorSecret);
        if(!MessageDigest.isEqual(expected,actual))
            throw new SecurityException("Trusted offline administrator bootstrap unavailable");

        UUID candidateId=candidate.userId();
        requireActiveLinked(candidateId);
        lockGuard();
        boolean used=jdbc.queryForObject("""
                SELECT bootstrapped FROM public.iam_global_admin_guard WHERE id=1
                """,Boolean.class);
        if(used)throw new SecurityException("Initial administrator already bootstrapped");
        Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_global_role_assignment
                WHERE role='PLATFORM_ADMIN'
                """,Integer.class);
        if(existing==null || existing!=0)
            throw new SecurityException("Initial administrator already exists");

        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO public.iam_global_role_assignment
                  (id,user_id,role,granted_at)
                VALUES (?,?,'PLATFORM_ADMIN',?)
                """,UUID.randomUUID(),candidateId,now);
        jdbc.update("""
                UPDATE public.iam_global_admin_guard
                SET bootstrapped=TRUE,bootstrapped_at=? WHERE id=1 AND bootstrapped=FALSE
                """,now);
        event(candidateId,candidateId,"ADMIN_BOOTSTRAPPED");
    }

    @Transactional
    public void grantAdmin(VerifiedActor administrator, UUID targetUserId) {
        UUID adminId=activeAdministrator(administrator);
        if(targetUserId==null || adminId.equals(targetUserId))
            throw new SecurityException("Another eligible account required");
        requireActiveLinked(targetUserId);
        int updated=jdbc.update("""
                UPDATE public.iam_global_role_assignment
                SET revoked_at=NULL,granted_at=?
                WHERE user_id=? AND role='PLATFORM_ADMIN' AND revoked_at IS NOT NULL
                """,Timestamp.from(Instant.now()),targetUserId);
        if(updated==0) {
            Integer exists=jdbc.queryForObject("""
                    SELECT count(*) FROM public.iam_global_role_assignment
                    WHERE user_id=? AND role='PLATFORM_ADMIN' AND revoked_at IS NULL
                    """,Integer.class,targetUserId);
            if(exists!=null && exists>0)return;
            jdbc.update("""
                    INSERT INTO public.iam_global_role_assignment
                      (id,user_id,role,granted_at)
                    VALUES (?,?,'PLATFORM_ADMIN',?)
                    """,UUID.randomUUID(),targetUserId,Timestamp.from(Instant.now()));
        }
        event(adminId,targetUserId,"ADMIN_GRANTED");
    }

    @Transactional
    public void revokeAdmin(VerifiedActor administrator, UUID targetUserId) {
        UUID adminId=activeAdministrator(administrator);
        if(targetUserId==null || adminId.equals(targetUserId))
            throw new SecurityException("Administrator self-revocation prohibited");
        Integer exists=jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_global_role_assignment
                WHERE user_id=? AND role='PLATFORM_ADMIN' AND revoked_at IS NULL
                """,Integer.class,targetUserId);
        if(exists==null || exists==0)return;
        Integer remaining=jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_global_role_assignment a
                JOIN public.iam_user_account u ON u.id=a.user_id
                WHERE a.role='PLATFORM_ADMIN' AND a.revoked_at IS NULL
                  AND u.status='ACTIVE' AND u.external_subject IS NOT NULL
                """,Integer.class);
        if(remaining==null || remaining<=1)
            throw new SecurityException("Cannot revoke the last active platform administrator");
        int modified=jdbc.update("""
                UPDATE public.iam_global_role_assignment SET revoked_at=?
                WHERE user_id=? AND role='PLATFORM_ADMIN' AND revoked_at IS NULL
                """,Timestamp.from(Instant.now()),targetUserId);
        if(modified!=1)throw new IllegalStateException("Concurrent global role revocation");
        event(adminId,targetUserId,"ADMIN_REVOKED");
    }

    /** Same-row serialization for grant/revoke and tenant emergency recovery. */
    UUID activeAdministrator(VerifiedActor actor) {
        if(actor==null)throw new SecurityException("Verified administrator required");
        UUID adminId=actor.userId();
        requireActiveLinked(adminId);
        lockGuard();
        boolean hasRole=jdbc.query("""
                SELECT 1 FROM public.iam_global_role_assignment
                WHERE user_id=? AND role='PLATFORM_ADMIN' AND revoked_at IS NULL
                """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),adminId);
        if(!hasRole)throw new SecurityException("Active platform administrator required");
        return adminId;
    }

    private void requireActiveLinked(UUID userId) {
        String status=jdbc.query("""
                SELECT status FROM public.iam_user_account
                WHERE id=? AND external_subject IS NOT NULL
                  AND length(trim(external_subject))>0 FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,userId);
        if(!"ACTIVE".equals(status))
            throw new SecurityException("Active OIDC-linked account required");
    }

    private void lockGuard() {
        Integer locked=jdbc.queryForObject("""
                SELECT id FROM public.iam_global_admin_guard WHERE id=1 FOR UPDATE
                """,Integer.class);
        if(locked==null || locked!=1)throw new SecurityException("Global administration guard unavailable");
    }

    private void event(UUID actorId,UUID targetId,String action) {
        jdbc.update("""
                INSERT INTO public.iam_global_role_event
                  (id,actor_user_id,target_user_id,action_code,occurred_at)
                VALUES (?,?,?,?,?)
                """,UUID.randomUUID(),actorId,targetId,action,Timestamp.from(Instant.now()));
    }

    private static byte[] digest(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
