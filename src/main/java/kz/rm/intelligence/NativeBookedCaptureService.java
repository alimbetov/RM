package kz.rm.intelligence;

import kz.rm.iam.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.*;

/** Prospective native capture opt-in. No caller-supplied start, no retrospective claim. */
@Service
public class NativeBookedCaptureService {
    public record Boundary(UUID tenant,Instant startedAt,int policyVersion){}
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final TenantAuthorization authorization;
    NativeBookedCaptureService(JdbcTemplate jdbc,PlatformTransactionManager manager,TenantAuthorization authorization){
        this.jdbc=jdbc;this.manager=manager;this.authorization=authorization;
    }
    public Boundary activate(VerifiedActor owner,UUID tenant){
        Objects.requireNonNull(owner);Objects.requireNonNull(tenant);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("S1_REQUIRES_OWN_TRANSACTION");
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(60);
        return tx.execute(st->{
            UUID user=owner.userId();
            var decision=authorization.requireForCommand(user,tenant,DomainPermission.TENANT_RBAC_MANAGE);
            if(!decision.validForCurrentTransaction()||!user.equals(decision.actorId())||!tenant.equals(decision.tenantId())||
                decision.role()!=OrganizationMembershipEntity.Role.OWNER||decision.permission()!=DomainPermission.TENANT_RBAC_MANAGE)
                throw new SecurityException("S1_OWNER_ADMISSION_REQUIRED");
            // Lock exclusively before transition. The trigger assigns the actual database clock.
            var prior=jdbc.query("SELECT started_at FROM crm.int_native_booked_capture WHERE tenant_id=? FOR UPDATE",
                rs->{if(!rs.next())throw new IllegalStateException("S1_CAPTURE_GUARD_MISSING");
                    var t=rs.getTimestamp(1);return t==null?null:t.toInstant();},tenant);
            if(prior==null)jdbc.update("UPDATE crm.int_native_booked_capture SET activated_by=? WHERE tenant_id=?",user,tenant);
            var result=read(jdbc,tenant);
            if(result==null||result.startedAt()==null)throw new IllegalStateException("S1_CAPTURE_ACTIVATION_FAILED");
            owner.userId();return result;
        });
    }
    static Boundary read(JdbcTemplate jdbc,UUID tenant){
        return jdbc.query("SELECT tenant_id,started_at,policy_version FROM crm.int_native_booked_capture WHERE tenant_id=?",
            rs->rs.next()?new Boundary(rs.getObject(1,UUID.class),rs.getTimestamp(2)==null?null:rs.getTimestamp(2).toInstant(),rs.getInt(3)):null,tenant);
    }
}
