package kz.rm.storefront;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * QR-01 anonymous read boundary. A random public reference is not a grant:
 * every read verifies the current workspace, storefront, offer and PRODUCT
 * publication. Media and region stock stay UNKNOWN until QR-02 qualification.
 */
@Service
public class PublishedOfferCardQuery {
    public record PublicCard(String publicRef, String storeSlug, String sku,
                             String title, String description, String locale,
                             String sourceLocale, BigDecimal unitPrice,
                             String currency, long offerRowVersion,
                             String availabilityBand) {}

    private final JdbcTemplate jdbc;

    public PublishedOfferCardQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PublicCard findPublished(String publicRef, String locale) {
        if (publicRef == null || !publicRef.matches("[A-Za-z0-9_-]{32}"))
            throw new IllegalArgumentException("QR_UNAVAILABLE");
        if (locale == null || !List.of("kk","ru","en","zh-Hans").contains(locale))
            throw new IllegalArgumentException("Unsupported locale");
        List<PublicCard> rows = jdbc.query("""
                SELECT q.public_ref, sf.slug, c.sku,
                       COALESCE(trans.name, src.name, o.title),
                       COALESCE(trans.description, src.description),
                       c.source_locale, trans.locale, src.locale,
                       o.unit_price, o.currency, o.row_version
                FROM crm.store_offer_qr_ref q
                JOIN crm.store_offer o
                  ON o.tenant_id=q.tenant_id AND o.id=q.offer_id
                JOIN crm.store_front sf ON sf.tenant_id=q.tenant_id
                JOIN crm.cat_item c
                  ON c.tenant_id=o.tenant_id AND c.id=o.catalog_item_id
                JOIN public.iam_workspace w ON w.id=q.tenant_id
                LEFT JOIN crm.cat_item_i18n trans
                  ON trans.tenant_id=c.tenant_id AND trans.item_id=c.id
                 AND trans.locale=?
                LEFT JOIN crm.cat_item_i18n src
                  ON src.tenant_id=c.tenant_id AND src.item_id=c.id
                 AND src.locale=c.source_locale
                WHERE q.public_ref=? AND q.state='ACTIVE'
                  AND q.active_offer_id=q.offer_id
                  AND sf.state='PUBLISHED' AND o.state='PUBLISHED'
                  AND c.kind='PRODUCT' AND c.state='PUBLISHED'
                  AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                """, (rs,n) -> new PublicCard(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getString(7)!=null ? rs.getString(7)
                    : rs.getString(8)!=null ? rs.getString(8) : rs.getString(6),
                rs.getString(6),rs.getBigDecimal(9),rs.getString(10),
                rs.getLong(11),"UNKNOWN"), locale, publicRef);
        if (rows.isEmpty()) throw new IllegalArgumentException("QR_UNAVAILABLE");
        return rows.getFirst();
    }
}
