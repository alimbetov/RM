package kz.rm.crm.dto;

import java.time.Instant;
import java.util.UUID;

/** PII: may be returned only after CRM_PII_READ admission. */
public record ContactSummary(UUID id,UUID customerId,boolean primary,String status,
                             String name,String email,String phone,Instant createdAt,
                             long rowVersion) {}
