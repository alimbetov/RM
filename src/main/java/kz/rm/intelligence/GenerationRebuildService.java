package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/** B4.7 internal bounded replay. Durable receipts and period stamps are the checkpoint.
 * No scheduler, automatic activation, source mutation or public analytical access.
 */
@Service
public class GenerationRebuildService {
    private final JdbcTemplate jdbc;
    private final L1ProjectionService l1;
    private final L2ProjectionService l2;
    private final L3RollupService l3;
    private final CanonicalFactDeliveryService delivery;
    private final MultidimensionalCoverageService coverage;
    public GenerationRebuildService(JdbcTemplate jdbc,L1ProjectionService l1,L2ProjectionService l2,
            L3RollupService l3,CanonicalFactDeliveryService delivery,MultidimensionalCoverageService coverage){
        this.jdbc=jdbc;this.l1=l1;this.l2=l2;this.l3=l3;this.delivery=delivery;this.coverage=coverage;
    }
    public enum State { REPLAY_PENDING, ROLLUP_PENDING, SOURCE_INCOMPLETE, READY, STALE }
    public record Request(UUID tenant,UUID generation,long calendar,LocalDate from,LocalDate until){
        public Request{
            Objects.requireNonNull(tenant);Objects.requireNonNull(generation);
            Objects.requireNonNull(from);Objects.requireNonNull(until);
            if(calendar<1||!from.isBefore(until)||java.time.temporal.ChronoUnit.DAYS.between(from,until)>3660)
                throw new IllegalArgumentException("INT_REBUILD_SCOPE");
        }
    }
    public enum Reason { PENDING_RECEIPTS, REJECTED_OR_MISMATCHED_RECEIPTS, PERIOD_BUDGET,
        DELIVERY_MISMATCH, CONTRIBUTION_MISMATCH, PERIOD_MISMATCH, CELL_OR_MEMBERSHIP_MISMATCH, REVISION_CHANGED, QUALIFIED }
    public record Progress(State state,int l1Steps,int l2Steps,int refreshed,UUID proof,Reason reason){}
    private record PeriodKey(PeriodResolver.Code code,LocalDate from,LocalDate until){}
    private record Stamp(PeriodKey key,Long revision){}
    /** Each step commits separately; callers may restart with the identical request.
     * READY means snapshot-qualified, not admitted. Activation always rechecks proof.
     */
    public Progress step(Request r,int factBudget,int periodBudget){
        Objects.requireNonNull(r);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("INT_REBUILD_OWN_TRANSACTIONS");
        if(factBudget<1||factBudget>CanonicalFactDeliveryService.MAX_BATCH||periodBudget<1||periodBudget>128)
            throw new IllegalArgumentException("INT_REBUILD_BUDGET");
        var g=l1.open(r.tenant(),r.generation(),r.calendar(),r.from(),r.until());
        String consumer=l2.open(r.tenant(),r.generation());
        int n1=l1.advance(r.tenant(),r.generation(),factBudget).size();
        int n2=l2.advance(r.tenant(),r.generation(),factBudget).size();
        var c1=delivery.consumerCoverage(r.tenant(),g.consumerCode());
        var c2=delivery.consumerCoverage(r.tenant(),consumer);
        if(c1.rejected()>0||c2.rejected()>0||c1.receiptMismatches()>0||c2.receiptMismatches()>0)
            return new Progress(State.SOURCE_INCOMPLETE,n1,n2,0,null,Reason.REJECTED_OR_MISMATCHED_RECEIPTS);
        if(!c1.caughtUp()||!c2.caughtUp())return new Progress(State.REPLAY_PENDING,n1,n2,0,null,Reason.PENDING_RECEIPTS);
        long dirty=Objects.requireNonNull(jdbc.queryForObject("""
            SELECT dirty_revision FROM crm.int_dim_revision WHERE tenant_id=? AND generation_id=?
            """,Long.class,r.tenant(),r.generation()));
        var stamps=new HashMap<PeriodKey,Long>();
        for(var s:jdbc.query("""
            SELECT period_code,from_day,until_day,refreshed_revision FROM crm.int_l3_period
            WHERE tenant_id=? AND generation_id=?
            """,(rs,n)->new Stamp(new PeriodKey(PeriodResolver.Code.valueOf(rs.getString(1)),
                rs.getObject(2,LocalDate.class),rs.getObject(3,LocalDate.class)),rs.getObject(4,Long.class)),r.tenant(),r.generation()))
            stamps.put(s.key(),s.revision());
        int refreshed=0;
        var policy=new PeriodResolver.Policy(g.zone(),r.calendar());
        for(var code:PeriodResolver.Code.values()){
            LocalDate cursor=r.from();
            while(cursor.isBefore(r.until())){
                var period=PeriodResolver.resolve(code,cursor,policy);
                LocalDate a=period.startInclusive().atZone(g.zone()).toLocalDate();
                LocalDate b=period.endExclusive().atZone(g.zone()).toLocalDate();
                if(!b.isAfter(cursor))throw new IllegalStateException("INT_REBUILD_CALENDAR_PROGRESS");
                if(!a.isBefore(r.from())&&!b.isAfter(r.until())&&
                   !Objects.equals(stamps.get(new PeriodKey(code,a,b)),dirty)){
                    if(refreshed==periodBudget)return new Progress(State.ROLLUP_PENDING,n1,n2,refreshed,null,Reason.PERIOD_BUDGET);
                    if(l3.refreshIfStale(r.tenant(),r.generation(),code,cursor).isPresent())refreshed++;
                }
                cursor=b;
            }
        }
        // Independent owner oracle, not receipt counts or rollup stamps alone.
        var proof=coverage.proveOrReuse(r.tenant(),r.generation());
        if(!proof.parity()){
            var latest1=delivery.consumerCoverage(r.tenant(),g.consumerCode());
            var latest2=delivery.consumerCoverage(r.tenant(),consumer);
            if(latest1.rejected()>0||latest2.rejected()>0||latest1.receiptMismatches()>0||latest2.receiptMismatches()>0)
                return new Progress(State.SOURCE_INCOMPLETE,n1,n2,refreshed,proof.id(),Reason.REJECTED_OR_MISMATCHED_RECEIPTS);
            if(latest1.pending()>0||latest2.pending()>0)
                return new Progress(State.REPLAY_PENDING,n1,n2,refreshed,proof.id(),Reason.PENDING_RECEIPTS);
            Reason reason=proof.delivery()>0?Reason.DELIVERY_MISMATCH:
                proof.contributions()>0?Reason.CONTRIBUTION_MISMATCH:
                proof.periods()>0?Reason.PERIOD_MISMATCH:Reason.CELL_OR_MEMBERSHIP_MISMATCH;
            return new Progress(reason==Reason.PERIOD_MISMATCH?State.ROLLUP_PENDING:State.SOURCE_INCOMPLETE,
                n1,n2,refreshed,proof.id(),reason);
        }
        if(coverage.status(r.tenant(),r.generation(),proof.id())!=MultidimensionalCoverageService.Status.CURRENT)
            return new Progress(State.STALE,n1,n2,refreshed,proof.id(),Reason.REVISION_CHANGED);
        return new Progress(State.READY,n1,n2,refreshed,proof.id(),Reason.QUALIFIED);
    }
}
