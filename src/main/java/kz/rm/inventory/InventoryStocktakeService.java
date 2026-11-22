package kz.rm.inventory;

import kz.rm.inventory.dto.InventoryStocktakeDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Q8: a bounded physical count of existing tenant stock positions.
 *
 * A count has no effect until posted. Posting requires the exact stock versions
 * captured at start; otherwise a new count is needed. All deltas are posted to
 * the existing immutable movement ledger, not a parallel balance store.
 */
@Service
class InventoryStocktakeService {
    private final JdbcTemplate jdbc;
    private final StockEligibility eligibility;
    InventoryStocktakeService(JdbcTemplate jdbc,StockEligibility eligibility){
        this.jdbc=jdbc;this.eligibility=eligibility;
    }
    private record Header(UUID id,UUID tenant,UUID location,String status,
                          UUID startedBy,Instant startedAt,UUID postedBy,Instant postedAt,
                          String startKey,String itemsSha,String postKey,long version) {}
    private record Snapshot(UUID item,BigDecimal onHand,BigDecimal reserved,long version) {}

    @Transactional(propagation=Propagation.MANDATORY)
    InventoryStocktakeDto start(UUID tenant,UUID location,List<UUID> items,
                                String requestKey,UUID actor) {
        if(tenant==null||location==null||actor==null
                ||items==null||items.isEmpty()||items.size()>100
                ||items.stream().anyMatch(java.util.Objects::isNull)
                ||items.size()!=new HashSet<>(items).size())
            throw new IllegalArgumentException("Stocktake needs 1..100 distinct SKU identities");
        var ordered=new ArrayList<>(items);ordered.sort(UUID::compareTo);
        String hash=fingerprint(location,ordered);
        requestLock(tenant,requestKey);
        Header old=byStartKey(tenant,requestKey);
        if(old!=null){
            if(!old.location().equals(location)||!old.itemsSha().equals(hash))
                throw new IllegalArgumentException("Stocktake key reused for different inventory");
            return details(tenant,old.id());
        }
        // Owner records and physical rows are locked in ascending SKU order.
        var snapshots=new ArrayList<Snapshot>(ordered.size());
        for(UUID item:ordered){
            eligibility.requireReceivable(tenant,location,item);
            snapshots.add(stock(tenant,location,item));
        }
        UUID id=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO crm.inv_stocktake
                  (tenant_id,id,location_id,start_request_key,item_set_sha256,
                   status,started_by,started_at,row_version)
                VALUES (?,?,?,?,?,'OPEN',?,?,0)
                """,tenant,id,location,requestKey,hash,actor,now);
        for(Snapshot row:snapshots){
            jdbc.update("""
                    INSERT INTO crm.inv_stocktake_line
                      (tenant_id,stocktake_id,item_id,book_on_hand,book_reserved,
                       expected_stock_version,row_version)
                    VALUES (?,?,?,?,?,?,0)
                    """,tenant,id,row.item(),row.onHand(),row.reserved(),row.version());
        }
        return details(tenant,id);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    InventoryStocktakeDto count(UUID tenant,UUID id,UUID item,BigDecimal counted,
                                long expectedLineVersion,UUID actor) {
        if(tenant==null||id==null||item==null||actor==null||expectedLineVersion<0
                ||counted==null||counted.signum()<0||counted.scale()>4
                ||counted.precision()>19)
            throw new IllegalArgumentException("Invalid counted physical quantity");
        Header header=lockHeader(tenant,id);
        if(!"OPEN".equals(header.status()))
            throw new IllegalStateException("Stocktake is not open");
        var current=jdbc.query("""
                SELECT row_version FROM crm.inv_stocktake_line
                WHERE tenant_id=? AND stocktake_id=? AND item_id=? FOR UPDATE
                """,rs->rs.next()?rs.getLong(1):null,tenant,id,item);
        if(current==null)throw new IllegalArgumentException("SKU not in stocktake");
        if(current!=expectedLineVersion)throw new IllegalStateException("Stale count revision");
        int updated=jdbc.update("""
                UPDATE crm.inv_stocktake_line
                SET counted_on_hand=?,counted_by=?,counted_at=?,row_version=row_version+1
                WHERE tenant_id=? AND stocktake_id=? AND item_id=? AND row_version=?
                """,counted,actor,Timestamp.from(Instant.now()),tenant,id,item,
                expectedLineVersion);
        if(updated!=1)throw new IllegalStateException("Concurrent physical count update");
        jdbc.update("""
                UPDATE crm.inv_stocktake SET row_version=row_version+1
                WHERE tenant_id=? AND id=?
                """,tenant,id);
        return details(tenant,id);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    InventoryStocktakeDto post(UUID tenant,UUID id,String requestKey,UUID actor) {
        if(tenant==null||id==null||actor==null)
            throw new IllegalArgumentException("Stocktake identity required");
        Header header=lockHeader(tenant,id);
        if(requestKey==null||!requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("Invalid stocktake post key");
        if("POSTED".equals(header.status())){
            if(!requestKey.equals(header.postKey()))
                throw new IllegalArgumentException("Stocktake already posted with another key");
            return details(tenant,id);
        }
        if(!"OPEN".equals(header.status()))
            throw new IllegalStateException("Cancelled stocktake cannot be posted");
        var lines=jdbc.query("""
                SELECT item_id,book_on_hand,book_reserved,expected_stock_version,counted_on_hand
                FROM crm.inv_stocktake_line
                WHERE tenant_id=? AND stocktake_id=? ORDER BY item_id FOR UPDATE
                """,(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getBigDecimal(2),
                        rs.getBigDecimal(3),rs.getLong(4),rs.getBigDecimal(5)},tenant,id);
        if(lines.isEmpty())throw new IllegalStateException("Stocktake has no selected items");
        for(Object[] line:lines) {
            if(line[4]==null)throw new IllegalStateException("Stocktake has uncounted SKU");
            UUID item=(UUID)line[0];
            eligibility.requireReceivable(tenant,header.location(),item);
            Snapshot current=stock(tenant,header.location(),item);
            BigDecimal book=(BigDecimal)line[1],reserved=(BigDecimal)line[2],
                    counted=(BigDecimal)line[4];
            long expected=(Long)line[3];
            if(current.version()!=expected ||
                    current.onHand().compareTo(book)!=0 ||
                    current.reserved().compareTo(reserved)!=0)
                throw new IllegalStateException("RECOUNT_REQUIRED: warehouse position changed");
            if(counted.compareTo(current.reserved())<0)
                throw new IllegalStateException("Counted quantity below active reservations");
            BigDecimal delta=counted.subtract(book);
            if(delta.signum()==0)continue; // no fake zero movement
            int affected=jdbc.update("""
                    UPDATE crm.inv_stock_balance SET on_hand=?,row_version=row_version+1
                    WHERE tenant_id=? AND location_id=? AND item_id=? AND row_version=?
                    """,counted,tenant,header.location(),item,expected);
            if(affected!=1)throw new IllegalStateException("RECOUNT_REQUIRED: concurrent stock update");
            Timestamp now=Timestamp.from(Instant.now());
            jdbc.update("""
                    INSERT INTO crm.inv_stock_movement
                      (tenant_id,id,location_id,item_id,request_key,delta_quantity,
                       balance_before,balance_after,occurred_at,recorded_at,
                       reason_code,actor_user_id,source_type,source_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,tenant,UUID.randomUUID(),header.location(),item,
                    "stocktake-"+id+"-"+item,delta,book,counted,now,now,
                    "STOCKTAKE_ADJUSTMENT",actor,"STOCKTAKE",id);
        }
        int updated=jdbc.update("""
                UPDATE crm.inv_stocktake
                SET status='POSTED',posted_by=?,posted_at=?,post_request_key=?,
                    row_version=row_version+1
                WHERE tenant_id=? AND id=? AND status='OPEN'
                """,actor,Timestamp.from(Instant.now()),requestKey,tenant,id);
        if(updated!=1)throw new IllegalStateException("Concurrent stocktake post");
        return details(tenant,id);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    InventoryStocktakeDto cancel(UUID tenant,UUID id,UUID actor) {
        if(tenant==null||id==null||actor==null)
            throw new IllegalArgumentException("Stocktake identity required");
        Header current=lockHeader(tenant,id);
        if("CANCELLED".equals(current.status()))return details(tenant,id);
        if(!"OPEN".equals(current.status()))
            throw new IllegalStateException("Posted stocktake cannot be cancelled");
        jdbc.update("""
                UPDATE crm.inv_stocktake SET status='CANCELLED',row_version=row_version+1
                WHERE tenant_id=? AND id=? AND status='OPEN'
                """,tenant,id);
        return details(tenant,id);
    }

