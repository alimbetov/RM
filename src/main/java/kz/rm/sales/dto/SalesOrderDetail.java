package kz.rm.sales.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-authorized seller detail. Buyer email and global IAM identity are
 * deliberately excluded. Original commercial snapshots are never recomputed.
 */
public record SalesOrderDetail(
        SalesOrderDto order,
        List<Line> lines) {
    public record Line(UUID id, UUID offerId, UUID catalogItemId,
                       String titleSnapshot, int quantity,
                       BigDecimal unitPriceSnapshot, String currency) {}
    public record StatusEvent(UUID id, String eventType, Instant occurredAt) {}
}
