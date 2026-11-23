package kz.rm.storefront;

import kz.rm.iam.DomainPermission;
import kz.rm.organization.CommerceCurrencyAdmission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The seller-owned commercial offer is not a CATALOG price revision.
 * One storefront exists per tenant (uk_store_tenant); offers are tenant-bound.
 *
 * Every mutation locks IAM admission, store_front and then store_offer.
 * Publication additionally locks cat_item, matching AutoOrderService checkout
 * (store_front -> store_offer -> cat_item). No remapping of an existing SKU.
 */
@Service
public class StorefrontOfferService {
    public enum State { DRAFT, PUBLISHED, PAUSED, HIDDEN }

    public record Offer(UUID tenantId, UUID id, UUID catalogItemId, String title,
                        BigDecimal unitPrice, String currency, State state,
                        long rowVersion) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private final CommerceCurrencyAdmission currencyAdmission;

    public StorefrontOfferService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this(jdbc, authorization, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StorefrontOfferService(JdbcTemplate jdbc, TenantAuthorization authorization,
                                 CommerceCurrencyAdmission currencyAdmission) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.currencyAdmission = currencyAdmission;
    }

    private void lockCurrency(UUID tenant) {
        if (currencyAdmission != null) currencyAdmission.lockKzMarket(tenant);
    }

    private void requirePublicCurrency(String currency) {
        if (currencyAdmission != null) currencyAdmission.assertKzt(currency);
    }

