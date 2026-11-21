package kz.rm.sales;

import kz.rm.sales.dto.SalesOrderDto;
import kz.rm.sales.dto.SalesOrderSearch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Seller tenant-scoped read model. No buyer PII in the projection. */
@Repository
class SalesOrderRepository {
    private final JdbcTemplate jdbc;
    public SalesOrderRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public Page<SalesOrderDto> search(UUID tenantId, SalesOrderSearch search, Pageable pageable) {
        StringBuilder filters=new StringBuilder(" WHERE tenant_id=?");
        List<Object> args=new ArrayList<>();
        args.add(tenantId);
        if(search.status()!=null) {
            filters.append(" AND status=?");
            args.add(search.status());
        }
        if(search.fromInclusive()!=null) {
            filters.append(" AND created_at>=?");
            args.add(Timestamp.from(search.fromInclusive()));
        }
        if(search.toExclusive()!=null) {
            filters.append(" AND created_at<?");
            args.add(Timestamp.from(search.toExclusive()));
        }
        Long count=jdbc.queryForObject("SELECT count(*) FROM crm.sales_order"+filters,
                Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        var content=jdbc.query("""
            SELECT id,status,total_amount,currency,created_at,row_version
            FROM crm.sales_order
            """+filters+"""
             ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
            """,(rs,n)->new SalesOrderDto(
                rs.getObject(1,UUID.class),rs.getString(2),rs.getBigDecimal(3),
                rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getLong(6)),
                pageArgs.toArray());
        return new PageImpl<>(content,pageable,count==null?0:count);
    }
}
