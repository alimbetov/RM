package kz.rm.fx;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class NbrkTradingCalendarPolicyTest {
    private static final String URL="https://kase.kz/ru/members/trading-and-clearing-regulation?calendar=2026";
    private static final String HASH="a".repeat(64);
    private NbrkTradingCalendarPolicy.Day day(String date,String instrument,
                                  NbrkTradingCalendarPolicy.TradingStatus status) {
        return new NbrkTradingCalendarPolicy.Day(LocalDate.parse(date),instrument,status,URL,HASH);
    }

    @Test void weekendAndVerifiedHolidayDoNotCountAsTradingSessions() {
        var days=Map.of(
            LocalDate.parse("2026-10-24"),day("2026-10-24","USD/KZT",NbrkTradingCalendarPolicy.TradingStatus.CLOSED),
            LocalDate.parse("2026-10-25"),day("2026-10-25","USD/KZT",NbrkTradingCalendarPolicy.TradingStatus.CLOSED),
            LocalDate.parse("2026-10-26"),day("2026-10-26","USD/KZT",NbrkTradingCalendarPolicy.TradingStatus.CLOSED),
            LocalDate.parse("2026-10-27"),day("2026-10-27","USD/KZT",NbrkTradingCalendarPolicy.TradingStatus.TRADING));
        var result=NbrkTradingCalendarPolicy.assess(LocalDate.parse("2026-10-23"),
            LocalDate.parse("2026-10-27"),"USD/KZT",days);
        assertThat(result.elapsedCalendarDays()).isEqualTo(4);
        assertThat(result.observedTradingDays()).isEqualTo(1);
    }

    @Test void incompleteCalendarNeverAssumesWeekendOrHoliday() {
        assertThatThrownBy(()->NbrkTradingCalendarPolicy.assess(
            LocalDate.parse("2026-10-23"),LocalDate.parse("2026-10-26"),
            "USD/KZT",Map.of())).hasMessage("CUR_R2B_CALENDAR_EVIDENCE_MISSING");
    }

    @Test void instrumentMismatchIsRejected() {
        assertThatThrownBy(()->NbrkTradingCalendarPolicy.assess(
            LocalDate.parse("2026-11-03"),LocalDate.parse("2026-11-04"),
            "USD/KZT",Map.of(LocalDate.parse("2026-11-04"),
                day("2026-11-04","RUB/KZT",NbrkTradingCalendarPolicy.TradingStatus.CLOSED))))
            .hasMessage("CUR_R2B_CALENDAR_EVIDENCE_MISSING");
    }

    @Test void invalidSourceProofCannotEnterCalendar() {
        assertThatThrownBy(()->new NbrkTradingCalendarPolicy.Day(
            LocalDate.parse("2026-10-26"),"USD/KZT",
            NbrkTradingCalendarPolicy.TradingStatus.CLOSED,
            "https://example.org/unverified","0".repeat(64)))
            .hasMessage("CUR_R2B_CALENDAR_PROVENANCE_INVALID");
    }
}
