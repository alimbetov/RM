package kz.rm.security.kms;

import java.util.UUID;

/** Construct only after the application service has authorized the loaded entity. */
public record CryptoContext(UUID tenantId, String entityType, UUID entityId, String fieldName) {
    public CryptoContext {
        if (tenantId == null || entityId == null || entityType == null || entityType.isBlank()
                || fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("Valid authorized crypto context required");
        }
    }
}
