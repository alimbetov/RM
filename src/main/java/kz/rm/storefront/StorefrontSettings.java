package kz.rm.storefront;
import java.util.UUID;
import kz.rm.architecture.SupportedLocale;
/** Mandatory-email autoorder: these security invariants are not optional settings. */
public record StorefrontSettings(UUID tenantId, String slug, State state,
                                 String defaultLocale, boolean acceptOrders) {
    public enum State { DRAFT, PUBLISHED, PAUSED }
    public StorefrontSettings {
        if(tenantId==null||slug==null||!slug.matches("[a-z0-9][a-z0-9-]{2,99}")
                ||state==null||defaultLocale==null)
            throw new IllegalArgumentException("Invalid storefront settings");
        SupportedLocale.fromCode(defaultLocale);
    }
    public boolean canCheckout(){return state==State.PUBLISHED&&acceptOrders;}
    public boolean emailRequired(){return true;}
    public boolean autoProvisionBuyer(){return true;}
}
