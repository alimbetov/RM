package kz.rm.sales;

import kz.rm.sales.dto.SalesOrderDto;
import kz.rm.sales.dto.SalesOrderDetail;
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

    public SalesOrderDetail detail(UUID tenant, UUID orderId) {
        List<SalesOrderDto> headers=jdbc.query("""
                SELECT id,status,total_amount,currency,created_at,row_version
                FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,(rs,n)->new SalesOrderDto(rs.getObject(1,UUID.class),
                rs.getString(2),rs.getBigDecimal(3),rs.getString(4),
                rs.getTimestamp(5).toInstant(),rs.getLong(6)),tenant,orderId);
        if(headers.isEmpty()) throw new IllegalArgumentException("Order not found in tenant");
        var lines=jdbc.query("""
                SELECT id,offer_id,catalog_item_id_snapshot,title_snapshot,
                       quantity,unit_price_snapshot,currency
                FROM crm.sales_order_line
                WHERE tenant_id=? AND order_id=?
                ORDER BY id
                """,(rs,n)->new SalesOrderDetail.Line(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getString(4),rs.getInt(5),rs.getBigDecimal(6),rs.getString(7)),
                tenant,orderId);
        return new SalesOrderDetail(headers.getFirst(),List.copyOf(lines));
    }

    public List<SalesOrderDetail.StatusEvent> history(UUID tenant,UUID orderId,
                                                        int page,int size) {
        return jdbc.query("""
                SELECT id,event_type,occurred_at
                FROM crm.sales_order_status_event
                WHERE tenant_id=? AND order_id=?
                ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?
                """,(rs,n)->new SalesOrderDetail.StatusEvent(rs.getObject(1,UUID.class),
                rs.getString(2),rs.getTimestamp(3).toInstant()),
                tenant,orderId,size,(long)page*size);
    }

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
