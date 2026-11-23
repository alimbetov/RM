package kz.rm.tasks;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * RM-TASK-01A: tenant-owned employee task lifecycle, service-only.
 * No customer PII, automated scheduling, CRM linkage or notifications.
 */
@Service
public class TaskService {
    public record Task(UUID tenantId, UUID id, String title, UUID assigneeUserId,
                       UUID createdBy, TaskState state, Instant dueAt, long version) {}
    public record Event(UUID id, UUID taskId, UUID actorUserId, String action,
                        TaskState from, TaskState to, Long previousVersion,
                        long newVersion, Instant occurredAt,
                        UUID previousAssigneeUserId, UUID nextAssigneeUserId) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final Clock clock;
    private String assigneeAccountLockSql;

    public TaskService(JdbcTemplate jdbc, TenantAuthorization authorization, Clock clock) {
        this.jdbc=jdbc;
        this.authorization=authorization;
        this.clock=clock;
    }

    @PostConstruct
    void initializeAssigneeLock() {
        String database=jdbc.execute((ConnectionCallback<String>) c->
            c.getMetaData().getDatabaseProductName());
        String lock=database!=null && database.toLowerCase(java.util.Locale.ROOT)
            .contains("postgresql")?"FOR SHARE":"FOR UPDATE";
        assigneeAccountLockSql="SELECT status FROM public.iam_user_account WHERE id=? "+lock;
    }

    private UUID admit(VerifiedActor actor, UUID tenant, DomainPermission permission) {
        if(actor==null || tenant==null)throw new SecurityException("TASK_VERIFIED_TENANT_REQUIRED");
        authorization.requireForCommand(actor.userId(),tenant,permission);
        return actor.userId();
    }

