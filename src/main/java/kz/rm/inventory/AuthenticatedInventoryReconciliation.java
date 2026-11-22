package kz.rm.inventory;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/**
 * INV-R1.5: diagnostic quantity reconciliation only.
 * Never repairs balances, reservations or immutable ledger records.
 * Historical stock positions without movements cannot prove an opening balance.
 */
@Service
public class AuthenticatedInventoryReconciliation {
    public enum Finding {
        CONSISTENT,
        RESERVED_MISMATCH,
        MOVEMENT_BALANCE_MISMATCH,
        MISSING_BALANCE,
        NO_MOVEMENT_BASELINE,
        MOVEMENT_CHAIN_BREAK,
        HISTORY_WINDOW_LIMIT,
        UNVERIFIED_MOVEMENT_ORDER
    }
    public record Result(UUID tenantId, UUID locationId, UUID itemId,
                         BigDecimal onHand, BigDecimal reserved,
                         BigDecimal activeReservations, BigDecimal latestMovementBalance,
                         Finding finding) {}

    /** Cursor is exclusive; null begins the tenant's ordered position scan. */
    public record Cursor(UUID locationId, UUID itemId) {}
    public record Discrepancy(UUID locationId, UUID itemId, Finding finding,
                              BigDecimal onHand, BigDecimal reserved,
                              BigDecimal activeReservations, BigDecimal latestMovementBalance) {}
    public record Batch(UUID tenantId, int inspected, List<Discrepancy> findings,
                        Cursor nextCursor, boolean hasMore) {}
    private record Key(UUID location, UUID item) {}
    private record Leg(BigDecimal before, BigDecimal after, BigDecimal delta) {}
    private static final int MAX_POSITIONS=50;
    private static final int MAX_MOVEMENTS_PER_POSITION=500;

