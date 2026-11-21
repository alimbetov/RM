package kz.rm.security.kms;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Portable, bounded binary envelope for CRM field storage.
 * Layout: magic(4) + format(4) + generation(8) + nonce(12)
 *         + payloadLength(4) + AES-GCM ciphertext-and-tag.
 * This codec never stores plaintext or key material.
 */
public final class EncryptedValueCodec {
    private static final int MAGIC = 0x524D4B31; // RMK1
    private static final int HEADER_BYTES = 32;
    private static final int MAX_CIPHER_BYTES = 2_000_000;

    private EncryptedValueCodec() {}

    public static byte[] encode(EncryptedValue value) {
        if (value == null) throw new IllegalArgumentException("Envelope required");
        byte[] body = value.ciphertextAndTag();
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + body.length).order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC);
        out.putInt(value.formatVersion());
        out.putLong(value.keyGeneration());
        out.put(value.nonce());
        out.putInt(body.length);
        out.put(body);
        return out.array();
    }

    public static EncryptedValue decode(byte[] stored) {
        if (stored == null || stored.length < HEADER_BYTES + 16
                || stored.length > HEADER_BYTES + MAX_CIPHER_BYTES) {
            throw new IllegalArgumentException("Invalid encrypted storage length");
        }
        ByteBuffer in = ByteBuffer.wrap(stored).order(ByteOrder.BIG_ENDIAN);
        if (in.getInt() != MAGIC) throw new IllegalArgumentException("Invalid encrypted storage magic");
        int version = in.getInt();
        long generation = in.getLong();
        byte[] nonce = new byte[12];
        in.get(nonce);
        int length = in.getInt();
        if (length < 16 || length != in.remaining()) {
            throw new IllegalArgumentException("Invalid encrypted storage payload length");
        }
        byte[] payload = new byte[length];
        in.get(payload);
        return new EncryptedValue(version, generation, nonce, payload);
    }
}
