package kz.rm.iam;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
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
    SecurityFilterChain rmOidcChain(HttpSecurity http,UserAccountRepository users,
            @Value("${rm.intelligence.scheduler.service-audience:}") String serviceAudience,
            @Value("${rm.intelligence.scheduler.service-client-id:}") String serviceClient) throws Exception {
        var converter=new JwtAuthenticationConverter();
        http.csrf(csrf->csrf.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth->auth
                .requestMatchers(HttpMethod.GET,"/api/v1/health").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth->oauth.jwt(jwt->jwt.jwtAuthenticationConverter(token->{
                String subject=token.getSubject();
                boolean service=(!serviceAudience.isBlank()&&java.util.Objects.requireNonNullElse(token.getAudience(),java.util.List.<String>of()).contains(serviceAudience))
                    ||(!serviceClient.isBlank()&&serviceClient.equals(token.getClaimAsString("azp")))
                    ||(subject!=null&&users.findByExternalSubject(subject).map(u->
                        u.getPrincipalKind()!=UserAccountEntity.PrincipalKind.HUMAN).orElse(false));
                if(service)throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token","User token required",null));
                return converter.convert(token);
            })));
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
