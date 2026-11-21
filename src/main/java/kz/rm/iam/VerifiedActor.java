package kz.rm.iam;

import java.time.Instant;
import java.util.UUID;

/**
 * Actor mapped from a cryptographically validated OIDC access token.
 * No public constructor/factory: only the IAM resolver can mint this value.
 * The object is a request-scoped identity, not a substitute for RBAC checks.
 */
public final class VerifiedActor {
    private final UUID userId;
    private final Instant expiresAt;

    VerifiedActor(UUID userId, Instant expiresAt) {
        if(userId==null || expiresAt==null || !Instant.now().isBefore(expiresAt))
            throw new SecurityException("Active verified principal required");
        this.userId=userId;
        this.expiresAt=expiresAt;
    }

    public UUID userId() {
        if(!Instant.now().isBefore(expiresAt))
            throw new SecurityException("Authenticated principal expired");
        return userId;
    }
}
