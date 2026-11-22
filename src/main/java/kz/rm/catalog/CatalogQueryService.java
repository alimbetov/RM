package kz.rm.catalog;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Verified read boundary for tenant-scoped catalog and effective price history. */
@Service
public class CatalogQueryService {
    public record Price(UUID id,BigDecimal amount,String currency,Instant validFrom) {}
    public record Details(CatalogItemService.Item item,String sourceName,Price effectivePrice) {}
    public record Event(UUID id,String action,long revision,Instant occurredAt) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    public CatalogQueryService(JdbcTemplate jdbc,TenantAuthorization authorization) {
        this.jdbc=jdbc;this.authorization=authorization;
    }

    private void read(VerifiedActor actor,UUID tenant) {
        if(actor==null||tenant==null)throw new SecurityException("Verified actor and tenant required");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.CATALOG_READ);
    }

    @Transactional
    public Details get(VerifiedActor actor,UUID tenant,UUID itemId,Instant asOf) {
        read(actor,tenant);
        if(itemId==null||asOf==null)throw new IllegalArgumentException("Item/asOf required");
        var item=jdbc.query("""
                SELECT tenant_id,id,sku,model_code,kind,source_locale,state,row_version
                FROM crm.cat_item WHERE tenant_id=? AND id=?
                """,(rs,n)->CatalogItemService.map(rs),tenant,itemId);
        if(item.isEmpty())throw new IllegalArgumentException("Catalog item not found in tenant");
        String name=jdbc.query("""
                SELECT name FROM crm.cat_item_i18n
                WHERE tenant_id=? AND item_id=? AND locale=?
                """,rs->rs.next()?rs.getString(1):null,tenant,itemId,item.getFirst().sourceLocale());
        return new Details(item.getFirst(),name,effective(tenant,itemId,asOf));
    }

    @Transactional
    public Page<CatalogItemService.Item> search(VerifiedActor actor,UUID tenant,
                CatalogItem.Kind kind,CatalogItem.State state,String sku,String modelCode,
                int page,int size) {
        read(actor,tenant);
        if(page<0||size<1||size>100)throw new IllegalArgumentException("Invalid catalog page");
        var where=new StringBuilder(" WHERE tenant_id=?");
        var args=new ArrayList<Object>(List.of(tenant));
        if(kind!=null){where.append(" AND kind=?");args.add(kind.name());}
        if(state!=null){where.append(" AND state=?");args.add(state.name());}
        if(sku!=null){where.append(" AND sku=?");args.add(sku);}
        if(modelCode!=null){where.append(" AND model_code=?");args.add(modelCode);}
        Long count=jdbc.queryForObject("SELECT count(*) FROM crm.cat_item"+where,
                Long.class,args.toArray());
        var paging=new ArrayList<>(args);
        paging.add(size);
        paging.add((long)page*size);
        var result=jdbc.query("""
                SELECT tenant_id,id,sku,model_code,kind,source_locale,state,row_version
                FROM crm.cat_item
                """+where+"""
                 ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """,(rs,n)->CatalogItemService.map(rs),paging.toArray());
        return new PageImpl<>(result,PageRequest.of(page,size),count==null?0:count);
    }

    @Transactional
    public Page<Price> priceHistory(VerifiedActor actor,UUID tenant,UUID itemId,
                                    int page,int size) {
        read(actor,tenant);
        if(itemId==null||page<0||size<1||size>100)
            throw new IllegalArgumentException("Invalid price history");
        Integer exists=jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_item WHERE tenant_id=? AND id=?
                """,Integer.class,tenant,itemId);
        if(exists==null||exists==0)throw new IllegalArgumentException("Catalog item not found");
        Long count=jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_price WHERE tenant_id=? AND item_id=?
                """,Long.class,tenant,itemId);
        var rows=jdbc.query("""
                SELECT id,unit_price,currency,valid_from FROM crm.cat_price
                WHERE tenant_id=? AND item_id=?
                ORDER BY valid_from DESC,created_at DESC,id DESC LIMIT ? OFFSET ?
                """,(rs,n)->price(rs),tenant,itemId,size,(long)page*size);
        return new PageImpl<>(rows,PageRequest.of(page,size),count==null?0L:count);
    }

    @Transactional
    public List<Event> history(VerifiedActor actor,UUID tenant,UUID itemId,int limit) {
        read(actor,tenant);
        if(itemId==null||limit<1||limit>100)throw new IllegalArgumentException("Invalid history");
        return jdbc.query("""
                SELECT id,action_code,new_version,occurred_at FROM crm.cat_item_event
                WHERE tenant_id=? AND item_id=?
                ORDER BY occurred_at DESC,id DESC LIMIT ?
                """,(rs,n)->new Event(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getLong(3),rs.getTimestamp(4).toInstant()),tenant,itemId,limit);
    }

    private Price effective(UUID tenant,UUID itemId,Instant asOf) {
        return jdbc.query("""
                SELECT id,unit_price,currency,valid_from FROM crm.cat_price
                WHERE tenant_id=? AND item_id=? AND valid_from<=?
                ORDER BY valid_from DESC,created_at DESC,id DESC LIMIT 1
                """,rs->rs.next()?price(rs):null,tenant,itemId,Timestamp.from(asOf));
    }

    private static Price price(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Price(rs.getObject(1,UUID.class),rs.getBigDecimal(2),rs.getString(3),
                rs.getTimestamp(4).toInstant());
    }
}
