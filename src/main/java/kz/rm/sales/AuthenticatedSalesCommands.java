package kz.rm.sales;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Public application boundary for a future trusted OIDC transport adapter. */
@Service
public class AuthenticatedSalesCommands {
    private final SalesInventoryCommandService commands;
    public AuthenticatedSalesCommands(SalesInventoryCommandService commands) {
        this.commands=commands;
    }
    public SalesInventoryCommandService.CommandResult confirm(
            VerifiedActor actor,UUID tenant,UUID order,UUID warehouse) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return commands.confirm(actor.userId(),tenant,order,warehouse);
    }
    public SalesInventoryCommandService.CommandResult cancel(
            VerifiedActor actor,UUID tenant,UUID order) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return commands.cancel(actor.userId(),tenant,order);
    }
    public SalesInventoryCommandService.CommandResult fulfill(
            VerifiedActor actor,UUID tenant,UUID order) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return commands.fulfill(actor.userId(),tenant,order);
    }
}
