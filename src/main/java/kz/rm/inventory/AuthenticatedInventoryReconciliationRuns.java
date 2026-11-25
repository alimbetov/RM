package kz.rm.inventory;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * INV-R1.5B: durable, tenant-bound diagnostic progress.
 * The only writes are to inv_reconciliation_run/finding, never inventory facts.
 * Each call commits a bounded number of positions and full streamed movement chains.
 */
@Service
public class AuthenticatedInventoryReconciliationRuns {
    public enum Status { RUNNING, COMPLETED, FAILED }
    public enum Code {
        MISSING_BALANCE, RESERVED_MISMATCH, MOVEMENT_BALANCE_MISMATCH,
        MOVEMENT_CHAIN_BREAK, NO_MOVEMENT_BASELINE, UNVERIFIED_MOVEMENT_ORDER
    }
    public record Run(UUID id, UUID tenantId, Status status, long inspectedPositions,
                      long findingCount, UUID cursorLocationId, UUID cursorItemId) {}
    public record Finding(UUID locationId, UUID itemId, Code code,
                          BigDecimal onHand, BigDecimal reserved,
                          BigDecimal activeReservations, BigDecimal ledgerBalance,
                          long inspectedMovements) {}
    public record Step(Run run, List<Finding> newFindings) {}
    private record Key(UUID location, UUID item) {}
    private record Position(BigDecimal onHand, BigDecimal reserved) {}
    private record Chain(long count, boolean broken, boolean orderUnverified, BigDecimal latest) {}

    private static final int MAX_PAGE=20;
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public AuthenticatedInventoryReconciliationRuns(JdbcTemplate jdbc,
                                                      TenantAuthorization authorization) {
        this.jdbc=jdbc;
        this.authorization=authorization;
    }

