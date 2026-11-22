package kz.rm.iam;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.InetSocketAddress;
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

/**
 * Integration with a REAL HTTP JWKS endpoint and JCA signature validation.
 * Isolated ephemeral loopback server is NOT an external IdP or HTTPS qualification.
 * The production configuration requires an HTTPS JWKS endpoint.
 */
@SpringBootTest
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class R4LocalJwksIntegrationQualificationTest {
    @Autowired EmailIdentityService emailService;
    @Autowired EmailIdentityCrypto crypto;
    @Autowired SelfServiceTenantOnboardingService onboarding;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
    private static String unsigned(java.math.BigInteger number) {
        byte[] bytes=number.toByteArray();
        return b64(bytes.length>1 && bytes[0]==0
                ?java.util.Arrays.copyOfRange(bytes,1,bytes.length):bytes);
    }
    private static String signed(PrivateKey key, String kid,String iss,String aud,
                                 String subject,long notBefore,long expires) throws Exception {
        String head="{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\""+kid+"\"}";
        String body="{\"iss\":\""+iss+"\",\"aud\":[\""+aud+"\"],\"sub\":\""
                +subject+"\",\"iat\":"+notBefore+",\"exp\":"+expires+"}";
        String part=b64(head.getBytes(StandardCharsets.UTF_8))+"."
                +b64(body.getBytes(StandardCharsets.UTF_8));
        Signature signature=Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(part.getBytes(StandardCharsets.US_ASCII));
        return part+"."+b64(signature.sign());
    }

    @Test void localJwksFetchVerifiesSignatureIssuerAudienceExpirySubjectAndRejectsForeignKey()
            throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair trusted=generator.generateKeyPair(), foreign=generator.generateKeyPair();
        var pub=(RSAPublicKey)trusted.getPublic();
        String kid="rm-r4-"+UUID.randomUUID();
        String jwk="{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"kid\":\""+kid
                +"\",\"alg\":\"RS256\",\"n\":\""+unsigned(pub.getModulus())
                +"\",\"e\":\""+unsigned(pub.getPublicExponent())+"\"}]}";
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/jwks",exchange->{
            byte[] payload=jwk.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,payload.length);
            try(var stream=exchange.getResponseBody()){stream.write(payload);}
        });
        server.start();
        try {
            String issuer="https://issuer.example.test/rm";
            String subject="r4-jwks-sub-"+UUID.randomUUID();
            UUID user=UUID.randomUUID();
            users.saveAndFlush(new UserAccountEntity(user,subject,"JWKS test user"));
            var decoder=NimbusJwtDecoder.withJwkSetUri(
                    "http://127.0.0.1:"+server.getAddress().getPort()+"/jwks").build();
            OidcJwtConfiguration.configureValidation(decoder,issuer,"rm-api");
            var resolver=new OidcActorResolver(decoder,users);
            long now=Instant.now().getEpochSecond();
            String accepted=signed(trusted.getPrivate(),kid,issuer,"rm-api",
                    subject,now-10,now+600);
            var validated=resolver.resolve(accepted);
            assertThat(validated.userId()).isEqualTo(user);
            // Real JWKS signature check feeds the actual tenant creation command.
            String email="r4-jwks-"+UUID.randomUUID()+"@example.kz";
            emailService.requestInitialVerification(validated,email);
            Object[] proof=jdbc.query("""
                    SELECT c.id,c.token_envelope
                    FROM public.iam_email_verification_challenge c
                    JOIN public.iam_email_identity e ON e.id=c.email_identity_id
                    WHERE e.user_id=? AND c.purpose='REGISTRATION'
                    """,rs->{
                        if(!rs.next())throw new IllegalStateException("Missing email challenge");
                        return new Object[]{rs.getObject(1,UUID.class),rs.getBytes(2)};
                    },user);
            assertThat(emailService.verifyRegistration((UUID)proof[0],
                    crypto.decrypt((byte[])proof[1]))).isTrue();
            UUID tenant=UUID.randomUUID();
            var acceptedTenant=onboarding.createFirstTenant(validated,tenant,
                    "JWKS Owner LLP","JWKS Owner","KZ");
            assertThat(acceptedTenant.tenantId()).isEqualTo(tenant);
            assertThat(onboarding.selectTenant(resolver.resolve(accepted),tenant).tenantId())
                    .isEqualTo(tenant);

            assertThatThrownBy(()->resolver.resolve(signed(foreign.getPrivate(),kid,issuer,
                    "rm-api",subject,now-10,now+600))).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->resolver.resolve(signed(trusted.getPrivate(),kid,issuer,
                    "wrong-audience",subject,now-10,now+600))).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->resolver.resolve(signed(trusted.getPrivate(),kid,
                    "https://wrong.test", "rm-api",subject,now-10,now+600)))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->resolver.resolve(signed(trusted.getPrivate(),kid,issuer,
                    "rm-api",subject,now-600,now-300))).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->resolver.resolve(signed(trusted.getPrivate(),kid,issuer,
                    "rm-api","unknown-"+UUID.randomUUID(),now-10,now+600)))
                    .isInstanceOf(SecurityException.class);
        } finally {
            server.stop(0);
        }
    }
}
