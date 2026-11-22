package kz.rm.iam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.IDN;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Separate GLOBAL IAM identity keys (not a seller/tenant DEK).
 * Requires two independent, random secrets from a secret manager.
 * No per-tenant KMS keys or KATO codes are used for global email deduplication.
 */
@Component
public class EmailIdentityCrypto {
    private final String encryptionB64;
    private final String lookupB64;
    private final SecureRandom random = new SecureRandom();

    public EmailIdentityCrypto(
            @Value("${RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64:}") String encryptionB64,
            @Value("${RM_IAM_EMAIL_LOOKUP_KEY_BASE64:}") String lookupB64) {
        this.encryptionB64=encryptionB64;
        this.lookupB64=lookupB64;
    }

    public String normalize(String email) {
        if (email==null || email.length()>320 || email.indexOf('\n')>=0 || email.indexOf('\r')>=0)
            throw new IllegalArgumentException("Valid email required");
        String trimmed=email.trim();
        int at=trimmed.lastIndexOf('@');
        if(at<=0 || at==trimmed.length()-1 || trimmed.indexOf('@')!=at)
            throw new IllegalArgumentException("Valid email required");
        String local=trimmed.substring(0,at);
        String domain=IDN.toASCII(trimmed.substring(at+1),IDN.USE_STD3_ASCII_RULES);
        if(local.length()>64 || domain.length()>253 || !local.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+")
                || !domain.contains(".") || domain.startsWith(".") || domain.endsWith("."))
            throw new IllegalArgumentException("Valid email required");
        return (local+"@"+domain).toLowerCase(Locale.ROOT);
    }

    public byte[] lookup(String normalized) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(lookupB64),"HmacSHA256"));
            return mac.doFinal(("iam-email:v1:"+normalized).getBytes(StandardCharsets.UTF_8));
        } catch(Exception e){throw new IllegalStateException("Identity lookup unavailable",e);}
    }

    public byte[] encrypt(String cleartext) {
        return encryptWithContext(cleartext,"rm:iam:email:v1");
    }

    /** Separate AEAD domain for OIDC logout subjects; never email ciphertext. */
    public byte[] encryptOidcSubject(String subject) {
        if(subject==null || subject.isBlank())throw new IllegalArgumentException("OIDC subject required");
        return encryptWithContext(subject,"rm:iam:oidc-subject:v1");
    }

    public String decryptOidcSubject(byte[] envelope) {
        return decryptWithContext(envelope,"rm:iam:oidc-subject:v1");
    }

    private byte[] encryptWithContext(String cleartext,String context) {
        try {
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret(encryptionB64),"AES"),new GCMParameterSpec(128,nonce));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=c.doFinal(cleartext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1+nonce.length+encrypted.length).put((byte)1).put(nonce).put(encrypted).array();
        } catch(Exception e){throw new IllegalStateException("Identity encryption unavailable",e);}
    }

    public String decrypt(byte[] encrypted) {
        return decryptWithContext(encrypted,"rm:iam:email:v1");
    }

    private String decryptWithContext(byte[] encrypted,String context) {
        try {
            ByteBuffer b=ByteBuffer.wrap(encrypted);
            if(b.remaining()<30 || b.get()!=1)throw new IllegalArgumentException("Unknown identity envelope");
            byte[] nonce=new byte[12];b.get(nonce);
            byte[] body=new byte[b.remaining()];b.get(body);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret(encryptionB64),"AES"),new GCMParameterSpec(128,nonce));
            c.updateAAD("rm:iam:email:v1".getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(body),StandardCharsets.UTF_8);
        } catch(Exception e){throw new IllegalStateException("Identity decrypt unavailable",e);}
    }

    public String token() {
        byte[] raw=new byte[32];random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public byte[] tokenHash(String token) {
        try {return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    private static byte[] secret(String b64) {
        if(b64==null || b64.isBlank())throw new IllegalStateException("IAM identity secrets are not configured");
        byte[] key;
        try {key=Base64.getDecoder().decode(b64);}
        catch(IllegalArgumentException e){throw new IllegalStateException("Invalid IAM secret encoding",e);}
        if(key.length!=32) throw new IllegalStateException("IAM secrets must have 32 bytes");
        return key;
    }
}
