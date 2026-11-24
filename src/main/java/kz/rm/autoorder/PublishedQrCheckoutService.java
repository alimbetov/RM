package kz.rm.autoorder;

import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * QR-03 service-only adapter from a stable PRODUCT_OFFER ref to the existing
 * email-first AUTOORDER checkout. It never creates a second SALES order ledger.
 *
 * A revoked alias can still be resolved ONLY as a lookup key for an exact
 * idempotent replay of a previously accepted checkout. AutoOrderService
 * revalidates current publication, token state and price under seller row locks
 * before any NEW order and IAM identity are created.
 */
@Service
public class PublishedQrCheckoutService {
    private record OfferTarget(String sellerSlug,UUID offerId) {}

    private final JdbcTemplate jdbc;
    private final AutoOrderService checkout;
    @org.springframework.beans.factory.annotation.Value("${rm.qr.legacy-email-first-checkout.enabled:false}")
    private boolean legacyEmailFirstQrEnabled;

    public PublishedQrCheckoutService(JdbcTemplate jdbc,AutoOrderService checkout) {
        this.jdbc=jdbc;
        this.checkout=checkout;
    }

    /** Legacy email-first QR path: disabled except explicitly opted-in compatibility tests. */
    public AutoOrderService.CheckoutResult checkout(String publicOfferRef,String email,
            int quantity,long acceptedOfferVersion,BigDecimal acceptedUnitPrice,
            String acceptedCurrency,String requestKey) {
        if(!legacyEmailFirstQrEnabled)
            throw new SecurityException("QR_VERIFIED_BUYER_REQUIRED");
        requireQuantity(quantity);
        OfferTarget target=resolveTarget(publicOfferRef);
        return checkout.checkoutPublishedQr(target.sellerSlug(),email,requestKey,
                new AutoOrderService.Line(target.offerId(),quantity),
                new AutoOrderService.QrPriceAcknowledgement(publicOfferRef,
                        target.offerId(),acceptedOfferVersion,acceptedUnitPrice,
                        acceptedCurrency));
    }

    public AutoOrderService.CheckoutResult checkoutVerified(VerifiedActor buyer,
            String publicOfferRef,int quantity,long acceptedOfferVersion,
            BigDecimal acceptedUnitPrice,String acceptedCurrency,String requestKey) {
        if(buyer==null)throw new SecurityException("QR_VERIFIED_BUYER_REQUIRED");
        buyer.userId();
        requireQuantity(quantity);
        OfferTarget target=resolveTarget(publicOfferRef);
        return checkout.checkoutVerifiedPublishedQr(buyer,target.sellerSlug(),requestKey,
                new AutoOrderService.Line(target.offerId(),quantity),
                new AutoOrderService.QrPriceAcknowledgement(publicOfferRef,
                        target.offerId(),acceptedOfferVersion,acceptedUnitPrice,
                        acceptedCurrency));
    }

    private static void requireQuantity(int quantity) {
        if(quantity<1||quantity>1000)throw new IllegalArgumentException("QTY_INVALID");
    }

    private OfferTarget resolveTarget(String publicOfferRef) {
        if(publicOfferRef==null || !publicOfferRef.matches("[A-Za-z0-9_-]{32}"))
            throw new IllegalArgumentException("QR_UNAVAILABLE");
        // Lookup is nonsecret; authoritative offer/price/QR validation is locked downstream.
        List<OfferTarget> targets=jdbc.query("""
                SELECT sf.slug,o.id
                FROM crm.store_offer_qr_ref q
                JOIN crm.store_offer o ON o.tenant_id=q.tenant_id AND o.id=q.offer_id
                JOIN crm.store_front sf ON sf.tenant_id=q.tenant_id
                WHERE q.public_ref=?
                """,(rs,n)->new OfferTarget(rs.getString(1),rs.getObject(2,UUID.class)),
                publicOfferRef);
        if(targets.size()!=1)throw new IllegalArgumentException("QR_UNAVAILABLE");
        return targets.getFirst();
    }
}