    @Transactional
    public Task create(VerifiedActor actor, UUID tenant, String title, UUID assignee,
                       Instant dueAt, String requestKey) {
        UUID creator=admit(actor,tenant,DomainPermission.TASK_MANAGE);
        if(title==null || title.isBlank() || title.length()>180
                || title.chars().anyMatch(c->Character.isISOControl((char)c))
                || assignee==null || dueAt==null
                || requestKey==null || !requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("TASK_CREATE_INVALID");
        String cleanTitle=TaskTitlePolicy.requireApproved(title);
        String fingerprint=sha(tenant+"|"+cleanTitle+"|"+assignee+"|"+dueAt);
        lockRequestStripe(requestKey);
        record Existing(UUID id,String fingerprint,UUID creator) {}
        Existing old=jdbc.query("""
            SELECT id,request_sha256,created_by FROM crm.task_item
            WHERE tenant_id=? AND created_request_key=?
            """,rs->rs.next()?new Existing(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getObject(3,UUID.class)):null,tenant,requestKey);
        if(old!=null) {
            if(!old.fingerprint().equals(fingerprint)||!creator.equals(old.creator()))
                throw new IllegalStateException("TASK_REQUEST_REPLAY_CONFLICT");
            return load(tenant,old.id());
        }
        requireMember(tenant,assignee);
        Instant now=clock.instant();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO crm.task_item
              (tenant_id,id,assignee_user_id,status,due_at,created_at,row_version,
               title,created_by,created_request_key,request_sha256,updated_at)
            VALUES (?,?,?,'OPEN',?,?,0,?,?,?,?,?)
            """,tenant,id,assignee,Timestamp.from(dueAt),Timestamp.from(now),
            cleanTitle,creator,requestKey,fingerprint,Timestamp.from(now));
        event(tenant,id,creator,"CREATED",null,TaskState.OPEN,null,0L,null,assignee);
        return load(tenant,id);
    }

    @Transactional
    public Task assign(VerifiedActor actor, UUID tenant, UUID id, UUID assignee,
                       long expectedVersion) {
        return assign(actor,tenant,id,assignee,expectedVersion,null);
    }

    @Transactional
    public Task assign(VerifiedActor actor, UUID tenant, UUID id, UUID assignee,
                       long expectedVersion,String requestKey) {
        UUID manager=admit(actor,tenant,DomainPermission.TASK_MANAGE);
        if(assignee==null)throw new IllegalArgumentException("TASK_ASSIGNEE_REQUIRED");
        if(expectedVersion<0)throw new IllegalArgumentException("TASK_VERSION_INVALID");
        String fingerprint=requestKey==null?null:
            sha(tenant+"|"+id+"|ASSIGNED|"+assignee+"|"+expectedVersion);
        if(requestKey!=null) {
            lockRequestStripe(requestKey);
            Task replay=loadReplayed(tenant,manager,id,requestKey,fingerprint);
            if(replay!=null)return replay;
        }
        // Lock target IAM actor before task row, retaining the established lock order.
        requireMember(tenant,assignee);
        Task before=lockTask(tenant,id,expectedVersion);
        if(before.state()!=TaskState.OPEN && before.state()!=TaskState.BLOCKED)
            throw new IllegalStateException("TASK_REASSIGN_STATE_INVALID");
        if(java.util.Objects.equals(before.assigneeUserId(),assignee))return before;
        update(tenant,id,expectedVersion,"assignee_user_id",assignee);
        event(tenant,id,manager,"ASSIGNED",before.state(),before.state(),
                expectedVersion,expectedVersion+1,before.assigneeUserId(),assignee,
                requestKey,fingerprint);
        return load(tenant,id);
    }

    @Transactional
    public Task start(VerifiedActor actor,UUID tenant,UUID id,long version) {
        return start(actor,tenant,id,version,null);
    }

    @Transactional
    public Task start(VerifiedActor actor,UUID tenant,UUID id,long version,String requestKey) {
        return move(actor,tenant,id,version,TaskState.OPEN,TaskState.IN_PROGRESS,"STARTED",requestKey);
    }

    @Transactional
    public Task block(VerifiedActor actor,UUID tenant,UUID id,long version) {
        return block(actor,tenant,id,version,null);
    }

    @Transactional
    public Task block(VerifiedActor actor,UUID tenant,UUID id,long version,String requestKey) {
        return move(actor,tenant,id,version,TaskState.IN_PROGRESS,TaskState.BLOCKED,"BLOCKED",requestKey);
    }

    @Transactional
    public Task resume(VerifiedActor actor,UUID tenant,UUID id,long version) {
        return resume(actor,tenant,id,version,null);
    }

    @Transactional
    public Task resume(VerifiedActor actor,UUID tenant,UUID id,long version,String requestKey) {
        return move(actor,tenant,id,version,TaskState.BLOCKED,TaskState.IN_PROGRESS,"RESUMED",requestKey);
    }

    @Transactional
    public Task complete(VerifiedActor actor,UUID tenant,UUID id,long version) {
        return complete(actor,tenant,id,version,null);
    }

    @Transactional
    public Task complete(VerifiedActor actor,UUID tenant,UUID id,long version,String requestKey) {
        return move(actor,tenant,id,version,TaskState.IN_PROGRESS,TaskState.COMPLETED,"COMPLETED",requestKey);
    }

    @Transactional
    public Task archive(VerifiedActor actor,UUID tenant,UUID id,long expectedVersion) {
        return archive(actor,tenant,id,expectedVersion,null);
    }

    @Transactional
    public Task archive(VerifiedActor actor,UUID tenant,UUID id,long expectedVersion,
                        String requestKey) {
        UUID manager=admit(actor,tenant,DomainPermission.TASK_MANAGE);
        if(expectedVersion<0)throw new IllegalArgumentException("TASK_VERSION_INVALID");
        String fingerprint=requestKey==null?null:
            sha(tenant+"|"+id+"|ARCHIVED|"+expectedVersion);
        if(requestKey!=null) {
            lockRequestStripe(requestKey);
            Task replay=loadReplayed(tenant,manager,id,requestKey,fingerprint);
            if(replay!=null)return replay;
        }
        Task before=lockTask(tenant,id,expectedVersion);
        if(before.state()!=TaskState.COMPLETED)
            throw new IllegalStateException("TASK_ARCHIVE_REQUIRES_COMPLETED");
        update(tenant,id,expectedVersion,"status",TaskState.ARCHIVED.name());
        event(tenant,id,manager,"ARCHIVED",before.state(),TaskState.ARCHIVED,
                expectedVersion,expectedVersion+1,before.assigneeUserId(),before.assigneeUserId(),
                requestKey,fingerprint);
        return load(tenant,id);
    }

    private record CommandReceipt(UUID taskId,String fingerprint,long version) {}

    private Task move(VerifiedActor actor,UUID tenant,UUID id,long version,
                      TaskState from,TaskState to,String action,String requestKey) {
        UUID employee=admit(actor,tenant,DomainPermission.TASK_EXECUTE);
        if(version<0)throw new IllegalArgumentException("TASK_VERSION_INVALID");
        if(requestKey!=null)lockRequestStripe(requestKey);
        String fingerprint=requestKey==null?null:
            sha(tenant+"|"+id+"|"+action+"|"+version+"|"+from+"|"+to);
        // Lock canonical task before looking up the receipt. Concurrent same-task
        // retries therefore observe the first transaction's committed event.
        Task before=lockTaskCurrent(tenant,id);
        if(requestKey!=null) {
            Task replay=loadReplayed(tenant,employee,id,requestKey,fingerprint);
            if(replay!=null)return replay;
        }
        if(before.version()!=version)throw new IllegalStateException("TASK_STALE_VERSION");
        if(!employee.equals(before.assigneeUserId()))
            throw new SecurityException("TASK_ASSIGNEE_ONLY");
        if(before.state()!=from)
            throw new IllegalStateException("TASK_TRANSITION_INVALID");
        update(tenant,id,version,"status",to.name());
        event(tenant,id,employee,action,from,to,version,version+1,
            before.assigneeUserId(),before.assigneeUserId(),requestKey,fingerprint);
        return load(tenant,id);
    }

    @Transactional
    public Task getMine(VerifiedActor actor,UUID tenant,UUID id) {
        UUID user=admit(actor,tenant,DomainPermission.TASK_READ);
        Task task=load(tenant,id);
        if(!user.equals(task.assigneeUserId())&&!user.equals(task.createdBy()))
            throw new SecurityException("TASK_NOT_ACCESSIBLE");
        return task;
    }

    @Transactional
    public List<Task> listMine(VerifiedActor actor,UUID tenant,TaskState state,
                               int limit,int offset) {
        UUID user=admit(actor,tenant,DomainPermission.TASK_READ);
        if(limit<1||limit>100||offset<0||offset>100000)
            throw new IllegalArgumentException("TASK_PAGE_INVALID");
        String sql="""
            SELECT tenant_id,id,title,assignee_user_id,created_by,status,due_at,row_version
            FROM crm.task_item
            WHERE tenant_id=? AND (assignee_user_id=? OR created_by=?)
            """+(state==null?"":" AND status=? ")
            +" ORDER BY due_at ASC,id ASC LIMIT ? OFFSET ?";
        var args=new ArrayList<Object>(List.of(tenant,user,user));
        if(state!=null)args.add(state.name());
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql,(rs,n)->map(rs),args.toArray());
    }

    @Transactional
    public List<Event> historyMine(VerifiedActor actor,UUID tenant,UUID id,int limit) {
        getMine(actor,tenant,id);
        if(limit<1||limit>100)throw new IllegalArgumentException("TASK_HISTORY_LIMIT_INVALID");
        return jdbc.query("""
            SELECT id,task_id,actor_user_id,action_code,from_state,to_state,
                   previous_version,new_version,occurred_at,
                   previous_assignee_user_id,next_assignee_user_id
            FROM crm.task_event WHERE tenant_id=? AND task_id=?
            ORDER BY new_version DESC LIMIT ?
            """,(rs,n)->new Event(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getString(4),
                rs.getString(5)==null?null:TaskState.valueOf(rs.getString(5)),
                TaskState.valueOf(rs.getString(6)),
                rs.getObject(7)==null?null:rs.getLong(7),rs.getLong(8),
                rs.getTimestamp(9).toInstant(),
                rs.getObject(10,UUID.class),rs.getObject(11,UUID.class)),tenant,id,limit);
    }

    private void lockRequestStripe(String requestKey) {
        if(requestKey==null||!requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("TASK_REQUEST_KEY_INVALID");
        int bucket=Math.floorMod(Arrays.hashCode(
            sha(requestKey).getBytes(StandardCharsets.UTF_8)),32);
        Integer locked=jdbc.queryForObject("""
            SELECT bucket FROM crm.task_request_lock WHERE bucket=? FOR UPDATE
            """,Integer.class,bucket);
        if(locked==null||locked!=bucket)
            throw new IllegalStateException("TASK_REQUEST_LOCK_MISSING");
    }

    private Task loadReplayed(UUID tenant,UUID actor,UUID taskId,
                              String requestKey,String fingerprint) {
        CommandReceipt receipt=jdbc.query("""
            SELECT task_id,request_sha256,new_version FROM crm.task_event
            WHERE tenant_id=? AND actor_user_id=? AND request_key=?
            """,rs->rs.next()?new CommandReceipt(rs.getObject(1,UUID.class),
                rs.getString(2),rs.getLong(3)):null,tenant,actor,requestKey);
        if(receipt==null)return null;
        if(!taskId.equals(receipt.taskId())||!fingerprint.equals(receipt.fingerprint()))
            throw new IllegalStateException("TASK_REQUEST_REPLAY_CONFLICT");
        Task current=load(tenant,taskId);
        if(current.version()!=receipt.version())
            throw new IllegalStateException("TASK_RECEIPT_SUPERSEDED");
        return current;
    }

    private Task lockTask(UUID tenant,UUID id,long version) {
        if(version<0)throw new IllegalArgumentException("TASK_VERSION_INVALID");
        Task task=lockTaskCurrent(tenant,id);
        if(task.version()!=version)throw new IllegalStateException("TASK_STALE_VERSION");
        return task;
    }

    private Task lockTaskCurrent(UUID tenant,UUID id) {
        if(id==null)throw new IllegalArgumentException("TASK_ID_REQUIRED");
        List<UUID> ids=jdbc.query("""
            SELECT id FROM crm.task_item WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,id);
        if(ids.isEmpty())throw new SecurityException("TASK_NOT_ACCESSIBLE");
        return load(tenant,id);
    }

