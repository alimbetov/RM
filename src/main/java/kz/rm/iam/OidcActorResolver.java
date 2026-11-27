package kz.rm.iam;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

/**
 * Verifies bearer token through configured JWT decoder and maps its subject
 * to an ACTIVE RM user; never trusts actorId from a request.
 * No security HTTP filter/controller is installed by this class.
 */
@Service
@ConditionalOnProperty(name="rm.security.oidc.enabled",havingValue="true")
public class OidcActorResolver {
    private final JwtDecoder decoder;
    private final UserAccountRepository users;
    @org.springframework.beans.factory.annotation.Value("${rm.intelligence.scheduler.service-audience:}")
    private String serviceAudience="";
    @org.springframework.beans.factory.annotation.Value("${rm.intelligence.scheduler.service-client-id:}")
    private String serviceClient="";

    public OidcActorResolver(JwtDecoder decoder,UserAccountRepository users) {
        this.decoder=decoder;this.users=users;
    }

    public VerifiedActor resolve(String accessToken) {
        if(accessToken==null || accessToken.isBlank())
            throw new SecurityException("Verified bearer token required");
        try {
            var jwt=decoder.decode(accessToken);
            if((!serviceAudience.isBlank()&&java.util.Objects.requireNonNullElse(jwt.getAudience(),java.util.List.<String>of()).contains(serviceAudience))
               ||(!serviceClient.isBlank()&&serviceClient.equals(jwt.getClaimAsString("azp"))))
                throw new SecurityException("Service token cannot use user admission");
            String subject=jwt.getSubject();
            if(subject==null || subject.isBlank() || jwt.getExpiresAt()==null)
                throw new SecurityException("OIDC principal subject or expiry missing");
            var user=users.findByExternalSubject(subject)
                .orElseThrow(()->new SecurityException("OIDC principal not linked to RM"));
            if(user.getPrincipalKind()!=UserAccountEntity.PrincipalKind.HUMAN)
                throw new SecurityException("Service principal cannot use user admission");
            if(user.getStatus()!=UserAccountEntity.Status.ACTIVE)
                throw new SecurityException("OIDC user inactive");
            return new VerifiedActor(user.getId(),jwt.getExpiresAt());
        } catch(JwtException error) {
            // Never propagate the presented token or raw issuer payload in logs.
            throw new SecurityException("OIDC token verification failed");
        }
    }
}
