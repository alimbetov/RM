package kz.rm.sales;

import kz.rm.iam.OidcActorResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * W5 seller command transport slice: real JWT principal + existing transactional
 * SALES/INVENTORY RBAC. No client-specified user id or inferred tenant membership.
 * No payment/delivery semantics; COMPLETED denotes inventory fulfillment only.
 */
@RestController
@RequestMapping("/api/v1/commercial/seller/tenants/{tenantId}/orders")
@ConditionalOnProperty(name="rm.security.oidc.enabled",havingValue="true")
public class SalesAuthenticatedCommandController {
    public record ConfirmRequest(UUID warehouseId) {}

    private final OidcActorResolver identity;
    private final AuthenticatedSalesCommands sales;

    public SalesAuthenticatedCommandController(OidcActorResolver identity,
                                                AuthenticatedSalesCommands sales) {
        this.identity=identity;
        this.sales=sales;
    }

    @PostMapping("/{orderId}/confirm")
    public SalesInventoryCommandService.CommandResult confirm(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID tenantId,@PathVariable UUID orderId,
            @RequestBody ConfirmRequest body) {
        if(body==null || body.warehouseId()==null)
            throw new IllegalArgumentException("Warehouse required");
        return sales.confirm(identity.resolve(jwt.getTokenValue()),
                tenantId,orderId,body.warehouseId());
    }

    @PostMapping("/{orderId}/fulfill")
    public SalesInventoryCommandService.CommandResult fulfill(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID tenantId,@PathVariable UUID orderId) {
        return sales.fulfill(identity.resolve(jwt.getTokenValue()),tenantId,orderId);
    }
}
