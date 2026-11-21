package kz.rm.catalog;
import java.util.UUID;
/** SKU is unique within one tenant, independent of locale/region. */
public record CatalogItem(UUID tenantId, UUID itemId, String sku, Kind kind, State state) {
    public enum Kind { PRODUCT, SERVICE }
    public enum State { DRAFT, PUBLISHED, ARCHIVED }
    public CatalogItem {
        if(tenantId==null || itemId==null || sku==null || sku.isBlank()
                || kind==null || state==null)throw new IllegalArgumentException("Invalid catalog item");
    }
    public boolean orderable() {return state==State.PUBLISHED;}
}
