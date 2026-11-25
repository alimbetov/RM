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
        return registerAndVerify(emails,crypto,jdbc,actor,email,null);
    }

    public static Proof registerAndVerify(EmailIdentityService emails,EmailIdentityCrypto crypto,
                                         JdbcTemplate jdbc,VerifiedActor actor,String email,
                                         java.util.function.BiConsumer<String,String> capture){
        UUID buyer=actor.userId();
        assertThat(emails.myVerificationStatus(actor).emailVerified()).isFalse();
        snapshot(jdbc,buyer,capture,"BEFORE");
        assertThat(emails.requestInitialVerification(actor,email).emailVerified()).isFalse();
        snapshot(jdbc,buyer,capture,"PENDING");
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
        snapshot(jdbc,buyer,capture,"WRONG_PURPOSE_DENIED");
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
        snapshot(jdbc,buyer,capture,"CURRENT");
        assertThatThrownBy(()->emails.verifyRegistration(c.challengeId(),token)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_verification_event WHERE email_identity_id=?
                """,Integer.class,c.identityId())).isEqualTo(1);
        snapshot(jdbc,buyer,capture,"REPLAY_DENIED");
        return new Proof(c.identityId(),c.challengeId());
    }

    // Read committed rows after each production service transaction returns.
    // Export neither email/HMAC, token/hash/envelope nor OIDC subject.
    private static void snapshot(JdbcTemplate jdbc,UUID buyer,
                                 java.util.function.BiConsumer<String,String> capture,String phase){
        if(capture==null)return;
        String row=jdbc.queryForObject("""
                SELECT row_to_json(p)::text FROM (
                    SELECT u.id AS buyer,u.status AS account_status,
                           e.id AS identity_id,e.user_id AS identity_buyer,
                           e.lifecycle_state AS identity_state,e.pending_reason,
                           e.pending_owner_id AS pending_owner,e.current_owner_id AS current_owner,
                           e.created_at AS identity_created_at,e.verified_at,
                           c.id AS challenge_id,c.email_identity_id AS challenge_identity,
                           c.purpose AS challenge_purpose,c.created_at AS challenge_created_at,
                           c.expires_at,c.consumed_at,
                           ev.id AS registration_event,ev.user_id AS registration_buyer,
                           ev.email_identity_id AS event_identity,ev.challenge_id AS event_challenge,
                           ev.purpose AS registration_purpose,ev.occurred_at AS event_at,
                           d.id AS delivery_id,d.email_identity_id AS delivery_identity,d.challenge_id AS delivery_challenge,
                           (SELECT count(*) FROM public.iam_email_identity i WHERE i.user_id=u.id) AS identities,
                           (SELECT count(*) FROM public.iam_email_verification_challenge ch
                              JOIN public.iam_email_identity i ON i.id=ch.email_identity_id WHERE i.user_id=u.id) AS challenges,
                           (SELECT count(*) FROM public.iam_email_verification_event v WHERE v.user_id=u.id) AS events,
                           (SELECT count(*) FROM public.iam_email_delivery_outbox x
                              JOIN public.iam_email_identity i ON i.id=x.email_identity_id WHERE i.user_id=u.id) AS deliveries
                    FROM public.iam_user_account u
                    LEFT JOIN public.iam_email_identity e ON e.user_id=u.id
                    LEFT JOIN public.iam_email_verification_challenge c ON c.email_identity_id=e.id
                    LEFT JOIN public.iam_email_verification_event ev ON ev.email_identity_id=e.id
                    LEFT JOIN public.iam_email_delivery_outbox d ON d.challenge_id=c.id
                    WHERE u.id=?
                ) p
                """,String.class,buyer);
        if(row==null)throw new AssertionError("Missing registration SQL snapshot");
        capture.accept(phase,row);
    }
}
