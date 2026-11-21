package kz.rm.inventory.dto;

import java.util.UUID;

public record StockSearch(UUID locationId, UUID itemId, boolean availableOnly) {}
