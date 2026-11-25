package kz.rm.crm.dto;

import java.time.Instant;
import java.util.UUID;

/** Public list metadata: PERSON name and contact PII are never decrypted or exposed. */
public record CustomerSummary(UUID id, String type, String companyLabel,
                              String regionZoneId, String status, Instant createdAt,
                              long rowVersion, String categoryCode,
                              CustomerContractExpiry contractExpiry) {
    /** Preserve existing callers and default non-PII metadata projection. */
    public CustomerSummary(UUID id, String type, String companyLabel,
                           String regionZoneId, String status, Instant createdAt, long rowVersion) {
        this(id, type, companyLabel, regionZoneId, status, createdAt, rowVersion, null, null);
    }
}
