package kz.rm.fx;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import java.time.Clock;

/** One injectable UTC time source for deterministic FX service admission. */
@Configuration(proxyBeanMethods = false)
public class FxEvaluationClockConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock fxEvaluationClock() {
        return Clock.systemUTC();
    }
}
