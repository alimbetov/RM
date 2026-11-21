package kz.rm.security.kms;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantKeyArchiveRepository
        extends JpaRepository<TenantKeyArchiveEntity, TenantKeyArchiveId> {}
