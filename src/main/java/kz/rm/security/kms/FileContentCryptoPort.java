package kz.rm.security.kms;

import java.util.Objects;
import java.util.UUID;

/** Trusted internal FILES boundary only. Caller must authorize tenant, file and
 * immutable upload attempt; never construct Expected directly from HTTP data.
 * Bounded memory/concurrency admission belongs to the FILES caller before IO.
 * No key material or unauthenticated plaintext is returned. */
public interface FileContentCryptoPort {
    enum Format {
        XLSX(1, 2 * 1024 * 1024), PDF(2, 25 * 1024 * 1024);
        final int code;
        final int maxBytes;
        Format(int code, int maxBytes) { this.code = code; this.maxBytes = maxBytes; }
        public int maxBytes() { return maxBytes; }
    }

    record Expected(UUID tenantId, UUID fileId, UUID uploadAttempt, Format format,
                    int sizeBytes, String sha256) {
        public Expected {
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(fileId);
            Objects.requireNonNull(uploadAttempt);
            Objects.requireNonNull(format);
            if (sizeBytes < 1 || sizeBytes > format.maxBytes
                    || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("REPORT_FILE_CRYPTO_EXPECTED");
        }
    }

    /** Proven malformed or mismatched envelope/bytes, distinct from KMS outage. */
    final class IntegrityFailure extends SecurityException {
        public IntegrityFailure() { super("REPORT_FILE_CRYPTO_INTEGRITY"); }
    }

    /** Key resolution or cryptographic provider failure; not proof of corruption. */
    final class CryptoUnavailable extends IllegalStateException {
        public CryptoUnavailable(Throwable cause) { super("REPORT_FILE_CRYPTO_UNAVAILABLE", cause); }
    }

    byte[] seal(Expected expected, byte[] plaintext);
    byte[] open(Expected expected, byte[] envelope);

    /** Exact V1 wire length, checked before any allocation/read. */
    static int envelopeSize(Expected expected) {
        return Math.addExact(expected.sizeBytes(), 158);
    }
}
