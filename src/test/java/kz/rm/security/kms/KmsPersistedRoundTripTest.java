package kz.rm.security.kms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Uses a disposable test fixture table rather than introducing a third
 * production KMS table or assuming the final CRM schema already exists.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class KmsPersistedRoundTripTest {
    @Autowired TenantKeyService keys;
    @Autowired TenantKeyRepository repository;
    @Autowired KeyWrappingService wrapping;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantCryptoPort crypto;

    @Test
    void persistedEnvelopeRestoresUsingFreshCryptoInstance() {
        UUID tenant = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        keys.provision(tenant);
        var context = new CryptoContext(tenant, "crm_client", client, "phone");
        String plaintext = "Құпия +7 777 123 45 67";
        var envelope = crypto.encrypt(context, plaintext);
        byte[] stored = EncryptedValueCodec.encode(envelope);

        // Fixture is local to this test and uses a unique name to avoid shared DDL.
        String table = "kms_fixture_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TABLE " + table + " (id UUID PRIMARY KEY, encrypted_data BYTEA NOT NULL)");
        try {
            jdbc.update("INSERT INTO " + table + "(id,encrypted_data) VALUES (?,?)", client, stored);
            byte[] loaded = jdbc.queryForObject(
                    "SELECT encrypted_data FROM " + table + " WHERE id=?", byte[].class, client);
            assertThat(loaded).containsExactly(stored);
            var reloaded = EncryptedValueCodec.decode(loaded);
            // A distinct instance has a fresh Caffeine cache: it must read wrapped DEK from SQL.
            var restartedInstance = new TenantCryptoService(keys, wrapping);
            assertThat(restartedInstance.decrypt(context, reloaded)).isEqualTo(plaintext);
            assertThatThrownBy(() -> restartedInstance.decrypt(
                    new CryptoContext(tenant, "crm_client", UUID.randomUUID(), "phone"), reloaded))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            jdbc.execute("DROP TABLE " + table);
        }
    }

    @Test
    void rejectsMalformedBinaryEnvelope() {
        UUID tenant = UUID.randomUUID();
        keys.provision(tenant);
        var context = new CryptoContext(tenant, "crm_client", UUID.randomUUID(), "email");
        byte[] encoded = EncryptedValueCodec.encode(crypto.encrypt(context, "mail"));
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 1);
        assertThatThrownBy(() -> EncryptedValueCodec.decode(truncated))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] badMagic = encoded.clone();
        badMagic[0] ^= 1;
        assertThatThrownBy(() -> EncryptedValueCodec.decode(badMagic))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] badGeneration = encoded.clone();
        java.nio.ByteBuffer.wrap(badGeneration).putLong(8, -1);
        assertThatThrownBy(() -> EncryptedValueCodec.decode(badGeneration))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void simultaneousProvisionCallsReuseExactlyOneKey() throws Exception {
        UUID tenant = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return keys.provision(tenant); });
            var second = pool.submit(() -> { start.await(); return keys.provision(tenant); });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(tenant);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(tenant);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM security.kms_tenant_key WHERE tenant_id=?",
                Integer.class, tenant)).isEqualTo(1);
        assertThat(repository.findById(tenant).orElseThrow().activeGeneration()).isEqualTo(1);
    }

    @Test
    void enforcesOneDecryptOnlyAndMonotonicGeneration() {
        UUID tenant = UUID.randomUUID();
        keys.provision(tenant);
        assertThatThrownBy(() -> jdbc.update("""
            UPDATE security.kms_tenant_key
            SET next_generation=1 WHERE tenant_id=?
            """, tenant)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
            UPDATE security.kms_tenant_key
            SET key_b=?, generation_b=2, wrapping_key_id_b='root-v1', status_b='DECRYPT_ONLY',
                key_c=?, generation_c=3, wrapping_key_id_c='root-v1', status_c='DECRYPT_ONLY',
                next_generation=4
            WHERE tenant_id=?
            """, new byte[] {1}, new byte[] {2}, tenant)).isInstanceOf(Exception.class);
    }
}
