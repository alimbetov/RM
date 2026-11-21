package kz.rm.iam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class OidcActorResolverQualificationTest {
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;

    private JwtDecoder verifiedDecoder(String subject,Instant expires) {
        // In this unit test, verification is simulated by the decoder.
        // Production decoder verifies JWKS signature, issuer, audience and time.
        return token->Jwt.withTokenValue(token).header("alg","RS256")
                .subject(subject).issuedAt(Instant.now().minusSeconds(1))
                .expiresAt(expires).build();
    }

    @Test void mapsSubjectToStableActiveUserAndExpiresWithToken() {
        UUID id=UUID.randomUUID();
        String subject="oidc-sub-"+UUID.randomUUID();
        users.saveAndFlush(new UserAccountEntity(id,subject,"Trusted user"));
        var decoder=verifiedDecoder(subject,Instant.now().plusSeconds(120));
        var actor=new OidcActorResolver(decoder,users).resolve("synthetic-test-only");
        assertThat(actor.userId()).isEqualTo(id);
        jdbc.update("UPDATE iam_user_account SET status='SUSPENDED' WHERE id=?",id);
        assertThatThrownBy(()->new OidcActorResolver(decoder,users)
                .resolve("synthetic-test-only")).isInstanceOf(SecurityException.class);
    }

    @Test void rejectsUnknownSubjectExpiredActorAndBadJwt() {
        var decoder=verifiedDecoder("missing-"+UUID.randomUUID(),
                Instant.now().plusSeconds(120));
        assertThatThrownBy(()->new OidcActorResolver(decoder,users)
                .resolve("synthetic-test-only")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->new OidcActorResolver(decoder,users)
                .resolve(" ")).isInstanceOf(SecurityException.class);

        UUID id=UUID.randomUUID();
        String subject="oidc-sub-"+UUID.randomUUID();
        users.saveAndFlush(new UserAccountEntity(id,subject,"Expiring user"));
        var expired=new OidcActorResolver(
                verifiedDecoder(subject,Instant.now().minusSeconds(2)),users);
        assertThatThrownBy(()->expired.resolve("synthetic-test-only"))
                .isInstanceOf(SecurityException.class);
        JwtDecoder bad=token->{throw new BadJwtException("invalid signature");};
        assertThatThrownBy(()->new OidcActorResolver(bad,users).resolve("bad"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("OIDC token verification failed");
    }
}
