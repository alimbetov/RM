package kz.rm.storefront;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * QR-01: seller-owned stable references to existing published PRODUCT offers.
 *
 * A public reference is not an authentication credential. QR creation does not
 * create a catalog item, price, stock balance or order. Lock acquisition follows
 * existing seller commands: IAM -> storefront -> offer -> catalog item.
 */
@Service
public class PublishedOfferQrService {
    public enum State { ACTIVE, REVOKED }

    public record Reference(UUID offerId, String publicRef, int generation,
                            State state, long rowVersion, Instant issuedAt) {}

    private record OfferState(long version, String state, UUID catalogItemId) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public PublishedOfferQrService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    /**
     * Natural-key idempotency: concurrent issuance for one published offer
     * returns the same ACTIVE reference. Existing alias survives price updates;
     * stale expected offer version is rejected (use reprint to retrieve an alias).
     */
    @Transactional
    public Reference issueOrGet(VerifiedActor actor, UUID tenant, UUID offerId,
                                long expectedOfferVersion) {
        UUID user = admit(actor, tenant);
        String storeState = lockStore(tenant);
        OfferState offer = lockOffer(tenant, offerId);
        if (offer.version() != expectedOfferVersion)
            throw new IllegalStateException("STALE_OFFER_VERSION");
        requirePublished(storeState, offer, tenant);
        Reference existing = active(tenant, offerId);
        if (existing != null) return existing;
        return insert(tenant, offerId, user);
    }

    /** Reprints the same literal reference. This is a staff-only management read. */
    @Transactional
    public Reference reprint(VerifiedActor actor, UUID tenant, UUID offerId) {
        admit(actor, tenant);
        lockStore(tenant);
        lockOffer(tenant, offerId);
        Reference existing = active(tenant, offerId);
        if (existing == null) throw new IllegalStateException("QR_NOT_ISSUED");
        return existing;
    }

    @Transactional
    public Reference revoke(VerifiedActor actor, UUID tenant, UUID offerId,
                            long expectedQrVersion) {
        UUID user = admit(actor, tenant);
        lockStore(tenant);
        lockOffer(tenant, offerId);
        Reference existing = active(tenant, offerId);
        if (existing == null) throw new IllegalStateException("QR_NOT_ISSUED");
        if (existing.rowVersion() != expectedQrVersion)
            throw new IllegalStateException("STALE_QR_VERSION");
        int changed = jdbc.update("""
                UPDATE crm.store_offer_qr_ref
                SET state='REVOKED',active_offer_id=NULL,revoked_at=?,
                    row_version=row_version+1
                WHERE tenant_id=? AND offer_id=? AND state='ACTIVE'
                  AND row_version=?
                """, Timestamp.from(Instant.now()), tenant, offerId, expectedQrVersion);
        if (changed != 1) throw new IllegalStateException("STALE_QR_VERSION");
        audit(tenant, user, offerId, "PRODUCT_QR_REVOKED");
        return new Reference(offerId, existing.publicRef(), existing.generation(),
                State.REVOKED, existing.rowVersion() + 1, existing.issuedAt());
    }

    /** After an explicit revoke, a new random alias gets the next generation. */
    @Transactional
    public Reference reissue(VerifiedActor actor, UUID tenant, UUID offerId,
                             long expectedOfferVersion) {
        UUID user = admit(actor, tenant);
        String storeState = lockStore(tenant);
        OfferState offer = lockOffer(tenant, offerId);
        if (offer.version() != expectedOfferVersion)
            throw new IllegalStateException("STALE_OFFER_VERSION");
        requirePublished(storeState, offer, tenant);
        if (active(tenant, offerId) != null)
            throw new IllegalStateException("QR_ALREADY_ACTIVE");
        Integer last = jdbc.queryForObject("""
                SELECT COALESCE(MAX(generation),0)
                FROM crm.store_offer_qr_ref WHERE tenant_id=? AND offer_id=?
                """, Integer.class, tenant, offerId);
        if (last == null || last == 0) throw new IllegalStateException("QR_NOT_ISSUED");
        return insert(tenant, offerId, user);
    }


