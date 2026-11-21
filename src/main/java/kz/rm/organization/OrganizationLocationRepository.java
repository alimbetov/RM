package kz.rm.organization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationLocationRepository extends JpaRepository<OrganizationLocationEntity,UUID> {
    List<OrganizationLocationEntity> findByWorkspaceId(UUID workspaceId);
    Optional<OrganizationLocationEntity> findByIdAndWorkspaceId(UUID id,UUID workspaceId);

    @Query("""
        SELECT l FROM OrganizationLocationEntity l
        WHERE l.workspaceId = :tenant
          AND (:type IS NULL OR l.type = :type)
          AND (:status IS NULL OR l.status = :status)
          AND LOWER(l.name) LIKE LOWER(CONCAT('%', :name, '%'))
        """)
    Page<OrganizationLocationEntity> search(
            @Param("tenant") UUID tenant,
            @Param("type") OrganizationLocationEntity.Type type,
            @Param("status") OrganizationLocationEntity.Status status,
            @Param("name") String name,
            Pageable pageable);
}
