package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Internal BUILDING-only L2 extension. No HTTP endpoint, public read or activation. */
@Service
public class L2ProjectionService {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final CanonicalFactDeliveryService delivery;
    public L2ProjectionService(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactDeliveryService delivery){
        this.jdbc=jdbc;this.manager=manager;this.delivery=delivery;
    }

    /** Attach an independent consumer to an existing immutable L1 generation scope. */
    public String open(UUID tenant,UUID generation){
        Objects.requireNonNull(tenant);Objects.requireNonNull(generation);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("L2_REQUIRES_OWN_TRANSACTION");
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(30);
        String code=ProjectionKind.L2.consumer(generation);
        return tx.execute(status->{
            if(!Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) con->
                con.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql"))))
                throw new IllegalStateException("L2_POSTGRESQL_REQUIRED");
            jdbc.update("INSERT INTO crm.int_fact_consumer(tenant_id,consumer_code) VALUES (?,?) ON CONFLICT DO NOTHING",tenant,code);
            jdbc.queryForObject("SELECT consumer_code FROM crm.int_fact_consumer WHERE tenant_id=? AND consumer_code=? FOR UPDATE",String.class,tenant,code);
            String state=jdbc.query("SELECT lifecycle_state FROM crm.int_projection_lifecycle WHERE tenant_id=? AND generation_id=? FOR SHARE",
                rs->rs.next()?rs.getString(1):null,tenant,generation);
            if(!"BUILDING".equals(state))throw new IllegalStateException("L2_GENERATION_NOT_BUILDING");
            boolean bound=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM crm.int_l2_consumer WHERE tenant_id=? AND generation_id=? AND consumer_code=?)",
                Boolean.class,tenant,generation,code));
            if(!bound){
                if(Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM crm.int_fact_consumer_receipt WHERE tenant_id=? AND consumer_code=?)
                      OR EXISTS(SELECT 1 FROM crm.int_dim_revision WHERE tenant_id=? AND generation_id=?)
                    """,Boolean.class,tenant,code,tenant,generation)))
                    throw new IllegalStateException("L2_PREEXISTING_EVIDENCE");
                jdbc.update("INSERT INTO crm.int_dim_revision(tenant_id,generation_id) VALUES (?,?)",tenant,generation);
                jdbc.update("INSERT INTO crm.int_l2_consumer(tenant_id,generation_id,consumer_code) VALUES (?,?,?)",tenant,generation,code);
            }
            return code;
        });
    }
    public List<CanonicalFactDeliveryService.Step> advance(UUID tenant,UUID generation,int limit){
        Objects.requireNonNull(tenant);Objects.requireNonNull(generation);
        return delivery.consumeProjectionBatch(tenant,ProjectionKind.L2,generation,limit,f->apply(tenant,generation,f));
    }
    private CanonicalFactDeliveryService.Effect apply(UUID tenant,UUID gen,CanonicalFactDeliveryService.Envelope f){
        if(!tenant.equals(f.key().tenantId()))throw new IllegalStateException("L2_FOREIGN_FACT");
        // Delivery already holds consumer and lifecycle locks. Lock revision BEFORE DML.
        jdbc.queryForObject("SELECT dirty_revision FROM crm.int_dim_revision WHERE tenant_id=? AND generation_id=? FOR UPDATE",Long.class,tenant,gen);
        switch(f.key().kind()){
            case ORDER_PLACED -> reconcile(tenant,gen,"BOOKED",f.subject(),null);
            case ORDER_COMPLETED -> reconcile(tenant,gen,"FULFILLED",f.subject(),null);
            case STOCK_MOVEMENT -> {
                reconcile(tenant,gen,"STOCK",null,f.key().sourceId());
                // A late fulfillment movement can complete an order already reconciled.
                UUID order=jdbc.query("""
                    SELECT r.order_id FROM crm.inv_stock_movement m JOIN crm.inv_stock_reservation r
                      ON r.tenant_id=m.tenant_id AND r.id=m.reservation_id
                    WHERE m.tenant_id=? AND m.id=? AND m.movement_kind='FULFILLMENT'
                    """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenant,f.key().sourceId());
                if(order!=null)reconcile(tenant,gen,"FULFILLED",order,null);
            }
            default -> {return CanonicalFactDeliveryService.Effect.OUTSIDE_COVERAGE;}
        }
        return CanonicalFactDeliveryService.Effect.APPLIED;
    }
    /** Replace only obsolete rows; identical replay never advances the dirty counter.
     * One statement gives the delete and upsert a shared owner-source snapshot. */
    private void reconcile(UUID tenant,UUID gen,String stream,UUID order,UUID source){
        jdbc.update("""
            WITH p AS (
              SELECT g.tenant_id,g.generation_id,g.from_day,g.until_day,c.zone_id,
                CAST(? AS varchar) stream,CAST(? AS uuid) order_key,CAST(? AS uuid) source_key
              FROM crm.int_projection_generation g JOIN crm.int_calendar_policy c
                ON c.tenant_id=g.tenant_id AND c.version=g.calendar_version
              WHERE g.tenant_id=? AND g.generation_id=?
            ), expected AS MATERIALIZED (
              SELECT e.tenant_id,p.generation_id,e.stream_code,e.source_id,e.line_id,e.movement_id,e.order_id,
                (e.event_at AT TIME ZONE p.zone_id)::date local_day,e.region_code,e.group_id,e.item_kind,
                e.warehouse_id,e.sku_id,e.currency,e.amount,e.qty_in,e.qty_out,e.product_order_units
              FROM crm.int_l2_expected_source e,p
              WHERE e.tenant_id=p.tenant_id AND e.stream_code=p.stream
                AND ((p.order_key IS NOT NULL AND e.order_id=p.order_key) OR e.source_id=p.source_key)
                AND (e.event_at AT TIME ZONE p.zone_id)::date>=p.from_day
                AND (e.event_at AT TIME ZONE p.zone_id)::date<p.until_day
            ), removed AS (
              DELETE FROM crm.int_l2_contribution old USING p
              WHERE old.tenant_id=p.tenant_id AND old.generation_id=p.generation_id AND old.stream_code=p.stream
                AND ((p.order_key IS NOT NULL AND old.order_id=p.order_key) OR old.source_id=p.source_key)
                AND NOT EXISTS(SELECT 1 FROM expected e WHERE e.source_id=old.source_id)
            )
            INSERT INTO crm.int_l2_contribution AS current_row
              (tenant_id,generation_id,stream_code,source_id,line_id,movement_id,order_id,local_day,region_code,
               group_id,item_kind,warehouse_id,sku_id,currency,amount,qty_in,qty_out,product_order_units)
            SELECT tenant_id,generation_id,stream_code,source_id,line_id,movement_id,order_id,local_day,region_code,
               group_id,item_kind,warehouse_id,sku_id,currency,amount,qty_in,qty_out,product_order_units FROM expected
            ON CONFLICT (tenant_id,generation_id,stream_code,source_id) DO UPDATE SET
               line_id=excluded.line_id,movement_id=excluded.movement_id,order_id=excluded.order_id,
               local_day=excluded.local_day,region_code=excluded.region_code,group_id=excluded.group_id,
               item_kind=excluded.item_kind,warehouse_id=excluded.warehouse_id,sku_id=excluded.sku_id,
               currency=excluded.currency,amount=excluded.amount,qty_in=excluded.qty_in,qty_out=excluded.qty_out,product_order_units=excluded.product_order_units
            WHERE (current_row.line_id,current_row.movement_id,current_row.order_id,current_row.local_day,
               current_row.region_code,current_row.group_id,current_row.item_kind,current_row.warehouse_id,
               current_row.sku_id,current_row.currency,current_row.amount,current_row.qty_in,current_row.qty_out,current_row.product_order_units)
               IS DISTINCT FROM
              (excluded.line_id,excluded.movement_id,excluded.order_id,excluded.local_day,excluded.region_code,
               excluded.group_id,excluded.item_kind,excluded.warehouse_id,excluded.sku_id,excluded.currency,
               excluded.amount,excluded.qty_in,excluded.qty_out,excluded.product_order_units)
            """,stream,order,source,tenant,gen);
    }
}
