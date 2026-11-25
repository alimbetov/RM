package kz.rm.intelligence;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static kz.rm.intelligence.PeriodResolver.*;
import static kz.rm.intelligence.MetricRegistry.Selection.*;

class IntelligenceB1ContractTest {
    private final Policy policy=new Policy(ZoneId.of("Europe/Berlin"),1);
    @Test void dstDaysUseCalendarNotFixedSeconds() {
        for(var date: new LocalDate[]{LocalDate.of(2026,3,29),LocalDate.of(2026,10,25)}) {
            var p=resolve(Code.D,date,policy);
            assertEquals(date.getMonthValue()==3?23:25,Duration.between(p.startInclusive(),p.endExclusive()).toHours());
        }
    }
    @Test void decadeLeapMonthAndIsoWeekBoundaries() {
        var p=resolve(Code.DEC,LocalDate.of(2024,2,29),policy);
        assertEquals(LocalDate.of(2024,2,21),p.startInclusive().atZone(policy.zone()).toLocalDate());
        assertEquals(LocalDate.of(2024,3,1),p.endExclusive().atZone(policy.zone()).toLocalDate());
        var w=resolve(Code.W,LocalDate.of(2026,1,1),policy);
        assertEquals(LocalDate.of(2025,12,29),w.startInclusive().atZone(policy.zone()).toLocalDate());
    }
    @Test void allSevenPeriodsAndVersion() {
        var anchor=LocalDate.of(2026,11,25);
        LocalDate[] starts={anchor,LocalDate.of(2026,11,23),LocalDate.of(2026,11,21),LocalDate.of(2026,11,1),LocalDate.of(2026,10,1),LocalDate.of(2026,7,1),LocalDate.of(2026,1,1)};
        LocalDate[] ends={anchor.plusDays(1),LocalDate.of(2026,11,30),LocalDate.of(2026,12,1),LocalDate.of(2026,12,1),LocalDate.of(2027,1,1),LocalDate.of(2027,1,1),LocalDate.of(2027,1,1)};
        for(var code:Code.values()) {
            var p=resolve(code,anchor,policy);
            assertEquals(starts[code.ordinal()],p.startInclusive().atZone(policy.zone()).toLocalDate());
            assertEquals(ends[code.ordinal()],p.endExclusive().atZone(policy.zone()).toLocalDate());
            assertEquals(1L,p.calendarVersion());
        }
    }
    @Test void invalidCalendarRejected() {
        assertThrows(IllegalArgumentException.class,()->new Policy(ZoneOffset.UTC,1));
        assertThrows(IllegalArgumentException.class,()->new Policy(policy.zone(),0));
    }
    @Test void registryRejectsWrongSelectionAndVersion() {
        var c=MetricRegistry.Code.CURRENT_AVAILABLE;
        assertThrows(IllegalArgumentException.class,()->MetricRegistry.require(c,1,ORDER_PERIOD));
        assertThrows(IllegalArgumentException.class,()->MetricRegistry.require(c,2,LIVE_POSITION));
        assertTrue(MetricRegistry.require(c,1,LIVE_POSITION).warehouseAssignment());
    }
    @Test void allEightMetricDefinitionsAreExplicit() {
        assertEquals(8,MetricRegistry.Code.values().length);
        for(var c:MetricRegistry.Code.values()) {
            var s=switch(c) {
                case CURRENT_AVAILABLE -> LIVE_POSITION;
                case STOCK_IN_QTY,STOCK_OUT_QTY -> MOVEMENT_PERIOD;
                default -> ORDER_PERIOD;
            };
            var d=MetricRegistry.require(c,1,s);
            assertEquals(s!=ORDER_PERIOD,d.warehouseAssignment());
            assertEquals(s==ORDER_PERIOD?"SALES_ORDER_READ":"INVENTORY_STOCK_READ",d.permission());
        }
    }
}
