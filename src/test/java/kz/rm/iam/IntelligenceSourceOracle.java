package kz.rm.iam;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Independent IE-00B source oracle for isolated, service-created tenant fixtures.
 * Lifetime fixture totals, not an authorized runtime metric API or period coverage proof.
 */
public final class IntelligenceSourceOracle {
    private IntelligenceSourceOracle() {}

    public static void assertTenantFacts(JdbcTemplate jdbc, UUID tenant, UUID warehouse, UUID sku,
            int placed, String booked, int confirmed, int fulfilled, int cancelled,
            String stockIn, String stockOut, String available) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=?",
                Integer.class, tenant)).isEqualTo(placed);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order o WHERE o.tenant_id=?
                AND (SELECT count(*) FROM crm.sales_order_status_event e
                     WHERE e.tenant_id=o.tenant_id AND e.order_id=o.id AND e.event_type='PLACED')<>1
                """, Integer.class, tenant)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT order_id,event_type FROM crm.sales_order_status_event
                  WHERE tenant_id=? AND event_type IN ('PLACED','CONFIRMED','COMPLETED','CANCELLED')
                  GROUP BY order_id,event_type HAVING count(*)<>1
                ) duplicates
                """, Integer.class, tenant)).isZero();
        assertTransition(jdbc,tenant,"PLACED",placed);
        assertTransition(jdbc,tenant,"CONFIRMED",confirmed);
        assertTransition(jdbc,tenant,"COMPLETED",fulfilled);
        assertTransition(jdbc,tenant,"CANCELLED",cancelled);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.sales_order WHERE tenant_id=? AND currency<>'KZT'",
                Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT sum(o.total_amount) FROM crm.sales_order o
                JOIN crm.sales_order_status_event e ON e.tenant_id=o.tenant_id AND e.order_id=o.id
                WHERE o.tenant_id=? AND e.event_type='PLACED' AND o.currency='KZT'
                """,BigDecimal.class,tenant)).isEqualByComparingTo(booked);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order o WHERE o.tenant_id=? AND
                (o.total_amount<>(SELECT sum(l.quantity*l.unit_price_snapshot)
                     FROM crm.sales_order_line l WHERE l.tenant_id=o.tenant_id AND l.order_id=o.id)
                 OR NOT EXISTS (SELECT 1 FROM crm.sales_order_line l
                     WHERE l.tenant_id=o.tenant_id AND l.order_id=o.id)
                 OR EXISTS (SELECT 1 FROM crm.sales_order_line l
                     WHERE l.tenant_id=o.tenant_id AND l.order_id=o.id
                     AND (l.currency<>o.currency OR l.catalog_item_id_snapshot IS NULL
                          OR l.catalog_item_id_snapshot<>?)))
                """,Integer.class,tenant,sku)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT coalesce(sum(delta_quantity),0) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=? AND item_id=? AND delta_quantity>0
                """,BigDecimal.class,tenant,warehouse,sku)).isEqualByComparingTo(stockIn);
        assertThat(jdbc.queryForObject("""
                SELECT coalesce(sum(-delta_quantity),0) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND location_id=? AND item_id=? AND delta_quantity<0
                """,BigDecimal.class,tenant,warehouse,sku)).isEqualByComparingTo(stockOut);
        assertThat(jdbc.queryForObject("""
                SELECT on_hand-reserved FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,BigDecimal.class,tenant,warehouse,sku)).isEqualByComparingTo(available);
    }

    private static void assertTransition(JdbcTemplate jdbc,UUID tenant,String event,int expected) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order_status_event WHERE tenant_id=? AND event_type=?
                """,Integer.class,tenant,event)).isEqualTo(expected);
    }
}
