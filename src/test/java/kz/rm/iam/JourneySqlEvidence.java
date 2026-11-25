package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Independent post-commit SQL projections: no JWT, PII or pickup secret in evidence. */
public final class JourneySqlEvidence {
    private JourneySqlEvidence(){}
    private static void outsideTransaction(){
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new AssertionError("J1 SQL evidence must be read outside the business transaction");
    }
    public static List<Map<String,Object>> invoices(JdbcTemplate jdbc,UUID tenant){
        outsideTransaction();
        return jdbc.queryForList("SELECT id,order_id,amount,currency,state,row_version FROM crm.fin_invoice WHERE tenant_id=? ORDER BY id",tenant);
    }
    public static List<Object> orderAndStock(JdbcTemplate jdbc,UUID tenant,UUID order,UUID warehouse,UUID sku){
        outsideTransaction();
        return List.of(
            jdbc.queryForList("SELECT id,status,row_version,total_amount,currency,fulfillment_mode FROM crm.sales_order WHERE tenant_id=? AND id=?",tenant,order),
            jdbc.queryForList("SELECT id,quantity,unit_price_snapshot,currency FROM crm.sales_order_line WHERE tenant_id=? AND order_id=? ORDER BY id",tenant,order),
            jdbc.queryForList("SELECT id,event_type,occurred_at FROM crm.sales_order_status_event WHERE tenant_id=? AND order_id=? ORDER BY id",tenant,order),
            jdbc.queryForList("SELECT owner_module,source_kind,source_id,subject_id,occurred_at,recorded_at,source_version,schema_version,digest FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module='SALES' AND subject_id=? ORDER BY source_kind,source_id",tenant,order),
            jdbc.queryForList("SELECT owner_module,source_kind,source_id,subject_id,occurred_at,recorded_at,source_version,schema_version,digest FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module='INVENTORY' AND subject_id=? ORDER BY source_id",tenant,sku),
            jdbc.queryForList("SELECT on_hand,reserved,row_version FROM crm.inv_stock_balance WHERE tenant_id=? AND location_id=? AND item_id=?",tenant,warehouse,sku),
            jdbc.queryForObject("SELECT count(*) FROM crm.inv_stock_reservation WHERE tenant_id=? AND order_id=?",Long.class,tenant,order),
            jdbc.queryForObject("SELECT count(*) FROM crm.inv_stock_movement WHERE tenant_id=? AND item_id=?",Long.class,tenant,sku),
            jdbc.queryForObject("SELECT count(*) FROM crm.sales_pickup_handover WHERE tenant_id=? AND order_id=?",Long.class,tenant,order),
            invoices(jdbc,tenant));
    }
}
