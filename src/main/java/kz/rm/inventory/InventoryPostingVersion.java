package kz.rm.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/**
 * Inventory movement commit ordering. Writers first lock and update the canonical
 * stock balance row in the SAME transaction, then call this helper.
 *
 * Balance row_version also advances on reserve/release; gaps in movement
 * posting_version are therefore expected and do not signal missing movements.
 */
final class InventoryPostingVersion {
    private InventoryPostingVersion() {}

    static long afterBalanceUpdate(JdbcTemplate jdbc, UUID tenant, UUID location, UUID item) {
        Long version=jdbc.queryForObject("""
            SELECT row_version FROM crm.inv_stock_balance
            WHERE tenant_id=? AND location_id=? AND item_id=?
            """,Long.class,tenant,location,item);
        if(version==null || version<1)
            throw new IllegalStateException("Movement requires a versioned stock balance");
        return version;
    }
}
