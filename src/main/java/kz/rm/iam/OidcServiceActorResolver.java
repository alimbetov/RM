package kz.rm.iam;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

/** Dedicated workload decoder is deliberately not a JwtDecoder bean. */
@Service
@ConditionalOnProperty(prefix="rm.intelligence.scheduler",name="intraday-enabled",havingValue="true")
public class OidcServiceActorResolver {
    private final JwtDecoder decoder;
    private final UserAccountRepository users;
    private final String audience,client;
    @Autowired
    OidcServiceActorResolver(UserAccountRepository users,
        @Value("${rm.intelligence.scheduler.service-jwks-uri}") String jwks,
        @Value("${rm.intelligence.scheduler.service-issuer}") String issuer,
        @Value("${rm.intelligence.scheduler.service-audience}") String audience,
        @Value("${rm.intelligence.scheduler.service-client-id}") String client,
        @Value("${rm.security.oidc.audience:}") String userAudience) {
        this(verifiedDecoder(jwks,issuer,audience),users,audience,client);
        if(audience.equals(userAudience))throw new IllegalArgumentException("INT_SERVICE_AUDIENCE_COLLISION");
    }
    OidcServiceActorResolver(JwtDecoder decoder,UserAccountRepository users,String audience,String client){
        if(audience.isBlank()||client.isBlank())throw new IllegalArgumentException("INT_SERVICE_CONFIGURATION");
        this.decoder=decoder;this.users=users;this.audience=audience;this.client=client;
    }
    private static JwtDecoder verifiedDecoder(String jwks,String issuer,String audience){
        if(!"https".equalsIgnoreCase(URI.create(jwks).getScheme())||
           !"https".equalsIgnoreCase(URI.create(issuer).getScheme()))
            throw new IllegalArgumentException("INT_SERVICE_HTTPS_REQUIRED");
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OidcJwtConfiguration.configureValidation(decoder,issuer,audience);
        return decoder;
    }
    public VerifiedServiceActor resolve(String token){
        if(token==null||token.isBlank())throw new SecurityException("INT_SERVICE_TOKEN_REQUIRED");
        try{
            var jwt=decoder.decode(token);
            if(!List.of(audience).equals(jwt.getAudience())||!client.equals(jwt.getClaimAsString("azp"))
               ||jwt.getSubject()==null||jwt.getExpiresAt()==null||!Instant.now().isBefore(jwt.getExpiresAt()))
                throw new SecurityException("INT_SERVICE_TOKEN_SCOPE");
            var user=users.findByExternalSubject(jwt.getSubject()).orElseThrow(()->new SecurityException("INT_SERVICE_UNKNOWN"));
            if(user.getStatus()!=UserAccountEntity.Status.ACTIVE||user.getPrincipalKind()!=UserAccountEntity.PrincipalKind.SERVICE)
                throw new SecurityException("INT_SERVICE_ACCOUNT_DENIED");
            return new VerifiedServiceActor(user.getId(),jwt.getSubject(),jwt.getExpiresAt());
        }catch(JwtException ex){throw new SecurityException("INT_SERVICE_TOKEN_INVALID");}
    }
}
