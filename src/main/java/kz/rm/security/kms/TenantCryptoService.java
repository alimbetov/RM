package kz.rm.security.kms;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

/**
 * Single-JVM MVP crypto path. Caller authorizes access before constructing CryptoContext.
 * Rotation/revocation remains disabled until in-flight transaction fencing is qualified.
 */
@Service
public class TenantCryptoService {
    private final TenantKeyService keys;
    private final KeyWrappingService wrapping;
    private final SecureRandom random = new SecureRandom();
    private final Cache<KeyRef, SecretKey> cache = Caffeine.newBuilder()
            .maximumSize(1000).expireAfterAccess(Duration.ofMinutes(10)).build();

    public TenantCryptoService(TenantKeyService keys, KeyWrappingService wrapping) {
        this.keys = keys;
        this.wrapping = wrapping;
    }

    public EncryptedValue encrypt(CryptoContext context, String plaintext) {
        if (context == null || plaintext == null) throw new IllegalArgumentException("Context and plaintext required");
        var active = keys.active(context.tenantId());
        long generation = active.generation();
        SecretKey dek = resolve(context.tenantId(), generation, active);
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(context, generation));
            return new EncryptedValue(1, generation, nonce,
                    cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Encryption failed", ex);
        }
    }

    public String decrypt(CryptoContext context, EncryptedValue encrypted) {
        if (context == null || encrypted == null) throw new IllegalArgumentException("Context and envelope required");
        try {
            var generation = encrypted.keyGeneration();
            var dek = cache.get(new KeyRef(context.tenantId(), generation),
                    ref -> resolveUncached(ref.tenantId(), ref.generation()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(128, encrypted.nonce()));
            cipher.updateAAD(aad(context, generation));
            return new String(cipher.doFinal(encrypted.ciphertextAndTag()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Decryption integrity check failed", ex);
        }
    }

    private SecretKey resolve(UUID tenantId, long generation, TenantKeyEntity.Slot active) {
        return cache.get(new KeyRef(tenantId, generation),
                ref -> wrapping.unwrap(ref.tenantId(), ref.generation(), active.wrappedKey()));
    }

    private SecretKey resolveUncached(UUID tenantId, long generation) {
        var slot = keys.forDecrypt(tenantId, generation);
        if (!"root-v1".equals(slot.wrappingKeyId())) {
            throw new IllegalStateException("Unknown root wrapping key ID");
        }
        return wrapping.unwrap(tenantId, generation, slot.wrappedKey());
    }

    // Binary length-prefixing prevents ambiguous AAD concatenation.
    private byte[] aad(CryptoContext context, long generation) {
        try {
            var buffer = new ByteArrayOutputStream();
            var out = new DataOutputStream(buffer);
            out.writeInt(1);
            out.writeLong(context.tenantId().getMostSignificantBits());
            out.writeLong(context.tenantId().getLeastSignificantBits());
            writeString(out, context.entityType());
            out.writeLong(context.entityId().getMostSignificantBits());
            out.writeLong(context.entityId().getLeastSignificantBits());
            writeString(out, context.fieldName());
            out.writeLong(generation);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("AAD encoding failed", impossible);
        }
    }

    private static void writeString(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024) throw new IllegalArgumentException("AAD component too long");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** Control path must call this after a successfully committed state change. */
    public void invalidateTenant(UUID tenantId) {
        cache.asMap().keySet().removeIf(key -> key.tenantId().equals(tenantId));
    }

    private record KeyRef(UUID tenantId, long generation) {}
}
