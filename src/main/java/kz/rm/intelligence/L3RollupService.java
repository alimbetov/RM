package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** Internal BUILDING-only rollup. A matching stamp is L2-relative, not source coverage or activation. */
@Service
public class L3RollupService {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    public L3RollupService(JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.manager=manager;
    }
    private record Scope(LocalDate from,LocalDate until,long calendar,ZoneId zone){}
    public record Refresh(PeriodResolver.Code code,LocalDate from,LocalDate until,long revision,int cells,int memberships){}
    public Refresh refresh(UUID tenant,UUID generation,PeriodResolver.Code code,LocalDate anchor){
        return refreshInternal(tenant,generation,code,anchor,false).orElseThrow();
    }
    public java.util.Optional<Refresh> refreshIfStale(UUID tenant,UUID generation,PeriodResolver.Code code,LocalDate anchor){
        return refreshInternal(tenant,generation,code,anchor,true);
    }
    private java.util.Optional<Refresh> refreshInternal(UUID tenant,UUID generation,PeriodResolver.Code code,LocalDate anchor,boolean conditional){
        Objects.requireNonNull(tenant);Objects.requireNonNull(generation);Objects.requireNonNull(code);Objects.requireNonNull(anchor);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("L3_REQUIRES_OWN_TRANSACTION");
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(60);
        return tx.execute(status->{
            if(!Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c->
                c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql"))))
                throw new IllegalStateException("L3_POSTGRESQL_REQUIRED");
            Scope scope=jdbc.query("""
                SELECT g.from_day,g.until_day,g.calendar_version,p.zone_id
                FROM crm.int_projection_generation g
                JOIN crm.int_projection_lifecycle l USING(tenant_id,generation_id)
                JOIN crm.int_l2_consumer b USING(tenant_id,generation_id)
                JOIN crm.int_calendar_policy p ON p.tenant_id=g.tenant_id AND p.version=g.calendar_version
                WHERE g.tenant_id=? AND g.generation_id=? AND l.lifecycle_state='BUILDING' FOR SHARE OF l
                """,rs->rs.next()?new Scope(rs.getObject(1,LocalDate.class),rs.getObject(2,LocalDate.class),
                    rs.getLong(3),ZoneId.of(rs.getString(4))):null,tenant,generation);
            if(scope==null)throw new IllegalStateException("L3_GENERATION_NOT_BUILDING");
            var period=PeriodResolver.resolve(code,anchor,new PeriodResolver.Policy(scope.zone(),scope.calendar()));
            LocalDate from=period.startInclusive().atZone(scope.zone()).toLocalDate();
            LocalDate until=period.endExclusive().atZone(scope.zone()).toLocalDate();
            if(from.isBefore(scope.from())||until.isAfter(scope.until()))
                throw new IllegalArgumentException("L3_PARTIAL_PERIOD");
            long revision=jdbc.queryForObject("SELECT dirty_revision FROM crm.int_dim_revision WHERE tenant_id=? AND generation_id=? FOR UPDATE",Long.class,tenant,generation);
            String c=code.name();
            if(conditional&&Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM crm.int_l3_period WHERE tenant_id=? AND generation_id=?
                  AND period_code=? AND from_day=? AND until_day=? AND refreshed_revision=?)
                """,Boolean.class,tenant,generation,c,from,until,revision)))return java.util.Optional.empty();
            jdbc.update("""
                INSERT INTO crm.int_l3_period(tenant_id,generation_id,period_code,from_day,until_day,refreshed_revision)
                VALUES (?,?,?,?,?,NULL)
                ON CONFLICT(tenant_id,generation_id,period_code,from_day)
                DO UPDATE SET refreshed_revision=NULL
                """,tenant,generation,c,from,until);
            if(!until.equals(jdbc.queryForObject("""
                SELECT until_day FROM crm.int_l3_period WHERE tenant_id=? AND generation_id=? AND period_code=? AND from_day=?
                """,LocalDate.class,tenant,generation,c,from)))throw new IllegalStateException("L3_PERIOD_SCOPE_CONFLICT");
            jdbc.update("DELETE FROM crm.int_l3_cell_order WHERE tenant_id=? AND generation_id=? AND period_code=? AND from_day=?",tenant,generation,c,from);
            jdbc.update("DELETE FROM crm.int_l3_cell WHERE tenant_id=? AND generation_id=? AND period_code=? AND from_day=?",tenant,generation,c,from);
            int cells=jdbc.update("""
                INSERT INTO crm.int_l3_cell(tenant_id,generation_id,period_code,from_day,cell_id,stream_code,
                  region_code,group_id,item_kind,warehouse_id,sku_id,currency,amount,qty_in,qty_out,distinct_orders,product_order_units)
                SELECT tenant_id,generation_id,?,CAST(? AS date),gen_random_uuid(),stream_code,
                  region_code,group_id,item_kind,warehouse_id,sku_id,currency,
                  sum(amount),sum(qty_in),sum(qty_out),count(DISTINCT order_id),sum(product_order_units)
                FROM crm.int_l2_contribution WHERE tenant_id=? AND generation_id=? AND local_day>=? AND local_day<?
                GROUP BY tenant_id,generation_id,stream_code,region_code,group_id,item_kind,warehouse_id,sku_id,currency
                """,c,from,tenant,generation,from,until);
            int memberships=jdbc.update("""
                INSERT INTO crm.int_l3_cell_order(tenant_id,generation_id,period_code,from_day,cell_id,order_id)
                SELECT DISTINCT x.tenant_id,x.generation_id,x.period_code,x.from_day,x.cell_id,l.order_id
                FROM crm.int_l3_cell x JOIN crm.int_l2_contribution l
                  ON l.tenant_id=x.tenant_id AND l.generation_id=x.generation_id
                  AND (l.stream_code,l.region_code,l.group_id,l.item_kind,l.warehouse_id,l.sku_id,l.currency)
                    IS NOT DISTINCT FROM (x.stream_code,x.region_code,x.group_id,x.item_kind,x.warehouse_id,x.sku_id,x.currency)
                WHERE x.tenant_id=? AND x.generation_id=? AND x.period_code=? AND x.from_day=?
                  AND l.local_day>=? AND l.local_day<? AND l.order_id IS NOT NULL
                """,tenant,generation,c,from,from,until);
            // Cardinality is an internal consistency check; independent L0 parity remains mandatory.
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM crm.int_l3_cell x
                  WHERE x.tenant_id=? AND x.generation_id=? AND x.period_code=? AND x.from_day=?
                  AND x.distinct_orders<>(SELECT count(*) FROM crm.int_l3_cell_order o
                    WHERE o.tenant_id=x.tenant_id AND o.generation_id=x.generation_id
                      AND o.period_code=x.period_code AND o.from_day=x.from_day AND o.cell_id=x.cell_id))
                """,Boolean.class,tenant,generation,c,from)))throw new IllegalStateException("L3_MEMBERSHIP_CARDINALITY");
            if(jdbc.update("""
                UPDATE crm.int_l3_period SET refreshed_revision=?
                WHERE tenant_id=? AND generation_id=? AND period_code=? AND from_day=?
                """,revision,tenant,generation,c,from)!=1)throw new IllegalStateException("L3_STAMP_MISSING");
            return java.util.Optional.of(new Refresh(code,from,until,revision,cells,memberships));
        });
    }
}
