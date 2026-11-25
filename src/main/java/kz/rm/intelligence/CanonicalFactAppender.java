package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
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
    private record Stored(int version,String digest,UUID subject,Instant occurred,Instant recorded,Long sourceVersion){}
    private record BookedHeader(BigDecimal amount,String currency,boolean sealed){}
    private record BookedLine(int quantity,BigDecimal price,String currency,String encoded){}
    @Transactional(propagation=Propagation.MANDATORY)
    public Outcome append(CanonicalFactKey key){
        Objects.requireNonNull(key);
        boolean pg=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
        Fact fact=load(key,pg);
        if(fact==null)throw new IllegalStateException("CANONICAL_SOURCE_NOT_FOUND");
        String legacy=legacyDigest(key,fact.subject(),fact.occurred(),fact.recorded(),fact.payload());
        String digest=v2Digest(legacy,fact.extended());
        // PostgreSQL atomic insert-or-ignore avoids aborting the owner transaction on exact replay.
        // H2 is a test-only dialect; serialize via tenant row before check/insert.
        if(!pg)jdbc.queryForObject("SELECT id FROM public.iam_workspace WHERE id=? FOR UPDATE",UUID.class,key.tenantId());
        Stored prior=existing(key);
        if(prior!=null)return verify(prior,fact,legacy,digest);
        String sql="""
            INSERT INTO crm.int_canonical_fact(tenant_id,owner_module,source_kind,source_id,subject_id,
                occurred_at,recorded_at,source_version,schema_version,digest)
            VALUES(?,?,?,?,?,?,?,?,2,?)
            """;
        int changed=jdbc.update(sql+(pg?" ON CONFLICT (tenant_id,owner_module,source_kind,source_id) DO NOTHING":""),
            key.tenantId(),key.owner().name(),key.kind().name(),key.sourceId(),fact.subject(),
            Timestamp.from(fact.occurred()),fact.recorded()==null?null:Timestamp.from(fact.recorded()),fact.sourceVersion(),digest);
        return changed==1?Outcome.INSERTED:verify(existing(key),fact,legacy,digest);
    }
    private Stored existing(CanonicalFactKey k){
        return jdbc.query("""
            SELECT schema_version,digest,subject_id,occurred_at,recorded_at,source_version
            FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module=? AND source_kind=? AND source_id=?
            """,rs->{
                if(!rs.next())return null;
                Timestamp recorded=rs.getTimestamp(5);
                return new Stored(rs.getInt(1),rs.getString(2),rs.getObject(3,UUID.class),
                    rs.getTimestamp(4).toInstant(),recorded==null?null:recorded.toInstant(),rs.getObject(6,Long.class));
            },k.tenantId(),k.owner().name(),k.kind().name(),k.sourceId());
    }
    private static Outcome verify(Stored prior,Fact fact,String legacy,String digest){
        // V1 never populated source_version; its historical digest must remain unchanged.
        boolean valid=prior!=null && Objects.equals(prior.subject(),fact.subject())
            && Objects.equals(prior.occurred(),fact.occurred())
            && Objects.equals(prior.recorded(),fact.recorded())
            && ((prior.version()==1 && prior.sourceVersion()==null && legacy.equals(prior.digest()))
                || (prior.version()==2 && Objects.equals(prior.sourceVersion(),fact.sourceVersion())
                    && digest.equals(prior.digest())));
        if(!valid)throw new IllegalStateException("CANONICAL_FACT_CONFLICT");
        return Outcome.ALREADY_PRESENT;
    }
    private Fact load(CanonicalFactKey k,boolean pg){
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
            // PostgreSQL source admission explicitly requires the owner sealing invariant.
            // H2 supports functional tests only and does not claim an equivalent database fence.
            String headerSql="SELECT total_amount,currency,"+(pg?"booked_snapshot_sealed":"TRUE")
                +" FROM crm.sales_order WHERE tenant_id=? AND id=?";
            BookedHeader header=jdbc.query(headerSql,rs->rs.next()?
                new BookedHeader(rs.getBigDecimal(1),rs.getString(2).trim(),rs.getBoolean(3)):null,
                k.tenantId(),status.subject());
            if(header==null)throw new IllegalStateException("CANONICAL_ORDER_NOT_FOUND");
            if(!header.sealed())throw new IllegalStateException("CANONICAL_SOURCE_NOT_SEALED");
            var lines=jdbc.query("""
                SELECT id,offer_id,catalog_item_id_snapshot,quantity,unit_price_snapshot,currency,price_source,accepted_quote_id
                FROM crm.sales_order_line WHERE tenant_id=? AND order_id=? ORDER BY id
                """,(rs,n)->{
                    int quantity=rs.getInt(4);
                    BigDecimal price=rs.getBigDecimal(5);
                    String currency=rs.getString(6).trim();
                    return new BookedLine(quantity,price,currency,frame(rs.getObject(1,UUID.class),
                        rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),quantity,price,currency,
                        rs.getString(7),rs.getObject(8,UUID.class)));
                },k.tenantId(),status.subject());
            if(lines.isEmpty())throw new IllegalStateException("CANONICAL_SNAPSHOT_EMPTY");
            BigDecimal booked=BigDecimal.ZERO;
            for(BookedLine line:lines){
                if(line.quantity()<=0 || line.price().signum()<0)
                    throw new IllegalStateException("CANONICAL_SNAPSHOT_INVALID_LINE");
                if(!header.currency().equals(line.currency()))
                    throw new IllegalStateException("CANONICAL_SNAPSHOT_CURRENCY_MISMATCH");
                booked=booked.add(line.price().multiply(BigDecimal.valueOf(line.quantity())));
            }
            if(booked.compareTo(header.amount())!=0)
                throw new IllegalStateException("CANONICAL_SNAPSHOT_AMOUNT_MISMATCH");
            // Preserve the existing V2 byte contract; sort framed rows independently of SQL UUID order.
            Object[] encoded=lines.stream().map(BookedLine::encoded).sorted().toArray();
            return new Fact(status.subject(),status.occurred(),null,event,null,
                frame(frame(header.amount(),header.currency()),frame(encoded)));
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
    // Package-private pure encoding contract for fixed compatibility vectors.
    static String legacyDigest(CanonicalFactKey key,UUID subject,Instant occurred,Instant recorded,String payload){
        return sha256("RM-FACT-V1|"+key.tenantId()+"|"+key.owner().name()+"|"+key.kind().name()+"|"+
            key.sourceId()+"|"+subject+"|"+occurred+"|"+(recorded==null?"NULL":recorded)+"|"+payload);
    }
    static String v2Digest(String legacy,String extended){
        return sha256(frame("RM-FACT-V2",legacy,extended));
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
