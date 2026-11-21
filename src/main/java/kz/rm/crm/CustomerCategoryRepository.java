package kz.rm.crm;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/** Internal-only repository: authorization is mandatory at the service boundary. */
interface CustomerCategoryRepository extends JpaRepository<CustomerCategoryEntity, CrmCategoryKey> {
    Optional<CustomerCategoryEntity> findByTenantIdAndId(UUID tenantId, UUID id);
    Page<CustomerCategoryEntity> findByTenantIdAndStatus(UUID tenantId,
            CustomerCategoryEntity.Status status, Pageable pageable);
    Page<CustomerCategoryEntity> findByTenantId(UUID tenantId, Pageable pageable);
}
