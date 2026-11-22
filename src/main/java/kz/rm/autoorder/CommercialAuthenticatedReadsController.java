package kz.rm.autoorder;

import kz.rm.iam.OidcActorResolver;
import kz.rm.sales.AuthenticatedSalesQueries;
import kz.rm.sales.dto.SalesOrderDetail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

/**
 * RM-E2E-04 read-only entry slice. Spring Security validates issuer/audience/
 * signature before the controller; the resolver additionally maps the subject
 * to an ACTIVE global RM account. Seller grants are checked per tenant by SALES.
 * No identity or tenant permission is accepted from client-supplied actorId.
 *
 * Enabled ONLY when rm.security.oidc.enabled=true.
 */
@RestController
@RequestMapping("/api/v1/commercial")
@ConditionalOnProperty(name="rm.security.oidc.enabled",havingValue="true")
public class CommercialAuthenticatedReadsController {
    private final OidcActorResolver identity;
    private final AuthenticatedBuyerOrders buyers;
    private final AuthenticatedSalesQueries sellers;

    public CommercialAuthenticatedReadsController(OidcActorResolver identity,
            AuthenticatedBuyerOrders buyers,AuthenticatedSalesQueries sellers) {
        this.identity=identity;
        this.buyers=buyers;
        this.sellers=sellers;
    }

    @GetMapping("/buyer/orders")
    public Page<AutoOrderService.OrderReceipt> ownOrders(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return buyers.history(identity.resolve(jwt.getTokenValue()),page,size);
    }

    @GetMapping("/seller/tenants/{tenantId}/orders/{orderId}")
    public SalesOrderDetail sellerDetail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID tenantId,@PathVariable UUID orderId) {
        return sellers.detail(identity.resolve(jwt.getTokenValue()),tenantId,orderId);
    }

    @GetMapping("/seller/tenants/{tenantId}/orders/{orderId}/events")
    public List<SalesOrderDetail.StatusEvent> sellerStatusHistory(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,
            @PathVariable UUID orderId,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return sellers.history(identity.resolve(jwt.getTokenValue()),
                tenantId,orderId,page,size);
    }
}
