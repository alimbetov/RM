package kz.rm.iam;

import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Test-only bridge: reuse production validation without widening production API. */
public final class OidcTestDecoderSupport {
    private OidcTestDecoderSupport() {}
    public static void configure(NimbusJwtDecoder decoder,String issuer,String audience) {
        OidcJwtConfiguration.configureValidation(decoder,issuer,audience);
    }
}
