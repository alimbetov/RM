package kz.rm.iam;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
        "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class IamInitialEmailRegistrationQualificationTest {
    @Autowired EmailIdentityService emails;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;

    private record Challenge(UUID id,String token,String purpose) {}
    private VerifiedActor actor(UUID id) {
        return new VerifiedActor(id,Instant.now().plusSeconds(300));
    }
    private UUID oidcAccount() {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
                "pii-oidc-"+UUID.randomUUID(),"Test identity")).getId();
    }
    private String uniqueEmail() {
        return "iam-pii-"+UUID.randomUUID()+"@example.kz";
    }
    private UUID identityId(String email) {
        return jdbc.queryForObject("""
                SELECT id FROM public.iam_email_identity WHERE lookup_hmac=?
                """,UUID.class,crypto.lookup(crypto.normalize(email)));
    }
    private Challenge challenge(String email,String purpose) {
        return jdbc.query("""
                SELECT c.id,c.token_envelope,c.purpose
                FROM public.iam_email_verification_challenge c
                JOIN public.iam_email_identity e ON e.id=c.email_identity_id
                WHERE e.lookup_hmac=? AND c.purpose=?
                ORDER BY c.created_at DESC,c.id DESC
                """,rs->{
                    if(!rs.next())throw new IllegalStateException("No email challenge");
                    return new Challenge(rs.getObject(1,UUID.class),
                            crypto.decrypt(rs.getBytes(2)),rs.getString(3));
                },crypto.lookup(crypto.normalize(email)),purpose);
    }
    private int events(UUID identityId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_verification_event
                WHERE email_identity_id=?
                """,Integer.class,identityId);
    }

    @Test void firstRegistrationCreatesUnverifiedEncryptedEmailAndQueuesOneChallenge() {
        String email=uniqueEmail();
        emails.registerPendingEmail("  "+email.toUpperCase()+"  ");
        UUID identity=identityId(email);
        assertThat(identity).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT verified_at FROM public.iam_email_identity WHERE id=?
                """,Timestamp.class,identity)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT u.status FROM public.iam_user_account u
                JOIN public.iam_email_identity e ON e.user_id=u.id WHERE e.id=?
                """,String.class,identity)).isEqualTo("PENDING");
        byte[] cipher=jdbc.queryForObject("""
                SELECT email_envelope FROM public.iam_email_identity WHERE id=?
                """,byte[].class,identity);
        assertThat(new String(cipher,StandardCharsets.UTF_8)).doesNotContain(email);
        assertThat(crypto.decrypt(cipher)).isEqualTo(email);
        var challenge=challenge(email,"REGISTRATION");
        assertThat(challenge.purpose()).isEqualTo("REGISTRATION");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_delivery_outbox
                WHERE challenge_id=? AND state='PENDING'
                """,Integer.class,challenge.id())).isEqualTo(1);
        emails.registerPendingEmail(email);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_verification_challenge
                WHERE email_identity_id=? AND purpose='REGISTRATION'
                """,Integer.class,identity)).isEqualTo(1);
        assertThat(events(identity)).isZero();
    }

    @Test void correctEmailProofIsSingleUseAndNeverCreatesOidcSession() {
        String email=uniqueEmail();
        emails.registerPendingEmail(email);
        UUID identity=identityId(email);
        var proof=challenge(email,"REGISTRATION");
        assertThatThrownBy(()->emails.verifyEmail(proof.id(),proof.token()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->emails.verifyRegistration(proof.id(),"not-the-right-token-"+UUID.randomUUID()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at FROM public.iam_email_verification_challenge WHERE id=?
                """,Timestamp.class,proof.id())).isNull();
        assertThat(emails.verifyRegistration(proof.id(),proof.token())).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT verified_at IS NOT NULL FROM public.iam_email_identity WHERE id=?
                """,Boolean.class,identity)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT u.status FROM public.iam_user_account u
                JOIN public.iam_email_identity e ON e.user_id=u.id WHERE e.id=?
                """,String.class,identity)).isEqualTo("ACTIVE");
        assertThat(events(identity)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT purpose FROM public.iam_email_verification_event
                WHERE email_identity_id=?
                """,String.class,identity)).isEqualTo("REGISTRATION");
        assertThatThrownBy(()->emails.verifyRegistration(proof.id(),proof.token()))
                .isInstanceOf(SecurityException.class);
        assertThat(events(identity)).isEqualTo(1);
        // Email proof is not a verified bearer subject and never creates an OIDC link.
        assertThat(jdbc.queryForObject("""
                SELECT u.external_subject FROM public.iam_user_account u
                JOIN public.iam_email_identity e ON e.user_id=u.id WHERE e.id=?
                """,String.class,identity)).isNull();
    }

    @Test void activeOidcUserCanVerifyFirstEmailButNotReplaceIt() {
        UUID userId=oidcAccount();
        String email=uniqueEmail();
        assertThat(emails.myVerificationStatus(actor(userId)).emailVerified()).isFalse();
        assertThat(emails.requestInitialVerification(actor(userId),email).emailVerified()).isFalse();
        UUID identity=identityId(email);
        assertThat(jdbc.queryForObject("""
                SELECT user_id FROM public.iam_email_identity WHERE id=?
                """,UUID.class,identity)).isEqualTo(userId);
        assertThatThrownBy(()->emails.requestInitialVerification(actor(userId),uniqueEmail()))
                .isInstanceOf(SecurityException.class);
        // A public email-only submission cannot attach a new proof to this ACTIVE account.
        emails.registerPendingEmail(email);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_verification_challenge
                WHERE email_identity_id=? AND purpose='REGISTRATION'
                """,Integer.class,identity)).isEqualTo(1);

        var proof=challenge(email,"REGISTRATION");
        assertThat(emails.verifyRegistration(proof.id(),proof.token())).isTrue();
        assertThat(emails.myVerificationStatus(actor(userId)).emailVerified()).isTrue();
        assertThat(emails.requestInitialVerification(actor(userId),email).emailVerified()).isTrue();
        assertThatThrownBy(()->emails.requestInitialVerification(actor(userId),uniqueEmail()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity WHERE user_id=?
                """,Integer.class,userId)).isEqualTo(1);
    }

    @Test void oneAccountCannotClaimOtherUsersEmailOrLegacyPlaintextEmail() {
        UUID owner=oidcAccount(),outsider=oidcAccount();
        String email=uniqueEmail();
        emails.requestInitialVerification(actor(owner),email);
        assertThatThrownBy(()->emails.requestInitialVerification(actor(outsider),email))
                .isInstanceOf(SecurityException.class);
        String old=uniqueEmail();
        jdbc.update("""
                UPDATE public.iam_user_account SET email_normalized=? WHERE id=?
                """,old,owner);
        assertThatThrownBy(()->emails.requestInitialVerification(actor(outsider),old))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity WHERE user_id=?
                """,Integer.class,outsider)).isZero();
    }

    @Test void checkoutChallengeAndRegistrationChallengeCannotCrossPurpose() {
        String email=uniqueEmail();
        var identity=emails.findOrProvision(email);
        emails.queueVerification(identity.identityId());
        var checkout=challenge(email,"CHECKOUT");
        assertThatThrownBy(()->emails.verifyRegistration(checkout.id(),checkout.token()))
                .isInstanceOf(SecurityException.class);
        assertThat(emails.verifyEmail(checkout.id(),checkout.token())).isEqualTo(identity.userId());
        assertThat(events(identity.identityId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT purpose FROM public.iam_email_verification_event WHERE challenge_id=?
                """,String.class,checkout.id())).isEqualTo("CHECKOUT");
    }

    @Test void expiredProofAndSuspendedAccountCannotBeVerified() {
        String expiredEmail=uniqueEmail();
        emails.registerPendingEmail(expiredEmail);
        var expired=challenge(expiredEmail,"REGISTRATION");
        jdbc.update("""
                UPDATE public.iam_email_verification_challenge SET expires_at=?
                WHERE id=?
                """,Timestamp.from(Instant.now().minusSeconds(1)),expired.id());
        assertThatThrownBy(()->emails.verifyRegistration(expired.id(),expired.token()))
                .isInstanceOf(SecurityException.class);
        assertThat(events(identityId(expiredEmail))).isZero();

        String disabledEmail=uniqueEmail();
        emails.registerPendingEmail(disabledEmail);
        UUID identity=identityId(disabledEmail);
        var proof=challenge(disabledEmail,"REGISTRATION");
        jdbc.update("""
                UPDATE public.iam_user_account SET status='SUSPENDED'
                WHERE id=(SELECT user_id FROM public.iam_email_identity WHERE id=?)
                """,identity);
        assertThatThrownBy(()->emails.verifyRegistration(proof.id(),proof.token()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
                SELECT verified_at FROM public.iam_email_identity WHERE id=?
                """,Timestamp.class,identity)).isNull();
        assertThat(events(identity)).isZero();
    }

    @Test void twoConcurrentRegistrationsHaveOneUnverifiedIdentityAndOneChallenge() throws Exception {
        String email=uniqueEmail();
        var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                release.await(5,TimeUnit.SECONDS);
                emails.registerPendingEmail(email);
                return true;
            });
            var second=pool.submit(()->{
                release.await(5,TimeUnit.SECONDS);
                emails.registerPendingEmail(email.toUpperCase());
                return true;
            });
            release.countDown();
            assertThat(first.get(25,TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(25,TimeUnit.SECONDS)).isTrue();
        }
        UUID identity=identityId(email);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity WHERE lookup_hmac=?
                """,Integer.class,crypto.lookup(email))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_verification_challenge
                WHERE email_identity_id=? AND purpose='REGISTRATION'
                """,Integer.class,identity)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT verified_at FROM public.iam_email_identity WHERE id=?
                """,Timestamp.class,identity)).isNull();
    }

    @Test void twoConcurrentProofsConsumeOneChallengeOnly() throws Exception {
        String email=uniqueEmail();
        emails.registerPendingEmail(email);
        var proof=challenge(email,"REGISTRATION");
        var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                go.await(5,TimeUnit.SECONDS);
                try {return emails.verifyRegistration(proof.id(),proof.token());}
                catch(SecurityException rejected) {return false;}
            });
            var second=pool.submit(()->{
                go.await(5,TimeUnit.SECONDS);
                try {return emails.verifyRegistration(proof.id(),proof.token());}
                catch(SecurityException rejected) {return false;}
            });
            go.countDown();
            int successes=(first.get(25,TimeUnit.SECONDS)?1:0)
                    +(second.get(25,TimeUnit.SECONDS)?1:0);
            assertThat(successes).isEqualTo(1);
        }
        assertThat(events(identityId(email))).isEqualTo(1);
    }

    @Test void postgresProofEvidenceCannotBeRewritten() throws Exception {
        try(var connection=source.getConnection()) {
            Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
        }
        String email=uniqueEmail();
        emails.registerPendingEmail(email);
        var proof=challenge(email,"REGISTRATION");
        emails.verifyRegistration(proof.id(),proof.token());
        assertThatThrownBy(()->jdbc.update("""
                DELETE FROM public.iam_email_verification_event
                WHERE challenge_id=?
                """,proof.id())).isInstanceOf(Exception.class);
        assertThat(events(identityId(email))).isEqualTo(1);
    }
}
