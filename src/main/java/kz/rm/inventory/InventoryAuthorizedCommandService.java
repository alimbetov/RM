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
    private final InventoryTransferService transfers;
    private final InventoryStocktakeService stocktakes;

    public InventoryAuthorizedCommandService(TenantAuthorization authorization,
                        InventoryMovementService movements,InventoryTransferService transfers,
                        InventoryStocktakeService stocktakes) {
        this.authorization=authorization;
        this.movements=movements;
        this.transfers=transfers;
        this.stocktakes=stocktakes;
    }

    @Transactional
    InventoryMovementService.Movement receive(UUID actorId,UUID tenantId,UUID locationId,
                                                       UUID itemId,String idempotencyKey,
                                                       BigDecimal quantity) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_RECEIVE);
        if(quantity==null || quantity.signum()<=0)
            throw new IllegalArgumentException("Receipt quantity must be positive");
        validateStaffKey(idempotencyKey);
        // This is quantity-only receiving; accounting valuation awaits INV-VAL-R1.2.
        return movements.adjust(tenantId,locationId,itemId,idempotencyKey,quantity,
                InventoryMovementService.Reason.RECEIPT,actorId,null);
    }

    @Transactional
    InventoryMovementService.Movement correct(UUID actorId,UUID tenantId,UUID locationId,
                                                       UUID itemId,String idempotencyKey,
                                                       BigDecimal delta) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_ADJUST);
        validateStaffKey(idempotencyKey);
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
        validateStaffKey(requestKey);
        return movements.adjust(tenantId,locationId,itemId,requestKey,quantity.negate(),
                InventoryMovementService.Reason.WRITE_OFF,actorId,cause);
    }

    @Transactional
    kz.rm.inventory.dto.StockTransferDto transfer(UUID actorId,UUID tenantId,
                UUID sourceLocationId,UUID targetLocationId,UUID itemId,
                BigDecimal quantity,String requestKey) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_ADJUST);
        validateStaffKey(requestKey);
        return transfers.transfer(tenantId,sourceLocationId,targetLocationId,
                itemId,quantity,requestKey,actorId);
    }

    @Transactional
    kz.rm.inventory.dto.InventoryStocktakeDto startStocktake(UUID actorId,UUID tenantId,
             UUID locationId,java.util.List<UUID> items,String requestKey) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_ADJUST);
        validateStaffKey(requestKey);
        return stocktakes.start(tenantId,locationId,items,requestKey,actorId);
    }
    @Transactional
    kz.rm.inventory.dto.InventoryStocktakeDto recordCount(UUID actorId,UUID tenantId,
              UUID stocktakeId,UUID itemId,BigDecimal quantity,long expectedLineVersion) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_ADJUST);
        return stocktakes.count(tenantId,stocktakeId,itemId,quantity,
                expectedLineVersion,actorId);
    }
    @Transactional
    kz.rm.inventory.dto.InventoryStocktakeDto postStocktake(UUID actorId,UUID tenantId,
              UUID stocktakeId,String requestKey) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_ADJUST);
        validateStaffKey(requestKey);
        return stocktakes.post(tenantId,stocktakeId,requestKey,actorId);
    }
    @Transactional
    kz.rm.inventory.dto.InventoryStocktakeDto cancelStocktake(UUID actorId,UUID tenantId,
              UUID stocktakeId) {
        authorization.requireForCommand(actorId,tenantId,DomainPermission.INVENTORY_ADJUST);
        return stocktakes.cancel(tenantId,stocktakeId,actorId);
    }

    private static void validateStaffKey(String key){
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,100}")
                || key.startsWith("fulfill-")||key.startsWith("transfer-")
                || key.startsWith("stocktake-"))
            throw new IllegalArgumentException("Invalid or reserved inventory request key");
    }

    // Order-linked reservation/release/fulfillment are intentionally NOT exposed here.
    // They must be invoked atomically through SalesInventoryCommandService,
    // which checks both SALES and INVENTORY permissions and updates order state.
}
