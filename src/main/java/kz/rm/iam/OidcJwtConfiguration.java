package kz.rm.iam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Internal JWT verification. Disabled until a real trusted issuer, audience
 * and JWKS URL are configured. Does NOT create or expose HTTP controllers.
 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="rm.security.oidc.enabled",havingValue="true")
public class OidcJwtConfiguration {
    @Bean
    JwtDecoder rmOidcJwtDecoder(
            @Value("${rm.security.oidc.jwk-set-uri}") String jwks,
            @Value("${rm.security.oidc.issuer}") String issuer,
            @Value("${rm.security.oidc.audience}") String audience) {
        if(jwks.isBlank() || issuer.isBlank() || audience.isBlank())
            throw new IllegalArgumentException("OIDC issuer, audience and JWKS URL required");
        if(!java.net.URI.create(jwks).getScheme().equalsIgnoreCase("https"))
            throw new IllegalArgumentException("OIDC JWKS must use HTTPS");
        NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
        configureValidation(decoder,issuer,audience);
        return decoder;
    }

    /** Tested with locally signed JWTs; production uses the HTTPS JWKS decoder. */
    static void configureValidation(NimbusJwtDecoder decoder,String issuer,String audience) {
        if(issuer==null || issuer.isBlank() || audience==null || audience.isBlank())
            throw new IllegalArgumentException("OIDC issuer and audience required");
        OAuth2TokenValidator<Jwt> expectedAudience=jwt->
                jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token","Untrusted token audience",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),expectedAudience));
    }
}
