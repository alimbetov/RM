package kz.rm.inventory.dto;
import java.time.Instant;
import java.util.UUID;

/** Half-open time interval for tenant-scoped movement history. */
public record InventoryMovementSearch(UUID locationId,UUID itemId,String reasonCode,
                                      Instant from,Instant before) {}
