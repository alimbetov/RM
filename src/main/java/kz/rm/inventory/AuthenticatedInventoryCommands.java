package kz.rm.inventory;

import kz.rm.iam.VerifiedActor;
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
    }
}