    private void admit(VerifiedActor actor,UUID tenant) {
        if(actor==null)throw new SecurityException("Verified actor required");
        if(tenant==null)throw new IllegalArgumentException("Tenant required");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.INVENTORY_STOCK_READ);
    }

    @Transactional
    public Run start(VerifiedActor actor,UUID tenant) {
        admit(actor,tenant);
        UUID id=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO crm.inv_reconciliation_run
                  (tenant_id,id,status,started_by,started_at,updated_at)
                VALUES (?,?,'RUNNING',?,?,?)
                """,tenant,id,actor.userId(),now,now);
        return new Run(id,tenant,Status.RUNNING,0,0,null,null);
    }

    /**
     * Locks only the run row, serializing concurrent workers for this one run.
     * A failed transaction rolls back its findings and cursor together.
     * Independent steps are NOT a single point-in-time inventory snapshot.
     */
    // A single page's keys, balance, active reserves and movement chain are
    // read from one repeatable-read snapshot. Rows from distinct pages are
    // NOT a certified tenant-wide as-of snapshot. Concurrent advances or
    // writes may cause a serialization failure; callers retry the entire step.
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Step advance(VerifiedActor actor,UUID tenant,UUID runId,int pageSize) {
        admit(actor,tenant);
        if(runId==null||pageSize<1||pageSize>MAX_PAGE)
            throw new IllegalArgumentException("Invalid reconciliation page");
        Run run=load(tenant,runId,true);
        if(run.status()!=Status.RUNNING) return new Step(run,List.of());

        String cursor=run.cursorLocationId()==null?"":" WHERE location_id>? OR (location_id=? AND item_id>?)";
        String sql="""
                SELECT location_id,item_id FROM (
                    SELECT location_id,item_id FROM crm.inv_stock_balance WHERE tenant_id=?
                    UNION
                    SELECT location_id,item_id FROM crm.inv_stock_reservation WHERE tenant_id=?
                    UNION
                    SELECT location_id,item_id FROM crm.inv_stock_movement WHERE tenant_id=?
                ) positions
                """+cursor+" ORDER BY location_id,item_id LIMIT ?";
        Object[] params=run.cursorLocationId()==null?
                new Object[]{tenant,tenant,tenant,pageSize+1}:
                new Object[]{tenant,tenant,tenant,run.cursorLocationId(),
                        run.cursorLocationId(),run.cursorItemId(),pageSize+1};
        List<Key> keys=jdbc.query(sql,(rs,n)->new Key(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),params);
        int count=Math.min(keys.size(),pageSize);
        List<Finding> findings=new ArrayList<>();
        for(int i=0;i<count;i++) {
            Key key=keys.get(i);
            Finding finding=check(tenant,key);
            if(finding!=null) {
                findings.add(finding);
                jdbc.update("""
                        INSERT INTO crm.inv_reconciliation_finding
                          (tenant_id,run_id,location_id,item_id,finding_code,on_hand,
                           reserved,active_reservations,ledger_balance,
                           inspected_movements,recorded_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)
                        """,tenant,runId,key.location(),key.item(),finding.code().name(),
                        finding.onHand(),finding.reserved(),finding.activeReservations(),
                        finding.ledgerBalance(),finding.inspectedMovements(),
                        Timestamp.from(Instant.now()));
            }
        }
        Key last=count>0?keys.get(count-1):null;
        boolean hasMore=keys.size()>pageSize;
        boolean completed=!hasMore;
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
                UPDATE crm.inv_reconciliation_run SET
                  cursor_location_id=COALESCE(?,cursor_location_id),
                  cursor_item_id=COALESCE(?,cursor_item_id),
                  inspected_positions=inspected_positions+?,
                  finding_count=finding_count+?,
                  status=?,updated_at=?,completed_at=?
                WHERE tenant_id=? AND id=? AND status='RUNNING'
                """,last==null?null:last.location(),last==null?null:last.item(),
                count,findings.size(),completed?"COMPLETED":"RUNNING",now,
                completed?now:null,tenant,runId);
        return new Step(load(tenant,runId,false),List.copyOf(findings));
    }

    /** Explicit terminal failure, e.g. when caller rejects an inconsistent scan epoch. */
    @Transactional
    public Run fail(VerifiedActor actor,UUID tenant,UUID runId) {
        admit(actor,tenant);
        if(runId==null)throw new IllegalArgumentException("Run required");
        Run run=load(tenant,runId,true);
        if(run.status()!=Status.RUNNING)return run;
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
                UPDATE crm.inv_reconciliation_run
                   SET status='FAILED',failure_code='OPERATOR_ABORTED',
                       completed_at=?,updated_at=?
                 WHERE tenant_id=? AND id=? AND status='RUNNING'
                """,now,now,tenant,runId);
        return load(tenant,runId,false);
    }

    @Transactional
    public Run get(VerifiedActor actor,UUID tenant,UUID runId) {
        admit(actor,tenant);
        if(runId==null)throw new IllegalArgumentException("Run required");
        return load(tenant,runId,false);
    }

    /** Bounded tenant-authorized access to saved diagnostic findings. */
    @Transactional
    public List<Finding> findings(VerifiedActor actor,UUID tenant,UUID runId,int page,int size) {
        admit(actor,tenant);
        if(runId==null||page<0||size<1||size>100)
            throw new IllegalArgumentException("Invalid finding page");
        load(tenant,runId,false);
        return jdbc.query("""
                SELECT location_id,item_id,finding_code,on_hand,reserved,
                       active_reservations,ledger_balance,inspected_movements
                FROM crm.inv_reconciliation_finding
                WHERE tenant_id=? AND run_id=?
                ORDER BY location_id,item_id LIMIT ? OFFSET ?
                """,(rs,n)->new Finding(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        Code.valueOf(rs.getString(3)),rs.getBigDecimal(4),rs.getBigDecimal(5),
                        rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getLong(8)),
                tenant,runId,size,(long)page*size);
    }

    private Run load(UUID tenant,UUID id,boolean lock) {
        return jdbc.query("""
                SELECT id,status,inspected_positions,finding_count,
                       cursor_location_id,cursor_item_id
                FROM crm.inv_reconciliation_run WHERE tenant_id=? AND id=?
                """+(lock?" FOR UPDATE":""),
                rs->{
                    if(!rs.next())throw new IllegalArgumentException("Run not found in tenant");
                    return new Run(rs.getObject(1,UUID.class),tenant,
                            Status.valueOf(rs.getString(2)),rs.getLong(3),rs.getLong(4),
                            rs.getObject(5,UUID.class),rs.getObject(6,UUID.class));
                },tenant,id);
    }

    private Finding check(UUID tenant,Key key) {
        Position position=jdbc.query("""
                SELECT on_hand,reserved FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,rs->rs.next()?new Position(rs.getBigDecimal(1),rs.getBigDecimal(2)):null,
                tenant,key.location(),key.item());
        BigDecimal active=jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity),0) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND location_id=? AND item_id=? AND state='ACTIVE'
                """,BigDecimal.class,tenant,key.location(),key.item());
        if(active==null)active=BigDecimal.ZERO;
        Chain chain=chain(tenant,key);
        Code code;
        if(position==null) code=Code.MISSING_BALANCE;
        else if(position.reserved().compareTo(active)!=0) code=Code.RESERVED_MISMATCH;
        else if(chain.broken()) code=Code.MOVEMENT_CHAIN_BREAK;
        else if(chain.orderUnverified()) code=Code.UNVERIFIED_MOVEMENT_ORDER;
        else if(chain.count()>0 && position.onHand().compareTo(chain.latest())!=0)
            code=Code.MOVEMENT_BALANCE_MISMATCH;
        else if(chain.count()==0) code=Code.NO_MOVEMENT_BASELINE;
        else return null;
        return new Finding(key.location(),key.item(),code,
                position==null?null:position.onHand(),
                position==null?null:position.reserved(),active,
                chain.latest(),chain.count());
    }

    /**
     * JDBC ResultSetExtractor reads a complete ordered history without materializing
     * rows in Java. No LIMIT 500 and no accidental false CONSISTENT beyond page one.
     * Long histories can still hold a transaction open; throughput qualification needed.
     */
    private Chain chain(UUID tenant,Key key) {
        return jdbc.query("""
                SELECT balance_before,balance_after,delta_quantity,posting_version
                FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=? AND item_id=?
                ORDER BY posting_version ASC NULLS FIRST,recorded_at ASC,id ASC
                """,ps->{
                    ps.setObject(1,tenant);
                    ps.setObject(2,key.location());
                    ps.setObject(3,key.item());
                    ps.setFetchSize(256);
                },rs->{
                    long count=0;
                    boolean broken=false;
                    boolean unverified=false;
                    BigDecimal preceding=null,latest=null;
                    while(rs.next()) {
                        BigDecimal before=rs.getBigDecimal(1);
                        BigDecimal after=rs.getBigDecimal(2);
                        BigDecimal delta=rs.getBigDecimal(3);
                        Long version=rs.getObject(4,Long.class);
                        if(version==null) unverified=true;
                        if(after.subtract(before).compareTo(delta)!=0) broken=true;
                        // Continuity across a legacy timestamp/UUID ordering is
                        // not evidence of the original serial posting order.
                        if(!unverified && preceding!=null && preceding.compareTo(before)!=0)
                            broken=true;
                        preceding=after;
                        latest=after;
                        count++;
                    }
                    return new Chain(count,broken,unverified,latest);
                });
    }
}