    private Task load(UUID tenant,UUID id) {
        if(id==null)throw new IllegalArgumentException("TASK_ID_REQUIRED");
        var tasks=jdbc.query("""
            SELECT tenant_id,id,title,assignee_user_id,created_by,status,due_at,row_version
            FROM crm.task_item WHERE tenant_id=? AND id=?
            """,(rs,n)->map(rs),tenant,id);
        if(tasks.isEmpty())throw new SecurityException("TASK_NOT_ACCESSIBLE");
        return tasks.getFirst();
    }

    private static Task map(java.sql.ResultSet rs)throws java.sql.SQLException {
        Timestamp due=rs.getTimestamp("due_at");
        return new Task(rs.getObject("tenant_id",UUID.class),rs.getObject("id",UUID.class),
                rs.getString("title"),rs.getObject("assignee_user_id",UUID.class),
                rs.getObject("created_by",UUID.class),
                TaskState.valueOf(rs.getString("status")),
                due==null?null:due.toInstant(),rs.getLong("row_version"));
    }

    private void requireMember(UUID tenant,UUID user) {
        // An account suspension UPDATE must wait until this command commits.
        String accountStatus=jdbc.query(assigneeAccountLockSql,rs->
            rs.next()?rs.getString(1):null,user);
        if(!"ACTIVE".equals(accountStatus))
            throw new SecurityException("TASK_ASSIGNEE_MUST_BE_ACTIVE_MEMBER");
        String state=jdbc.query("""
            SELECT m.status FROM public.iam_workspace w
            JOIN public.iam_org_membership m
              ON m.organization_id=w.organization_id AND m.user_id=?
            JOIN public.iam_user_account a
              ON a.id=m.user_id AND a.status='ACTIVE'
            WHERE w.id=? AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
            """,rs->rs.next()?rs.getString(1):null,user,tenant);
        if(!"ACTIVE".equals(state))
            throw new SecurityException("TASK_ASSIGNEE_MUST_BE_ACTIVE_MEMBER");
    }

