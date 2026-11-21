package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Ownership changes require two separate verified people:
 * the ACTIVE current OWNER requests, the ACTIVE successor accepts.
 * The workspace row serializes membership/RBAC changes, ensuring an owner is
 * promoted before the previous owner is demoted in the same transaction.
 */
@Service
public class TenantOwnershipTransferService {
    private final JdbcTemplate jdbc;
    private final TenantRoleService roles;

    public TenantOwnershipTransferService(JdbcTemplate jdbc,TenantRoleService roles) {
        this.jdbc=jdbc;
        this.roles=roles;
    }

    public record Transfer(UUID id,UUID fromUserId,UUID toUserId,String state) {}
    private record Workspace(UUID organizationId,String status) {}
    private record State(UUID id,UUID fromId,UUID toId,String status,Instant expiry) {}

    @Transactional
    public Transfer requestTransfer(VerifiedActor owner,UUID tenantId,UUID successorId) {
        if(owner==null || successorId==null)throw new SecurityException("Verified owner and successor required");
        UUID from=owner.userId();
        if(from.equals(successorId))throw new SecurityException("Self ownership transfer prohibited");
        roles.lockAndAuthorize(from,tenantId);
        requireEligibleSuccessor(tenantId,successorId);
        State state=currentRequest(tenantId);
        Instant now=Instant.now();
        if(state!=null && "PENDING".equals(state.status()) && state.expiry().isAfter(now)) {
            if(state.fromId().equals(from) && state.toId().equals(successorId))
                return new Transfer(state.id(),from,successorId,"PENDING");
            throw new IllegalStateException("Another ownership transfer is pending");
        }
        UUID requestId=UUID.randomUUID();
        Timestamp created=Timestamp.from(now),expires=Timestamp.from(now.plus(24,ChronoUnit.HOURS));
        if(state==null) {
            jdbc.update("""
                    INSERT INTO public.iam_owner_transfer_request
                      (tenant_id,request_id,from_user_id,to_user_id,state,requested_at,expires_at,row_version)
                    VALUES (?,?,?,?,'PENDING',?,?,0)
                    """,tenantId,requestId,from,successorId,created,expires);
        } else {
            jdbc.update("""
                    UPDATE public.iam_owner_transfer_request
                    SET request_id=?,from_user_id=?,to_user_id=?,state='PENDING',
                        requested_at=?,expires_at=?,decided_at=NULL,row_version=row_version+1
                    WHERE tenant_id=?
                    """,requestId,from,successorId,created,expires,tenantId);
        }
        audit(tenantId,from,successorId,"OWNER_TRANSFER_REQUESTED");
        return new Transfer(requestId,from,successorId,"PENDING");
    }

    @Transactional
    public Transfer acceptTransfer(VerifiedActor successor,UUID tenantId,UUID requestId) {
        if(successor==null || tenantId==null || requestId==null)
            throw new SecurityException("Verified successor and valid request required");
        UUID to=successor.userId();
        requireActiveLinked(to); // account lock precedes tenant workspace lock
        Workspace workspace=lockWorkspace(tenantId);
        if(workspace==null || !"ACTIVE".equals(workspace.status()))
            throw new SecurityException("Organization is not active");

        State request=currentRequest(tenantId);
        if(request==null || !request.id().equals(requestId) || !request.toId().equals(to))
            throw new SecurityException("Ownership transfer request not addressed to this account");
        if("ACCEPTED".equals(request.status()))
            return new Transfer(requestId,request.fromId(),to,"ACCEPTED");
        if(!"PENDING".equals(request.status()) || !request.expiry().isAfter(Instant.now()))
            throw new SecurityException("Ownership transfer request is no longer valid");
        // A later suspension or owner revocation invalidates a pending request.
        if(!activeOwner(workspace.organizationId(),request.fromId()))
            throw new SecurityException("Initiating owner unavailable");
        requireEligibleSuccessor(tenantId,to);
        int promoted=jdbc.update("""
                UPDATE public.iam_org_membership
                SET role='OWNER',row_version=row_version+1
                WHERE organization_id=? AND user_id=? AND role<>'OWNER' AND status='ACTIVE'
                """,workspace.organizationId(),to);
        if(promoted!=1)throw new IllegalStateException("Successor membership is no longer eligible");
        int demoted=jdbc.update("""
                UPDATE public.iam_org_membership
                SET role='MEMBER',row_version=row_version+1
                WHERE organization_id=? AND user_id=? AND role='OWNER' AND status='ACTIVE'
                """,workspace.organizationId(),request.fromId());
        if(demoted!=1)throw new IllegalStateException("Initiating owner lost ownership");
        int accepted=jdbc.update("""
                UPDATE public.iam_owner_transfer_request
                SET state='ACCEPTED',decided_at=?,row_version=row_version+1
                WHERE tenant_id=? AND request_id=? AND state='PENDING'
                """,Timestamp.from(Instant.now()),tenantId,requestId);
        if(accepted!=1)throw new IllegalStateException("Concurrent ownership transfer");
        audit(tenantId,to,request.fromId(),"OWNER_TRANSFER_ACCEPTED");
        return new Transfer(requestId,request.fromId(),to,"ACCEPTED");
    }

