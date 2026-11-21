package kz.rm.security.kms;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TenantKeyRepository extends JpaRepository<TenantKeyEntity, UUID> {}
