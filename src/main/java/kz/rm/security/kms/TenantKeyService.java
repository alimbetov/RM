package kz.rm.security.kms;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Phase R1.6: provisioning only. Rotation is explicitly disabled until admission gate is implemented. */
@Service
@RequiredArgsConstructor
public class TenantKeyService {
    private static final String ROOT_KEY_ID = "root-v1";
    private final TenantKeyRepository repository;
    private final KeyWrappingService wrapping;
    private final JdbcTemplate jdbc;

    @Transactional
    public UUID provision(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId required");
        if (repository.existsById(tenantId)) return tenantId;
        // Do not silently generate an unrelated v1 when ciphertext uses the legacy key table.
        Long legacy = jdbc.queryForObject(
                "select count(*) from security.tenant_key where tenant_id = ?",
                Long.class, tenantId);
        if (legacy != null && legacy > 0) {
            throw new IllegalStateException("Legacy tenant keys require an explicitly qualified migration");
        }
        // PK also rejects concurrent duplicate provisioning; caller may retry in a new transaction.
        var dek = wrapping.generateTenantKey();
        var wrapped = wrapping.wrap(tenantId, 1L, dek);
        repository.saveAndFlush(TenantKeyEntity.first(tenantId, wrapped, ROOT_KEY_ID, Instant.now()));
        return tenantId;
    }

    @Transactional(readOnly = true)
    public TenantKeyEntity.Slot active(UUID tenantId) {
        return repository.findById(tenantId).orElseThrow(() ->
                new IllegalStateException("Tenant key not provisioned")).active();
    }

    @Transactional(readOnly = true)
    public TenantKeyEntity.Slot forDecrypt(UUID tenantId, long generation) {
        var slot = repository.findById(tenantId).orElseThrow(() ->
                new IllegalStateException("Tenant key not provisioned")).forGeneration(generation);
        if (slot.status() != KeyStatus.ACTIVE && slot.status() != KeyStatus.DECRYPT_ONLY) {
            throw new IllegalStateException("Generation is not available for normal decryption");
        }
        return slot;
    }

    /** Fail closed while transaction/admission and archive restore gates are not implemented. */
    public void rotate(UUID tenantId) {
        throw new UnsupportedOperationException("Rotation requires KMS-R1.8 admission and recovery qualification");
    }
}
