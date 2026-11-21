package kz.rm.security.kms;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface TenantKeyRepository
        extends JpaRepository<TenantKeyEntity, TenantKeyId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from TenantKeyEntity k where k.id.tenantId = :tenantId and k.status = :status")
    Optional<TenantKeyEntity> lockByTenantIdAndStatus(UUID tenantId, KeyStatus status);

    Optional<TenantKeyEntity> findByIdTenantIdAndStatus(UUID tenantId, KeyStatus status);
}
