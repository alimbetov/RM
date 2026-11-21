package kz.rm.organization;
import java.util.UUID;
/** Domain-level location identity; GEO validates KATO, ORG owns locations. */
public record OrganizationLocation(UUID tenantId, UUID locationId, String katoCode,
                                   String regionZoneId, Kind kind) {
    public enum Kind { BRANCH, WAREHOUSE, POINT_OF_SALE }
    public OrganizationLocation {
        if (tenantId==null || locationId==null || kind==null) throw new IllegalArgumentException("Location identity required");
        if (katoCode==null || !katoCode.matches("[0-9]{2,16}")
                || regionZoneId==null || !regionZoneId.matches("[0-9]{2}")
                || !katoCode.startsWith(regionZoneId)) throw new IllegalArgumentException("Invalid KATO zone");
    }
}
