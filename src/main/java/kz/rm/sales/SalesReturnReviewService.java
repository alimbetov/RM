package kz.rm.sales;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * QR-07/R1: authorized review, approve and reject without physical receipt,
 * inventory posting, refund, or mutation of completed SALES evidence.
 *
 * Decision transaction: fresh IAM command admission -> return row lock ->
 * policy / transition / optimistic revision -> state update + append-only event.
 */
@Service
public class SalesReturnReviewService {
    public record Decision(UUID tenantId, UUID returnId, SalesReturnState state,
                           long rowVersion, UUID decisionEventId) {}
    private record Locked(UUID orderId, UUID buyerId, String state,
                          String policyVersion, long version, Instant sourceHandoverAt) {}
    private record Prior(UUID returnId, String fingerprint, UUID id,
                         String state, long version) {}

    // Test-only provisional eligibility: not a commercial return policy.
    private static final Duration UAT_ELIGIBILITY = Duration.ofDays(30);
    private static final String UAT_POLICY = "UAT_1";

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public SalesReturnReviewService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @Transactional
    public Decision startReview(VerifiedActor reviewer, UUID tenant, UUID returnId,
                                long expectedVersion, String policyVersion,
                                String requestKey) {
        return transition(reviewer,tenant,returnId,SalesReturnState.UNDER_REVIEW,
                expectedVersion,policyVersion,requestKey);
    }

    @Transactional
    public Decision approve(VerifiedActor reviewer, UUID tenant, UUID returnId,
                            long expectedVersion, String policyVersion,
                            String requestKey) {
        return transition(reviewer,tenant,returnId,SalesReturnState.APPROVED,
                expectedVersion,policyVersion,requestKey);
    }

    @Transactional
    public Decision reject(VerifiedActor reviewer, UUID tenant, UUID returnId,
                           long expectedVersion, String policyVersion,
                           String requestKey) {
        return transition(reviewer,tenant,returnId,SalesReturnState.REJECTED,
                expectedVersion,policyVersion,requestKey);
    }

    private Decision transition(VerifiedActor reviewer, UUID tenant, UUID returnId,
                                SalesReturnState target, long expectedVersion,
                                String policyVersion, String key) {
        if (reviewer == null || tenant == null || returnId == null)
            throw new SecurityException("RETURN_REVIEWER_TENANT_REQUIRED");
        if (expectedVersion < 0 || !validToken(key,100) ||
                !validToken(policyVersion,40))
            throw new IllegalArgumentException("RETURN_REVIEW_INPUT_INVALID");

        UUID reviewerId=reviewer.userId();
        authorization.requireForCommand(reviewerId,tenant,DomainPermission.SALES_RETURN_REVIEW);
        Locked locked=jdbc.query("""
                SELECT rr.order_id, rr.buyer_user_id, rr.state, rr.policy_version,
                       rr.row_version,h.happened_at
                FROM crm.sales_return_request rr
                JOIN crm.sales_pickup_handover h
                  ON h.tenant_id=rr.tenant_id AND h.id=rr.source_handover_id
                JOIN crm.sales_order o
                  ON o.tenant_id=rr.tenant_id AND o.id=rr.order_id
                WHERE rr.tenant_id=? AND rr.id=? AND o.status='COMPLETED'
                FOR UPDATE OF rr
                """,rs->rs.next()?new Locked(rs.getObject(1,UUID.class),
                        rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),
                        rs.getLong(5),rs.getTimestamp(6).toInstant()):null,
                tenant,returnId);
        if(locked==null)throw new SecurityException("RETURN_NOT_ACCESSIBLE");
        if(reviewerId.equals(locked.buyerId()))
            throw new SecurityException("RETURN_SELF_APPROVAL_FORBIDDEN");
        if(!locked.policyVersion().equals(policyVersion))
            throw new IllegalStateException("RETURN_POLICY_VERSION_CONFLICT");

        String fingerprint=hash(reviewerId,returnId,target,expectedVersion,policyVersion);
        Prior prior=jdbc.query("""
                SELECT return_id,request_fingerprint,id,to_state,expected_row_version
                FROM crm.sales_return_event
                WHERE tenant_id=? AND request_key=?
                """,rs->rs.next()?new Prior(rs.getObject(1,UUID.class),rs.getString(2),
                        rs.getObject(3,UUID.class),rs.getString(4),rs.getLong(5)):null,
                tenant,key);
        if(prior!=null){
            if(!prior.returnId().equals(returnId) ||
                    !prior.fingerprint().equals(fingerprint) ||
                    !prior.state().equals(target.name()) ||
                    prior.version()!=expectedVersion)
                throw new IllegalStateException("RETURN_DECISION_IDEMPOTENCY_CONFLICT");
            // The stored decision is an immutable receipt, not a new mutation;
            // return its originally committed version even if later steps occurred.
            return new Decision(tenant,returnId,target,expectedVersion+1,prior.id());
        }

        SalesReturnState current=SalesReturnState.valueOf(locked.state());
        if(locked.version()!=expectedVersion)
            throw new IllegalStateException("RETURN_REVIEW_VERSION_CONFLICT");
        if(!current.mayTransitionTo(target))
            throw new IllegalStateException("RETURN_REVIEW_TRANSITION_FORBIDDEN");
        if(target==SalesReturnState.APPROVED){
            if(!UAT_POLICY.equals(policyVersion))
                throw new IllegalStateException("RETURN_POLICY_UNAPPROVED");
            Instant now=Instant.now();
            if(now.isAfter(locked.sourceHandoverAt().plus(UAT_ELIGIBILITY)))
                throw new IllegalStateException("RETURN_UAT_ELIGIBILITY_EXPIRED");
        }

        int updated=jdbc.update("""
                UPDATE crm.sales_return_request
                SET state=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND state=? AND row_version=?
                """,target.name(),tenant,returnId,current.name(),expectedVersion);
        if(updated!=1)throw new IllegalStateException("RETURN_REVIEW_VERSION_CONFLICT");
        UUID eventId=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.sales_return_event
                  (tenant_id,id,return_id,actor_user_id,event_type,occurred_at,
                   request_key,request_fingerprint,from_state,to_state,
                   expected_row_version,policy_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,eventId,returnId,reviewerId,target.name(),
                Timestamp.from(Instant.now()),key,fingerprint,current.name(),
                target.name(),expectedVersion,policyVersion);
        return new Decision(tenant,returnId,target,expectedVersion+1,eventId);
    }

    private static boolean validToken(String value,int max) {
        return value!=null && !value.isBlank() && value.length()<=max
                && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }

    private static String hash(UUID actor, UUID returnId, SalesReturnState target,
                               long expectedVersion, String policy) {
        try{
            String canonical=actor+"|"+returnId+"|"+target.name()+"|"+
                    expectedVersion+"|"+policy;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException ex){
            throw new IllegalStateException("SHA256 unavailable",ex);
        }
    }
}
