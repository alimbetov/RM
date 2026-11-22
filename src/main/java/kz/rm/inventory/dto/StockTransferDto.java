package kz.rm.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An immutable quantity-only transfer receipt; exactly two movement legs. */
public record StockTransferDto(UUID id,UUID tenantId,UUID sourceLocationId,
        UUID targetLocationId,UUID itemId,BigDecimal quantity,UUID postedBy,
        Instant postedAt) {}
