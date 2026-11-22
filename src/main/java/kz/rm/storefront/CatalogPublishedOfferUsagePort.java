package kz.rm.storefront;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * STOREFRONT owns the sale-offer publication fact. Used by CATALOG archive
 * after locking cat_item. This MUST remain an un-locked, tenant-scoped read:
 * offer writers take storefront -> offer -> catalog locks in that order.
 */
@Service
public class CatalogPublishedOfferUsagePort {
    private final JdbcTemplate jdbc;

    public CatalogPublishedOfferUsagePort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean hasPublishedOffer(UUID tenantId, UUID catalogItemId) {
        if (tenantId == null || catalogItemId == null)
            throw new IllegalArgumentException("Tenant and catalog item required");
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT 1 FROM crm.store_offer
                WHERE tenant_id=? AND catalog_item_id=? AND state='PUBLISHED'
                LIMIT 1
                """, rs -> rs.next(), tenantId, catalogItemId));
    }
}
