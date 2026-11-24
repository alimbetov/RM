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
        return balanceAt(tenant,now,limit.seconds(),limit.cap(),limit.count());
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
