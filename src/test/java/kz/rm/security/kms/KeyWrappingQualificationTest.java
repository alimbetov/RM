package kz.rm.security.kms;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class KeyWrappingQualificationTest {
    private static final String TEST_KEK = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void generatedDekWrapsAndUnwrapsWithAuthenticatedTenantAndGeneration() {
        var wrapping = new KeyWrappingService(TEST_KEK);
        UUID tenant = UUID.randomUUID();
        var dek = wrapping.generateTenantKey();
        assertThat(dek.getEncoded()).hasSize(32);
        byte[] wrapped = wrapping.wrap(tenant, 1L, dek);
        assertThat(wrapped).hasSize(60);
        assertThat(wrapping.unwrap(tenant, 1L, wrapped).getEncoded())
                .containsExactly(dek.getEncoded());
        assertThatThrownBy(() -> wrapping.unwrap(UUID.randomUUID(), 1L, wrapped))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> wrapping.unwrap(tenant, 2L, wrapped))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void corruptedWrappedDataAndWrongRootAreRejected() {
        var wrapping = new KeyWrappingService(TEST_KEK);
        UUID tenant = UUID.randomUUID();
        byte[] wrapped = wrapping.wrap(tenant, 42L, wrapping.generateTenantKey());
        wrapped[wrapped.length - 1] ^= 0x01;
        assertThatThrownBy(() -> wrapping.unwrap(tenant, 42L, wrapped))
                .isInstanceOf(IllegalStateException.class);

        var wrongRoot = new KeyWrappingService("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        assertThatThrownBy(() -> wrongRoot.unwrap(tenant, 42L, wrapped))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingOrMalformedRootKeyFailsClosed() {
        UUID tenant = UUID.randomUUID();
        for (String badKey : new String[]{"", "not-base64@", "AA=="}) {
            var wrapping = new KeyWrappingService(badKey);
            assertThatThrownBy(() -> wrapping.wrap(tenant, 1L, wrapping.generateTenantKey()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void distinctWrappedPayloadsForSameDekHaveDifferentNonce() {
        var wrapping = new KeyWrappingService(TEST_KEK);
        UUID tenant = UUID.randomUUID();
        var key = wrapping.generateTenantKey();
        byte[] first = wrapping.wrap(tenant, 1L, key);
        byte[] second = wrapping.wrap(tenant, 1L, key);
        assertThat(first).isNotEqualTo(second);
        assertThat(wrapping.unwrap(tenant, 1L, first).getEncoded())
                .containsExactly(wrapping.unwrap(tenant, 1L, second).getEncoded());
    }
}
