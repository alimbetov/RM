package kz.rm.tasks;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

        // Lock the canonical source before replay/capture: a committed receipt
        // remains replayable after a later source status change, but a new
        // capture requires the source to be CANCELLED at the lock boundary.
        List<String> source=jdbc.query("""
            SELECT status FROM crm.customer_interaction
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,n)->rs.getString(1),tenant,interactionId);
        Snapshot previous=findByKey(tenant,manager,requestKey);
        if(previous!=null) {
            if(!previous.interactionId().equals(interactionId))
                throw new IllegalStateException("TASK_SNAPSHOT_REQUEST_CONFLICT");
            return previous;
        }
        if(source.isEmpty()||!"CANCELLED".equals(source.getFirst()))
            throw new IllegalStateException("TASK_SNAPSHOT_SOURCE_NOT_CANCELLED");
        List<Item> items=jdbc.query("""
            SELECT t.id,t.status,t.row_version FROM crm.task_crm_followup l
            JOIN crm.task_item t ON t.tenant_id=l.tenant_id AND t.id=l.task_id
            WHERE l.tenant_id=? AND l.interaction_id=?
              AND t.status IN ('OPEN','IN_PROGRESS','BLOCKED','QUEUED')
            ORDER BY t.id LIMIT 10001
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
        String digest=snapshotDigest(items);
        jdbc.update("""
            INSERT INTO crm.task_reconciliation_snapshot_seal
              (tenant_id,snapshot_id,sealed_at,items_sha256)
            VALUES (?,?,?,?)
            """,tenant,id,Timestamp.from(clock.instant()),digest);
        return new Snapshot(id,tenant,interactionId,manager,now,items.size());
    }

    @Transactional
    public Page page(VerifiedActor actor,UUID tenant,UUID snapshotId,
                     UUID afterTaskId,int limit) {
        UUID manager=admit(actor,tenant);
        if(snapshotId==null||limit<1||limit>100)
            throw new IllegalArgumentException("TASK_SNAPSHOT_PAGE_INVALID");
        List<UUID> owners=jdbc.query("""
            SELECT s.created_by FROM crm.task_reconciliation_snapshot s
            JOIN crm.task_reconciliation_snapshot_seal z
              ON z.tenant_id=s.tenant_id AND z.snapshot_id=s.id
            WHERE s.tenant_id=? AND s.id=?
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

    /**
     * Recheck the committed immutable manifest against its persisted seal.
     * This is a diagnostic integrity admission, not a task-state read or
     * authorization for an automatic reconciliation command.
     */
    @Transactional(readOnly=true)
    public boolean verifyIntegrity(VerifiedActor actor, UUID tenant, UUID snapshotId) {
        UUID manager=admit(actor,tenant);
        if(snapshotId==null)throw new IllegalArgumentException("TASK_SNAPSHOT_PAGE_INVALID");
        List<int[]> counts=jdbc.query("""
            SELECT s.item_count FROM crm.task_reconciliation_snapshot s
            WHERE s.tenant_id=? AND s.id=? AND s.created_by=?
            """,(rs,n)->new int[]{rs.getInt(1)},tenant,snapshotId,manager);
        if(counts.isEmpty())throw new SecurityException("TASK_SNAPSHOT_NOT_ACCESSIBLE");
        List<String> seals=jdbc.query("""
            SELECT items_sha256 FROM crm.task_reconciliation_snapshot_seal
            WHERE tenant_id=? AND snapshot_id=?
            """,(rs,n)->rs.getString(1),tenant,snapshotId);
        if(seals.size()!=1)return false;
        List<Item> persisted=jdbc.query("""
            SELECT task_id,captured_state,captured_version
            FROM crm.task_reconciliation_snapshot_item
            WHERE tenant_id=? AND snapshot_id=? ORDER BY task_id
            """,(rs,n)->new Item(rs.getObject(1,UUID.class),
                TaskState.valueOf(rs.getString(2)),rs.getLong(3)),tenant,snapshotId);
        return persisted.size()==counts.getFirst()[0]
            && snapshotDigest(persisted).equals(seals.getFirst());
    }

    private static String snapshotDigest(List<Item> items) {
        StringBuilder canonical=new StringBuilder();
        for(Item item:items)
            canonical.append(item.taskId()).append('|')
                .append(item.capturedState()).append('|')
                .append(item.capturedVersion()).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Snapshot findByKey(UUID tenant,UUID manager,String requestKey) {
        List<Snapshot> rows=jdbc.query("""
            SELECT s.id,s.tenant_id,s.interaction_id,s.created_by,s.captured_at,s.item_count
            FROM crm.task_reconciliation_snapshot s
            JOIN crm.task_reconciliation_snapshot_seal z
              ON z.tenant_id=s.tenant_id AND z.snapshot_id=s.id
            WHERE s.tenant_id=? AND s.created_by=? AND s.request_key=?
            """,(rs,n)->new Snapshot(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getTimestamp(5).toInstant(),
                rs.getInt(6)),tenant,manager,requestKey);
        return rows.isEmpty()?null:rows.getFirst();
    }
}
