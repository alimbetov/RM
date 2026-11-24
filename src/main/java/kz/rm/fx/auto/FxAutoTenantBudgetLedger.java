package kz.rm.fx.auto;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;

/** Read-side of durable tenant consumption. No production debit port yet.
 * The caller must retain the guard through the eventual atomic owner write.
 * A returned balance is diagnostic evidence, never a transferable permit.
 */
@Service
public class FxAutoTenantBudgetLedger {
    private final JdbcTemplate jdbc;
    private final FxAutoTenantBudgetGuard guard;
    public FxAutoTenantBudgetLedger(JdbcTemplate jdbc,FxAutoTenantBudgetGuard guard){
        this.jdbc=jdbc;this.guard=guard;
    }
    public record Balance(Instant asOf,BigDecimal consumedKzt,long consumedCount,
                          BigDecimal remainingKzt,long remainingCount) {
        public boolean fits(BigDecimal proposed){
            return proposed!=null && proposed.signum()>0 && remainingCount>0 && proposed.compareTo(remainingKzt)<=0;
        }
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Balance balance(UUID tenant,UUID policyRevision){
        Objects.requireNonNull(tenant);Objects.requireNonNull(policyRevision);
        guard.lock(tenant);
        record Limit(long seconds,BigDecimal cap,long count){}
        Limit limit=jdbc.query("""
            SELECT l.tenant_window_seconds,l.tenant_delta_cap_kzt,l.tenant_count_cap
            FROM crm.fx_auto_policy_revision p JOIN crm.fx_auto_limit_policy_version l
              ON l.tenant_id=p.tenant_id AND l.id=p.limit_id
            WHERE p.tenant_id=? AND p.id=?
            """,rs->rs.next()?new Limit(rs.getLong(1),rs.getBigDecimal(2),rs.getLong(3)):null,tenant,policyRevision);
        if(limit==null)throw new IllegalArgumentException("FX_AUTO_BUDGET_POLICY_UNAVAILABLE");
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        Balance selected=balanceAt(tenant,now,limit.seconds(),limit.cap(),limit.count());
        // Each previous policy whose consumption is still inside its original
        // window remains a constraint. Never reset capacity by switching policy.
        var historical=jdbc.query("""
            SELECT DISTINCT l.tenant_window_seconds,l.tenant_delta_cap_kzt,l.tenant_count_cap
            FROM crm.fx_auto_budget_consumption c
            JOIN crm.fx_auto_policy_revision p ON p.tenant_id=c.tenant_id AND p.id=c.policy_revision_id
            JOIN crm.fx_auto_limit_policy_version l ON l.tenant_id=p.tenant_id AND l.id=p.limit_id
            WHERE c.tenant_id=? AND c.recorded_at<=?
              AND c.recorded_at > CAST(? AS timestamptz)-l.tenant_window_seconds*interval '1 second'
            """,(rs,n)->new Limit(rs.getLong(1),rs.getBigDecimal(2),rs.getLong(3)),
            tenant,Timestamp.from(now),Timestamp.from(now));
        BigDecimal remaining=selected.remainingKzt();
        long remainingCount=selected.remainingCount();
        for(Limit old:historical){
            Balance previous=balanceAt(tenant,now,old.seconds(),old.cap(),old.count());
            remaining=remaining.min(previous.remainingKzt());
            remainingCount=Math.min(remainingCount,previous.remainingCount());
        }
        return new Balance(now,selected.consumedKzt(),selected.consumedCount(),remaining,remainingCount);
    }

    private Balance balanceAt(UUID tenant,Instant now,long seconds,BigDecimal cap,long count){
        Instant lower=now.minusSeconds(seconds);
        return jdbc.query("""
            SELECT coalesce(sum(delta_kzt),0),count(*)
            FROM crm.fx_auto_budget_consumption
            WHERE tenant_id=? AND recorded_at>? AND recorded_at<=?
            """,rs->{
                if(!rs.next())throw new IllegalStateException("FX_AUTO_BUDGET_READ_FAILED");
                BigDecimal used=rs.getBigDecimal(1);long usedCount=rs.getLong(2);
                return new Balance(now,used,usedCount,cap.subtract(used).max(BigDecimal.ZERO),
                    Math.max(0,count-usedCount));
            },tenant,Timestamp.from(lower),Timestamp.from(now));
    }
}
