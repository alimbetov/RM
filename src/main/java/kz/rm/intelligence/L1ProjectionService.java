package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Internal BUILDING generations only. No public read route or activation in B3. */
@Service
public class L1ProjectionService {
    private static final UUID NONE=new UUID(0,0);
    private static final String NO_CURRENCY="~";
    public record Generation(UUID tenant,UUID id,String consumerCode,int methodVersion,long calendarVersion,
                             ZoneId zone,LocalDate from,LocalDate until){
        boolean covers(LocalDate day){return !day.isBefore(from)&&day.isBefore(until);}
    }
    public record Lifecycle(String state,long revision){}
    public record Bucket(MetricRegistry.Code metric,LocalDate day,String currency,UUID warehouse,UUID sku,
                         BigDecimal amount,long observations){}
    public record Proof(UUID id,Instant observedAt,long missingFacts,long orphanFacts,long pendingFacts,
                        long rejectedFacts,long receiptMismatches,long bucketMismatches){
        /** Evidence for one MVCC snapshot, never an activation token. */
        public boolean parity(){return missingFacts==0&&orphanFacts==0&&pendingFacts==0
            &&rejectedFacts==0&&receiptMismatches==0&&bucketMismatches==0;}
    }
    public enum FenceStatus { UNPROVEN, INCOMPLETE, STALE, ABORTED, CURRENT }
    public record Revisions(long source,long projection,long lifecycle){}
    public record Route(boolean materialized,UUID generation,UUID proofId,String reason){}
    private Revisions revisions(UUID tenant,UUID generation){
        return jdbc.queryForObject("""
            SELECT COALESCE(s.revision,0)+(SELECT count(*) FROM crm.int_source_revision_event e WHERE e.tenant_id=l.tenant_id),
                   COALESCE(p.revision,0),l.revision
            FROM crm.int_projection_lifecycle l
            LEFT JOIN crm.int_source_revision s ON s.tenant_id=l.tenant_id
            LEFT JOIN crm.int_projection_revision p ON p.tenant_id=l.tenant_id AND p.generation_id=l.generation_id
            WHERE l.tenant_id=? AND l.generation_id=?
            """,(rs,n)->new Revisions(rs.getLong(1),rs.getLong(2),rs.getLong(3)),tenant,generation);
    }
    private FenceStatus fenceStatusInSnapshot(UUID tenant,UUID generation,UUID proof){
        if("ABORTED".equals(readLifecycle(tenant,generation,"").state()))return FenceStatus.ABORTED;
        return jdbc.query("""
            SELECT CASE
              WHEN f.proof_id IS NULL THEN 'UNPROVEN'
              WHEN f.source_revision<>COALESCE(s.revision,0)
                +(SELECT count(*) FROM crm.int_source_revision_event e WHERE e.tenant_id=l.tenant_id)
                OR f.projection_revision<>COALESCE(p.revision,0)
                OR f.lifecycle_revision<>l.revision THEN 'STALE'
              WHEN c.missing_facts<>0 OR c.orphan_facts<>0 OR c.pending_facts<>0
                OR c.rejected_facts<>0 OR c.receipt_mismatches<>0 OR c.bucket_mismatches<>0 THEN 'INCOMPLETE'
              ELSE 'CURRENT' END
            FROM crm.int_projection_lifecycle l
            LEFT JOIN crm.int_source_revision s ON s.tenant_id=l.tenant_id
            LEFT JOIN crm.int_projection_revision p ON p.tenant_id=l.tenant_id AND p.generation_id=l.generation_id
            LEFT JOIN crm.int_coverage_fence f ON f.tenant_id=l.tenant_id AND f.generation_id=l.generation_id AND f.proof_id=?
            LEFT JOIN crm.int_projection_coverage c ON c.tenant_id=f.tenant_id AND c.generation_id=f.generation_id AND c.proof_id=f.proof_id
            WHERE l.tenant_id=? AND l.generation_id=?
            """,rs->rs.next()?FenceStatus.valueOf(rs.getString(1)):FenceStatus.UNPROVEN,proof,tenant,generation);
    }
    /** Technical evidence only; CURRENT does not mean ACTIVE or geographically complete. */
    public FenceStatus fenceStatus(UUID tenant,UUID generation,UUID proof){
        Objects.requireNonNull(proof);
        return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,()->{
            require(tenant,generation);
            return fenceStatusInSnapshot(tenant,generation,proof);
        });
    }
    /** Internal routing descriptor only. No metric data, authorization or public routing.
     * A later reader must repeat admission and read buckets in the same transaction.
     * B4.2 cannot populate the pointer: ACTIVE is prohibited by the lifecycle schema.
     */
    public Route route(UUID tenant,long calendarVersion,int methodVersion,LocalDate from,LocalDate until){
        Objects.requireNonNull(tenant);Objects.requireNonNull(from);Objects.requireNonNull(until);
        if(calendarVersion<1||methodVersion!=1||!from.isBefore(until))
            throw new IllegalArgumentException("L1_ROUTE_SCOPE");
        return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,()->{
            record Pointer(UUID generation,UUID proof){}
            Pointer pointer=jdbc.query("""
                SELECT generation_id,proof_id FROM crm.int_active_generation
                WHERE tenant_id=? AND projection_code='L1'
                """,rs->rs.next()?new Pointer(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)):null,tenant);
            if(pointer==null)return new Route(false,null,null,"NO_ACTIVE_GENERATION");
            Generation g=require(tenant,pointer.generation());
            if(g.calendarVersion()!=calendarVersion||g.methodVersion()!=methodVersion
                ||from.isBefore(g.from())||until.isAfter(g.until()))
                return new Route(false,null,null,"SCOPE_MISMATCH");
            if(!"ACTIVE".equals(readLifecycle(tenant,g.id(),"").state()))
                return new Route(false,null,null,"NOT_ACTIVE");
            var status=fenceStatusInSnapshot(tenant,g.id(),pointer.proof());
            if(status!=FenceStatus.CURRENT)return new Route(false,null,null,status.name());
            return new Route(true,g.id(),pointer.proof(),"CURRENT");
        });
    }
    private record SalesContribution(LocalDate day,String currency,BigDecimal booked){}
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final CanonicalFactDeliveryService delivery;
    public L1ProjectionService(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactDeliveryService delivery){
        this.jdbc=jdbc;this.manager=manager;this.delivery=delivery;
    }
    private <T> T tx(int isolation,Supplier<T> work){
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("L1_REQUIRES_OWN_TRANSACTION");
        var t=new TransactionTemplate(manager);t.setIsolationLevel(isolation);t.setTimeout(60);
        return t.execute(status->{
            if(!Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->
                c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql"))))
                throw new IllegalStateException("L1_POSTGRESQL_REQUIRED");
            return work.get();
        });
    }
    /** Caller supplies a fresh generation ID; retry with the same immutable scope is safe. */
    public Generation open(UUID tenant,UUID generation,long calendarVersion,LocalDate from,LocalDate until){
        Objects.requireNonNull(tenant);Objects.requireNonNull(generation);Objects.requireNonNull(from);Objects.requireNonNull(until);
        if(calendarVersion<1||!from.isBefore(until)||ChronoUnit.DAYS.between(from,until)>3660)
            throw new IllegalArgumentException("L1_GENERATION_SCOPE");
        String code="l1:"+generation;
        return tx(TransactionDefinition.ISOLATION_READ_COMMITTED,()->{
            // Atomic generation/consumer creation; an independently used consumer cannot be adopted.
            jdbc.update("INSERT INTO crm.int_fact_consumer(tenant_id,consumer_code) VALUES (?,?) ON CONFLICT DO NOTHING",tenant,code);
            jdbc.queryForObject("SELECT consumer_code FROM crm.int_fact_consumer WHERE tenant_id=? AND consumer_code=? FOR UPDATE",String.class,tenant,code);
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM crm.int_fact_consumer_receipt WHERE tenant_id=? AND consumer_code=?)
                  AND NOT EXISTS(SELECT 1 FROM crm.int_projection_generation WHERE tenant_id=? AND generation_id=?)
                """,Boolean.class,tenant,code,tenant,generation)))throw new IllegalStateException("L1_CONSUMER_ALREADY_USED");
            jdbc.update("""
                INSERT INTO crm.int_projection_generation(tenant_id,generation_id,projection_code,consumer_code,method_version,calendar_version,from_day,until_day)
                VALUES (?,?,'L1',?,1,?,?,?) ON CONFLICT (tenant_id,generation_id) DO NOTHING
                """,tenant,generation,code,calendarVersion,from,until);
            Generation g=require(tenant,generation);
            if(g.calendarVersion()!=calendarVersion||!g.from().equals(from)||!g.until().equals(until))
                throw new IllegalArgumentException("L1_GENERATION_SCOPE_CONFLICT");
            requireBuilding(tenant,generation);
            return g;
        });
    }
    private Lifecycle readLifecycle(UUID tenant,UUID generation,String lock){
        Lifecycle l=jdbc.query("""
            SELECT lifecycle_state,revision FROM crm.int_projection_lifecycle
            WHERE tenant_id=? AND generation_id=?
            """+lock,rs->rs.next()?new Lifecycle(rs.getString(1),rs.getLong(2)):null,tenant,generation);
        if(l==null)throw new IllegalStateException("L1_LIFECYCLE_MISSING");
        return l;
    }
    private void requireBuilding(UUID tenant,UUID generation){
        if(!"BUILDING".equals(readLifecycle(tenant,generation," FOR SHARE").state()))
            throw new IllegalStateException("L1_GENERATION_NOT_BUILDING");
    }
    public Lifecycle lifecycle(UUID tenant,UUID generation){
        return tx(TransactionDefinition.ISOLATION_READ_COMMITTED,()->{
            require(tenant,generation);
            return readLifecycle(tenant,generation,"");
        });
    }
    /** Internal terminal abort; same consumer lock as P2 before lifecycle lock. */
    public Lifecycle abort(UUID tenant,UUID generation,long expectedRevision){
        if(expectedRevision!=0)throw new IllegalArgumentException("L1_LIFECYCLE_REVISION_CONFLICT");
        return tx(TransactionDefinition.ISOLATION_READ_COMMITTED,()->{
            Generation g=require(tenant,generation);
            jdbc.queryForObject("SELECT consumer_code FROM crm.int_fact_consumer WHERE tenant_id=? AND consumer_code=? FOR UPDATE",
                String.class,tenant,g.consumerCode());
            Lifecycle before=readLifecycle(tenant,generation," FOR UPDATE");
            if("ABORTED".equals(before.state())&&before.revision()==1)return before;
            if(!"BUILDING".equals(before.state())||before.revision()!=expectedRevision)
                throw new IllegalStateException("L1_LIFECYCLE_REVISION_CONFLICT");
            if(jdbc.update("""
                UPDATE crm.int_projection_lifecycle SET lifecycle_state='ABORTED',revision=1,
                  changed_at=GREATEST(changed_at,CURRENT_TIMESTAMP)
                WHERE tenant_id=? AND generation_id=? AND lifecycle_state='BUILDING' AND revision=?
                """,tenant,generation,expectedRevision)!=1)
                throw new IllegalStateException("L1_LIFECYCLE_REVISION_CONFLICT");
            return readLifecycle(tenant,generation,"");
        });
    }
    private Generation require(UUID tenant,UUID id){
        var g=jdbc.query("""
            SELECT g.consumer_code,g.method_version,g.calendar_version,c.zone_id,g.from_day,g.until_day
            FROM crm.int_projection_generation g JOIN crm.int_calendar_policy c
              ON c.tenant_id=g.tenant_id AND c.version=g.calendar_version
            WHERE g.tenant_id=? AND g.generation_id=?
            """,rs->rs.next()?new Generation(tenant,id,rs.getString(1),rs.getInt(2),rs.getLong(3),
                ZoneId.of(rs.getString(4)),rs.getObject(5,LocalDate.class),rs.getObject(6,LocalDate.class)):null,tenant,id);
        if(g==null)throw new IllegalArgumentException("L1_GENERATION_NOT_FOUND");
        return g;
    }
    /** P2 holds the exact consumer lock; handler and P2 receipt commit together. */
    public List<CanonicalFactDeliveryService.Step> advance(UUID tenant,UUID generation,int limit){
        Generation g=tx(TransactionDefinition.ISOLATION_READ_COMMITTED,()->require(tenant,generation));
        return delivery.consumeProjectionBatch(tenant,g.id(),limit,f->apply(g,f));
    }
    private CanonicalFactDeliveryService.Effect apply(Generation g,CanonicalFactDeliveryService.Envelope f){
        if(!g.tenant().equals(f.key().tenantId()))throw new IllegalStateException("L1_FOREIGN_FACT");
        if(f.key().owner()==CanonicalFactKey.Owner.SALES){
            // Reconcile the whole order/kind group even when this event is outside
            // the window: a late duplicate invalidates a previously eligible row.
            reconcileSales(g,f);
            return CanonicalFactDeliveryService.Effect.APPLIED;
        }
        LocalDate day=f.recordedAt().atZone(g.zone()).toLocalDate();
        if(!g.covers(day))return CanonicalFactDeliveryService.Effect.OUTSIDE_COVERAGE;
        jdbc.query("""
            SELECT location_id,item_id,delta_quantity FROM crm.inv_stock_movement WHERE tenant_id=? AND id=?
            """,rs->{
                if(!rs.next())throw new IllegalStateException("L1_MOVEMENT_NOT_FOUND");
                BigDecimal delta=rs.getBigDecimal(3);
                change(g,delta.signum()>0?MetricRegistry.Code.STOCK_IN_QTY:MetricRegistry.Code.STOCK_OUT_QTY,
                    day,NO_CURRENCY,rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),delta.abs(),1);
                return null;
            },g.tenant(),f.key().sourceId());
        return CanonicalFactDeliveryService.Effect.APPLIED;
    }
    private static MetricRegistry.Code salesMetric(CanonicalFactKey.Kind kind){
        return switch(kind){
            case ORDER_PLACED->MetricRegistry.Code.PLACED_ORDERS;
            case ORDER_CONFIRMED->MetricRegistry.Code.CONFIRMED_ORDERS;
            case ORDER_COMPLETED->MetricRegistry.Code.FULFILLED_ORDERS;
            case ORDER_CANCELLED->MetricRegistry.Code.CANCELLED_ORDERS;
            default->throw new IllegalArgumentException("L1_SALES_KIND");
        };
    }
    private void reconcileSales(Generation g,CanonicalFactDeliveryService.Envelope f){
        UUID order=f.subject();var kind=f.key().kind();var metric=salesMetric(kind);
        SalesContribution prior=jdbc.query("""
            SELECT local_day,currency,booked_amount FROM crm.int_l1_sales_contribution
            WHERE tenant_id=? AND generation_id=? AND event_kind=? AND order_id=?
            """,rs->rs.next()?new SalesContribution(rs.getObject(1,LocalDate.class),rs.getString(2),rs.getBigDecimal(3)):null,
            g.tenant(),g.id(),kind.name(),order);
        SalesContribution eligible=jdbc.query("""
            SELECT min(e.occurred_at),o.currency,o.total_amount
            FROM crm.sales_order_status_event e JOIN crm.sales_order o ON o.tenant_id=e.tenant_id AND o.id=e.order_id
            WHERE e.tenant_id=? AND e.order_id=? AND e.event_type=?
            GROUP BY o.currency,o.total_amount HAVING count(*)=1
            """,rs->rs.next()?new SalesContribution(rs.getTimestamp(1).toInstant().atZone(g.zone()).toLocalDate(),
                rs.getString(2).trim(),rs.getBigDecimal(3)):null,g.tenant(),order,kind.name().substring(6));
        if(eligible!=null&&!g.covers(eligible.day()))eligible=null;
        if(prior!=null){
            salesEffect(g,metric,prior,-1);
            jdbc.update("DELETE FROM crm.int_l1_sales_contribution WHERE tenant_id=? AND generation_id=? AND event_kind=? AND order_id=?",
                g.tenant(),g.id(),kind.name(),order);
        }
        if(eligible!=null){
            salesEffect(g,metric,eligible,1);
            jdbc.update("""
                INSERT INTO crm.int_l1_sales_contribution(tenant_id,generation_id,event_kind,order_id,local_day,currency,booked_amount)
                VALUES (?,?,?,?,?,?,?)
                """,g.tenant(),g.id(),kind.name(),order,eligible.day(),eligible.currency(),eligible.booked());
        }
    }
    private void salesEffect(Generation g,MetricRegistry.Code metric,SalesContribution c,int sign){
        change(g,metric,c.day(),NO_CURRENCY,NONE,NONE,BigDecimal.valueOf(sign),sign);
        if(metric==MetricRegistry.Code.PLACED_ORDERS)
            change(g,MetricRegistry.Code.BOOKED_ORDER_AMOUNT,c.day(),c.currency(),NONE,NONE,c.booked().multiply(BigDecimal.valueOf(sign)),sign);
    }
    private void change(Generation g,MetricRegistry.Code metric,LocalDate day,String currency,UUID warehouse,UUID sku,BigDecimal amount,int count){
        if(count>0){
            jdbc.update("""
                INSERT INTO crm.int_daily_bucket(tenant_id,generation_id,metric_code,local_day,currency_key,warehouse_key,sku_key,amount,observations)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id,generation_id,metric_code,local_day,currency_key,warehouse_key,sku_key)
                DO UPDATE SET amount=int_daily_bucket.amount+excluded.amount,observations=int_daily_bucket.observations+excluded.observations
                """,g.tenant(),g.id(),metric.name(),day,currency,warehouse,sku,amount,count);
        }else{
            if(jdbc.update("""
                UPDATE crm.int_daily_bucket SET amount=amount+?,observations=observations+?
                WHERE tenant_id=? AND generation_id=? AND metric_code=? AND local_day=? AND currency_key=? AND warehouse_key=? AND sku_key=?
                """,amount,count,g.tenant(),g.id(),metric.name(),day,currency,warehouse,sku)!=1)
                throw new IllegalStateException("L1_CONTRIBUTION_BUCKET_MISSING");
            jdbc.update("""
                DELETE FROM crm.int_daily_bucket WHERE tenant_id=? AND generation_id=? AND metric_code=?
                  AND local_day=? AND currency_key=? AND warehouse_key=? AND sku_key=? AND observations=0 AND amount=0
                """,g.tenant(),g.id(),metric.name(),day,currency,warehouse,sku);
        }
    }
    /** Internal inspection only; not the authorized L0 metric API. */
    public List<Bucket> buckets(UUID tenant,UUID generation,LocalDate day){
        Objects.requireNonNull(day);
        return tx(TransactionDefinition.ISOLATION_READ_COMMITTED,()->{
            Generation g=require(tenant,generation);
            if(!g.covers(day))throw new IllegalArgumentException("L1_DAY_OUTSIDE_SCOPE");
            return jdbc.query("""
                SELECT metric_code,local_day,currency_key,warehouse_key,sku_key,amount,observations
                FROM crm.int_daily_bucket WHERE tenant_id=? AND generation_id=? AND local_day=?
                ORDER BY metric_code,currency_key,warehouse_key,sku_key
                """,(rs,n)->new Bucket(MetricRegistry.Code.valueOf(rs.getString(1)),rs.getObject(2,LocalDate.class),
                rs.getString(3),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),rs.getBigDecimal(6),rs.getLong(7)),
                tenant,generation,day);
        });
    }
    /**
     * Independent owner SQL vs projection in one repeatable-read snapshot.
     * Conservative whole-tenant source/receipt coverage; bucket parity is scoped
     * to the immutable calendar and local-day range. Never marks a generation READY.
     */
    public Proof prove(UUID tenant,UUID generation){
        return tx(TransactionDefinition.ISOLATION_REPEATABLE_READ,()->{
            Generation g=require(tenant,generation);
            Revisions stamp=revisions(tenant,generation);
            UUID proofId=UUID.randomUUID();
            Proof proof=jdbc.queryForObject("""
                WITH p AS (SELECT CAST(? AS uuid) tenant,CAST(? AS uuid) gen,CAST(? AS varchar) consumer,
                   CAST(? AS varchar) zone,CAST(? AS date) from_day,CAST(? AS date) until_day),
                owner_rows AS (
                  SELECT 'SALES' owner_module, 'ORDER_'||e.event_type source_kind,e.id source_id,e.order_id subject_id,
                      e.occurred_at,CAST(NULL AS timestamptz) recorded_at,CAST(NULL AS bigint) source_version
                  FROM crm.sales_order_status_event e,p WHERE e.tenant_id=p.tenant
                    AND e.event_type IN ('PLACED','CONFIRMED','COMPLETED','CANCELLED')
                  UNION ALL
                  SELECT 'INVENTORY','STOCK_MOVEMENT',m.id,m.item_id,m.occurred_at,m.recorded_at,m.posting_version
                  FROM crm.inv_stock_movement m,p WHERE m.tenant_id=p.tenant
                ), facts AS (
                  SELECT f.* FROM crm.int_canonical_fact f,p WHERE f.tenant_id=p.tenant
                ), r AS (
                  SELECT r.* FROM crm.int_fact_consumer_receipt r,p WHERE r.tenant_id=p.tenant AND r.consumer_code=p.consumer
                ), eligible AS (
                  SELECT e.order_id,e.event_type,CAST(min(e.occurred_at) AT TIME ZONE p.zone AS date) local_day,
                      trim(o.currency) currency,o.total_amount
                  FROM crm.sales_order_status_event e JOIN crm.sales_order o ON o.tenant_id=e.tenant_id AND o.id=e.order_id,p
                  WHERE e.tenant_id=p.tenant AND e.event_type IN ('PLACED','CONFIRMED','COMPLETED','CANCELLED')
                  GROUP BY e.order_id,e.event_type,p.zone,o.currency,o.total_amount HAVING count(*)=1
                ), expected_rows AS (
                  SELECT CASE e.event_type WHEN 'PLACED' THEN 'PLACED_ORDERS' WHEN 'CONFIRMED' THEN 'CONFIRMED_ORDERS'
                    WHEN 'COMPLETED' THEN 'FULFILLED_ORDERS' ELSE 'CANCELLED_ORDERS' END metric_code,
                    e.local_day,'~' currency_key,CAST('00000000-0000-0000-0000-000000000000' AS uuid) warehouse_key,
                    CAST('00000000-0000-0000-0000-000000000000' AS uuid) sku_key,CAST(1 AS numeric) amount
                  FROM eligible e
                  UNION ALL
                  SELECT 'BOOKED_ORDER_AMOUNT',e.local_day,e.currency,
                    CAST('00000000-0000-0000-0000-000000000000' AS uuid),
                    CAST('00000000-0000-0000-0000-000000000000' AS uuid),e.total_amount
                  FROM eligible e WHERE e.event_type='PLACED'
                  UNION ALL
                  SELECT CASE WHEN m.delta_quantity>0 THEN 'STOCK_IN_QTY' ELSE 'STOCK_OUT_QTY' END,
                    CAST(m.recorded_at AT TIME ZONE p.zone AS date),'~',m.location_id,m.item_id,abs(m.delta_quantity)
                  FROM crm.inv_stock_movement m,p WHERE m.tenant_id=p.tenant
                ), expected AS (
                  SELECT metric_code,local_day,currency_key,warehouse_key,sku_key,sum(amount) amount,count(*) observations
                  FROM expected_rows,p WHERE local_day>=p.from_day AND local_day<p.until_day
                  GROUP BY metric_code,local_day,currency_key,warehouse_key,sku_key
                ), actual AS (
                  SELECT b.* FROM crm.int_daily_bucket b,p WHERE b.tenant_id=p.tenant AND b.generation_id=p.gen
                )
                SELECT statement_timestamp(),
                  (SELECT count(*) FROM owner_rows o WHERE NOT EXISTS(SELECT 1 FROM facts f
                    WHERE f.owner_module=o.owner_module AND f.source_kind=o.source_kind AND f.source_id=o.source_id
                    AND f.subject_id=o.subject_id AND f.occurred_at=o.occurred_at AND f.recorded_at IS NOT DISTINCT FROM o.recorded_at
                    AND (f.schema_version=1 OR f.source_version IS NOT DISTINCT FROM o.source_version))),
                  (SELECT count(*) FROM facts f WHERE NOT EXISTS(SELECT 1 FROM owner_rows o
                    WHERE f.owner_module=o.owner_module AND f.source_kind=o.source_kind AND f.source_id=o.source_id)),
                  (SELECT count(*) FROM facts f WHERE NOT EXISTS(SELECT 1 FROM r WHERE r.owner_module=f.owner_module
                     AND r.source_kind=f.source_kind AND r.source_id=f.source_id)),
                  (SELECT count(*) FROM r WHERE disposition='REJECTED'),
                  (SELECT count(*) FROM r JOIN facts f USING(owner_module,source_kind,source_id)
                     WHERE r.digest<>f.digest OR r.schema_version<>f.schema_version),
                  (SELECT count(*) FROM expected e FULL JOIN actual a USING(metric_code,local_day,currency_key,warehouse_key,sku_key)
                     WHERE e.metric_code IS NULL OR a.metric_code IS NULL OR e.amount<>a.amount OR e.observations<>a.observations)
                """,(rs,n)->new Proof(proofId,rs.getTimestamp(1).toInstant(),rs.getLong(2),rs.getLong(3),rs.getLong(4),
                rs.getLong(5),rs.getLong(6),rs.getLong(7)),tenant,generation,g.consumerCode(),g.zone().getId(),g.from(),g.until());
            jdbc.update("""
                INSERT INTO crm.int_projection_coverage(tenant_id,generation_id,proof_id,observed_at,missing_facts,orphan_facts,
                  pending_facts,rejected_facts,receipt_mismatches,bucket_mismatches) VALUES (?,?,?,?,?,?,?,?,?,?)
                """,tenant,generation,proof.id(),java.sql.Timestamp.from(proof.observedAt()),proof.missingFacts(),
                proof.orphanFacts(),proof.pendingFacts(),proof.rejectedFacts(),proof.receiptMismatches(),proof.bucketMismatches());
            jdbc.update("""
                INSERT INTO crm.int_coverage_fence(tenant_id,generation_id,proof_id,
                  source_revision,projection_revision,lifecycle_revision) VALUES (?,?,?,?,?,?)
                """,tenant,generation,proof.id(),stamp.source(),stamp.projection(),stamp.lifecycle());
            return proof;
        });
    }
}
