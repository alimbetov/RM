package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Internal owner transaction adapter. B2.1 admits only source-backed status and movement facts. */
@Service
public class CanonicalFactAppender {
    public enum Outcome { INSERTED, ALREADY_PRESENT }
    private final JdbcTemplate jdbc;
    public CanonicalFactAppender(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private record Fact(UUID subject,Instant occurred,Instant recorded,String payload,Long sourceVersion,String extended){}
    private record Stored(int version,String digest){}
    @Transactional(propagation=Propagation.MANDATORY)
    public Outcome append(CanonicalFactKey key){
        Objects.requireNonNull(key);
        Fact fact=load(key);
        if(fact==null)throw new IllegalStateException("CANONICAL_SOURCE_NOT_FOUND");
        String legacy=sha256("RM-FACT-V1|"+key.tenantId()+"|"+key.owner().name()+"|"+key.kind().name()+"|"+
            key.sourceId()+"|"+fact.subject()+"|"+fact.occurred()+"|"+(fact.recorded()==null?"NULL":fact.recorded())+"|"+fact.payload());
        String digest=sha256(frame("RM-FACT-V2",legacy,fact.extended()));
        // PostgreSQL atomic insert-or-ignore avoids aborting the owner transaction on exact replay.
        // H2 is a test-only dialect; serialize via tenant row before check/insert.
        boolean pg=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
        if(!pg)jdbc.queryForObject("SELECT id FROM public.iam_workspace WHERE id=? FOR UPDATE",UUID.class,key.tenantId());
        Stored prior=existing(key);
        if(prior!=null)return verify(prior,legacy,digest);
        String sql="""
            INSERT INTO crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id,subject_id,
                occurred_at,recorded_at,source_version,schema_version,digest)
            VALUES(?,?,?,?,?,?,?,?,2,?)
            """;
        int changed=jdbc.update(sql+(pg?" ON CONFLICT (tenant_id,owner_module,source_kind,source_id) DO NOTHING":""),
            key.tenantId(),key.owner().name(),key.kind().name(),key.sourceId(),fact.subject(),
            Timestamp.from(fact.occurred()),fact.recorded()==null?null:Timestamp.from(fact.recorded()),fact.sourceVersion(),digest);
        return changed==1?Outcome.INSERTED:verify(existing(key),legacy,digest);
    }
    private Stored existing(CanonicalFactKey k){
        return jdbc.query("""
            SELECT schema_version,digest FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module=? AND source_kind=? AND source_id=?
            """,rs->rs.next()?new Stored(rs.getInt(1),rs.getString(2)):null,k.tenantId(),k.owner().name(),k.kind().name(),k.sourceId());
    }
    private static Outcome verify(Stored prior,String legacy,String digest){
        if(prior==null || !((prior.version()==1 && legacy.equals(prior.digest())) ||
                (prior.version()==2 && digest.equals(prior.digest()))))throw new IllegalStateException("CANONICAL_FACT_CONFLICT");
        return Outcome.ALREADY_PRESENT;
    }
    private Fact load(CanonicalFactKey k){
        if(k.owner()==CanonicalFactKey.Owner.SALES){
            String event=switch(k.kind()){
                case ORDER_PLACED -> "PLACED";
                case ORDER_CONFIRMED -> "CONFIRMED";
                case ORDER_COMPLETED -> "COMPLETED";
                case ORDER_CANCELLED -> "CANCELLED";
                default -> throw new IllegalArgumentException("Unsupported source kind");
            };
            Fact status=jdbc.query("""
                SELECT order_id,occurred_at FROM crm.sales_order_status_event
                WHERE tenant_id=? AND id=? AND event_type=?
                """,rs->rs.next()?new Fact(rs.getObject(1,UUID.class),rs.getTimestamp(2).toInstant(),null,event,null,null):null,
                k.tenantId(),k.sourceId(),event);
            if(status==null)return null;
            String header=jdbc.query("""
                SELECT total_amount,currency FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,rs->rs.next()?frame(rs.getBigDecimal(1),rs.getString(2).trim()):null,k.tenantId(),status.subject());
            if(header==null)throw new IllegalStateException("CANONICAL_ORDER_NOT_FOUND");
            var lines=jdbc.query("""
                SELECT id,offer_id,catalog_item_id_snapshot,quantity,unit_price_snapshot,currency,price_source,accepted_quote_id
                FROM crm.sales_order_line WHERE tenant_id=? AND order_id=? ORDER BY id
                """,(rs,n)->frame(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                    rs.getInt(4),rs.getBigDecimal(5),rs.getString(6).trim(),rs.getString(7),rs.getObject(8,UUID.class)),
                k.tenantId(),status.subject());
            // Sort encoded rows in Java to avoid dialect-specific UUID ordering.
            lines.sort(String::compareTo);
            return new Fact(status.subject(),status.occurred(),null,event,null,frame(header,frame(lines.toArray())));
        }
        return jdbc.query("""
            SELECT item_id,occurred_at,recorded_at,location_id,delta_quantity,balance_before,balance_after,
                   posting_version,reason_code,movement_kind,source_type,source_id,reservation_id,actor_user_id,business_reason_code,request_key
            FROM crm.inv_stock_movement WHERE tenant_id=? AND id=?
            """,rs->rs.next()?new Fact(rs.getObject(1,UUID.class),rs.getTimestamp(2).toInstant(),rs.getTimestamp(3).toInstant(),
            rs.getObject(4,UUID.class)+"|"+rs.getBigDecimal(5).stripTrailingZeros().toPlainString()+"|"+
            rs.getBigDecimal(6).stripTrailingZeros().toPlainString()+"|"+rs.getBigDecimal(7).stripTrailingZeros().toPlainString(),rs.getObject(8,Long.class),
            frame(rs.getObject(8,Long.class),rs.getString(9),rs.getString(10),rs.getString(11),
                rs.getObject(12,UUID.class),rs.getObject(13,UUID.class),rs.getObject(14,UUID.class),rs.getString(15),rs.getString(16))):null,
            k.tenantId(),k.sourceId());
    }
    // Length framing distinguishes null, empty, delimiters and variable-length records.
    static String frame(Object... values){
        StringBuilder out=new StringBuilder();
        for(Object value:values){
            if(value==null){out.append("-1:");continue;}
            String s=value instanceof java.math.BigDecimal d?d.stripTrailingZeros().toPlainString():value.toString();
            out.append(s.length()).append(':').append(s);
        }
        return out.toString();
    }
    private static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
