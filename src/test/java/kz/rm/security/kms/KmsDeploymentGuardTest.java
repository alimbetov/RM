package kz.rm.security.kms;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KmsDeploymentGuardTest {
    @Test void immutableKeysAllowMultiPod() {
        assertThatCode(() -> new KmsDeploymentGuard("multi-pod", false, false))
                .doesNotThrowAnyException();
    }

    @Test void singleJvmRemainsSupportedForLocalDevelopment() {
        assertThatCode(() -> new KmsDeploymentGuard("single-jvm", false, false))
                .doesNotThrowAnyException();
    }

    @Test void unknownDeploymentConfigurationFailsClosed() {
        assertThatThrownBy(() -> new KmsDeploymentGuard("unverified-mode", false, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void multiPodCannotEnableRotationOrMigrationWithoutFencing() {
        assertThatThrownBy(() -> new KmsDeploymentGuard("multi-pod", true, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distributed");
        assertThatThrownBy(() -> new KmsDeploymentGuard("multi-pod", false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distributed");
    }

    /**
     * UR-R3.4: one canonical proof for the complete supported deployment-mode
     * admission contract, not evidence for external KMS/HSM certification.
     */
    @Test void urR34DeploymentConfigurationAcceptsOnlySafeImmutableKeyModes() {
        for (String valid : new String[]{"single-jvm", "multi-pod"}) {
            assertThatCode(() -> new KmsDeploymentGuard(valid, false, false))
                    .doesNotThrowAnyException();
            for (boolean rotation : new boolean[]{false, true}) {
                for (boolean migration : new boolean[]{false, true}) {
                    if (!rotation && !migration) continue;
                    assertThatThrownBy(() -> new KmsDeploymentGuard(
                            valid, rotation, migration))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("distributed generation fencing");
                }
            }
        }
        for (String invalid : new String[]{"", "unsupported", "MULTI-POD", "single-jvm "}) {
            assertThatThrownBy(() -> new KmsDeploymentGuard(invalid, false, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unsupported RM-KMS deployment mode");
        }
    }
}
