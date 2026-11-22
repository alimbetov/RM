package kz.rm.iam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;

/**
 * Trusted internal runner, disabled until OIDC provider and retention
 * qualification are approved. Never log provider subjects or credentials.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="rm.iam.oidc-revocation",
        name="scheduler-enabled",havingValue="true")
public class OidcRevocationOutboxScheduler {
    private final OidcRevocationOutboxWorker worker;
    private final int batchLimit;
    private final UUID workerId=UUID.randomUUID();

    public OidcRevocationOutboxScheduler(OidcRevocationOutboxWorker worker,
           @Value("${rm.iam.oidc-revocation.batch-limit:20}") int batchLimit) {
        this.worker=worker;
        if(batchLimit<1 || batchLimit>200)
            throw new IllegalArgumentException("OIDC revocation batch-limit must be 1..200");
        this.batchLimit=batchLimit;
    }

    @Scheduled(fixedDelayString="${rm.iam.oidc-revocation.poll-ms:5000}")
    public void dispatch() {
        for(int i=0;i<batchLimit;i++) {
            if(worker.processOne(workerId)==OidcRevocationOutboxWorker.Outcome.NO_WORK)
                return;
        }
    }
}
