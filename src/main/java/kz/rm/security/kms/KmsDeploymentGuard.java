package kz.rm.security.kms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Multi-pod safe operating envelope for the current immutable-key phase.
 * Scheduler exclusivity alone does NOT fence concurrent crypto writers.
 * Rotation and migration stay disabled until distributed fencing is proven.
 */
@Component
public final class KmsDeploymentGuard {
    public KmsDeploymentGuard(
            @Value("${rm.kms.deployment.mode:multi-pod}") String mode,
            @Value("${rm.kms.rotation.enabled:false}") boolean rotationEnabled,
            @Value("${rm.kms.migration.enabled:false}") boolean migrationEnabled) {
        if (!"multi-pod".equals(mode) && !"single-jvm".equals(mode)) {
            throw new IllegalStateException("Unsupported RM-KMS deployment mode");
        }
        if (rotationEnabled || migrationEnabled) {
            throw new IllegalStateException(
                    "RM-KMS rotation and migration require qualified distributed generation fencing");
        }
    }
}
