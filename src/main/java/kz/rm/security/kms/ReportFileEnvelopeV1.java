package kz.rm.security.kms;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import static kz.rm.security.kms.FileContentCryptoPort.*;

/** Fixed-width canonical V1 envelope; all integers are big endian.
 * Header (142 bytes): RMFE[4], version[1], format[1], generation[8],
 * tenant[16], file[16], upload attempt[16], plaintext length[4],
 * plaintext SHA-256[32], HKDF salt[32], GCM nonce[12].
 * Then plaintext-length bytes of ciphertext and 16-byte GCM tag.
 * HKDF info = ASCII domain label + first 62 bytes of header.
 * AAD = entire header. No compression or optional fields. */
final class ReportFileEnvelopeV1 {
    static final int HEADER = 142;
    private static final int PREFIX = 62;
    private static final int MAGIC = 0x524d4645;
    private static final byte[] DOMAIN = "RM:FILES:REPORT:v1".getBytes(StandardCharsets.US_ASCII);
    private ReportFileEnvelopeV1() {}

    static byte[] seal(Expected expected, byte[] plain, long generation,
                       SecretKey tenantKey, byte[] salt, byte[] nonce) {
        Objects.requireNonNull(expected);
        if (generation < 1 || salt == null || salt.length != 32 || nonce == null || nonce.length != 12)
            throw new IllegalArgumentException("REPORT_FILE_CRYPTO_PARAMETERS");
        requirePlain(expected, plain);
        byte[] header = header(expected, generation, salt, nonce);
        byte[] key = null;
        try {
            key = derive(tenantKey, salt, header);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            byte[] result = new byte[FileContentCryptoPort.envelopeSize(expected)];
            System.arraycopy(header, 0, result, 0, HEADER);
            int written = cipher.doFinal(plain, 0, plain.length, result, HEADER);
            if (written != plain.length + 16) throw new CryptoUnavailable(null);
            return result;
        } catch (GeneralSecurityException e) {
            throw new CryptoUnavailable(e);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    static long generation(Expected expected, byte[] envelope) {
        Objects.requireNonNull(expected);
        if (envelope == null || envelope.length != FileContentCryptoPort.envelopeSize(expected))
            throw new IntegrityFailure();
        ByteBuffer b = ByteBuffer.wrap(envelope, 0, HEADER);
        if (b.getInt() != MAGIC || b.get() != 1 || b.get() != expected.format().code)
            throw new IntegrityFailure();
        long generation = b.getLong();
        if (generation < 1 || !uuid(b).equals(expected.tenantId())
                || !uuid(b).equals(expected.fileId()) || !uuid(b).equals(expected.uploadAttempt())
                || b.getInt() != expected.sizeBytes()) throw new IntegrityFailure();
        byte[] digest = new byte[32];
        b.get(digest);
        if (!MessageDigest.isEqual(digest, HexFormat.of().parseHex(expected.sha256())))
            throw new IntegrityFailure();
        return generation;
    }

    static byte[] open(Expected expected, byte[] envelope, long selectedGeneration, SecretKey tenantKey) {
        if (generation(expected, envelope) != selectedGeneration) throw new IntegrityFailure();
        byte[] header = Arrays.copyOf(envelope, HEADER);
        byte[] salt = Arrays.copyOfRange(header, 98, 130);
        byte[] nonce = Arrays.copyOfRange(header, 130, HEADER);
        byte[] key = null;
        byte[] plain = null;
        boolean verified = false;
        try {
            key = derive(tenantKey, salt, header);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            // doFinal authenticates the entire message before returning any bytes.
            plain = cipher.doFinal(envelope, HEADER, envelope.length - HEADER);
            requirePlain(expected, plain);
            verified = true;
            return plain;
        } catch (AEADBadTagException e) {
            throw new IntegrityFailure();
        } catch (GeneralSecurityException e) {
            throw new CryptoUnavailable(e);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (!verified && plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    private static byte[] header(Expected expected, long generation, byte[] salt, byte[] nonce) {
        ByteBuffer b = ByteBuffer.allocate(HEADER);
        b.putInt(MAGIC).put((byte) 1).put((byte) expected.format().code).putLong(generation);
        putUuid(b, expected.tenantId());
        putUuid(b, expected.fileId());
        putUuid(b, expected.uploadAttempt());
        b.putInt(expected.sizeBytes()).put(HexFormat.of().parseHex(expected.sha256())).put(salt).put(nonce);
        return b.array();
    }

    private static void putUuid(ByteBuffer b, UUID value) {
        b.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }
    private static UUID uuid(ByteBuffer b) { return new UUID(b.getLong(), b.getLong()); }

    private static void requirePlain(Expected expected, byte[] plain) {
        if (plain == null || plain.length != expected.sizeBytes()) throw new IntegrityFailure();
        try {
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(plain),
                    HexFormat.of().parseHex(expected.sha256()))) throw new IntegrityFailure();
        } catch (GeneralSecurityException e) {
            throw new CryptoUnavailable(e);
        }
    }

    private static byte[] derive(SecretKey tenantKey, byte[] salt, byte[] header)
            throws GeneralSecurityException {
        byte[] ikm = tenantKey == null ? null : tenantKey.getEncoded();
        if (ikm == null || ikm.length != 32) {
            if (ikm != null) Arrays.fill(ikm, (byte) 0);
            throw new CryptoUnavailable(null);
        }
        byte[] prk = null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            prk = mac.doFinal(ikm); // RFC 5869 extract
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(DOMAIN);
            mac.update(header, 0, PREFIX);
            mac.update((byte) 1); // one 32-byte expand block
            return mac.doFinal();
        } finally {
            Arrays.fill(ikm, (byte) 0);
            if (prk != null) Arrays.fill(prk, (byte) 0);
        }
    }
}
