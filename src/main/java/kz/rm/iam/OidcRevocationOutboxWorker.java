package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Explicitly invoked trusted worker. No automatic scheduler or production
 * admission. Durable at-least-once provider logout with attempt fencing.
 * Network I/O is outside SQL transactions; duplicate remote logout is safe.
 */
@Service
public class OidcRevocationOutboxWorker {
    public record Attempt(UUID deletionEventId,UUID workerId,int sequence,byte[] subjectCipher) {}
    public enum Outcome { NO_WORK, ACKNOWLEDGED, RETRY_SCHEDULED, LEASE_LOST }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmailIdentityCrypto crypto;
    private final OidcSessionRevocationGateway provider;

    public OidcRevocationOutboxWorker(JdbcTemplate jdbc,
           PlatformTransactionManager manager, EmailIdentityCrypto crypto,
           OidcSessionRevocationGateway provider) {
        this.jdbc=jdbc;
        this.tx=new TransactionTemplate(manager);
        this.crypto=crypto;
        this.provider=provider;
    }

    /** Processes at most one due row; a separate secured invoker drives cadence. */
    public Outcome processOne(UUID workerId) {
        return processOne(workerId,null);
    }

    /** Optional event scoping for qualified targeted retries and diagnosis. */
    public Outcome processOne(UUID workerId,UUID deletionEventId) {
        if(workerId==null)throw new IllegalArgumentException("Worker identity required");
        Attempt attempt=tx.execute(status->claim(workerId,deletionEventId));
        if(attempt==null)return Outcome.NO_WORK;
        try {
            // Never expose or log the plaintext subject.
            String subject=crypto.decryptOidcSubject(attempt.subjectCipher());
            provider.revokeSessions(subject);
            return tx.execute(status->ack(attempt));
        } catch(RuntimeException failure) {
            // Failure details could contain PII/tokens; only store a fixed code.
            return tx.execute(status->retry(attempt));
        }
    }

    private Attempt claim(UUID workerId,UUID specificEventId) {
        Timestamp now=Timestamp.from(Instant.now());
        UUID selected=jdbc.query("""
                SELECT deletion_event_id
                FROM public.iam_deletion_oidc_revocation_outbox
                WHERE state='PENDING' AND next_attempt_at<=?
                  AND (lease_owner IS NULL OR leased_until<=?)
                  AND (CAST(? AS UUID) IS NULL OR deletion_event_id=?)
                ORDER BY next_attempt_at,deletion_event_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,
                now,now,specificEventId,specificEventId);
        if(selected==null)return null;
        int rows=jdbc.update("""
                UPDATE public.iam_deletion_oidc_revocation_outbox
                SET lease_owner=?,leased_until=?,attempt_count=attempt_count+1,
                    last_failure_code=NULL
                WHERE deletion_event_id=? AND state='PENDING'
                  AND (lease_owner IS NULL OR leased_until<=?)
                """,workerId,Timestamp.from(Instant.now().plusSeconds(60)),selected,now);
        if(rows!=1)throw new IllegalStateException("Revocation lease claim conflict");
        return jdbc.query("""
                SELECT deletion_event_id,attempt_count,provider_subject_envelope
                FROM public.iam_deletion_oidc_revocation_outbox
                WHERE deletion_event_id=? AND lease_owner=?
                """,rs->rs.next()?new Attempt(rs.getObject(1,UUID.class),workerId,
                        rs.getInt(2),rs.getBytes(3)):null,selected,workerId);
    }

    private Outcome ack(Attempt a) {
        int updated=jdbc.update("""
                UPDATE public.iam_deletion_oidc_revocation_outbox
                SET state='ACKNOWLEDGED',acknowledged_at=?,lease_owner=NULL,
                    leased_until=NULL,last_failure_code=NULL
                WHERE deletion_event_id=? AND state='PENDING'
                  AND lease_owner=? AND attempt_count=?
                """,Timestamp.from(Instant.now()),a.deletionEventId(),a.workerId(),a.sequence());
        return updated==1?Outcome.ACKNOWLEDGED:Outcome.LEASE_LOST;
    }

    private Outcome retry(Attempt a) {
        // Bounded exponential backoff; no permanent DEAD without human review.
        long delay=Math.min(3600L,60L<<Math.min(6,Math.max(0,a.sequence()-1)));
        int updated=jdbc.update("""
                UPDATE public.iam_deletion_oidc_revocation_outbox
                SET next_attempt_at=?,lease_owner=NULL,leased_until=NULL,
                    last_failure_code='PROVIDER_OR_DECRYPT_FAILED'
                WHERE deletion_event_id=? AND state='PENDING'
                  AND lease_owner=? AND attempt_count=?
                """,Timestamp.from(Instant.now().plusSeconds(delay)),
                a.deletionEventId(),a.workerId(),a.sequence());
        return updated==1?Outcome.RETRY_SCHEDULED:Outcome.LEASE_LOST;
    }
}
