package kz.rm.security.kms;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/** Provisioning is idempotent across pods; rotation remains disabled. */
@Service
@RequiredArgsConstructor
public class TenantKeyService {
    private static final String ROOT_KEY_ID = "root-v1";
    private final TenantKeyRepository repository;
    private final KeyWrappingService wrapping;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    /**
     * Two contenders may generate different provisional keys; only the committed
     * winner's wrapped DEK persists. On PK conflict the losing transaction rolls
     * back and reads the winning row in a fresh transaction.
     *
     * This standalone operation uses REQUIRES_NEW; tenant onboarding must not
     * assume atomicity with a separate service's registration transaction.
     */
    public UUID provision(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId required");
        try {
            return inNewTransaction(() -> {
                if (repository.existsById(tenantId)) return tenantId;
                Long legacy = jdbc.queryForObject(
                        "select count(*) from security.tenant_key where tenant_id = ?",
                        Long.class, tenantId);
                if (legacy != null && legacy > 0) {
                    throw new IllegalStateException(
                            "Legacy tenant keys require an explicitly qualified migration");
                }
                var dek = wrapping.generateTenantKey();
                var wrapped = wrapping.wrap(tenantId, 1L, dek);
                repository.saveAndFlush(
                        TenantKeyEntity.first(tenantId, wrapped, ROOT_KEY_ID, Instant.now()));
                return tenantId;
            });
        } catch (DataIntegrityViolationException conflict) {
            // Never catch arbitrary failures as proof that provisioning succeeded.
            return inNewTransaction(() -> {
                if (!repository.existsById(tenantId)) throw conflict;
                return tenantId;
            });
        }
    }

    private UUID inNewTransaction(java.util.function.Supplier<UUID> callback) {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> callback.get());
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

    /** Fail closed until the distributed generation/epoch admission gate is tested. */
    public void rotate(UUID tenantId) {
        throw new UnsupportedOperationException(
                "Rotation requires distributed fencing and recovery qualification");
    }
}
