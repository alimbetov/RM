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

    public InventoryAuthorizedCommandService(TenantAuthorization authorization,
                                             InventoryMovementService movements) {
        this.authorization=authorization;
        this.movements=movements;
    }

    @Transactional
    InventoryMovementService.Movement receive(UUID actorId,UUID tenantId,UUID locationId,
                                                       UUID itemId,String idempotencyKey,
                                                       BigDecimal quantity) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_RECEIVE);
        if(quantity==null || quantity.signum()<=0)
            throw new IllegalArgumentException("Receipt quantity must be positive");
        // This is quantity-only receiving; accounting valuation awaits INV-VAL-R1.2.
        return movements.adjust(tenantId,locationId,itemId,idempotencyKey,quantity,
                InventoryMovementService.Reason.RECEIPT,actorId,null);
    }

    @Transactional
    InventoryMovementService.Movement correct(UUID actorId,UUID tenantId,UUID locationId,
                                                       UUID itemId,String idempotencyKey,
                                                       BigDecimal delta) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_ADJUST);
        return movements.adjust(tenantId,locationId,itemId,idempotencyKey,delta,
                InventoryMovementService.Reason.CORRECTION,actorId,null);
    }

    @Transactional
    InventoryMovementService.Movement writeOff(UUID actorId,UUID tenantId,UUID locationId,
                UUID itemId,String requestKey,BigDecimal quantity,
                InventoryMovementService.WriteOffCause cause) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_ADJUST);
        if(quantity==null || quantity.signum()<=0 || cause==null)
            throw new IllegalArgumentException("Positive quantity and write-off cause required");
        return movements.adjust(tenantId,locationId,itemId,requestKey,quantity.negate(),
                InventoryMovementService.Reason.WRITE_OFF,actorId,cause);
    }

    // Order-linked reservation/release/fulfillment are intentionally NOT exposed here.
    // They must be invoked atomically through SalesInventoryCommandService,
    // which checks both SALES and INVENTORY permissions and updates order state.
}
