package kz.rm.intelligence;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public record CanonicalFactKey(UUID tenantId, Owner owner, Kind kind, UUID sourceId) implements Serializable {
    public enum Owner { SALES, INVENTORY }
    public enum Kind { ORDER_PLACED, ORDER_CONFIRMED, ORDER_COMPLETED, ORDER_CANCELLED, STOCK_MOVEMENT }
    public CanonicalFactKey {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(owner);
        Objects.requireNonNull(kind); Objects.requireNonNull(sourceId);
        if ((owner==Owner.INVENTORY)!=(kind==Kind.STOCK_MOVEMENT))
            throw new IllegalArgumentException("CANONICAL_OWNER_KIND_MISMATCH");
    }
}
