package kz.rm.inventory;

import kz.rm.inventory.dto.StockTransferDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Q7: two warehouse quantity legs, one atomic PostgreSQL transaction.
 * Uses the existing balance and immutable movement journal, not another stock engine.
 * IAM admission belongs to InventoryAuthorizedCommandService.
 */
@Service
class InventoryTransferService {
    private final JdbcTemplate jdbc;
    private final StockEligibility eligibility;
    InventoryTransferService(JdbcTemplate jdbc,StockEligibility eligibility){
        this.jdbc=jdbc;this.eligibility=eligibility;
    }
    private record Position(BigDecimal onHand,BigDecimal reserved) {}

    @Transactional(propagation=Propagation.MANDATORY)
    StockTransferDto transfer(UUID tenant,UUID from,UUID to,UUID item,BigDecimal qty,
                              String requestKey,UUID actor) {
        if(tenant==null||from==null||to==null||item==null||actor==null||from.equals(to)
                ||qty==null||qty.signum()<=0||qty.scale()>4||qty.precision()>19
                ||requestKey==null||!requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("Invalid stock transfer");
        int stripe;
        try {
            stripe=Math.floorMod(Arrays.hashCode(MessageDigest.getInstance("SHA-256")
                    .digest((tenant+":"+requestKey).getBytes(StandardCharsets.UTF_8))),64);
        }catch(java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
        jdbc.queryForObject("""
                SELECT bucket FROM crm.inv_movement_request_lock WHERE bucket=? FOR UPDATE
                """,Integer.class,stripe);
        StockTransferDto previous=jdbc.query("""
                SELECT id,source_location_id,target_location_id,item_id,quantity,
                       posted_by,posted_at FROM crm.inv_stock_transfer
                WHERE tenant_id=? AND request_key=?
                """,rs->rs.next()?map(rs,tenant):null,tenant,requestKey);
        if(previous!=null){
            if(!previous.sourceLocationId().equals(from)||!previous.targetLocationId().equals(to)
                    ||!previous.itemId().equals(item)||previous.quantity().compareTo(qty)!=0)
                throw new IllegalArgumentException("Transfer request key reused with different payload");
            return previous;
        }
        Integer movementCollision=jdbc.queryForObject("""
                SELECT count(*) FROM crm.inv_stock_movement
                WHERE tenant_id=? AND request_key=?
                """,Integer.class,tenant,requestKey);
        if(movementCollision!=null&&movementCollision>0)
            throw new IllegalArgumentException("Transfer key belongs to an existing movement");

        // Both location locks are taken in the same order for A->B and B->A.
        // Re-check state/type after acquiring locks; SKU owner row is also fenced.
        UUID first=from.compareTo(to)<0?from:to;
        UUID second=first.equals(from)?to:from;
        eligibility.requireTransferWarehouses(tenant,first,second,item);
        // Target zero position is opened inside the transaction; never for source.
        eligibility.ensureZeroPosition(tenant,to,item);
        // Stable position lock order across opposite transfers.
        Position firstPosition=locked(tenant,first,item);
        Position secondPosition=locked(tenant,second,item);
        Position source=first.equals(from)?firstPosition:secondPosition;
        Position target=first.equals(to)?firstPosition:secondPosition;
        if(source.onHand().subtract(source.reserved()).compareTo(qty)<0)
            throw new IllegalStateException("Insufficient unreserved stock for transfer");
        BigDecimal sourceAfter=source.onHand().subtract(qty);
        BigDecimal targetAfter=target.onHand().add(qty);
        if(targetAfter.precision()>19||sourceAfter.compareTo(source.reserved())<0)
            throw new IllegalArgumentException("Transfer violates quantity precision or reservation");
        UUID transferId=UUID.randomUUID();
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO crm.inv_stock_transfer
                 (tenant_id,id,request_key,source_location_id,target_location_id,
                  item_id,quantity,posted_by,posted_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,tenant,transferId,requestKey,from,to,item,qty,actor,now);
        if(jdbc.update("""
                UPDATE crm.inv_stock_balance SET on_hand=?,row_version=row_version+1
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,sourceAfter,tenant,from,item)!=1)
            throw new IllegalStateException("Missing transfer source position");
        if(jdbc.update("""
                UPDATE crm.inv_stock_balance SET on_hand=?,row_version=row_version+1
                WHERE tenant_id=? AND location_id=? AND item_id=?
                """,targetAfter,tenant,to,item)!=1)
            throw new IllegalStateException("Missing transfer target position");
        postLeg(tenant,from,item,transferId,actor,"TRANSFER_OUT",qty.negate(),
                source.onHand(),sourceAfter,now,"transfer-out-"+transferId);
        postLeg(tenant,to,item,transferId,actor,"TRANSFER_IN",qty,
                target.onHand(),targetAfter,now,"transfer-in-"+transferId);
        return new StockTransferDto(transferId,tenant,from,to,item,qty,actor,now.toInstant());
    }

    private Position locked(UUID tenant,UUID location,UUID item) {
        return jdbc.query("""
                SELECT on_hand,reserved FROM crm.inv_stock_balance
                WHERE tenant_id=? AND location_id=? AND item_id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())throw new IllegalArgumentException(
                            "Transfer source position not initialized");
                    return new Position(rs.getBigDecimal(1),rs.getBigDecimal(2));
                },tenant,location,item);
    }
    private static StockTransferDto map(java.sql.ResultSet rs,UUID tenant)
            throws java.sql.SQLException {
        return new StockTransferDto(rs.getObject(1,UUID.class),tenant,
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getBigDecimal(5),
                rs.getObject(6,UUID.class),rs.getTimestamp(7).toInstant());
    }
    private void postLeg(UUID tenant,UUID location,UUID item,UUID receipt,UUID actor,
                         String reason,BigDecimal delta,BigDecimal before,BigDecimal after,
                         Timestamp at,String key) {
        jdbc.update("""
                INSERT INTO crm.inv_stock_movement
                  (tenant_id,id,location_id,item_id,request_key,delta_quantity,
                   balance_before,balance_after,occurred_at,recorded_at,reason_code,
                   actor_user_id,source_type,source_id,posting_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),location,item,key,delta,before,after,at,at,
                reason,actor,"TRANSFER",receipt,
                InventoryPostingVersion.afterBalanceUpdate(jdbc,tenant,location,item));
    }
}
