package kz.rm.security.kms;

import org.junit.jupiter.api.Test;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static kz.rm.security.kms.FileContentCryptoPort.*;

class ReportFileEnvelopeV1Test {
    private static final byte[] PLAIN = "%PDF-1.7\nsynthetic fixture\n".getBytes(StandardCharsets.US_ASCII);
    private static final String SHA = "33d4441402124e873df98a527c98ed26fdf5b04fa5bc148fb9671fe6a8adf68d";
    private static final Expected EXPECTED = new Expected(new UUID(0,1), new UUID(0,2),
        new UUID(0,3), Format.PDF, PLAIN.length, SHA);
    private static final SecretKeySpec KEY = new SecretKeySpec(sequence(0,32), "AES");
    // Independent Python cryptography HKDF-SHA256 / AESGCM reference, fixed synthetic inputs.
    private static final String GOLDEN = "524d4645010200000000000000070000000000000000000000000000000100000000000000000000000000000002000000000000000000000000000000030000001b33d4441402124e873df98a527c98ed26fdf5b04fa5bc148fb9671fe6a8adf68d202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b2240bbc55590a92bd107dff4e799be9dd49c5c70ebea8ef5090117d6be438eddba9abf50e459040e955ec4";

    @Test void independentGoldenVectorAndRoundTrip() {
        byte[] envelope = seal();
        assertEquals(GOLDEN, HexFormat.of().formatHex(envelope));
        assertEquals(7, ReportFileEnvelopeV1.generation(EXPECTED, envelope));
        assertArrayEquals(PLAIN, ReportFileEnvelopeV1.open(EXPECTED, envelope, 7, KEY));
        assertEquals(PLAIN.length + 158, envelope.length);
    }

    @Test void rejectsEverySingleByteMutation() {
        byte[] envelope = seal();
        for (int i=0; i<envelope.length; i++) {
            byte[] changed = envelope.clone();
            changed[i] ^= 1;
            assertThrows(IntegrityFailure.class,
                () -> ReportFileEnvelopeV1.open(EXPECTED, changed, 7, KEY), "offset "+i);
        }
    }

    @Test void rejectsWrongScopeFormatHashGenerationAndKey() {
        byte[] envelope = seal();
        for (Expected wrong : new Expected[]{
            new Expected(new UUID(0,4), EXPECTED.fileId(), EXPECTED.uploadAttempt(), Format.PDF, PLAIN.length, SHA),
            new Expected(EXPECTED.tenantId(), new UUID(0,4), EXPECTED.uploadAttempt(), Format.PDF, PLAIN.length, SHA),
            new Expected(EXPECTED.tenantId(), EXPECTED.fileId(), new UUID(0,4), Format.PDF, PLAIN.length, SHA),
            new Expected(EXPECTED.tenantId(), EXPECTED.fileId(), EXPECTED.uploadAttempt(), Format.XLSX, PLAIN.length, SHA),
            new Expected(EXPECTED.tenantId(), EXPECTED.fileId(), EXPECTED.uploadAttempt(), Format.PDF, PLAIN.length, "0".repeat(64))
        }) assertThrows(IntegrityFailure.class, () -> ReportFileEnvelopeV1.open(wrong, envelope, 7, KEY));
        assertThrows(IntegrityFailure.class, () -> ReportFileEnvelopeV1.open(EXPECTED, envelope, 8, KEY));
        assertThrows(IntegrityFailure.class, () -> ReportFileEnvelopeV1.open(EXPECTED, envelope, 7,
            new SecretKeySpec(new byte[32], "AES")));
    }

    @Test void rejectsTruncationTrailingBytesAndWrongPlaintext() {
        byte[] envelope = seal();
        assertThrows(IntegrityFailure.class, () -> ReportFileEnvelopeV1.open(EXPECTED,
            Arrays.copyOf(envelope, envelope.length-1), 7, KEY));
        assertThrows(IntegrityFailure.class, () -> ReportFileEnvelopeV1.open(EXPECTED,
            Arrays.copyOf(envelope, envelope.length+1), 7, KEY));
        byte[] wrong = PLAIN.clone(); wrong[0] ^= 1;
        assertThrows(IntegrityFailure.class, () -> ReportFileEnvelopeV1.seal(EXPECTED, wrong, 7,
            KEY, sequence(32,32), sequence(64,12)));
    }

    @Test void boundsAndFreshSaltProduceDistinctEnvelopes() {
        assertThrows(IllegalArgumentException.class, () -> new Expected(EXPECTED.tenantId(), EXPECTED.fileId(),
            EXPECTED.uploadAttempt(), Format.XLSX, Format.XLSX.maxBytes()+1, SHA));
        byte[] changed = ReportFileEnvelopeV1.seal(EXPECTED, PLAIN, 7, KEY, sequence(33,32), sequence(64,12));
        assertFalse(Arrays.equals(seal(), changed));
        assertArrayEquals(PLAIN, ReportFileEnvelopeV1.open(EXPECTED, changed, 7, KEY));
    }

    private static byte[] seal() {
        return ReportFileEnvelopeV1.seal(EXPECTED, PLAIN, 7, KEY, sequence(32,32), sequence(64,12));
    }
    private static byte[] sequence(int start, int count) {
        byte[] result = new byte[count];
        for (int i=0;i<count;i++) result[i]=(byte)(start+i);
        return result;
    }
}