    InventoryStocktakeDto details(UUID tenant,UUID id) {
        if(tenant==null||id==null)throw new IllegalArgumentException("Stocktake identity required");
        Header h=jdbc.query("""
                SELECT id,tenant_id,location_id,status,started_by,started_at,
                       posted_by,posted_at,start_request_key,item_set_sha256,
                       post_request_key,row_version
                FROM crm.inv_stocktake WHERE tenant_id=? AND id=?
                """,rs->rs.next()?map(rs):null,tenant,id);
        if(h==null)throw new IllegalArgumentException("Stocktake not found in tenant");
        List<InventoryStocktakeDto.Line> lines=jdbc.query("""
                SELECT item_id,book_on_hand,book_reserved,counted_on_hand,
                       expected_stock_version,row_version,counted_by
                FROM crm.inv_stocktake_line
                WHERE tenant_id=? AND stocktake_id=? ORDER BY item_id
                """,(rs,n)->new InventoryStocktakeDto.Line(
                    rs.getObject(1,UUID.class),rs.getBigDecimal(2),rs.getBigDecimal(3),
                    rs.getBigDecimal(4),rs.getLong(5),rs.getLong(6),
                    rs.getObject(7,UUID.class)),tenant,id);
        return new InventoryStocktakeDto(h.id(),tenant,h.location(),h.status(),
                h.startedBy(),h.startedAt(),h.postedBy(),h.postedAt(),
                h.version(),List.copyOf(lines));
    }

