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
    StockEligibility(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @PostConstruct
    void initializeLockSql() {
        String database=jdbc.execute((ConnectionCallback<String>)connection->
                connection.getMetaData().getDatabaseProductName());
        // PostgreSQL SHARE is compatible with other inventory admissions, but
        // conflicts with warehouse status/SKU archive UPDATE. H2 supports UPDATE.
        // The mode is fixed from trusted DB metadata, never user-controlled SQL.
        String mode=database!=null &&
                database.toLowerCase(java.util.Locale.ROOT).contains("postgresql")
                ?" FOR SHARE":" FOR UPDATE";
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
