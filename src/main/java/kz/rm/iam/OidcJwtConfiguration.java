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
        // Keycloak RM access tokens carry claim typ=Bearer; ID tokens do not.
        // Issuer/audience/signature alone cannot distinguish an ID token from
        // an API access token when clients share the same audience.
        // token_use is optional for Keycloak, but when present (other issuers)
        // it must not identify an ID/refresh token.
        OAuth2TokenValidator<Jwt> accessTokenPurpose=jwt -> {
            Object type=jwt.getClaims().get("typ");
            Object use=jwt.getClaims().get("token_use");
            if(!"Bearer".equals(type) || (use!=null && !"access".equals(use)))
                return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token","RM API access token required",null));
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                expectedAudience,accessTokenPurpose));
    }
}
