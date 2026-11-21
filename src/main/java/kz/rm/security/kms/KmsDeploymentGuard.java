package kz.rm.security.kms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fail-closed deployment guard: a local in-process key cache and transaction
 * barriers are NOT a distributed rotation/revocation protocol.
 */
@Component
public final class KmsDeploymentGuard {
    public KmsDeploymentGuard(
            @Value("${rm.kms.deployment.mode:single-jvm}") String mode,
            @Value("${rm.kms.rotation.enabled:false}") boolean rotationEnabled,
            @Value("${rm.kms.migration.enabled:false}") boolean migrationEnabled) {
        if (!"single-jvm".equals(mode)) {
            throw new IllegalStateException(
                    "RM-KMS currently supports single-jvm only; distributed key state admission is not qualified");
        }
        if (rotationEnabled || migrationEnabled) {
            throw new IllegalStateException(
                    "RM-KMS automatic rotation and migration are disabled pending transactional qualification");
        }
    }
}
