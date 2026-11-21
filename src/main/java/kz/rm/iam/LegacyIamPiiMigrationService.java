package kz.rm.iam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Explicit maintenance-mode-only, bounded, transactional legacy backfill.
 * Call only after stopping checkout, account creation and legacy writers.
 * Never autostart, never call from a controller or user-facing command.
 */
@Service
public class LegacyIamPiiMigrationService {
    private final JdbcTemplate jdbc;
    private final GlobalUserProfileCrypto profileCrypto;
    private final EmailIdentityCrypto emailCrypto;
    private final boolean enabled;

    public LegacyIamPiiMigrationService(JdbcTemplate jdbc,
                                        GlobalUserProfileCrypto profileCrypto,
                                        EmailIdentityCrypto emailCrypto,
                                        @Value("${rm.iam.pii.migration.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;
        this.profileCrypto=profileCrypto;
        this.emailCrypto=emailCrypto;
        this.enabled=enabled;
    }

    private record Legacy(UUID id,String name,String phone,String email) {}

    @Transactional
    public int migrateBatch(int limit) {
        if(!enabled)throw new SecurityException("Offline IAM PII migration disabled");
        if(limit<1||limit>500)throw new IllegalArgumentException("Batch size must be 1..500");
        // One transaction: encrypt and create missing IAM identity before clearing
        // legacy plaintext. On any key/unique collision the entire batch rolls back.
        List<Legacy> rows=jdbc.query("""
            SELECT id,display_name,phone_e164,email_normalized
            FROM public.iam_user_account
            WHERE display_name IS NOT NULL OR phone_e164 IS NOT NULL
               OR email_normalized IS NOT NULL
            ORDER BY id LIMIT ? FOR UPDATE
            """,(rs,n)->new Legacy(rs.getObject(1,UUID.class),
                    rs.getString(2),rs.getString(3),rs.getString(4)),limit);
        for(Legacy row:rows) {
            migrateProfile(row);
            migrateLegacyEmail(row);
            int scrubbed=jdbc.update("""
                UPDATE public.iam_user_account
                SET display_name=NULL,phone_e164=NULL,email_normalized=NULL,
                    row_version=row_version+1,updated_at=?
                WHERE id=?
                """,Timestamp.from(Instant.now()),row.id());
            if(scrubbed!=1)throw new IllegalStateException("Legacy PII scrub conflict");
        }
        return rows.size();
    }

    /**
     * One-account offline admission, useful for exact proof on a populated
     * PostgreSQL copy before the bounded whole-table migration is approved.
     */
    @Transactional
    public boolean migrateUser(UUID userId) {
        if(!enabled)throw new SecurityException("Offline IAM PII migration disabled");
        if(userId==null)throw new IllegalArgumentException("User ID required");
        Legacy row=jdbc.query("""
            SELECT id,display_name,phone_e164,email_normalized
            FROM public.iam_user_account WHERE id=? FOR UPDATE
            """,rs->rs.next()?new Legacy(rs.getObject(1,UUID.class),
                    rs.getString(2),rs.getString(3),rs.getString(4)):null,userId);
        if(row==null)throw new SecurityException("Account unavailable");
        if(row.name()==null && row.phone()==null && row.email()==null)return false;
        migrateProfile(row);
        migrateLegacyEmail(row);
        int scrubbed=jdbc.update("""
            UPDATE public.iam_user_account
            SET display_name=NULL,phone_e164=NULL,email_normalized=NULL,
                row_version=row_version+1,updated_at=?
            WHERE id=?
            """,Timestamp.from(Instant.now()),row.id());
        if(scrubbed!=1)throw new IllegalStateException("Legacy PII scrub conflict");
        return true;
    }

    private void migrateProfile(Legacy row) {
        Integer exists=jdbc.queryForObject("""
            SELECT count(*) FROM iam_pii.user_profile_secret WHERE user_id=?
            """,Integer.class,row.id());
        if(exists!=null&&exists>0)return;
        if(row.name()==null||row.name().isBlank())
            throw new IllegalStateException("Legacy name missing: profile requires review");
        String displayName=GlobalUserProfileService.validateName(row.name());
        String phone=GlobalUserProfileService.validatePhone(row.phone());
        byte[] encryptedName=profileCrypto.encrypt(row.id(),"display_name",displayName);
        byte[] encryptedPhone=phone==null?null:profileCrypto.encrypt(row.id(),"phone_e164",phone);
        byte[] lookup=phone==null?null:profileCrypto.phoneLookup(phone);
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO iam_pii.user_profile_secret
              (user_id,display_name_envelope,phone_envelope,phone_lookup_hmac,
               row_version,created_at,updated_at)
            VALUES (?,?,?,?,0,?,?)
            """,row.id(),encryptedName,encryptedPhone,lookup,now,now);
        jdbc.update("""
            INSERT INTO iam_pii.user_profile_event(id,user_id,action_code,occurred_at)
            VALUES (?,?,'PROFILE_MIGRATED',?)
            """,UUID.randomUUID(),row.id(),now);
    }

    private void migrateLegacyEmail(Legacy row) {
        if(row.email()==null)return;
        String normalized=emailCrypto.normalize(row.email());
        byte[] lookup=emailCrypto.lookup(normalized);
        List<UUID> activeOwners=jdbc.query("""
            SELECT user_id FROM public.iam_email_identity WHERE active_lookup_hmac=?
            """,(rs,n)->rs.getObject(1,UUID.class),lookup);
        if(!activeOwners.isEmpty()) {
            if(!activeOwners.getFirst().equals(row.id()))
                throw new SecurityException("Legacy email is claimed by another identity");
            return;
        }
        List<UUID> existing=jdbc.query("""
            SELECT id FROM public.iam_email_identity
            WHERE user_id=? AND lifecycle_state IN ('CURRENT','PENDING')
            """,(rs,n)->rs.getObject(1,UUID.class),row.id());
        if(!existing.isEmpty())
            throw new SecurityException("Legacy email conflicts with current email");
        UUID identityId=UUID.randomUUID();
        // Existing legacy email is NOT evidence of mailbox ownership. Always pending.
        jdbc.update("""
            INSERT INTO public.iam_email_identity
              (id,user_id,lookup_hmac,email_envelope,created_at,lifecycle_state,
               pending_reason,active_lookup_hmac,pending_owner_id)
            VALUES (?,?,?,?,?,'PENDING','REGISTRATION',?,?)
            """,identityId,row.id(),lookup,emailCrypto.encrypt(normalized),
                Timestamp.from(Instant.now()),lookup,row.id());
    }
}
