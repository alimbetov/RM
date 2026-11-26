package kz.rm.intelligence;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/** Pure calendar computation; no persistence or implicit timezone. */
public final class PeriodResolver {
    public enum Code { D,W,DEC,M,Q,H,Y }
    public record Policy(ZoneId zone,long version) {
        public Policy {
            Objects.requireNonNull(zone);
            if (!ZoneId.getAvailableZoneIds().contains(zone.getId()) || version < 1)
                throw new IllegalArgumentException("Explicit IANA zone and positive version required");
        }
    }
    public record Period(Instant startInclusive,Instant endExclusive,String zoneId,long calendarVersion) {}
    public static Period resolve(Code code,LocalDate anchor,Policy policy) {
        Objects.requireNonNull(code); Objects.requireNonNull(anchor); Objects.requireNonNull(policy);
        LocalDate start = switch(code) {
            case D -> anchor;
            case W -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case DEC -> anchor.withDayOfMonth(anchor.getDayOfMonth() <= 10 ? 1 : anchor.getDayOfMonth() <= 20 ? 11 : 21);
            case M -> anchor.withDayOfMonth(1);
            case Q -> LocalDate.of(anchor.getYear(), ((anchor.getMonthValue()-1)/3)*3+1,1);
            case H -> LocalDate.of(anchor.getYear(),anchor.getMonthValue()<=6?1:7,1);
            case Y -> LocalDate.of(anchor.getYear(),1,1);
        };
        LocalDate end = switch(code) {
            case D -> start.plusDays(1);
            case W -> start.plusWeeks(1);
            case DEC -> start.getDayOfMonth()==21 ? start.plusMonths(1).withDayOfMonth(1) : start.plusDays(10);
            case M -> start.plusMonths(1);
            case Q -> start.plusMonths(3);
            case H -> start.plusMonths(6);
            case Y -> start.plusYears(1);
        };
        Instant from=start.atStartOfDay(policy.zone()).toInstant(), until=end.atStartOfDay(policy.zone()).toInstant();
        if (!from.isBefore(until)) throw new IllegalArgumentException("Empty calendar interval");
        return new Period(from,until,policy.zone().getId(),policy.version());
    }
}
