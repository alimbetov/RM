package kz.rm.intelligence;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual migrated source schema, not a claim of historical data completeness. */
@SpringBootTest
class IntelligenceSourceSchemaQualificationTest {
    @Autowired JdbcTemplate jdbc;

    private record Source(String schema,String table,List<String> key,List<String> columns) {}
    private static final List<Source> SOURCES=List.of(
        new Source("crm","sales_order",List.of("tenant_id","id"),List.of("buyer_user_id","total_amount","currency","status","created_at")),
        new Source("crm","sales_order_status_event",List.of("tenant_id","id"),List.of("order_id","event_type","occurred_at")),
        new Source("crm","sales_order_line",List.of("tenant_id","id"),List.of("order_id","quantity","unit_price_snapshot","currency","catalog_item_id_snapshot")),
        new Source("crm","inv_stock_balance",List.of("tenant_id","location_id","item_id"),List.of("on_hand","reserved")),
        new Source("crm","inv_stock_movement",List.of("tenant_id","id"),List.of("location_id","item_id","delta_quantity","occurred_at","recorded_at")),
        new Source("crm","customer_buyer_link",List.of("tenant_id","id"),List.of("customer_id","buyer_user_id","status","confirmed_at")),
        new Source("crm","int_fact_consumer",List.of("tenant_id","consumer_code"),List.of("created_at")),
        new Source("crm","int_fact_consumer_receipt",List.of("tenant_id","consumer_code","owner_module","source_kind","source_id"),List.of("digest","schema_version","disposition","error_code","processed_at")),
        new Source("crm","int_fact_backfill_run",List.of("tenant_id","run_id"),List.of("owner_module","source_kind","range_start","range_end","created_at","time_basis")),
        new Source("crm","int_projection_generation",List.of("tenant_id","generation_id"),List.of("state","projection_code","consumer_code","method_version","calendar_version","from_day","until_day")),
        new Source("crm","int_daily_bucket",List.of("tenant_id","generation_id","metric_code","local_day","currency_key","warehouse_key","sku_key"),List.of("amount","observations")),
        new Source("crm","int_l1_sales_contribution",List.of("tenant_id","generation_id","event_kind","order_id"),List.of("local_day","currency","booked_amount")),
        new Source("crm","int_projection_coverage",List.of("tenant_id","generation_id","proof_id"),List.of("observed_at","missing_facts","orphan_facts","pending_facts","rejected_facts","receipt_mismatches","bucket_mismatches")),
        new Source("crm","int_fact_backfill_receipt",List.of("tenant_id","run_id","owner_module","source_kind","source_id"),List.of("digest","schema_version","disposition","error_code","processed_at")),
        new Source("public","iam_warehouse_staff_scope",List.of("tenant_id","warehouse_id","actor_user_id"),List.of("state","row_version"))
    );

    @Test void migratedSourceColumnsAndOrderedTenantPrimaryKeys() {
        for(var source:SOURCES) {
            var columns=jdbc.queryForList("""
                SELECT lower(column_name) FROM information_schema.columns
                WHERE lower(table_schema)=? AND lower(table_name)=?
                """,String.class,source.schema(),source.table());
            assertThat(columns).as(source.table()).containsAll(source.key()).containsAll(source.columns());
            var pk=jdbc.queryForList("""
                SELECT lower(k.column_name)
                FROM information_schema.table_constraints c
                JOIN information_schema.key_column_usage k
                  ON k.constraint_catalog=c.constraint_catalog
                 AND k.constraint_schema=c.constraint_schema
                 AND k.constraint_name=c.constraint_name
                 AND k.table_schema=c.table_schema AND k.table_name=c.table_name
                WHERE lower(c.table_schema)=? AND lower(c.table_name)=?
                  AND c.constraint_type='PRIMARY KEY'
                ORDER BY k.ordinal_position
                """,String.class,source.schema(),source.table());
            assertThat(pk).as(source.table()).containsExactlyElementsOf(source.key());
        }
    }

    @Test void sourceNumericAndTimestampTypesArePreserved() {
        for(var entry:Map.of("sales_order",List.of("total_amount"),
                "sales_order_line",List.of("unit_price_snapshot"),
                "inv_stock_balance",List.of("on_hand","reserved"),
                "inv_stock_movement",List.of("delta_quantity")).entrySet()) {
            for(String col:entry.getValue()) {
                var type=jdbc.queryForObject("""
                    SELECT lower(data_type) FROM information_schema.columns
                    WHERE lower(table_schema)='crm' AND lower(table_name)=? AND lower(column_name)=?
                    """,String.class,entry.getKey(),col);
                assertThat(type).isIn("numeric","decimal");
            }
        }
        for(var entry:Map.of("sales_order_status_event",List.of("occurred_at"),
                "inv_stock_movement",List.of("occurred_at","recorded_at")).entrySet()) {
            for(String col:entry.getValue()) {
                var type=jdbc.queryForObject("""
                    SELECT lower(data_type) FROM information_schema.columns
                    WHERE lower(table_schema)='crm' AND lower(table_name)=? AND lower(column_name)=?
                    """,String.class,entry.getKey(),col);
                assertThat(type).isEqualTo("timestamp with time zone");
            }
        }
    }
}
