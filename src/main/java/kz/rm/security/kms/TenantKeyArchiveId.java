package kz.rm.security.kms;

import java.io.Serializable;
import java.util.UUID;

public record TenantKeyArchiveId(UUID tenantId, Long keyGeneration) implements Serializable {}
