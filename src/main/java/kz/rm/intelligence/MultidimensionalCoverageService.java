package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Internal evidence only. No authorization shortcut, active pointer or production admission. */
@Service
public class MultidimensionalCoverageService {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    public MultidimensionalCoverageService(JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.manager=manager;
    }
    public record Revisions(long source,long dirty,long receipt,long materialization,long lifecycle){}
    public record Proof(UUID id,Revisions revisions,long delivery,long contributions,long periods,long cells,long memberships){
        public boolean parity(){return delivery==0&&contributions==0&&periods==0&&cells==0&&memberships==0;}
    }
    public enum Status { UNPROVEN,INCOMPLETE,STALE,ABORTED,CURRENT }
    private record Scope(LocalDate from,LocalDate until,ZoneId zone,long calendar,String consumer,String state,Revisions revisions){}
    private record PeriodKey(String code,LocalDate from,LocalDate until){}
    private record PeriodRow(PeriodKey key,Long revision){}
    private <T>T tx(Supplier<T> work){
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("INT_DIM_PROOF_REQUIRES_OWN_TRANSACTION");
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(120);
        return tx.execute(status->{
            if(!Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c->
                c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql"))))
                throw new IllegalStateException("INT_DIM_PROOF_POSTGRESQL_REQUIRED");
            return work.get();
        });
    }
    private Scope scope(UUID tenant,UUID gen){
        Scope s=jdbc.query("""
            SELECT g.from_day,g.until_day,p.zone_id,g.calendar_version,b.consumer_code,l.lifecycle_state,
              coalesce(r.revision,0)+(SELECT count(*) FROM crm.int_source_revision_event e WHERE e.tenant_id=g.tenant_id),
              d.dirty_revision,d.receipt_revision,d.materialization_revision,l.revision
            FROM crm.int_projection_generation g
            JOIN crm.int_calendar_policy p ON p.tenant_id=g.tenant_id AND p.version=g.calendar_version
            JOIN crm.int_projection_lifecycle l ON l.tenant_id=g.tenant_id AND l.generation_id=g.generation_id
            JOIN crm.int_l2_consumer b ON b.tenant_id=g.tenant_id AND b.generation_id=g.generation_id
            JOIN crm.int_dim_revision d ON d.tenant_id=g.tenant_id AND d.generation_id=g.generation_id
            LEFT JOIN crm.int_source_revision r ON r.tenant_id=g.tenant_id
            WHERE g.tenant_id=? AND g.generation_id=?
            """,rs->rs.next()?new Scope(rs.getObject(1,LocalDate.class),rs.getObject(2,LocalDate.class),
                ZoneId.of(rs.getString(3)),rs.getLong(4),rs.getString(5),rs.getString(6),
                new Revisions(rs.getLong(7),rs.getLong(8),rs.getLong(9),rs.getLong(10),rs.getLong(11))):null,tenant,gen);
        if(s==null)throw new IllegalArgumentException("INT_DIM_GENERATION_NOT_FOUND");
        return s;
    }
    private long count(String sql,Object... args){return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args));}
    // Whole-tenant owner/fact/receipt identity coverage, independent of the L2 producer.
    private long delivery(UUID tenant,String consumer){
        return count("""
            WITH p AS (SELECT CAST(? AS uuid) t,CAST(? AS varchar) c),
            owners AS (
              SELECT 'SALES' owner_module,'ORDER_'||event_type source_kind,id source_id,order_id subject_id,
                occurred_at,NULL::timestamptz recorded_at,NULL::bigint source_version
              FROM crm.sales_order_status_event,p WHERE tenant_id=p.t
                AND event_type IN ('PLACED','CONFIRMED','COMPLETED','CANCELLED')
              UNION ALL
              SELECT 'INVENTORY','STOCK_MOVEMENT',id,item_id,occurred_at,recorded_at,posting_version
              FROM crm.inv_stock_movement,p WHERE tenant_id=p.t
            ), facts AS (SELECT f.* FROM crm.int_canonical_fact f,p WHERE f.tenant_id=p.t),
            receipts AS (SELECT r.* FROM crm.int_fact_consumer_receipt r,p WHERE r.tenant_id=p.t AND r.consumer_code=p.c)
            SELECT (SELECT count(*) FROM owners o WHERE NOT EXISTS(
                SELECT 1 FROM facts f WHERE (f.owner_module,f.source_kind,f.source_id)=(o.owner_module,o.source_kind,o.source_id)
                AND f.subject_id=o.subject_id AND f.occurred_at=o.occurred_at AND f.recorded_at IS NOT DISTINCT FROM o.recorded_at
                AND (f.schema_version=1 OR f.source_version IS NOT DISTINCT FROM o.source_version)))
              +(SELECT count(*) FROM facts f WHERE NOT EXISTS(SELECT 1 FROM owners o
                  WHERE (f.owner_module,f.source_kind,f.source_id)=(o.owner_module,o.source_kind,o.source_id)))
              +(SELECT count(*) FROM facts f WHERE NOT EXISTS(SELECT 1 FROM receipts r
                  WHERE (f.owner_module,f.source_kind,f.source_id)=(r.owner_module,r.source_kind,r.source_id)))
              +(SELECT count(*) FROM receipts r LEFT JOIN facts f USING(owner_module,source_kind,source_id)
                  WHERE f.source_id IS NULL OR r.digest<>f.digest OR r.schema_version<>f.schema_version
                     OR r.disposition NOT IN ('APPLIED','OUTSIDE_COVERAGE'))
            """,tenant,consumer);
    }
    // Independent owner query: use unique-event anti-join, not producer's grouped eligible view.
    private long contributions(UUID tenant,UUID gen,Scope s){
        return count("""
            WITH p AS (SELECT CAST(? AS uuid) t,CAST(? AS uuid) g,CAST(? AS varchar) z,CAST(? AS date) a,CAST(? AS date) b),
            unique_events AS (
              SELECT e.* FROM crm.sales_order_status_event e,p
              WHERE e.tenant_id=p.t AND e.event_type IN ('PLACED','COMPLETED')
                AND NOT EXISTS(SELECT 1 FROM crm.sales_order_status_event other
                   WHERE other.tenant_id=e.tenant_id AND other.order_id=e.order_id
                     AND other.event_type=e.event_type AND other.id<>e.id)
            ), owner_rows AS (
              SELECT 'BOOKED'::varchar stream_code,l.id source_id,l.id line_id,NULL::uuid movement_id,l.order_id,
                CAST(e.occurred_at AT TIME ZONE p.z AS date) local_day,NULL::varchar region_code,
                d.group_id,d.item_kind,NULL::uuid warehouse_id,l.catalog_item_id_snapshot sku_id,
                trim(l.currency)::varchar currency,l.unit_price_snapshot*l.quantity amount,0::numeric qty_in,0::numeric qty_out
              FROM p,unique_events e JOIN crm.sales_order_line l ON l.tenant_id=e.tenant_id AND l.order_id=e.order_id
              LEFT JOIN crm.int_sales_line_dimension d ON d.tenant_id=l.tenant_id AND d.line_id=l.id
              WHERE e.event_type='PLACED'
              UNION ALL
              SELECT 'FULFILLED',m.id,l.id,m.id,l.order_id,CAST(e.occurred_at AT TIME ZONE p.z AS date),
                md.region_code,d.group_id,d.item_kind,m.location_id,m.item_id,trim(l.currency),
                abs(m.delta_quantity)*l.unit_price_snapshot,0::numeric,0::numeric
              FROM p,crm.inv_stock_movement m
              JOIN crm.inv_stock_reservation r ON r.tenant_id=m.tenant_id AND r.id=m.reservation_id
                AND r.location_id=m.location_id AND r.item_id=m.item_id
              JOIN crm.sales_order_line l ON l.tenant_id=r.tenant_id AND l.id=r.order_line_id AND l.order_id=r.order_id
              JOIN unique_events e ON e.tenant_id=l.tenant_id AND e.order_id=l.order_id AND e.event_type='COMPLETED'
              LEFT JOIN crm.int_sales_line_dimension d ON d.tenant_id=l.tenant_id AND d.line_id=l.id
              LEFT JOIN crm.int_movement_dimension md ON md.tenant_id=m.tenant_id AND md.movement_id=m.id
              WHERE m.tenant_id=p.t AND m.movement_kind='FULFILLMENT'
              UNION ALL
              SELECT 'STOCK',m.id,NULL::uuid,m.id,NULL::uuid,CAST(m.recorded_at AT TIME ZONE p.z AS date),
                d.region_code,d.group_id,d.item_kind,m.location_id,m.item_id,NULL::varchar,0::numeric,
                CASE WHEN m.delta_quantity>0 THEN m.delta_quantity ELSE 0 END,
                CASE WHEN m.delta_quantity<0 THEN -m.delta_quantity ELSE 0 END
              FROM p,crm.inv_stock_movement m LEFT JOIN crm.int_movement_dimension d
                ON d.tenant_id=m.tenant_id AND d.movement_id=m.id WHERE m.tenant_id=p.t
            ), expected AS (SELECT o.* FROM owner_rows o,p WHERE o.local_day>=p.a AND o.local_day<p.b),
            actual AS (
              SELECT stream_code,source_id,line_id,movement_id,order_id,local_day,region_code,group_id,item_kind,
                warehouse_id,sku_id,currency,amount,qty_in,qty_out
              FROM crm.int_l2_contribution x,p WHERE x.tenant_id=p.t AND x.generation_id=p.g
            )
            SELECT (SELECT count(*) FROM (SELECT * FROM expected EXCEPT ALL SELECT * FROM actual) x)
                  +(SELECT count(*) FROM (SELECT * FROM actual EXCEPT ALL SELECT * FROM expected) x)
            """,tenant,gen,s.zone().getId(),s.from(),s.until());
    }
    // Full periods contained in the immutable generation, including empty periods.
    private static Set<PeriodKey> manifest(Scope s){
        Set<PeriodKey> result=new HashSet<>();
        for(var code:PeriodResolver.Code.values()){
            LocalDate day=s.from();
            while(day.isBefore(s.until())){
                var period=PeriodResolver.resolve(code,day,new PeriodResolver.Policy(s.zone(),s.calendar()));
                LocalDate a=period.startInclusive().atZone(s.zone()).toLocalDate();
                LocalDate b=period.endExclusive().atZone(s.zone()).toLocalDate();
                if(!b.isAfter(day))throw new IllegalStateException("INT_DIM_CALENDAR_PROGRESS");
                if(!a.isBefore(s.from())&&!b.isAfter(s.until()))result.add(new PeriodKey(code.name(),a,b));
                day=b;
            }
        }
        return result;
    }
    private long periods(UUID tenant,UUID gen,Scope s){
        Set<PeriodKey> expected=manifest(s);
        var rows=jdbc.query("""
            SELECT period_code,from_day,until_day,refreshed_revision FROM crm.int_l3_period
            WHERE tenant_id=? AND generation_id=?
            """,(rs,n)->new PeriodRow(new PeriodKey(rs.getString(1),rs.getObject(2,LocalDate.class),
                rs.getObject(3,LocalDate.class)),rs.getObject(4,Long.class)),tenant,gen);
        long bad=0;
        for(var row:rows){
            if(!expected.remove(row.key()))bad++;
            if(!Objects.equals(row.revision(),s.revisions().dirty()))bad++;
        }
        return bad+expected.size();
    }
    private long cells(UUID tenant,UUID gen){
        return count("""
            WITH p AS (SELECT CAST(? AS uuid) t,CAST(? AS uuid) g),
            expected AS (
              SELECT r.period_code,r.from_day,l.stream_code,l.region_code,l.group_id,l.item_kind,l.warehouse_id,l.sku_id,l.currency,
                sum(l.amount) amount,sum(l.qty_in) qty_in,sum(l.qty_out) qty_out,count(DISTINCT l.order_id) distinct_orders
              FROM crm.int_l3_period r JOIN crm.int_l2_contribution l ON l.tenant_id=r.tenant_id AND l.generation_id=r.generation_id
                AND l.local_day>=r.from_day AND l.local_day<r.until_day,p
              WHERE r.tenant_id=p.t AND r.generation_id=p.g
              GROUP BY r.period_code,r.from_day,l.stream_code,l.region_code,l.group_id,l.item_kind,l.warehouse_id,l.sku_id,l.currency
            ), actual AS (
              SELECT period_code,from_day,stream_code,region_code,group_id,item_kind,warehouse_id,sku_id,currency,
                amount,qty_in,qty_out,distinct_orders FROM crm.int_l3_cell x,p WHERE x.tenant_id=p.t AND x.generation_id=p.g
            )
            SELECT (SELECT count(*) FROM (SELECT * FROM expected EXCEPT ALL SELECT * FROM actual) x)
                  +(SELECT count(*) FROM (SELECT * FROM actual EXCEPT ALL SELECT * FROM expected) x)
            """,tenant,gen);
    }
    private long memberships(UUID tenant,UUID gen){
        return count("""
            WITH p AS (SELECT CAST(? AS uuid) t,CAST(? AS uuid) g),
            expected AS (
              SELECT DISTINCT r.period_code,r.from_day,l.stream_code,l.region_code,l.group_id,l.item_kind,l.warehouse_id,l.sku_id,l.currency,l.order_id
              FROM crm.int_l3_period r JOIN crm.int_l2_contribution l ON l.tenant_id=r.tenant_id AND l.generation_id=r.generation_id
                AND l.local_day>=r.from_day AND l.local_day<r.until_day,p
              WHERE r.tenant_id=p.t AND r.generation_id=p.g AND l.order_id IS NOT NULL
            ), actual AS (
              SELECT x.period_code,x.from_day,x.stream_code,x.region_code,x.group_id,x.item_kind,x.warehouse_id,x.sku_id,x.currency,o.order_id
              FROM crm.int_l3_cell x JOIN crm.int_l3_cell_order o USING(tenant_id,generation_id,period_code,from_day,cell_id),p
              WHERE x.tenant_id=p.t AND x.generation_id=p.g
            )
            SELECT (SELECT count(*) FROM (SELECT * FROM expected EXCEPT ALL SELECT * FROM actual) x)
                  +(SELECT count(*) FROM (SELECT * FROM actual EXCEPT ALL SELECT * FROM expected) x)
            """,tenant,gen);
    }
    public Proof prove(UUID tenant,UUID gen){
        Objects.requireNonNull(tenant);Objects.requireNonNull(gen);
        return tx(()->{
            Scope s=scope(tenant,gen);
            if(!"BUILDING".equals(s.state()))throw new IllegalStateException("INT_DIM_GENERATION_NOT_BUILDING");
            var proof=new Proof(UUID.randomUUID(),s.revisions(),delivery(tenant,s.consumer()),contributions(tenant,gen,s),
                periods(tenant,gen,s),cells(tenant,gen),memberships(tenant,gen));
            var r=proof.revisions();
            jdbc.update("""
                INSERT INTO crm.int_dim_coverage_proof(tenant_id,generation_id,proof_id,observed_at,
                  source_revision,dirty_revision,receipt_revision,materialization_revision,lifecycle_revision,
                  delivery_mismatches,contribution_mismatches,period_mismatches,cell_mismatches,membership_mismatches)
                VALUES (?,?,?,statement_timestamp(),?,?,?,?,?,?,?,?,?,?)
                """,tenant,gen,proof.id(),r.source(),r.dirty(),r.receipt(),r.materialization(),r.lifecycle(),
                proof.delivery(),proof.contributions(),proof.periods(),proof.cells(),proof.memberships());
            return proof;
        });
    }
    public Status status(UUID tenant,UUID gen,UUID proofId){
        Objects.requireNonNull(proofId);
        return tx(()->{
            Scope s=scope(tenant,gen);
            if("ABORTED".equals(s.state()))return Status.ABORTED;
            Proof p=jdbc.query("""
                SELECT source_revision,dirty_revision,receipt_revision,materialization_revision,lifecycle_revision,
                  delivery_mismatches,contribution_mismatches,period_mismatches,cell_mismatches,membership_mismatches
                FROM crm.int_dim_coverage_proof WHERE tenant_id=? AND generation_id=? AND proof_id=?
                """,rs->rs.next()?new Proof(proofId,new Revisions(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)),
                    rs.getLong(6),rs.getLong(7),rs.getLong(8),rs.getLong(9),rs.getLong(10)):null,tenant,gen,proofId);
            if(p==null)return Status.UNPROVEN;
            if(!p.revisions().equals(s.revisions()))return Status.STALE;
            return p.parity()?Status.CURRENT:Status.INCOMPLETE;
        });
    }
}
