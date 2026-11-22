package kz.rm.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Physical quantity count. Financial valuation is not implied. */
public record InventoryStocktakeDto(UUID id,UUID tenantId,UUID locationId,String status,
        UUID startedBy,Instant startedAt,UUID postedBy,Instant postedAt,long rowVersion,
        List<Line> lines) {
    public record Line(UUID itemId,BigDecimal bookOnHand,BigDecimal bookReserved,
                       BigDecimal countedOnHand,long expectedStockVersion,
                       long rowVersion,UUID countedBy) {}
}
