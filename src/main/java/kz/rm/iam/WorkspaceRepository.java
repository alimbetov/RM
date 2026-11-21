package kz.rm.iam;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {
    Optional<WorkspaceEntity> findByOwnerUserId(UUID ownerUserId);
    Optional<WorkspaceEntity> findByOrganizationId(UUID organizationId);
}
