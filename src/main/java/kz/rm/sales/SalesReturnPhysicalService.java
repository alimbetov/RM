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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * QR-07/R2: physical return evidence and inventory restock in three distinct
 * transactional commands. Never modifies the original completed order.
 */
@Service
public class SalesReturnPhysicalService {
    public enum Disposition { RESTOCK, WRITE_OFF, QUARANTINE }
    public record Result(UUID returnId, String state, long version, UUID evidenceId) {}
    private record Claim(String state,long version,UUID buyer,UUID order,UUID handover) {}
    private record Receipt(UUID id,UUID actor,UUID warehouse,String key,String evidence) {}
    private record Inspection(UUID id,UUID actor,Disposition decision,String key,String evidence) {}
    private record Posting(UUID id,UUID actor,String key,String decision,UUID movement) {}
    private record StockLine(UUID item,BigDecimal quantity) {}
    private final JdbcTemplate jdbc;
    private final TenantAuthorization auth;
    private final WarehouseStaffScopeService scopes;
    private final ReturnInventoryPostingPort inventory;
    private final SalesReturnReceptionProofService proofs;

    public SalesReturnPhysicalService(JdbcTemplate jdbc,TenantAuthorization auth,
                       WarehouseStaffScopeService scopes,ReturnInventoryPostingPort inventory,
                       SalesReturnReceptionProofService proofs) {
        this.jdbc=jdbc;this.auth=auth;this.scopes=scopes;this.inventory=inventory;
        this.proofs=proofs;
    }

