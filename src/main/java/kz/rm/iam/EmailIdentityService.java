package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Single owner of global IAM email identity, verification challenge and delivery outbox.
 * Never treat a verified email challenge as an OIDC/authenticated session.
 * The caller must hold a single database transaction for find/provision + checkout.
 */
@Service
public class EmailIdentityService {
    private final JdbcTemplate jdbc;
    private final EmailIdentityCrypto crypto;

    public EmailIdentityService(JdbcTemplate jdbc, EmailIdentityCrypto crypto) {
        this.jdbc=jdbc;
        this.crypto=crypto;
    }

    public record Identity(UUID identityId, UUID userId) {}

    public String normalize(String email) {
        return crypto.normalize(email);
    }

    @Transactional
    public Identity findOrProvision(String normalizedEmail) {
        byte[] lookup=crypto.lookup(normalizedEmail);
        // Serialize globally on an email stripe across seller tenants.
        int bucket=Math.floorMod(Arrays.hashCode(lookup),256);
        Integer locked=jdbc.queryForObject(
            "SELECT bucket FROM iam_email_identity_lock WHERE bucket=? FOR UPDATE",Integer.class,bucket);
        if(locked==null || locked!=bucket)throw new IllegalStateException("Identity lock stripe missing");

        List<Identity> found=jdbc.query("""
            SELECT id,user_id FROM iam_email_identity WHERE lookup_hmac=?
            """,(rs,n)->new Identity(rs.getObject(1,UUID.class),
                 rs.getObject(2,UUID.class)),lookup);
        if(!found.isEmpty()) {
            assertNotDisabled(found.getFirst().userId());
            return found.getFirst();
        }
        // Legacy identity compatibility. No implicit login or order claim.
        List<UUID> legacy=jdbc.query("""
            SELECT id FROM iam_user_account WHERE email_normalized=? AND status <> 'DELETED'
            """,(rs,n)->rs.getObject(1,UUID.class),normalizedEmail);
        UUID userId=legacy.isEmpty()?UUID.randomUUID():legacy.getFirst();
        assertNotDisabledIfPresent(legacy);
        UUID identityId=UUID.randomUUID();
        if(legacy.isEmpty()) {
            Timestamp now=Timestamp.from(Instant.now());
            jdbc.update("""
                INSERT INTO iam_user_account(id,display_name,status,created_at,updated_at,row_version)
                VALUES (?,'Покупатель','PENDING',?,?,0)
                """,userId,now,now);
        }
        jdbc.update("""
            INSERT INTO iam_email_identity(id,user_id,lookup_hmac,email_envelope,created_at)
            VALUES (?,?,?,?,?)
            """,identityId,userId,lookup,crypto.encrypt(normalizedEmail),
                Timestamp.from(Instant.now()));
        return new Identity(identityId,userId);
    }

    /** Internal transactional outbox; sender remains disabled until approved. */
    @Transactional
    public void queueVerification(UUID identityId) {
        if(identityId==null)throw new IllegalArgumentException("Identity required");
        String token=crypto.token();
        UUID id=UUID.randomUUID();
        Instant now=Instant.now();
        jdbc.update("""
            INSERT INTO iam_email_verification_challenge
            (id,email_identity_id,token_sha256,token_envelope,expires_at,created_at)
            VALUES (?,?,?,?,?,?)
            """,id,identityId,crypto.tokenHash(token),crypto.encrypt(token),
                Timestamp.from(now.plus(15,ChronoUnit.MINUTES)),Timestamp.from(now));
        jdbc.update("""
            INSERT INTO iam_email_delivery_outbox
            (id,challenge_id,email_identity_id,state,next_attempt_at,created_at)
            VALUES (?,?,?,'PENDING',?,?)
            """,UUID.randomUUID(),id,identityId,Timestamp.from(now),Timestamp.from(now));
    }

    /**
     * Proves possession of an email address, NOT an authenticated session.
     * A suspended/deleted account must never be reactivated by verifying email.
     */
    @Transactional
    public UUID verifyEmail(UUID challengeId,String presentedToken) {
        if(challengeId==null || presentedToken==null || presentedToken.length()<32 ||
                presentedToken.length()>256)throw new SecurityException("Invalid challenge");
        byte[] hash=crypto.tokenHash(presentedToken);
        UUID identityId=jdbc.query("""
            SELECT email_identity_id,token_sha256,expires_at,consumed_at
            FROM iam_email_verification_challenge WHERE id=? FOR UPDATE
            """,rs->{
                if(!rs.next()||rs.getTimestamp(4)!=null||
                        !rs.getTimestamp(3).toInstant().isAfter(Instant.now())||
                        !MessageDigest.isEqual(hash,rs.getBytes(2)))
                    throw new SecurityException("Invalid or expired challenge");
                return rs.getObject(1,UUID.class);
            },challengeId);
        UUID userId=jdbc.queryForObject("""
            SELECT user_id FROM iam_email_identity WHERE id=?
            """,UUID.class,identityId);
        // Lock account row and fail closed for restricted or removed identities.
        String status=jdbc.query("""
            SELECT status FROM iam_user_account WHERE id=? FOR UPDATE
            """,rs->{
                if(!rs.next())throw new SecurityException("Account unavailable");
                return rs.getString(1);
            },userId);
        if(!status.equals("ACTIVE") && !status.equals("PENDING"))
            throw new SecurityException("Account not eligible for verification");
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("UPDATE iam_email_verification_challenge SET consumed_at=? WHERE id=?",now,challengeId);
        jdbc.update("UPDATE iam_email_identity SET verified_at=COALESCE(verified_at,?) WHERE id=?",now,identityId);
        jdbc.update("""
            UPDATE iam_user_account SET status='ACTIVE',updated_at=? WHERE id=? AND status='PENDING'
            """,now,userId);
        return userId;
    }

    private void assertNotDisabledIfPresent(List<UUID> legacy) {
        if(!legacy.isEmpty())assertNotDisabled(legacy.getFirst());
    }

    private void assertNotDisabled(UUID userId) {
        String status=jdbc.queryForObject(
            "SELECT status FROM iam_user_account WHERE id=?",String.class,userId);
        if(!"ACTIVE".equals(status)&&!"PENDING".equals(status))
            throw new SecurityException("Account unavailable");
    }
}
