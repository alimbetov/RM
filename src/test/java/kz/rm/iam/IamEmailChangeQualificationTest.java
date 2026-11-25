package kz.rm.iam;

import kz.rm.autoorder.AutoOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

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
 "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class IamEmailChangeQualificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired EmailIdentityService email;
    @Autowired AutoOrderService orders;

    private record Proof(UUID id,String token) {}
    private UUID user() {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "email-change-"+UUID.randomUUID(),"Test Owner")).getId();
    }
    private VerifiedActor actor(UUID id) {
        return new VerifiedActor(id,Instant.now().plusSeconds(300));
    }
    private String address() { return "mail-"+UUID.randomUUID()+"@example.kz"; }
    private Proof proof(String address,String purpose) {
        return jdbc.query("""
            SELECT c.id,c.token_envelope FROM public.iam_email_verification_challenge c
            JOIN public.iam_email_identity e ON c.email_identity_id=e.id
            WHERE e.lookup_hmac=? AND c.purpose=?
            ORDER BY c.created_at DESC,c.id DESC
            """,rs->{
                if(!rs.next())throw new IllegalStateException("Missing proof");
                return new Proof(rs.getObject(1,UUID.class),crypto.decrypt(rs.getBytes(2)));
            },crypto.lookup(crypto.normalize(address)),purpose);
    }
    private UUID current(UUID id) {
        return jdbc.query("""
            SELECT id FROM public.iam_email_identity WHERE current_owner_id=?
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,id);
    }
    private UUID pending(UUID id) {
        return jdbc.query("""
            SELECT id FROM public.iam_email_identity WHERE pending_owner_id=?
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,id);
    }
    private void firstVerified(UUID id,String address) {
        email.requestInitialVerification(actor(id),address);
        var p=proof(address,"REGISTRATION");
        assertThat(email.verifyRegistration(p.id(),p.token())).isTrue();
        assertThat(email.myVerificationStatus(actor(id)).emailVerified()).isTrue();
    }

    @Test void oldEmailRemainsCurrentUntilFreshNewProofAndOriginalIdentityIsImmutable() {
        UUID id=user(); String old=address(),next=address();
        firstVerified(id,old);
        UUID oldIdentity=current(id);
        byte[] oldCipher=jdbc.queryForObject("""
            SELECT email_envelope FROM public.iam_email_identity WHERE id=?
            """,byte[].class,oldIdentity);
        assertThat(email.requestEmailChange(actor(id),next).currentEmailVerified()).isTrue();
        UUID proposal=pending(id);
        assertThat(proposal).isNotNull().isNotEqualTo(oldIdentity);
        assertThat(current(id)).isEqualTo(oldIdentity);
        assertThat(email.myVerificationStatus(actor(id)).emailVerified()).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT verified_at FROM public.iam_email_identity WHERE id=?
            """,Timestamp.class,proposal)).isNull();
        var fresh=proof(next,"CHANGE_EMAIL");
        assertThatThrownBy(()->email.verifyRegistration(fresh.id(),fresh.token()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->email.verifyEmail(fresh.id(),fresh.token()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->email.confirmEmailChange(actor(id),fresh.id(),
                "bad-"+UUID.randomUUID())).isInstanceOf(SecurityException.class);
        assertThat(current(id)).isEqualTo(oldIdentity);

        assertThat(email.confirmEmailChange(actor(id),fresh.id(),fresh.token())
                .currentEmailVerified()).isTrue();
        assertThat(current(id)).isEqualTo(proposal);
        assertThat(pending(id)).isNull();
        assertThat(email.myVerificationStatus(actor(id)).emailVerified()).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT lifecycle_state FROM public.iam_email_identity WHERE id=?
            """,String.class,oldIdentity)).isEqualTo("HISTORICAL");
        assertThat(jdbc.queryForObject("""
            SELECT email_envelope FROM public.iam_email_identity WHERE id=?
            """,byte[].class,oldIdentity)).containsExactly(oldCipher);
        assertThatThrownBy(()->email.confirmEmailChange(actor(id),fresh.id(),fresh.token()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_email_lifecycle_event
            WHERE user_id=? AND action_code='CHANGE_CONFIRMED'
            """,Integer.class,id)).isEqualTo(1);
    }

    @Test void cancelAndReplacePendingProofCannotModifyCurrentEmail() {
        UUID id=user();String old=address(),first=address(),second=address();
        firstVerified(id,old);UUID oldId=current(id);
        email.requestEmailChange(actor(id),first);
        var stale=proof(first,"CHANGE_EMAIL");
        email.requestEmailChange(actor(id),second);
        assertThatThrownBy(()->email.confirmEmailChange(actor(id),stale.id(),stale.token()))
                .isInstanceOf(SecurityException.class);
        assertThat(current(id)).isEqualTo(oldId);
        var pendingSecond=proof(second,"CHANGE_EMAIL");
        email.cancelEmailChange(actor(id));email.cancelEmailChange(actor(id));
        assertThatThrownBy(()->email.confirmEmailChange(actor(id),
                pendingSecond.id(),pendingSecond.token()))
                .isInstanceOf(SecurityException.class);
        assertThat(current(id)).isEqualTo(oldId);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_email_lifecycle_event
            WHERE user_id=? AND action_code='CHANGE_CANCELLED'
            """,Integer.class,id)).isEqualTo(2);
    }

    @Test void foreignActorAndExistingAddressCannotClaimEmailChange() {
        UUID a=user(),b=user();String original=address(),next=address();
        firstVerified(a,original);firstVerified(b,next);
        assertThatThrownBy(()->email.requestEmailChange(actor(a),next))
                .isInstanceOf(SecurityException.class);
        assertThat(email.requestEmailChange(actor(a),original).currentEmailVerified()).isTrue();
        assertThat(pending(a)).isNull();
        String candidate=address();
        email.requestEmailChange(actor(a),candidate);
        var ch=proof(candidate,"CHANGE_EMAIL");
        assertThatThrownBy(()->email.confirmEmailChange(actor(b),ch.id(),ch.token()))
                .isInstanceOf(SecurityException.class);
        assertThat(current(a)).isNotNull();
        assertThat(current(b)).isNotNull();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_email_verification_event
            WHERE purpose='CHANGE_EMAIL' AND email_identity_id=?
            """,Integer.class,pending(a))).isZero();
    }

    @Test void historicalEmailCanBeUsedByNewBuyerWithoutReassigningOldUser() {
        UUID first=user();String old=address(),next=address();
        firstVerified(first,old);
        UUID before=current(first);
        email.requestEmailChange(actor(first),next);
        var ch=proof(next,"CHANGE_EMAIL");
        email.confirmEmailChange(actor(first),ch.id(),ch.token());
        var fresh=email.findOrProvision(old);
        assertThat(fresh.userId()).isNotEqualTo(first);
        assertThat(fresh.identityId()).isNotEqualTo(before);
        assertThat(jdbc.queryForObject("""
            SELECT lifecycle_state FROM public.iam_email_identity WHERE id=?
            """,String.class,before)).isEqualTo("HISTORICAL");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_email_identity WHERE lookup_hmac=?
            """,Integer.class,crypto.lookup(old))).isEqualTo(2);
    }

    @Test void checkoutProofOnPendingChangeNeverConfirmsNewAddress() {
        UUID owner=user();String old=address(),next=address();
        firstVerified(owner,old);
        email.requestEmailChange(actor(owner),next);
        UUID before=current(owner);
        assertThatThrownBy(()->email.findOrProvision(next))
                .isInstanceOf(SecurityException.class);
        assertThat(current(owner)).isEqualTo(before);
    }

    @Test void twoConfirmationsSerializeAndOnlyOnePromotes() throws Exception {
        UUID id=user();String old=address(),next=address();
        firstVerified(id,old);
        email.requestEmailChange(actor(id),next);
        var ch=proof(next,"CHANGE_EMAIL");
        var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{
                go.await(5,TimeUnit.SECONDS);
                try {email.confirmEmailChange(actor(id),ch.id(),ch.token()); return true;}
                catch(SecurityException rejected) {return false;}
            });
            var b=pool.submit(()->{
                go.await(5,TimeUnit.SECONDS);
                try {email.confirmEmailChange(actor(id),ch.id(),ch.token()); return true;}
                catch(SecurityException rejected) {return false;}
            });
            go.countDown();
            assertThat((a.get(25,TimeUnit.SECONDS)?1:0)
                       +(b.get(25,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_email_lifecycle_event
            WHERE user_id=? AND action_code='CHANGE_CONFIRMED'
            """,Integer.class,id)).isEqualTo(1);
        assertThat(current(id)).isNotNull();
    }
}
