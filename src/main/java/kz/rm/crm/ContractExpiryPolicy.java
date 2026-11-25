package kz.rm.crm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Pure as-of calculation; no mutable EXPIRED value stored in PostgreSQL. */
public final class ContractExpiryPolicy {
    public enum Indicator { NOT_STARTED, OPEN_ENDED, VALID, EXPIRING_SOON, EXPIRED, CLOSED, CANCELLED, DRAFT }
    private ContractExpiryPolicy() {}
    public static Indicator evaluate(CustomerContractEntity.Status status, LocalDate startsOn,
                                     LocalDate endsOn, LocalDate asOf, int warningDays) {
        Objects.requireNonNull(status);
        Objects.requireNonNull(startsOn);
        Objects.requireNonNull(asOf);
        if (warningDays < 0 || warningDays > 365) throw new IllegalArgumentException("warningDays must be 0..365");
        if (status == CustomerContractEntity.Status.DRAFT) return Indicator.DRAFT;
        if (status == CustomerContractEntity.Status.CLOSED) return Indicator.CLOSED;
        if (status == CustomerContractEntity.Status.CANCELLED) return Indicator.CANCELLED;
        if (asOf.isBefore(startsOn)) return Indicator.NOT_STARTED;
        if (endsOn == null) return Indicator.OPEN_ENDED;
        if (asOf.isAfter(endsOn)) return Indicator.EXPIRED;
        return ChronoUnit.DAYS.between(asOf, endsOn) <= warningDays
                ? Indicator.EXPIRING_SOON : Indicator.VALID;
    }
}
