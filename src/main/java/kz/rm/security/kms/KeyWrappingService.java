package kz.rm.security.kms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Component
public class KeyWrappingService {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final String rootKeyBase64;

    public KeyWrappingService(@Value("${RM_KMS_ROOT_KEY_BASE64:}") String rootKeyBase64) {
        this.rootKeyBase64 = rootKeyBase64;
    }

    public SecretKey generateTenantKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, random);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256 unavailable", e);
        }
    }

    public byte[] wrap(UUID tenantId, int version, SecretKey dek) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, rootKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, version));
            byte[] ciphertext = cipher.doFinal(dek.getEncoded());
            return ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to wrap tenant key", e);
        }
    }

    // Internal service API only; never expose key material in an HTTP response.
    public SecretKey unwrap(UUID tenantId, int version, byte[] wrapped) {
        if (wrapped == null || wrapped.length != NONCE_BYTES + 32 + 16) {
            throw new IllegalArgumentException("Invalid wrapped key payload");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ciphertext = new byte[wrapped.length - NONCE_BYTES];
            System.arraycopy(wrapped, 0, nonce, 0, NONCE_BYTES);
            System.arraycopy(wrapped, NONCE_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, rootKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, version));
            return new SecretKeySpec(cipher.doFinal(ciphertext), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to unwrap tenant key", e);
        }
    }

    private SecretKey rootKey() {
        if (rootKeyBase64 == null || rootKeyBase64.isBlank()) {
            throw new IllegalStateException("RM_KMS_ROOT_KEY_BASE64 must be configured before provisioning keys");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(rootKeyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid root key encoding", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("Root KEK must be 256 bits");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    private byte[] aad(UUID tenantId, int version) {
        return ("rm:kms:tenant-dek:v1:" + tenantId + ":" + version)
                .getBytes(StandardCharsets.UTF_8);
    }
}
