package kz.rm.crm.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Typed, tenant-scoped CRM customer list filters.
 * Four-argument constructor preserves the R1 read contract.
 * Expiry range is evaluated explicitly on asOfDate, never on server wall-clock time.
 */
public record CustomerSearch(
        String type,
        String status,
        String regionZoneId,
        String companyNameContains,
        String categoryCode,
        Instant createdFrom,
        Instant createdTo,
        LocalDate asOfDate,
        Integer expiresWithinDays) {

    public CustomerSearch(String type, String status, String regionZoneId,
                          String companyNameContains) {
        this(type, status, regionZoneId, companyNameContains,
                null, null, null, null, null);
    }

    public CustomerSearch {
        if (type != null && !type.matches("PERSON|COMPANY"))
            throw new IllegalArgumentException("Invalid customer type");
        if (status != null && !status.matches("ACTIVE|ARCHIVED"))
            throw new IllegalArgumentException("Invalid customer status");
        if (regionZoneId != null && !regionZoneId.matches("[0-9]{2}"))
            throw new IllegalArgumentException("Invalid region zone");
        if (companyNameContains != null
                && (companyNameContains.isBlank() || companyNameContains.length() > 100))
            throw new IllegalArgumentException("Invalid company name filter");
        // Match CrmCategoryService's tenant-local immutable category code contract.
        if (categoryCode != null && !categoryCode.matches("[A-Z0-9][A-Z0-9_-]{0,39}"))
            throw new IllegalArgumentException("Invalid customer category code");
        if (createdFrom != null && createdTo != null
                && !createdFrom.isBefore(createdTo))
            throw new IllegalArgumentException("Customer creation interval must be half-open");
        if (expiresWithinDays != null
                && (expiresWithinDays < 0 || expiresWithinDays > 365 || asOfDate == null))
            throw new IllegalArgumentException("Expiry days must be 0..365 with explicit asOfDate");
    }
}
