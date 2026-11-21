package kz.rm.iam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Per-user global IAM profile AEAD: independent of organization tenant DEKs
 * and independent of global email encryption/lookup keys.
 * No plaintext fallback if secrets are absent or ciphertext is corrupted.
 */
@Component
public class GlobalUserProfileCrypto {
    private final String encryptionKey;
    private final String lookupKey;
    private final SecureRandom random=new SecureRandom();

    public GlobalUserProfileCrypto(
            @Value("${RM_IAM_PROFILE_ENCRYPTION_KEY_BASE64:}") String encryptionKey,
            @Value("${RM_IAM_PHONE_LOOKUP_KEY_BASE64:}") String lookupKey) {
        this.encryptionKey=encryptionKey;
        this.lookupKey=lookupKey;
    }

    public byte[] encrypt(UUID userId,String field,String plaintext) {
        validateScope(userId,field);
        if(plaintext==null)throw new IllegalArgumentException("Profile value required");
        try {
            byte[] nonce=new byte[12];
            random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(secret(encryptionKey),"AES"),
                    new GCMParameterSpec(128,nonce));
            cipher.updateAAD(aad(userId,field));
            byte[] body=cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1+nonce.length+body.length)
                    .put((byte)1).put(nonce).put(body).array();
        } catch(Exception e) {
            throw new IllegalStateException("Global IAM profile encryption unavailable",e);
        }
    }

    public String decrypt(UUID userId,String field,byte[] envelope) {
        validateScope(userId,field);
        try {
            if(envelope==null||envelope.length<30||envelope[0]!=1)
                throw new SecurityException("Invalid profile envelope");
            ByteBuffer data=ByteBuffer.wrap(envelope);
            data.get();
            byte[] nonce=new byte[12];
            data.get(nonce);
            byte[] body=new byte[data.remaining()];
            data.get(body);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret(encryptionKey),"AES"),
                    new GCMParameterSpec(128,nonce));
            cipher.updateAAD(aad(userId,field));
            return new String(cipher.doFinal(body),StandardCharsets.UTF_8);
        } catch(Exception e) {
            throw new SecurityException("Global IAM profile cannot be decrypted");
        }
    }

    public byte[] phoneLookup(String normalizedPhone) {
        if(normalizedPhone==null||!normalizedPhone.matches("\\+[1-9][0-9]{6,14}"))
            throw new IllegalArgumentException("Valid E.164 phone required");
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(lookupKey),"HmacSHA256"));
            return mac.doFinal(("rm:iam:phone:v1:"+normalizedPhone)
                    .getBytes(StandardCharsets.UTF_8));
        } catch(Exception e) {
            throw new IllegalStateException("Global IAM phone lookup unavailable",e);
        }
    }

    private static byte[] aad(UUID userId,String field) {
        return ("rm:iam:profile:v1:"+userId+":"+field).getBytes(StandardCharsets.UTF_8);
    }

    private static void validateScope(UUID id,String field) {
        if(id==null||!("display_name".equals(field)||"phone_e164".equals(field)))
            throw new SecurityException("Unknown global profile field");
    }

    private static byte[] secret(String encoded) {
        if(encoded==null||encoded.isBlank())
            throw new IllegalStateException("Global IAM profile secret unavailable");
        try {
            byte[] decoded=Base64.getDecoder().decode(encoded);
            if(decoded.length!=32)throw new IllegalStateException("Invalid profile key length");
            return decoded;
        } catch(IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid global IAM profile key");
        }
    }
}
