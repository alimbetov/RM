package kz.rm.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;

/**
 * Nightly cleanup at 02:30 Asia/Almaty (configurable).
 * Scheduler is opt-in until retention/legal and production acceptance are signed.
 * No public endpoint, and no sensitive data is logged.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="rm.retention", name="enabled", havingValue="true")
public class AuditRetentionScheduler {
    private static final Logger log=LoggerFactory.getLogger(AuditRetentionScheduler.class);
    private final AuditRetentionService retention;
    private final int batchSize;
    private final int maxBatches;

    public AuditRetentionScheduler(AuditRetentionService retention,
            @Value("${rm.retention.batch-size:500}") int batchSize,
            @Value("${rm.retention.max-batches:10}") int maxBatches) {
        this.retention=retention;
        this.batchSize=batchSize;
        this.maxBatches=maxBatches;
    }

    @Scheduled(cron="${rm.retention.cron:0 30 2 * * *}",zone="${rm.retention.time-zone:Asia/Almaty}")
    public void nightly() {
        var outcome=retention.runBounded(batchSize,maxBatches);
        log.info("RM audit retention run={} deleted={} batches={} skipped={}",
                outcome.runId(),outcome.deletedRows(),outcome.batches(),outcome.skipped());
    }
}