    @Transactional
    public void cancelTransfer(VerifiedActor owner,UUID tenantId,UUID requestId) {
        if(owner==null || requestId==null)throw new SecurityException("Verified owner and request required");
        UUID from=owner.userId();
        roles.lockAndAuthorize(from,tenantId);
        State request=currentRequest(tenantId);
        if(request==null || !request.id().equals(requestId) || !request.fromId().equals(from))
            throw new SecurityException("Ownership transfer request unavailable");
        if("CANCELLED".equals(request.status()))return;
        if(!"PENDING".equals(request.status()))
            throw new IllegalStateException("Accepted ownership transfer cannot be cancelled");
        int cancelled=jdbc.update("""
                UPDATE public.iam_owner_transfer_request
                SET state='CANCELLED',decided_at=?,row_version=row_version+1
                WHERE tenant_id=? AND request_id=? AND state='PENDING'
                """,Timestamp.from(Instant.now()),tenantId,requestId);
        if(cancelled!=1)throw new IllegalStateException("Concurrent transfer cancellation");
        audit(tenantId,from,request.toId(),"OWNER_TRANSFER_CANCELLED");
    }

    private void requireEligibleSuccessor(UUID tenantId,UUID successorId) {
        // Nonlocking check for a proposed successor. Acceptance separately locks
        // the successor's account BEFORE the workspace, then rechecks eligibility.
        // Locking target after workspace here could deadlock request vs acceptance.
        String status=jdbc.query("""
                SELECT status FROM public.iam_user_account
                WHERE id=? AND external_subject IS NOT NULL
                  AND length(trim(external_subject))>0
                """,rs->rs.next()?rs.getString(1):null,successorId);
        if(!"ACTIVE".equals(status))throw new SecurityException("Successor identity inactive");
        boolean eligible=jdbc.query("""
                SELECT 1 FROM public.iam_org_membership m
                JOIN public.iam_workspace w ON w.organization_id=m.organization_id
                WHERE w.id=? AND m.user_id=? AND m.status='ACTIVE' AND m.role<>'OWNER'
                """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),
                tenantId,successorId);
        if(!eligible)throw new SecurityException("Active tenant employee required");
    }

    private void requireActiveLinked(UUID userId) {
        String status=jdbc.query("""
                SELECT status FROM public.iam_user_account
                WHERE id=? AND external_subject IS NOT NULL
                  AND length(trim(external_subject))>0 FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,userId);
        if(!"ACTIVE".equals(status))throw new SecurityException("Successor account inactive");
    }

    private Workspace lockWorkspace(UUID tenantId) {
        return jdbc.query("""
                SELECT organization_id,status FROM public.iam_workspace
                WHERE id=? AND kind='ORGANIZATION' FOR UPDATE
                """,rs->rs.next()?new Workspace(rs.getObject(1,UUID.class),rs.getString(2)):null,tenantId);
    }

    private boolean activeOwner(UUID organizationId,UUID fromId) {
        return jdbc.query("""
                SELECT 1 FROM public.iam_org_membership m
                JOIN public.iam_user_account u ON u.id=m.user_id
                WHERE m.organization_id=? AND m.user_id=? AND m.role='OWNER'
                  AND m.status='ACTIVE' AND u.status='ACTIVE'
                """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),
                organizationId,fromId);
    }

    private State currentRequest(UUID tenantId) {
        return jdbc.query("""
                SELECT request_id,from_user_id,to_user_id,state,expires_at
                FROM public.iam_owner_transfer_request WHERE tenant_id=? FOR UPDATE
                """,rs->rs.next()
                    ? new State(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getObject(3,UUID.class),rs.getString(4),rs.getTimestamp(5).toInstant())
                    : null,tenantId);
    }

    private void audit(UUID tenantId,UUID actorId,UUID targetId,String code) {
        jdbc.update("""
                INSERT INTO public.iam_tenant_rbac_event
                  (tenant_id,id,actor_user_id,action_code,target_user_id,occurred_at)
                VALUES (?,?,?,?,?,?)
                """,tenantId,UUID.randomUUID(),actorId,code,targetId,
                Timestamp.from(Instant.now()));
    }
}