    @Transactional
    public Offer createDraft(VerifiedActor actor, UUID tenant, UUID catalogItemId,
                             String title, BigDecimal amount, String currency) {
        UUID user = admit(actor, tenant, DomainPermission.STOREFRONT_OFFER_MANAGE);
        lockCurrency(tenant);
        lockStorefront(tenant);
        validatePrice(title, amount, currency);
        validateCatalogProduct(tenant, catalogItemId, false);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.store_offer(tenant_id,id,title,unit_price,currency,
                                            state,catalog_item_id,row_version)
                VALUES (?,?,?,?,?,'DRAFT',?,0)
                """, tenant, id, title.trim(), amount, currency, catalogItemId);
        recordPriceRevision(tenant,id,0,null,amount,null,currency,user);
        audit(tenant, user, id, "STOREFRONT_OFFER_CREATED");
        return new Offer(tenant, id, catalogItemId, title.trim(), amount,
                currency, State.DRAFT, 0);
    }

    @Transactional
    public Offer updatePrice(VerifiedActor actor, UUID tenant, UUID offerId,
                             String title, BigDecimal amount, String currency,
                             long expectedVersion) {
        UUID user = admit(actor, tenant, DomainPermission.STOREFRONT_OFFER_MANAGE);
        lockCurrency(tenant);
        lockStorefront(tenant);
        validatePrice(title, amount, currency);
        Offer current = locked(tenant, offerId, expectedVersion);
        if (current.state() == State.PUBLISHED) requirePublicCurrency(currency);
        if (current.state() != State.DRAFT && current.state() != State.PAUSED
                && current.state() != State.HIDDEN && current.state() != State.PUBLISHED)
            throw new IllegalStateException("Invalid offer state");
        String normalized = title.trim();
        if (current.title().equals(normalized)
                && current.unitPrice().compareTo(amount) == 0
                && current.currency().equals(currency)) return current;
        int changed = jdbc.update("""
                UPDATE crm.store_offer
                SET title=?,unit_price=?,currency=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND row_version=? AND state=?
                """, normalized, amount, currency, tenant, offerId, expectedVersion,
                current.state().name());
        if (changed != 1) throw new IllegalStateException("Concurrent offer modification");
        if(current.unitPrice().compareTo(amount)!=0 || !current.currency().equals(currency))
            recordPriceRevision(tenant,offerId,expectedVersion+1,
                    current.unitPrice(),amount,current.currency(),currency,user);
        audit(tenant, user, offerId, "STOREFRONT_OFFER_PRICE_UPDATED");
        return new Offer(tenant, offerId, current.catalogItemId(), normalized,
                amount, currency, current.state(), expectedVersion + 1);
    }

    /**
     * Explicit seller decision: delegates to the canonical offer CAS and
     * immutable price revision. Never uses an FX rate during checkout.
     */
    @Transactional
    public Offer approveSuggestedKzt(VerifiedActor actor, UUID tenant, UUID offerId,
                                     BigDecimal approvedKzt, long expectedVersion) {
        Offer current=get(actor,tenant,offerId);
        if(current.state()!=State.PUBLISHED)
            throw new IllegalStateException("FX_SELLER_APPROVAL_PUBLISHED_ONLY");
        if(current.rowVersion()!=expectedVersion)
            throw new IllegalStateException("STALE_OFFER_VERSION");
        if(!"KZT".equals(current.currency()))
            throw new IllegalStateException("FX_SELLER_APPROVAL_KZT_ONLY");
        return updatePrice(actor,tenant,offerId,current.title(),
                approvedKzt,"KZT",expectedVersion);
    }

    @Transactional
    public Offer publish(VerifiedActor actor, UUID tenant, UUID offerId, long expectedVersion) {
        return transition(actor, tenant, offerId, expectedVersion, State.PUBLISHED);
    }

    @Transactional
    public Offer pause(VerifiedActor actor, UUID tenant, UUID offerId, long expectedVersion) {
        return transition(actor, tenant, offerId, expectedVersion, State.PAUSED);
    }

    @Transactional
    public Offer hide(VerifiedActor actor, UUID tenant, UUID offerId, long expectedVersion) {
        return transition(actor, tenant, offerId, expectedVersion, State.HIDDEN);
    }

    @Transactional
    public Offer get(VerifiedActor actor, UUID tenant, UUID offerId) {
        if (actor == null || tenant == null)
            throw new SecurityException("Verified actor and selected tenant required");
        authorization.requireForCommand(actor.userId(), tenant, DomainPermission.STOREFRONT_OFFER_MANAGE);
        return fetch(tenant, offerId, false);
    }

    private Offer transition(VerifiedActor actor, UUID tenant, UUID offerId,
                             long expectedVersion, State target) {
        UUID user = admit(actor, tenant, target == State.PUBLISHED
                ? DomainPermission.STOREFRONT_OFFER_PUBLISH
                : DomainPermission.STOREFRONT_OFFER_MANAGE);
        lockCurrency(tenant);
        lockStorefront(tenant);
        Offer current = locked(tenant, offerId, expectedVersion);
        if (current.state() == target)
            throw new IllegalStateException("OFFER_INVALID_STATE");
        boolean legal = switch (target) {
            case PUBLISHED -> current.state() == State.DRAFT
                    || current.state() == State.PAUSED || current.state() == State.HIDDEN;
            case PAUSED, HIDDEN -> current.state() == State.PUBLISHED
                    || current.state() == State.PAUSED || current.state() == State.HIDDEN;
            case DRAFT -> false;
        };
        if (!legal) throw new IllegalStateException("Invalid offer transition");
        if (target == State.PUBLISHED) {
            String storeState=jdbc.query("""
                    SELECT state FROM crm.store_front WHERE tenant_id=?
                    """, rs -> rs.next() ? rs.getString(1) : null, tenant);
            if (!"PUBLISHED".equals(storeState))
                throw new IllegalStateException("STOREFRONT_UNAVAILABLE");
            requirePublicCurrency(current.currency());
            validateCatalogProduct(tenant, current.catalogItemId(), true);
        }
        int changed = jdbc.update("""
                UPDATE crm.store_offer SET state=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND row_version=? AND state=?
                """, target.name(), tenant, offerId, expectedVersion, current.state().name());
        if (changed != 1) throw new IllegalStateException("Concurrent offer transition");
        audit(tenant, user, offerId, switch (target) {
            case PUBLISHED -> "STOREFRONT_OFFER_PUBLISHED";
            case PAUSED -> "STOREFRONT_OFFER_PAUSED";
            case HIDDEN -> "STOREFRONT_OFFER_HIDDEN";
            case DRAFT -> throw new IllegalStateException("Unexpected draft transition");
        });
        return new Offer(tenant, offerId, current.catalogItemId(), current.title(),
                current.unitPrice(), current.currency(), target, expectedVersion + 1);
    }

    private Offer locked(UUID tenant, UUID id, long expectedVersion) {
        if (expectedVersion < 0)
            throw new IllegalArgumentException("Expected offer version required");
        Offer current = fetch(tenant, id, true);
        if (current.rowVersion() != expectedVersion)
            throw new IllegalStateException("STALE_OFFER_VERSION");
        return current;
    }

    private Offer fetch(UUID tenant, UUID id, boolean lock) {
        if (tenant == null || id == null)
            throw new IllegalArgumentException("Offer identity required");
        String sql = """
                SELECT tenant_id,id,catalog_item_id,title,unit_price,currency,state,row_version
                FROM crm.store_offer WHERE tenant_id=? AND id=?
                """ + (lock ? " FOR UPDATE" : "");
        List<Offer> rows = jdbc.query(sql, (rs, n) -> new Offer(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getBigDecimal(5),
                rs.getString(6), State.valueOf(rs.getString(7)), rs.getLong(8)),
                tenant, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Offer not found in tenant");
        return rows.getFirst();
    }

    /** The offer CAS update and its immutable history commit atomically. */
    private void recordPriceRevision(UUID tenant,UUID offerId,long version,
                                     BigDecimal oldPrice,BigDecimal newPrice,
                                     String oldCurrency,String newCurrency,UUID actor) {
        jdbc.update("""
                INSERT INTO crm.store_offer_price_revision
                  (tenant_id,id,offer_id,offer_row_version,old_price,new_price,
                   old_currency,new_currency,actor_user_id,recorded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),offerId,version,oldPrice,newPrice,
                oldCurrency,newCurrency,actor,Timestamp.from(Instant.now()));
    }

    public record PriceRevision(UUID id,long offerRowVersion,
                                BigDecimal oldPrice,BigDecimal newPrice,
                                String oldCurrency,String newCurrency,
                                UUID actorUserId,Instant recordedAt) {}

    @Transactional
    public List<PriceRevision> priceHistory(VerifiedActor actor,UUID tenant,
                                             UUID offerId,int page,int size) {
        admit(actor,tenant,DomainPermission.STOREFRONT_OFFER_MANAGE);
        if(offerId==null||page<0||size<1||size>100)
            throw new IllegalArgumentException("Invalid price history page");
        fetch(tenant,offerId,false); // do not leak foreign-tenant records
        return jdbc.query("""
                SELECT id,offer_row_version,old_price,new_price,old_currency,
                       new_currency,actor_user_id,recorded_at
                FROM crm.store_offer_price_revision
                WHERE tenant_id=? AND offer_id=?
                ORDER BY offer_row_version DESC,id DESC LIMIT ? OFFSET ?
                """,(rs,n)->new PriceRevision(rs.getObject(1,UUID.class),rs.getLong(2),
                        rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getString(5),
                        rs.getString(6),rs.getObject(7,UUID.class),
                        rs.getTimestamp(8).toInstant()),
                tenant,offerId,size,(long)page*size);
    }

    private void lockStorefront(UUID tenant) {
        UUID id = jdbc.query("""
                SELECT id FROM crm.store_front WHERE tenant_id=? FOR UPDATE
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenant);
        if (id == null) throw new IllegalStateException("Tenant storefront required");
    }

    private void validateCatalogProduct(UUID tenant, UUID catalogItemId,
                                        boolean requirePublished) {
        if (catalogItemId == null)
            throw new IllegalArgumentException("Catalog PRODUCT required");
        record Product(String kind, String state) {}
        Product product = jdbc.query("""
                SELECT kind,state FROM crm.cat_item
                WHERE tenant_id=? AND id=? FOR UPDATE
                """, rs -> rs.next() ? new Product(rs.getString(1), rs.getString(2)) : null,
                tenant, catalogItemId);
        if (product == null || !"PRODUCT".equals(product.kind())
                || "ARCHIVED".equals(product.state())
                || (requirePublished && !"PUBLISHED".equals(product.state())))
            throw new IllegalStateException("Offer requires eligible catalog PRODUCT");
    }

    private static void validatePrice(String title, BigDecimal amount, String currency) {
        if (title == null || title.isBlank() || title.trim().length() > 240
                || amount == null || amount.signum() <= 0 || amount.scale() > 4
                || amount.precision() - amount.scale() > 15
                || currency == null || !currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Invalid seller offer title/price/currency");
        try {
            java.util.Currency.getInstance(currency);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid ISO 4217 currency", invalid);
        }
    }

    private UUID admit(VerifiedActor actor, UUID tenant, DomainPermission permission) {
        if (actor == null || tenant == null)
            throw new SecurityException("Verified actor and selected tenant required");
        authorization.requireForCommand(actor.userId(), tenant, permission);
        return actor.userId();
    }

    private void audit(UUID tenant, UUID actor, UUID entity, String action) {
        jdbc.update("""
                INSERT INTO crm.audit_event
                  (tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)
                VALUES (?,?,?,?,?,?)
                """, tenant, UUID.randomUUID(), actor, entity, action,
                Timestamp.from(Instant.now()));
    }
}
