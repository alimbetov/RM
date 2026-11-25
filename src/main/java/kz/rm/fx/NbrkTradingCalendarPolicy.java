package kz.rm.fx;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, fail-closed evidence evaluator. This component does NOT infer official
 * NBRK rates or fetch KASE trading calendars. A trusted adapter must persist
 * immutable, instrument-specific calendar evidence with a provenance hash.
 */
public final class NbrkTradingCalendarPolicy {
    public enum TradingStatus { TRADING, CLOSED }
    public record Day(LocalDate date, String instrument, TradingStatus status,
                      String sourceUrl, String sha256) {
        public Day {
            Objects.requireNonNull(date);
            Objects.requireNonNull(instrument);
            Objects.requireNonNull(status);
            if (instrument.isBlank() || sourceUrl==null
                    || !(sourceUrl.startsWith("https://kase.kz/")
                        || sourceUrl.startsWith("https://nationalbank.kz/"))
                    || sha256==null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("CUR_R2B_CALENDAR_PROVENANCE_INVALID");
        }
    }
    public record Assessment(long elapsedCalendarDays, long observedTradingDays) {}

    private NbrkTradingCalendarPolicy() {}

    public static Assessment assess(LocalDate officialValidDate, LocalDate evaluationDate,
                                    String instrument, Map<LocalDate,Day> evidence) {
        Objects.requireNonNull(officialValidDate);
        Objects.requireNonNull(evaluationDate);
        Objects.requireNonNull(instrument);
        Objects.requireNonNull(evidence);
        if(officialValidDate.isAfter(evaluationDate))
            throw new IllegalArgumentException("CUR_R2B_FUTURE_RATE_FORBIDDEN");
        long duration=ChronoUnit.DAYS.between(officialValidDate,evaluationDate);
        if(duration>366)
            throw new IllegalStateException("CUR_R2B_CALENDAR_RANGE_EXCEEDED");
        long trading=0;
        for(LocalDate date=officialValidDate.plusDays(1);
                !date.isAfter(evaluationDate);date=date.plusDays(1)) {
            Day day=evidence.get(date);
            if(day==null || !day.date().equals(date) || !day.instrument().equals(instrument))
                throw new IllegalStateException("CUR_R2B_CALENDAR_EVIDENCE_MISSING");
            if(day.status()==TradingStatus.TRADING) trading++;
        }
        return new Assessment(duration,trading);
    }
}
