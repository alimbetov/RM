package kz.rm.iam;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembershipEntity, UUID> {
    Optional<OrganizationMembershipEntity> findByOrganizationIdAndUserId(UUID organizationId,UUID userId);
    List<OrganizationMembershipEntity> findByUserId(UUID userId);
}
