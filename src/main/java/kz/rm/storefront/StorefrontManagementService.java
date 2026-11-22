package kz.rm.storefront;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RM-STORE-01: small tenant-authorized storefront lifecycle.
 *
 * The current schema has no storefront row_version, so compare-and-set uses the
 * expected state and a locked row. A future version-column migration must
 * preserve these externally observable state transitions.
 *
 * Offer ownership, catalog publication and the seller's offer price belong to
 * their respective services. This service never writes store_offer.
 */
@Service
public class StorefrontManagementService {
    public record Storefront(UUID tenantId, UUID id, String slug, StorefrontSettings.State state) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public StorefrontManagementService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @Transactional
    public Storefront createDraft(VerifiedActor actor, UUID tenant, String slug) {
        UUID user = authorize(actor, tenant, DomainPermission.STOREFRONT_OFFER_MANAGE);
        if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{2,99}"))
            throw new IllegalArgumentException("Invalid storefront slug");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at)
                VALUES (?,?,?,'DRAFT',?)
                """, tenant, id, slug, Timestamp.from(Instant.now()));
        audit(tenant, user, id, "STOREFRONT_CREATED");
        return new Storefront(tenant, id, slug, StorefrontSettings.State.DRAFT);
    }

    @Transactional
    public Storefront publish(VerifiedActor actor, UUID tenant, UUID storefront,
                              StorefrontSettings.State expectedState) {
        return transition(actor, tenant, storefront, expectedState,
                StorefrontSettings.State.PUBLISHED);
    }

    @Transactional
    public Storefront pause(VerifiedActor actor, UUID tenant, UUID storefront,
                            StorefrontSettings.State expectedState) {
        return transition(actor, tenant, storefront, expectedState,
                StorefrontSettings.State.PAUSED);
    }

    @Transactional(readOnly = true)
    public Storefront get(VerifiedActor actor, UUID tenant, UUID storefront) {
        // No storefront READ permission is registered in R2; do not open a
        // tenant-wide public management read or assume the staff is OWNER.
        require(actor, tenant, DomainPermission.STOREFRONT_OFFER_MANAGE);
        return fetch(tenant, storefront, false);
    }

    private Storefront transition(VerifiedActor actor, UUID tenant, UUID id,
                                  StorefrontSettings.State expected,
                                  StorefrontSettings.State target) {
        UUID user = authorize(actor, tenant, DomainPermission.STOREFRONT_OFFER_PUBLISH);
        if (expected == null || id == null)
            throw new IllegalArgumentException("Storefront and expected state required");
        Storefront existing = fetch(tenant, id, true);
        if (existing.state() != expected)
            throw new IllegalStateException("STALE_STOREFRONT_STATE");
        if (existing.state() == target) return existing; // proven no-op
        boolean legal = (target == StorefrontSettings.State.PUBLISHED
                && (expected == StorefrontSettings.State.DRAFT
                    || expected == StorefrontSettings.State.PAUSED))
                || (target == StorefrontSettings.State.PAUSED
                    && expected == StorefrontSettings.State.PUBLISHED);
        if (!legal) throw new IllegalStateException("Invalid storefront transition");
        int changed = jdbc.update("""
                UPDATE crm.store_front SET state=?
                WHERE tenant_id=? AND id=? AND state=?
                """, target.name(), tenant, id, expected.name());
        if (changed != 1) throw new IllegalStateException("Concurrent storefront transition");
        audit(tenant, user, id, target == StorefrontSettings.State.PAUSED
                ? "STOREFRONT_PAUSED" : "STOREFRONT_PUBLISHED");
        return new Storefront(tenant, id, existing.slug(), target);
    }

    private Storefront fetch(UUID tenant, UUID id, boolean lock) {
        if (tenant == null || id == null)
            throw new IllegalArgumentException("Storefront identity required");
        String sql = """
                SELECT tenant_id,id,slug,state FROM crm.store_front
                WHERE tenant_id=? AND id=?
                """ + (lock ? " FOR UPDATE" : "");
        List<Storefront> rows = jdbc.query(sql, (rs, n) -> new Storefront(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), StorefrontSettings.State.valueOf(rs.getString(4))),
                tenant, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Storefront not found in tenant");
        return rows.getFirst();
    }

    private UUID authorize(VerifiedActor actor, UUID tenant, DomainPermission permission) {
        if (actor == null || tenant == null)
            throw new SecurityException("Verified actor and selected tenant required");
        authorization.requireForCommand(actor.userId(), tenant, permission);
        return actor.userId();
    }

    private void require(VerifiedActor actor, UUID tenant, DomainPermission permission) {
        if (actor == null || tenant == null)
            throw new SecurityException("Verified actor and selected tenant required");
        authorization.require(actor.userId(), tenant, permission);
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
