package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Only decrypted owner-self global IAM profile projection. Neither PLATFORM_ADMIN
 * nor tenant OWNER implies access to another person's profile plaintext.
 */
@Service
public class GlobalUserProfileService {
    private final JdbcTemplate jdbc;
    private final GlobalUserProfileCrypto crypto;

    public GlobalUserProfileService(JdbcTemplate jdbc,GlobalUserProfileCrypto crypto) {
        this.jdbc=jdbc;
        this.crypto=crypto;
    }

    public record Profile(UUID userId,String displayName,String phoneE164,long rowVersion) {}
    private record Secret(byte[] displayName,byte[] phone,long version) {}

    @Transactional(readOnly=true)
    public Profile myProfile(VerifiedActor actor) {
        UUID id=active(actor,false);
        Secret secret=secret(id);
        if(secret==null)throw new IllegalStateException("Encrypted profile not provisioned");
        return new Profile(id,crypto.decrypt(id,"display_name",secret.displayName()),
                secret.phone()==null?null:crypto.decrypt(id,"phone_e164",secret.phone()),
                secret.version());
    }

    /**
     * Upsert is intentional for empty legacy profiles: expectedVersion=0 both
     * initializes and updates revision 0; any stale or mismatch fails closed.
     */
    @Transactional
    public Profile saveOwnProfile(VerifiedActor actor,String displayName,
                                  String phoneE164,long expectedVersion) {
        UUID id=active(actor,true);
        String name=validateName(displayName);
        String phone=validatePhone(phoneE164);
        if(expectedVersion<0)throw new IllegalArgumentException("Expected version required");

        Secret current=secret(id);
        if(current==null && expectedVersion!=0)
            throw new IllegalStateException("Global profile version conflict");
        if(current!=null && current.version()!=expectedVersion)
            throw new IllegalStateException("Global profile version conflict");
        byte[] nameCipher=crypto.encrypt(id,"display_name",name);
        byte[] phoneCipher=phone==null?null:crypto.encrypt(id,"phone_e164",phone);
        byte[] phoneIndex=phone==null?null:crypto.phoneLookup(phone);
        Timestamp now=Timestamp.from(Instant.now());
        long newVersion;
        if(current==null) {
            jdbc.update("""
                INSERT INTO iam_pii.user_profile_secret
                  (user_id,display_name_envelope,phone_envelope,phone_lookup_hmac,
                   row_version,created_at,updated_at)
                VALUES (?,?,?,?,0,?,?)
                """,id,nameCipher,phoneCipher,phoneIndex,now,now);
            newVersion=0;
        } else {
            int updated=jdbc.update("""
                UPDATE iam_pii.user_profile_secret
                SET display_name_envelope=?,phone_envelope=?,phone_lookup_hmac=?,
                    row_version=row_version+1,updated_at=?
                WHERE user_id=? AND row_version=?
                """,nameCipher,phoneCipher,phoneIndex,now,id,expectedVersion);
            if(updated!=1)throw new IllegalStateException("Concurrent global profile mutation");
            newVersion=expectedVersion+1;
        }
        event(id,current==null?"PROFILE_CREATED":"PROFILE_UPDATED");
        return new Profile(id,name,phone,newVersion);
    }

    /** Only the migration process may use this; never accepts a caller userId. */
    static String validateName(String name) {
        if(name==null||name.isBlank()||name.length()>200)
            throw new IllegalArgumentException("Valid display name required");
        return name.strip();
    }

    static String validatePhone(String phone) {
        if(phone==null||phone.isBlank())return null;
        String normalized=phone.strip();
        if(!normalized.matches("\\+[1-9][0-9]{6,14}"))
            throw new IllegalArgumentException("Valid E.164 phone required");
        return normalized;
    }

    private UUID active(VerifiedActor actor,boolean lock) {
        if(actor==null)throw new SecurityException("Verified identity required");
        UUID id=actor.userId();
        String sql=lock?
            "SELECT status FROM public.iam_user_account WHERE id=? FOR UPDATE":
            "SELECT status FROM public.iam_user_account WHERE id=?";
        String status=jdbc.query(sql,rs->rs.next()?rs.getString(1):null,id);
        if(!"ACTIVE".equals(status))throw new SecurityException("Active user required");
        return id;
    }

    private Secret secret(UUID id) {
        return jdbc.query("""
            SELECT display_name_envelope,phone_envelope,row_version
            FROM iam_pii.user_profile_secret WHERE user_id=?
            """,rs->rs.next()?new Secret(rs.getBytes(1),rs.getBytes(2),rs.getLong(3)):null,id);
    }

    private void event(UUID id,String action) {
        jdbc.update("""
            INSERT INTO iam_pii.user_profile_event(id,user_id,action_code,occurred_at)
            VALUES (?,?,?,?)
            """,UUID.randomUUID(),id,action,Timestamp.from(Instant.now()));
    }
}
