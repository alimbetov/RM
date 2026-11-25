package kz.rm.tasks;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * RM-TASK-01B/R3 — explicit reconciliation. A closed CRM interaction never
 * silently cancels work; a manager reviews and cancels each pending task.
 * No PII or arbitrary note is persisted.
 */
@Service
public class TaskCrmReconciliationService {
    public record Pending(UUID taskId,TaskState state,UUID assigneeUserId,
                          long version) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final TaskService tasks;

    public TaskCrmReconciliationService(JdbcTemplate jdbc,
                                        TenantAuthorization authorization,
                                        TaskService tasks) {
        this.jdbc=jdbc;
        this.authorization=authorization;
        this.tasks=tasks;
    }

    private void admit(VerifiedActor actor,UUID tenant) {
        if(actor==null||tenant==null)
            throw new SecurityException("TASK_CRM_VERIFIED_TENANT_REQUIRED");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.TASK_MANAGE);
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.CRM_READ);
    }

    /**
     * Bounded, deterministic list of unfinished follow-ups for a cancelled
     * CRM source. Returned records contain only task UUID, version and state.
     */
    @Transactional
    public List<Pending> pending(VerifiedActor actor,UUID tenant,UUID interactionId,
                                 int limit) {
        admit(actor,tenant);
        if(interactionId==null||limit<1||limit>100)
            throw new IllegalArgumentException("TASK_RECONCILE_QUERY_INVALID");
        return jdbc.query("""
            SELECT t.id,t.status,t.assignee_user_id,t.row_version
            FROM crm.customer_interaction i
            JOIN crm.task_crm_followup l
              ON l.tenant_id=i.tenant_id AND l.interaction_id=i.id
            JOIN crm.task_item t
              ON t.tenant_id=l.tenant_id AND t.id=l.task_id
            WHERE i.tenant_id=? AND i.id=? AND i.status='CANCELLED'
              AND t.status IN ('OPEN','IN_PROGRESS','BLOCKED','QUEUED')
            ORDER BY t.id LIMIT ?
            """,(rs,row)->new Pending(rs.getObject(1,UUID.class),
                TaskState.valueOf(rs.getString(2)),
                rs.getObject(3,UUID.class),rs.getLong(4)),
            tenant,interactionId,limit);
    }

    /**
     * Cursor-based management review of a cancelled CRM source.
     * Cursor is last observed task UUID. Each page is tenant- and RBAC-scoped;
     * a concurrent reconciliation may remove entries from subsequent pages.
     * No automatic cancellation or retry loop is triggered by reading.
     */
    public record PendingPage(List<Pending> items, UUID nextAfterTaskId) {}

    @Transactional
    public PendingPage pendingPage(VerifiedActor actor,UUID tenant,UUID interactionId,
                                   UUID afterTaskId,int limit) {
        admit(actor,tenant);
        if(interactionId==null||limit<1||limit>100)
            throw new IllegalArgumentException("TASK_RECONCILE_QUERY_INVALID");
        String sql="""
            SELECT t.id,t.status,t.assignee_user_id,t.row_version
            FROM crm.customer_interaction i
            JOIN crm.task_crm_followup l
              ON l.tenant_id=i.tenant_id AND l.interaction_id=i.id
            JOIN crm.task_item t
              ON t.tenant_id=l.tenant_id AND t.id=l.task_id
            WHERE i.tenant_id=? AND i.id=? AND i.status='CANCELLED'
              AND t.status IN ('OPEN','IN_PROGRESS','BLOCKED','QUEUED')
            """+(afterTaskId==null?"":" AND t.id>? ")
            +" ORDER BY t.id ASC LIMIT ?";
        var params=new java.util.ArrayList<Object>();
        params.add(tenant);
        params.add(interactionId);
        if(afterTaskId!=null)params.add(afterTaskId);
        params.add(limit+1);
        List<Pending> rows=jdbc.query(sql,(rs,row)->
            new Pending(rs.getObject(1,UUID.class),TaskState.valueOf(rs.getString(2)),
                rs.getObject(3,UUID.class),rs.getLong(4)),params.toArray());
        boolean hasMore=rows.size()>limit;
        List<Pending> page=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        return new PendingPage(page,hasMore?page.getLast().taskId():null);
    }

    /**
     * Explicitly cancel one pending task. Source cancellation is checked and
     * locked by TaskService BEFORE task CAS. The caller must handle a stale
     * version by refreshing pending(); never guess a new expectedVersion.
     */
    @Transactional
    public TaskService.Task reconcileCancelled(VerifiedActor actor,UUID tenant,
                                                UUID interactionId,UUID taskId,
                                                long expectedVersion,String requestKey) {
        admit(actor,tenant);
        return tasks.cancelForSource(actor,tenant,interactionId,taskId,
                                     expectedVersion,requestKey);
    }
}
