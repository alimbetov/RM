package kz.rm.inventory;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * INV-R1.5: diagnostic quantity reconciliation only.
 * Never repairs balances, reservations or immutable ledger records.
 * Historical stock positions without movements cannot prove an opening balance.
 */
@Service
public class AuthenticatedInventoryReconciliation {
    public enum Finding {
        CONSISTENT,
        RESERVED_MISMATCH,
        MOVEMENT_BALANCE_MISMATCH,
        MISSING_BALANCE,
        NO_MOVEMENT_BASELINE
    }
    public record Result(UUID tenantId, UUID locationId, UUID itemId,
                         BigDecimal onHand, BigDecimal reserved,
                         BigDecimal activeReservations, BigDecimal latestMovementBalance,
                         Finding finding) {}

    private final TenantAuthorization authorization;
    private final JdbcTemplate jdbc;

    public AuthenticatedInventoryReconciliation(TenantAuthorization authorization, JdbcTemplate jdbc) {
        this.authorization=authorization;
        this.jdbc=jdbc;
    }

    @Transactional
    public Result inspect(VerifiedActor actor, UUID tenant, UUID location, UUID item) {
        if(actor==null)throw new SecurityException("Verified actor required");
        if(tenant==null||location==null||item==null)
            throw new IllegalArgumentException("Tenant, warehouse and SKU required");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.INVENTORY_STOCK_READ);
        BigDecimal[] position=jdbc.query("""
                SELECT on_hand,reserved FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,rs->rs.next()?new BigDecimal[]{rs.getBigDecimal(1),rs.getBigDecimal(2)}:null,
                tenant,location,item);
        BigDecimal active=jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity),0) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND location_id=? AND item_id=? AND state='ACTIVE'
                """,BigDecimal.class,tenant,location,item);
        BigDecimal latest=jdbc.query("""
                SELECT balance_after FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=? AND item_id=?
                ORDER BY recorded_at DESC,id DESC LIMIT 1
                """,rs->rs.next()?rs.getBigDecimal(1):null,tenant,location,item);
        if(active==null)active=BigDecimal.ZERO;
        if(position==null) {
            if(active.signum()==0 && latest==null)
                throw new IllegalArgumentException("Position not found in tenant");
            return new Result(tenant,location,item,null,null,active,latest,Finding.MISSING_BALANCE);
        }
        Finding finding=position[1].compareTo(active)!=0?Finding.RESERVED_MISMATCH:
                latest!=null && latest.compareTo(position[0])!=0?Finding.MOVEMENT_BALANCE_MISMATCH:
                latest==null?Finding.NO_MOVEMENT_BASELINE:Finding.CONSISTENT;
        return new Result(tenant,location,item,position[0],position[1],active,latest,finding);
    }
}
