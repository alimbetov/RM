package kz.rm.iam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Keycloak Admin REST adapter. Disabled by default: no simulated ACK.
 * Caller supplies the restricted admin bearer token via secret manager.
 */
@Component
public class KeycloakOidcSessionRevocationGateway implements OidcSessionRevocationGateway {
    private final boolean enabled;
    private final URI baseUri;
    private final String realm;
    private final String adminBearer;
    private final HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public KeycloakOidcSessionRevocationGateway(
            @Value("${rm.iam.oidc-revocation.enabled:false}") boolean enabled,
            @Value("${rm.iam.oidc-revocation.base-url:}") String url,
            @Value("${rm.iam.oidc-revocation.realm:}") String realm,
            @Value("${RM_IAM_OIDC_REVOCATION_ADMIN_BEARER:}") String bearer) {
        this.enabled=enabled;
        this.baseUri=url==null || url.isBlank()?null:URI.create(url);
        this.realm=realm;
        this.adminBearer=bearer;
    }

    @Override
    public void revokeSessions(String subject) {
        if(!enabled || baseUri==null || !"https".equalsIgnoreCase(baseUri.getScheme())
                || baseUri.getHost()==null || baseUri.getUserInfo()!=null
                || baseUri.getQuery()!=null || baseUri.getFragment()!=null
                || baseUri.getPath()==null || !baseUri.getPath().matches("/?") 
                || realm==null || !realm.matches("[A-Za-z0-9_-]{1,80}")
                || adminBearer==null || adminBearer.isBlank())
            throw new IllegalStateException("Trusted OIDC revocation provider not configured");
        // The account external_subject must be the actual Keycloak user ID.
        // Reject opaque/issuer-qualified subjects rather than guessing a user.
        UUID keycloakUserId;
        try { keycloakUserId=UUID.fromString(subject); }
        catch(RuntimeException invalid) {throw new IllegalStateException("OIDC user ID mapping unavailable");}
        URI endpoint=baseUri.resolve("/admin/realms/"+realm+"/users/"+keycloakUserId+"/logout");
        HttpRequest request=HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(8))
                .header("Authorization","Bearer "+adminBearer)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<Void> response=http.send(request,HttpResponse.BodyHandlers.discarding());
            // 204 is the documented explicit Keycloak user-session logout ACK.
            if(response.statusCode()!=204)throw new IllegalStateException("OIDC logout not acknowledged");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OIDC logout interrupted");
        } catch(java.io.IOException e) {
            throw new IllegalStateException("OIDC logout transport unavailable");
        }
    }
}
