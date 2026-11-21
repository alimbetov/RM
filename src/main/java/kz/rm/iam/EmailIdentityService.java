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
 * Global IAM owner of encrypted email identities, proof challenges and delivery outbox.
 * REGISTRATION and CHECKOUT proof have different purposes and never mint a session.
 * R2.5A does not implement changes to an existing email address.
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
    public record EmailVerificationStatus(boolean emailVerified) {}

    public String normalize(String email) {
        return crypto.normalize(email);
    }

    /**
     * Trusted internal registration entry point, for an email-only pending account.
     * Responses are deliberately neutral: neither userId nor existence or verification
     * state of a different, already registered account is returned.
     * Do not expose without provider/rate-limit qualification.
     */
    @Transactional
    public void registerPendingEmail(String rawEmail) {
        String normalized=crypto.normalize(rawEmail);
        Identity identity=findOrProvision(normalized);
        String status=jdbc.queryForObject("""
            SELECT status FROM public.iam_user_account WHERE id=?
            """,String.class,identity.userId());
        // Do not use a public email-only registration to attach email-based proof
        // to an existing ACTIVE (possibly OIDC-linked) account.
        if ("PENDING".equals(status)) {
            queueChallenge(identity.identityId(),"REGISTRATION",true);
        }
    }

    /**
     * An existing ACTIVE OIDC-linked user may bind an initial email, but may not
     * replace an existing email through this method. Address changes belong to R2.5B.
     * Stripe -> account is the acquisition order; the email lookup is global.
     */
    @Transactional
    public EmailVerificationStatus requestInitialVerification(VerifiedActor actor,String rawEmail) {
        if(actor==null)throw new SecurityException("Verified principal required");
        String normalized=crypto.normalize(rawEmail);
        byte[] lookup=crypto.lookup(normalized);
        lockEmailStripe(lookup);
        UUID userId=actor.userId();
        boolean activeAndLinked=jdbc.query("""
            SELECT 1 FROM public.iam_user_account
            WHERE id=? AND status='ACTIVE' AND external_subject IS NOT NULL
              AND length(trim(external_subject))>0 FOR UPDATE
            """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),userId);
        if(!activeAndLinked)throw new SecurityException("Active OIDC-linked identity required");

        // In a pre-cutover populated database, never claim another account's
        // legacy plaintext email just because the encrypted identity is absent.
        boolean legacyCollision=jdbc.query("""
            SELECT 1 FROM public.iam_user_account
            WHERE email_normalized=? AND id<>? AND status<>'DELETED'
            """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),
                normalized,userId);
        if(legacyCollision)throw new SecurityException("Email registration unavailable");

        List<Identity> collision=jdbc.query("""
            SELECT id,user_id FROM public.iam_email_identity WHERE lookup_hmac=?
            """,(rs,n)->new Identity(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class)),lookup);
        if(!collision.isEmpty()&&!collision.getFirst().userId().equals(userId))
            throw new SecurityException("Email registration unavailable");

        List<UUID> owned=jdbc.query("""
            SELECT id FROM public.iam_email_identity WHERE user_id=?
            """,(rs,n)->rs.getObject(1,UUID.class),userId);
        if(!owned.isEmpty() && (collision.isEmpty()
                || !owned.getFirst().equals(collision.getFirst().identityId())))
            throw new SecurityException("Email change requires separate verification process");

        UUID identityId;
        if(!collision.isEmpty()) {
            identityId=collision.getFirst().identityId();
            boolean verified=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT verified_at IS NOT NULL FROM public.iam_email_identity WHERE id=?
                """,Boolean.class,identityId));
            if(verified)return new EmailVerificationStatus(true);
        } else {
            identityId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO public.iam_email_identity
                  (id,user_id,lookup_hmac,email_envelope,created_at)
                VALUES (?,?,?,?,?)
                """,identityId,userId,lookup,crypto.encrypt(normalized),
                Timestamp.from(Instant.now()));
        }
        queueChallenge(identityId,"REGISTRATION",true);
        return new EmailVerificationStatus(false);
    }

    /** Self read only: verified_at is the source of truth, not a duplicated flag. */
    @Transactional(readOnly=true)
    public EmailVerificationStatus myVerificationStatus(VerifiedActor actor) {
        if(actor==null)throw new SecurityException("Verified principal required");
        UUID userId=actor.userId();
        String status=jdbc.query("""
            SELECT status FROM public.iam_user_account WHERE id=?
            """,rs->rs.next()?rs.getString(1):null,userId);
        if(!"ACTIVE".equals(status))
            throw new SecurityException("Active account required");
        boolean verified=jdbc.query("""
            SELECT 1 FROM public.iam_email_identity
            WHERE user_id=? AND verified_at IS NOT NULL
            """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),userId);
        return new EmailVerificationStatus(verified);
    }

    /**
     * Existing checkout compatibility: no account claim happens here.
     * The caller (AUTOORDER) owns linking orders AFTER this proof has committed.
     */
    @Transactional
    public Identity findOrProvision(String normalizedEmail) {
        String normalized=crypto.normalize(normalizedEmail);
        byte[] lookup=crypto.lookup(normalized);
        lockEmailStripe(lookup);
        List<Identity> found=jdbc.query("""
            SELECT id,user_id FROM public.iam_email_identity WHERE lookup_hmac=?
            """,(rs,n)->new Identity(rs.getObject(1,UUID.class),
                 rs.getObject(2,UUID.class)),lookup);
        if(!found.isEmpty()) {
            assertNotDisabled(found.getFirst().userId());
            return found.getFirst();
        }
        // Legacy identity compatibility; no implicit login or order claim.
        List<UUID> legacy=jdbc.query("""
            SELECT id FROM public.iam_user_account WHERE email_normalized=?
              AND status <> 'DELETED'
            """,(rs,n)->rs.getObject(1,UUID.class),normalized);
        UUID userId=legacy.isEmpty()?UUID.randomUUID():legacy.getFirst();
        assertNotDisabledIfPresent(legacy);
        UUID identityId=UUID.randomUUID();
        if(legacy.isEmpty()) {
            Timestamp now=Timestamp.from(Instant.now());
            jdbc.update("""
                INSERT INTO public.iam_user_account
                  (id,display_name,status,created_at,updated_at,row_version)
                VALUES (?,'Покупатель','PENDING',?,?,0)
                """,userId,now,now);
        }
        jdbc.update("""
            INSERT INTO public.iam_email_identity
              (id,user_id,lookup_hmac,email_envelope,created_at)
            VALUES (?,?,?,?,?)
            """,identityId,userId,lookup,crypto.encrypt(normalized),
                Timestamp.from(Instant.now()));
        return new Identity(identityId,userId);
    }

    /** Checkout uses an explicitly CHECKOUT-only challenge. */
    @Transactional
    public void queueVerification(UUID identityId) {
        queueChallenge(identityId,"CHECKOUT",false);
    }

    private void queueChallenge(UUID identityId,String purpose,boolean cooldown) {
        if(identityId==null)throw new IllegalArgumentException("Identity required");
        Instant now=Instant.now();
        if(cooldown) {
            Integer recent=jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_verification_challenge
                WHERE email_identity_id=? AND purpose='REGISTRATION'
                  AND consumed_at IS NULL AND expires_at>?
                  AND created_at>?
                """,Integer.class,identityId,Timestamp.from(now),
                    Timestamp.from(now.minus(60,ChronoUnit.SECONDS)));
            if(recent!=null&&recent>0)return;
        }
        String token=crypto.token();
        UUID challengeId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO public.iam_email_verification_challenge
              (id,email_identity_id,token_sha256,token_envelope,expires_at,created_at,purpose)
            VALUES (?,?,?,?,?,?,?)
            """,challengeId,identityId,crypto.tokenHash(token),
                crypto.encrypt(token),
                Timestamp.from(now.plus(15,ChronoUnit.MINUTES)),
                Timestamp.from(now),purpose);
        jdbc.update("""
            INSERT INTO public.iam_email_delivery_outbox
              (id,challenge_id,email_identity_id,state,next_attempt_at,created_at)
            VALUES (?,?,?,'PENDING',?,?)
            """,UUID.randomUUID(),challengeId,identityId,
                Timestamp.from(now),Timestamp.from(now));
    }

    /** Proof of CHECKOUT email possession, never an OIDC authentication/session. */
    @Transactional
    public UUID verifyEmail(UUID challengeId,String presentedToken) {
        return verifyForPurpose(challengeId,presentedToken,"CHECKOUT");
    }

    /** Proof of initial signup/linked OIDC email. No user identifier is returned. */
    @Transactional
    public boolean verifyRegistration(UUID challengeId,String presentedToken) {
        verifyForPurpose(challengeId,presentedToken,"REGISTRATION");
        return true;
    }

    private UUID verifyForPurpose(UUID challengeId,String presentedToken,String requiredPurpose) {
        if(challengeId==null || presentedToken==null || presentedToken.length()<32 ||
                presentedToken.length()>256)throw new SecurityException("Invalid challenge");
        byte[] hash=crypto.tokenHash(presentedToken);
        UUID identityId=jdbc.query("""
            SELECT email_identity_id,token_sha256,expires_at,consumed_at,purpose
            FROM public.iam_email_verification_challenge WHERE id=? FOR UPDATE
            """,rs->{
                if(!rs.next()||rs.getTimestamp(4)!=null||
                        !rs.getTimestamp(3).toInstant().isAfter(Instant.now())||
                        !requiredPurpose.equals(rs.getString(5))||
                        !MessageDigest.isEqual(hash,rs.getBytes(2)))
                    throw new SecurityException("Invalid or expired challenge");
                return rs.getObject(1,UUID.class);
            },challengeId);
        UUID userId=jdbc.queryForObject("""
            SELECT user_id FROM public.iam_email_identity WHERE id=?
            """,UUID.class,identityId);
        String status=jdbc.query("""
            SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE
            """,rs->{
                if(!rs.next())throw new SecurityException("Account unavailable");
                return rs.getString(1);
            },userId);
        if(!"ACTIVE".equals(status) && !"PENDING".equals(status))
            throw new SecurityException("Account not eligible for verification");
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("UPDATE public.iam_email_verification_challenge SET consumed_at=? WHERE id=?",
                now,challengeId);
        jdbc.update("""
            UPDATE public.iam_email_identity
            SET verified_at=COALESCE(verified_at,?) WHERE id=?
            """,now,identityId);
        jdbc.update("""
            UPDATE public.iam_user_account
            SET status='ACTIVE',updated_at=? WHERE id=? AND status='PENDING'
            """,now,userId);
        jdbc.update("""
            INSERT INTO public.iam_email_verification_event
              (id,challenge_id,email_identity_id,user_id,purpose,occurred_at)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),challengeId,identityId,userId,requiredPurpose,now);
        return userId;
    }

    private void lockEmailStripe(byte[] lookup) {
        int bucket=Math.floorMod(Arrays.hashCode(lookup),256);
        Integer locked=jdbc.queryForObject(
            "SELECT bucket FROM public.iam_email_identity_lock WHERE bucket=? FOR UPDATE",
            Integer.class,bucket);
        if(locked==null || locked!=bucket)
            throw new IllegalStateException("Identity lock stripe missing");
    }

    private void assertNotDisabledIfPresent(List<UUID> legacy) {
        if(!legacy.isEmpty())assertNotDisabled(legacy.getFirst());
    }

    private void assertNotDisabled(UUID userId) {
        String status=jdbc.queryForObject(
            "SELECT status FROM public.iam_user_account WHERE id=?",
            String.class,userId);
        if(!"ACTIVE".equals(status)&&!"PENDING".equals(status))
            throw new SecurityException("Account unavailable");
    }
}
