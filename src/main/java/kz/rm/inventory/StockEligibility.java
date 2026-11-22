package kz.rm.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.ConnectionCallback;
import jakarta.annotation.PostConstruct;
import java.util.UUID;

/** Tenant-qualified owner metadata. Locks fence concurrent location closure/SKU archive. */
@Service
class StockEligibility {
    private final JdbcTemplate jdbc;
    private String locationLockSql;
    private String itemLockSql;
    private boolean postgres;
    StockEligibility(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @PostConstruct
    void initializeLockSql() {
        String database=jdbc.execute((ConnectionCallback<String>)connection->
                connection.getMetaData().getDatabaseProductName());
        // PostgreSQL SHARE is compatible with other inventory admissions, but
        // conflicts with warehouse status/SKU archive UPDATE. H2 supports UPDATE.
        // The mode is fixed from trusted DB metadata, never user-controlled SQL.
        postgres=database!=null &&
                database.toLowerCase(java.util.Locale.ROOT).contains("postgresql");
        String mode=postgres?" FOR SHARE":" FOR UPDATE";
        locationLockSql="SELECT location_type,status FROM public.org_location "
                +"WHERE workspace_id=? AND id=?"+mode;
        itemLockSql="SELECT kind,state FROM crm.cat_item "
                +"WHERE tenant_id=? AND id=?"+mode;
    }

    void requireReceivable(UUID tenant,UUID location,UUID item) {
        requireWarehouse(tenant,location);
        requireProduct(tenant,item,false);
    }
    void requireReservable(UUID tenant,UUID location,UUID item) {
        requireWarehouse(tenant,location);
        requireProduct(tenant,item,true);
    }
    /** Acquire both location locks before the item lock. Opposite transfers and
     * a receipt/stocktake at the second location must obey the same order. */
    void requireTransferWarehouses(UUID tenant,UUID first,UUID second,UUID item) {
        if(first==null||second==null||first.compareTo(second)>=0)
            throw new IllegalArgumentException("Ascending transfer location locks required");
        requireWarehouse(tenant,first);
        requireWarehouse(tenant,second);
        requireProduct(tenant,item,false);
    }
    /**
     * A compatible PostgreSQL SHARE owner lock cannot serialize the initial
     * INSERT of a missing balance row. ON CONFLICT DO NOTHING is required to
     * make two independent first receipts safe. H2 test mode keeps exclusive
     * warehouse owner locks and uses its existing conditional INSERT.
     * Caller must hold the owner admission and a write transaction.
     */
    void ensureZeroPosition(UUID tenant,UUID location,UUID item) {
        if(postgres) {
            jdbc.update("""
                    INSERT INTO crm.inv_stock_balance
                      (tenant_id,location_id,item_id,on_hand,reserved,row_version)
                    VALUES (?,?,?,0,0,0)
                    ON CONFLICT (tenant_id,location_id,item_id) DO NOTHING
                    """,tenant,location,item);
        } else {
            jdbc.update("""
                    INSERT INTO crm.inv_stock_balance
                      (tenant_id,location_id,item_id,on_hand,reserved,row_version)
                    SELECT ?,?,?,0,0,0 WHERE NOT EXISTS (
                      SELECT 1 FROM crm.inv_stock_balance
                      WHERE tenant_id=? AND location_id=? AND item_id=?
                    )
                    """,tenant,location,item,tenant,location,item);
        }
    }

    private void requireWarehouse(UUID tenant,UUID location) {
        if(tenant==null||location==null)throw new IllegalArgumentException("Warehouse required");
        String[] status=jdbc.query(locationLockSql,
                rs->rs.next()?new String[]{rs.getString(1),rs.getString(2)}:null,
                tenant,location);
        if(status==null || !"WAREHOUSE".equals(status[0]) || !"ACTIVE".equals(status[1]))
            throw new IllegalArgumentException("Active tenant WAREHOUSE required");
    }
    private void requireProduct(UUID tenant,UUID item,boolean forReservation) {
        if(item==null)throw new IllegalArgumentException("SKU required");
        String[] status=jdbc.query(itemLockSql,
                rs->rs.next()?new String[]{rs.getString(1),rs.getString(2)}:null,
                tenant,item);
        if(status==null || !"PRODUCT".equals(status[0])
                || "ARCHIVED".equals(status[1])
                || (forReservation && !"PUBLISHED".equals(status[1])))
            throw new IllegalArgumentException(forReservation
                    ?"Published tenant PRODUCT required":"Non-archived tenant PRODUCT required");
    }
}
