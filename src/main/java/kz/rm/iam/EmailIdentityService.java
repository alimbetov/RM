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
 * REGISTRATION, CHECKOUT and CHANGE_EMAIL proof have different purposes.
 * An email proof never mints an OIDC session or a business permission.
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
    private record Existing(UUID id,UUID userId,String pendingReason) {}
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
    void registerPendingEmail(String rawEmail) {
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
            SELECT id,user_id FROM public.iam_email_identity WHERE active_lookup_hmac=?
            """,(rs,n)->new Identity(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class)),lookup);
        if(!collision.isEmpty()&&!collision.getFirst().userId().equals(userId))
            throw new SecurityException("Email registration unavailable");

        List<UUID> owned=jdbc.query("""
            SELECT id FROM public.iam_email_identity
            WHERE user_id=? AND lifecycle_state IN ('CURRENT','PENDING')
            """,(rs,n)->rs.getObject(1,UUID.class),userId);
        if(!owned.isEmpty() && (collision.isEmpty()
                || !owned.getFirst().equals(collision.getFirst().identityId())))
            throw new SecurityException("Email change requires separate verification process");

        UUID identityId;
        if(!collision.isEmpty()) {
            identityId=collision.getFirst().identityId();
            boolean verified=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT verified_at IS NOT NULL FROM public.iam_email_identity
                WHERE id=? AND lifecycle_state='CURRENT'
                """,Boolean.class,identityId));
            if(verified)return new EmailVerificationStatus(true);
        } else {
            identityId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO public.iam_email_identity
                  (id,user_id,lookup_hmac,email_envelope,created_at,
                    lifecycle_state,pending_reason,active_lookup_hmac,pending_owner_id)
                VALUES (?,?,?,?,?,'PENDING','REGISTRATION',?,?)
                """,identityId,userId,lookup,crypto.encrypt(normalized),
                Timestamp.from(Instant.now()),lookup,userId);
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
            WHERE user_id=? AND lifecycle_state='CURRENT' AND verified_at IS NOT NULL
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
        List<Existing> found=jdbc.query("""
            SELECT id,user_id,pending_reason FROM public.iam_email_identity
            WHERE active_lookup_hmac=?
            """,(rs,n)->new Existing(rs.getObject(1,UUID.class),
                 rs.getObject(2,UUID.class),rs.getString(3)),lookup);
        if(!found.isEmpty()) {
            // Checkout cannot prove a CHANGE_EMAIL candidate via CHECKOUT token.
            if ("CHANGE_EMAIL".equals(found.getFirst().pendingReason()))
                throw new SecurityException("Email temporarily unavailable");
            assertNotDisabled(found.getFirst().userId());
            return new Identity(found.getFirst().id(),found.getFirst().userId());
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
                  (id,status,created_at,updated_at,row_version)
                VALUES (?,'PENDING',?,?,0)
                """,userId,now,now);
        }
        jdbc.update("""
            INSERT INTO public.iam_email_identity
              (id,user_id,lookup_hmac,email_envelope,created_at,
               lifecycle_state,pending_reason,active_lookup_hmac,pending_owner_id)
            VALUES (?,?,?,?,?,'PENDING','REGISTRATION',?,?)
            """,identityId,userId,lookup,crypto.encrypt(normalized),
                Timestamp.from(Instant.now()),lookup,userId);
        return new Identity(identityId,userId);
    }

    /** Email-change status never reveals the new email value or cross-account existence. */
    public record EmailChangeStatus(boolean currentEmailVerified,boolean pendingEmailVerified) {}
    private record Pending(UUID id,byte[] lookup) {}

    /**
     * Account-scoped request for a different email: active previous email remains
     * intact until the successor proves ownership of the new mailbox.
     */
    @Transactional
    public EmailChangeStatus requestEmailChange(VerifiedActor actor,String rawNewEmail) {
        if(actor==null)throw new SecurityException("Verified principal required");
        String normalized=crypto.normalize(rawNewEmail);
        byte[] lookup=crypto.lookup(normalized);
        lockEmailStripe(lookup);
        UUID userId=actor.userId();
        lockActiveLinkedAccount(userId);
        UUID current=jdbc.query("""
            SELECT id FROM public.iam_email_identity
            WHERE current_owner_id=? AND lifecycle_state='CURRENT' AND verified_at IS NOT NULL
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,userId);
        if(current==null)
            throw new SecurityException("First email must be verified before changing address");
        byte[] currentLookup=jdbc.queryForObject("""
            SELECT lookup_hmac FROM public.iam_email_identity WHERE id=?
            """,byte[].class,current);
        if(MessageDigest.isEqual(currentLookup,lookup))
            return new EmailChangeStatus(true,false);

        // Existing ACTIVE claim, including another user's pending proposal, is protected.
        UUID activeOwner=jdbc.query("""
            SELECT user_id FROM public.iam_email_identity WHERE active_lookup_hmac=?
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,lookup);
        if(activeOwner!=null&&!activeOwner.equals(userId))
            throw new SecurityException("Email unavailable");

        Pending oldPending=jdbc.query("""
            SELECT id,lookup_hmac FROM public.iam_email_identity
            WHERE pending_owner_id=? AND lifecycle_state='PENDING'
            """,rs->rs.next()?new Pending(rs.getObject(1,UUID.class),rs.getBytes(2)):null,userId);
        if(oldPending!=null && MessageDigest.isEqual(oldPending.lookup(),lookup)) {
            queueChallenge(oldPending.id(),"CHANGE_EMAIL",true);
            return new EmailChangeStatus(true,false);
        }
        if(activeOwner!=null)throw new SecurityException("Email already claimed");

        if(oldPending!=null) {
            jdbc.update("""
                UPDATE public.iam_email_identity
                SET lifecycle_state='HISTORICAL',pending_reason=NULL,
                    active_lookup_hmac=NULL,pending_owner_id=NULL
                WHERE id=? AND pending_owner_id=?
                """,oldPending.id(),userId);
            lifecycleEvent(oldPending.id(),userId,"CHANGE_CANCELLED");
        }
        UUID proposed=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO public.iam_email_identity
              (id,user_id,lookup_hmac,email_envelope,created_at,
               lifecycle_state,pending_reason,active_lookup_hmac,pending_owner_id)
            VALUES (?,?,?,?,?,'PENDING','CHANGE_EMAIL',?,?)
            """,proposed,userId,lookup,crypto.encrypt(normalized),
            Timestamp.from(Instant.now()),lookup,userId);
        lifecycleEvent(proposed,userId,"CHANGE_REQUESTED");
        queueChallenge(proposed,"CHANGE_EMAIL",true);
        return new EmailChangeStatus(true,false);
    }

    @Transactional
    public void cancelEmailChange(VerifiedActor actor) {
        if(actor==null)throw new SecurityException("Verified principal required");
        UUID userId=actor.userId();
        lockActiveLinkedAccount(userId);
        UUID pending=jdbc.query("""
            SELECT id FROM public.iam_email_identity
            WHERE pending_owner_id=? AND pending_reason='CHANGE_EMAIL'
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,userId);
        if(pending==null)return;
        int updated=jdbc.update("""
            UPDATE public.iam_email_identity
            SET lifecycle_state='HISTORICAL',pending_reason=NULL,
                active_lookup_hmac=NULL,pending_owner_id=NULL
            WHERE id=? AND pending_owner_id=? AND pending_reason='CHANGE_EMAIL'
            """,pending,userId);
        if(updated!=1)throw new IllegalStateException("Concurrent email cancellation");
        lifecycleEvent(pending,userId,"CHANGE_CANCELLED");
    }

    /** Proof must be bound to the verified same principal as the change request. */
    @Transactional
    public EmailChangeStatus confirmEmailChange(VerifiedActor actor,UUID challengeId,String token) {
        if(actor==null||challengeId==null||token==null||token.length()<32||token.length()>256)
            throw new SecurityException("Verified principal and challenge required");
        UUID userId=actor.userId();
        // Account lock before challenge. Registration/checkout may lock their own
        // challenge first but cannot have CHANGE_EMAIL purpose for that identity.
        lockActiveLinkedAccount(userId);
        byte[] suppliedHash=crypto.tokenHash(token);
        UUID identityId=jdbc.query("""
            SELECT c.email_identity_id,c.token_sha256,c.consumed_at,c.expires_at,c.purpose
            FROM public.iam_email_verification_challenge c WHERE c.id=? FOR UPDATE
            """,rs->{
                if(!rs.next() || rs.getTimestamp(3)!=null ||
                   !rs.getTimestamp(4).toInstant().isAfter(Instant.now()) ||
                   !"CHANGE_EMAIL".equals(rs.getString(5)) ||
                   !MessageDigest.isEqual(suppliedHash,rs.getBytes(2)))
                    throw new SecurityException("Invalid or expired email change proof");
                return rs.getObject(1,UUID.class);
            },challengeId);
        UUID ownedPending=jdbc.query("""
            SELECT id FROM public.iam_email_identity
            WHERE id=? AND pending_owner_id=? AND pending_reason='CHANGE_EMAIL'
              AND lifecycle_state='PENDING' AND verified_at IS NULL
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,identityId,userId);
        if(ownedPending==null)
            throw new SecurityException("Email change is no longer pending");
        UUID current=jdbc.query("""
            SELECT id FROM public.iam_email_identity
            WHERE current_owner_id=? AND lifecycle_state='CURRENT' AND verified_at IS NOT NULL
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,userId);
        if(current==null)throw new SecurityException("Current verified email required");
        Timestamp now=Timestamp.from(Instant.now());
        // Change the two identity states atomically, preserving historical ciphertext
        // and FK identity for existing orders.
        int retired=jdbc.update("""
            UPDATE public.iam_email_identity
            SET lifecycle_state='HISTORICAL',active_lookup_hmac=NULL,
                current_owner_id=NULL
            WHERE id=? AND current_owner_id=?
            """,current,userId);
        if(retired!=1)throw new IllegalStateException("Current email changed concurrently");
        int activated=jdbc.update("""
            UPDATE public.iam_email_identity
            SET lifecycle_state='CURRENT',pending_reason=NULL,
                pending_owner_id=NULL,current_owner_id=?,verified_at=?
            WHERE id=? AND pending_owner_id=? AND pending_reason='CHANGE_EMAIL'
            """,userId,now,identityId,userId);
        if(activated!=1)throw new IllegalStateException("New email changed concurrently");
        // Important for pre-cutover legacy: never reuse old address as an account
        // identity after it is HISTORICAL.
        jdbc.update("""
            UPDATE public.iam_user_account
            SET email_normalized=NULL,row_version=row_version+1,updated_at=?
            WHERE id=? AND email_normalized IS NOT NULL
            """,now,userId);
        jdbc.update("""
            UPDATE public.iam_email_verification_challenge
            SET consumed_at=? WHERE id=? AND consumed_at IS NULL
            """,now,challengeId);
        proofEvent(challengeId,identityId,userId,"CHANGE_EMAIL",now);
        lifecycleEvent(identityId,userId,"CHANGE_CONFIRMED");
        return new EmailChangeStatus(true,false);
    }

    private void lockActiveLinkedAccount(UUID userId) {
        boolean valid=jdbc.query("""
            SELECT 1 FROM public.iam_user_account
            WHERE id=? AND status='ACTIVE' AND external_subject IS NOT NULL
             AND length(trim(external_subject))>0 FOR UPDATE
            """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),userId);
        if(!valid)throw new SecurityException("Active OIDC-linked account required");
    }

    private void lifecycleEvent(UUID identityId,UUID userId,String event) {
        jdbc.update("""
            INSERT INTO public.iam_email_lifecycle_event
              (id,email_identity_id,user_id,action_code,occurred_at)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),identityId,userId,event,Timestamp.from(Instant.now()));
    }

    private void proofEvent(UUID challengeId,UUID identityId,UUID userId,
                            String purpose,Timestamp now) {
        jdbc.update("""
            INSERT INTO public.iam_email_verification_event
              (id,challenge_id,email_identity_id,user_id,purpose,occurred_at)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),challengeId,identityId,userId,purpose,now);
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
                WHERE email_identity_id=? AND purpose=?
                  AND consumed_at IS NULL AND expires_at>?
                  AND created_at>?
                """,Integer.class,identityId,purpose,Timestamp.from(now),
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
    boolean verifyRegistration(UUID challengeId,String presentedToken) {
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
        String stateAndPurpose=jdbc.query("""
            SELECT lifecycle_state,pending_reason FROM public.iam_email_identity WHERE id=?
            """,rs->{
                if(!rs.next())throw new SecurityException("Email identity unavailable");
                String state=rs.getString(1),reason=rs.getString(2);
                if("HISTORICAL".equals(state) || "CHANGE_EMAIL".equals(reason) ||
                   ("REGISTRATION".equals(requiredPurpose) && !"PENDING".equals(state)))
                    throw new SecurityException("Email identity is not eligible for this proof");
                return state;
            },identityId);
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("UPDATE public.iam_email_verification_challenge SET consumed_at=? WHERE id=?",
                now,challengeId);
        if("PENDING".equals(stateAndPurpose)) {
            // A newly proved registration/checkout email becomes CURRENT.
            int promoted=jdbc.update("""
                UPDATE public.iam_email_identity
                SET lifecycle_state='CURRENT',pending_reason=NULL,
                    pending_owner_id=NULL,current_owner_id=?,verified_at=?
                WHERE id=? AND lifecycle_state='PENDING' AND pending_reason='REGISTRATION'
                """,userId,now,identityId);
            if(promoted!=1)throw new SecurityException("Email identity admission changed");
        } else {
            jdbc.update("""
                UPDATE public.iam_email_identity
                SET verified_at=COALESCE(verified_at,?) WHERE id=? AND lifecycle_state='CURRENT'
                """,now,identityId);
        }
        jdbc.update("""
            UPDATE public.iam_user_account
            SET status='ACTIVE',updated_at=? WHERE id=? AND status='PENDING'
            """,now,userId);
        proofEvent(challengeId,identityId,userId,requiredPurpose,now);
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