    private void update(UUID tenant,UUID id,long version,String column,Object value) {
        if(!List.of("status","assignee_user_id").contains(column))
            throw new IllegalArgumentException("TASK_UPDATE_FIELD_INVALID");
        int n=jdbc.update("UPDATE crm.task_item SET "+column+
            "=?,row_version=row_version+1,updated_at=? WHERE tenant_id=? AND id=? AND row_version=?",
            value,Timestamp.from(clock.instant()),tenant,id,version);
        if(n!=1)throw new IllegalStateException("TASK_CONCURRENT_MODIFICATION");
    }

    private void event(UUID tenant,UUID id,UUID user,String action,
                       TaskState before,TaskState after,Long version,long next,
                       UUID previousAssignee,UUID nextAssignee) {
        event(tenant,id,user,action,before,after,version,next,
              previousAssignee,nextAssignee,null,null);
    }

    private void event(UUID tenant,UUID id,UUID user,String action,
                       TaskState before,TaskState after,Long version,long next,
                       UUID previousAssignee,UUID nextAssignee,
                       String requestKey,String fingerprint) {
        jdbc.update("""
            INSERT INTO crm.task_event
              (tenant_id,id,task_id,actor_user_id,action_code,from_state,to_state,
               previous_version,new_version,occurred_at,
               previous_assignee_user_id,next_assignee_user_id,request_key,request_sha256)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,tenant,UUID.randomUUID(),id,user,action,
            before==null?null:before.name(),after.name(),version,next,
            Timestamp.from(clock.instant()),previousAssignee,nextAssignee,
            requestKey,fingerprint);
    }

    private static String sha(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
