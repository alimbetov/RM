package kz.rm.security.kms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class KmsCryptoQualificationTest {
    @Autowired TenantKeyService keyService;
    @Autowired TenantCryptoPort crypto;
    @Autowired JdbcTemplate jdbc;

    @Test
    void roundTripEmptyUnicodeAndLongText() {
        UUID tenantId = UUID.randomUUID();
        keyService.provision(tenantId);
        var context = new CryptoContext(tenantId, "crm_client", UUID.randomUUID(), "notes");
        for (String plaintext : new String[]{"", "Сәлем! Привет 🌐", "a".repeat(4096)}) {
            var encrypted = crypto.encrypt(context, plaintext);
            assertThat(encrypted.formatVersion()).isEqualTo(1);
            assertThat(encrypted.keyGeneration()).isEqualTo(1L);
            assertThat(encrypted.nonce()).hasSize(12);
            assertThat(encrypted.ciphertextAndTag().length).isGreaterThanOrEqualTo(16);
            assertThat(crypto.decrypt(context, encrypted)).isEqualTo(plaintext);
        }
    }

    @Test
    void rejectsNonceAndTagTamperingAndWrongGeneration() {
        UUID tenantId = UUID.randomUUID();
        keyService.provision(tenantId);
        var context = new CryptoContext(tenantId, "crm_client", UUID.randomUUID(), "phone");
        var encrypted = crypto.encrypt(context, "987654");

        var wrongNonce = encrypted.nonce();
        wrongNonce[0] ^= 0x01;
        assertThatThrownBy(() -> crypto.decrypt(context, new EncryptedValue(
                1, 1, wrongNonce, encrypted.ciphertextAndTag()))).isInstanceOf(IllegalStateException.class);

        var wrongTag = encrypted.ciphertextAndTag();
        wrongTag[wrongTag.length - 1] ^= 0x01;
        assertThatThrownBy(() -> crypto.decrypt(context, new EncryptedValue(
                1, 1, encrypted.nonce(), wrongTag))).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> crypto.decrypt(context, new EncryptedValue(
                1, 999, encrypted.nonce(), encrypted.ciphertextAndTag())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EncryptedValue(2, 1, encrypted.nonce(), encrypted.ciphertextAndTag()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void envelopeDefensivelyCopiesArrays() {
        var nonce = new byte[12];
        var ct = new byte[16];
        var envelope = new EncryptedValue(1, 1, nonce, ct);
        nonce[0] = 42;
        ct[0] = 42;
        assertThat(envelope.nonce()[0]).isZero();
        assertThat(envelope.ciphertextAndTag()[0]).isZero();
        envelope.nonce()[0] = 42;
        envelope.ciphertextAndTag()[0] = 42;
        assertThat(envelope.nonce()[0]).isZero();
        assertThat(envelope.ciphertextAndTag()[0]).isZero();
    }

    @Test
    void physicalSchemaHasUniqueTenantRowsAndArchiveKey() {
        UUID tenantId = UUID.randomUUID();
        keyService.provision(tenantId);
        assertThat(jdbc.queryForObject(
                "select count(*) from security.kms_tenant_key where tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from security.kms_tenant_key_archive",
                Integer.class)).isNotNull();
        assertThatThrownBy(() -> jdbc.update(
                "update security.kms_tenant_key set status_b = 'ACTIVE' where tenant_id = ?", tenantId))
                .isInstanceOf(Exception.class);
    }
}
