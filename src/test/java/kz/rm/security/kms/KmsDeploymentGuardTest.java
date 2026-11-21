package kz.rm.security.kms;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KmsDeploymentGuardTest {
    @Test void singleInstanceDefaultsAreAllowed() {
        assertThatCode(() -> new KmsDeploymentGuard("single-jvm", false, false))
                .doesNotThrowAnyException();
    }

    @Test void distributedConfigurationFailsClosed() {
        assertThatThrownBy(() -> new KmsDeploymentGuard("multi-jvm", false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single-jvm");
    }

    @Test void rotationCannotBeEnabledWithoutQualification() {
        assertThatThrownBy(() -> new KmsDeploymentGuard("single-jvm", true, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KmsDeploymentGuard("single-jvm", false, true))
                .isInstanceOf(IllegalStateException.class);
    }
}
