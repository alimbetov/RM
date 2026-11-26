package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.HashSet;
import java.util.UUID;

/** Operation-time snapshots only. Historical backfill must never use today's master data. */
final class LiveDimensionCapture {
    private LiveDimensionCapture(){}
    private record Territory(long source,Long parent,String code,int level,boolean deleted,String type){}
    record Geo(String region,String quality){}
    static Geo resolve(JdbcTemplate jdbc,String code){
        if(code==null||!code.matches("[0-9]{9}"))return new Geo(null,"UNKNOWN");
        Territory node=jdbc.query("""
            SELECT source_id,parent_source_id,code,level_no,is_deleted,territory_type
            FROM public.ref_kato_territory WHERE code=?
            """,rs->rs.next()?new Territory(rs.getLong(1),rs.getObject(2,Long.class),rs.getString(3),
                rs.getInt(4),rs.getBoolean(5),rs.getString(6)):null,code);
        var seen=new HashSet<Long>();
        int previousLevel=Integer.MAX_VALUE;
        for(int depth=0;node!=null&&depth<32;depth++){
            if(node.deleted()||!seen.add(node.source())||node.level()>=previousLevel
                ||!node.code().startsWith(code.substring(0,2)))break;
            if("OBLAST".equals(node.type())||"REPUBLICAN_CITY".equals(node.type())){
                if(node.parent()==null)return new Geo(node.code(),"RESOLVED");
                break;
            }
            if(node.parent()==null)break;
            previousLevel=node.level();
            node=jdbc.query("""
                SELECT source_id,parent_source_id,code,level_no,is_deleted,territory_type
                FROM public.ref_kato_territory WHERE source_id=?
                """,rs->rs.next()?new Territory(rs.getLong(1),rs.getObject(2,Long.class),rs.getString(3),
                    rs.getInt(4),rs.getBoolean(5),rs.getString(6)):null,node.parent());
        }
        return new Geo(null,"UNKNOWN");
    }
    static void capture(JdbcTemplate jdbc,CanonicalFactKey key){
        if(key.kind()==CanonicalFactKey.Kind.ORDER_PLACED){
            // One row per booked merchandise line, with exact assignment revision.
            jdbc.update("""
                INSERT INTO crm.int_sales_line_dimension(tenant_id,line_id,item_id,item_kind,group_id,assignment_revision)
                SELECT l.tenant_id,l.id,i.id,i.kind,a.group_id,a.revision
                FROM crm.sales_order_status_event e
                JOIN crm.sales_order_line l ON l.tenant_id=e.tenant_id AND l.order_id=e.order_id
                LEFT JOIN crm.cat_item i ON i.tenant_id=l.tenant_id AND i.id=l.catalog_item_id_snapshot
                LEFT JOIN crm.cat_group_assignment a ON a.tenant_id=i.tenant_id AND a.item_id=i.id
                  AND a.revision=(SELECT max(x.revision) FROM crm.cat_group_assignment x WHERE x.tenant_id=i.tenant_id AND x.item_id=i.id)
                WHERE e.tenant_id=? AND e.id=? AND e.event_type='PLACED'
                """,key.tenantId(),key.sourceId());
        }else if(key.kind()==CanonicalFactKey.Kind.STOCK_MOVEMENT){
            record Movement(UUID item,String kind,UUID group,Long revision,UUID location,String kato){}
            Movement m=jdbc.query("""
                SELECT i.id,i.kind,a.group_id,a.revision,m.location_id,l.kato_code
                FROM crm.inv_stock_movement m
                JOIN crm.cat_item i ON i.tenant_id=m.tenant_id AND i.id=m.item_id
                JOIN public.org_location l ON l.workspace_id=m.tenant_id AND l.id=m.location_id
                LEFT JOIN crm.cat_group_assignment a ON a.tenant_id=i.tenant_id AND a.item_id=i.id
                  AND a.revision=(SELECT max(x.revision) FROM crm.cat_group_assignment x WHERE x.tenant_id=i.tenant_id AND x.item_id=i.id)
                WHERE m.tenant_id=? AND m.id=?
                """,rs->rs.next()?new Movement(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),
                    rs.getObject(4,Long.class),rs.getObject(5,UUID.class),rs.getString(6)):null,key.tenantId(),key.sourceId());
            if(m==null)throw new IllegalStateException("DIMENSION_MOVEMENT_SOURCE_NOT_FOUND");
            Geo geo=resolve(jdbc,m.kato());
            jdbc.update("""
                INSERT INTO crm.int_movement_dimension(tenant_id,movement_id,item_id,item_kind,group_id,assignment_revision,
                   location_id,kato_code,region_code,geo_quality) VALUES (?,?,?,?,?,?,?,?,?,?)
                """,key.tenantId(),key.sourceId(),m.item(),m.kind(),m.group(),m.revision(),m.location(),m.kato(),geo.region(),geo.quality());
        }else throw new IllegalArgumentException("DIMENSION_LIVE_KIND");
    }
}