    private Header lockHeader(UUID tenant,UUID id) {
        return jdbc.query("""
                SELECT id,tenant_id,location_id,status,started_by,started_at,
                       posted_by,posted_at,start_request_key,item_set_sha256,
                       post_request_key,row_version
                FROM crm.inv_stocktake WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())throw new IllegalArgumentException("Stocktake not found");
                    return map(rs);
                },tenant,id);
    }
    private static Header map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp posted=rs.getTimestamp(8);
        return new Header(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getString(4),rs.getObject(5,UUID.class),
                rs.getTimestamp(6).toInstant(),rs.getObject(7,UUID.class),
                posted==null?null:posted.toInstant(),rs.getString(9),
                rs.getString(10),rs.getString(11),rs.getLong(12));
    }
    private Snapshot stock(UUID tenant,UUID location,UUID item) {
        return jdbc.query("""
                SELECT item_id,on_hand,reserved,row_version FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())throw new IllegalArgumentException("Stock position not initialized");
                    return new Snapshot(rs.getObject(1,UUID.class),rs.getBigDecimal(2),
                            rs.getBigDecimal(3),rs.getLong(4));
                },tenant,location,item);
    }
    private Header byStartKey(UUID tenant,String key){
        return jdbc.query("""
                SELECT id,tenant_id,location_id,status,started_by,started_at,
                       posted_by,posted_at,start_request_key,item_set_sha256,
                       post_request_key,row_version
                FROM crm.inv_stocktake WHERE tenant_id=? AND start_request_key=?
                """,rs->rs.next()?map(rs):null,tenant,key);
    }
    private void requestLock(UUID tenant,String key) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("Invalid stocktake key");
        int stripe;
        try {
            stripe=Math.floorMod(Arrays.hashCode(MessageDigest.getInstance("SHA-256")
                    .digest((tenant+":"+key).getBytes(StandardCharsets.UTF_8))),64);
        }catch(java.security.NoSuchAlgorithmException e){
            throw new IllegalStateException("SHA-256 unavailable",e);
        }
        jdbc.queryForObject("""
                SELECT bucket FROM crm.inv_movement_request_lock WHERE bucket=? FOR UPDATE
                """,Integer.class,stripe);
    }
    private static String fingerprint(UUID location,List<UUID> ordered) {
        StringBuilder b=new StringBuilder(location.toString());
        for(UUID item:ordered)b.append('|').append(item);
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException e){
            throw new IllegalStateException("SHA-256 unavailable",e);
        }
    }
}
