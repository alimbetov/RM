package kz.rm.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-only INVENTORY-owned admission facts for a CATALOG SKU archive.
 * CATALOG already holds the SKU row FOR UPDATE when calling this port.
 * Do not acquire location/stock row locks here: stock writers lock
 * location -> SKU -> stock, and a reverse order would deadlock.
 */
@Service
public class CatalogInventoryUsagePort {
    public record Blockers(boolean onHand, boolean reservedBalance,
                           boolean activeReservation, boolean openStocktake) {
        public boolean any() {
            return onHand || reservedBalance || activeReservation || openStocktake;
        }
    }

    private final JdbcTemplate jdbc;

    public CatalogInventoryUsagePort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Blockers blockers(UUID tenantId, UUID itemId) {
        if (tenantId == null || itemId == null)
            throw new IllegalArgumentException("Tenant and catalog item required");
        boolean onHand = exists("""
                SELECT 1 FROM crm.inv_stock_balance
                WHERE tenant_id=? AND item_id=? AND on_hand<>0 LIMIT 1
                """, tenantId, itemId);
        boolean reserved = exists("""
                SELECT 1 FROM crm.inv_stock_balance
                WHERE tenant_id=? AND item_id=? AND reserved<>0 LIMIT 1
                """, tenantId, itemId);
        boolean active = exists("""
                SELECT 1 FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND item_id=? AND state='ACTIVE' LIMIT 1
                """, tenantId, itemId);
        boolean stocktake = exists("""
                SELECT 1 FROM crm.inv_stocktake st
                JOIN crm.inv_stocktake_line ln
                  ON ln.tenant_id=st.tenant_id AND ln.stocktake_id=st.id
                WHERE st.tenant_id=? AND ln.item_id=? AND st.status='OPEN' LIMIT 1
                """, tenantId, itemId);
        return new Blockers(onHand, reserved, active, stocktake);
    }

    private boolean exists(String sql, UUID tenant, UUID item) {
        return Boolean.TRUE.equals(jdbc.query(sql, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), tenant, item));
    }
}
