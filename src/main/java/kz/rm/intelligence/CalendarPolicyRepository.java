package kz.rm.intelligence;
import org.springframework.data.repository.Repository;
import java.util.Optional;
import java.util.UUID;

/** Internal tenant-scoped reads only; writes are append-only via service. */
interface CalendarPolicyRepository extends Repository<CalendarPolicyEntity,CalendarPolicyKey> {
    Optional<CalendarPolicyEntity> findFirstByTenantIdOrderByVersionDesc(UUID tenantId);
    Optional<CalendarPolicyEntity> findByTenantIdAndVersion(UUID tenantId,long version);
}
