package kz.rm.security.kms;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenantKeyService {

    private static final Duration ROTATION_INTERVAL = Duration.ofDays(90);
    private final TenantKeyRepository repository;
    private final KeyWrappingService wrapping;

    // For now, caller supplies a validated company ID. A future Tenant
    // entity must be committed atomically with this provisioning operation.
    @Transactional
    public TenantKeyId provision(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return repository.findByIdTenantIdAndStatus(tenantId, KeyStatus.ACTIVE)
                .map(TenantKeyEntity::getId)
                .orElseGet(() -> {
                    var key = wrapping.generateTenantKey();
                    Instant now = Instant.now();
                    return repository.saveAndFlush(TenantKeyEntity.active(
                            tenantId, 1, wrapping.wrap(tenantId, 1, key),
                            "root-v1", now, now.plus(ROTATION_INTERVAL))).getId();
                });
    }

    @Transactional
    public TenantKeyId rotate(UUID tenantId) {
        TenantKeyEntity current = repository
                .lockByTenantIdAndStatus(tenantId, KeyStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("No ACTIVE key for tenant"));
        int next = Math.addExact(current.getId().getVersion(), 1);
        current.markDecryptOnly();
        repository.saveAndFlush(current);
        Instant now = Instant.now();
        var dek = wrapping.generateTenantKey();
        return repository.saveAndFlush(TenantKeyEntity.active(
                tenantId, next, wrapping.wrap(tenantId, next, dek),
                "root-v1", now, now.plus(ROTATION_INTERVAL))).getId();
    }
}
