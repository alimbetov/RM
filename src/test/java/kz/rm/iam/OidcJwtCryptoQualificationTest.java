package kz.rm.iam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** No network or real identity provider: JCA-signed synthetic OIDC JWTs. */
@SpringBootTest
class OidcJwtCryptoQualificationTest {
    @Autowired UserAccountRepository users;

    @Test void signatureIssuerAudienceAndExpiryAreAllRequired() throws Exception {
        KeyPair pair=keypair();
        String issuer="https://login.example.test/realms/rm";
        String subject="signed-sub-"+UUID.randomUUID();
        UUID id=UUID.randomUUID();
        users.saveAndFlush(new UserAccountEntity(id,subject,"Signed Actor"));
        var decoder=NimbusJwtDecoder.withPublicKey((RSAPublicKey)pair.getPublic()).build();
        OidcJwtConfiguration.configureValidation(decoder,issuer,"rm-api");
        var resolver=new OidcActorResolver(decoder,users);
        long now=Instant.now().getEpochSecond();

        String valid=sign(pair.getPrivate(),issuer,"rm-api",subject,now-10,now+600);
        assertThat(resolver.resolve(valid).userId()).isEqualTo(id);
        assertThatThrownBy(()->resolver.resolve(
            sign(pair.getPrivate(),"https://wrong.example.test","rm-api",subject,now-10,now+600)))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->resolver.resolve(
            sign(pair.getPrivate(),issuer,"another-audience",subject,now-10,now+600)))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->resolver.resolve(
            sign(pair.getPrivate(),issuer,"rm-api",subject,now-600,now-300)))
            .isInstanceOf(SecurityException.class);
        KeyPair attacker=keypair();
        assertThatThrownBy(()->resolver.resolve(
            sign(attacker.getPrivate(),issuer,"rm-api",subject,now-10,now+600)))
            .isInstanceOf(SecurityException.class);
    }

    private static KeyPair keypair() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String sign(PrivateKey privateKey,String iss,String aud,String sub,
                               long issued,long expires) throws Exception {
        String header="{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String body="{\"iss\":\""+iss+"\",\"aud\":[\""+aud+"\"],\"sub\":\""+sub+
                "\",\"iat\":"+issued+",\"exp\":"+expires+"}";
        var encoder=Base64.getUrlEncoder().withoutPadding();
        String payload=encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))+"."
                +encoder.encodeToString(body.getBytes(StandardCharsets.UTF_8));
        Signature signature=Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(payload.getBytes(StandardCharsets.US_ASCII));
        return payload+"."+encoder.encodeToString(signature.sign());
    }
}
