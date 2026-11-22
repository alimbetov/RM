package kz.rm.storefront;

import kz.rm.iam.VerifiedActor;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.DomainPermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Compatibility facade for PASS 08.4 callers. The single authority for
 * offer mutations, locking, audit, RBAC and strict version CAS is
 * StorefrontOfferService; storefront mutations belong to StorefrontManagementService.
 * This facade MUST NOT issue direct mutations to either table.
 */
@Service
public class SellerOfferService {
    public record Offer(UUID tenantId, UUID id, UUID catalogItemId, String title,
                        BigDecimal price, String currency, String state, long rowVersion) {}
    public record Storefront(UUID tenantId, UUID id, String slug, String state, long rowVersion) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final StorefrontOfferService offers;
    private final StorefrontManagementService storefronts;

    public SellerOfferService(JdbcTemplate jdbc, StorefrontOfferService offers,
                              StorefrontManagementService storefronts,
                              TenantAuthorization authorization) {
        this.jdbc=jdbc;
        this.authorization=authorization;
        this.offers=offers;
        this.storefronts=storefronts;
    }

    private static Offer asOffer(StorefrontOfferService.Offer value) {
        return new Offer(value.tenantId(),value.id(),value.catalogItemId(),
                value.title(),value.unitPrice(),value.currency(),
                value.state().name(),value.rowVersion());
    }

    private static Storefront asStorefront(StorefrontManagementService.Storefront value) {
        return new Storefront(value.tenantId(),value.id(),value.slug(),
                value.state().name(),value.rowVersion());
    }

    private UUID storefrontId(UUID tenant) {
        if(tenant==null)throw new SecurityException("Tenant required");
        List<UUID> ids=jdbc.query(
                "SELECT id FROM crm.store_front WHERE tenant_id=?",
                (rs,n)->rs.getObject(1,UUID.class),tenant);
        if(ids.isEmpty())throw new IllegalArgumentException("Storefront unavailable");
        return ids.getFirst();
    }

    private static IllegalStateException normalize(IllegalStateException cause) {
        if(cause.getMessage()!=null && cause.getMessage().contains("STALE_OFFER_VERSION"))
            return new IllegalStateException("OFFER_VERSION_CONFLICT",cause);
        if(cause.getMessage()!=null && cause.getMessage().contains("STALE_STOREFRONT_VERSION"))
            return new IllegalStateException("OFFER_VERSION_CONFLICT",cause);
        if(cause.getMessage()!=null && cause.getMessage().contains("eligible catalog PRODUCT"))
            return new IllegalStateException("OFFER_SKU_NOT_SELLABLE",cause);
        return cause;
    }

    @Transactional
    public Storefront createStorefrontDraft(VerifiedActor actor,UUID tenant,String slug) {
        if(actor==null || tenant==null)throw new SecurityException("Verified actor and tenant required");
        authorization.requireForCommand(actor.userId(),tenant,DomainPermission.STOREFRONT_OFFER_MANAGE);
        if(jdbc.queryForObject("SELECT count(*) FROM crm.store_front WHERE tenant_id=?",
                Integer.class,tenant)>0)
            throw new IllegalStateException("Tenant storefront already exists");
        return asStorefront(storefronts.createDraft(actor,tenant,slug));
    }

    @Transactional
    public Offer createDraft(VerifiedActor actor,UUID tenant,UUID itemId,
                             String title,BigDecimal amount,String isoCurrency) {
        try {
            return asOffer(offers.createDraft(actor,tenant,itemId,title,amount,isoCurrency));
        } catch(IllegalStateException ex) {
            throw normalize(ex);
        }
    }

    @Transactional
    public Offer publish(VerifiedActor actor,UUID tenant,UUID offerId,long expectedVersion) {
        try {
            return asOffer(offers.publish(actor,tenant,offerId,expectedVersion));
        } catch(IllegalStateException ex) {
            throw normalize(ex);
        } catch(IllegalArgumentException ex) {
            if(ex.getMessage()!=null && ex.getMessage().contains("not found in tenant"))
                throw new IllegalArgumentException("Offer unavailable");
            throw ex;
        }
    }

    @Transactional
    public Offer pause(VerifiedActor actor,UUID tenant,UUID offerId,long expectedVersion) {
        try {
            return asOffer(offers.pause(actor,tenant,offerId,expectedVersion));
        } catch(IllegalStateException ex) {
            throw normalize(ex);
        }
    }

    @Transactional
    public Offer resume(VerifiedActor actor,UUID tenant,UUID offerId,long expectedVersion) {
        return publish(actor,tenant,offerId,expectedVersion);
    }

    @Transactional
    public Offer changePrice(VerifiedActor actor,UUID tenant,UUID offerId,
                             long expectedVersion,BigDecimal amount,String isoCurrency) {
        // The current title is read only after the same tenant authorization as
        // the mutation. The canonical service owns lock/version revalidation.
        StorefrontOfferService.Offer before=offers.get(actor,tenant,offerId);
        if(before.rowVersion()!=expectedVersion)
            throw new IllegalStateException("OFFER_VERSION_CONFLICT");
        if(amount==null)throw new IllegalArgumentException("OFFER_INVALID_PRICE");
        if(before.unitPrice().compareTo(amount)==0 && before.currency().equals(isoCurrency))
            throw new IllegalArgumentException("Offer price unchanged");
        try {
            return asOffer(offers.updatePrice(actor,tenant,offerId,before.title(),amount,
                    isoCurrency,expectedVersion));
        } catch(IllegalStateException ex) {
            throw normalize(ex);
        }
    }

    @Transactional
    public Storefront changeStorefrontState(VerifiedActor actor,UUID tenant,
                                            long expectedVersion,String target) {
        if(actor==null || tenant==null)throw new SecurityException("Verified actor and tenant required");
        authorization.requireForCommand(actor.userId(),tenant,
                "PUBLISHED".equals(target) ? DomainPermission.STOREFRONT_OFFER_PUBLISH
                    : DomainPermission.STOREFRONT_OFFER_MANAGE);
        UUID id=storefrontId(tenant);
        try {
            return asStorefront(switch(target==null?"":target) {
                case "PUBLISHED" -> storefronts.publish(actor,tenant,id,expectedVersion);
                case "PAUSED" -> storefronts.pause(actor,tenant,id,expectedVersion);
                default -> throw new IllegalArgumentException("Unsupported storefront state");
            });
        } catch(IllegalStateException ex) {
            throw normalize(ex);
        }
    }
}
