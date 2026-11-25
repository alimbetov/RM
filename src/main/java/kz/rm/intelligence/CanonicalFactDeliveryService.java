package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Trusted INTERNAL PostgreSQL delivery/backfill port. No endpoint, scheduler or
 * user-facing tenant admission is enabled here. A consumer identity denotes a
 * target/epoch. A DB-local handler and its receipt share one transaction.
 * Never call a remote service or publish an external effect from the handler.
 */
@Service
public class CanonicalFactDeliveryService {
    public static final int MAX_BATCH = 500;
    public static final Duration MAX_SLICE = Duration.ofDays(31);
    private static final Set<String> SOURCE_ERRORS=Set.of(
        "CANONICAL_FACT_CONFLICT","CANONICAL_SOURCE_NOT_FOUND","CANONICAL_ORDER_NOT_FOUND",
        "CANONICAL_SOURCE_NOT_SEALED","CANONICAL_SNAPSHOT_EMPTY","CANONICAL_SNAPSHOT_INVALID_LINE",
        "CANONICAL_SNAPSHOT_CURRENCY_MISMATCH","CANONICAL_SNAPSHOT_AMOUNT_MISMATCH");

    public record Envelope(CanonicalFactKey key,UUID subject,Instant occurredAt,Instant recordedAt,
                           Long sourceVersion,int schemaVersion,String digest){}
    public enum Effect { APPLIED, OUTSIDE_COVERAGE }
    @FunctionalInterface public interface DatabaseHandler {
        Effect apply(Envelope fact);
    }
    public enum Disposition { APPLIED, OUTSIDE_COVERAGE, REJECTED, INSERTED, VERIFIED, CONFLICT, INVALID, ALREADY_PROCESSED }
    public record Step(CanonicalFactKey key,Disposition disposition,String errorCode){}
    public enum TimeBasis { OCCURRED, RECORDED }
    public record Slice(UUID tenant,UUID runId,CanonicalFactKey.Owner owner,CanonicalFactKey.Kind kind,
                        Instant from,Instant until,TimeBasis basis){
        public Slice(UUID tenant,UUID runId,CanonicalFactKey.Owner owner,CanonicalFactKey.Kind kind,Instant from,Instant until){
            this(tenant,runId,owner,kind,from,until,owner==CanonicalFactKey.Owner.INVENTORY?TimeBasis.RECORDED:TimeBasis.OCCURRED);
        }
        public Slice {
            Objects.requireNonNull(runId);
            new CanonicalFactKey(tenant,owner,kind,runId);
            Objects.requireNonNull(basis);
            if(owner==CanonicalFactKey.Owner.SALES && basis!=TimeBasis.OCCURRED)
                throw new IllegalArgumentException("BACKFILL_TIME_BASIS");
            from=Objects.requireNonNull(from).truncatedTo(ChronoUnit.MICROS);
            until=Objects.requireNonNull(until).truncatedTo(ChronoUnit.MICROS);
            if(!from.isBefore(until)||Duration.between(from,until).compareTo(MAX_SLICE)>0)
                throw new IllegalArgumentException("BACKFILL_SLICE_RANGE");
        }
    }
    public record Coverage(Instant observedAt,long sources,long facts,long missing,long unprocessed,
                           long rejected,long orphanFacts,long receiptMismatches,long orphanReceipts){
        // Point-in-time evidence for this range only; later commits can change it.
        public boolean complete(){return missing==0 && unprocessed==0 && rejected==0
            && orphanFacts==0 && receiptMismatches==0 && orphanReceipts==0 && sources==facts;}
    }
    public record ConsumerCoverage(long facts,long pending,long rejected,long receiptMismatches){
        public boolean caughtUp(){return pending==0 && rejected==0 && receiptMismatches==0;}
    }
    private record Selection(String sql,List<Object> args){}
    private static final class SourceRejected extends RuntimeException {
        final CanonicalFactKey key;
        final String code;
        SourceRejected(CanonicalFactKey key,String code,Throwable cause){super(code,cause);this.key=key;this.code=code;}
    }
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final CanonicalFactAppender appender;
    public CanonicalFactDeliveryService(JdbcTemplate jdbc,PlatformTransactionManager manager,CanonicalFactAppender appender){
        this.jdbc=jdbc;this.manager=manager;this.appender=appender;
    }
    private <T> T tx(Supplier<T> work){
        // Each unit owns COMMIT. Never join an owner command or callback transaction.
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("CANONICAL_DELIVERY_REQUIRES_OWN_TRANSACTION");
        var template=new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(30);
        return template.execute(status->{
            boolean pg=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->
                c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
            if(!pg)throw new IllegalStateException("CANONICAL_DELIVERY_POSTGRESQL_REQUIRED");
            return work.get();
        });
    }
    private static void consumerCode(String code){
        if(code==null || !code.matches("[A-Za-z0-9_.:-]{1,96}"))
            throw new IllegalArgumentException("CANONICAL_CONSUMER_CODE");
    }
    private static void batch(int limit){
        if(limit<1||limit>MAX_BATCH)throw new IllegalArgumentException("CANONICAL_BATCH_LIMIT");
    }
    private static void requireGenericConsumer(String code){
        consumerCode(code);
        if(ProjectionKind.reserved(code))throw new IllegalArgumentException("CANONICAL_RESERVED_CONSUMER");
    }
    public void openConsumer(UUID tenant,String code){
        Objects.requireNonNull(tenant);requireGenericConsumer(code);
        tx(()->jdbc.update("""
            INSERT INTO crm.int_fact_consumer(tenant_id,consumer_code) VALUES (?,?)
            ON CONFLICT (tenant_id,consumer_code) DO NOTHING
            """,tenant,code));
    }
    private void lockConsumer(UUID tenant,String code){
        String found=jdbc.query("""
            SELECT consumer_code FROM crm.int_fact_consumer WHERE tenant_id=? AND consumer_code=? FOR UPDATE
            """,rs->rs.next()?rs.getString(1):null,tenant,code);
        if(found==null)throw new IllegalArgumentException("CANONICAL_CONSUMER_NOT_FOUND");
    }
    /** Under the consumer lock, in the same transaction as each effect/receipt. */
    private void requireProjectionBuilding(UUID tenant,String code){
        if(!ProjectionKind.reserved(code))return;
        boolean l2=code.startsWith(ProjectionKind.L2.prefix());
        String state=jdbc.query(l2 ? """
            SELECT l.lifecycle_state FROM crm.int_projection_lifecycle l
            JOIN crm.int_l2_consumer b ON b.tenant_id=l.tenant_id AND b.generation_id=l.generation_id
            WHERE l.tenant_id=? AND b.consumer_code=? FOR SHARE OF l
            """ : """
            SELECT l.lifecycle_state FROM crm.int_projection_lifecycle l
            JOIN crm.int_projection_generation g ON g.tenant_id=l.tenant_id AND g.generation_id=l.generation_id
            WHERE l.tenant_id=? AND g.consumer_code=? FOR SHARE OF l
            """,rs->rs.next()?rs.getString(1):null,tenant,code);
        if(!"BUILDING".equals(state))throw new IllegalStateException(l2?"L2_GENERATION_NOT_BUILDING":"L1_GENERATION_NOT_BUILDING");
    }
    private static Envelope envelope(ResultSet rs) throws SQLException {
        Timestamp recorded=rs.getTimestamp("recorded_at");
        return new Envelope(new CanonicalFactKey(rs.getObject("tenant_id",UUID.class),
            CanonicalFactKey.Owner.valueOf(rs.getString("owner_module")),
            CanonicalFactKey.Kind.valueOf(rs.getString("source_kind")),rs.getObject("source_id",UUID.class)),
            rs.getObject("subject_id",UUID.class),rs.getTimestamp("occurred_at").toInstant(),
            recorded==null?null:recorded.toInstant(),rs.getObject("source_version",Long.class),
            rs.getInt("schema_version"),rs.getString("digest"));
    }
    private Envelope find(CanonicalFactKey k){
        return jdbc.query("""
            SELECT * FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module=? AND source_kind=? AND source_id=?
            """,rs->rs.next()?envelope(rs):null,k.tenantId(),k.owner().name(),k.kind().name(),k.sourceId());
    }
    private CanonicalFactAppender.Outcome append(CanonicalFactKey key){
        try{return appender.append(key);}
        catch(IllegalStateException e){
            // Only explicit immutable-source admission failures are terminal.
            // SQL/network/lock/deadlock/handler failures propagate and retry.
            if(e.getMessage()!=null && SOURCE_ERRORS.contains(e.getMessage()))
                throw new SourceRejected(key,e.getMessage(),e);
            throw e;
        }
    }
    private Envelope nextFact(UUID tenant,String code){
        // Never seek by max UUID, timestamp or sequence. A late commit with a
        // lower key remains visible until its own receipt is committed.
        return jdbc.query("""
            SELECT f.* FROM crm.int_canonical_fact f WHERE f.tenant_id=?
            AND NOT EXISTS (SELECT 1 FROM crm.int_fact_consumer_receipt r
                 WHERE r.tenant_id=f.tenant_id AND r.consumer_code=?
                   AND r.owner_module=f.owner_module AND r.source_kind=f.source_kind AND r.source_id=f.source_id)
            ORDER BY f.owner_module,f.source_kind,f.source_id LIMIT 1
            """,rs->rs.next()?envelope(rs):null,tenant,code);
    }
    private void consumerReceipt(String code,Envelope f,Disposition disposition,String error){
        var k=f.key();
        jdbc.update("""
            INSERT INTO crm.int_fact_consumer_receipt(tenant_id,consumer_code,owner_module,source_kind,source_id,
                 digest,schema_version,disposition,error_code) VALUES (?,?,?,?,?,?,?,?,?)
            """,k.tenantId(),code,k.owner().name(),k.kind().name(),k.sourceId(),
            f.digest(),f.schemaVersion(),disposition.name(),error);
    }
    private boolean hasConsumerReceipt(String code,CanonicalFactKey k){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM crm.int_fact_consumer_receipt
              WHERE tenant_id=? AND consumer_code=? AND owner_module=? AND source_kind=? AND source_id=?)
            """,Boolean.class,k.tenantId(),code,k.owner().name(),k.kind().name(),k.sourceId()));
    }
    public Optional<Step> consumeNext(UUID tenant,String code,DatabaseHandler handler){
        requireGenericConsumer(code);
        return consumeInternal(tenant,code,handler);
    }
    private Optional<Step> consumeInternal(UUID tenant,String code,DatabaseHandler handler){
        Objects.requireNonNull(tenant);consumerCode(code);Objects.requireNonNull(handler);
        try{
            return tx(()->{
                lockConsumer(tenant,code);
                requireProjectionBuilding(tenant,code);
                Envelope f=nextFact(tenant,code);
                if(f==null)return Optional.empty();
                if(append(f.key())!=CanonicalFactAppender.Outcome.ALREADY_PRESENT)
                    throw new IllegalStateException("CANONICAL_DISCOVERY_SOURCE_DISAPPEARED");
                Effect effect=Objects.requireNonNull(handler.apply(f),"Missing handler outcome");
                Disposition result=Disposition.valueOf(effect.name());
                consumerReceipt(code,f,result,null);
                return Optional.of(new Step(f.key(),result,null));
            });
        }catch(SourceRejected rejected){
            // The failed owner validation transaction was rolled back, including
            // any write. Record only an immutable-source rejection in a new unit.
            return tx(()->{
                lockConsumer(tenant,code);
                requireProjectionBuilding(tenant,code);
                if(hasConsumerReceipt(code,rejected.key))
                    return Optional.of(new Step(rejected.key,Disposition.ALREADY_PROCESSED,null));
                Envelope f=Objects.requireNonNull(find(rejected.key),"Missing rejected fact");
                consumerReceipt(code,f,Disposition.REJECTED,rejected.code);
                return Optional.of(new Step(rejected.key,Disposition.REJECTED,rejected.code));
            });
        }
    }
    public List<Step> consumeBatch(UUID tenant,String code,int limit,DatabaseHandler handler){
        requireGenericConsumer(code);
        return consumeInternalBatch(tenant,code,limit,handler);
    }
    // Package-internal reserved route, used only by the typed L1 service.
    List<Step> consumeProjectionBatch(UUID tenant,UUID generation,int limit,DatabaseHandler handler){
        Objects.requireNonNull(generation);
        return consumeProjectionBatch(tenant,ProjectionKind.L1,generation,limit,handler);
    }
    List<Step> consumeProjectionBatch(UUID tenant,ProjectionKind kind,UUID generation,int limit,DatabaseHandler handler){
        Objects.requireNonNull(generation);
        return consumeInternalBatch(tenant,kind.consumer(generation),limit,handler);
    }
    private List<Step> consumeInternalBatch(UUID tenant,String code,int limit,DatabaseHandler handler){
        batch(limit);var results=new ArrayList<Step>(limit);
        for(int i=0;i<limit;i++){
            var step=consumeInternal(tenant,code,handler);
            if(step.isEmpty())break;
            results.add(step.orElseThrow());
        }
        return List.copyOf(results);
    }
    /** New consumer code/epoch replays all facts. An existing consumer never resets receipts. */
    public ConsumerCoverage consumerCoverage(UUID tenant,String code){
        Objects.requireNonNull(tenant);consumerCode(code);
        return tx(()->{
            lockConsumer(tenant,code);
            return jdbc.queryForObject("""
                SELECT count(*),count(*) FILTER (WHERE r.source_id IS NULL),
                    count(*) FILTER (WHERE r.disposition='REJECTED'),
                    count(*) FILTER (WHERE r.source_id IS NOT NULL AND
                       (r.digest<>f.digest OR r.schema_version<>f.schema_version))
                FROM crm.int_canonical_fact f LEFT JOIN crm.int_fact_consumer_receipt r
                 ON r.tenant_id=f.tenant_id AND r.consumer_code=? AND r.owner_module=f.owner_module
                 AND r.source_kind=f.source_kind AND r.source_id=f.source_id
                WHERE f.tenant_id=?
                """,(rs,n)->new ConsumerCoverage(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)),code,tenant);
        });
    }
    public void openBackfill(Slice slice){
        Objects.requireNonNull(slice);
        tx(()->{
            jdbc.update("""
                INSERT INTO crm.int_fact_backfill_run(tenant_id,run_id,owner_module,source_kind,range_start,range_end,time_basis)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (tenant_id,run_id) DO NOTHING
                """,slice.tenant(),slice.runId(),slice.owner().name(),slice.kind().name(),
                Timestamp.from(slice.from()),Timestamp.from(slice.until()),slice.basis().name());
            Slice stored=lockRun(slice.tenant(),slice.runId());
            if(!stored.equals(slice))throw new IllegalArgumentException("BACKFILL_RUN_SCOPE_CONFLICT");
            return null;
        });
    }
    private Slice lockRun(UUID tenant,UUID runId){
        Slice s=jdbc.query("""
            SELECT owner_module,source_kind,range_start,range_end,time_basis FROM crm.int_fact_backfill_run
            WHERE tenant_id=? AND run_id=? FOR UPDATE
            """,rs->rs.next()?new Slice(tenant,runId,CanonicalFactKey.Owner.valueOf(rs.getString(1)),
            CanonicalFactKey.Kind.valueOf(rs.getString(2)),rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant(),TimeBasis.valueOf(rs.getString(5))):null,
            tenant,runId);
        if(s==null)throw new IllegalArgumentException("BACKFILL_RUN_NOT_FOUND");
        return s;
    }
    private static String timeColumn(Slice s){return s.basis()==TimeBasis.RECORDED?"recorded_at":"occurred_at";}
    private static Selection sources(Slice s){
        var args=new ArrayList<Object>();
        args.add(s.tenant());
        String query;
        if(s.owner()==CanonicalFactKey.Owner.SALES){
            query="SELECT id AS source_id,occurred_at FROM crm.sales_order_status_event WHERE tenant_id=? AND event_type=?";
            args.add(s.kind().name().substring("ORDER_".length()));
        }else query="SELECT id AS source_id,"+timeColumn(s)+" AS occurred_at FROM crm.inv_stock_movement WHERE tenant_id=?";
        args.add(Timestamp.from(s.from()));args.add(Timestamp.from(s.until()));
        return new Selection(query+" AND "+timeColumn(s)+">=? AND "+timeColumn(s)+"<?",args);
    }
    private CanonicalFactKey nextSource(Slice s){
        Selection source=sources(s);
        var args=new ArrayList<>(source.args());
        args.add(s.tenant());args.add(s.runId());args.add(s.owner().name());args.add(s.kind().name());
        UUID id=jdbc.query("SELECT o.source_id FROM ("+source.sql()+"""
            ) o WHERE NOT EXISTS (SELECT 1 FROM crm.int_fact_backfill_receipt r
               WHERE r.tenant_id=? AND r.run_id=? AND r.owner_module=? AND r.source_kind=? AND r.source_id=o.source_id)
            ORDER BY o.occurred_at,o.source_id LIMIT 1
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,args.toArray());
        return id==null?null:new CanonicalFactKey(s.tenant(),s.owner(),s.kind(),id);
    }
    private boolean hasBackfillReceipt(Slice s,CanonicalFactKey k){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM crm.int_fact_backfill_receipt WHERE tenant_id=? AND run_id=?
                AND owner_module=? AND source_kind=? AND source_id=?)
            """,Boolean.class,s.tenant(),s.runId(),k.owner().name(),k.kind().name(),k.sourceId()));
    }
    private void backfillReceipt(Slice s,CanonicalFactKey k,Disposition d,Envelope f,String error){
        jdbc.update("""
            INSERT INTO crm.int_fact_backfill_receipt(tenant_id,run_id,owner_module,source_kind,source_id,
                 disposition,digest,schema_version,error_code) VALUES (?,?,?,?,?,?,?,?,?)
            """,s.tenant(),s.runId(),k.owner().name(),k.kind().name(),k.sourceId(),d.name(),
            f==null?null:f.digest(),f==null?null:f.schemaVersion(),error);
    }
    public Optional<Step> backfillNext(UUID tenant,UUID runId){
        Objects.requireNonNull(tenant);Objects.requireNonNull(runId);
        try{
            return tx(()->{
                Slice s=lockRun(tenant,runId);
                CanonicalFactKey k=nextSource(s);
                if(k==null)return Optional.empty();
                var outcome=append(k);
                Disposition d=outcome==CanonicalFactAppender.Outcome.INSERTED?Disposition.INSERTED:Disposition.VERIFIED;
                backfillReceipt(s,k,d,Objects.requireNonNull(find(k)),null);
                return Optional.of(new Step(k,d,null));
            });
        }catch(SourceRejected rejected){
            return tx(()->{
                Slice s=lockRun(tenant,runId);
                if(hasBackfillReceipt(s,rejected.key))
                    return Optional.of(new Step(rejected.key,Disposition.ALREADY_PROCESSED,null));
                Disposition d="CANONICAL_FACT_CONFLICT".equals(rejected.code)?Disposition.CONFLICT:Disposition.INVALID;
                backfillReceipt(s,rejected.key,d,null,rejected.code);
                return Optional.of(new Step(rejected.key,d,rejected.code));
            });
        }
    }
    public List<Step> backfillBatch(UUID tenant,UUID runId,int limit){
        batch(limit);var results=new ArrayList<Step>(limit);
        for(int i=0;i<limit;i++){
            var step=backfillNext(tenant,runId);
            if(step.isEmpty())break;
            results.add(step.orElseThrow());
        }
        return List.copyOf(results);
    }
    /** Independently compare owner rows, canonical rows and durable per-run outcomes in one SQL snapshot. */
    public Coverage coverage(UUID tenant,UUID runId){
        Objects.requireNonNull(tenant);Objects.requireNonNull(runId);
        return tx(()->{
            Slice s=lockRun(tenant,runId);
            Selection source=sources(s);
            var args=new ArrayList<>(source.args());
            args.addAll(List.of(tenant,s.owner().name(),s.kind().name(),Timestamp.from(s.from()),Timestamp.from(s.until()),
                tenant,runId,s.owner().name(),s.kind().name()));
            String col=timeColumn(s);
            String sql="WITH o AS ("+source.sql()+"), f AS (SELECT source_id,"+col+" AS occurred_at,digest,schema_version FROM crm.int_canonical_fact"
                +" WHERE tenant_id=? AND owner_module=? AND source_kind=? AND "+col+">=? AND "+col+"<?),"+"""

                r AS (SELECT source_id,disposition,digest,schema_version FROM crm.int_fact_backfill_receipt
                  WHERE tenant_id=? AND run_id=? AND owner_module=? AND source_kind=?)
                SELECT statement_timestamp(),
                    (SELECT count(*) FROM o), (SELECT count(*) FROM f),
                    (SELECT count(*) FROM o WHERE NOT EXISTS(SELECT 1 FROM f WHERE f.source_id=o.source_id AND f.occurred_at=o.occurred_at)),
                    (SELECT count(*) FROM o WHERE NOT EXISTS(SELECT 1 FROM r WHERE r.source_id=o.source_id)),
                    (SELECT count(*) FROM r WHERE disposition IN ('CONFLICT','INVALID')),
                    (SELECT count(*) FROM f WHERE NOT EXISTS(SELECT 1 FROM o WHERE o.source_id=f.source_id AND o.occurred_at=f.occurred_at)),
                    (SELECT count(*) FROM r LEFT JOIN f ON f.source_id=r.source_id
                       WHERE r.disposition IN ('INSERTED','VERIFIED') AND
                       (f.source_id IS NULL OR f.digest<>r.digest OR f.schema_version<>r.schema_version)),
                    (SELECT count(*) FROM r WHERE NOT EXISTS(SELECT 1 FROM o WHERE o.source_id=r.source_id))
                """;
            return jdbc.queryForObject(sql,(rs,n)->new Coverage(rs.getTimestamp(1).toInstant(),rs.getLong(2),
                rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8),rs.getLong(9)),
                args.toArray());
        });
    }
}
