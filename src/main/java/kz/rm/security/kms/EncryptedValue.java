package kz.rm.security.kms;

import java.util.Objects;

/** Serialized envelope data; never select a key by physical slot letter. */
public record EncryptedValue(int formatVersion, long keyGeneration, byte[] nonce, byte[] ciphertextAndTag) {
    public EncryptedValue {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(ciphertextAndTag, "ciphertextAndTag");
        if (formatVersion != 1 || keyGeneration < 1 || nonce.length != 12 || ciphertextAndTag.length < 16
                || ciphertextAndTag.length > 2_000_000) {
            throw new IllegalArgumentException("Invalid encrypted envelope");
        }
        nonce = nonce.clone();
        ciphertextAndTag = ciphertextAndTag.clone();
    }
    @Override public byte[] nonce() { return nonce.clone(); }
    @Override public byte[] ciphertextAndTag() { return ciphertextAndTag.clone(); }
}
