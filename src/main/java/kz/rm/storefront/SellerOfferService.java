package kz.rm.storefront;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Service-only tenant-authorized seller commands. No public transport adapter.
 * Offer prices are the checkout source; catalogue price revisions are independent.
 * Already accepted sales_order_line snapshots are never updated here.
 */
@Service
public class SellerOfferService {
    public record Offer(UUID tenantId, UUID id, UUID catalogItemId, String title,
                        BigDecimal price, String currency, String state, long rowVersion) {}
    public record Storefront(UUID tenantId, UUID id, String slug, String state, long rowVersion) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public SellerOfferService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    private void admit(VerifiedActor actor, UUID tenant, DomainPermission permission) {
        if (actor == null || tenant == null)
            throw new SecurityException("Verified actor and tenant required");
        authorization.requireForCommand(actor.userId(), tenant, permission);
    }

    private Storefront storefront(UUID tenant, boolean lock) {
        List<Storefront> rows = jdbc.query("""
                SELECT tenant_id,id,slug,state,row_version FROM crm.store_front
                WHERE tenant_id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Storefront(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getLong(5)), tenant);
        if (rows.isEmpty()) throw new IllegalArgumentException("Storefront unavailable");
        return rows.getFirst();
    }

    private Offer offer(UUID tenant, UUID offerId, boolean lock) {
        if (offerId == null) throw new IllegalArgumentException("Offer ID required");
        List<Offer> rows = jdbc.query("""
                SELECT tenant_id,id,catalog_item_id,title,unit_price,currency,state,row_version
                FROM crm.store_offer WHERE tenant_id=? AND id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Offer(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getBigDecimal(5),
                rs.getString(6), rs.getString(7), rs.getLong(8)), tenant, offerId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Offer unavailable");
        return rows.getFirst();
    }

    private static void version(long expected, long actual) {
        if (expected < 0) throw new IllegalArgumentException("Invalid expected version");
        if (actual != expected) throw new IllegalStateException("OFFER_VERSION_CONFLICT");
    }

    private static String title(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 240)
            throw new IllegalArgumentException("Offer title must have 1..240 characters");
        return value.trim();
    }

