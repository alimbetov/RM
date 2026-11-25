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
    private record Fact(UUID subject,Instant occurred,Instant recorded,String payload){}
    @Transactional(propagation=Propagation.MANDATORY)
    public Outcome append(CanonicalFactKey key){
        Objects.requireNonNull(key);
        Fact fact=load(key);
        if(fact==null)throw new IllegalStateException("CANONICAL_SOURCE_NOT_FOUND");
        String digest=sha256("RM-FACT-V1|"+key.tenantId()+"|"+key.owner().name()+"|"+key.kind().name()+"|"+
            key.sourceId()+"|"+fact.subject()+"|"+fact.occurred()+"|"+(fact.recorded()==null?"NULL":fact.recorded())+"|"+fact.payload());
        // PostgreSQL atomic insert-or-ignore avoids aborting the owner transaction on exact replay.
        // H2 is a test-only dialect; serialize via tenant row before check/insert.
        boolean pg=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
        if(!pg)jdbc.queryForObject("SELECT id FROM public.iam_workspace WHERE id=? FOR UPDATE",UUID.class,key.tenantId());
        String prior=existing(key);
        if(prior!=null)return verify(prior,digest);
        String sql="""
            INSERT INTO crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id,subject_id,
                occurred_at,recorded_at,schema_version,digest)
            VALUES(?,?,?,?,?,?,?,1,?)
            """;
        int changed=jdbc.update(sql+(pg?" ON CONFLICT (tenant_id,owner_module,source_kind,source_id) DO NOTHING":""),
            key.tenantId(),key.owner().name(),key.kind().name(),key.sourceId(),fact.subject(),
            Timestamp.from(fact.occurred()),fact.recorded()==null?null:Timestamp.from(fact.recorded()),digest);
        return changed==1?Outcome.INSERTED:verify(existing(key),digest);
    }
    private String existing(CanonicalFactKey k){
        return jdbc.query("""
            SELECT digest FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module=? AND source_kind=? AND source_id=?
            """,rs->rs.next()?rs.getString(1):null,k.tenantId(),k.owner().name(),k.kind().name(),k.sourceId());
    }
    private static Outcome verify(String prior,String digest){
        if(!digest.equals(prior))throw new IllegalStateException("CANONICAL_FACT_CONFLICT");
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
            return jdbc.query("""
                SELECT order_id,occurred_at FROM crm.sales_order_status_event
                WHERE tenant_id=? AND id=? AND event_type=?
                """,rs->rs.next()?new Fact(rs.getObject(1,UUID.class),rs.getTimestamp(2).toInstant(),null,event):null,
                k.tenantId(),k.sourceId(),event);
        }
        return jdbc.query("""
            SELECT item_id,occurred_at,recorded_at,location_id,delta_quantity,balance_before,balance_after
            FROM crm.inv_stock_movement WHERE tenant_id=? AND id=?
            """,rs->rs.next()?new Fact(rs.getObject(1,UUID.class),rs.getTimestamp(2).toInstant(),rs.getTimestamp(3).toInstant(),
            rs.getObject(4,UUID.class)+"|"+rs.getBigDecimal(5).stripTrailingZeros().toPlainString()+"|"+
            rs.getBigDecimal(6).stripTrailingZeros().toPlainString()+"|"+rs.getBigDecimal(7).stripTrailingZeros().toPlainString()):null,
            k.tenantId(),k.sourceId());
    }
    private static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
