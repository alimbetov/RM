package kz.rm.security.kms;

import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Objects;

/** Separate binary capability; no change to existing text crypto or key lifecycle.
 * No DEK cache: use current KMS admission for each operation. Caller owns bounded
 * buffer lifetime and must not mutate input arrays during this synchronous call. */
@Service
public final class FileContentCryptoService implements FileContentCryptoPort {
    private final TenantKeyService keys;
    private final KeyWrappingService wrapping;
    private final SecureRandom random = new SecureRandom();

    public FileContentCryptoService(TenantKeyService keys, KeyWrappingService wrapping) {
        this.keys = keys;
        this.wrapping = wrapping;
    }

    @Override
    public byte[] seal(Expected expected, byte[] plaintext) {
        Objects.requireNonNull(expected);
        if (plaintext == null || plaintext.length != expected.sizeBytes()) throw new IntegrityFailure();
        TenantKeyEntity.Slot slot;
        SecretKey tenantKey;
        try {
            slot = keys.active(expected.tenantId());
            tenantKey = unwrap(expected, slot);
        } catch (RuntimeException e) {
            throw new CryptoUnavailable(e);
        }
        byte[] salt = new byte[32];
        byte[] nonce = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        return ReportFileEnvelopeV1.seal(expected, plaintext, slot.generation(), tenantKey, salt, nonce);
    }

    @Override
    public byte[] open(Expected expected, byte[] envelope) {
        long generation = ReportFileEnvelopeV1.generation(expected, envelope);
        SecretKey tenantKey;
        try {
            var slot = keys.forDecrypt(expected.tenantId(), generation);
            if (slot.generation() == null || slot.generation() != generation)
                throw new IllegalStateException("KMS generation mismatch");
            tenantKey = unwrap(expected, slot);
        } catch (RuntimeException e) {
            throw new CryptoUnavailable(e);
        }
        return ReportFileEnvelopeV1.open(expected, envelope, generation, tenantKey);
    }

    private SecretKey unwrap(Expected expected, TenantKeyEntity.Slot slot) {
        if (slot.generation() == null || slot.generation() < 1 || !"root-v1".equals(slot.wrappingKeyId()))
            throw new IllegalStateException("Unsupported KMS key slot");
        return wrapping.unwrap(expected.tenantId(), slot.generation(), slot.wrappedKey());
    }
}
