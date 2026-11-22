package kz.rm.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-only inventory-owned dependency check for ORGANIZATION lifecycle.
 * Caller must already hold the tenant-scoped org_location FOR UPDATE lock.
 * NEVER acquire stock, reservation or stocktake row locks from this port.
 */
@Service
public class InventoryLocationUsagePort {
    public record LocationUsageBlockers(boolean hasOnHand, boolean hasReservedBalance,
                                        boolean hasActiveReservation, boolean hasOpenStocktake) {}

    private final JdbcTemplate jdbc;

    public InventoryLocationUsagePort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LocationUsageBlockers blockers(UUID tenant, UUID location) {
        requireIdentity(tenant, location);
        return jdbc.query("""
                SELECT
                  EXISTS(SELECT 1 FROM crm.inv_stock_balance b
                         WHERE b.tenant_id=? AND b.location_id=? AND b.on_hand>0),
                  EXISTS(SELECT 1 FROM crm.inv_stock_balance b
                         WHERE b.tenant_id=? AND b.location_id=? AND b.reserved>0),
                  EXISTS(SELECT 1 FROM crm.inv_stock_reservation r
                         WHERE r.tenant_id=? AND r.location_id=? AND r.state='ACTIVE'),
                  EXISTS(SELECT 1 FROM crm.inv_stocktake s
                         WHERE s.tenant_id=? AND s.location_id=? AND s.status='OPEN')
                """, rs -> {
                    if (!rs.next()) throw new IllegalStateException("Inventory usage unavailable");
                    return new LocationUsageBlockers(rs.getBoolean(1), rs.getBoolean(2),
                            rs.getBoolean(3), rs.getBoolean(4));
                }, tenant, location, tenant, location, tenant, location, tenant, location);
    }

    /** History must not be silently relabeled to another administrative KATO. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasMovementOrReservationHistory(UUID tenant, UUID location) {
        requireIdentity(tenant, location);
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT
                    EXISTS(SELECT 1 FROM crm.inv_stock_movement m
                           WHERE m.tenant_id=? AND m.location_id=?)
                 OR EXISTS(SELECT 1 FROM crm.inv_stock_reservation r
                           WHERE r.tenant_id=? AND r.location_id=?)
                 OR EXISTS(SELECT 1 FROM crm.inv_stocktake t
                           WHERE t.tenant_id=? AND t.location_id=?)
                """, rs -> rs.next() && rs.getBoolean(1),
                tenant, location, tenant, location, tenant, location));
    }

    private static void requireIdentity(UUID tenant, UUID location) {
        if (tenant == null || location == null)
            throw new IllegalArgumentException("Tenant and location required");
    }
}
