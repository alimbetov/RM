package kz.rm.iam;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * RM-E2E-04: only an explicitly configured OIDC issuer enables authenticated
 * business HTTP entrypoints. No raw actor UUID, session, basic login or form.
 *
 * Production ingress must separately enforce TLS, rate limits and trace IDs.
 */
@Configuration(proxyBeanMethods=false)
public class RmOidcHttpSecurity {
    @Bean
    @ConditionalOnProperty(name="rm.security.oidc.enabled",havingValue="true")
    SecurityFilterChain rmOidcChain(HttpSecurity http) throws Exception {
        http.csrf(csrf->csrf.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth->auth
                .requestMatchers(HttpMethod.GET,"/api/v1/health").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth->oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name="rm.security.oidc.enabled",havingValue="false",
                           matchIfMissing=true)
    SecurityFilterChain rmOidcDisabled(HttpSecurity http) throws Exception {
        http.csrf(csrf->csrf.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth->auth
                .requestMatchers(HttpMethod.GET,"/api/v1/health").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
