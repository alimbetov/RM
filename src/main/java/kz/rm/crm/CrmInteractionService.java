package kz.rm.crm;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Service-only CRM interaction lifecycle, without unprotected free-text or PII.
 * A contact reference must belong to the same customer AND the same tenant.
 */
@Service
public class CrmInteractionService {
    public enum Kind { CALL, MEETING, EMAIL, NOTE }
    public enum Status { OPEN, COMPLETED, CANCELLED }
    public record Interaction(UUID tenantId, UUID id, UUID customerId, UUID contactId,
                              Kind kind, Status status, UUID responsibleUserId,
                              Instant occurredAt, Instant nextActionAt, long rowVersion) {}
    public record Event(UUID id, UUID interactionId, UUID actorId, String action,
                        Long previousVersion, long newVersion, Instant occurredAt) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final CrmCancellationReviewIntentPort cancellationIntentPort;

    public CrmInteractionService(JdbcTemplate jdbc, TenantAuthorization authorization,
                                 CrmCancellationReviewIntentPort cancellationIntentPort) {
        this.jdbc=jdbc;
        this.authorization=authorization;
        this.cancellationIntentPort=cancellationIntentPort;
    }

    private UUID admit(VerifiedActor actor, UUID tenant, DomainPermission permission) {
        if(actor==null || tenant==null) throw new SecurityException("Verified actor and tenant required");
        authorization.requireForCommand(actor.userId(),tenant,permission);
        return actor.userId();
    }

