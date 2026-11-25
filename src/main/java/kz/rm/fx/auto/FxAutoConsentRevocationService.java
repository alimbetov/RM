package kz.rm.fx.auto;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * D.4 terminal stop fence for inert PRODUCT consent.
 * Revocation requires a current OWNER but does not require currency permission,
 * a surviving original author or an unexpired consent. No price execution port.
 */
@Service
public class FxAutoConsentRevocationService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public FxAutoConsentRevocationService(JdbcTemplate jdbc,TenantAuthorization authorization) {
        this.jdbc=jdbc;this.authorization=authorization;
    }

    public record Command(UUID policyId,UUID approvalId,long expectedEpoch) {
        public Command {
            Objects.requireNonNull(policyId);Objects.requireNonNull(approvalId);
            if(expectedEpoch<0 || expectedEpoch==Long.MAX_VALUE)
                throw new IllegalArgumentException("FX_AUTO_INVALID_EPOCH");
        }
    }

    public record Receipt(UUID policyId,UUID revisionId,UUID approvalId,String state,long executionEpoch) {}
    private record State(UUID revision,UUID approval,String status,long epoch) {}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt revoke(VerifiedActor actor,UUID tenant,Command command) {
        if(actor==null || tenant==null)throw new SecurityException("FX_AUTO_VERIFIED_ACTOR_REQUIRED");
        Objects.requireNonNull(command);
        if(!"PostgreSQL".equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                c->c.getMetaData().getDatabaseProductName())) ||
                !"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation",String.class)))
            throw new IllegalStateException("FX_AUTO_POSTGRES_READ_COMMITTED_REQUIRED");
        UUID user=actor.userId();
        // Stop is a nondelegable owner safety action. Do not require an enablement grant.
        authorization.requireForCommand(user,tenant,DomainPermission.TENANT_RBAC_MANAGE);
        State current=jdbc.query("""
                SELECT policy_revision_id,approval_id,state,execution_epoch
                FROM crm.fx_auto_policy_activation WHERE tenant_id=? AND policy_id=? FOR UPDATE
                """,rs->rs.next()?new State(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getString(3),rs.getLong(4)):null,tenant,command.policyId());
        actor.userId();
        if(current==null || !current.approval().equals(command.approvalId()))
            throw new SecurityException("FX_AUTO_ACTIVATION_UNAVAILABLE");
        if("REVOKED".equals(current.status()) && current.epoch()==command.expectedEpoch()+1)
            return new Receipt(command.policyId(),current.revision(),current.approval(),"REVOKED",current.epoch());
        if(!"PAUSED".equals(current.status()) || current.epoch()!=command.expectedEpoch())
            throw new IllegalStateException("FX_AUTO_STALE_EXECUTION_EPOCH");
        int changed=jdbc.update("""
                UPDATE crm.fx_auto_policy_activation SET state='REVOKED',execution_epoch=execution_epoch+1
                WHERE tenant_id=? AND policy_id=? AND approval_id=? AND state='PAUSED' AND execution_epoch=?
                """,tenant,command.policyId(),command.approvalId(),command.expectedEpoch());
        if(changed!=1)throw new IllegalStateException("FX_AUTO_STALE_EXECUTION_EPOCH");
        jdbc.update("""
                INSERT INTO crm.fx_auto_revocation_evidence
                (tenant_id,policy_id,approval_id,actor_user_id,previous_epoch,revoked_epoch,revoked_at)
                VALUES (?,?,?,?,?,?,clock_timestamp())
                """,tenant,command.policyId(),command.approvalId(),user,command.expectedEpoch(),command.expectedEpoch()+1);
        actor.userId();
        return new Receipt(command.policyId(),current.revision(),current.approval(),"REVOKED",current.epoch()+1);
    }
}
