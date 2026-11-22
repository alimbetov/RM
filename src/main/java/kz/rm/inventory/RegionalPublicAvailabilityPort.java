package kz.rm.inventory;

import kz.rm.geo.KatoLocationPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * QR-02B: read-only regional inventory discovery for CURRENT published offers.
 *
 * Exact KATO only. Every result joins the active seller workspace, published
 * storefront, offer, PRODUCT and QR alias. Inventory truth is LIVE balance
 * (on_hand - reserved). The maximum per eligible warehouse determines whether
 * a FULL R1 single-warehouse order could fit; SUM across warehouses must NEVER
 * imply availability for one order. These are indicative public bands only.
 */
@Service
public class RegionalPublicAvailabilityPort {
    public enum Band { INDICATIVE_AVAILABLE, INSUFFICIENT_STOCK, NOT_LISTED_IN_REGION }

    public record Summary(String katoCode,int requestedQuantity,Band band,
                          Instant checkedAt) {}

    public record RegionalOffer(String publicOfferRef,String title,
                                BigDecimal unitPrice,String currency,
                                String katoCode) {}

    private record Position(long warehouses,BigDecimal maximumAvailable) {}

    private final JdbcTemplate jdbc;
    private final KatoLocationPolicy kato;

    public RegionalPublicAvailabilityPort(JdbcTemplate jdbc,KatoLocationPolicy kato){
        this.jdbc=jdbc;this.kato=kato;
    }

    @Transactional(readOnly=true)
    public Summary summary(String offerPublicRef,String katoCode,int requestedQuantity) {
        requirePublicRef(offerPublicRef);
        validate(katoCode,requestedQuantity);
        var rows=jdbc.query("""
                SELECT COUNT(DISTINCT l.id), MAX(b.on_hand - b.reserved)
                FROM crm.store_offer_qr_ref q
                JOIN crm.store_offer o
                  ON o.tenant_id=q.tenant_id AND o.id=q.offer_id
                JOIN crm.store_front sf ON sf.tenant_id=q.tenant_id
                JOIN public.iam_workspace w ON w.id=q.tenant_id
                JOIN crm.cat_item c
                  ON c.tenant_id=o.tenant_id AND c.id=o.catalog_item_id
                LEFT JOIN public.org_location l
                  ON l.workspace_id=o.tenant_id
                 AND l.location_type='WAREHOUSE' AND l.status='ACTIVE'
                 AND l.kato_code=?
                LEFT JOIN crm.inv_stock_balance b
                  ON b.tenant_id=o.tenant_id AND b.location_id=l.id
                 AND b.item_id=c.id
                WHERE q.public_ref=? AND q.state='ACTIVE'
                  AND q.active_offer_id=q.offer_id
                  AND sf.state='PUBLISHED' AND o.state='PUBLISHED'
                  AND c.kind='PRODUCT' AND c.state='PUBLISHED'
                  AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                GROUP BY q.tenant_id,q.offer_id
                """,(rs,n)->new Position(rs.getLong(1),rs.getBigDecimal(2)),
                katoCode,offerPublicRef);
        if(rows.isEmpty())throw new IllegalArgumentException("QR_UNAVAILABLE");
        Position position=rows.getFirst();
        Band band=position.warehouses()==0?Band.NOT_LISTED_IN_REGION:
                position.maximumAvailable()!=null &&
                        position.maximumAvailable().compareTo(
                                BigDecimal.valueOf(requestedQuantity))>=0
                ?Band.INDICATIVE_AVAILABLE:Band.INSUFFICIENT_STOCK;
        return new Summary(katoCode,requestedQuantity,band,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Search public PRODUCT offers by one exact KATO and integer quantity.
     * Returns only offers with a SINGLE ACTIVE warehouse able to meet qty.
     * Keyset pagination on opaque public refs; never reveal internal SKU
     * balances, tenant UUIDs, inactive warehouses or private stock.
     */
    @Transactional(readOnly=true)
    public List<RegionalOffer> search(String katoCode,String locale,int requestedQuantity,
                                      String afterPublicRef,int limit) {
        validate(katoCode,requestedQuantity);
        if(locale==null||!List.of("kk","ru","en","zh-Hans").contains(locale)
                ||limit<1||limit>50)
            throw new IllegalArgumentException("Invalid region search parameters");
        if(afterPublicRef!=null)requirePublicRef(afterPublicRef);
        return jdbc.query("""
                SELECT q.public_ref,COALESCE(trans.name,src.name,o.title),
                       o.unit_price,o.currency
                FROM crm.store_offer_qr_ref q
                JOIN crm.store_offer o
                  ON o.tenant_id=q.tenant_id AND o.id=q.offer_id
                JOIN crm.store_front sf ON sf.tenant_id=q.tenant_id
                JOIN public.iam_workspace w ON w.id=q.tenant_id
                JOIN crm.cat_item c
                  ON c.tenant_id=o.tenant_id AND c.id=o.catalog_item_id
                LEFT JOIN crm.cat_item_i18n trans
                  ON trans.tenant_id=c.tenant_id AND trans.item_id=c.id
                 AND trans.locale=?
                LEFT JOIN crm.cat_item_i18n src
                  ON src.tenant_id=c.tenant_id AND src.item_id=c.id
                 AND src.locale=c.source_locale
                WHERE q.state='ACTIVE' AND q.active_offer_id=q.offer_id
                  AND q.public_ref>?
                  AND sf.state='PUBLISHED' AND o.state='PUBLISHED'
                  AND c.kind='PRODUCT' AND c.state='PUBLISHED'
                  AND w.kind='ORGANIZATION' AND w.status='ACTIVE'
                  AND EXISTS (
                    SELECT 1 FROM public.org_location l
                    JOIN crm.inv_stock_balance b
                      ON b.tenant_id=l.workspace_id AND b.location_id=l.id
                     AND b.item_id=c.id
                    WHERE l.workspace_id=q.tenant_id
                      AND l.location_type='WAREHOUSE' AND l.status='ACTIVE'
                      AND l.kato_code=? AND (b.on_hand-b.reserved)>=?
                  )
                ORDER BY q.public_ref ASC
                LIMIT ?
                """,(rs,n)->new RegionalOffer(rs.getString(1),rs.getString(2),
                rs.getBigDecimal(3),rs.getString(4),katoCode),
                locale,afterPublicRef==null?"":afterPublicRef,
                katoCode,BigDecimal.valueOf(requestedQuantity),limit);
    }

    private void validate(String katoCode,int qty) {
        if(qty<1||qty>1000)throw new IllegalArgumentException("QTY_INVALID");
        try {
            kato.requireOrganizationLocationEligible(katoCode);
        } catch(IllegalArgumentException invalid) {
            throw new IllegalArgumentException("KATO_UNVERIFIED",invalid);
        }
    }
    private static void requirePublicRef(String ref) {
        if(ref==null||!ref.matches("[A-Za-z0-9_-]{32}"))
            throw new IllegalArgumentException("QR_UNAVAILABLE");
    }
}