    /** Explicit durable request-key API. The legacy no-key methods remain
     * for internal compatibility, but future adapters MUST use these variants.
     * A successful retry returns the original command-time result snapshot even
     * when the associated public reference has since been revoked. */
    @Transactional
    public Reference issueOrGet(VerifiedActor actor, UUID tenant, UUID offerId,
                                long expectedOfferVersion, String requestKey) {
        return executeRecorded(actor, tenant, offerId, "ISSUE",
                expectedOfferVersion, requestKey,
                () -> issueOrGet(actor, tenant, offerId, expectedOfferVersion));
    }

    @Transactional
    public Reference revoke(VerifiedActor actor, UUID tenant, UUID offerId,
                            long expectedQrVersion, String requestKey) {
        return executeRecorded(actor, tenant, offerId, "REVOKE",
                expectedQrVersion, requestKey,
                () -> revoke(actor, tenant, offerId, expectedQrVersion));
    }

    @Transactional
    public Reference reissue(VerifiedActor actor, UUID tenant, UUID offerId,
                             long expectedOfferVersion, String requestKey) {
        return executeRecorded(actor, tenant, offerId, "REISSUE",
                expectedOfferVersion, requestKey,
                () -> reissue(actor, tenant, offerId, expectedOfferVersion));
    }

    private record Recorded(String hash, UUID offerId, UUID actorId,
                            String action, Reference reference) {}

    private Reference executeRecorded(VerifiedActor actor, UUID tenant, UUID offerId,
                                      String action, long expectedVersion,
                                      String requestKey,
                                      java.util.function.Supplier<Reference> execute) {
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("QR_REQUEST_KEY_INVALID");
        if (offerId == null || expectedVersion < 0)
            throw new IllegalArgumentException("QR_REQUEST_INVALID");
        UUID user = admit(actor, tenant);
        // All QR mutations of this seller already serialize on this row.
        // Lock BEFORE checking a receipt, BEFORE locking an offer, and BEFORE
        // any alias insertion; no second lock order is introduced.
        lockStore(tenant);
        String fingerprint = fingerprint(tenant, user, offerId, action, expectedVersion);
        Recorded receipt = recorded(tenant, requestKey);
        if (receipt != null) {
            if (!fingerprint.equals(receipt.hash())
                    || !offerId.equals(receipt.offerId())
                    || !user.equals(receipt.actorId())
                    || !action.equals(receipt.action()))
                throw new IllegalStateException("QR_IDEMPOTENCY_CONFLICT");
            return receipt.reference();
        }
        Reference result = execute.get();
        UUID refId = jdbc.queryForObject("""
                SELECT id FROM crm.store_offer_qr_ref
                WHERE tenant_id=? AND public_ref=?
                """, UUID.class, tenant, result.publicRef());
        if (refId == null) throw new IllegalStateException("QR_RECEIPT_SOURCE_MISSING");
        jdbc.update("""
                INSERT INTO crm.store_offer_qr_receipt
                  (tenant_id,request_key,offer_id,actor_id,action_code,request_sha256,
                   qr_ref_id,result_state,result_row_version,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, tenant, requestKey, offerId, user, action, fingerprint, refId,
                result.state().name(), result.rowVersion(), Timestamp.from(Instant.now()));
        return result;
    }

    private Recorded recorded(UUID tenant, String requestKey) {
        List<Recorded> matches = jdbc.query("""
                SELECT r.request_sha256,r.offer_id,r.actor_id,r.action_code,
                       q.public_ref,q.generation,r.result_state,
                       r.result_row_version,q.issued_at
                FROM crm.store_offer_qr_receipt r
                JOIN crm.store_offer_qr_ref q
                  ON q.tenant_id=r.tenant_id AND q.id=r.qr_ref_id
                WHERE r.tenant_id=? AND r.request_key=?
                """, (rs,n) -> new Recorded(rs.getString(1),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), new Reference(rs.getObject(2, UUID.class),
                rs.getString(5), rs.getInt(6), State.valueOf(rs.getString(7)),
                rs.getLong(8), rs.getTimestamp(9).toInstant())), tenant, requestKey);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static String fingerprint(UUID tenant, UUID actor, UUID offer,
                                      String action, long version) {
        String canonical = "RM/PRODUCT_OFFER/QR-R1B|" + tenant + "|" + actor + "|"
                + offer + "|" + action + "|" + version;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private Reference insert(UUID tenant, UUID offerId, UUID actor) {
        Integer last = jdbc.queryForObject("""
                SELECT COALESCE(MAX(generation),0)
                FROM crm.store_offer_qr_ref WHERE tenant_id=? AND offer_id=?
                """, Integer.class, tenant, offerId);
        int generation = (last == null ? 0 : last) + 1;
        byte[] bytes = new byte[24]; // 192 bits of unpredictable public reference.
        RANDOM.nextBytes(bytes);
        String ref = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // PostgreSQL TIMESTAMPTZ stores microseconds; the first result must be
        // byte-for-byte identical to an idempotent DB-backed replay.
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO crm.store_offer_qr_ref
                  (tenant_id,id,offer_id,active_offer_id,public_ref,generation,
                   state,row_version,issued_at,issued_by)
                VALUES (?,?,?,?,?,?,'ACTIVE',0,?,?)
                """, tenant, UUID.randomUUID(), offerId, offerId, ref, generation,
                Timestamp.from(now), actor);
        audit(tenant, actor, offerId, "PRODUCT_QR_ISSUED");
        return new Reference(offerId, ref, generation, State.ACTIVE, 0L, now);
    }

