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
}
