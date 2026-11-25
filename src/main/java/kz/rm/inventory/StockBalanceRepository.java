package kz.rm.inventory;

import kz.rm.inventory.dto.StockBalanceDto;
import kz.rm.inventory.dto.StockSearch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded, tenant-isolated stock reads. No dynamic column names or SQL from clients. */
@Repository
class StockBalanceRepository {
    private final JdbcTemplate jdbc;
    public StockBalanceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Page<StockBalanceDto> search(UUID tenantId, StockSearch filter, Pageable pageable) {
        StringBuilder predicate=new StringBuilder(" WHERE tenant_id=?");
        List<Object> args=new ArrayList<>();
        args.add(tenantId);
        if(filter.locationId()!=null) {
            predicate.append(" AND location_id=?");
            args.add(filter.locationId());
        }
        if(filter.itemId()!=null) {
            predicate.append(" AND item_id=?");
            args.add(filter.itemId());
        }
        if(filter.availableOnly())
            predicate.append(" AND on_hand > reserved");

        Long count=jdbc.queryForObject(
                "SELECT count(*) FROM crm.inv_stock_balance"+predicate,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        var rows=jdbc.query("""
            SELECT tenant_id,location_id,item_id,on_hand,reserved,row_version
            FROM crm.inv_stock_balance
            """+predicate+"""
             ORDER BY location_id,item_id LIMIT ? OFFSET ?
            """,(rs,n)->{
                var hand=rs.getBigDecimal(4);
                var reserved=rs.getBigDecimal(5);
                return new StockBalanceDto(
                        rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getObject(3,UUID.class),hand,reserved,hand.subtract(reserved),
                        rs.getLong(6));
            },pageArgs.toArray());
        return new PageImpl<>(rows,pageable,count==null?0:count);
    }
}
