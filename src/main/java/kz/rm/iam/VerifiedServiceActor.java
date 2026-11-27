package kz.rm.iam;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Verified workload identity, never a user command credential. */
public final class VerifiedServiceActor {
    private final UUID account;
    private final String subject;
    private final Instant expiry;
    VerifiedServiceActor(UUID account,String subject,Instant expiry) {
        this.account=Objects.requireNonNull(account);
        this.subject=Objects.requireNonNull(subject);
        this.expiry=Objects.requireNonNull(expiry);
        accountId();
    }
    public UUID accountId(){
        if(!Instant.now().isBefore(expiry))throw new SecurityException("INT_SERVICE_EXPIRED");
        return account;
    }
    String subject(){return subject;}
    public Instant expiresAt(){return expiry;}
}
