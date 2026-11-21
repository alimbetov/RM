package kz.rm.sales.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Seller-side order summary without buyer identity or email PII. */
public record SalesOrderDto(UUID id, String status, BigDecimal totalAmount,
                            String currency, Instant createdAt, long rowVersion) {}
