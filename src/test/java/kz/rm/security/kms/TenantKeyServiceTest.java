package kz.rm.security.kms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class TenantKeyServiceTest {
    @Autowired TenantKeyService keys;
    @Autowired TenantKeyRepository repository;
    @Autowired TenantCryptoService crypto;

    @Test
    void provisionsOneRowWithThreeSlots() {
        UUID tenant = UUID.randomUUID();
        assertThat(keys.provision(tenant)).isEqualTo(tenant);
        assertThat(keys.provision(tenant)).isEqualTo(tenant);
        var entity = repository.findById(tenant).orElseThrow();
        assertThat(entity.getActiveSlot()).isEqualTo("A");
        assertThat(entity.activeGeneration()).isEqualTo(1);
        assertThat(entity.getNextGeneration()).isEqualTo(2);
        assertThat(entity.forGeneration(1).status()).isEqualTo(KeyStatus.ACTIVE);
        assertThatThrownBy(() -> entity.forGeneration(2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> keys.rotate(tenant)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void encryptDecryptAndRejectWrongContext() {
        UUID tenant = UUID.randomUUID();
        keys.provision(tenant);
        CryptoContext c = new CryptoContext(tenant, "crm_client", UUID.randomUUID(), "phone");
        var a = crypto.encrypt(c, "Сәлем, Алматы! +7 777");
        var b = crypto.encrypt(c, "Сәлем, Алматы! +7 777");
        assertThat(a.nonce()).isNotEqualTo(b.nonce());
        assertThat(crypto.decrypt(c, a)).isEqualTo("Сәлем, Алматы! +7 777");
        assertThat(crypto.decrypt(c, b)).isEqualTo("Сәлем, Алматы! +7 777");
        assertThatThrownBy(() -> crypto.decrypt(
            new CryptoContext(tenant, "crm_client", UUID.randomUUID(), "phone"), a))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(
            new CryptoContext(tenant, "crm_client", c.entityId(), "email"), a))
            .isInstanceOf(IllegalStateException.class);
        byte[] tampered = a.ciphertextAndTag();
        tampered[0] ^= 1;
        var bad = new EncryptedValue(1, a.keyGeneration(), a.nonce(), tampered);
        assertThatThrownBy(() -> crypto.decrypt(c, bad)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongTenantFails() {
        UUID tenant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        keys.provision(tenant);
        keys.provision(other);
        var context = new CryptoContext(tenant, "crm_client", UUID.randomUUID(), "name");
        var encrypted = crypto.encrypt(context, "A");
        assertThatThrownBy(() -> crypto.decrypt(
            new CryptoContext(other, "crm_client", context.entityId(), "name"), encrypted))
            .isInstanceOf(IllegalStateException.class);
    }
}