    /**
     * Read-only tenant-wide diagnostic. Keyset pages include orphan reservations
     * and orphan movements, not only existing balance rows. The entire scan is
     * not a globally consistent snapshot across separate calls.
     */
    // REPEATABLE READ pins balance, reservation and ledger queries to one MVCC
    // snapshot for this call. A cursor across separate calls remains diagnostic.
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Batch scan(VerifiedActor actor, UUID tenant, Cursor after, int size) {
        if(actor==null) throw new SecurityException("Verified actor required");
        if(tenant==null || size<1 || size>MAX_POSITIONS
                || (after!=null && (after.locationId()==null || after.itemId()==null)))
            throw new IllegalArgumentException("Invalid reconciliation scan");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.INVENTORY_STOCK_READ);
        String predicate=after==null?"":" WHERE location_id>? OR (location_id=? AND item_id>?)";
        String sql="""
                SELECT location_id,item_id FROM (
                    SELECT location_id,item_id FROM crm.inv_stock_balance WHERE tenant_id=?
                    UNION
                    SELECT location_id,item_id FROM crm.inv_stock_reservation WHERE tenant_id=?
                    UNION
                    SELECT location_id,item_id FROM crm.inv_stock_movement WHERE tenant_id=?
                ) positions
                """+predicate+" ORDER BY location_id,item_id LIMIT ?";
        Object[] params=after==null?new Object[]{tenant,tenant,tenant,size+1}:
                new Object[]{tenant,tenant,tenant,after.locationId(),after.locationId(),
                        after.itemId(),size+1};
        List<Key> keys=jdbc.query(sql,(rs,n)->new Key(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),params);
        boolean more=keys.size()>size;
        List<Discrepancy> findings=new ArrayList<>();
        int count=Math.min(keys.size(),size);
        for(int i=0;i<count;i++) {
            Key key=keys.get(i);
            Result result=inspectPosition(tenant,key.location(),key.item());
            Finding finding=result.finding();
            if(finding!=Finding.MISSING_BALANCE &&
                    finding!=Finding.UNVERIFIED_MOVEMENT_ORDER) {
                List<Leg> legs=jdbc.query("""
                        SELECT balance_before,balance_after,delta_quantity
                        FROM crm.inv_stock_movement
                        WHERE tenant_id=? AND location_id=? AND item_id=?
                        ORDER BY posting_version DESC NULLS LAST,recorded_at DESC,id DESC LIMIT ?
                        """,(rs,n)->new Leg(rs.getBigDecimal(1),rs.getBigDecimal(2),
                                rs.getBigDecimal(3)),tenant,key.location(),key.item(),
                        MAX_MOVEMENTS_PER_POSITION+1);
                if(!legs.isEmpty()) {
                    boolean broken=false;
                    for(int j=0;j<Math.min(legs.size(),MAX_MOVEMENTS_PER_POSITION);j++) {
                        Leg leg=legs.get(j);
                        if(leg.after().subtract(leg.before()).compareTo(leg.delta())!=0)
                            broken=true;
                        if(j+1<legs.size() && j+1<MAX_MOVEMENTS_PER_POSITION
                                && leg.before().compareTo(legs.get(j+1).after())!=0)
                            broken=true;
                    }
                    if(broken) finding=Finding.MOVEMENT_CHAIN_BREAK;
                    else if(legs.size()>MAX_MOVEMENTS_PER_POSITION &&
                            finding==Finding.CONSISTENT) finding=Finding.HISTORY_WINDOW_LIMIT;
                }
            }
            if(finding!=Finding.CONSISTENT)
                findings.add(new Discrepancy(key.location(),key.item(),finding,
                        result.onHand(),result.reserved(),result.activeReservations(),
                        result.latestMovementBalance()));
        }
        Cursor next=more?new Cursor(keys.get(count-1).location(),keys.get(count-1).item()):null;
        return new Batch(tenant,count,List.copyOf(findings),next,more);
    }

    private final TenantAuthorization authorization;
    private final JdbcTemplate jdbc;

    public AuthenticatedInventoryReconciliation(TenantAuthorization authorization, JdbcTemplate jdbc) {
        this.authorization=authorization;
        this.jdbc=jdbc;
    }

    // The three independent SELECTs MUST observe the same database snapshot.
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Result inspect(VerifiedActor actor, UUID tenant, UUID location, UUID item) {
        if(actor==null)throw new SecurityException("Verified actor required");
        if(tenant==null||location==null||item==null)
            throw new IllegalArgumentException("Tenant, warehouse and SKU required");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.INVENTORY_STOCK_READ);
        return inspectPosition(tenant,location,item);
    }

    private Result inspectPosition(UUID tenant,UUID location,UUID item) {
        BigDecimal[] position=jdbc.query("""
                SELECT on_hand,reserved FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,rs->rs.next()?new BigDecimal[]{rs.getBigDecimal(1),rs.getBigDecimal(2)}:null,
                tenant,location,item);
        BigDecimal active=jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity),0) FROM crm.inv_stock_reservation
                WHERE tenant_id=? AND location_id=? AND item_id=? AND state='ACTIVE'
                """,BigDecimal.class,tenant,location,item);
        BigDecimal latest=jdbc.query("""
                SELECT balance_after FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=? AND item_id=?
                ORDER BY posting_version DESC NULLS LAST,recorded_at DESC,id DESC LIMIT 1
                """,rs->rs.next()?rs.getBigDecimal(1):null,tenant,location,item);
        if(active==null)active=BigDecimal.ZERO;
        if(position==null) {
            if(active.signum()==0 && latest==null)
                throw new IllegalArgumentException("Position not found in tenant");
            return new Result(tenant,location,item,null,null,active,latest,Finding.MISSING_BALANCE);
        }
        Long unverifiable=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=? AND item_id=? AND posting_version IS NULL
                """,Long.class,tenant,location,item);
        Finding finding=position[1].compareTo(active)!=0?Finding.RESERVED_MISMATCH:
                latest==null?Finding.NO_MOVEMENT_BASELINE:
                unverifiable!=null && unverifiable>0?Finding.UNVERIFIED_MOVEMENT_ORDER:
                latest.compareTo(position[0])!=0?Finding.MOVEMENT_BALANCE_MISMATCH:
                Finding.CONSISTENT;
        return new Result(tenant,location,item,position[0],position[1],active,latest,finding);
    }
}
