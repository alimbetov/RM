package kz.rm.inventory.dto;
import java.time.Instant;
import java.util.UUID;

/** Tenant-owned immutable reservation event. */
public record InventoryReservationEventDto(UUID id,UUID reservationId,
                                            String eventType,UUID actorUserId,
                                            Instant occurredAt) {}
