package kz.rm.fx.auto;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import kz.rm.organization.TenantCurrencyPolicyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * R2C.1D.1: transaction-scoped PRODUCT consent admission only.
 * No policy persistence, consent issuance, activation or price mutation.
 * IAM actor/workspace locks precede the tenant currency policy lock.
 */
@Service
public class FxAutoOwnerConsentService {
    private final TenantAuthorization authorization;
    private final TenantCurrencyPolicyService currencies;

    public FxAutoOwnerConsentService(TenantAuthorization authorization,
                                     TenantCurrencyPolicyService currencies) {
        this.authorization=authorization;
        this.currencies=currencies;
    }

    /**
     * Internal admission for a future consent command. Caller must derive the
     * complete currency set from canonical immutable target bindings; this
     * preliminary API is not an external approval endpoint.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireProductConsentAuthority(VerifiedActor actor, UUID tenantId,
                                               Collection<String> anchorCurrencies) {
        if(actor==null || tenantId==null) throw new SecurityException("FX_AUTO_VERIFIED_ACTOR_REQUIRED");
        if(anchorCurrencies==null || anchorCurrencies.isEmpty())
            throw new IllegalArgumentException("FX_AUTO_ANCHOR_SCOPE_REQUIRED");
        var ordered=new TreeSet<String>();
        for(String currency:anchorCurrencies) {
            if(currency==null || !Set.of("USD","EUR","CNY").contains(currency))
                throw new IllegalArgumentException("FX_AUTO_ANCHOR_SCOPE_INVALID");
            ordered.add(currency);
        }
        UUID user=actor.userId();
        authorization.requireForCommand(user,tenantId,DomainPermission.TENANT_RBAC_MANAGE);
        authorization.requireForCommand(user,tenantId,DomainPermission.STOREFRONT_OFFER_MANAGE);
        for(String currency:ordered) currencies.requirePricingAnchor(tenantId,currency);
        // Recheck request lifetime after any lock wait.
        actor.userId();
    }
}
