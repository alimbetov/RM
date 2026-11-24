package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * Service-only test mailbox: takes an already cryptographically resolved
 * actor, executes the production REGISTRATION purpose, and independently
 * reads persisted proof. No identity/verification state is seeded by SQL.
 * Encrypted challenge access is test-only; no external email delivery is claimed.
 */
public final class VerifiedBuyerEmailFixture {
    private VerifiedBuyerEmailFixture() {}

    public record Proof(UUID identityId,UUID challengeId) {}
    private record Challenge(UUID identityId,UUID challengeId,byte[] tokenEnvelope) {}

    public static Proof registerAndVerify(EmailIdentityService emails,EmailIdentityCrypto crypto,
                                         JdbcTemplate jdbc,VerifiedActor actor,String email){
        UUID buyer=actor.userId();
        assertThat(emails.myVerificationStatus(actor).emailVerified()).isFalse();
        assertThat(emails.requestInitialVerification(actor,email).emailVerified()).isFalse();
        List<Challenge> challenges=jdbc.query("""
                SELECT e.id,c.id,c.token_envelope
                FROM public.iam_email_identity e
                JOIN public.iam_email_verification_challenge c ON c.email_identity_id=e.id
                WHERE e.user_id=? AND e.active_lookup_hmac=? AND e.lifecycle_state='PENDING'
                  AND e.pending_owner_id=? AND e.verified_at IS NULL
                  AND c.purpose='REGISTRATION' AND c.consumed_at IS NULL
                """,(rs,n)->new Challenge(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBytes(3)),
                buyer,crypto.lookup(crypto.normalize(email)),buyer);
        // A generic assertion avoids exposing encrypted or raw token material.
        if(challenges.size()!=1)throw new AssertionError("Expected exactly one pending registration challenge");
        var c=challenges.getFirst();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_delivery_outbox WHERE challenge_id=?
                """,Integer.class,c.challengeId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_verification_event WHERE email_identity_id=?
                """,Integer.class,c.identityId())).isZero();
        String token=crypto.decrypt(c.tokenEnvelope());
        assertThatThrownBy(()->emails.verifyEmail(c.challengeId(),token)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_verification_challenge
                WHERE id=? AND consumed_at IS NULL
                """,Integer.class,c.challengeId())).isEqualTo(1);
        assertThat(emails.verifyRegistration(c.challengeId(),token)).isTrue();
        assertThat(emails.myVerificationStatus(actor).emailVerified()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity
                WHERE id=? AND user_id=? AND current_owner_id=? AND pending_owner_id IS NULL
                  AND lifecycle_state='CURRENT' AND pending_reason IS NULL AND verified_at IS NOT NULL
                """,Integer.class,c.identityId(),buyer,buyer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_verification_event e
                JOIN public.iam_email_verification_challenge c ON c.id=e.challenge_id
                WHERE e.email_identity_id=? AND e.challenge_id=? AND e.user_id=?
                  AND e.purpose='REGISTRATION' AND c.purpose='REGISTRATION' AND c.consumed_at IS NOT NULL
                """,Integer.class,c.identityId(),c.challengeId(),buyer)).isEqualTo(1);
        assertThatThrownBy(()->emails.verifyRegistration(c.challengeId(),token)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_verification_event WHERE email_identity_id=?
                """,Integer.class,c.identityId())).isEqualTo(1);
        return new Proof(c.identityId(),c.challengeId());
    }
}
