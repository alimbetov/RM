package kz.rm.inventory;
import java.math.BigDecimal;
import java.util.UUID;
/** Operational stock invariant. Ledger movements require separate immutable records. */
public record StockPosition(UUID tenantId, UUID locationId, UUID itemId,
                            BigDecimal onHand, BigDecimal reserved) {
    public StockPosition {
        if(tenantId==null||locationId==null||itemId==null||onHand==null||reserved==null
                ||onHand.signum()<0||reserved.signum()<0||reserved.compareTo(onHand)>0)
            throw new IllegalArgumentException("Invalid inventory balance");
    }
    public BigDecimal available() {return onHand.subtract(reserved);}
}
