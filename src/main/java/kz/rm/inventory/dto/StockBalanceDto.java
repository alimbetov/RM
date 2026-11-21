package kz.rm.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Quantity snapshot, not an authoritative cost valuation. */
public record StockBalanceDto(UUID tenantId, UUID locationId, UUID itemId,
                              BigDecimal onHand, BigDecimal reserved,
                              BigDecimal available, long rowVersion) {}
