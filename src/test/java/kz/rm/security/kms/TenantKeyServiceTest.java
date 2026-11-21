package kz.rm.security.kms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.SecretKey;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        // Test fixture only. Never use this well-known KEK in a real deployment.
        "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class TenantKeyServiceTest {

    @Autowired TenantKeyService service;
    @Autowired TenantKeyRepository repository;
    @Autowired KeyWrappingService wrapping;

    @Test
    void provisionsAndRotatesSingleTable() {
        UUID tenant = UUID.randomUUID();
        TenantKeyId v1 = service.provision(tenant);
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(service.provision(tenant)).isEqualTo(v1);

        TenantKeyEntity first = repository.findById(v1).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(KeyStatus.ACTIVE);
        SecretKey original = wrapping.unwrap(tenant, 1, first.getWrappedKey());

        TenantKeyId v2 = service.rotate(tenant);
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(repository.findById(v1).orElseThrow().getStatus())
                .isEqualTo(KeyStatus.DECRYPT_ONLY);
        assertThat(repository.findById(v2).orElseThrow().getStatus())
                .isEqualTo(KeyStatus.ACTIVE);
        assertThat(repository.findById(v2).orElseThrow().getActiveSlot()).isEqualTo((short) 1);
        assertThat(wrapping.unwrap(tenant, 1, first.getWrappedKey()).getEncoded())
                .containsExactly(original.getEncoded());
    }

    @Test
    void wrongTenantCannotUnwrapKey() {
        UUID tenant = UUID.randomUUID();
        TenantKeyId id = service.provision(tenant);
        byte[] wrapped = repository.findById(id).orElseThrow().getWrappedKey();
        assertThatThrownBy(() -> wrapping.unwrap(UUID.randomUUID(), 1, wrapped))
                .isInstanceOf(IllegalStateException.class);
    }
}
