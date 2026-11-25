package kz.rm.inventory.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable quantity evidence; legacy actor=null means unknown, never inferred. */
public record InventoryMovementDto(UUID id,UUID tenantId,UUID locationId,UUID itemId,
        BigDecimal delta,BigDecimal beforeQuantity,BigDecimal afterQuantity,
        String movementKind,String reasonCode,String businessReasonCode,
        UUID actorUserId,String sourceType,UUID sourceId,UUID reservationId,Instant recordedAt) {}
