package kz.rm.inventory;

import kz.rm.iam.VerifiedActor;
import kz.rm.inventory.dto.StockTransferDto;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.UUID;

/** Public application boundary; internal stock adjustment service is package-private. */
@Service
public class AuthenticatedInventoryCommands {
    private final InventoryAuthorizedCommandService commands;
    public AuthenticatedInventoryCommands(InventoryAuthorizedCommandService commands) {
        this.commands=commands;
    }
    public InventoryMovementService.Movement receive(
            VerifiedActor actor,UUID tenant,UUID location,UUID item,String key,BigDecimal qty) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return commands.receive(actor.userId(),tenant,location,item,key,qty);
    }
    public InventoryMovementService.Movement correct(
            VerifiedActor actor,UUID tenant,UUID location,UUID item,String key,BigDecimal delta) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return commands.correct(actor.userId(),tenant,location,item,key,delta);
    }    public InventoryMovementService.Movement writeOff(
            VerifiedActor actor,UUID tenant,UUID location,UUID item,String key,BigDecimal qty,
            InventoryMovementService.WriteOffCause cause) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return commands.writeOff(actor.userId(),tenant,location,item,key,qty,cause);
    }    public StockTransferDto transfer(VerifiedActor actor,UUID tenant,
             UUID sourceLocation,UUID targetLocation,UUID item,BigDecimal qty,String requestKey) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return commands.transfer(actor.userId(),tenant,sourceLocation,targetLocation,
                item,qty,requestKey);
    }
}
