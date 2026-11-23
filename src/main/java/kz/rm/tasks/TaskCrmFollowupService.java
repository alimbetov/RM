package kz.rm.tasks;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RM-TASK-01B: scoped linkage, not a duplicate task aggregate.
 * Caller must hold both TASK_MANAGE and CRM_READ in the same tenant.
 */
@Service
public class TaskCrmFollowupService {
    public record Link(UUID tenantId, UUID taskId, UUID interactionId,
                       UUID createdBy, Instant linkedAt) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final TaskService tasks;
    private final Clock clock;

    public TaskCrmFollowupService(JdbcTemplate jdbc,TenantAuthorization authorization,
                                  TaskService tasks,Clock clock) {
        this.jdbc=jdbc;
        this.authorization=authorization;
        this.tasks=tasks;
        this.clock=clock;
    }

    /**
     * CRM row is locked before creating the task. A concurrent CRM cancellation
     * cannot commit between verifying OPEN and linking the task.
     */
    @Transactional
    public TaskService.Task create(VerifiedActor actor,UUID tenant,UUID interactionId,
                                   String approvedTitle,UUID assignee,Instant dueAt,
                                   String requestKey) {
        if(actor==null||tenant==null||interactionId==null)
            throw new IllegalArgumentException("TASK_CRM_LINK_INVALID");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.TASK_MANAGE);
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.CRM_READ);
        // A committed linked task must remain recoverable with its original
        // request key even when the CRM interaction subsequently becomes terminal.
        // TaskService.create rechecks actor authority and validates the full
        // creation fingerprint before returning any replay result.
        Link previousByKey=jdbc.query("""
            SELECT l.tenant_id,l.task_id,l.interaction_id,l.created_by,l.linked_at
            FROM crm.task_item t JOIN crm.task_crm_followup l
              ON l.tenant_id=t.tenant_id AND l.task_id=t.id
            WHERE t.tenant_id=? AND t.created_request_key=?
              AND t.created_by=?
            """,rs->rs.next()?new Link(rs.getObject(1,UUID.class),
                 rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                 rs.getObject(4,UUID.class),rs.getTimestamp(5).toInstant()):null,
            tenant,requestKey,actor.userId());
        if(previousByKey!=null) {
            if(!previousByKey.interactionId().equals(interactionId))
                throw new IllegalStateException("TASK_CRM_LINK_REPLAY_CONFLICT");
            return tasks.create(actor,tenant,approvedTitle,assignee,dueAt,requestKey);
        }
        List<UUID> locked=jdbc.query("""
            SELECT i.id FROM crm.customer_interaction i
            JOIN crm.customer c ON c.tenant_id=i.tenant_id AND c.id=i.customer_id
            WHERE i.tenant_id=? AND i.id=? AND i.status='OPEN' AND c.status='ACTIVE'
            FOR UPDATE
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,interactionId);
        if(locked.isEmpty())
            throw new IllegalStateException("TASK_CRM_INTERACTION_NOT_ACTIVE");

        // Calls another proxied service, sharing the ambient transaction.
        TaskService.Task task=tasks.create(actor,tenant,approvedTitle,assignee,dueAt,requestKey);
        Link previous=loadLink(tenant,task.id());
        if(previous!=null) {
            if(!previous.interactionId().equals(interactionId))
                throw new IllegalStateException("TASK_CRM_LINK_REPLAY_CONFLICT");
            return task;
        }
        jdbc.update("""
            INSERT INTO crm.task_crm_followup
              (tenant_id,task_id,interaction_id,created_by,linked_at)
            VALUES (?,?,?,?,?)
            """,tenant,task.id(),interactionId,actor.userId(),Timestamp.from(clock.instant()));
        return task;
    }

    @Transactional
    public Link getMine(VerifiedActor actor,UUID tenant,UUID taskId) {
        if(actor==null||tenant==null)
            throw new SecurityException("TASK_CRM_VERIFIED_ACTOR_REQUIRED");
        // If a TASK_READ principal cannot inspect the task, it cannot obtain
        // its customer reference, even if it otherwise has CRM_READ.
        tasks.getMine(actor,tenant,taskId);
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.CRM_READ);
        Link link=loadLink(tenant,taskId);
        if(link==null)throw new IllegalArgumentException("TASK_CRM_LINK_NOT_FOUND");
        return link;
    }

    private Link loadLink(UUID tenant,UUID taskId) {
        List<Link> rows=jdbc.query("""
            SELECT tenant_id,task_id,interaction_id,created_by,linked_at
            FROM crm.task_crm_followup WHERE tenant_id=? AND task_id=?
            """,(rs,n)->new Link(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
                rs.getTimestamp(5).toInstant()),tenant,taskId);
        return rows.isEmpty()?null:rows.getFirst();
    }
}