    private static BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() > 4
                || value.precision() - value.scale() > 15)
            throw new IllegalArgumentException("OFFER_INVALID_PRICE");
        return value;
    }

    private static String currency(String value) {
        if (value == null || !value.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Invalid currency");
        try {
            java.util.Currency.getInstance(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unsupported ISO 4217 currency", invalid);
        }
        return value;
    }

    private void sellableSku(UUID tenant, UUID itemId) {
        if (itemId == null) throw new IllegalStateException("OFFER_SKU_NOT_SELLABLE");
        List<UUID> items = jdbc.query("""
                SELECT id FROM crm.cat_item
                WHERE tenant_id=? AND id=? AND state='PUBLISHED' AND kind='PRODUCT'
                FOR UPDATE
                """, (rs, n) -> rs.getObject(1, UUID.class), tenant, itemId);
        if (items.size() != 1)
            throw new IllegalStateException("OFFER_SKU_NOT_SELLABLE");
    }

    private void publicationEligible(UUID tenant, Storefront front, UUID catalogItemId) {
        if (!"PUBLISHED".equals(front.state()))
            throw new IllegalStateException("STOREFRONT_UNAVAILABLE");
        sellableSku(tenant, catalogItemId);
    }

    @Transactional
    public Storefront createStorefrontDraft(VerifiedActor actor, UUID tenant, String slug) {
        admit(actor, tenant, DomainPermission.STOREFRONT_OFFER_MANAGE);
        if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{2,99}"))
            throw new IllegalArgumentException("Invalid storefront slug");
        Integer existing = jdbc.queryForObject("""
                SELECT count(*) FROM crm.store_front WHERE tenant_id=?
                """, Integer.class, tenant);
        if (existing != null && existing != 0)
            throw new IllegalStateException("Tenant storefront already exists");
        UUID storefrontId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at,row_version)
                VALUES (?,?,?,'DRAFT',CURRENT_TIMESTAMP,0)
                """, tenant, storefrontId, slug);
        return storefront(tenant, false);
    }

    @Transactional
    public Offer createDraft(VerifiedActor actor, UUID tenant, UUID itemId,
                             String offerTitle, BigDecimal amount, String isoCurrency) {
        admit(actor, tenant, DomainPermission.STOREFRONT_OFFER_MANAGE);
        storefront(tenant, true);
        // Draft can be prepared before the catalogue item is published,
        // but it must already be a tenant-owned PRODUCT.
        Integer skuCount = jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_item
                WHERE tenant_id=? AND id=? AND kind='PRODUCT'
                """, Integer.class, tenant, itemId);
        if (skuCount == null || skuCount != 1)
            throw new IllegalStateException("OFFER_SKU_NOT_SELLABLE");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.store_offer
                (tenant_id,id,title,unit_price,currency,state,catalog_item_id,row_version)
                VALUES (?,?,?,?,?,'DRAFT',?,0)
                """, tenant, id, title(offerTitle), price(amount), currency(isoCurrency), itemId);
        return offer(tenant, id, false);
    }

    @Transactional
    public Offer publish(VerifiedActor actor, UUID tenant, UUID offerId, long expectedVersion) {
        return transition(actor, tenant, offerId, expectedVersion, "DRAFT", "PUBLISHED",
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
    }

    @Transactional
    public Offer pause(VerifiedActor actor, UUID tenant, UUID offerId, long expectedVersion) {
        return transition(actor, tenant, offerId, expectedVersion, "PUBLISHED", "PAUSED",
                DomainPermission.STOREFRONT_OFFER_MANAGE);
    }

    @Transactional
    public Offer resume(VerifiedActor actor, UUID tenant, UUID offerId, long expectedVersion) {
        return transition(actor, tenant, offerId, expectedVersion, "PAUSED", "PUBLISHED",
                DomainPermission.STOREFRONT_OFFER_PUBLISH);
    }

    private Offer transition(VerifiedActor actor, UUID tenant, UUID offerId, long expected,
                             String source, String target, DomainPermission permission) {
        admit(actor, tenant, permission);
        // Lock order is shared with checkout: storefront -> offer -> catalogue.
        Storefront front = storefront(tenant, true);
        Offer old = offer(tenant, offerId, true);
        version(expected, old.rowVersion());
        if (!source.equals(old.state())) throw new IllegalStateException("OFFER_INVALID_STATE");
        if ("PUBLISHED".equals(target)) publicationEligible(tenant, front, old.catalogItemId());
        int changed = jdbc.update("""
                UPDATE crm.store_offer SET state=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND row_version=? AND state=?
                """, target, tenant, offerId, expected, source);
        if (changed != 1) throw new IllegalStateException("OFFER_VERSION_CONFLICT");
        return offer(tenant, offerId, false);
    }

    @Transactional
    public Offer changePrice(VerifiedActor actor, UUID tenant, UUID offerId,
                             long expectedVersion, BigDecimal amount, String isoCurrency) {
        admit(actor, tenant, DomainPermission.STOREFRONT_OFFER_MANAGE);
        storefront(tenant, true);
        Offer old = offer(tenant, offerId, true);
        version(expectedVersion, old.rowVersion());
        if (!List.of("DRAFT", "PUBLISHED", "PAUSED", "HIDDEN").contains(old.state()))
            throw new IllegalStateException("OFFER_INVALID_STATE");
        BigDecimal validated = price(amount);
        String validatedCurrency = currency(isoCurrency);
        if (old.price().compareTo(validated) == 0 && old.currency().equals(validatedCurrency))
            throw new IllegalArgumentException("Offer price unchanged");
        int changed = jdbc.update("""
                UPDATE crm.store_offer SET unit_price=?,currency=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND row_version=?
                """, validated, validatedCurrency, tenant, offerId, expectedVersion);
        if (changed != 1) throw new IllegalStateException("OFFER_VERSION_CONFLICT");
        return offer(tenant, offerId, false);
    }

    @Transactional
    public Storefront changeStorefrontState(VerifiedActor actor, UUID tenant,
                                            long expectedVersion, String target) {
        DomainPermission permission = "PUBLISHED".equals(target)
                ? DomainPermission.STOREFRONT_OFFER_PUBLISH
                : DomainPermission.STOREFRONT_OFFER_MANAGE;
        if (!List.of("PUBLISHED", "PAUSED").contains(target))
            throw new IllegalArgumentException("Unsupported storefront state");
        admit(actor, tenant, permission);
        Storefront front = storefront(tenant, true);
        version(expectedVersion, front.rowVersion());
        boolean allowed = "PUBLISHED".equals(target)
                ? List.of("DRAFT", "PAUSED").contains(front.state())
                : "PUBLISHED".equals(front.state());
        if (!allowed) throw new IllegalStateException("OFFER_INVALID_STATE");
        int changed = jdbc.update("""
                UPDATE crm.store_front SET state=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND row_version=?
                """, target, tenant, front.id(), expectedVersion);
        if (changed != 1) throw new IllegalStateException("OFFER_VERSION_CONFLICT");
        return storefront(tenant, false);
    }
}
