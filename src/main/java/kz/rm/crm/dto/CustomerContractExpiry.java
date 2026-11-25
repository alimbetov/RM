package kz.rm.crm.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A tenant-scoped, as-of customer contract projection. This is not the stored
 * contract state and does not modify the legal status of an expired agreement.
 */
public record CustomerContractExpiry(UUID contractId, LocalDate endsOn, Long daysRemaining,
                                     Indicator indicator, long activeContractCount,
                                     long expiredContractCount, LocalDate asOfDate) {
    public enum Indicator {
        NO_CONTRACT, NO_ACTIVE_CONTRACT, NOT_STARTED, OPEN_ENDED,
        EXPIRED, CRITICAL, EXPIRING_SOON, ACTIVE
    }
}
