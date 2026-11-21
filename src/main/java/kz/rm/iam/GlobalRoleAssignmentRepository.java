package kz.rm.iam;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
public interface GlobalRoleAssignmentRepository extends JpaRepository<GlobalRoleAssignmentEntity, UUID> {
    List<GlobalRoleAssignmentEntity> findByUserIdAndRevokedAtIsNull(UUID userId);
}
