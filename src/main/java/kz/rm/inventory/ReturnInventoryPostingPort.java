package kz.rm.inventory;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal QR-07 posting boundary. Caller is the return transaction and must
 * persist return provenance in that same transaction. No controller exposure.
 */
@Service
public class ReturnInventoryPostingPort {
    private final InventoryMovementService movements;
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    public ReturnInventoryPostingPort(InventoryMovementService movements,
                                      TenantAuthorization authorization,JdbcTemplate jdbc) {
        this.movements=movements;
        this.jdbc=jdbc;
        this.authorization=authorization;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public InventoryMovementService.Movement restock(UUID actor,UUID tenant,UUID warehouse,
                         UUID item,UUID returnId,BigDecimal quantity) {
        authorization.requireForCommand(actor,tenant,DomainPermission.INVENTORY_RECEIVE);
        if(returnId==null||quantity==null||quantity.signum()<=0)
            throw new IllegalArgumentException("RETURN_POST_QUANTITY_INVALID");
        // Dedicated deterministic movement key, not supplied by a caller.
        String movementKey="R07_"+returnId.toString().replace("-","");
        return movements.adjust(tenant,warehouse,item,movementKey,quantity,
                InventoryMovementService.Reason.RECEIPT,actor,null);
    }

    /**
     * Physical quarantine is an independent, non-sellable quantity ledger.
     * Lock the existing warehouse/SKU stock row as a shared serialization
     * point before initializing or updating quarantine quantity.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID quarantine(UUID actor,UUID tenant,UUID warehouse,UUID item,
                           UUID returnId,BigDecimal quantity) {
        authorization.requireForCommand(actor,tenant,DomainPermission.INVENTORY_RECEIVE);
        if(returnId==null||quantity==null||quantity.signum()<=0
                ||quantity.scale()>4||quantity.precision()>19)
            throw new IllegalArgumentException("RETURN_QUARANTINE_QUANTITY_INVALID");
        BigDecimal stock=jdbc.query("""
                SELECT on_hand FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=? FOR UPDATE
                """,rs->rs.next()?rs.getBigDecimal(1):null,tenant,warehouse,item);
        if(stock==null)throw new IllegalStateException("RETURN_ORIGINAL_STOCK_POSITION_REQUIRED");
        BigDecimal prior=jdbc.query("""
                SELECT quantity FROM crm.inv_return_quarantine_balance
                WHERE tenant_id=? AND warehouse_id=? AND item_id=? FOR UPDATE
                """,rs->rs.next()?rs.getBigDecimal(1):null,tenant,warehouse,item);
        if(prior==null){
            prior=BigDecimal.ZERO;
            jdbc.update("""
                    INSERT INTO crm.inv_return_quarantine_balance
                     (tenant_id,warehouse_id,item_id,quantity,row_version)
                    VALUES (?,?,?,?,0)
                    """,tenant,warehouse,item,prior);
        }
        BigDecimal after=prior.add(quantity);
        if(after.precision()>19)throw new IllegalArgumentException("RETURN_QUARANTINE_OVERFLOW");
        jdbc.update("""
                UPDATE crm.inv_return_quarantine_balance
                SET quantity=?,row_version=row_version+1
                WHERE tenant_id=? AND warehouse_id=? AND item_id=?
                """,after,tenant,warehouse,item);
        UUID movement=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.inv_return_quarantine_movement
                 (tenant_id,id,return_id,warehouse_id,item_id,delta_quantity,
                  before_quantity,after_quantity,actor_user_id,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,tenant,movement,returnId,warehouse,item,quantity,prior,after,
                actor,Timestamp.from(Instant.now()));
        return movement;
    }

}
