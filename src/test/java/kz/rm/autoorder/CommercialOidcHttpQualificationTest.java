package kz.rm.autoorder;

import jakarta.servlet.Filter;
import kz.rm.iam.UserAccountEntity;
import kz.rm.iam.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Synthetic transport qualification only: a test decoder stands in for trusted
 * JWKS. Real issuer, TLS and externally delivered tokens remain W5 admission gates.
 */
@SpringBootTest(properties={
        "rm.security.oidc.enabled=true",
        "rm.security.oidc.jwk-set-uri=https://issuer.invalid.test/jwks",
        "rm.security.oidc.issuer=https://issuer.invalid.test",
        "rm.security.oidc.audience=rm-api"
})
class CommercialOidcHttpQualificationTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class SyntheticJwt {
        @Bean
        @Primary
        JwtDecoder syntheticJwtDecoder() {
            return value->{
                if(!"signed-test-bearer".equals(value))
                    throw new BadJwtException("Invalid test bearer");
                return Jwt.withTokenValue(value).header("alg","RS256")
                        .subject("w5-http-"+UUID.randomUUID())
                        .issuedAt(Instant.now().minusSeconds(10))
                        .expiresAt(Instant.now().plusSeconds(300))
                        .audience(java.util.List.of("rm-api"))
                        .issuer("https://issuer.invalid.test").build();
            };
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityFilter;

    private MockMvc mvc;

    @BeforeEach void configure() {
        mvc=MockMvcBuilders.webAppContextSetup(context)
                .addFilters(securityFilter).build();
    }

    @Test void anonymousAndInvalidBearerAreDeniedBeforeCommercialService() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/commercial/buyer/orders"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/commercial/buyer/orders")
                .header("Authorization","Bearer malicious"))
                .andExpect(status().isUnauthorized());
    }

    @Test void validBearerWithUnlinkedSubjectFailsClosed() throws Exception {
        mvc.perform(get("/api/v1/commercial/buyer/orders")
                .header("Authorization","Bearer signed-test-bearer"))
                .andExpect(status().isForbidden());
    }
}
