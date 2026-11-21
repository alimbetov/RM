package kz.rm.crm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal persistence only. Application callers must first verify actor,
 * tenant, CRM permissions and any applicable customer-level access.
 */
interface CrmContactRepository extends JpaRepository<CrmContactEntity, CrmContactKey> {
    Optional<CrmContactEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<CrmContactEntity> findByTenantIdAndCustomerId(UUID tenantId, UUID customerId);
}
