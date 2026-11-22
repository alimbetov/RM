package kz.rm.inventory;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal QR-07 posting boundary. Caller is the return transaction and must
 * persist return provenance in that same transaction. No controller exposure.
 */
@Service
public class ReturnInventoryPostingPort {
    private final InventoryMovementService movements;
    private final TenantAuthorization authorization;
    public ReturnInventoryPostingPort(InventoryMovementService movements,
                                      TenantAuthorization authorization) {
        this.movements=movements;
        this.authorization=authorization;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public InventoryMovementService.Movement restock(UUID actor,UUID tenant,UUID warehouse,
                         UUID item,UUID returnId,BigDecimal quantity) {
        authorization.requireForCommand(actor,tenant,DomainPermission.INVENTORY_RECEIVE);
        if(returnId==null||quantity==null||quantity.signum()<=0)
            throw new IllegalArgumentException("RETURN_POST_QUANTITY_INVALID");
        // Dedicated deterministic movement key, not supplied by a caller.
        String movementKey="R07_"+returnId.toString().replace("-","");
        return movements.adjust(tenant,warehouse,item,movementKey,quantity,
                InventoryMovementService.Reason.RECEIPT,actor,null);
    }
}
