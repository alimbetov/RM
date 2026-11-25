package kz.rm.crm;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/** Internal-only repository, every application query includes tenantId. */
interface CustomerContractRepository extends JpaRepository<CustomerContractEntity, CrmContractKey> {
    Optional<CustomerContractEntity> findByTenantIdAndId(UUID tenantId, UUID id);
    Page<CustomerContractEntity> findByTenantIdAndCustomerId(UUID tenantId, UUID customerId, Pageable pageable);
    Page<CustomerContractEntity> findByTenantId(UUID tenantId, Pageable pageable);
}
