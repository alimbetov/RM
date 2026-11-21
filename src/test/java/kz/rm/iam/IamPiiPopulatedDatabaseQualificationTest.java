package kz.rm.iam;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Runs ONLY in the dedicated fresh PostgreSQL CI job via -Drm.pii.populated=true.
 * No real customer data, no external services, no production migration.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named="rm.pii.populated",matches="true")
@TestPropertySource(properties={
 "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
 "RM_IAM_PROFILE_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "RM_IAM_PHONE_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
 "rm.iam.pii.migration.enabled=true"
})
class IamPiiPopulatedDatabaseQualificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired LegacyIamPiiMigrationService migration;
    @Autowired IamPiiMigrationQualificationService qualifier;
    @Autowired GlobalUserProfileCrypto profileCrypto;
    @Autowired EmailIdentityCrypto emailCrypto;
    @Autowired EmailIdentityService emailService;
    @Autowired UserAccountRepository users;

    private static final int NAMED_AND_PHONED=150;
    private static final int EMAIL_ONLY=30;
    private static final int NAME_ONLY=20;

    private UUID legacy(String name,String phone,String email,String state) {
        UUID id=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO public.iam_user_account
              (id,external_subject,display_name,phone_e164,email_normalized,
               status,created_at,updated_at,row_version)
            VALUES (?,?,?,?,?,?,?,?,0)
            """,id,"load-test-"+id,name,phone,email,state,now,now);
        return id;
    }

    @Test @Order(1)
    void twoHundredPopulatedAccountsMigrateInCommittedBatchesWithoutPlaintext() {
        String batch=UUID.randomUUID().toString().substring(0,8);
        for(int i=0;i<NAMED_AND_PHONED;i++) {
            String phone="+7701"+String.format("%07d",i+2_000_000);
            String address="q-"+batch+"-"+i+"@example.kz";
            legacy("Person "+i,phone,address,"ACTIVE");
        }
        for(int i=0;i<EMAIL_ONLY;i++) {
            legacy(null,null,"guest-"+batch+"-"+i+"@example.kz","PENDING");
        }
        for(int i=0;i<NAME_ONLY;i++) {
            legacy("No phone "+i,null,null,"ACTIVE");
        }
        var preflight=qualifier.assertBackfillAdmissible();
        assertThat(preflight.plaintextAccounts()).isEqualTo(200);
        assertThat(preflight.encryptedProfiles()).isZero();
        assertThat(preflight.emailIdentities()).isZero();
        assertThatThrownBy(qualifier::assertReadyForPlaintextSeal)
                .isInstanceOf(SecurityException.class);

        int migrated=0,rounds=0;
        while(true) {
            int chunk=migration.migrateBatch(31);
            if(chunk==0)break;
            migrated+=chunk;
            assertThat(++rounds).isLessThan(15);
        }
        assertThat(migrated).isEqualTo(200);
        assertThat(rounds).isGreaterThan(1);
        var result=qualifier.assertReadyForPlaintextSeal();
        assertThat(result.accounts()).isEqualTo(200);
        assertThat(result.plaintextAccounts()).isZero();
        assertThat(result.encryptedProfiles()).isEqualTo(NAMED_AND_PHONED+NAME_ONLY);
        assertThat(result.emailIdentities()).isEqualTo(NAMED_AND_PHONED+EMAIL_ONLY);
        assertThat(result.currentVerifiedEmails()).isZero();
        assertThat(result.pendingEmails()).isEqualTo(NAMED_AND_PHONED+EMAIL_ONLY);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_pii.user_profile_event
            WHERE action_code='PROFILE_MIGRATED'
            """,Integer.class)).isEqualTo(NAMED_AND_PHONED+NAME_ONLY);
        assertThat(migration.migrateBatch(31)).isZero();
        // Only aggregate evidence is returned; never log/expose the source PII.
        assertThat(result.toString()).doesNotContain("@example.kz","+7701","Person");
    }

    @Test @Order(2) @Transactional
    void corruptedCiphertextFailsIntegritySeal() {
        UUID id=jdbc.queryForObject("""
            SELECT user_id FROM iam_pii.user_profile_secret
            WHERE phone_envelope IS NOT NULL LIMIT 1
            """,UUID.class);
        byte[] cipher=jdbc.queryForObject("""
            SELECT display_name_envelope FROM iam_pii.user_profile_secret WHERE user_id=?
            """,byte[].class,id);
        cipher[cipher.length-1]^=1;
        jdbc.update("""
            UPDATE iam_pii.user_profile_secret SET display_name_envelope=? WHERE user_id=?
            """,cipher,id);
        assertThatThrownBy(qualifier::assertReadyForPlaintextSeal)
                .isInstanceOf(SecurityException.class);
    }

    @Test @Order(3) @Transactional
    void differentCaseSameNormalizedEmailIsRejectedBeforeAnyScrub() {
        String address="dup-"+UUID.randomUUID()+"@example.kz";
        UUID first=legacy("Case 1",null,address,"ACTIVE");
        UUID second=legacy("Case 2",null,address.toUpperCase(),"ACTIVE");
        assertThatThrownBy(qualifier::assertBackfillAdmissible)
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public.iam_user_account
            WHERE id IN (?,?) AND display_name IS NOT NULL
            """,Integer.class,first,second)).isEqualTo(2);
    }

    @Test @Order(4) @Transactional
    void staleLegacyUpdateCannotBeSilentlyDiscardedByBackfill() {
        UUID id=jdbc.queryForObject("""
            SELECT user_id FROM iam_pii.user_profile_secret LIMIT 1
            """,UUID.class);
        jdbc.update("""
            UPDATE public.iam_user_account SET display_name=? WHERE id=?
            ""","Conflicting legacy edit",id);
        assertThatThrownBy(qualifier::assertBackfillAdmissible)
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->migration.migrateUser(id))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT display_name FROM public.iam_user_account WHERE id=?
            """,String.class,id)).isEqualTo("Conflicting legacy edit");
    }

    @Test @Order(5) @Transactional
    void emailOnlyAndPhoneWithoutNameAreDifferentCases() {
        UUID valid=legacy(null,null,"new-"+UUID.randomUUID()+"@example.kz","PENDING");
        assertThat(migration.migrateUser(valid)).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT verified_at FROM public.iam_email_identity WHERE user_id=?
            """,Timestamp.class,valid)).isNull();
        UUID invalid=legacy(null,"+77019876543",null,"ACTIVE");
        assertThatThrownBy(()->migration.migrateUser(invalid))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT phone_e164 FROM public.iam_user_account WHERE id=?
            """,String.class,invalid)).isNotNull();
    }

    @Test @Order(6) @Transactional
    void legacyEmailCompetingWithAnotherOwnersActiveIdentityBlocksCutover() {
        String email="claim-"+UUID.randomUUID()+"@example.kz";
        UUID owner=users.saveAndFlush(new UserAccountEntity(
                UUID.randomUUID(),"oidc-"+UUID.randomUUID(),"Owner")).getId();
        emailService.requestInitialVerification(
                new VerifiedActor(owner,Instant.now().plusSeconds(300)),email);
        UUID legacy=legacy("Duplicate",null,email,"ACTIVE");
        assertThatThrownBy(qualifier::assertBackfillAdmissible)
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->migration.migrateUser(legacy))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT display_name FROM public.iam_user_account WHERE id=?
            """,String.class,legacy)).isEqualTo("Duplicate");
    }

    @Test @Order(7) @Transactional
    void wrongGlobalKeyAndIncorrectPhoneIndexAreRejected() {
        UUID id=jdbc.queryForObject("""
            SELECT user_id FROM iam_pii.user_profile_secret
            WHERE phone_envelope IS NOT NULL LIMIT 1
            """,UUID.class);
        var missing=new GlobalUserProfileCrypto("","");
        var qualifierWithMissingKey=new IamPiiMigrationQualificationService(
                jdbc,missing,emailCrypto);
        assertThatThrownBy(qualifierWithMissingKey::assertReadyForPlaintextSeal)
                .isInstanceOf(SecurityException.class,IllegalStateException.class);
        byte[] bogus=new byte[32];
        Arrays.fill(bogus,(byte)99);
        jdbc.update("""
            UPDATE iam_pii.user_profile_secret SET phone_lookup_hmac=? WHERE user_id=?
            """,bogus,id);
        assertThatThrownBy(qualifier::assertReadyForPlaintextSeal)
                .isInstanceOf(SecurityException.class);
    }

    @Test @Order(8)
    void postgresTransactionalSealConstraintRejectsPlaintextReintroduction() throws Exception {
        // Read account before the exclusive DDL lock to avoid using a second
        // connection while holding that lock (which would block itself).
        UUID account=jdbc.queryForObject("""
            SELECT id FROM public.iam_user_account LIMIT 1
            """,UUID.class);
        try(Connection c=source.getConnection()) {
            Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("postgresql"));
            c.setAutoCommit(false);
            try(Statement st=c.createStatement()) {
                st.execute("LOCK TABLE public.iam_user_account IN ACCESS EXCLUSIVE MODE");
                st.execute("""
                    ALTER TABLE public.iam_user_account ADD CONSTRAINT ck_test_iam_pii_sealed
                    CHECK(display_name IS NULL AND email_normalized IS NULL
                       AND phone_e164 IS NULL) NOT VALID
                    """);
                st.execute("""
                    ALTER TABLE public.iam_user_account
                    VALIDATE CONSTRAINT ck_test_iam_pii_sealed
                    """);
                Savepoint guarded=c.setSavepoint();
                try(PreparedStatement invalid=c.prepareStatement("""
                    UPDATE public.iam_user_account SET display_name=? WHERE id=?
                    """)) {
                    invalid.setString(1,"Forbidden plaintext");
                    invalid.setObject(2,account);
                    assertThatThrownBy(invalid::executeUpdate).isInstanceOf(Exception.class);
                }
                c.rollback(guarded);
            } finally {
                c.rollback(); // DDL itself is test-only and never affects shared schema.
            }
        }
        assertThat(qualifier.assertReadyForPlaintextSeal().plaintextAccounts()).isZero();
    }
}
