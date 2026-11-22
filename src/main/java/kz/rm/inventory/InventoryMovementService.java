package kz.rm.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Arrays;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Internal persistence component for tenant-scoped stock adjustments. Locked balance ensures
 * atomic balance + immutable movement under concurrent workers. No stock-history
 * retention is enabled: legal classification must precede any purge.
 * Never expose raw mutation overloads outside the inventory package.
 * Call via InventoryAuthorizedCommandService after transaction-bound RBAC admission.
 */
@Service
public class InventoryMovementService {
    private final JdbcTemplate jdbc;
    private final StockEligibility stockEligibility;

    public InventoryMovementService(JdbcTemplate jdbc,StockEligibility stockEligibility) {
        this.jdbc=jdbc;this.stockEligibility=stockEligibility;
    }

    public record Movement(UUID id, UUID tenantId, UUID locationId, UUID itemId,
                           BigDecimal delta, BigDecimal before, BigDecimal after) {}

    public enum Reason { MANUAL_ADJUSTMENT, CORRECTION, RECEIPT, WRITE_OFF }

    @Transactional
    Movement adjust(UUID tenantId, UUID locationId, UUID itemId,
                           String requestKey, BigDecimal delta) {
        return adjust(tenantId,locationId,itemId,requestKey,delta,Reason.MANUAL_ADJUSTMENT);
    }

    @Transactional
    Movement adjust(UUID tenantId, UUID locationId, UUID itemId,
                           String requestKey, BigDecimal delta, Reason reason) {
        if (tenantId==null || locationId==null || itemId==null
                || requestKey==null || !requestKey.matches("[A-Za-z0-9_-]{8,100}")
                || delta==null || delta.signum()==0 || delta.scale()>4
                || delta.precision()>19 || reason==null)
            throw new IllegalArgumentException("Invalid stock movement");
        if(reason==Reason.RECEIPT && delta.signum()<0 ||
                reason==Reason.WRITE_OFF && delta.signum()>0)
            throw new IllegalArgumentException("Movement direction conflicts with reason");

        // Lock tenant-wide request key FIRST: a retry targeting another stock row
        // must see the existing request and fail deterministically, not on UNIQUE.
        int stripe;
        try {
            stripe=Math.floorMod(Arrays.hashCode(MessageDigest.getInstance("SHA-256")
                    .digest((tenantId+":"+requestKey).getBytes(StandardCharsets.UTF_8))),64);
        } catch(Exception e) { throw new IllegalStateException("Cannot hash movement key",e); }
        jdbc.queryForObject(
                "SELECT bucket FROM crm.inv_movement_request_lock WHERE bucket=? FOR UPDATE",
                Integer.class,stripe);
        // Same request must return its original immutable ledger entry.
        var recorded=jdbc.query("""
            SELECT id,location_id,item_id,delta_quantity,balance_before,balance_after,reason_code
            FROM crm.inv_stock_movement WHERE tenant_id=? AND request_key=?
            """,(rs,n)->new Movement(rs.getObject(1,UUID.class),tenantId,
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getBigDecimal(6)),
                tenantId,requestKey);
        if(!recorded.isEmpty()) {
            String savedReason=jdbc.queryForObject("""
                SELECT reason_code FROM crm.inv_stock_movement WHERE tenant_id=? AND request_key=?
                """,String.class,tenantId,requestKey);
            var old=recorded.getFirst();
            if(!old.locationId().equals(locationId) || !old.itemId().equals(itemId)
                    || old.delta().compareTo(delta)!=0 || !savedReason.equals(reason.name()))
                throw new IllegalArgumentException("Idempotency key reused with different movement");
            return old;
        }

        // First check under tenant request-key fence; idempotent retries above
        // are replayable even after closing a warehouse or archiving the SKU.
        // Lock owner reference rows before stock for race-safe ACTIVE PRODUCT check.
        stockEligibility.requireReceivable(tenantId,locationId,itemId);
        if(reason==Reason.RECEIPT) {
            // Stock row is zero-initialized on first legitimate receipt only.
            // The warehouse owner row is FOR UPDATE locked, so competing first
            // receives serialize initialization without an upsert race.
            jdbc.update("""
                INSERT INTO crm.inv_stock_balance
                  (tenant_id,location_id,item_id,on_hand,reserved,row_version)
                SELECT ?,?,?,0,0,0 WHERE NOT EXISTS (
                  SELECT 1 FROM crm.inv_stock_balance
                  WHERE tenant_id=? AND location_id=? AND item_id=?
                )
                """,tenantId,locationId,itemId,tenantId,locationId,itemId);
        }
        // Serialize by the exact tenant/location/SKU stock row, across all pods.
        List<BigDecimal[]> balance=jdbc.query("""
            SELECT on_hand,reserved FROM crm.inv_stock_balance
            WHERE tenant_id=? AND location_id=? AND item_id=? FOR UPDATE
            """,(rs,n)->new BigDecimal[]{rs.getBigDecimal(1),rs.getBigDecimal(2)},
                tenantId,locationId,itemId);
        if (balance.isEmpty()) throw new IllegalArgumentException("Stock position not initialized");

        BigDecimal before=balance.getFirst()[0],reserved=balance.getFirst()[1];
        BigDecimal after=before.add(delta);
        if(after.precision()>19 || after.scale()>4 || after.signum()<0 ||
                after.compareTo(reserved)<0)
            throw new IllegalArgumentException("Adjustment violates stock or reservations");

        UUID id=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
            UPDATE crm.inv_stock_balance SET on_hand=?,row_version=row_version+1
            WHERE tenant_id=? AND location_id=? AND item_id=?
            """,after,tenantId,locationId,itemId);
        jdbc.update("""
            INSERT INTO crm.inv_stock_movement
            (tenant_id,id,location_id,item_id,request_key,delta_quantity,
             balance_before,balance_after,occurred_at,recorded_at,reason_code)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,tenantId,id,locationId,itemId,requestKey,delta,before,after,now,now,reason.name());
        return new Movement(id,tenantId,locationId,itemId,delta,before,after);
    }
}
