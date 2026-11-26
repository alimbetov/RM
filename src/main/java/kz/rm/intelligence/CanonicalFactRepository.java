package kz.rm.intelligence;
import org.springframework.data.repository.Repository;
import java.util.Optional;
import java.util.UUID;

/** Internal exact-key lookup; no public unscoped CRUD. */
interface CanonicalFactRepository extends Repository<CanonicalFactEntity,CanonicalFactKey> {
    Optional<CanonicalFactEntity> findByTenantIdAndOwnerAndKindAndSourceId(UUID tenantId,CanonicalFactKey.Owner owner,
        CanonicalFactKey.Kind kind,UUID sourceId);
}
