package kz.rm.crm;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Append-only event store, no plaintext PII or document bodies. */
@Service
public class CrmHistoryService {
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    public CrmHistoryService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this.jdbc=jdbc; this.authorization=authorization;
    }
    public record CustomerChange(UUID id, UUID customerId, UUID actorId, String action,
                                 Long previousVersion, long newVersion, Instant occurredAt) {}
    public record ContractRevision(UUID id, UUID contractId, long revisionNo, UUID actorId,
                                   String action, LocalDate startsOn, LocalDate endsOn,
                                   String status, Instant occurredAt) {}
    void customer(UUID tenant, UUID customer, UUID actor, String action, long previous, long next) {
        jdbc.update("""
          INSERT INTO crm.customer_change_event(tenant_id,id,customer_id,actor_id,action_code,
              previous_version,new_version,occurred_at)
          VALUES (?,?,?,?,?,?,?,?)
          """,tenant,UUID.randomUUID(),customer,actor,action,previous,next,Timestamp.from(Instant.now()));
    }
    void contract(UUID tenant, UUID contract, UUID actor, String action, long revision,
                  LocalDate start, LocalDate end, CustomerContractEntity.Status status) {
        jdbc.update("""
          INSERT INTO crm.contract_revision(tenant_id,id,contract_id,revision_no,actor_id,
             action_code,starts_on,ends_on,status,occurred_at)
          VALUES (?,?,?,?,?,?,?,?,?,?)
          """,tenant,UUID.randomUUID(),contract,revision,actor,action,
                Date.valueOf(start),end==null?null:Date.valueOf(end),status.name(),
                Timestamp.from(Instant.now()));
    }
    private void read(VerifiedActor actor, UUID tenant) {
        if(actor==null || tenant==null) throw new SecurityException("Verified actor and tenant required");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.CRM_READ);
    }
    @Transactional
    public List<CustomerChange> customerHistory(VerifiedActor actor, UUID tenant, UUID customer, int limit) {
        read(actor,tenant); checkLimit(limit);
        return jdbc.query("""
          SELECT id,customer_id,actor_id,action_code,previous_version,new_version,occurred_at
          FROM crm.customer_change_event WHERE tenant_id=? AND customer_id=?
          ORDER BY occurred_at DESC,id DESC LIMIT ?
          """,(rs,n)->new CustomerChange(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
             rs.getObject(3,UUID.class),rs.getString(4),(Long)rs.getObject(5),
             rs.getLong(6),rs.getTimestamp(7).toInstant()),tenant,customer,limit);
    }
    @Transactional
    public List<ContractRevision> contractHistory(VerifiedActor actor, UUID tenant, UUID contract, int limit) {
        read(actor,tenant); checkLimit(limit);
        return jdbc.query("""
          SELECT id,contract_id,revision_no,actor_id,action_code,starts_on,ends_on,status,occurred_at
          FROM crm.contract_revision WHERE tenant_id=? AND contract_id=?
          ORDER BY revision_no DESC LIMIT ?
          """,(rs,n)->new ContractRevision(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
             rs.getLong(3),rs.getObject(4,UUID.class),rs.getString(5),
             rs.getDate(6).toLocalDate(),rs.getDate(7)==null?null:rs.getDate(7).toLocalDate(),
             rs.getString(8),rs.getTimestamp(9).toInstant()),tenant,contract,limit);
    }

    public record ContactChange(UUID id, UUID contactId, UUID customerId, UUID actorId,
                                String action, Long previousVersion, long newVersion, Instant occurredAt) {}

    void contact(UUID tenant, UUID id, UUID customer, UUID actor, String action, long before, long after) {
        jdbc.update("""
          INSERT INTO crm.contact_change_event(tenant_id,id,contact_id,customer_id,actor_id,
              action_code,previous_version,new_version,occurred_at)
          VALUES (?,?,?,?,?,?,?,?,?)
          """,tenant,UUID.randomUUID(),id,customer,actor,action,before,after,Timestamp.from(Instant.now()));
    }

    @Transactional
    public List<ContactChange> contactHistory(VerifiedActor actor, UUID tenant, UUID id, int limit) {
        read(actor,tenant); checkLimit(limit);
        return jdbc.query("""
            SELECT id,contact_id,customer_id,actor_id,action_code,previous_version,
                   new_version,occurred_at FROM crm.contact_change_event
            WHERE tenant_id=? AND contact_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT ?
            """,(rs,n)->new ContactChange(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getString(5),
                (Long)rs.getObject(6),rs.getLong(7),rs.getTimestamp(8).toInstant()),tenant,id,limit);
    }

    private static void checkLimit(int limit) {
        if(limit<1||limit>100) throw new IllegalArgumentException("History limit must be 1..100");
    }
}
