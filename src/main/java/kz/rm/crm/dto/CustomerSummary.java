package kz.rm.crm.dto;

import java.time.Instant;
import java.util.UUID;

/** Public list metadata: person name is never decrypted or exposed. */
public record CustomerSummary(UUID id,String type,String companyLabel,
                              String regionZoneId,String status,Instant createdAt,
                              long rowVersion) {}