    @Transactional
    public Interaction record(VerifiedActor actor, UUID tenant, UUID customer, UUID contact,
                              Kind kind, UUID responsible, Instant occurredAt,
                              Instant nextActionAt, String requestKey) {
        UUID actorId=admit(actor,tenant,DomainPermission.CRM_WRITE);
        if(customer==null || kind==null || occurredAt==null || requestKey==null
                || !requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("Invalid interaction intent");
        byte[] fingerprint=digest(tenant+"|"+customer+"|"+contact+"|"+kind.name()+"|"
                +responsible+"|"+occurredAt+"|"+nextActionAt);
        int bucket=Math.floorMod(Arrays.hashCode(digest(requestKey)),32);
        Integer locked=jdbc.queryForObject(
                "SELECT bucket FROM crm.interaction_request_lock WHERE bucket=? FOR UPDATE",
                Integer.class,bucket);
        if(locked==null || locked!=bucket)
            throw new IllegalStateException("Interaction request fence unavailable");
        var old=jdbc.query("""
                SELECT id,request_sha256 FROM crm.customer_interaction
                WHERE tenant_id=? AND request_key=?
                """,rs->rs.next()
                ?new Receipt(rs.getObject(1,UUID.class),rs.getBytes(2)):null,
                tenant,requestKey);
        if(old!=null){
            if(!MessageDigest.isEqual(fingerprint,old.fingerprint()))
                throw new IllegalArgumentException("Interaction request key reused with different payload");
            return load(tenant,old.id());
        }
        requireActiveCustomer(tenant,customer);
        requireContact(tenant,customer,contact);
        requireActiveMember(tenant,responsible);
        UUID id=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO crm.customer_interaction
                  (tenant_id,id,customer_id,contact_id,kind,status,responsible_user_id,
                   occurred_at,next_action_at,request_key,request_sha256,created_by,
                   created_at,updated_at,row_version)
                VALUES (?,?,?,?,?,'OPEN',?,?,?,?,?,?,?,?,0)
                """,tenant,id,customer,contact,kind.name(),responsible,
                Timestamp.from(occurredAt),
                nextActionAt==null?null:Timestamp.from(nextActionAt),
                requestKey,fingerprint,actorId,now,now);
        event(tenant,id,actorId,"CREATED",null,0L);
        return load(tenant,id);
    }

    @Transactional
    public Interaction get(VerifiedActor actor, UUID tenant, UUID id) {
        admit(actor,tenant,DomainPermission.CRM_READ);
        return load(tenant,id);
    }

    @Transactional
    public Page<Interaction> search(VerifiedActor actor, UUID tenant, UUID customer,
                                    UUID responsible, Status status, int page, int size) {
        admit(actor,tenant,DomainPermission.CRM_READ);
        if(page<0 || size<1 || size>100)
            throw new IllegalArgumentException("Page must be nonnegative, size 1..100");
        StringBuilder where=new StringBuilder(" WHERE tenant_id=?");
        var args=new ArrayList<Object>(List.of(tenant));
        if(customer!=null){where.append(" AND customer_id=?");args.add(customer);}
        if(responsible!=null){where.append(" AND responsible_user_id=?");args.add(responsible);}
        if(status!=null){where.append(" AND status=?");args.add(status.name());}
        Long count=jdbc.queryForObject(
                "SELECT count(*) FROM crm.customer_interaction"+where,Long.class,args.toArray());
        var pageArgs=new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long)page*size);
        List<Interaction> list=jdbc.query("""
                SELECT tenant_id,id,customer_id,contact_id,kind,status,responsible_user_id,
                       occurred_at,next_action_at,row_version
                FROM crm.customer_interaction
                """+where+"""
                 ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?
                """,(rs,row)->map(rs),pageArgs.toArray());
        return new PageImpl<>(list,PageRequest.of(page,size),count==null?0L:count);
    }

    @Transactional
    public Interaction assignResponsible(VerifiedActor actor, UUID tenant, UUID id,
                                         UUID responsible, long expectedVersion) {
        if(responsible==null) throw new IllegalArgumentException("Responsible required");
        return transition(actor,tenant,id,expectedVersion,"RESPONSIBLE_ASSIGNED",responsible,null);
    }

    @Transactional
    public Interaction setNextAction(VerifiedActor actor, UUID tenant, UUID id,
                                     Instant nextActionAt, long expectedVersion) {
        if(nextActionAt==null) throw new IllegalArgumentException("Next action time required");
        return transition(actor,tenant,id,expectedVersion,"NEXT_ACTION_SET",null,nextActionAt);
    }

    @Transactional
    public Interaction complete(VerifiedActor actor, UUID tenant, UUID id,long expectedVersion) {
        return transition(actor,tenant,id,expectedVersion,"COMPLETED",null,null);
    }

    @Transactional
    public Interaction cancel(VerifiedActor actor, UUID tenant, UUID id,long expectedVersion) {
        return transition(actor,tenant,id,expectedVersion,"CANCELLED",null,null);
    }

    @Transactional
    public List<Event> history(VerifiedActor actor, UUID tenant, UUID id,int limit) {
        admit(actor,tenant,DomainPermission.CRM_READ);
        if(id==null || limit<1 || limit>100) throw new IllegalArgumentException("Invalid history request");
        return jdbc.query("""
                SELECT id,interaction_id,actor_id,action_code,previous_version,new_version,occurred_at
                FROM crm.customer_interaction_event
                WHERE tenant_id=? AND interaction_id=?
                ORDER BY occurred_at DESC,id DESC LIMIT ?
                """,(rs,row)->new Event(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                  rs.getObject(3,UUID.class),rs.getString(4),
                  rs.getObject(5)==null?null:rs.getLong(5),rs.getLong(6),
                  rs.getTimestamp(7).toInstant()),tenant,id,limit);
    }

    private Interaction transition(VerifiedActor actor, UUID tenant, UUID id,long expectedVersion,
                                   String action, UUID responsible, Instant nextAction) {
        UUID actorId=admit(actor,tenant,DomainPermission.CRM_WRITE);
        if(id==null || expectedVersion<0) throw new IllegalArgumentException("Interaction/version required");
        var rows=jdbc.query("""
                SELECT id FROM crm.customer_interaction
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,row)->rs.getObject(1,UUID.class),tenant,id);
        if(rows.isEmpty()) throw new IllegalArgumentException("Interaction not found in tenant");
        Interaction existing=load(tenant,id);
        if(existing.rowVersion()!=expectedVersion)
            throw new IllegalStateException("Stale interaction version");
        if(existing.status()!=Status.OPEN)
            throw new IllegalStateException("Interaction is terminal");
        if("RESPONSIBLE_ASSIGNED".equals(action)||"NEXT_ACTION_SET".equals(action))
            requireActiveCustomer(tenant,existing.customerId());
        String field;
        Object value;
        if("RESPONSIBLE_ASSIGNED".equals(action)){
            requireActiveMember(tenant,responsible);
            field="responsible_user_id";value=responsible;
        } else if("NEXT_ACTION_SET".equals(action)){
            field="next_action_at";value=Timestamp.from(nextAction);
        } else {
            field="status";value=action;
        }
        // Column is a closed internal allowlist, never client-provided SQL.
        int updated=jdbc.update("UPDATE crm.customer_interaction SET "+field+
                "=?,row_version=row_version+1,updated_at=? WHERE tenant_id=? AND id=? AND row_version=?",
                value,Timestamp.from(Instant.now()),tenant,id,expectedVersion);
        if(updated!=1) throw new IllegalStateException("Concurrent interaction update");
        CrmCancellationEventRef cancellationEvent =
                event(tenant,id,actorId,action,expectedVersion,expectedVersion+1);
        if(cancellationEvent!=null) cancellationIntentPort.onCancelled(cancellationEvent);
        return load(tenant,id);
    }

    private Interaction load(UUID tenant, UUID id) {
        if(id==null) throw new IllegalArgumentException("Interaction ID required");
        var list=jdbc.query("""
                SELECT tenant_id,id,customer_id,contact_id,kind,status,responsible_user_id,
                       occurred_at,next_action_at,row_version
                FROM crm.customer_interaction WHERE tenant_id=? AND id=?
                """,(rs,row)->map(rs),tenant,id);
        if(list.isEmpty()) throw new IllegalArgumentException("Interaction not found in tenant");
        return list.getFirst();
    }

    private static Interaction map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp due=rs.getTimestamp("next_action_at");
        return new Interaction(rs.getObject("tenant_id",UUID.class),
                rs.getObject("id",UUID.class),rs.getObject("customer_id",UUID.class),
                rs.getObject("contact_id",UUID.class),Kind.valueOf(rs.getString("kind")),
                Status.valueOf(rs.getString("status")),
                rs.getObject("responsible_user_id",UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                due==null?null:due.toInstant(),rs.getLong("row_version"));
    }

    private void requireActiveCustomer(UUID tenant, UUID customer) {
        String status=jdbc.query("""
                SELECT status FROM crm.customer WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant,customer);
        if(!"ACTIVE".equals(status))
            throw new IllegalArgumentException("Active customer required in tenant");
    }

    private void requireContact(UUID tenant,UUID customer,UUID contact) {
        if(contact==null) return;
        String status=jdbc.query("""
                SELECT status FROM crm.contact
                WHERE tenant_id=? AND customer_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant,customer,contact);
        if(!"ACTIVE".equals(status))
            throw new IllegalArgumentException("Active contact owned by customer required");
    }

    private void requireActiveMember(UUID tenant, UUID user) {
        if(user==null)return;
        String status=jdbc.query("""
                SELECT m.status FROM public.iam_workspace w
                JOIN public.iam_org_membership m
                  ON m.organization_id=w.organization_id AND m.user_id=?
                JOIN public.iam_user_account a
                  ON a.id=m.user_id AND a.status='ACTIVE'
                WHERE w.id=? AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                """,rs->rs.next()?rs.getString(1):null,user,tenant);
        if(!"ACTIVE".equals(status))
            throw new SecurityException("Responsible must be active member of selected tenant");
    }

    private CrmCancellationEventRef event(UUID tenant,UUID id,UUID actor,
                                          String action,Long previous,long next) {
        UUID eventId=UUID.randomUUID();
        Timestamp timestamp=Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        jdbc.update("""
                INSERT INTO crm.customer_interaction_event
                  (tenant_id,id,interaction_id,actor_id,action_code,
                   previous_version,new_version,occurred_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,tenant,eventId,id,actor,action,previous,next,timestamp);
        return "CANCELLED".equals(action)
                ? new CrmCancellationEventRef(tenant,id,eventId,timestamp.toInstant(),next)
                : null;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch(java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable",e);
        }
    }
    private record Receipt(UUID id,byte[] fingerprint) {}
}
