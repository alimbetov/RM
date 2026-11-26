package kz.rm.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Read-only independent V1/V2 byte-contract verifier; never calls the appender or repairs a fact. */
final class IndependentCanonicalDigestVerifier {
    private final JdbcTemplate jdbc;
    IndependentCanonicalDigestVerifier(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private record Fact(String owner,String kind,UUID id,UUID subject,Instant occurred,Instant recorded,
                        Long version,int schema,String digest){}
    private record Header(BigDecimal amount,String currency,boolean sealed){}
    private record Line(int qty,BigDecimal price,String currency,String framed){}
    private record Movement(UUID subject,Instant occurred,Instant recorded,Long version,String payload,String extended){}
    private record BookedOwner(UUID source,UUID order,Instant occurred,Header header){}
    private record BookedLine(UUID order,Line line){}
    static final int BOOKED_LINE_LIMIT=20_000;
    static final int BOOKED_POPULATION_ENCODING_VERSION=1;
    long mismatches(UUID tenant){
        var facts=jdbc.query("""
            SELECT owner_module,source_kind,source_id,subject_id,occurred_at,recorded_at,source_version,schema_version,digest
            FROM crm.int_canonical_fact WHERE tenant_id=?
            """,(rs,n)->new Fact(rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
                rs.getTimestamp(5).toInstant(),rs.getTimestamp(6)==null?null:rs.getTimestamp(6).toInstant(),
                rs.getObject(7,Long.class),rs.getInt(8),rs.getString(9)),tenant);
        long bad=0;
        for(var f:facts)if(!matches(tenant,f))bad++;
        return bad;
    }
    /** Bounded diagnostic extension; existing B4 whole-tenant verifier is unchanged. */
    record SliceDigest(long checked,long mismatches,long legacy,boolean budgetExceeded,String populationDigest){}
    SliceDigest bookedSlice(UUID tenant,Instant from,Instant until,int limit){
        if(limit<1||limit>CanonicalFactDeliveryService.MAX_BATCH||!from.isBefore(until))
            throw new IllegalArgumentException("S1_DIGEST_BUDGET");
        var facts=jdbc.query("""
            SELECT owner_module,source_kind,source_id,subject_id,occurred_at,recorded_at,source_version,schema_version,digest
            FROM crm.int_canonical_fact WHERE tenant_id=? AND owner_module='SALES' AND source_kind='ORDER_PLACED'
              AND occurred_at>=? AND occurred_at<? ORDER BY occurred_at,source_id LIMIT ?
            """,(rs,n)->new Fact(rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
                rs.getTimestamp(5).toInstant(),rs.getTimestamp(6)==null?null:rs.getTimestamp(6).toInstant(),
                rs.getObject(7,Long.class),rs.getInt(8),rs.getString(9)),tenant,
                java.sql.Timestamp.from(from),java.sql.Timestamp.from(until),limit+1);
        if(facts.size()>limit)return new SliceDigest(0,0,0,true,null);
        long bad=0,legacy=0;
        if(!facts.isEmpty()){
            // At most two extra round trips, no per-fact SQL. Every lookup is tenant scoped.
            // Bound both the number of facts and the total number of loaded order lines.
            var sourceIds=facts.stream().map(Fact::id).distinct().toList();
            var orderIds=facts.stream().map(Fact::subject).distinct().toList();
            var owners=jdbc.query("""
                SELECT e.id,e.order_id,e.occurred_at,h.total_amount,h.currency,h.booked_snapshot_sealed
                FROM crm.sales_order_status_event e
                LEFT JOIN crm.sales_order h ON h.tenant_id=e.tenant_id AND h.id=e.order_id
                WHERE e.tenant_id=? AND e.event_type='PLACED' AND e.id IN (%s)
                """.formatted(marks(sourceIds.size())),(rs,n)->new BookedOwner(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getTimestamp(3).toInstant(),
                    rs.getBigDecimal(4)==null?null:new Header(rs.getBigDecimal(4),rs.getString(5).trim(),rs.getBoolean(6))),
                    arguments(tenant,sourceIds,null));
            var ownerBySource=new HashMap<UUID,BookedOwner>();
            for(var owner:owners)if(ownerBySource.put(owner.source(),owner)!=null)
                throw new IllegalStateException("S1_DUPLICATE_OWNER_ID");
            var loadedLines=jdbc.query("""
                SELECT order_id,id,offer_id,catalog_item_id_snapshot,quantity,unit_price_snapshot,currency,price_source,accepted_quote_id
                FROM crm.sales_order_line WHERE tenant_id=? AND order_id IN (%s)
                ORDER BY order_id,id LIMIT ?
                """.formatted(marks(orderIds.size())),(rs,n)->new BookedLine(rs.getObject(1,UUID.class),
                    new Line(rs.getInt(5),rs.getBigDecimal(6),rs.getString(7).trim(),
                      frame(rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getInt(5),
                        rs.getBigDecimal(6),rs.getString(7).trim(),rs.getString(8),rs.getObject(9,UUID.class)))),
                    arguments(tenant,orderIds,BOOKED_LINE_LIMIT+1));
            if(loadedLines.size()>BOOKED_LINE_LIMIT)return new SliceDigest(0,0,0,true,null);
            var linesByOrder=new HashMap<UUID,List<Line>>();
            for(var item:loadedLines)linesByOrder.computeIfAbsent(item.order(),key->new ArrayList<>()).add(item.line());
            for(var f:facts){
                if(!matchesBooked(tenant,f,ownerBySource.get(f.id()),linesByOrder.getOrDefault(f.subject(),List.of())))bad++;
                if(f.schema()==1)legacy++;
            }
        }
        // Versioned, order-independent fingerprint of the exact canonical set.
        // This is not itself an owner attestation. Reuse only after independent verification.
        var framed=facts.stream().map(f->frame(f.id(),f.subject(),f.occurred(),f.recorded(),
            f.version(),f.schema(),f.digest())).sorted().toArray();
        String populationDigest=hash(frame("RM-S1-BOOKED-POPULATION-V1",tenant,from,until,frame(framed)));
        return new SliceDigest(facts.size(),bad,legacy,false,populationDigest);
    }
    private static String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
    private static Object[] arguments(UUID tenant,List<UUID> ids,Integer limit){
        var args=new ArrayList<Object>();args.add(tenant);args.addAll(ids);if(limit!=null)args.add(limit);
        return args.toArray();
    }
    // Same V1/V2 byte contract as the unchanged independent single-fact path.
    private static boolean matchesBooked(UUID tenant,Fact f,BookedOwner owner,List<Line> lines){
        if(!"SALES".equals(f.owner())||!"ORDER_PLACED".equals(f.kind())||owner==null||
            !f.id().equals(owner.source())||!f.subject().equals(owner.order())||
            !f.occurred().equals(owner.occurred())||f.recorded()!=null)return false;
        Header h=owner.header();
        if(h==null||!h.sealed()||lines.isEmpty())return false;
        BigDecimal total=BigDecimal.ZERO;
        for(var l:lines){
            if(l.qty()<=0||l.price().signum()<0||!h.currency().equals(l.currency()))return false;
            total=total.add(l.price().multiply(BigDecimal.valueOf(l.qty())));
        }
        if(total.compareTo(h.amount())!=0)return false;
        String extended=frame(frame(h.amount(),h.currency()),frame(lines.stream().map(Line::framed).sorted().toArray()));
        String legacy=hash(String.join("|","RM-FACT-V1",tenant.toString(),f.owner(),f.kind(),f.id().toString(),
            f.subject().toString(),f.occurred().toString(),"NULL","PLACED"));
        return switch(f.schema()){
            case 1 -> f.version()==null&&legacy.equals(f.digest());
            case 2 -> f.version()==null&&hash(frame("RM-FACT-V2",legacy,extended)).equals(f.digest());
            default -> false;
        };
    }
    private boolean matches(UUID tenant,Fact f){
        String payload,extended;
        Long sourceVersion=null;
        if("SALES".equals(f.owner())){
            if(!Set.of("ORDER_PLACED","ORDER_CONFIRMED","ORDER_COMPLETED","ORDER_CANCELLED").contains(f.kind()))return false;
            payload=f.kind().substring(6);
            var status=jdbc.query("""
                SELECT order_id,occurred_at FROM crm.sales_order_status_event WHERE tenant_id=? AND id=? AND event_type=?
                """,rs->rs.next()&&f.subject().equals(rs.getObject(1,UUID.class))&&f.occurred().equals(rs.getTimestamp(2).toInstant()),
                tenant,f.id(),payload);
            if(!Boolean.TRUE.equals(status)||f.recorded()!=null)return false;
            Header h=jdbc.query("""
                SELECT total_amount,currency,booked_snapshot_sealed FROM crm.sales_order WHERE tenant_id=? AND id=?
                """,rs->rs.next()?new Header(rs.getBigDecimal(1),rs.getString(2).trim(),rs.getBoolean(3)):null,tenant,f.subject());
            if(h==null||!h.sealed())return false;
            var lines=jdbc.query("""
                SELECT id,offer_id,catalog_item_id_snapshot,quantity,unit_price_snapshot,currency,price_source,accepted_quote_id
                FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
                """,(rs,n)->new Line(rs.getInt(4),rs.getBigDecimal(5),rs.getString(6).trim(),
                  frame(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getInt(4),
                    rs.getBigDecimal(5),rs.getString(6).trim(),rs.getString(7),rs.getObject(8,UUID.class))),tenant,f.subject());
            if(lines.isEmpty())return false;
            BigDecimal total=BigDecimal.ZERO;
            for(var l:lines){
                if(l.qty()<=0||l.price().signum()<0||!h.currency().equals(l.currency()))return false;
                total=total.add(l.price().multiply(BigDecimal.valueOf(l.qty())));
            }
            if(total.compareTo(h.amount())!=0)return false;
            extended=frame(frame(h.amount(),h.currency()),frame(lines.stream().map(Line::framed).sorted().toArray()));
        }else if("INVENTORY".equals(f.owner())&&"STOCK_MOVEMENT".equals(f.kind())){
            Movement m=jdbc.query("""
                SELECT item_id,occurred_at,recorded_at,posting_version,location_id,delta_quantity,balance_before,balance_after,
                  reason_code,movement_kind,source_type,source_id,reservation_id,actor_user_id,business_reason_code,request_key
                FROM crm.inv_stock_movement WHERE tenant_id=? AND id=?
                """,rs->rs.next()?new Movement(rs.getObject(1,UUID.class),rs.getTimestamp(2).toInstant(),rs.getTimestamp(3).toInstant(),
                  rs.getObject(4,Long.class),rs.getObject(5,UUID.class)+"|"+decimal(rs.getBigDecimal(6))+"|"+
                  decimal(rs.getBigDecimal(7))+"|"+decimal(rs.getBigDecimal(8)),
                  frame(rs.getObject(4,Long.class),rs.getString(9),rs.getString(10),rs.getString(11),rs.getObject(12,UUID.class),
                    rs.getObject(13,UUID.class),rs.getObject(14,UUID.class),rs.getString(15),rs.getString(16))):null,tenant,f.id());
            if(m==null||!f.subject().equals(m.subject())||!f.occurred().equals(m.occurred())||!Objects.equals(f.recorded(),m.recorded()))return false;
            payload=m.payload();extended=m.extended();sourceVersion=m.version();
        }else return false;
        String legacy=hash(String.join("|","RM-FACT-V1",tenant.toString(),f.owner(),f.kind(),f.id().toString(),
            f.subject().toString(),f.occurred().toString(),f.recorded()==null?"NULL":f.recorded().toString(),payload));
        return switch(f.schema()){
            case 1 -> f.version()==null&&legacy.equals(f.digest());
            case 2 -> Objects.equals(sourceVersion,f.version())&&hash(frame("RM-FACT-V2",legacy,extended)).equals(f.digest());
            default -> false;
        };
    }
    private static String decimal(BigDecimal v){return v.stripTrailingZeros().toPlainString();}
    private static String frame(Object... values){
        var result=new StringBuilder();
        for(Object v:values){
            if(v==null)result.append("-1:");
            else{
                String x=v instanceof BigDecimal b?decimal(b):String.valueOf(v);
                result.append(x.length()).append(':').append(x);
            }
        }
        return result.toString();
    }
    private static String hash(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
