package kz.rm.organization;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface OrganizationLocationRepository extends JpaRepository<OrganizationLocationEntity,UUID> {
    List<OrganizationLocationEntity> findByWorkspaceId(UUID workspaceId);
    Optional<OrganizationLocationEntity> findByIdAndWorkspaceId(UUID id,UUID workspaceId);
}
