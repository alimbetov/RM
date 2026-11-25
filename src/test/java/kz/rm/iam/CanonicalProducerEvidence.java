package kz.rm.iam;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

/** Independent persisted owner journal reconciliation, for fresh isolated test tenants. */
public final class CanonicalProducerEvidence {
    private CanonicalProducerEvidence(){}
    private static String val(Object v){
        return v instanceof BigDecimal d?d.stripTrailingZeros().toPlainString():String.valueOf(v);
    }
    private static String frame(Object... values){
        return Arrays.stream(values).map(v->v==null?"-1:":val(v).length()+":"+val(v)).collect(Collectors.joining());
    }
    private static String hash(String s){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    public static void assertMovement(JdbcTemplate jdbc,UUID tenant,UUID movement){
        var expected=jdbc.query("""
            SELECT item_id,occurred_at,recorded_at,location_id,delta_quantity,balance_before,balance_after,
                   posting_version,reason_code,movement_kind,source_type,source_id,reservation_id,actor_user_id,business_reason_code,request_key
            FROM crm.inv_stock_movement WHERE tenant_id=? AND id=?
            """,rs->{
                if(!rs.next())throw new AssertionError("Missing movement");
                String legacy=hash(String.join("|","RM-FACT-V1",tenant.toString(),"INVENTORY","STOCK_MOVEMENT",
                    movement.toString(),rs.getObject(1).toString(),rs.getTimestamp(2).toInstant().toString(),
                    rs.getTimestamp(3).toInstant().toString(),rs.getObject(4).toString(),
                    val(rs.getBigDecimal(5)),val(rs.getBigDecimal(6)),val(rs.getBigDecimal(7))));
                Object[] extended=new Object[9];
                for(int i=0;i<9;i++)extended[i]=rs.getObject(i+8);
                return hash(frame("RM-FACT-V2",legacy,frame(extended)));
            },tenant,movement);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM crm.int_canonical_fact f JOIN crm.inv_stock_movement m
                ON m.tenant_id=f.tenant_id AND m.id=f.source_id
            WHERE f.tenant_id=? AND f.source_id=? AND f.owner_module='INVENTORY' AND f.source_kind='STOCK_MOVEMENT'
                AND f.subject_id=m.item_id AND f.occurred_at=m.occurred_at AND f.recorded_at=m.recorded_at
                AND f.source_version=m.posting_version AND f.schema_version=2 AND f.digest=?
            """,Integer.class,tenant,movement,expected)).isEqualTo(1);
    }
    public static void assertInventory(JdbcTemplate jdbc,UUID tenant){
        var movements=jdbc.query("SELECT id FROM crm.inv_stock_movement WHERE tenant_id=? ORDER BY id",
            (rs,n)->rs.getObject(1,UUID.class),tenant);
        for(UUID movement:movements)assertMovement(jdbc,tenant,movement);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module='INVENTORY'",Integer.class,tenant))
            .isEqualTo(movements.size());
    }
    public static void assertSales(JdbcTemplate jdbc,UUID tenant){
        var sources=jdbc.query("""
            SELECT order_id,event_type FROM crm.sales_order_status_event
            WHERE tenant_id=? AND event_type IN ('PLACED','CONFIRMED','CANCELLED','COMPLETED')
            ORDER BY id
            """,(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2)},tenant);
        for(Object[] source:sources){
            UUID order=(UUID)source[0];String status=(String)source[1];
            UUID event=CheckoutCanonicalEvidence.assertStatus(jdbc,tenant,order,status);
            assertThat(jdbc.queryForObject("SELECT digest FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module='SALES' AND source_id=?",
                String.class,tenant,event)).isEqualTo(salesDigest(jdbc,tenant,order,event,status));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module='SALES'",Integer.class,tenant)).isEqualTo(sources.size());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM (SELECT subject_id FROM crm.int_canonical_fact
            WHERE tenant_id=? AND owner_module='SALES' AND source_kind IN ('ORDER_COMPLETED','ORDER_CANCELLED')
            GROUP BY subject_id HAVING count(*)>1) terminal
            """,Integer.class,tenant)).isZero();
    }
    private static String salesDigest(JdbcTemplate jdbc,UUID tenant,UUID order,UUID event,String status){
        String occurred=jdbc.queryForObject("SELECT occurred_at FROM crm.sales_order_status_event WHERE tenant_id=? AND id=?",
            java.sql.Timestamp.class,tenant,event).toInstant().toString();
        String legacy=hash(String.join("|","RM-FACT-V1",tenant.toString(),"SALES","ORDER_"+status,event.toString(),order.toString(),occurred,"NULL",status));
        String header=jdbc.query("SELECT total_amount,currency FROM crm.sales_order WHERE tenant_id=? AND id=?",
            rs->{if(!rs.next())throw new AssertionError("Missing header");return frame(rs.getBigDecimal(1),rs.getString(2).trim());},tenant,order);
        var lines=jdbc.query("""
            SELECT id,offer_id,catalog_item_id_snapshot,quantity,unit_price_snapshot,currency,price_source,accepted_quote_id
            FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
            """,(rs,n)->frame(rs.getObject(1),rs.getObject(2),rs.getObject(3),rs.getInt(4),rs.getBigDecimal(5),rs.getString(6).trim(),rs.getString(7),rs.getObject(8)),tenant,order);
        return hash(frame("RM-FACT-V2",legacy,frame(header,frame(lines.stream().sorted().toArray()))));
    }
    public static void assertHandover(JdbcTemplate jdbc,UUID tenant,UUID order,int lines){
        UUID event=CheckoutCanonicalEvidence.assertStatus(jdbc,tenant,order,"COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status_event_id FROM crm.sales_pickup_handover WHERE tenant_id=? AND order_id=?",UUID.class,tenant,order)).isEqualTo(event);
        var movements=jdbc.query("""
            SELECT m.id FROM crm.inv_stock_movement m WHERE m.tenant_id=? AND m.source_type='SALES_ORDER'
                 AND m.source_id=? AND m.movement_kind='FULFILLMENT'
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,order);
        assertThat(movements).hasSize(lines);
        movements.forEach(m->assertMovement(jdbc,tenant,m));
    }
}
