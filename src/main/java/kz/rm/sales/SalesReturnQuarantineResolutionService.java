package kz.rm.sales;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import kz.rm.iam.WarehouseStaffScopeService;
import kz.rm.inventory.ReturnInventoryPostingPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * R2.5: exact return-linked exit from non-sellable quarantine. Restock and
 * disposal use separate authorized decisions, never rewriting quarantine
 * intake, pickup receipt or the completed original sale.
 */
@Service
public class SalesReturnQuarantineResolutionService {
    public enum Action { RESTOCK, DISPOSE }
    public record Resolved(UUID returnId,UUID resolutionId,String state,
                           long version,UUID stockMovementId) {}
    private record Quarantine(String state,long version,UUID warehouse,
                              UUID item,BigDecimal quantity,String policy) {}
    private record Prior(UUID id,UUID actor,String key,String evidence,
                         Action action,UUID stockMovement) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final WarehouseStaffScopeService scopes;
    private final ReturnInventoryPostingPort inventory;

    public SalesReturnQuarantineResolutionService(JdbcTemplate jdbc,
                          TenantAuthorization authorization,
                          WarehouseStaffScopeService scopes,
                          ReturnInventoryPostingPort inventory) {
        this.jdbc=jdbc;this.authorization=authorization;
        this.scopes=scopes;this.inventory=inventory;
    }

    @Transactional
    public Resolved resolve(VerifiedActor actor,UUID tenant,UUID warehouse,UUID returnId,
                            long expectedVersion,Action action,String evidence,String key) {
        if(actor==null||tenant==null||warehouse==null||returnId==null)
            throw new SecurityException("QUARANTINE_STAFF_REQUIRED");
        if(action==null||expectedVersion<0||!token(key,100)||!token(evidence,120))
            throw new IllegalArgumentException("QUARANTINE_RESOLUTION_INPUT_INVALID");
        UUID staff=actor.userId();
        authorization.requireForCommand(staff,tenant,DomainPermission.SALES_RETURN_POST);
        authorization.requireForCommand(staff,tenant,
                action==Action.RESTOCK?DomainPermission.INVENTORY_RECEIVE:
                        DomainPermission.INVENTORY_ADJUST);
        Quarantine source=jdbc.query("""
                SELECT rr.state,rr.row_version,q.warehouse_id,q.item_id,
                       q.delta_quantity,rr.policy_version
                FROM crm.sales_return_request rr
                JOIN crm.inv_return_quarantine_movement q
                  ON q.tenant_id=rr.tenant_id AND q.return_id=rr.id
                JOIN crm.sales_return_posting p
                  ON p.tenant_id=rr.tenant_id AND p.return_id=rr.id
                WHERE rr.tenant_id=? AND rr.id=? AND p.disposition='QUARANTINE'
                FOR UPDATE OF rr
                """,rs->rs.next()?new Quarantine(rs.getString(1),rs.getLong(2),
                        rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
                        rs.getBigDecimal(5),rs.getString(6)):null,tenant,returnId);
        if(source==null||!source.warehouse().equals(warehouse))
            throw new SecurityException("QUARANTINE_SOURCE_NOT_ACCESSIBLE");
        scopes.requireAssigned(staff,tenant,warehouse);
        Prior prior=jdbc.query("""
                SELECT id,actor_user_id,request_key,evidence_ref,action_code,stock_movement_id
                FROM crm.sales_return_quarantine_resolution
                WHERE tenant_id=? AND return_id=?
                """,rs->rs.next()?new Prior(rs.getObject(1,UUID.class),
                        rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),
                        Action.valueOf(rs.getString(5)),rs.getObject(6,UUID.class)):null,
                tenant,returnId);
        if(prior!=null){
            if(!prior.actor().equals(staff)||!prior.key().equals(key)
                    ||!prior.evidence().equals(evidence)||prior.action()!=action
                    ||expectedVersion!=6)
                throw new IllegalStateException("QUARANTINE_REPLAY_CONFLICT");
            return new Resolved(returnId,prior.id(),
                    action==Action.RESTOCK?"RESTOCKED":"DISPOSED",7,
                    prior.stockMovement());
        }
        if(!"QUARANTINED".equals(source.state())||source.version()!=expectedVersion)
            throw new IllegalStateException("QUARANTINE_STATE_VERSION_CONFLICT");

        // Align lock acquisition with standard INVENTORY: request stripe and
        // stock row inside restock() first. An exception further below rolls
        // back the sellable credit in the same outer PostgreSQL transaction.
        UUID movement=action==Action.RESTOCK
                ?inventory.restock(staff,tenant,warehouse,source.item(),returnId,
                        source.quantity()).id():null;
        BigDecimal sellable=jdbc.query("""
                SELECT on_hand FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=? FOR UPDATE
                """,rs->rs.next()?rs.getBigDecimal(1):null,
                tenant,warehouse,source.item());
        if(sellable==null)throw new IllegalStateException("QUARANTINE_SOURCE_STOCK_MISSING");
        BigDecimal before=jdbc.query("""
                SELECT quantity FROM crm.inv_return_quarantine_balance
                WHERE tenant_id=? AND warehouse_id=? AND item_id=? FOR UPDATE
                """,rs->rs.next()?rs.getBigDecimal(1):null,
                tenant,warehouse,source.item());
        if(before==null||before.compareTo(source.quantity())<0)
            throw new IllegalStateException("QUARANTINE_BALANCE_INCONSISTENT");
        BigDecimal after=before.subtract(source.quantity());
        jdbc.update("""
                UPDATE crm.inv_return_quarantine_balance
                SET quantity=?,row_version=row_version+1
                WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                """,after,tenant,warehouse,source.item());
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.sales_return_quarantine_resolution
                 (tenant_id,return_id,id,warehouse_id,item_id,quantity,
                  before_quantity,after_quantity,action_code,actor_user_id,
                  request_key,evidence_ref,stock_movement_id,happened_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,returnId,id,warehouse,source.item(),source.quantity(),
                before,after,action.name(),staff,key,evidence,movement,
                Timestamp.from(Instant.now()));
        String target=action==Action.RESTOCK?"RESTOCKED":"DISPOSED";
        int changed=jdbc.update("""
                UPDATE crm.sales_return_request
                SET state=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND state='QUARANTINED' AND row_version=?
                """,target,tenant,returnId,expectedVersion);
        if(changed!=1)throw new IllegalStateException("QUARANTINE_VERSION_CONFLICT");
        String hash=hash(staff+"|"+returnId+"|QUARANTINED|"+target+"|"+
                expectedVersion+"|"+key);
        jdbc.update("""
                INSERT INTO crm.sales_return_event
                  (tenant_id,id,return_id,actor_user_id,event_type,occurred_at,
                   request_key,request_fingerprint,from_state,to_state,
                   expected_row_version,policy_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),returnId,staff,target,
                Timestamp.from(Instant.now()),key,hash,"QUARANTINED",target,
                expectedVersion,source.policy());
        return new Resolved(returnId,id,target,expectedVersion+1,movement);
    }

    private static boolean token(String value,int max) {
        return value!=null&&!value.isBlank()&&value.length()<=max
                && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }
    private static String hash(String value){
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException ex){
            throw new IllegalStateException(ex);
        }
    }
}
