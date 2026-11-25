package kz.rm.crm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence-only repository; a caller MUST authorize actor/tenant before use.
 * Never expose the inherited findById(idClass) as a public application query.
 */
interface CrmCustomerRepository extends JpaRepository<CrmCustomerEntity, CrmCustomerKey> {
    Optional<CrmCustomerEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
