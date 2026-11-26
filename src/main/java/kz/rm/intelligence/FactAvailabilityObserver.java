package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.UUID;

/** Explicit internal polling entry point. Only observes facts visible in a separate committed snapshot.
 * Not a scheduler or proof of first availability. */
@Service
class FactAvailabilityObserver {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    FactAvailabilityObserver(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.manager=manager;}
    int observe(UUID tenant,int limit){
        Objects.requireNonNull(tenant);
        if(limit<1||limit>10000)throw new IllegalArgumentException("OBSERVATION_LIMIT");
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("OBSERVATION_REQUIRES_SEPARATE_TRANSACTION");
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(60);
        return tx.execute(status->{
            // Serialize cooperating observers per tenant. Separate statement gives fresh RC visibility.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),7215))",
                rs->{rs.next();return Boolean.TRUE;},tenant);
            return jdbc.update("""
                INSERT INTO crm.int_fact_availability_observation(tenant_id,owner_module,source_kind,source_id)
                SELECT f.tenant_id,f.owner_module,f.source_kind,f.source_id
                FROM crm.int_canonical_fact f WHERE f.tenant_id=?
                AND NOT EXISTS(SELECT 1 FROM crm.int_fact_availability_observation o
                    WHERE (o.tenant_id,o.owner_module,o.source_kind,o.source_id)=
                          (f.tenant_id,f.owner_module,f.source_kind,f.source_id))
                ORDER BY f.owner_module,f.source_kind,f.source_id LIMIT ?
                """,tenant,limit);
        });
    }
}
