package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only, aggregate-only qualification of a populated PostgreSQL migration.
 * Never emits a name, phone, email, key, decrypted value or account UUID.
 *
 * Before offline backfill: inspect legacy/profile consistency, duplicate normalized
 * email claims and decryptability of existing envelopes. After backfill: require
 * zero plaintext and prove encrypted profile/email round-trips.
 *
 * A successful synthetic run is not a production-database security approval.
 */
@Service
public class IamPiiMigrationQualificationService {
    private final JdbcTemplate jdbc;
    private final GlobalUserProfileCrypto profiles;
    private final EmailIdentityCrypto emails;

    public IamPiiMigrationQualificationService(JdbcTemplate jdbc,
                                               GlobalUserProfileCrypto profiles,
                                               EmailIdentityCrypto emails) {
        this.jdbc=jdbc;
        this.profiles=profiles;
        this.emails=emails;
    }

    public record Inventory(long accounts,long plaintextAccounts,
                            long encryptedProfiles,long emailIdentities,
                            long historicalEmails,long pendingEmails,
                            long currentVerifiedEmails) {
        public boolean plaintextCleared() {return plaintextAccounts==0;}
    }

    @Transactional(readOnly=true)
    public Inventory inventory() {
        return jdbc.query("""
            SELECT
              (SELECT count(*) FROM public.iam_user_account),
              (SELECT count(*) FROM public.iam_user_account
                 WHERE display_name IS NOT NULL OR phone_e164 IS NOT NULL
                    OR email_normalized IS NOT NULL),
              (SELECT count(*) FROM iam_pii.user_profile_secret),
              (SELECT count(*) FROM public.iam_email_identity),
              (SELECT count(*) FROM public.iam_email_identity
                 WHERE lifecycle_state='HISTORICAL'),
              (SELECT count(*) FROM public.iam_email_identity
                 WHERE lifecycle_state='PENDING'),
              (SELECT count(*) FROM public.iam_email_identity
                 WHERE lifecycle_state='CURRENT' AND verified_at IS NOT NULL)
            """,rs->{
                if(!rs.next())throw new IllegalStateException("IAM PII inventory unavailable");
                return new Inventory(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                         rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7));
            });
    }

    /**
     * Fail-closed dry-run on the actual loaded dataset. No mutations.
     * Validation precedes any optional offline plaintext scrub and detects
     * legacy normalized-email duplicates that SQL case-sensitive UNIQUE misses.
     */
    @Transactional(readOnly=true)
    public Inventory assertBackfillAdmissible() {
        // Only keyed hashes/UUIDs; never persist or report any source PII.
        Map<ByteBuffer,UUID> claims=new HashMap<>();
        jdbc.query("""
            SELECT user_id,active_lookup_hmac FROM public.iam_email_identity
            WHERE active_lookup_hmac IS NOT NULL
            """,rs->{
                while(rs.next()) {
                    ByteBuffer index=ByteBuffer.wrap(rs.getBytes(2));
                    UUID userId=rs.getObject(1,UUID.class);
                    UUID previous=claims.putIfAbsent(index,userId);
                    if(previous!=null&&!previous.equals(userId))
                        throw new SecurityException("Duplicate active email identity");
                }
                return null;
            });

        jdbc.query("""
            SELECT u.id,u.display_name,u.phone_e164,u.email_normalized,
                   p.display_name_envelope,p.phone_envelope
            FROM public.iam_user_account u
            LEFT JOIN iam_pii.user_profile_secret p ON p.user_id=u.id
            WHERE u.display_name IS NOT NULL OR u.phone_e164 IS NOT NULL
               OR u.email_normalized IS NOT NULL
            ORDER BY u.id
            """,rs->{
                while(rs.next()) {
                    UUID id=rs.getObject(1,UUID.class);
                    String name=rs.getString(2),phone=rs.getString(3),email=rs.getString(4);
                    byte[] protectedName=rs.getBytes(5),protectedPhone=rs.getBytes(6);
                    if(protectedName!=null) {
                        String actual=profiles.decrypt(id,"display_name",protectedName);
                        if(name!=null &&
                                !GlobalUserProfileService.validateName(name).equals(actual))
                            throw new SecurityException("Legacy name conflicts with encrypted profile");
                        String actualPhone=protectedPhone==null?null:
                                profiles.decrypt(id,"phone_e164",protectedPhone);
                        if(phone!=null && !phone.equals(actualPhone))
                            throw new SecurityException("Legacy phone conflicts with encrypted profile");
                    } else if(name!=null||phone!=null) {
                        if(name==null)
                            throw new SecurityException("Legacy phone missing required display name");
                        GlobalUserProfileService.validateName(name);
                        GlobalUserProfileService.validatePhone(phone);
                    }
                    if(email!=null) {
                        String normalized=emails.normalize(email);
                        ByteBuffer index=ByteBuffer.wrap(emails.lookup(normalized));
                        UUID previous=claims.putIfAbsent(index,id);
                        if(previous!=null&&!previous.equals(id))
                            throw new SecurityException("Normalized legacy email owner conflict");
                    }
                }
                return null;
            });
        verifyEncryptedStorage();
        return inventory();
    }

    @Transactional(readOnly=true)
    public Inventory assertReadyForPlaintextSeal() {
        Inventory report=assertBackfillAdmissible();
        if(!report.plaintextCleared())
            throw new SecurityException("IAM legacy PII still present; plaintext seal forbidden");
        return report;
    }

    private void verifyEncryptedStorage() {
        // A genuinely empty database must not pass the cutover gate while the
        // mandatory global IAM keys are missing or misconfigured.
        UUID canary=UUID.fromString("00000000-0000-4000-8000-000000000001");
        byte[] probe=profiles.encrypt(canary,"display_name","iam-pii-probe");
        if(!"iam-pii-probe".equals(profiles.decrypt(canary,"display_name",probe)))
            throw new SecurityException("Global IAM profile crypto unavailable");
        if(profiles.phoneLookup("+77010000000").length!=32)
            throw new SecurityException("Global IAM phone lookup unavailable");
        byte[] mailProbe=emails.encrypt("pii-check@example.invalid");
        if(!"pii-check@example.invalid".equals(emails.decrypt(mailProbe)) ||
                emails.lookup("pii-check@example.invalid").length!=32)
            throw new SecurityException("Global IAM email crypto unavailable");
        jdbc.query("""
            SELECT user_id,display_name_envelope,phone_envelope,phone_lookup_hmac
            FROM iam_pii.user_profile_secret
            """,rs->{
                while(rs.next()) {
                    UUID id=rs.getObject(1,UUID.class);
                    String name=profiles.decrypt(id,"display_name",rs.getBytes(2));
                    GlobalUserProfileService.validateName(name);
                    byte[] phoneEnvelope=rs.getBytes(3),lookup=rs.getBytes(4);
                    if(phoneEnvelope==null) {
                        if(lookup!=null)
                            throw new SecurityException("Orphan phone lookup index");
                    } else {
                        String phone=profiles.decrypt(id,"phone_e164",phoneEnvelope);
                        GlobalUserProfileService.validatePhone(phone);
                        if(lookup==null ||
                                !MessageDigest.isEqual(lookup,profiles.phoneLookup(phone)))
                            throw new SecurityException("Phone lookup integrity mismatch");
                    }
                }
                return null;
            });
        jdbc.query("""
            SELECT lookup_hmac,email_envelope FROM public.iam_email_identity
            """,rs->{
                while(rs.next()) {
                    byte[] lookup=rs.getBytes(1);
                    String normalized=emails.normalize(emails.decrypt(rs.getBytes(2)));
                    if(lookup==null ||
                            !MessageDigest.isEqual(lookup,emails.lookup(normalized)))
                        throw new SecurityException("Email lookup integrity mismatch");
                }
                return null;
            });
    }
}
