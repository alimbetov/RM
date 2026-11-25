package kz.rm.fx.auto;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.Objects;

/** Internal transaction-bound lock only, not authorization or a spending permit.
 * Call after current IAM/currency admission and before owner target locks.
 * A stable row is shared by every policy, currency and run for the tenant.
 */
@Component
public class FxAutoTenantBudgetGuard {
    private final JdbcTemplate jdbc;
    public FxAutoTenantBudgetGuard(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Transactional(propagation=Propagation.MANDATORY)
    public void lock(UUID tenant){
        Objects.requireNonNull(tenant);
        if(!"PostgreSQL".equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                c->c.getMetaData().getDatabaseProductName())) ||
                !"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation",String.class)))
            throw new IllegalStateException("FX_AUTO_POSTGRES_READ_COMMITTED_REQUIRED");
        jdbc.update("INSERT INTO crm.fx_auto_tenant_budget_guard(tenant_id,created_at) VALUES (?,clock_timestamp()) ON CONFLICT (tenant_id) DO NOTHING",tenant);
        UUID locked=jdbc.query("SELECT tenant_id FROM crm.fx_auto_tenant_budget_guard WHERE tenant_id=? FOR UPDATE",
                rs->rs.next()?rs.getObject(1,UUID.class):null,tenant);
        if(!tenant.equals(locked))throw new IllegalStateException("FX_AUTO_BUDGET_GUARD_MISSING");
    }
}
