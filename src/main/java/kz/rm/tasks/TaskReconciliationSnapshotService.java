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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Frozen enumeration of tasks requiring review after CRM source cancellation.
 * Captured state/version is evidence, NOT permission to mutate: TaskService
 * must recheck current IAM, CRM source and row_version for each decision.
 */
@Service
public class TaskReconciliationSnapshotService {
    public record Snapshot(UUID id,UUID tenantId,UUID interactionId,
                           UUID createdBy,Instant capturedAt,int itemCount) {}
    public record Item(UUID taskId,TaskState capturedState,long capturedVersion) {}
    public record Page(List<Item> items,UUID nextAfterTaskId) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final Clock clock;

    public TaskReconciliationSnapshotService(JdbcTemplate jdbc,
                                             TenantAuthorization authorization,Clock clock) {
        this.jdbc=jdbc;
        this.authorization=authorization;
        this.clock=clock;
    }

    private UUID admit(VerifiedActor actor,UUID tenant) {
        if(actor==null||tenant==null)
            throw new SecurityException("TASK_SNAPSHOT_VERIFIED_TENANT_REQUIRED");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.TASK_MANAGE);
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.CRM_READ);
        return actor.userId();
    }

    @Transactional
    public Snapshot capture(VerifiedActor actor,UUID tenant,UUID interactionId,
                            String requestKey) {
        UUID manager=admit(actor,tenant);
        if(interactionId==null||requestKey==null
                ||!requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("TASK_SNAPSHOT_REQUEST_INVALID");

        // Serializes same-source captures and CRM status changes on PostgreSQL.
        List<UUID> source=jdbc.query("""
            SELECT id FROM crm.customer_interaction
            WHERE tenant_id=? AND id=? AND status='CANCELLED' FOR UPDATE
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,interactionId);
        if(source.isEmpty())
            throw new IllegalStateException("TASK_SNAPSHOT_SOURCE_NOT_CANCELLED");
        Snapshot previous=findByKey(tenant,manager,requestKey);
        if(previous!=null) {
            if(!previous.interactionId().equals(interactionId))
                throw new IllegalStateException("TASK_SNAPSHOT_REQUEST_CONFLICT");
            return previous;
        }
        List<Item> items=jdbc.query("""
            SELECT t.id,t.status,t.row_version FROM crm.task_crm_followup l
            JOIN crm.task_item t ON t.tenant_id=l.tenant_id AND t.id=l.task_id
            WHERE l.tenant_id=? AND l.interaction_id=?
              AND t.status IN ('OPEN','IN_PROGRESS','BLOCKED','QUEUED')
            ORDER BY t.id
            """,(rs,n)->new Item(rs.getObject(1,UUID.class),
                TaskState.valueOf(rs.getString(2)),rs.getLong(3)),tenant,interactionId);
        if(items.size()>10000)throw new IllegalStateException("TASK_SNAPSHOT_CAPACITY_EXCEEDED");
        UUID id=UUID.randomUUID();
        Instant now=clock.instant();
        jdbc.update("""
            INSERT INTO crm.task_reconciliation_snapshot
            (tenant_id,id,interaction_id,created_by,request_key,captured_at,item_count)
            VALUES (?,?,?,?,?,?,?)
            """,tenant,id,interactionId,manager,requestKey,Timestamp.from(now),items.size());
        if(!items.isEmpty()) {
            List<Object[]> params=new ArrayList<>();
            for(Item item:items)
                params.add(new Object[]{tenant,id,item.taskId(),
                    item.capturedState().name(),item.capturedVersion()});
            jdbc.batchUpdate("""
                INSERT INTO crm.task_reconciliation_snapshot_item
                (tenant_id,snapshot_id,task_id,captured_state,captured_version)
                VALUES (?,?,?,?,?)
                """,params);
        }
        return new Snapshot(id,tenant,interactionId,manager,now,items.size());
    }

    @Transactional
    public Page page(VerifiedActor actor,UUID tenant,UUID snapshotId,
                     UUID afterTaskId,int limit) {
        UUID manager=admit(actor,tenant);
        if(snapshotId==null||limit<1||limit>100)
            throw new IllegalArgumentException("TASK_SNAPSHOT_PAGE_INVALID");
        List<UUID> owners=jdbc.query("""
            SELECT created_by FROM crm.task_reconciliation_snapshot
            WHERE tenant_id=? AND id=?
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,snapshotId);
        if(owners.isEmpty()||!manager.equals(owners.getFirst()))
            throw new SecurityException("TASK_SNAPSHOT_NOT_ACCESSIBLE");
        String sql="""
            SELECT task_id,captured_state,captured_version
            FROM crm.task_reconciliation_snapshot_item
            WHERE tenant_id=? AND snapshot_id=?
            """+(afterTaskId==null?"":" AND task_id>? ")
            +" ORDER BY task_id ASC LIMIT ?";
        var args=new ArrayList<Object>();
        args.add(tenant);args.add(snapshotId);
        if(afterTaskId!=null)args.add(afterTaskId);
        args.add(limit+1);
        List<Item> result=jdbc.query(sql,(rs,n)->new Item(
            rs.getObject(1,UUID.class),TaskState.valueOf(rs.getString(2)),
            rs.getLong(3)),args.toArray());
        boolean hasMore=result.size()>limit;
        List<Item> page=List.copyOf(result.subList(0,Math.min(limit,result.size())));
        return new Page(page,hasMore?page.getLast().taskId():null);
    }

    private Snapshot findByKey(UUID tenant,UUID manager,String requestKey) {
        List<Snapshot> rows=jdbc.query("""
            SELECT id,tenant_id,interaction_id,created_by,captured_at,item_count
            FROM crm.task_reconciliation_snapshot
            WHERE tenant_id=? AND created_by=? AND request_key=?
            """,(rs,n)->new Snapshot(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getTimestamp(5).toInstant(),
                rs.getInt(6)),tenant,manager,requestKey);
        return rows.isEmpty()?null:rows.getFirst();
    }
}