    private UUID admit(VerifiedActor actor, UUID tenant) {
        if (actor == null || tenant == null)
            throw new SecurityException("Verified seller and tenant required");
        authorization.requireForCommand(actor.userId(), tenant,
                DomainPermission.STOREFRONT_OFFER_MANAGE);
        return actor.userId();
    }

    private String lockStore(UUID tenant) {
        String state = jdbc.query("""
                SELECT state FROM crm.store_front WHERE tenant_id=? FOR UPDATE
                """, rs -> rs.next() ? rs.getString(1) : null, tenant);
        if (state == null) throw new IllegalArgumentException("Storefront not found");
        return state;
    }

    private OfferState lockOffer(UUID tenant, UUID offer) {
        if (offer == null) throw new IllegalArgumentException("Offer required");
        OfferState row = jdbc.query("""
                SELECT row_version,state,catalog_item_id
                FROM crm.store_offer WHERE tenant_id=? AND id=? FOR UPDATE
                """, rs -> rs.next()
                ? new OfferState(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, UUID.class)) : null, tenant, offer);
        if (row == null) throw new IllegalArgumentException("Offer not found in tenant");
        return row;
    }

    private void requirePublished(String storeState, OfferState offer, UUID tenant) {
        if (!"PUBLISHED".equals(storeState) || !"PUBLISHED".equals(offer.state())
                || offer.catalogItemId() == null)
            throw new IllegalStateException("QR_OFFER_NOT_PUBLISHED");
        String product = jdbc.query("""
                SELECT kind || ':' || state FROM crm.cat_item
                WHERE tenant_id=? AND id=? FOR UPDATE
                """, rs -> rs.next() ? rs.getString(1) : null,
                tenant, offer.catalogItemId());
        if (!"PRODUCT:PUBLISHED".equals(product))
            throw new IllegalStateException("QR_PRODUCT_NOT_PUBLISHED");
    }

    private Reference active(UUID tenant, UUID offerId) {
        List<Reference> refs = jdbc.query("""
                SELECT public_ref,generation,state,row_version,issued_at
                FROM crm.store_offer_qr_ref
                WHERE tenant_id=? AND active_offer_id=? AND state='ACTIVE'
                """, (rs,n) -> new Reference(offerId,rs.getString(1),rs.getInt(2),
                State.valueOf(rs.getString(3)),rs.getLong(4),
                rs.getTimestamp(5).toInstant()), tenant, offerId);
        return refs.isEmpty() ? null : refs.getFirst();
    }

    private void audit(UUID tenant, UUID actor, UUID offerId, String action) {
        jdbc.update("""
                INSERT INTO crm.audit_event
                  (tenant_id,id,actor_user_id,entity_id,action_code,occurred_at)
                VALUES (?,?,?,?,?,?)
                """, tenant, UUID.randomUUID(), actor, offerId, action,
                Timestamp.from(Instant.now()));
    }
}
