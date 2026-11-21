package kz.rm.iam;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
 "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
 "RM_IAM_PROFILE_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "RM_IAM_PHONE_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
 "rm.iam.pii.migration.enabled=true"
})
class IamGlobalUserProfileQualificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired GlobalUserProfileCrypto crypto;
    @Autowired GlobalUserProfileService profiles;
    @Autowired LegacyIamPiiMigrationService migration;
    @Autowired EmailIdentityCrypto emailCrypto;
    @Autowired EmailIdentityService emails;
    @Autowired DataSource dataSource;

    private VerifiedActor actor(UUID id) {
        return new VerifiedActor(id,Instant.now().plusSeconds(300));
    }
    private UUID oidcUser() {
        return users.saveAndFlush(new UserAccountEntity(UUID.randomUUID(),
            "pii-protected-"+UUID.randomUUID(),"Provided name not saved as plaintext")).getId();
    }
    private UUID legacyUser(String name,String phone,String email) {
        UUID id=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO public.iam_user_account
              (id,external_subject,display_name,phone_e164,email_normalized,
               status,created_at,updated_at,row_version)
            VALUES (?,?,?,?,?,'ACTIVE',?,?,0)
            """,id,"legacy-pii-"+UUID.randomUUID(),name,phone,email,now,now);
        return id;
    }
    private String email() { return "old-"+UUID.randomUUID()+"@example.kz"; }

    @Test void profileSavedEncryptedAndNeverInLegacyJPAColumns() {
        UUID id=oidcUser();
        assertThat(jdbc.queryForObject("""
            SELECT display_name FROM public.iam_user_account WHERE id=?
            """,String.class,id)).isNull();
        var created=profiles.saveOwnProfile(actor(id),"Example User","+77011234567",0);
        assertThat(created.displayName()).isEqualTo("Example User");
        assertThat(created.phoneE164()).isEqualTo("+77011234567");
        assertThat(created.rowVersion()).isZero();
        byte[] ciphertext=jdbc.queryForObject("""
            SELECT display_name_envelope FROM iam_pii.user_profile_secret WHERE user_id=?
            """,byte[].class,id);
        assertThat(new String(ciphertext,java.nio.charset.StandardCharsets.UTF_8))
            .doesNotContain("Example User");
        assertThat(profiles.myProfile(actor(id)).displayName()).isEqualTo("Example User");
        assertThat(profiles.saveOwnProfile(actor(id),"Updated User",null,0).rowVersion()).isEqualTo(1);
        assertThat(profiles.myProfile(actor(id)).phoneE164()).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_pii.user_profile_event WHERE user_id=?
            """,Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT display_name FROM public.iam_user_account WHERE id=?
            """,String.class,id)).isNull();
    }

    @Test void staleVersionAndUnauthorizedOrSuspendedUserFailClosed() {
        UUID id=oidcUser(),outsider=oidcUser();
        profiles.saveOwnProfile(actor(id),"One",null,0);
        assertThatThrownBy(()->profiles.saveOwnProfile(actor(id),"Two",null,5))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->profiles.myProfile(actor(outsider)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_pii.user_profile_event WHERE user_id=?
            """,Integer.class,id)).isEqualTo(1);
        jdbc.update("UPDATE public.iam_user_account SET status='SUSPENDED' WHERE id=?",id);
        assertThatThrownBy(()->profiles.myProfile(actor(id))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->profiles.saveOwnProfile(actor(id),"Again",null,0))
            .isInstanceOf(SecurityException.class);
    }

    @Test void userBoundAadRejectsCiphertextSwapAndWrongKey() {
        UUID first=oidcUser(),second=oidcUser();
        byte[] ciphertext=crypto.encrypt(first,"display_name","Confidential");
        assertThat(crypto.decrypt(first,"display_name",ciphertext)).isEqualTo("Confidential");
        assertThatThrownBy(()->crypto.decrypt(second,"display_name",ciphertext))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->crypto.decrypt(first,"phone_e164",ciphertext))
            .isInstanceOf(SecurityException.class);
        byte[] swapped=ciphertext.clone();swapped[swapped.length-1]^=1;
        assertThatThrownBy(()->crypto.decrypt(first,"display_name",swapped))
            .isInstanceOf(SecurityException.class);
        var missing=new GlobalUserProfileCrypto("","");
        assertThatThrownBy(()->missing.encrypt(first,"display_name","Private"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->missing.decrypt(first,"display_name",ciphertext))
            .isInstanceOf(SecurityException.class);
    }

    @Test void offlineBackfillEncryptsProfileAndPendingLegacyEmailThenScrubsPlaintext() {
        String oldEmail=email(),phone="+7701"+String.format("%07d",
                Math.floorMod(UUID.randomUUID().hashCode(),10_000_000));
        UUID id=legacyUser("Legacy User",phone,oldEmail);
        assertThat(migration.migrateBatch(500)).isPositive();
        assertThat(jdbc.queryForObject("""
            SELECT display_name FROM public.iam_user_account WHERE id=?
            """,String.class,id)).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT phone_e164 FROM public.iam_user_account WHERE id=?
            """,String.class,id)).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT email_normalized FROM public.iam_user_account WHERE id=?
            """,String.class,id)).isNull();
        assertThat(profiles.myProfile(actor(id)).displayName()).isEqualTo("Legacy User");
        assertThat(profiles.myProfile(actor(id)).phoneE164()).isEqualTo(phone);
        assertThat(jdbc.queryForObject("""
            SELECT verified_at FROM public.iam_email_identity WHERE user_id=?
            """,Timestamp.class,id)).isNull();
        assertThat(emails.myVerificationStatus(actor(id)).emailVerified()).isFalse();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_email_identity
            WHERE user_id=? AND active_lookup_hmac=?
            """,Integer.class,id,emailCrypto.lookup(oldEmail))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_pii.user_profile_event
            WHERE user_id=? AND action_code='PROFILE_MIGRATED'
            """,Integer.class,id)).isEqualTo(1);
        assertThat(migration.migrateBatch(500)).isZero();
    }

    @Test void offlineBackfillCannotRunWhenDisabledAndMissingKeysFailClosed() {
        UUID id=legacyUser("Offline Test",null,null);
        var blocked=new LegacyIamPiiMigrationService(jdbc,crypto,emailCrypto,false);
        assertThatThrownBy(()->blocked.migrateBatch(1)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT display_name FROM public.iam_user_account WHERE id=?
            """,String.class,id)).isEqualTo("Offline Test");
    }

    @Test void offlineMigrationRejectsExistingEmailClaimWithoutScrubbingLegacyPII() {
        String shared=email();
        UUID first=oidcUser(),second=legacyUser("Second",null,shared);
        emails.requestInitialVerification(actor(first),shared);
        assertThatThrownBy(()->migration.migrateBatch(500))
            .isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("""
            SELECT display_name FROM public.iam_user_account WHERE id=?
            """,String.class,second)).isEqualTo("Second");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_pii.user_profile_secret WHERE user_id=?
            """,Integer.class,second)).isZero();
    }

    @Test void globalPhoneHmacIsUniqueAcrossUsersAndNeverPlaintext() {
        UUID first=oidcUser(),second=oidcUser();
        String number="+77271234567";
        profiles.saveOwnProfile(actor(first),"First",number,0);
        assertThat(jdbc.queryForObject("""
            SELECT phone_lookup_hmac FROM iam_pii.user_profile_secret WHERE user_id=?
            """,byte[].class,first)).hasSize(32);
        assertThatThrownBy(()->profiles.saveOwnProfile(actor(second),"Second",number,0))
            .isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_pii.user_profile_secret WHERE user_id=?
            """,Integer.class,second)).isZero();
    }

    @Test void postgresProfileAuditRejectsMutation() throws Exception {
        try(var conn=dataSource.getConnection()){
            Assumptions.assumeTrue(conn.getMetaData().getDatabaseProductName()
                .toLowerCase().contains("postgresql"));
        }
        UUID id=oidcUser();
        profiles.saveOwnProfile(actor(id),"Auditable",null,0);
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM iam_pii.user_profile_event WHERE user_id=?
            """,id)).isInstanceOf(Exception.class);
    }
}
