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
 * Tenant-authorized management of the single storefront owned by a workspace.
 * The schema already has row_version and uk_store_tenant; no additional
 * storefront identity or feature flag is needed.
 *
 * Lock order shared with checkout and offer commands:
 * IAM actor/workspace -> store_front -> store_offer -> cat_item.
 */
@Service
public class StorefrontManagementService {
    public record Storefront(UUID tenantId, UUID id, String slug,
                             StorefrontSettings.State state, long rowVersion) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public StorefrontManagementService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @Transactional
    public Storefront createDraft(VerifiedActor actor, UUID tenant, String slug) {
        UUID user = admit(actor, tenant, DomainPermission.STOREFRONT_OFFER_MANAGE);
        if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{2,99}"))
            throw new IllegalArgumentException("Invalid storefront slug");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.store_front(tenant_id,id,slug,state,created_at,row_version)
                VALUES (?,?,?,'DRAFT',?,0)
                """, tenant, id, slug, Timestamp.from(Instant.now()));
        audit(tenant, user, id, "STOREFRONT_CREATED");
        return new Storefront(tenant, id, slug, StorefrontSettings.State.DRAFT, 0);
    }

    @Transactional
    public Storefront publish(VerifiedActor actor, UUID tenant, UUID storefront,
                              long expectedVersion) {
        return transition(actor, tenant, storefront, expectedVersion,
                StorefrontSettings.State.PUBLISHED);
    }

    @Transactional
    public Storefront pause(VerifiedActor actor, UUID tenant, UUID storefront,
                            long expectedVersion) {
        return transition(actor, tenant, storefront, expectedVersion,
                StorefrontSettings.State.PAUSED);
    }

    @Transactional
    public Storefront get(VerifiedActor actor, UUID tenant, UUID storefront) {
        read(actor, tenant);
        return fetch(tenant, storefront, false);
    }

    private Storefront transition(VerifiedActor actor, UUID tenant, UUID id,
                                  long expectedVersion, StorefrontSettings.State target) {
        UUID user = admit(actor, tenant, DomainPermission.STOREFRONT_OFFER_PUBLISH);
        if (id == null || expectedVersion < 0)
            throw new IllegalArgumentException("Storefront and expected version required");
        Storefront current = fetch(tenant, id, true);
        if (current.rowVersion() != expectedVersion)
            throw new IllegalStateException("STALE_STOREFRONT_VERSION");
        if (current.state() == target) return current; // fresh-version no-op, no audit
        boolean legal = (target == StorefrontSettings.State.PUBLISHED
                && (current.state() == StorefrontSettings.State.DRAFT
                    || current.state() == StorefrontSettings.State.PAUSED))
                || (target == StorefrontSettings.State.PAUSED
                    && current.state() == StorefrontSettings.State.PUBLISHED);
        if (!legal) throw new IllegalStateException("Invalid storefront transition");
        int updated = jdbc.update("""
                UPDATE crm.store_front SET state=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND row_version=? AND state=?
                """, target.name(), tenant, id, expectedVersion, current.state().name());
        if (updated != 1) throw new IllegalStateException("Concurrent storefront transition");
        audit(tenant, user, id, target == StorefrontSettings.State.PAUSED
                ? "STOREFRONT_PAUSED" : "STOREFRONT_PUBLISHED");
        return new Storefront(tenant, id, current.slug(), target, expectedVersion + 1);
    }

    private Storefront fetch(UUID tenant, UUID id, boolean lock) {
        if (tenant == null || id == null)
            throw new IllegalArgumentException("Storefront identity required");
        String sql = """
                SELECT tenant_id,id,slug,state,row_version FROM crm.store_front
                WHERE tenant_id=? AND id=?
                """ + (lock ? " FOR UPDATE" : "");
        List<Storefront> rows = jdbc.query(sql, (rs, n) -> new Storefront(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), StorefrontSettings.State.valueOf(rs.getString(4)),
                rs.getLong(5)), tenant, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Storefront not found in tenant");
        return rows.getFirst();
    }

    private UUID admit(VerifiedActor actor, UUID tenant, DomainPermission permission) {
        if (actor == null || tenant == null)
            throw new SecurityException("Verified actor and selected tenant required");
        authorization.requireForCommand(actor.userId(), tenant, permission);
        return actor.userId();
    }

    private void read(VerifiedActor actor, UUID tenant) {
        if (actor == null || tenant == null)
            throw new SecurityException("Verified actor and selected tenant required");
        authorization.requireForCommand(actor.userId(), tenant, DomainPermission.STOREFRONT_OFFER_MANAGE);
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
