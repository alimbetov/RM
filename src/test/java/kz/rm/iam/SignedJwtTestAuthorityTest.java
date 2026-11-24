package kz.rm.iam;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import com.nimbusds.jose.jwk.JWKSet;

import static org.assertj.core.api.Assertions.*;
import static kz.rm.iam.SignedJwtTestAuthority.SigningKey.*;

/** Authority contract, no Spring context or database required. */
class SignedJwtTestAuthorityTest {
    @Test void publishesOnlyPublicRsaJwkAndDecodesWithProductionValidators() throws Exception {
        try(var authority=SignedJwtTestAuthority.start()){
            var response=HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(authority.jwksUri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var keys=JWKSet.parse(response.body()).getKeys();
            assertThat(keys).hasSize(1);
            assertThat(keys.getFirst().isPrivate()).isFalse();
            assertThat(keys.getFirst().getAlgorithm().getName()).isEqualTo("RS256");
            String subject="authority-"+UUID.randomUUID();
            var jwt=SignedJwtTestAuthority.decoder(authority.jwksUri()).decode(authority.access(subject));
            assertThat(jwt.getSubject()).isEqualTo(subject);
            assertThat(jwt.getAudience()).contains(SignedJwtTestAuthority.AUDIENCE);
            assertThat(jwt.getClaimAsString("typ")).isEqualTo("Bearer");
        }
    }

    @Test void rejectsUntrustedSignatureIssuerAudiencePurposeAndExpiry() throws Exception {
        try(var a=SignedJwtTestAuthority.start()){
            var decoder=a.decoder();
            String sub="authority-"+UUID.randomUUID();
            String iss=SignedJwtTestAuthority.ISSUER,aud=SignedJwtTestAuthority.AUDIENCE;
            long valid=Instant.now().plusSeconds(600).getEpochSecond();
            long expired=Instant.now().minusSeconds(300).getEpochSecond();
            String[] rejected={
                a.issue(sub,"Bearer",iss,aud,valid,UNTRUSTED,null,null,null),
                a.issue(sub,"Bearer","https://wrong.example.test",aud,valid,TRUSTED,null,null,null),
                a.issue(sub,"Bearer",iss,"other-api",valid,TRUSTED,null,null,null),
                a.issue(sub,"ID",iss,aud,valid,TRUSTED,null,null,null),
                a.issue(sub,null,iss,aud,valid,TRUSTED,null,null,null),
                a.issue(sub,"Bearer",iss,aud,valid,TRUSTED,"refresh",null,null),
                a.issue(sub,"Bearer",iss,aud,valid,TRUSTED,"id",null,null),
                a.issue(sub,"Bearer",iss,aud,expired,TRUSTED,null,null,null)
            };
            for(String token:rejected)assertThatThrownBy(()->decoder.decode(token))
                    .isInstanceOf(JwtException.class);
            assertThat(decoder.decode(a.access(sub)).getSubject()).isEqualTo(sub);
        }
    }

    @Test void independentAuthoritiesCannotVerifyEachOthersTokens() throws Exception {
        try(var a=SignedJwtTestAuthority.start();var b=SignedJwtTestAuthority.start()){
            String bearer=a.access("isolated-subject");
            assertThat(a.decoder().decode(bearer).getSubject()).isEqualTo("isolated-subject");
            assertThatThrownBy(()->b.decoder().decode(bearer)).isInstanceOf(JwtException.class);
        }
    }

    @Test void closeIsIdempotentAndNoRemoteJwksIsAllowed() throws Exception {
        try(var a=SignedJwtTestAuthority.start()){
            a.close();a.close();
            assertThatThrownBy(a::jwksUri).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->a.access("closed")).isInstanceOf(IllegalStateException.class);
        }
        for(String uri:new String[]{"https://example.test/jwks","http://localhost:1234/jwks",
                "http://127.0.0.1:1234/other","http://127.0.0.1:1234/jwks?token=1"})
            assertThatThrownBy(()->SignedJwtTestAuthority.decoder(URI.create(uri)))
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
