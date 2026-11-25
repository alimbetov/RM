package kz.rm.iam;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Independent owner journal -> canonical fact oracle; no appender calls. */
public final class CheckoutCanonicalEvidence {
    private CheckoutCanonicalEvidence(){}
    public static UUID assertPlaced(JdbcTemplate jdbc,UUID tenant,UUID order){
        UUID event=jdbc.queryForObject("""
            SELECT id FROM crm.sales_order_status_event
            WHERE tenant_id=? AND order_id=? AND event_type='PLACED'
            """,UUID.class,tenant,order);
        assertThat(event).isNotNull();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.int_canonical_fact f
            JOIN crm.sales_order_status_event e ON e.tenant_id=f.tenant_id AND e.id=f.source_id
            WHERE f.tenant_id=? AND f.subject_id=? AND f.owner_module='SALES'
              AND f.source_kind='ORDER_PLACED' AND f.source_id=?
              AND e.order_id=f.subject_id AND e.event_type='PLACED'
              AND e.occurred_at=f.occurred_at AND f.recorded_at IS NULL
              AND f.source_version IS NULL AND f.schema_version=2
              AND length(f.digest)=64
            """,Integer.class,tenant,order,event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=? AND subject_id=?
              AND owner_module='SALES' AND source_kind='ORDER_PLACED'
            """,Integer.class,tenant,order)).isEqualTo(1);
        return event;
    }
}
