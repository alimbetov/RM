package kz.rm.bootstrap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void healthReturnsUp() {
        var response = new HealthController().health();
        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.application()).isEqualTo("RM");
    }
}