    @Transactional
    public Result receive(VerifiedActor actor,UUID tenant,UUID warehouse,UUID returnId,
                          long version,String evidence,String key) {
        UUID employee=admit(actor,tenant,warehouse,returnId,version,evidence,key,
                DomainPermission.SALES_RETURN_RECEIVE);
        Claim c=lock(tenant,returnId);
        scopes.requireAssigned(employee,tenant,warehouse);
        String proofDigest=SalesReturnReceptionProofService.digest(
                tenant,warehouse,returnId,evidence);
        Receipt prior=jdbc.query("""
                SELECT receipt_id,actor_user_id,warehouse_id,request_key,evidence_ref
                FROM crm.sales_return_reception WHERE tenant_id=? AND return_id=?
                """,rs->rs.next()?new Receipt(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                    rs.getString(4),rs.getString(5)):null,tenant,returnId);
        if(prior!=null){
            if(!prior.actor().equals(employee)||!prior.warehouse().equals(warehouse)
               ||!prior.key().equals(key)||!prior.evidence().equals(proofDigest)
               ||version!=2)
                throw new IllegalStateException("RETURN_RECEPTION_REPLAY_CONFLICT");
            return new Result(returnId,"RECEIVED",4,prior.id());
        }
        if(c.version()!=version)throw new IllegalStateException("RETURN_VERSION_CONFLICT");
        if(!SalesReturnState.APPROVED.name().equals(c.state()))
            throw new IllegalStateException("RETURN_NOT_APPROVED");
        proofs.consume(tenant,warehouse,returnId,evidence);
        // Physical receipt is not stock admission. Both intermediate state
        // transitions are durable and atomic with the immutable receipt.
        update(tenant,returnId,"APPROVED","AWAITING_RECEIPT",version);
        event(tenant,returnId,employee,"APPROVED","AWAITING_RECEIPT",version,
                "RC_"+returnId.toString().replace("-",""),"UAT_1");
        update(tenant,returnId,"AWAITING_RECEIPT","RECEIVED",version+1);
        event(tenant,returnId,employee,"AWAITING_RECEIPT","RECEIVED",version+1,key,"UAT_1");
        UUID receiptId=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.sales_return_reception
                  (tenant_id,return_id,receipt_id,warehouse_id,actor_user_id,
                   evidence_ref,request_key,happened_at)
                VALUES(?,?,?,?,?,?,?,?)
                """,tenant,returnId,receiptId,warehouse,employee,proofDigest,key,
                Timestamp.from(Instant.now()));
        return new Result(returnId,"RECEIVED",version+2,receiptId);
    }

    @Transactional
    public Result inspect(VerifiedActor actor,UUID tenant,UUID warehouse,UUID returnId,
                          long version,Disposition disposition,String evidence,String key) {
        UUID employee=admit(actor,tenant,warehouse,returnId,version,evidence,key,
                DomainPermission.SALES_RETURN_INSPECT);
        if(disposition==null)throw new IllegalArgumentException("RETURN_DISPOSITION_REQUIRED");
        Claim c=lock(tenant,returnId);
        scopes.requireAssigned(employee,tenant,warehouse);
        Receipt reception=reception(tenant,returnId);
        if(reception==null||!reception.warehouse().equals(warehouse))
            throw new SecurityException("RETURN_WAREHOUSE_MISMATCH");
        Inspection prior=inspection(tenant,returnId);
        if(prior!=null){
            if(!prior.actor().equals(employee)||prior.decision()!=disposition
                    ||!prior.evidence().equals(evidence)||!prior.key().equals(key))
                throw new IllegalStateException("RETURN_INSPECTION_REPLAY_CONFLICT");
            return new Result(returnId,"INSPECTED",5,prior.id());
        }
        if(c.version()!=version)throw new IllegalStateException("RETURN_VERSION_CONFLICT");
        if(!"RECEIVED".equals(c.state()))throw new IllegalStateException("RETURN_NOT_RECEIVED");
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.sales_return_inspection
                  (tenant_id,return_id,inspection_id,actor_user_id,disposition,
                   evidence_ref,request_key,happened_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,tenant,returnId,id,employee,disposition.name(),evidence,key,
                Timestamp.from(Instant.now()));
        update(tenant,returnId,"RECEIVED","INSPECTED",version);
        event(tenant,returnId,employee,"RECEIVED","INSPECTED",version,key,"UAT_1");
        return new Result(returnId,"INSPECTED",version+1,id);
    }

    @Transactional
    public Result post(VerifiedActor actor,UUID tenant,UUID warehouse,UUID returnId,
                       long version,String key) {
        UUID employee=admit(actor,tenant,warehouse,returnId,version,"POST",key,
                DomainPermission.SALES_RETURN_POST);
        Claim c=lock(tenant,returnId);
        scopes.requireAssigned(employee,tenant,warehouse);
        Receipt reception=reception(tenant,returnId);
        Inspection inspection=inspection(tenant,returnId);
        if(reception==null||inspection==null||!reception.warehouse().equals(warehouse))
            throw new IllegalStateException("RETURN_PHYSICAL_EVIDENCE_REQUIRED");
        Posting prior=jdbc.query("""
                SELECT posting_id,actor_user_id,request_key,disposition,movement_id
                FROM crm.sales_return_posting WHERE tenant_id=? AND return_id=?
                """,rs->rs.next()?new Posting(rs.getObject(1,UUID.class),
                   rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),
                   rs.getObject(5,UUID.class)):null,tenant,returnId);
        if(prior!=null){
            if(!prior.actor().equals(employee)||!prior.key().equals(key))
                throw new IllegalStateException("RETURN_POST_REPLAY_CONFLICT");
            return new Result(returnId,switch(prior.decision()){
                    case "RESTOCK" -> "RESTOCKED";
                    case "QUARANTINE" -> "QUARANTINED";
                    default -> "DISPOSED";
                },6,prior.id());
        }
        if(c.version()!=version)throw new IllegalStateException("RETURN_VERSION_CONFLICT");
        if(!"INSPECTED".equals(c.state()))throw new IllegalStateException("RETURN_NOT_INSPECTED");
        StockLine line=jdbc.query("""
                SELECT r.item_id,rl.quantity
                FROM crm.sales_return_line rl
                JOIN crm.inv_stock_reservation r
                  ON r.tenant_id=rl.tenant_id AND r.order_line_id=rl.order_line_id
                JOIN crm.sales_pickup_handover_line hl
                  ON hl.tenant_id=r.tenant_id AND hl.reservation_id=r.id
                WHERE rl.tenant_id=? AND rl.return_id=? AND hl.handover_id=?
                """,rs->rs.next()?new StockLine(rs.getObject(1,UUID.class),
                        rs.getBigDecimal(2)):null,tenant,returnId,c.handover());
        if(line==null)throw new IllegalStateException("RETURN_ORIGINAL_STOCK_PROOF_REQUIRED");
        UUID movement=null, quarantineMovement=null;
        if(inspection.decision()==Disposition.RESTOCK)
            movement=inventory.restock(employee,tenant,warehouse,line.item(),returnId,
                    line.quantity()).id();
        if(inspection.decision()==Disposition.QUARANTINE)
            quarantineMovement=inventory.quarantine(employee,tenant,warehouse,line.item(),
                    returnId,line.quantity());
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.sales_return_posting
                 (tenant_id,return_id,posting_id,actor_user_id,warehouse_id,item_id,
                  quantity,disposition,movement_id,quarantine_movement_id,request_key,happened_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,returnId,id,employee,warehouse,line.item(),line.quantity(),
                inspection.decision().name(),movement,quarantineMovement,key,Timestamp.from(Instant.now()));
        String target=switch(inspection.decision()) {
            case RESTOCK -> "RESTOCKED";
            case QUARANTINE -> "QUARANTINED";
            case WRITE_OFF -> "DISPOSED";
        };
        update(tenant,returnId,"INSPECTED",target,version);
        event(tenant,returnId,employee,"INSPECTED",target,version,key,"UAT_1");
        return new Result(returnId,target,version+1,id);
    }

    private UUID admit(VerifiedActor actor,UUID tenant,UUID warehouse,UUID id,
                       long version,String evidence,String key,DomainPermission permission) {
        if(actor==null||tenant==null||warehouse==null||id==null)
            throw new SecurityException("RETURN_STAFF_REQUIRED");
        boolean proofEvidence=permission==DomainPermission.SALES_RETURN_RECEIVE;
        if(version<0||!token(key,90)||
           (proofEvidence?(evidence==null||!evidence.matches("[A-Za-z0-9_-]{43}")):!token(evidence,120)))
            throw new IllegalArgumentException("RETURN_EVIDENCE_INVALID");
        auth.requireForCommand(actor.userId(),tenant,permission);
        return actor.userId();
    }

    private Claim lock(UUID tenant,UUID id) {
        Claim claim=jdbc.query("""
                SELECT rr.state,rr.row_version,rr.buyer_user_id,rr.order_id,
                       rr.source_handover_id
                FROM crm.sales_return_request rr
                JOIN crm.sales_order o
                  ON o.tenant_id=rr.tenant_id AND o.id=rr.order_id
                WHERE rr.tenant_id=? AND rr.id=? AND o.status='COMPLETED'
                FOR UPDATE OF rr
                """,rs->rs.next()?new Claim(rs.getString(1),rs.getLong(2),
                    rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
                    rs.getObject(5,UUID.class)):null,tenant,id);
        if(claim==null)throw new SecurityException("RETURN_NOT_ACCESSIBLE");
        return claim;
    }

    private Receipt reception(UUID tenant,UUID id) {
        return jdbc.query("""
                SELECT receipt_id,actor_user_id,warehouse_id,request_key,evidence_ref
                FROM crm.sales_return_reception WHERE tenant_id=? AND return_id=?
                """,rs->rs.next()?new Receipt(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                    rs.getString(4),rs.getString(5)):null,tenant,id);
    }
    private Inspection inspection(UUID tenant,UUID id) {
        return jdbc.query("""
                SELECT inspection_id,actor_user_id,disposition,request_key,evidence_ref
                FROM crm.sales_return_inspection WHERE tenant_id=? AND return_id=?
                """,rs->rs.next()?new Inspection(rs.getObject(1,UUID.class),
                   rs.getObject(2,UUID.class),Disposition.valueOf(rs.getString(3)),
                   rs.getString(4),rs.getString(5)):null,tenant,id);
    }
    private void update(UUID tenant,UUID id,String before,String after,long version) {
        int changed=jdbc.update("""
                UPDATE crm.sales_return_request
                SET state=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND state=? AND row_version=?
                """,after,tenant,id,before,version);
        if(changed!=1)throw new IllegalStateException("RETURN_VERSION_CONFLICT");
    }
    private void event(UUID tenant,UUID id,UUID actor,String before,String after,
                       long version,String key,String policy) {
        String fingerprint=java.util.HexFormat.of().formatHex(
                digest(actor+"|"+id+"|"+before+"|"+after+"|"+version+"|"+key));
        jdbc.update("""
                INSERT INTO crm.sales_return_event
                 (tenant_id,id,return_id,actor_user_id,event_type,occurred_at,
                  request_key,request_fingerprint,from_state,to_state,
                  expected_row_version,policy_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),id,actor,after,Timestamp.from(Instant.now()),
                key,fingerprint,before,after,version,policy);
    }
    private static byte[] digest(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch(java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
    private static boolean token(String value,int max) {
        return value!=null&&!value.isBlank()&&value.length()<=max
                && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }
}
