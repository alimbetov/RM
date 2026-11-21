package kz.rm.inventory;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * IAM-authorized entry point for standalone inventory commands.
 * Do not expose the underlying legacy internal write services over HTTP.
 * Caller actor ID must be mapped from an authenticated trusted principal.
 */
@Service
public class InventoryAuthorizedCommandService {
    private final TenantAuthorization authorization;
    private final InventoryMovementService movements;
    private final InventoryReservationService reservations;

    public InventoryAuthorizedCommandService(TenantAuthorization authorization,
                                             InventoryMovementService movements,
                                             InventoryReservationService reservations) {
        this.authorization=authorization;
        this.movements=movements;
        this.reservations=reservations;
    }

    @Transactional
    public InventoryMovementService.Movement receive(UUID actorId,UUID tenantId,UUID locationId,
                                                       UUID itemId,String idempotencyKey,
                                                       BigDecimal quantity) {
        authorization.require(actorId,tenantId,DomainPermission.INVENTORY_RECEIVE);
        if(quantity==null || quantity.signum()<=0)
            throw new IllegalArgumentException("Receipt quantity must be positive");
        // This is quantity-only receiving; accounting valuation awaits INV-VAL-R1.2.
        return movements.adjust(tenantId,locationId,itemId,idempotencyKey,quantity,
                InventoryMovementService.Reason.RECEIPT);
    }

    @Transactional
    public InventoryMovementService.Movement correct(UUID actorId,UUID tenantId,UUID locationId,
                                                       UUID itemId,String idempotencyKey,
                                                       BigDecimal delta) {
        authorization.require(actorId,tenantId,DomainPermission.INVENTORY_ADJUST);
        return movements.adjust(tenantId,locationId,itemId,idempotencyKey,delta,
                InventoryMovementService.Reason.CORRECTION);
    }

    @Transactional
    public InventoryReservationService.Reservation reserve(UUID actorId,UUID tenantId,
            UUID orderId,UUID orderLineId,UUID locationId,UUID itemId,BigDecimal quantity) {
        authorization.require(actorId,tenantId,DomainPermission.INVENTORY_RESERVE);
        return reservations.reserve(tenantId,orderId,orderLineId,locationId,itemId,quantity);
    }

    @Transactional
    public InventoryReservationService.Reservation release(UUID actorId,UUID tenantId,
                                                              UUID reservationId) {
        authorization.require(actorId,tenantId,DomainPermission.INVENTORY_RELEASE);
        return reservations.release(tenantId,reservationId);
    }

    @Transactional
    public InventoryReservationService.Reservation fulfill(UUID actorId,UUID tenantId,
                                                              UUID reservationId) {
        authorization.require(actorId,tenantId,DomainPermission.INVENTORY_FULFILL);
        return reservations.fulfill(tenantId,reservationId);
    }
}
